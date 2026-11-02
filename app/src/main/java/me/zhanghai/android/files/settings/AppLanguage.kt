package me.zhanghai.android.files.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Product language is explicit: a new installation starts in English on every device. */
object AppLanguage {
    const val ENGLISH = AppLanguagePolicy.ENGLISH
    const val SIMPLIFIED_CHINESE = AppLanguagePolicy.SIMPLIFIED_CHINESE
    private const val PREFERENCES = "fileweave_language"
    private const val LANGUAGE = "language"

    fun selected(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AppLanguagePolicy.fromExplicitLocales(
                LocaleManagerCompat.getApplicationLocales(context).toLanguageTags()
            )
        } else {
            saved(context)
        }

    private fun saved(context: Context): String = normalize(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE, null)
    )

    internal fun normalize(language: String?): String =
        AppLanguagePolicy.normalizeSaved(language)

    fun save(context: Context, language: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(LANGUAGE, normalize(language)).apply()
    }

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val explicit = LocaleManagerCompat.getApplicationLocales(context).toLanguageTags()
        if (!preferences.contains(LANGUAGE) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ system app-language settings are authoritative, including resetting to
            // system default (which means English for this product). Earlier Android versions
            // migrate once, then use our persisted choice and AppCompat's locale application.
            save(context, AppLanguagePolicy.fromExplicitLocales(explicit))
        }
        applySaved(context)
    }

    fun applySaved(context: Context) {
        val language = saved(context)
        val locales = LocaleListCompat.forLanguageTags(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val platformLocales = LocaleList.forLanguageTags(language)
            if (manager.applicationLocales != platformLocales) {
                manager.applicationLocales = platformLocales
            }
        }
        AppCompatDelegate.setApplicationLocales(locales)
        // Services and notification channels use the application context rather than an activity.
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(
            android.content.res.Configuration(context.resources.configuration).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setLocales(LocaleList.forLanguageTags(language))
                } else {
                    setLocale(Locale.forLanguageTag(language))
                }
            }, context.resources.displayMetrics
        )
    }
}
