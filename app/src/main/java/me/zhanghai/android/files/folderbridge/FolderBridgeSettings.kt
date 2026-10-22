package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import me.zhanghai.android.files.R
import me.zhanghai.android.files.settings.StringSettingLiveData
import me.zhanghai.android.files.util.valueCompat

/**
 * Folder bridge preferences, stored the same way as the FTP server's (a string setting LiveData
 * backed by the default shared preferences), without extending the upstream Settings object.
 */
object FolderBridgeSettings {
    /** SAF tree Uri (ACTION_OPEN_DOCUMENT_TREE) of the directory shared with the Mac. */
    val DIRECTORY = StringSettingLiveData(
        R.string.pref_key_folder_bridge_directory, R.string.pref_default_value_empty
    )

    fun getTreeUri(context: Context): Uri? {
        val value = DIRECTORY.valueCompat
        if (value.isEmpty()) {
            return null
        }
        return try {
            val uri = Uri.parse(value)
            val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            if (documentId.isNullOrBlank()) null else uri
        } catch (_: Exception) {
            null
        }
    }

    fun setTreeUri(uri: Uri) {
        DIRECTORY.putValue(uri.toString())
    }

    fun createOpenTreeIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
}
