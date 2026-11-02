package me.zhanghai.android.files.lanshare

import java.util.concurrent.TimeUnit

/** Service-lifetime convenience gate; the resource URL remains a separate strong grant. */
class LanShareManualAccess(
    private var code: String,
    private val nanoTime: () -> Long = System::nanoTime
) {
    enum class Result { GRANTED, INVALID, THROTTLED }
    private var windowStartedAt = nanoTime()
    private var attempts = 0
    init { require(code.matches(Regex("[0-9]{6}"))) }

    /** Serializes renewal with all GET/POST attempts, including requests still reading a body. */
    @Synchronized
    fun renew(newCode: String) {
        require(newCode.matches(Regex("[0-9]{6}")) && newCode != code)
        code = newCode
        windowStartedAt = nanoTime()
        attempts = 0
    }

    @Synchronized
    fun authorize(candidate: String?): Result {
        val now = nanoTime()
        if (now - windowStartedAt >= TimeUnit.MINUTES.toNanos(1)) {
            windowStartedAt = now
            attempts = 0
        }
        if (attempts >= 5) return Result.THROTTLED
        ++attempts // Malformed and missing values consume the same global budget.
        return if (candidate == code) Result.GRANTED else Result.INVALID
    }
}
