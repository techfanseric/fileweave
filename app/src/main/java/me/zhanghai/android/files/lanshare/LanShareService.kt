package me.zhanghai.android.files.lanshare

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.getLocalAddress
import me.zhanghai.android.files.webdavserver.WebDavServer
import java.net.InetAddress
import java.util.concurrent.Executors

class LanShareService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var server: WebDavServer? = null
    private var kernel: LanShareKernel? = null
    @Volatile private var closed = false
    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("lan_share", getString(R.string.fw_lan_share), NotificationManager.IMPORTANCE_LOW))
        }
        // Device lock revokes this temporary guest link; paired Mac sharing has separate controls.
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { stopSelf() }
        }.also { ContextCompat.registerReceiver(this, it, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val paths = intent?.extraPathList.orEmpty()
        if (paths.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, LanShareActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, LanShareService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(5, NotificationCompat.Builder(this, "lan_share")
            .setSmallIcon(R.drawable.notification_icon).setContentTitle(getString(R.string.fw_lan_share))
            .setContentText(getString(R.string.fw_lan_scope, paths.size)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.stop), stop).build())
        state.value = State(null, paths.map { it.fileName?.toString() ?: it.toString() })
        executor.execute {
            try {
                kernel?.active = false
                server?.stop()
                val address = InetAddress::class.getLocalAddress() ?: error(getString(R.string.fw_lan_no_network))
                val token = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val next = LanShareKernel(token, LanShareBackend(paths))
                val listener = WebDavServer(0, next::handle)
                kernel = next; server = listener
                if (closed) { next.active = false; listener.stop(); return@execute }
                listener.start()
                if (closed) { next.active = false; listener.stop(); return@execute }
                val host = address.hostAddress!!.let { if (':' in it) "[$it]" else it }
                state.postValue(State("http://$host:${listener.localPort}/$token/", paths.map { it.fileName?.toString() ?: it.toString() }))
            } catch (error: Exception) {
                state.postValue(State(null, emptyList(), error.localizedMessage))
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        closed = true
        kernel?.active = false
        server?.stop()
        executor.shutdownNow()
        receiver?.let { unregisterReceiver(it) }
        if (state.value?.error == null) state.value = State(null, emptyList())
        super.onDestroy()
    }
    data class State(val url: String?, val names: List<String>, val error: String? = null)
    companion object { val state = MutableLiveData(State(null, emptyList())) }
}
