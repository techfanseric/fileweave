package me.zhanghai.android.files.webdavserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.RuntimeBroadcastReceiver
import me.zhanghai.android.files.util.getLocalAddress
import me.zhanghai.android.files.util.valueCompat
import java.net.InetAddress

/**
 * 通知与设置页显示的访问地址。协议是明文 http——WebDAV over TLS 需要向局域网客户端分发
 * 证书，超出本功能范围；凭据以 Basic 认证明文传输，威胁模型与上游 FTP 服务器一致（都
 * 假设可信局域网）。
 */
object WebDavServerUrl {
    fun getUrl(): String? {
        val localAddress = InetAddress::class.getLocalAddress() ?: return null
        val username = if (!Settings.WEBDAV_SERVER_ANONYMOUS_LOGIN.valueCompat) {
            Settings.WEBDAV_SERVER_USERNAME.valueCompat
        } else {
            null
        }
        val host = localAddress.hostAddress
        val port = Settings.WEBDAV_SERVER_PORT.valueCompat
        return "http://${if (username != null) "$username@" else ""}$host:$port/"
    }

    fun createChangeReceiver(context: Context, onChange: () -> Unit): RuntimeBroadcastReceiver =
        RuntimeBroadcastReceiver(
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onChange()
                }
            }, context
        )
}
