package me.zhanghai.android.files.lanshare

/** Component boundaries matter: Android/data is private, Android/database is not that path. */
class LanSharePathPolicy(excludedAbsolutePaths: List<String>) {
    private val excluded = excludedAbsolutePaths.map { normalize(it).lowercase(java.util.Locale.ROOT) }.distinct()
    fun excludes(absolutePath: String): Boolean {
        // Emulated/removable storage can compare names without case sensitivity.
        val normalized = normalize(absolutePath).lowercase(java.util.Locale.ROOT)
        return excluded.any { it == "/" || normalized == it || normalized.startsWith("$it/") }
    }
    private fun normalize(value: String): String {
        require(value.startsWith('/'))
        val parts = ArrayList<String>()
        for (part in value.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
        return "/" + parts.joinToString("/")
    }
    companion object {
        fun requireComponents(parts: List<String>) {
            require(parts.all { part -> part.isNotEmpty() && part != "." && part != ".." &&
                part.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 } }) { "Invalid shared path" }
        }
    }
}
