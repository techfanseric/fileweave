package me.zhanghai.android.files.filelist

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/** Long-press arms selection; ordinary drags remain RecyclerView scrolling gestures. */
internal class DragSelectTouchListener(
    private val recyclerView: RecyclerView,
    private val selectRange: (Int, Int) -> Unit,
    private val endSelection: () -> Unit
) : RecyclerView.SimpleOnItemTouchListener(), Runnable {
    private var pointerId = -1
    private var anchor = RecyclerView.NO_POSITION
    private var dragging = false
    private var x = 0f
    private var y = 0f

    fun start(position: Int) {
        if (pointerId == -1) return
        anchor = position
    }

    fun stop() {
        anchor = RecyclerView.NO_POSITION
        pointerId = -1
        dragging = false
        recyclerView.removeCallbacks(this)
        recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
        endSelection()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stop()
            pointerId = event.getPointerId(0)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            stop()
            return false
        }
        if (anchor == RecyclerView.NO_POSITION || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        dragging = true
        recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
        update(event)
        recyclerView.removeCallbacks(this)
        recyclerView.postOnAnimation(this)
        return true
    }

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == pointerId)) {
            stop()
        } else {
            update(event)
        }
    }

    private fun update(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) {
            stop()
            return
        }
        x = event.getX(index)
        y = event.getY(index)
        updateRange()
    }

    private fun updateRange() {
        if (anchor == RecyclerView.NO_POSITION || recyclerView.width == 0 || recyclerView.height == 0) return
        val child = recyclerView.findChildViewUnder(
            x.coerceIn(0f, recyclerView.width - 1f),
            y.coerceIn(recyclerView.paddingTop.toFloat(), (recyclerView.height - recyclerView.paddingBottom - 1f)
                .coerceAtLeast(recyclerView.paddingTop.toFloat()))
        ) ?: return
        val position = recyclerView.getChildAdapterPosition(child)
        if (position != RecyclerView.NO_POSITION) selectRange(anchor, position)
    }

    override fun run() {
        if (!dragging) return
        val density = recyclerView.resources.displayMetrics.density
        val edge = 56 * density
        val bottom = recyclerView.height - recyclerView.paddingBottom
        val speed = when {
            y < recyclerView.paddingTop + edge -> -((recyclerView.paddingTop + edge - y) / edge).coerceIn(0f, 1f)
            y > bottom - edge -> ((y - bottom + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (speed != 0f) {
            recyclerView.scrollBy(0, (speed * 16 * density).toInt())
            updateRange()
        }
        recyclerView.postOnAnimation(this)
    }
}
