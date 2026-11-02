package me.zhanghai.android.files.filelist

import android.content.Context
import java.io.File
import me.zhanghai.android.files.app.application

object RecentFilesSettings {
    private val preferences
        get() = application.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun read(): RecentFileRules {
        val mode = runCatching {
            RecentFileRules.Mode.valueOf(preferences.getString("mode", "ALL")!!)
        }.getOrDefault(RecentFileRules.Mode.ALL)
        return RecentFileRules(mode, preferences.getStringSet("roots", emptySet())!!
            .map { runCatching { File(it).canonicalPath }.getOrDefault(it) }.distinct().sorted())
    }

    fun write(rules: RecentFileRules) {
        preferences.edit().putString("mode", rules.mode.name)
            .putStringSet("roots", rules.roots.toSet()).apply()
    }
}
