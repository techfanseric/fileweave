package me.zhanghai.android.files.lanshare

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class LanShareManualAccessTest {
    @Test fun globalBudgetIncludesMalformedAndMissingCodes() {
        var now = 0L
        val access = LanShareManualAccess("042198") { now }
        repeat(5) { assertEquals(LanShareManualAccess.Result.INVALID, access.authorize(null)) }
        assertEquals(LanShareManualAccess.Result.THROTTLED, access.authorize("042198"))
        now = TimeUnit.MINUTES.toNanos(1)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("042198"))
    }
    @Test fun codeRemainsValidAfterElevenMinutesAndOneDay() {
        var now = 0L
        val access = LanShareManualAccess("123456") { now }
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("123456"))
        now = TimeUnit.MINUTES.toNanos(9)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("123456"))
        now = TimeUnit.MINUTES.toNanos(11)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("123456"))
        now = TimeUnit.HOURS.toNanos(24)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("123456"))
    }
    @Test fun concurrentClientsShareOneAttemptBudget() {
        val access = LanShareManualAccess("123456") { 0L }
        val answers = java.util.Collections.synchronizedList(mutableListOf<LanShareManualAccess.Result>())
        val threads = List(20) { Thread { answers += access.authorize("000000") }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(5, answers.count { it == LanShareManualAccess.Result.INVALID })
        assertEquals(15, answers.count { it == LanShareManualAccess.Result.THROTTLED })
    }
    @Test fun renewalReplacesCodeAndResetsRateBudget() {
        var now = 0L
        val access = LanShareManualAccess("123456") { now }
        repeat(5) { access.authorize("wrong") }
        now = TimeUnit.MINUTES.toNanos(11)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("123456"))
        access.renew("654321")
        assertEquals(LanShareManualAccess.Result.INVALID, access.authorize("123456"))
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("654321"))
        now = TimeUnit.MINUTES.toNanos(20)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("654321"))
        now = TimeUnit.MINUTES.toNanos(21)
        assertEquals(LanShareManualAccess.Result.GRANTED, access.authorize("654321"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun renewalMustInvalidatePreviousCode() {
        LanShareManualAccess("123456").renew("123456")
    }
}
