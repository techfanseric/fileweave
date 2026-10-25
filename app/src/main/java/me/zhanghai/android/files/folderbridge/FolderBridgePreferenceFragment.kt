package me.zhanghai.android.files.folderbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import me.zhanghai.android.files.provider.document.createDocumentTreeRootPath
import me.zhanghai.android.files.R
import me.zhanghai.android.files.ui.PreferenceFragmentCompat
import me.zhanghai.android.files.util.showToast

class FolderBridgePreferenceFragment : PreferenceFragmentCompat() {
    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            return@registerForActivityResult
        }
        // The grant must be persistable to survive reboots; without it the bridge would silently
        // lose access to the tree after the next restart.
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            showToast(R.string.fw_folder_bridge_directory_keep_failed)
        }
        FolderBridgeSettings.setTreeUri(uri)
    }

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.folder_bridge)
        findPreference<androidx.preference.Preference>("recordings_transcripts")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), TranscriptActivity::class.java))
            true
        }
        findPreference<androidx.preference.Preference>("recordings_browse")?.setOnPreferenceClickListener {
            val directory = FolderBridgeDefaults.detect()
            val tree = FolderBridgeSettings.getTreeUri(requireContext())
            if (tree != null) {
                startActivity(me.zhanghai.android.files.filelist.FileListActivity.createViewIntent(
                    tree.createDocumentTreeRootPath()))
            } else if (directory != null) {
                startActivity(me.zhanghai.android.files.filelist.FileListActivity.createViewIntent(
                    java8.nio.file.Paths.get(directory.absolutePath)))
            } else {
                pickTree.launch(null)
            }
            true
        }
        findPreference<FolderBridgeDirectoryPreference>(KEY_DIRECTORY)?.onPickRequested = {
            pickTree.launch(null)
        }
    }

    companion object {
        private const val KEY_DIRECTORY = "folder_bridge_directory"
    }
}
