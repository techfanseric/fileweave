package me.zhanghai.android.files.folderbridge

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.bridge.BridgeStore
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.Executors

/**
 * Foreground service running the folder bridge TLS server and its NSD registration.
 *
 * Startup order: chosen directory -> certificate (enrolling via the paired Mac when missing,
 * with a visible error state on failure, never silent) -> SSLContext -> server thread -> NSD.
 *
 * The service type is specialUse, mirroring FtpServerService: this share is expected to stay
 * up for days, which the dataSync six-hour quota on Android 15+ would cut off mid-use.
 *
 * Deliberately no boot-completed auto start: starting this service from BOOT_COMPLETED is
 * restricted on Android 15+, so a boot receiver would either crash or silently fail. Users
 * start the bridge from its settings screen or the Quick Settings tile.
 */
class FolderBridgeService : Service() {
    private var status = Status(State.STOPPED)
        set(value) {
            field = value
            _statusLiveData.value = value
        }

    private lateinit var wakeWifiLock: WakeWifiLock

    private lateinit var notification: FolderBridgeNotification

    private val executorService = Executors.newSingleThreadExecutor()

    private var server: FolderBridgeServer? = null

    private var nsd: FolderBridgeNsd? = null

    override fun onCreate() {
        super.onCreate()

        wakeWifiLock = WakeWifiLock(FolderBridgeService::class.java.simpleName)
        notification = FolderBridgeNotification(this)
        executeStart()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()

        executeStop()
        executorService.shutdown()
    }

    private fun executeStart() {
        if (status.state == State.STARTING || status.state == State.RUNNING) {
            return
        }
        wakeWifiLock.isAcquired = true
        notification.startForeground(getString(R.string.fw_folder_bridge_state_summary_starting))
        status = Status(State.STARTING)
        executorService.execute { doStart() }
    }

    private fun onStartError(error: String?) {
        // Failure is stated plainly in the status line and as a toast; nothing keeps running.
        status = Status(State.STOPPED, error = error)
        showToast(error ?: getString(R.string.fw_folder_bridge_error_start_unknown))
        notification.stopForeground()
        wakeWifiLock.isAcquired = false
        stopSelf()
    }

    private fun executeStop() {
        if (status.state == State.STOPPING || status.state == State.STOPPED) {
            return
        }
        status = Status(State.STOPPING)
        executorService.execute { doStop() }
        notification.stopForeground()
        wakeWifiLock.isAcquired = false
    }

    @WorkerThread
    private fun postStatus(status: Status) {
        mainExecutorCompat.execute { this.status = status }
    }

    @WorkerThread
    private fun doStart() {
        if (server != null) {
            return
        }
        val tree = FolderBridgeSettings.getTreeUri(this)
        if (tree == null) {
            mainExecutorCompat.execute {
                onStartError(getString(R.string.fw_folder_bridge_error_no_directory))
            }
            return
        }

        // Enrollment happens synchronously here: without a Mac-issued certificate the TLS server
        // cannot start, and pretending otherwise would hide the failure from the user.
        val token = try {
            BridgeStore.read(this)?.token
        } catch (_: Exception) {
            null
        }
        val fingerprint = try {
            FolderBridgeKeys.ensureCertificate(this)
        } catch (error: Exception) {
            mainExecutorCompat.execute {
                onStartError(getString(
                    R.string.fw_folder_bridge_error_issuance, error.message ?: error.toString()
                ))
            }
            return
        }
        if (token == null) {
            mainExecutorCompat.execute {
                onStartError(getString(R.string.fw_folder_bridge_error_no_pairing))
            }
            return
        }

        val sslContext = try {
            FolderBridgeKeys.serverSslContext(this)
        } catch (error: Exception) {
            mainExecutorCompat.execute {
                onStartError(getString(
                    R.string.fw_folder_bridge_error_start, error.message ?: error.toString()
                ))
            }
            return
        }
        val backend = FolderBridgeSafBackend(this, tree)
        val kernel = FolderBridgeKernel(backend, token, Build.MODEL)
        kernel.onScan = { count ->
            mainExecutorCompat.execute {
                if (this.status.state == State.RUNNING) {
                    this.status = this.status.copy(fileCount = count)
                    notification.update(notificationText(this.status))
                }
            }
        }
        val server = FolderBridgeServer(sslContext, FolderBridgeServer.DEFAULT_PORT, kernel)
        this.server = server
        // The server only reports persistent accept failure (three in a row); a server in that
        // state is dead in practice, so stop with a visible error instead of serving nothing.
        server.onError = { _ ->
            mainExecutorCompat.execute {
                if (this.status.state == State.RUNNING) {
                    onStartError(getString(R.string.fw_folder_bridge_error_start,
                        "accept failed repeatedly"))
                }
            }
        }
        try {
            server.start()
        } catch (error: Exception) {
            this.server = null
            mainExecutorCompat.execute {
                onStartError(getString(
                    R.string.fw_folder_bridge_error_start, error.message ?: error.toString()
                ))
            }
            return
        }

        val nsd = FolderBridgeNsd(this)
        this.nsd = nsd
        // A failed NSD registration leaves the server usable over TLS by address; the status
        // line and notification say so instead of hiding it.
        nsd.start(
            "FileWeave ${Build.MODEL}", FolderBridgeServer.DEFAULT_PORT, fingerprint
        ) { failed ->
            mainExecutorCompat.execute {
                if (this.status.state == State.RUNNING && !this.status.nsdFailed) {
                    this.status = this.status.copy(nsdFailed = failed)
                    notification.update(notificationText(this.status))
                }
            }
        }

        val started = Status(State.RUNNING, port = FolderBridgeServer.DEFAULT_PORT)
        postStatus(started)
        notification.update(notificationText(started))
    }

    private fun notificationText(status: Status): String {
        val builder = StringBuilder()
        when (status.state) {
            State.RUNNING -> {
                builder.append(getString(R.string.fw_folder_bridge_notification_running,
                    status.port ?: FolderBridgeServer.DEFAULT_PORT))
                if (status.fileCount >= 0) {
                    builder.append(' ').append(getString(
                        R.string.fw_folder_bridge_notification_files, status.fileCount))
                }
                if (status.nsdFailed) {
                    builder.append(' ').append(
                        getString(R.string.fw_folder_bridge_notification_nsd_failed))
                }
            }
            State.STARTING -> builder.append(getString(R.string.fw_folder_bridge_state_summary_starting))
            else -> {}
        }
        return builder.toString().ifEmpty { getString(R.string.fw_folder_bridge_title) }
    }

    @WorkerThread
    private fun doStop() {
        nsd?.stop()
        nsd = null
        server?.stop()
        server = null
        postStatus(Status(State.STOPPED))
    }

    enum class State {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    data class Status(
        val state: State,
        val error: String? = null,
        val port: Int? = null,
        val nsdFailed: Boolean = false,
        val fileCount: Int = -1
    )

    companion object {
        private val _statusLiveData = MutableLiveData(Status(State.STOPPED))
        val statusLiveData: LiveData<Status>
            get() = _statusLiveData

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, FolderBridgeService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FolderBridgeService::class.java))
        }

        fun toggle(context: Context) {
            when (val state = _statusLiveData.valueCompat.state) {
                State.STARTING, State.STOPPING -> {}
                State.RUNNING -> stop(context)
                State.STOPPED -> start(context)
                else -> throw AssertionError(state)
            }
        }
    }
}

/** Registers the folder bridge over NSD as `_fileweave-folder._tcp.` with v and fp TXT records. */
class FolderBridgeNsd(private val context: Context) {
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registrationCompleted = false

    @Volatile
    private var stopRequested = false

    fun start(serviceName: String, port: Int, fingerprint: String,
              onRegistrationFailed: (Boolean) -> Unit) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            // TXT records are untrusted hints; the Mac still verifies the TLS certificate.
            setAttribute("v", "1")
            setAttribute("fp", fingerprint)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registrationCompleted = true
                // stop() may have raced ahead of the registration completing; unregister here,
                // or the broadcast would keep advertising a server that no longer exists.
                if (stopRequested) {
                    unregisterQuietly()
                }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onRegistrationFailed(true)
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        this.listener = listener
        val manager = context.getSystemService(NsdManager::class.java) ?: run {
            onRegistrationFailed(true)
            return
        }
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            this.listener = null
            onRegistrationFailed(true)
        }
    }

    fun stop() {
        stopRequested = true
        val listener = listener ?: return
        if (!registrationCompleted) {
            // Calling unregisterService before the registration completes throws
            // IllegalArgumentException; onServiceRegistered unregisters instead.
            return
        }
        unregisterQuietly()
    }

    private fun unregisterQuietly() {
        val listener = listener ?: return
        this.listener = null
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        try {
            manager.unregisterService(listener)
        } catch (_: RuntimeException) { }
    }

    companion object {
        // Distinct from the Mac-to-phone bridge's "_fileweave._tcp.".
        const val SERVICE_TYPE = "_fileweave-folder._tcp."
    }
}

private val folderBridgeServiceNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "folder_bridge",
            R.string.notification_channel_folder_bridge_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_folder_bridge_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.fw_folder_bridge_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_SERVICE,
        priority = NotificationCompat.PRIORITY_LOW
    )

/** The persistent notification doubles as the status light for the user. */
class FolderBridgeNotification(private val service: Service) {
    fun startForeground(text: String) {
        service.startForeground(NotificationIds.FOLDER_BRIDGE, build(text))
    }

    fun update(text: String) {
        service.getSystemService(NotificationManager::class.java)?.notify(
            NotificationIds.FOLDER_BRIDGE, build(text)
        )
    }

    private fun build(text: String): android.app.Notification {
        val contentIntent = FolderBridgeActivity::class.createIntent()
        val contentPendingIntent = PendingIntent.getActivity(
            service, FolderBridgeActivity::class.hashCode(), contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = FolderBridgeReceiver.createIntent()
        val stopPendingIntent = PendingIntent.getBroadcast(
            service, FolderBridgeReceiver::class.hashCode(), stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return folderBridgeServiceNotificationTemplate.createBuilder(service)
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.stop_icon_white_24dp, service.getString(R.string.stop), stopPendingIntent
            )
            .build()
    }

    fun stopForeground() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}

class FolderBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            ACTION_STOP -> FolderBridgeService.stop(context)
            else -> throw IllegalArgumentException(action)
        }
    }

    companion object {
        const val ACTION_STOP = "stop"

        fun createIntent(): Intent =
            Intent(application, FolderBridgeReceiver::class.java)
                .setAction(ACTION_STOP)
    }
}
