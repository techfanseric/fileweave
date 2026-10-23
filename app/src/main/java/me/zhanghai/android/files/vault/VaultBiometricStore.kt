package me.zhanghai.android.files.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext credential on disk. Every encrypt/decrypt needs a CryptoObject biometric operation. */
internal class VaultBiometricStore(
    private val root: File,
    bindingFile: File,
    private val alias: String = "fileweave.vault.biometric.v1"
) {
    private val binding = AtomicFile(bindingFile)
    fun isEnabled(): Boolean = binding.baseFile.exists()
    fun vaultId(): ByteArray = MessageDigest.getInstance("SHA-256").digest(File(root, "gocryptfs.conf").readBytes())

    fun createEncryptionCipher(): Cipher {
        disable()
        val parameters = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            parameters.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            parameters.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= 24) parameters.setInvalidatedByBiometricEnrollment(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(parameters.build())
        return cipher().apply { init(Cipher.ENCRYPT_MODE, generator.generateKey()); updateAAD(vaultId()) }
    }

    fun read(): VaultBiometricEnvelope = VaultBiometricEnvelope.decode(binding.openRead().use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > 8192) throw IOException("Invalid biometric binding")
            bytes.write(buffer, 0, count)
        }
        bytes.toByteArray()
    }).also { it.requireVault(vaultId()) }

    fun createDecryptionCipher(envelope: VaultBiometricEnvelope): Cipher {
        envelope.requireVault(vaultId())
        val key = keyStore().getKey(alias, null) as? SecretKey ?: throw IOException("Biometric key unavailable")
        return cipher().apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
            updateAAD(envelope.vaultId)
        }
    }

    fun save(cipher: Cipher, password: ByteArray, expectedId: ByteArray) {
        if (!MessageDigest.isEqual(expectedId, vaultId())) throw IOException("Vault changed during enrollment")
        val bytes = VaultBiometricEnvelope.wrap(cipher, password, expectedId).encode()
        val output = binding.startWrite()
        try { output.write(bytes); binding.finishWrite(output) }
        catch (error: Exception) { binding.failWrite(output); throw error }
    }

    fun disable() {
        // Removing the wrapped credential also revokes access if Keystore deletion fails.
        binding.delete()
        keyStore().deleteEntry(alias)
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding")
}
