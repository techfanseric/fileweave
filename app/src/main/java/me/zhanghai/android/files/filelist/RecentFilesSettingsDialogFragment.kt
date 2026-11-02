package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RecentFilesSettingsDialogBinding
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.util.showToast

class RecentFilesSettingsDialogFragment : DialogFragment() {
    private var binding: RecentFilesSettingsDialogBinding? = null
    private val directoryPicker = registerForActivityResult(FileListActivity.OpenDirectoryContract()) { path ->
        if (path == null) return@registerForActivityResult
        if (!path.isLinuxPath) {
            showToast(R.string.fw_recent_scope_local_only)
            return@registerForActivityResult
        }
        binding?.roots?.apply {
            val paths = text.toString().lineSequence().filter { it.isNotBlank() }.toMutableList()
            if (path.toString() !in paths) paths += path.toString()
            setText(paths.joinToString("\n"))
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = RecentFilesSettingsDialogBinding.inflate(layoutInflater)
        binding = view
        val rules = RecentFilesSettings.read()
        view.modes.check(when (rules.mode) {
            RecentFileRules.Mode.ALL -> R.id.modeAll
            RecentFileRules.Mode.EXCLUDE -> R.id.modeExclude
            RecentFileRules.Mode.ONLY -> R.id.modeOnly
        })
        view.roots.setText(rules.roots.joinToString("\n"))
        view.addDirectory.setOnClickListener { directoryPicker.launch(null) }
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fw_recent_scope)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        (requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val view = binding ?: return@setOnClickListener
            val paths = view.roots.text.toString().lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() }.toList()
            val roots = paths.map { RecentFileRules.normalizeAbsolutePath(it) }
            if (roots.any { it == null }) {
                view.rootsInput.error = getString(R.string.fw_recent_scope_invalid_path)
                return@setOnClickListener
            }
            val mode = when (view.modes.checkedRadioButtonId) {
                R.id.modeExclude -> RecentFileRules.Mode.EXCLUDE
                R.id.modeOnly -> RecentFileRules.Mode.ONLY
                else -> RecentFileRules.Mode.ALL
            }
            RecentFilesSettings.write(RecentFileRules(mode, roots.filterNotNull().distinct()))
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
            dismiss()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "recent_files_scope_changed"
    }
}
