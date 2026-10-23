package me.zhanghai.android.files.webdavserver

import android.app.PendingIntent
import android.app.Service
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.compat.stopForegroundCompat
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate
import me.zhanghai.android.files.util.createIntent

/**
 * 通知模板与渠道定义，结构照抄 FtpServerNotification。
 *
 * 注意：渠道必须经 AppInitializers.createNotificationChannels 注册后才可用——真机上曾
 * 踩过“未注册渠道直接 startForeground 崩溃”的坑，webdavServerServiceNotificationTemplate
 * 已加入该列表。
 */
val webDavServerServiceNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "webdav_server",
            R.string.notification_channel_webdav_server_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_webdav_server_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.webdav_server_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_SERVICE,
        priority = NotificationCompat.PRIORITY_LOW
    )

class WebDavServerNotification(private val service: Service) {
    private val receiver = WebDavServerUrl.createChangeReceiver(service) { doStartForeground() }

    fun startForeground() {
        doStartForeground()
        receiver.register()
    }

    private fun doStartForeground() {
        val contextText = WebDavServerUrl.getUrl()
            ?: service.getString(R.string.webdav_server_url_summary_no_local_inet_address)
        val contentIntent = WebDavServerActivity::class.createIntent()
        val contentPendingIntent = PendingIntent.getActivity(
            service, WebDavServerActivity::class.hashCode(), contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = WebDavServerReceiver.createIntent()
        val stopPendingIntent = PendingIntent.getBroadcast(
            service, WebDavServerReceiver::class.hashCode(), stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = webDavServerServiceNotificationTemplate.createBuilder(service)
            .setContentText(contextText)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.stop_icon_white_24dp, service.getString(R.string.stop), stopPendingIntent
            )
            .build()
        service.startForeground(NotificationIds.WEBDAV_SERVER, notification)
    }

    fun stopForeground() {
        receiver.unregister()
        service.stopForegroundCompat(ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
