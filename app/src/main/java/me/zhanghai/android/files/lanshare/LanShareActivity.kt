package me.zhanghai.android.files.lanshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import me.zhanghai.android.files.util.extraPathList

class LanShareActivity : FeatureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        screen(getString(R.string.fw_lan_share))
        val scope = text("")
        val address = text("").apply { setTextIsSelectable(true) }
        text(getString(R.string.fw_lan_hint))
        button(getString(R.string.fw_lan_copy)) {
            LanShareService.state.value?.url?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FileWeave", it))
                android.widget.Toast.makeText(this, R.string.fw_lan_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        button(getString(R.string.fw_lan_send)) {
            LanShareService.state.value?.url?.let {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), getString(R.string.fw_lan_send)))
            }
        }
        button(getString(R.string.stop)) { stopService(Intent(this, LanShareService::class.java)) }
        LanShareService.state.observe(this) { state ->
            scope.text = state.names.joinToString("\n")
            address.text = state.url ?: state.error ?: getString(if (state.names.isEmpty()) R.string.fw_lan_stopped else R.string.fw_lan_starting)
        }
        if (savedInstanceState == null && intent.extraPathList.isNotEmpty()) {
            ContextCompat.startForegroundService(this, Intent(this, LanShareService::class.java).apply { extraPathList = intent.extraPathList })
        }
    }
}
