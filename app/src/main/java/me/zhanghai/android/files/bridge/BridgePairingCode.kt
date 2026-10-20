package me.zhanghai.android.files.bridge

import java.security.MessageDigest
import java.util.Locale

/** Human comparison binds the enrollment nonce to the exact TLS certificate. */
internal object BridgePairingCode {
    fun create(pin: String, nonce: String): String {
        require(pin.matches(Regex("[a-f0-9]{64}")) && nonce.matches(Regex("[A-Za-z0-9_-]{43}")))
        return MessageDigest.getInstance("SHA-256").digest((pin + ":" + nonce).toByteArray(Charsets.US_ASCII))
            .take(6).joinToString("") { "%02X".format(Locale.ROOT, it) }.chunked(4).joinToString("-")
    }
}
