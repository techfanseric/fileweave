package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Checkable
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.FileListFragment
import me.zhanghai.android.files.filelist.FileViewType
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.File

/** Exercises real list touch dispatch with generated cache files, never user storage. */
class FileListGestureInstrumentation : Instrumentation() {
    private var testView = FileViewType.LIST
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        arguments?.getString("view")?.let { testView = FileViewType.valueOf(it) }
        start()
    }

    override fun onStart() {
        val directory = File(targetContext.cacheDir, "drag-selection-${SystemClock.uptimeMillis()}")
        val originalView = Settings.FILE_LIST_VIEW_TYPE.valueCompat
        val originalDrawer = Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
        var activity: FileListActivity? = null
        try {
            check(directory.mkdir())
            repeat(100) { File(directory, "%03d.txt".format(it)).writeText("FileWeave gesture fixture") }
            runOnMainSync {
                Settings.FILE_LIST_VIEW_TYPE.putValue(testView)
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(false)
            }
            activity = startActivitySync(FileListActivity.createViewIntent(Paths.get(directory.toURI()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FileListActivity
            val screen = activity
            val rv = screen.findViewById<ViewGroup>(R.id.contentLayout).findViewById<RecyclerView>(R.id.recyclerView)
            val fragment = screen.supportFragmentManager.fragments.filterIsInstance<FileListFragment>().single()
            await { rv.adapter?.itemCount == 100 && rv.childCount >= 4 }
            fun checked(position: Int): Boolean = (rv.findViewHolderForAdapterPosition(position)?.itemView as? Checkable)?.isChecked == true
            var x = 0f
            var x0 = 0f
            var x1 = 0f
            var x3 = 0f
            var y0 = 0f
            var y3 = 0f
            var y1 = 0f
            runOnMainSync {
                x = rv.width * .45f
                fun center(position: Int): Float {
                    val view = checkNotNull(rv.findViewHolderForAdapterPosition(position)).itemView
                    return (view.top + view.bottom) / 2f
                }
                y0 = center(0); y1 = center(1); y3 = center(3)
                fun centerX(position: Int): Float {
                    val view = checkNotNull(rv.findViewHolderForAdapterPosition(position)).itemView
                    return (view.left + view.right) / 2f
                }
                x0 = centerX(0); x1 = centerX(1); x3 = centerX(3)
            }
            var down = SystemClock.uptimeMillis()
            runOnMainSync { send(rv, down, MotionEvent.ACTION_DOWN, x0, y0) }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200)
            runOnMainSync {
                check(checked(0)) { "Long press did not select anchor" }
                send(rv, down, MotionEvent.ACTION_MOVE, x3, y3)
            }
            await { (0..3).all { checked(it) } }
            runOnMainSync { send(rv, down, MotionEvent.ACTION_MOVE, x1, y1) }
            await { checked(0) && checked(1) && !checked(2) && !checked(3) }
            runOnMainSync {
                send(rv, down, MotionEvent.ACTION_UP, x1, y1)
                fragment.clearSelectedFiles()
            }
            await { !checked(0) && !checked(1) }
            // Normal scrolling remains a scroll, including after leaving selection mode.
            down = SystemClock.uptimeMillis()
            runOnMainSync {
                send(rv, down, MotionEvent.ACTION_DOWN, x, rv.height * .75f)
                send(rv, down, MotionEvent.ACTION_MOVE, x, rv.height * .70f)
                send(rv, down, MotionEvent.ACTION_MOVE, x, rv.height * .25f)
                send(rv, down, MotionEvent.ACTION_UP, x, rv.height * .25f)
            }
            await { (rv.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > 0 }
            // Edge dragging scrolls and cancel stops the animation loop.
            var before = 0
            runOnMainSync {
                rv.stopScroll()
                before = (rv.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                val child = rv.getChildAt(rv.childCount / 2)
                x = child.left + child.width * .4f
                y1 = (child.top + child.bottom) / 2f
            }
            down = SystemClock.uptimeMillis()
            runOnMainSync { send(rv, down, MotionEvent.ACTION_DOWN, x, y1) }
            Thread.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 200)
            runOnMainSync { send(rv, down, MotionEvent.ACTION_MOVE, x, rv.height - 2f) }
            await { (rv.layoutManager as GridLayoutManager).findFirstVisibleItemPosition() > before + 2 }
            var stoppedAt = 0
            runOnMainSync {
                send(rv, down, MotionEvent.ACTION_CANCEL, x, rv.height - 2f)
                stoppedAt = rv.computeVerticalScrollOffset()
            }
            Thread.sleep(200)
            runOnMainSync {
                check(rv.computeVerticalScrollOffset() == stoppedAt) { "Auto-scroll continued after cancel" }
                fragment.clearSelectedFiles()
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream",
                "PASS ($testView): long press, range selection, retract, ordinary scrolling, edge auto-scroll, cancel\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${error.stackTraceToString()}\n") })
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            runOnMainSync {
                Settings.FILE_LIST_VIEW_TYPE.putValue(originalView)
                Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(originalDrawer)
            }
            directory.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync { ready = condition() }
            Thread.sleep(50)
        }
        check(ready) { "List state did not become ready" }
    }

    private fun send(rv: RecyclerView, down: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
            rv.dispatchTouchEvent(it)
            it.recycle()
        }
    }
}
