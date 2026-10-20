package me.zhanghai.android.files.viewer.video

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Uses view coordinates, so left/right always follow the current display orientation. */
class VideoGestureLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    enum class Kind { SEEK, BRIGHTNESS, VOLUME }
    interface Listener {
        fun onDown()
        fun onStart(kind: Kind): Boolean
        fun onMove(kind: Kind, dx: Float, dy: Float)
        fun onEnd(kind: Kind, commit: Boolean)
        fun onTap()
        fun onDoubleTap(right: Boolean)
    }
    var listener: Listener? = null
    // Controls keep their taps; their horizontal/vertical drags still participate in gestures.
    var isControlHit: ((MotionEvent) -> Boolean)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var kind: Kind? = null
    private var ownsTap = false
    private var multiTouch = false
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            listener?.onDoubleTap(e.x >= width / 2f)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            downX = event.x; downY = event.y; multiTouch = false
            listener?.onDown()
            ownsTap = isControlHit?.invoke(event) != true
            return ownsTap
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !multiTouch) {
            return beginOrMove(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true
            cancelGesture()
        }
        return kind != null
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A time bar may request exclusive input on DOWN; horizontal drags still belong to
        // the whole-screen gesture layer. Its tap and accessibility actions remain available.
        super.requestDisallowInterceptTouchEvent(false)
    }

    // Single taps call performClick from onSingleTapConfirmed, after excluding double taps.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> taps.onTouchEvent(event)
            MotionEvent.ACTION_MOVE -> {
                if (!multiTouch && beginOrMove(event)) cancelTap(event)
                else if (ownsTap && !multiTouch) taps.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                val current = kind
                if (current != null) {
                    move(event, current)
                    kind = null
                    listener?.onEnd(current, true)
                } else if (ownsTap && !multiTouch) taps.onTouchEvent(event)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelTap(event)
                cancelGesture()
            }
        }
        return true
    }

    private fun beginOrMove(event: MotionEvent): Boolean {
        if (kind == null) {
            val dx = event.x - downX
            val dy = event.y - downY
            if (maxOf(abs(dx), abs(dy)) <= slop) return false
            val selected = if (abs(dx) >= abs(dy)) Kind.SEEK
                else if (downX < width / 2f) Kind.BRIGHTNESS else Kind.VOLUME
            if (listener?.onStart(selected) != true) return false
            kind = selected
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        move(event, checkNotNull(kind))
        return true
    }

    private fun move(event: MotionEvent, current: Kind) {
        listener?.onMove(current, (event.x - downX) / width.coerceAtLeast(1),
            (downY - event.y) / height.coerceAtLeast(1))
    }

    private fun cancelTap(event: MotionEvent) {
        MotionEvent.obtain(event).let { it.action = MotionEvent.ACTION_CANCEL; taps.onTouchEvent(it); it.recycle() }
    }

    fun cancelGesture() {
        kind?.let { kind = null; listener?.onEnd(it, false) }
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onTap()
        return true
    }

    fun hits(view: android.view.View, event: MotionEvent): Boolean {
        val rect = Rect()
        return view.isShown && view.getGlobalVisibleRect(rect) && rect.contains(event.rawX.toInt(), event.rawY.toInt())
    }
}
