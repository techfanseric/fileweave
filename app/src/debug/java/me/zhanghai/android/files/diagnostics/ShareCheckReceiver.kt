package me.zhanghai.android.files.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shell-only checks without starting instrumentation or terminating the user's app. */
class ShareCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("enable_connection", false)) {
            me.zhanghai.android.files.settings.Settings.BRIDGE_AUTO_CONNECT.putValue(true)
        }
        val pending = goAsync()
        Thread {
            try {
                me.zhanghai.android.files.folderbridge.FolderBridgeSettings.getTreeUri(context)
                verifyScopedFiles(context)
                pending.resultCode = 0
                pending.resultData = "PASS: background settings, real file Range, symlink boundary, revocation"
            } catch (error: Throwable) {
                pending.resultCode = 1
                pending.resultData = error.stackTraceToString()
            } finally { pending.finish() }
        }.start()
    }
}
