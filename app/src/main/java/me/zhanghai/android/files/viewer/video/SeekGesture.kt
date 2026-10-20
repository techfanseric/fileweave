package me.zhanghai.android.files.viewer.video

import kotlin.math.min
import kotlin.math.roundToLong

/** One screen spans at most two minutes; short clips span their full duration. */
internal object SeekGesture {
    fun target(start: Long, duration: Long, distance: Float, width: Int): Long {
        if (duration <= 0 || width <= 0) return start
        val delta = (distance.toDouble() / width * min(duration, 120_000L)).roundToLong()
        return (start + delta).coerceIn(0, duration)
    }
}
