/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference

class LocalePreference : ListPreference {
    lateinit var setApplicationLocales: (LocaleListCompat) -> Unit

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int,
        @StyleRes defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        setDefaultValue(AppLanguage.ENGLISH)
        entries = arrayOf("English", "简体中文")
        entryValues = arrayOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)
        summaryProvider = SimpleSummaryProvider.getInstance()
    }

    override fun getPersistedString(defaultReturnValue: String?): String =
        AppLanguage.selected(context)

    override fun persistString(value: String?): Boolean {
        val language = AppLanguage.normalize(value)
        if (language != AppLanguage.selected(context)) {
            AppLanguage.save(context, language)
            setApplicationLocales(LocaleListCompat.forLanguageTags(language))
        }
        return true
    }
}
