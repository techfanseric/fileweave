package me.zhanghai.android.files.bridge

import org.junit.Assert.*
import org.junit.Test

class BridgePairingCodeTest {
    @Test fun agreesWithMacAndBindsBothInputs() {
        assertEquals("7311-256C-6DE9", BridgePairingCode.create("0".repeat(64), "A".repeat(43)))
        assertNotEquals(BridgePairingCode.create("0".repeat(64), "A".repeat(43)),
            BridgePairingCode.create("1".repeat(64), "A".repeat(43)))
        assertNotEquals(BridgePairingCode.create("0".repeat(64), "A".repeat(43)),
            BridgePairingCode.create("0".repeat(64), "B".repeat(43)))
    }
    @Test fun malformedCertificateAndNonceAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BridgePairingCode.create("invalid", "A".repeat(43)) }
        assertThrows(IllegalArgumentException::class.java) { BridgePairingCode.create("0".repeat(64), "short") }
    }
}
