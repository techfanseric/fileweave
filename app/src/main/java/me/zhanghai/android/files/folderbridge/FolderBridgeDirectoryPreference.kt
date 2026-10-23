package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.lifecycle.Observer
import androidx.preference.Preference
import me.zhanghai.android.files.R

/**
 * Opens the system directory picker (ACTION_OPEN_DOCUMENT_TREE). The actual launch happens in
 * the fragment, which owns the activity result launcher; persistence goes through
 * FolderBridgeSettings like the FTP server's preferences.
 */
class FolderBridgeDirectoryPreference : Preference {
    private val observer = Observer<String> { onDirectoryChanged(it) }

    /** Set by the fragment; invoked on click to launch the picker. */
    var onPickRequested: (() -> Unit)? = null

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

        FolderBridgeSettings.DIRECTORY.observeForever(observer)
        onDirectoryChanged(FolderBridgeSettings.DIRECTORY.value)
    }

    override fun onDetached() {
        super.onDetached()

        FolderBridgeSettings.DIRECTORY.removeObserver(observer)
    }

    private fun onDirectoryChanged(value: String?) {
        summary = if (value.isNullOrEmpty()) {
            // 未手动选择时展示自动识别结果（已授予所有文件访问权限且探测命中才会出现），
            // 让用户知道打开开关即可用；探测只是少量目录存在性检查。
            val detected = FolderBridgeDefaults.detect()
            if (detected != null) {
                context.getString(R.string.fw_folder_bridge_directory_auto, detected.name)
            } else {
                context.getString(R.string.fw_folder_bridge_directory_none)
            }
        } else {
            try {
                val documentId = android.provider.DocumentsContract.getTreeDocumentId(
                    android.net.Uri.parse(value)
                ).orEmpty()
                FolderBridgePaths.rootNameOf(documentId)
            } catch (_: Exception) {
                context.getString(R.string.fw_folder_bridge_directory_none)
            }
        }
    }

    override fun onClick() {
        onPickRequested?.invoke()
    }
}
