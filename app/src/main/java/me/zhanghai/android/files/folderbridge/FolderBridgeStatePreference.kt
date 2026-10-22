package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.lifecycle.Observer
import androidx.preference.SwitchPreferenceCompat
import me.zhanghai.android.files.R

class FolderBridgeStatePreference : SwitchPreferenceCompat {
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
    }

    override fun onAttached() {
        super.onAttached()

        FolderBridgeService.statusLiveData.observeForever(observer)
    }

    override fun onDetached() {
        super.onDetached()

        FolderBridgeService.statusLiveData.removeObserver(observer)
    }

    private fun onStatusChanged(status: FolderBridgeService.Status) {
        val summaryRes = when (status.state) {
            FolderBridgeService.State.STARTING -> R.string.fw_folder_bridge_state_summary_starting
            FolderBridgeService.State.RUNNING -> R.string.fw_folder_bridge_state_summary_running
            FolderBridgeService.State.STOPPING -> R.string.fw_folder_bridge_state_summary_stopping
            FolderBridgeService.State.STOPPED -> R.string.fw_folder_bridge_state_summary_stopped
        }
        summary = if (status.error != null) {
            context.getString(R.string.fw_folder_bridge_status_error, status.error)
        } else {
            context.getString(summaryRes)
        }
        isChecked = status.state == FolderBridgeService.State.STARTING
            || status.state == FolderBridgeService.State.RUNNING
        isEnabled = !(status.state == FolderBridgeService.State.STARTING
            || status.state == FolderBridgeService.State.STOPPING)
    }

    override fun onClick() {
        FolderBridgeService.toggle(context)
    }
}
