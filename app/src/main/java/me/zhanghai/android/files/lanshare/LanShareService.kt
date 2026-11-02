package me.zhanghai.android.files.lanshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.getLocalAddress
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.vault.VaultSession
import me.zhanghai.android.files.webdavserver.WebDavServer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executors

class LanShareService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverLock = Any()
    private var server: WebDavServer? = null
    private var kernel: LanShareKernel? = null
    private var preparation: Preparation? = null
    private var sharedCache: File? = null
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private lateinit var wakeWifiLock: WakeWifiLock
    private val vaultLockListener: () -> Unit = {
        if (!closed) {
            val revokedGeneration = revoke()
            val finish = Runnable {
                if (!closed && generation == revokedGeneration) {
                    state.value = State(null, emptyList())
                    stopSelf()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) finish.run() else mainHandler.post(finish)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeWifiLock = WakeWifiLock(LanShareService::class.java.simpleName)
        VaultSession.addLockListener(vaultLockListener)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("lan_share", getString(R.string.fw_lan_share), NotificationManager.IMPORTANCE_LOW))
        }
        // A killed process has no live guest server; remove only our abandoned URI snapshots.
        executor.execute {
            cacheDir.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("lan-share-[0-9a-fA-F-]{36}")) }
                ?.forEach { it.deleteRecursively() }
        }

    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            revoke()
            state.value = State(null, emptyList())
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RENEW_CODE) {
            val current = state.value
            if (current?.url != null && current.url == intent.getStringExtra(EXTRA_EXPECTED_URL)) {
                val random = SecureRandom()
                var code: String
                do { code = random.nextInt(1_000_000).toString().padStart(6, '0') } while (code == current.accessCode)
                val renewed = synchronized(serverLock) { kernel?.takeIf { it.active }?.renewManualAccess(code) == true }
                if (renewed) state.value = current.copy(accessCode = code, accessCodeExpiresAt = Long.MAX_VALUE)
            }
            if (kernel == null && state.value?.starting != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CLIPBOARD) {
            val current = state.value
            if (current?.url != null && current.url == intent.getStringExtra(EXTRA_EXPECTED_URL)) {
                val snapshot = intent.getStringExtra(EXTRA_CLIPBOARD)?.takeIf { it.length <= MAX_CLIPBOARD_LENGTH }
                synchronized(serverLock) { kernel?.takeIf { it.active }?.clipboardText = snapshot }
                state.value = current.copy(clipboardText = snapshot)
            }
            if (kernel == null && !state.value!!.starting) stopSelf(startId)
            return START_NOT_STICKY
        }
        val paths = intent?.extraPathList.orEmpty()
        @Suppress("DEPRECATION")
        val uris = intent?.getParcelableArrayListExtra<Uri>(EXTRA_URIS).orEmpty().distinct()
        val snapshot = intent?.getStringExtra(EXTRA_CLIPBOARD)?.takeIf { it.isNotEmpty() && it.length <= MAX_CLIPBOARD_LENGTH }
        val global = intent != null && intent.action == ACTION_START && intent.getBooleanExtra(EXTRA_GLOBAL, false)
        // Only the private hub can explicitly request the fixed phone-storage root. An empty
        // scoped/external payload must never silently expand to phone-wide access.
        if ((intent?.action != ACTION_START && paths.isEmpty()) ||
            (!global && paths.isEmpty() && uris.isEmpty() && snapshot == null) ||
            (global && (paths.isNotEmpty() || uris.isNotEmpty() || snapshot != null)) ||
            uris.size > MAX_IMPORTED_FILES || uris.any { it.scheme != "content" }) {
            if (kernel == null && state.value?.starting != true) {
                state.value = State(null, emptyList(), getString(R.string.fw_lan_prepare_failed))
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, LanShareActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, LanShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(5, NotificationCompat.Builder(this, "lan_share")
            .setSmallIcon(R.drawable.notification_icon).setContentTitle(getString(R.string.fw_lan_share))
            .setContentText(getString(if (global) R.string.fw_lan_global_storage else if (paths.isEmpty() && uris.isEmpty()) R.string.fw_lan_clipboard_title else R.string.fw_lan_preparing))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.stop), stop).build())
        val run = revoke()
        val initialNames = if (global) listOf(getString(R.string.fw_lan_global_storage)) else
            paths.map { it.fileName?.toString() ?: it.toString() } +
                if (uris.isEmpty()) emptyList() else listOf(getString(R.string.fw_lan_importing, uris.size))
        state.value = State(null, initialNames, clipboardText = snapshot, starting = true, global = global)
        try {
            synchronized(serverLock) { if (!closed && generation == run) wakeWifiLock.isAcquired = true }
        } catch (error: Exception) {
            fail(run, error)
            return START_NOT_STICKY
        }
        executor.execute {
            var staged: Preparation? = null
            var installed = false
            try {
                val address = InetAddress::class.getLocalAddress() ?: error(getString(R.string.fw_lan_no_network))
                val imported = if (uris.isEmpty()) emptyList() else {
                    val nextPreparation = Preparation(File(cacheDir, "lan-share-${java.util.UUID.randomUUID()}"))
                    staged = nextPreparation
                    synchronized(serverLock) {
                        if (closed || generation != run) return@execute
                        preparation = nextPreparation
                    }
                    check(nextPreparation.directory.mkdir()) { getString(R.string.fw_lan_prepare_failed) }
                    prepareFiles(uris, nextPreparation)
                }
                val storage = Environment.getExternalStorageDirectory()
                if (global) requireGlobalStorageAccess(storage)
                val selected = if (global) listOf(Paths.get(storage.absolutePath)) else paths + imported
                val excludedPaths = listOf("Android/data", "Android/obb").map { Paths.get(File(storage, it).absolutePath) }
                val random = SecureRandom()
                val token = Base64.encodeToString(ByteArray(16).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val code = random.nextInt(1_000_000).toString().padStart(6, '0')
                val next = LanShareKernel(token, LanShareBackend(selected,
                    rootLabel = if (global) getString(R.string.fw_lan_global_storage) else null,
                    flattenSingleRoot = global, excludedPaths = excludedPaths), LanShareManualAccess(code, SystemClock::elapsedRealtimeNanos)).apply { clipboardText = snapshot }
                val listener = WebDavServer(0, next::handle)
                val port = synchronized(serverLock) {
                    if (closed || generation != run) { next.active = false; listener.stop(); return@execute }
                    listener.start()
                    kernel = next
                    server = listener
                    sharedCache = staged?.directory
                    preparation = null
                    installed = true
                    listener.localPort
                }
                listener.onError = { error -> mainHandler.post { fail(run, error) } }
                val host = address.hostAddress!!.let { if (':' in it) "[$it]" else it }
                val root = "http://$host:$port/"
                val names = if (global) listOf(getString(R.string.fw_lan_global_storage)) else
                    selected.map { it.fileName?.toString() ?: it.toString() }
                mainHandler.post {
                    if (!closed && generation == run) state.value = State("$root$token/", names,
                        clipboardText = snapshot, accessAddress = root, accessCode = code, accessCodeExpiresAt = Long.MAX_VALUE, global = global)
                }
            } catch (error: Exception) {
                mainHandler.post { fail(run, error) }
            } catch (error: LinkageError) {
                // A provider/native adapter that cannot initialize must end only this share.
                val cause = error.cause ?: error
                val failure = java.io.IOException("${getString(R.string.fw_lan_prepare_failed)}: ${cause.javaClass.simpleName}: ${cause.localizedMessage}", error)
                mainHandler.post { fail(run, failure) }
            } finally {
                staged?.cancel()
                if (!installed) staged?.directory?.deleteRecursively()
            }
        }
        return START_NOT_STICKY
    }
    private fun requireGlobalStorageAccess(storage: File) {
        val granted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted || !storage.isDirectory || storage.list() == null) {
            throw java.io.IOException(getString(R.string.fw_lan_global_permission_required))
        }
    }
    private fun fail(run: Long, error: Exception) {
        if (!closed && generation == run) {
            revoke()
            state.value = State(null, emptyList(), error.localizedMessage ?: error.javaClass.simpleName)
            stopSelf()
        }
    }
    /** Snapshot granted provider streams before publishing links, so replacing the system
     * clipboard or leaving the source activity cannot break a running share. */
    private fun prepareFiles(uris: List<Uri>, task: Preparation): List<Path> {
        var total = 0L
        val names = hashSetOf<String>()
        return uris.mapIndexed { index, uri ->
            task.signal.throwIfCanceled()
            var name: String? = null
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, task.signal)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0)
                    if (!it.isNull(1) && it.getLong(1) > MAX_IMPORTED_BYTES - total) error(getString(R.string.fw_lan_import_limit))
                }
            }
            task.signal.throwIfCanceled()
            val mimeType = contentResolver.getType(uri)
            task.signal.throwIfCanceled()
            val unique = LanShareImportNames.choose(name, mimeType, index + 1, names)
            val target = File(task.directory, unique)
            task.signal.throwIfCanceled()
            contentResolver.openAssetFileDescriptor(uri, "r", task.signal)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    synchronized(serverLock) {
                        task.signal.throwIfCanceled()
                        task.input = input
                    }
                    target.outputStream().use { output ->
                        synchronized(serverLock) { task.signal.throwIfCanceled(); task.output = output }
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            task.signal.throwIfCanceled()
                            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total + count > MAX_IMPORTED_BYTES || cacheDir.usableSpace < MIN_RESERVED_BYTES + count) error(getString(R.string.fw_lan_import_limit))
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                }
            } ?: error(getString(R.string.fw_lan_prepare_failed))
            synchronized(serverLock) { task.input = null; task.output = null }
            Paths.get(target.absolutePath)
        }
    }
    private class Preparation(val directory: File) {
        val signal = CancellationSignal()
        var input: InputStream? = null
        var output: OutputStream? = null
        fun cancel() {
            signal.cancel()
            try { input?.close() } catch (_: Exception) { }
            try { output?.close() } catch (_: Exception) { }
        }
    }
    private fun revoke(): Long {
        return synchronized(serverLock) {
            ++generation
            kernel?.apply { active = false; clipboardText = null }
            kernel = null
            server?.stop()
            server = null
            preparation?.apply { cancel(); directory.deleteRecursively() }
            preparation = null
            sharedCache?.deleteRecursively()
            sharedCache = null
            if (::wakeWifiLock.isInitialized) wakeWifiLock.isAcquired = false
            generation
        }
    }
    override fun onDestroy() {
        closed = true
        VaultSession.removeLockListener(vaultLockListener)
        revoke()
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        if (state.value?.error == null) state.value = State(null, emptyList())
        super.onDestroy()
    }
    data class State(val url: String?, val names: List<String>, val error: String? = null,
        val clipboardText: String? = null, val starting: Boolean = false,
        val accessAddress: String? = null, val accessCode: String? = null,
        /** Compatibility field: active codes have no time limit, represented by Long.MAX_VALUE. */
        val accessCodeExpiresAt: Long = 0L, val global: Boolean = false) {
        /** Convenient hand-entry/share link; QR can keep using the independent strong URL. */
        val directAccessUrl: String?
            get() = if (accessAddress != null && accessCode != null) "$accessAddress?code=$accessCode" else null
    }
    companion object {
        const val ACTION_START = "start"
        const val ACTION_RENEW_CODE = "renew_code"
        const val ACTION_STOP = "stop"
        const val ACTION_CLIPBOARD = "clipboard"
        const val EXTRA_EXPECTED_URL = "expected_url"
        const val EXTRA_GLOBAL = "global_storage"
        const val EXTRA_CLIPBOARD = "clipboard_text"
        const val EXTRA_URIS = "shared_uris"
        const val MAX_CLIPBOARD_LENGTH = 100_000
        const val MAX_IMPORTED_FILES = 100
        private const val MAX_IMPORTED_BYTES = 1L shl 30
        private const val MIN_RESERVED_BYTES = 32L shl 20
        val state = MutableLiveData(State(null, emptyList()))
    }
}
