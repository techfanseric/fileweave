package me.zhanghai.android.files.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgeRetryTest {
    @Test fun failuresBackOffAndAreCapped() {
        val retry = BridgeRetry()
        var time = 100L
        for (delay in listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L)) {
            assertTrue(retry.ready(time))
            retry.failed(time)
            assertFalse(retry.ready(time + delay - 1))
            time += delay
            assertTrue(retry.ready(time))
        }
    }
    @Test fun recoveryResetsDelayAndAllowsImmediateConnection() {
        val retry = BridgeRetry()
        repeat(10) { retry.failed(100) }
        retry.reset()
        assertTrue(retry.ready(100))
        retry.failed(100)
        assertFalse(retry.ready(1099))
        assertTrue(retry.ready(1100))
    }
}
