package me.zhanghai.android.files.folderbridge

import android.content.Context
import androidx.lifecycle.MutableLiveData
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.bridge.BridgeStore
import me.zhanghai.android.files.lanshare.LanShareBackend
import me.zhanghai.android.files.lanshare.LanShareKernel
import java8.nio.file.Path
import java8.nio.file.Paths

/** Independent user choices. Explicit selections never change the global defaults. */
object PairedShares {
    private val preferences get() = application.getSharedPreferences("paired_shares", Context.MODE_PRIVATE)
    val changes = MutableLiveData(0L)
    var files: Boolean
        get() = preferences.getBoolean("files", true)
        set(value) { preferences.edit().putBoolean("files", value).apply(); changed() }
    var recordings: Boolean
        get() = preferences.getBoolean("recordings", true)
        set(value) { preferences.edit().putBoolean("recordings", value).apply(); changed() }
    @Volatile private var selection: LanShareBackend? = null
    @Volatile var selectionVersion = 0L
        private set
    fun select(paths: List<Path>) {
        selection = LanShareBackend(paths)
        selectionVersion = System.currentTimeMillis()
        changes.postValue(selectionVersion)
    }
    fun clearSelection() { selection = null; selectionVersion = System.currentTimeMillis(); changes.postValue(selectionVersion) }
    private fun changed() {
        changes.value = System.currentTimeMillis()
        if (!files && !recordings && selection == null) FolderBridgeService.stop(application)
        else ensureStarted(application)
    }
    fun ensureStarted(context: Context) {
        if ((files || recordings || selection != null) && BridgeStore.read(context) != null &&
            FolderBridgeService.statusLiveData.value?.state == FolderBridgeService.State.STOPPED) {
            FolderBridgeService.start(context)
        }
    }
    fun backend(selected: Boolean): LanShareKernel.Backend? {
        val delegate = if (selected) selection else if (files) {
            LanShareBackend(listOf(Paths.get(android.os.Environment.getExternalStorageDirectory().absolutePath)), "手机存储")
        } else null
        delegate ?: return null
        return object : LanShareKernel.Backend {
            private fun check() { check(if (selected) selection === delegate else files) { "Sharing stopped" } }
            override fun stat(path: List<String>): LanShareKernel.Entry? { check(); return delegate.stat(path) }
            override fun list(path: List<String>): List<LanShareKernel.Entry> { check(); return delegate.list(path) }
            override fun open(path: List<String>, offset: Long): java.io.InputStream {
                check()
                return object : java.io.FilterInputStream(delegate.open(path, offset)) {
                    override fun read(): Int { check(); return super.read() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return `in`.read(b, off, len) }
                }
            }
        }
    }
}
