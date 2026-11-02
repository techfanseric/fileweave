package me.zhanghai.android.files.lanshare

/** Provider labels are display data, never paths. Keep names within ordinary UTF-8 limits. */
object LanShareImportNames {
    fun choose(label: String?, mimeType: String?, index: Int, used: MutableSet<String>): String {
        var name = label.orEmpty().take(512).map {
            if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it
        }.joinToString("").trim().takeUnless { it.isBlank() || it == "." || it == ".." } ?: "shared-file-$index"
        val rasterSuffix = when (mimeType?.lowercase(java.util.Locale.ROOT)) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            else -> null
        }
        if (rasterSuffix != null && name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) !in
            setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")) name += ".$rasterSuffix"
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 16 } ?: name.length
        val extension = name.substring(dot)
        var stem = name.substring(0, dot)
        val budget = 180 - extension.toByteArray(Charsets.UTF_8).size
        while (stem.toByteArray(Charsets.UTF_8).size > budget) {
            val drop = if (stem.last().isLowSurrogate() && stem.length > 1 && stem[stem.lastIndex - 1].isHighSurrogate()) 2 else 1
            stem = stem.dropLast(drop)
        }
        var unique = stem + extension
        var number = 2
        while (!used.add(unique)) unique = "$stem (${number++})$extension"
        return unique
    }
}
