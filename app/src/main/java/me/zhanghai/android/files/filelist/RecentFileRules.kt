package me.zhanghai.android.files.filelist

/** Pure component-aware matching for the paths exposed by the shared-storage file index. */
data class RecentFileRules(val mode: Mode = Mode.ALL, val roots: List<String> = emptyList()) {
    enum class Mode { ALL, EXCLUDE, ONLY }

    private val normalizedRoots = roots.mapNotNull(::normalizeAbsolutePath)

    fun includes(path: String): Boolean {
        val normalized = normalizeAbsolutePath(path) ?: return false
        if (isTrashPath(normalized)) return false
        if (mode == Mode.ALL) return true
        val matched = normalizedRoots.any {
            it == "/" || normalized == it || normalized.startsWith("$it/")
        }
        return if (mode == Mode.ONLY) matched else !matched
    }

    companion object {
        /** MediaStore IS_TRASHED excludes system trash, but app-managed trash is
         * indexed as ordinary files. Match directory components, never substrings. */
        fun isTrashPath(path: String): Boolean {
            val parts = (normalizeAbsolutePath(path) ?: return false).split('/').filter { it.isNotEmpty() }
            val directories = parts.dropLast(1).map { it.lowercase(java.util.Locale.ROOT) }
            return directories.any { it in trashDirectoryNames || numberedTrashDirectory.matches(it) } ||
                directories.zipWithNext().any { (parent, child) ->
                    // Honor/Huawei Gallery's private recycle store is indexed as
                    // ordinary .hndgp files, without MediaStore's trash flag.
                    (parent == ".gallery2" && child == "recycle") ||
                        (parent == "recycle" && child in recycleContainers)
                } || parts.lastOrNull()?.lowercase(java.util.Locale.ROOT)?.startsWith(".trashed-") == true
        }
        private val trashDirectoryNames = setOf(
            ".trash", ".trashes", ".recycle", ".recyclebin", ".recycle_bin", "\$recycle.bin", "#recycle",
            "recyclebin", "recycle_bin"
        )
        private val numberedTrashDirectory = Regex("\\.trash-[0-9]+")
        private val recycleContainers = setOf("bin", "bins")
        fun normalizeAbsolutePath(path: String): String? {
            if (!path.startsWith('/') || '\u0000' in path) return null
            val parts = ArrayList<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
            return "/" + parts.joinToString("/")
        }
    }
}
