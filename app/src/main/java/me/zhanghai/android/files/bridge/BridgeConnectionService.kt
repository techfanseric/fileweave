package me.zhanghai.android.files.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.folderbridge.FolderBridgeReceiver
import me.zhanghai.android.files.folderbridge.FolderBridgeServer
import me.zhanghai.android.files.folderbridge.FolderBridgeService
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ambient connection to the paired Mac, alive for as long as the app is: discovery,
 * zero-touch pairing, file list refresh and clipboard synchronisation run here so that
 * sharing works without visiting any page. There is deliberately no switch for it —
 * the app running is the only condition.
 *
 * The service owns the long-lived client and its state is published as LiveData
 * for the UI; user-triggered transfers still run through their own short-lived
 * clients in the activities.
 */
class BridgeConnectionService : Service() {
    private var discovery: BridgeDiscovery? = null
    private val nearby = linkedMapOf<String, BridgeDiscovery.MacEndpoint>()
    private val retry = BridgeRetry()
    private var loop: Job? = null
    private var pairingJob: Job? = null
    private var pairingBridge: BridgeClient? = null
    private var config: BridgeConfig? = null
    private var client: BridgeClient? = null
    private var lastRefresh = 0L
    private var lastFilesRefresh = 0L
    private var lastDiscoveryRestart = 0L
    private var discoveryFailed = false
    private var connecting = AtomicBoolean(false)
    private var lastMacClipboard: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settleDiscovery = Runnable { autoConnect() }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NotificationIds.BRIDGE_CONNECTION, MacLinkNotification.build(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop?.isActive != true) {
            updateState { it.copy(phase = Phase.DISCOVERING) }
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        loop?.cancel(); loop = null
        pairingJob?.cancel(); pairingJob = null
        discovery?.stop(); discovery = null
        pairingBridge?.close(); pairingBridge = null
        client?.close(); client = null
        handler.removeCallbacks(settleDiscovery)
        ScreenshotSync.stop(application)
        _state.postValue(State())
        instance = null
        super.onDestroy()
    }

    private suspend fun runLoop() {
        config = BridgeStore.read(application)
        restartDiscovery()
        while (true) {
            delay(if (_state.valueCompat.connected) REFRESH_INTERVAL_MILLIS else 5_000L)
            if (discoveryFailed && now() - lastDiscoveryRestart >= 10_000) restartDiscovery()
            if (_state.valueCompat.connected) {
                if (now() - lastRefresh >= REFRESH_INTERVAL_MILLIS) refresh()
            } else {
                autoConnect()
            }
        }
    }

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private fun restartDiscovery() {
        discovery?.stop()
        nearby.clear()
        discoveryFailed = false
        lastDiscoveryRestart = now()
        discovery = BridgeDiscovery(application, { endpoint ->
            nearby[endpoint.name] = endpoint
            handler.removeCallbacks(settleDiscovery)
            handler.postDelayed(settleDiscovery, 1500)
        }, { name ->
            nearby.remove(name)?.let { endpoint ->
                if (config?.pin == endpoint.pin) {
                    updateState { it.copy(connected = false, phase = Phase.RETRYING) }
                    ScreenshotSync.stop(application)
                }
            }
        }, { discoveryFailed = true }).also { it.start() }
    }

    private fun autoConnect() {
        if (connecting.get() || pairingJob?.isActive == true || !retry.ready(now())) return
        val saved = config
        if (saved == null) {
            // Zero-touch pairing: only when exactly one Mac is around.
            if (nearby.size == 1) beginPairing(nearby.values.single())
            return
        }
        val matched = nearby.values.lastOrNull { it.pin == saved.pin }
        if (matched == null && nearby.size == 1) {
            beginPairing(nearby.values.single())
        } else {
            connect(matched?.config(saved.token) ?: saved)
        }
    }

    private fun connect(proposed: BridgeConfig) {
        if (!connecting.compareAndSet(false, true)) return
        scope.launch {
            val bridge = BridgeClient(proposed)
            try {
                val result = withContext(Dispatchers.IO) {
                    val info = bridge.json("status")
                    require(info.getInt("protocol") == 1)
                    val files = readFiles(bridge)
                    info.getString("name") to files
                }
                client?.close()
                client = bridge
                config = proposed
                BridgeStore.save(application, proposed)
                retry.reset()
                lastRefresh = now()
                me.zhanghai.android.files.folderbridge.PairedShares.ensureStarted(this@BridgeConnectionService)
                lastFilesRefresh = lastRefresh
                // Mac 配对更换后，旧共享证书在 Mac 侧已失去绑定：重启已在运行的共享
                // 服务，让它按新配对重新签发并广播。刚启动或已停止的服务不归这里管：
                // 前者的启动流程自己会重签，后者尊重用户关掉共享的选择。
                val folderState = me.zhanghai.android.files.folderbridge.FolderBridgeService
                    .statusLiveData.value?.state
                if (me.zhanghai.android.files.folderbridge.FolderBridgeKeys.isEnrollmentStale(application) &&
                    folderState == me.zhanghai.android.files.folderbridge.FolderBridgeService.State.RUNNING
                ) {
                    me.zhanghai.android.files.folderbridge.FolderBridgeService.stop(application)
                    handler.postDelayed({
                        me.zhanghai.android.files.folderbridge.FolderBridgeService.start(application)
                    }, 1_000)
                }
                updateState { it.copy(connected = true, phase = Phase.CONNECTED, macName = result.first, files = result.second) }
                refreshNotification()
                // 连接就绪后才开始观察新截屏；内部会先看截屏推送开关。
                ScreenshotSync.start(application)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recover(error)
            } finally {
                if (client !== bridge) bridge.close()
                connecting.set(false)
            }
        }
    }

    private suspend fun refresh() {
        val bridge = client ?: return
        lastRefresh = now()
        val refreshFiles = now() - lastFilesRefresh >= 30_000
        val previousFiles = _state.valueCompat.files
        try {
            val (files, macClipboard) = withContext(Dispatchers.IO) {
                val files = if (refreshFiles) readFiles(bridge) else previousFiles
                val text = bridge.json("clipboard").optString("text")
                files to text
            }
            if (refreshFiles) lastFilesRefresh = now()
            updateState { it.copy(files = files) }
            if (macClipboard.isNotEmpty() && macClipboard != lastMacClipboard) {
                lastMacClipboard = macClipboard
                // A background service may write the clipboard; reading it still needs
                // focus, which is why the phone -> Mac direction stays user-triggered.
                AutomaticClipboard.receive(this, macClipboard)
                updateState { it.copy(macClipboard = macClipboard) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            recover(error)
        }
    }

    private fun recover(error: Exception) {
        client?.close(); client = null
        // 连接断开同步停掉截屏观察：Mac 已不可达，继续上传只会必然失败。
        ScreenshotSync.stop(application)
        retry.failed(now())
        updateState { it.copy(connected = false, phase = Phase.RETRYING, lastError = error.message) }
        refreshNotification()
        // A verified server rejecting the old credential restarts zero-touch pairing.
        if (error is BridgeHttpException && error.status == 401) {
            BridgeStore.forget(application)
            config = null
        }
    }

    private fun beginPairing(endpoint: BridgeDiscovery.MacEndpoint) {
        if (pairingJob?.isActive == true) return
        val nonce = android.util.Base64.encodeToString(
            ByteArray(32).also(java.security.SecureRandom()::nextBytes),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        updateState { it.copy(phase = Phase.PAIRING) }
        refreshNotification()
        val bridge = BridgeClient(endpoint.config("0".repeat(43)))
        pairingBridge = bridge
        pairingJob = scope.launch {
            var approved: BridgeConfig? = null
            var requestId: String? = null
            try {
                val id = withContext(Dispatchers.IO) {
                    bridge.pairingRequest("pair/start", org.json.JSONObject().put("nonce", nonce)
                        .put("name", android.os.Build.MODEL.take(80))).getString("id")
                }
                requestId = id
                val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    delay(1000)
                    val result = withContext(Dispatchers.IO) {
                        bridge.pairingRequest("pair/poll", org.json.JSONObject().put("id", id).put("nonce", nonce))
                    }
                    if (result.getString("state") == "approved") {
                        approved = endpoint.config(result.getString("token"))
                        break
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                retry.failed(now())
            } finally {
                if (approved == null && requestId != null) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        try {
                            bridge.pairingRequest("pair/cancel",
                                org.json.JSONObject().put("id", requestId).put("nonce", nonce))
                        } catch (_: Exception) { }
                    }
                }
                bridge.close()
                if (pairingBridge === bridge) pairingBridge = null
            }
            approved?.let { proposed ->
                // The Mac's approval is the trust decision; save and connect at once.
                try {
                    BridgeStore.save(application, proposed)
                    config = proposed
                    connect(proposed)
                } catch (_: Exception) {
                    updateState { it.copy(phase = Phase.RETRYING) }
                }
            } ?: run {
                retry.failed(now())
                updateState { it.copy(phase = Phase.RETRYING) }
            }
        }
    }

    private fun readFiles(bridge: BridgeClient): List<Pair<String, Long>> {
        val array = bridge.json("files").getJSONArray("files")
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { it.getString("name") to it.getLong("size") }
    }

    private fun updateState(mutate: (State) -> State) {
        mainExecutorCompat.execute {
            _state.value = mutate(_state.valueCompat)
        }
    }

    private fun refreshNotification() {
        MacLinkNotification.notify(this)
    }

    enum class Phase { DISCOVERING, PAIRING, CONNECTED, RETRYING }

    data class State(
        val phase: Phase = Phase.DISCOVERING,
        val connected: Boolean = false,
        val macName: String? = null,
        val files: List<Pair<String, Long>> = emptyList(),
        val macClipboard: String? = null,
        val lastError: String? = null,
    )

    companion object {
        const val REFRESH_INTERVAL_MILLIS = 5_000L

        @Volatile
        private var instance: BridgeConnectionService? = null

        private val _state = MutableLiveData(State())
        val stateLiveData: LiveData<State>
            get() = _state

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, BridgeConnectionService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeConnectionService::class.java))
        }

        /**
         * 文件夹共享服务退出前台后，把合并通知重新挂回常驻连接服务的前台状态，
         * 保证通知栏里这条永远存在（见 MacLinkNotification）。
         */
        fun repostIfRunning() {
            val service = instance ?: return
            service.startForeground(NotificationIds.BRIDGE_CONNECTION, MacLinkNotification.build(service))
        }
    }
}

internal val bridgeConnectionNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "bridge_connection",
            R.string.notification_channel_bridge_connection_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_bridge_connection_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.fw_bridge_connection_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_SERVICE,
        priority = NotificationCompat.PRIORITY_LOW
    )

/**
 * 合并的常驻通知：Mac 连接与文件夹共享两个前台服务共用同一条（同一通知 id）。
 * 两个服务都在状态变化时重发完整内容（连接行 + 共享行 + 全部按钮），后写的
 * 内容也是完整的，谁写都一样；一个服务退出前台时由另一个补发（repostIfRunning），
 * 用户在通知栏始终只看到这一条。
 */
object MacLinkNotification {
    fun notify(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.notify(
            NotificationIds.BRIDGE_CONNECTION, build(context)
        )
    }

    fun build(context: Context): Notification {
        val bridge = BridgeConnectionService.stateLiveData.valueCompat
        val folder = FolderBridgeService.statusLiveData.valueCompat
        val lines = mutableListOf(
            context.getString(
                when {
                    bridge.connected -> R.string.fw_bridge_connection_connected
                    bridge.phase == BridgeConnectionService.Phase.PAIRING ->
                        R.string.fw_bridge_connection_pairing
                    bridge.phase == BridgeConnectionService.Phase.RETRYING ->
                        R.string.fw_bridge_connection_retrying
                    else -> R.string.fw_bridge_connection_finding
                },
                bridge.macName.orEmpty()
            )
        )
        when (folder.state) {
            FolderBridgeService.State.RUNNING -> lines.add(
                context.getString(
                    R.string.fw_folder_bridge_notification_running,
                    folder.port ?: FolderBridgeServer.DEFAULT_PORT
                ) + (if (folder.fileCount >= 0) context.getString(
                    R.string.fw_folder_bridge_notification_files, folder.fileCount
                ) else "") + (if (folder.nsdFailed) context.getString(
                    R.string.fw_folder_bridge_notification_nsd_failed
                ) else "")
            )
            FolderBridgeService.State.STARTING -> lines.add(
                context.getString(R.string.fw_folder_bridge_state_summary_starting)
            )
            else -> {}
        }
        val text = lines.joinToString("\n")
        val builder = bridgeConnectionNotificationTemplate.createBuilder(context)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(context, bridge.connected))
            .addAction(
                R.drawable.clipboard_icon_white_24dp,
                context.getString(R.string.fw_send_clipboard_quick),
                clipboardPendingIntent(context)
            )
        if (folder.state == FolderBridgeService.State.RUNNING ||
            folder.state == FolderBridgeService.State.STARTING) {
            builder.addAction(
                R.drawable.stop_icon_white_24dp, context.getString(R.string.stop),
                stopSharePendingIntent(context)
            )
        }
        return builder.build()
    }

    /** 连接就绪时点通知直接进发送剪贴板（沿用合并前的行为），否则进共享文件页。 */
    private fun contentPendingIntent(context: Context, connected: Boolean): PendingIntent =
        if (connected) clipboardPendingIntent(context) else PendingIntent.getActivity(
            context, BridgeActivity::class.hashCode(), BridgeActivity::class.createIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun clipboardPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, SendClipboardActivity::class.hashCode(),
        Intent(context, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopSharePendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, FolderBridgeReceiver::class.hashCode(), FolderBridgeReceiver.createIntent(),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
