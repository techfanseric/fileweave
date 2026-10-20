package me.zhanghai.android.files.bridge

/** Monotonic retry pacing shared by foreground connection recovery. */
internal class BridgeRetry {
    private var delay = 1_000L
    private var next = 0L
    fun ready(now: Long) = now >= next
    fun failed(now: Long) { next = now + delay; delay = (delay * 2).coerceAtMost(30_000L) }
    fun reset() { delay = 1_000L; next = 0L }
}
