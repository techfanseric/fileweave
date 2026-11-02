package me.zhanghai.android.files.settings

/** Language policy is independent of the device locale and Android resource fallback. */
internal object AppLanguagePolicy {
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh-CN"

    fun normalizeSaved(language: String?): String =
        if (language == SIMPLIFIED_CHINESE) SIMPLIFIED_CHINESE else ENGLISH

    /** Only an explicit app language (including an upstream Chinese locale) is migrated. */
    fun fromExplicitLocales(languageTags: String?): String {
        val first = languageTags.orEmpty().substringBefore(',').substringBefore('-')
        return if (first.equals("zh", ignoreCase = true)) SIMPLIFIED_CHINESE else ENGLISH
    }
}
