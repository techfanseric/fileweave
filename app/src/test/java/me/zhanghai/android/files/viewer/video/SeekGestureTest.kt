package me.zhanghai.android.files.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekGestureTest {
    @Test fun longVideosUseTwoMinuteSpanAndClampAtBothEnds() {
        assertEquals(90_000, SeekGesture.target(30_000, 3_600_000, 500f, 1000))
        assertEquals(0, SeekGesture.target(30_000, 3_600_000, -500f, 1000))
        assertEquals(3_600_000, SeekGesture.target(3_590_000, 3_600_000, 500f, 1000))
    }
    @Test fun shortVideosScaleToDurationAndInvalidDimensionsDoNotSeek() {
        assertEquals(15_000, SeekGesture.target(5_000, 20_000, 500f, 1000))
        assertEquals(5_000, SeekGesture.target(5_000, -1, 500f, 1000))
        assertEquals(5_000, SeekGesture.target(5_000, 20_000, 500f, 0))
    }
}
