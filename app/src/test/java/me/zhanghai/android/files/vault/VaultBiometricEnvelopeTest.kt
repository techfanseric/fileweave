package me.zhanghai.android.files.vault

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class VaultBiometricEnvelopeTest {
    @Test fun roundTripAuthenticatesVaultAndCiphertext() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val id = ByteArray(32) { it.toByte() }
        val secret = "correct horse battery staple".toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(id) }
        val encoded = VaultBiometricEnvelope.wrap(cipher, secret, id).encode()
        val envelope = VaultBiometricEnvelope.decode(encoded)
        envelope.requireVault(id)
        fun decrypt(aad: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv)); updateAAD(aad); doFinal(data)
        }
        assertArrayEquals(secret, decrypt(id, envelope.ciphertext))
        assertThrows(java.io.IOException::class.java) { envelope.requireVault(ByteArray(32)) }
        assertThrows(javax.crypto.AEADBadTagException::class.java) { decrypt(ByteArray(32), envelope.ciphertext) }
        val changed = envelope.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(javax.crypto.AEADBadTagException::class.java) { decrypt(id, changed) }
        assertFalse(encoded.toString(Charsets.ISO_8859_1).contains(secret.toString(Charsets.UTF_8)))
    }
    @Test fun malformedBindingsFailClosed() {
        for (size in listOf(0, 20, 68, 9000)) {
            assertThrows(java.io.IOException::class.java) { VaultBiometricEnvelope.decode(ByteArray(size)) }
        }
    }
}
