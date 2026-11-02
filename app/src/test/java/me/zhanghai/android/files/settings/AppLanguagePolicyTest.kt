package me.zhanghai.android.files.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguagePolicyTest {
    @Test fun freshInstallAndResetToSystemDefaultAlwaysUseEnglish() {
        // No parameter accepts the device language: Chinese Android cannot change this result.
        assertEquals("en", AppLanguagePolicy.fromExplicitLocales(null))
        assertEquals("en", AppLanguagePolicy.fromExplicitLocales(""))
    }

    @Test fun explicitSystemAndUpstreamSelectionsMapToSupportedLanguages() {
        for (tag in listOf("zh", "zh-CN", "zh-Hans-CN", "zh-TW", "zh-CN,en")) {
            assertEquals(tag, "zh-CN", AppLanguagePolicy.fromExplicitLocales(tag))
        }
        for (tag in listOf("en", "en-US", "fr", "en,zh-CN", "invalid")) {
            assertEquals(tag, "en", AppLanguagePolicy.fromExplicitLocales(tag))
        }
    }

    @Test fun savedChoicePersistsWithoutFollowingDeviceLanguage() {
        assertEquals("zh-CN", AppLanguagePolicy.normalizeSaved("zh-CN"))
        assertEquals("en", AppLanguagePolicy.normalizeSaved("en"))
        assertEquals("en", AppLanguagePolicy.normalizeSaved(null))
        assertEquals("en", AppLanguagePolicy.normalizeSaved("garbage"))
    }
}
