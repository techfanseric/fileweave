package me.zhanghai.android.files.provider.root

import androidx.lifecycle.MutableLiveData
import com.topjohnwu.superuser.Shell
import kotlin.concurrent.thread
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Whether a root shell can actually be obtained right now (or we are already running as
 * root/shell UID). The drawer's "根目录" entry only makes sense with this, so it is hidden
 * otherwise; checking spawns a shell once, done on a background thread at app start and on
 * root strategy changes.
 */
object RootAccessLiveData : MutableLiveData<Boolean>(if (isRunningAsRoot) true else false) {

    init {
        if (!isRunningAsRoot) {
            Settings.ROOT_STRATEGY.observeForever { check() }
        }
    }

    fun check() {
        if (isRunningAsRoot) {
            value = true
            return
        }
        if (Settings.ROOT_STRATEGY.valueCompat == RootStrategy.NEVER) {
            value = false
            return
        }
        thread(name = "root-access-check") {
            val available = try {
                val shell = Shell.getShell()
                shell.isRoot && Shell.isAppGrantedRoot() != false
            } catch (_: Exception) {
                false
            }
            postValue(available)
        }
    }
}
