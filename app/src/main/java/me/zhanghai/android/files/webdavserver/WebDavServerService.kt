package me.zhanghai.android.files.webdavserver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.compat.mainExecutorCompat
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.WakeWifiLock
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.valueCompat
import java.util.concurrent.Executors

/**
 * WebDAV 服务器的前台服务，结构与上游 FtpServerService 一一对应：状态机（STARTING/
 * RUNNING/STOPPING/STOPPED）+ 单线程执行器串行启停 + WakeWifiLock 保活 + 常驻通知。
 *
 * 与 FTP 服务器不同的一点：匿名模式默认关闭（FTP 上游默认匿名开启），因为 WebDAV 的
 * 使用场景通常是“把手机当网络磁盘挂载”，凭据是预期内的默认行为。
 */
class WebDavServerService : Service() {
    private var state = State.STOPPED
        set(value) {
            field = value
            _stateLiveData.value = value
        }

    private lateinit var wakeWifiLock: WakeWifiLock

    private lateinit var notification: WebDavServerNotification

    private val executorService = Executors.newSingleThreadExecutor()

    private var server: WebDavServer? = null

    override fun onCreate() {
        super.onCreate()

        wakeWifiLock = WakeWifiLock(WebDavServerService::class.java.simpleName)
        notification = WebDavServerNotification(this)
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
        if (state == State.STARTING || state == State.RUNNING) {
            return
        }
        wakeWifiLock.isAcquired = true
        notification.startForeground()
        state = State.STARTING
        executorService.execute { doStart() }
    }

    private fun onStartError(exception: Exception) {
        state = State.STOPPED
        showToast(exception.toString())
        notification.stopForeground()
        wakeWifiLock.isAcquired = false
        stopSelf()
    }

    private fun executeStop() {
        if (state == State.STOPPING || state == State.STOPPED) {
            return
        }
        state = State.STOPPING
        executorService.execute { doStop() }
        notification.stopForeground()
        wakeWifiLock.isAcquired = false
    }

    @WorkerThread
    private fun postState(state: State) {
        mainExecutorCompat.execute { this.state = state }
    }

    @WorkerThread
    private fun doStart() {
        if (server != null) {
            return
        }
        // 匿名开关与凭据读取方式和 FtpServerService 保持一致；匿名时不校验 Basic 认证。
        val username: String?
        val password: String?
        if (Settings.WEBDAV_SERVER_ANONYMOUS_LOGIN.valueCompat) {
            username = null
            password = null
        } else {
            username = Settings.WEBDAV_SERVER_USERNAME.valueCompat
            password = Settings.WEBDAV_SERVER_PASSWORD.valueCompat
        }
        val port = Settings.WEBDAV_SERVER_PORT.valueCompat
        val homeDirectory = Settings.WEBDAV_SERVER_HOME_DIRECTORY.valueCompat
        val writable = Settings.WEBDAV_SERVER_WRITABLE.valueCompat
        val backend = WebDavProviderBackend(homeDirectory)
        val kernel = WebDavKernel(backend, username, password, writable)
        val server = WebDavServer(port, kernel)
        this.server = server
        try {
            server.start()
        } catch (e: Exception) {
            e.printStackTrace()
            this.server = null
            mainExecutorCompat.execute { onStartError(e) }
            return
        }
        postState(State.RUNNING)
    }

    @WorkerThread
    private fun doStop() {
        val server = server ?: return
        server.stop()
        this.server = null
        postState(State.STOPPED)
    }

    companion object {
        private val _stateLiveData = MutableLiveData(State.STOPPED)
        val stateLiveData: LiveData<State>
            get() = _stateLiveData

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WebDavServerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebDavServerService::class.java))
        }

        fun toggle(context: Context) {
            when (val state = _stateLiveData.valueCompat) {
                State.STARTING, State.STOPPING -> {}
                State.RUNNING -> stop(context)
                State.STOPPED -> start(context)
                else -> throw AssertionError(state)
            }
        }
    }

    enum class State {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
