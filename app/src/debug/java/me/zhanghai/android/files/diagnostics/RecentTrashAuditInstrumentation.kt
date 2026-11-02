package me.zhanghai.android.files.diagnostics

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.Observer
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filelist.RecentFilesLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import java.util.concurrent.atomic.AtomicReference

/** Read-only audit of the installed recent source. No fixtures or preference writes. */
class RecentTrashAuditInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var source: RecentFilesLiveData? = null
        val result = AtomicReference<Stateful<List<FileItem>>>()
        val observer = Observer<Stateful<List<FileItem>>> { result.set(it) }
        var code = 0
        var report: String
        try {
            @Suppress("DEPRECATION")
            val rawGalleryTrash = targetContext.contentResolver.query(
                MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID),
                "LOWER(${MediaStore.MediaColumns.DATA}) LIKE ?", arrayOf("%/.gallery2/recycle/%"), null
            )?.use { it.count } ?: error("Index query failed")
            runOnMainSync {
                source = RecentFilesLiveData()
                source!!.observeForever(observer)
            }
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (result.get() !is Success) {
                val current = result.get()
                if (current is Failure) throw current.throwable
                check(SystemClock.elapsedRealtime() < deadline) { "Recent source timed out" }
                SystemClock.sleep(50)
            }
            val files = (result.get() as Success).value
            // Independent check for the OEM layout observed on the real screen.
            val leaked = files.count {
                val path = it.path.toString().lowercase(java.util.Locale.ROOT)
                path.contains("/.gallery2/recycle/") || path.contains("/recycle/bin/") || path.contains("/recycle/bins/")
            }
            check(leaked == 0) { "OEM recycle files still present in recent source: $leaked" }
            report = "PASS: indexed_gallery_recycle=$rawGalleryTrash, recent_first_page=${files.size}, recycle_leaks=$leaked"
        } catch (error: Throwable) {
            code = 1
            report = "FAIL: ${error.javaClass.simpleName}"
        } finally {
            runOnMainSync { source?.removeObserver(observer); source?.close() }
        }
        finish(code, Bundle().apply { putString("result", report) })
    }
}
