package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.lifecycle.Observer
import androidx.preference.Preference
import me.zhanghai.android.files.R
import me.zhanghai.android.files.util.valueCompat

/** Non-interactive status line: running/port/issued/error, mirroring the notification. */
class FolderBridgeStatusPreference : Preference {
    private val observer = Observer<FolderBridgeService.Status> { onStatusChanged(it) }

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        isPersistent = false
        isEnabled = false
    }

    override fun onAttached() {
        super.onAttached()

        FolderBridgeService.statusLiveData.observeForever(observer)
        onStatusChanged(FolderBridgeService.statusLiveData.valueCompat)
    }

    override fun onDetached() {
        super.onDetached()

        FolderBridgeService.statusLiveData.removeObserver(observer)
    }

    private fun onStatusChanged(status: FolderBridgeService.Status) {
        summary = when (status.state) {
            FolderBridgeService.State.STARTING ->
                context.getString(R.string.fw_folder_bridge_state_summary_starting)
            FolderBridgeService.State.RUNNING -> {
                val builder = StringBuilder(context.getString(
                    R.string.fw_folder_bridge_status_running, status.port
                        ?: FolderBridgeServer.DEFAULT_PORT
                ))
                if (status.fileCount >= 0) {
                    builder.append(' ').append(context.getString(
                        R.string.fw_folder_bridge_notification_files, status.fileCount))
                }
                if (status.nsdFailed) {
                    builder.append(' ').append(
                        context.getString(R.string.fw_folder_bridge_notification_nsd_failed))
                }
                builder.toString()
            }
            FolderBridgeService.State.STOPPING ->
                context.getString(R.string.fw_folder_bridge_state_summary_stopping)
            FolderBridgeService.State.STOPPED -> status.error
                ?: context.getString(R.string.fw_folder_bridge_state_summary_stopped)
        }
    }
}
