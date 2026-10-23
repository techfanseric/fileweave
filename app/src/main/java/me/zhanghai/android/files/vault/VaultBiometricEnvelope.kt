package me.zhanghai.android.files.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher

/** Device-local wrapping envelope, separate from the standard gocryptfs vault/backup format. */
internal class VaultBiometricEnvelope(val vaultId: ByteArray, val iv: ByteArray, val ciphertext: ByteArray) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC); it.write(vaultId); it.write(iv)
            it.writeInt(ciphertext.size); it.write(ciphertext)
        }
    }.toByteArray()

    fun requireVault(expectedId: ByteArray) {
        if (!MessageDigest.isEqual(vaultId, expectedId)) throw IOException("Vault binding changed")
    }

    companion object {
        private const val MAGIC = 0x46574231
        private const val MAX_PASSWORD_BYTES = 4096
        fun wrap(cipher: Cipher, password: ByteArray, vaultId: ByteArray): VaultBiometricEnvelope {
            require(password.isNotEmpty() && password.size <= MAX_PASSWORD_BYTES)
            require(vaultId.size == 32 && cipher.iv.size == 12)
            return VaultBiometricEnvelope(vaultId.copyOf(), cipher.iv, cipher.doFinal(password))
        }
        fun decode(bytes: ByteArray): VaultBiometricEnvelope {
            if (bytes.size !in 69..(64 + MAX_PASSWORD_BYTES + 16)) throw IOException("Invalid biometric binding")
            DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readInt() != MAGIC) throw IOException("Unknown biometric binding")
                val id = ByteArray(32).also(it::readFully)
                val iv = ByteArray(12).also(it::readFully)
                val length = it.readInt()
                if (length !in 17..(MAX_PASSWORD_BYTES + 16) || length != it.available()) throw IOException("Invalid biometric binding")
                return VaultBiometricEnvelope(id, iv, ByteArray(length).also(it::readFully))
            }
        }
    }
}
