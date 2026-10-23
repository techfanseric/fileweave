package me.zhanghai.android.files.filelist

import androidx.core.view.isVisible
import coil.dispose
import coil.load
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FilePreviewPaneBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatLong
import me.zhanghai.android.files.provider.common.PosixFileAttributes
import me.zhanghai.android.files.provider.common.toModeString

/** Uses the same bounded thumbnail pipeline as the list; no player or document reader is started. */
internal class FileListPreview(
    private val binding: FilePreviewPaneBinding,
    private val open: (FileItem) -> Unit,
    private val properties: (FileItem) -> Unit
) {
    private var file: FileItem? = null
    val isVisible: Boolean get() = binding.root.isVisible

    init {
        binding.previewEmptyState.stateTitle.setText(R.string.fw_preview_empty_title)
        binding.previewEmptyState.stateHint.setText(R.string.fw_preview_empty_hint)
        clear()
        binding.previewOpen.setOnClickListener { file?.let(open) }
        binding.previewProperties.setOnClickListener { file?.let(properties) }
    }

    fun setVisible(visible: Boolean) {
        if (isVisible == visible) return
        binding.root.isVisible = visible
        if (!visible) clear()
    }

    fun resize(height: Int) {
        if (height > 0) binding.previewImage.maxHeight = (height * 0.68f).toInt()
    }

    fun show(file: FileItem) {
        this.file = file
        binding.previewEmptyState.root.isVisible = false
        binding.previewScroll.isVisible = true
        binding.previewImage.dispose()
        binding.previewImage.setImageResource(file.mimeType.iconRes)
        binding.previewImage.isVisible = true
        val params = binding.previewImage.layoutParams
        params.height = if (file.supportsThumbnail) android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            else (80 * binding.root.resources.displayMetrics.density).toInt()
        binding.previewImage.layoutParams = params
        if (file.supportsThumbnail) {
            binding.previewImage.load(file.path to file.attributes) {
                placeholder(file.mimeType.iconRes)
                error(file.mimeType.iconRes)
            }
        }
        binding.previewHint.isVisible = !file.supportsThumbnail && !file.attributes.isDirectory
        binding.previewHint.setText(R.string.fw_preview_unavailable)
        binding.previewName.text = file.name
        val context = binding.root.context
        fun property(label: Int, value: String) = "${context.getString(label)}  $value"
        binding.previewDetails.text = buildList {
            add(property(R.string.file_properties_basic_type,
                "${file.getMimeTypeName(context)} (${file.mimeType.value})"))
            if (!file.attributes.isDirectory) {
                add(property(R.string.file_properties_basic_size,
                    "${file.attributes.fileSize.formatHumanReadable(context)} (${file.attributes.size()} B)"))
            }
            add(property(R.string.file_properties_basic_last_modification_time,
                file.attributes.lastModifiedTime().toInstant().formatLong()))
            file.symbolicLinkTarget?.let {
                add(property(R.string.file_properties_basic_symbolic_link_target, it.toString()))
            }
            (file.attributes as? PosixFileAttributes)?.let { attributes ->
                attributes.owner()?.let { add(property(R.string.file_properties_permission_owner, it.toString())) }
                attributes.group()?.let { add(property(R.string.file_properties_permission_group, it.toString())) }
                attributes.mode()?.let { add(property(R.string.file_properties_permission_mode, it.toModeString())) }
            }
        }.joinToString("\n")
        binding.previewPath.text = file.path.toString()
        binding.previewOpen.isVisible = true
        binding.previewProperties.isVisible = true
        binding.previewScroll.scrollTo(0, 0)
    }

    fun refresh(files: List<FileItem>) {
        val current = file ?: return
        val updated = files.firstOrNull { it.path == current.path }
        if (updated == null) clear() else if (updated != current) show(updated)
    }

    fun clear() {
        file = null
        binding.previewEmptyState.root.isVisible = true
        binding.previewScroll.isVisible = false
        binding.previewImage.dispose()
        binding.previewImage.setImageDrawable(null)
        binding.previewImage.isVisible = false
        binding.previewHint.setText(R.string.fw_preview_hint)
        binding.previewHint.isVisible = true
        binding.previewName.text = null
        binding.previewDetails.text = null
        binding.previewPath.text = null
        binding.previewOpen.isVisible = false
        binding.previewProperties.isVisible = false
    }
}
