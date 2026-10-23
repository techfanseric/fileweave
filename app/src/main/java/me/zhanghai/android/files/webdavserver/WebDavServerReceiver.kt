package me.zhanghai.android.files.webdavserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.zhanghai.android.files.app.application

/** 通知栏“停止”按钮的广播接收者，与 FtpServerReceiver 同款。 */
class WebDavServerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            ACTION_STOP -> WebDavServerService.stop(context)
            else -> throw IllegalArgumentException(action)
        }
    }

    companion object {
        const val ACTION_STOP = "stop"

        fun createIntent(): Intent =
            Intent(application, WebDavServerReceiver::class.java)
                .setAction(ACTION_STOP)
    }
}
