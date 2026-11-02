package me.zhanghai.android.files.filelist

/** Omit only the known storage root; all directory segments remain user content. */
internal object RecentFileLocation {
    fun display(parent: String, storageRoot: String, rootLabel: String): String = when {
        parent == storageRoot -> rootLabel
        parent.startsWith("$storageRoot/") -> parent.removePrefix("$storageRoot/")
        else -> parent
    }
}
