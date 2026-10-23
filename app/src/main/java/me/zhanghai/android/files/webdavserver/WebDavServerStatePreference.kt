package me.zhanghai.android.files.webdavserver

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.lifecycle.Observer
import androidx.preference.SwitchPreferenceCompat
import me.zhanghai.android.files.R

/** 服务器总开关，显示启动/运行/停止状态，点击切换，与 FtpServerStatePreference 同款。 */
class WebDavServerStatePreference : SwitchPreferenceCompat {
    private val observer = Observer<WebDavServerService.State> { onStateChanged(it) }

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

        WebDavServerService.stateLiveData.observeForever(observer)
    }

    override fun onDetached() {
        super.onDetached()

        WebDavServerService.stateLiveData.removeObserver(observer)
    }

    private fun onStateChanged(state: WebDavServerService.State) {
        val summaryRes = when (state) {
            WebDavServerService.State.STARTING -> R.string.webdav_server_state_summary_starting
            WebDavServerService.State.RUNNING -> R.string.webdav_server_state_summary_running
            WebDavServerService.State.STOPPING -> R.string.webdav_server_state_summary_stopping
            WebDavServerService.State.STOPPED -> R.string.webdav_server_state_summary_stopped
        }
        summary = context.getString(summaryRes)
        isChecked = state == WebDavServerService.State.STARTING
            || state == WebDavServerService.State.RUNNING
        isEnabled = !(state == WebDavServerService.State.STARTING
            || state == WebDavServerService.State.STOPPING)
    }

    override fun onClick() {
        WebDavServerService.toggle(context)
    }
}
