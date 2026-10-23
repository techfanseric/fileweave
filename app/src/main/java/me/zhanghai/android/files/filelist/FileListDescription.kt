package me.zhanghai.android.files.filelist

import android.content.Context
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatShort

internal fun FileItem.listDescription(context: Context): String {
    val modified = attributes.lastModifiedTime().toInstant().formatShort(context)
    val detail = if (attributes.isDirectory) {
        context.getString(R.string.file_open_as_type_directory)
    } else {
        attributes.fileSize.formatHumanReadable(context)
    }
    return listOf(modified, detail).joinToString(context.getString(R.string.file_item_description_separator))
}
