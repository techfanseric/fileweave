package me.zhanghai.android.files.vault

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import javax.crypto.Cipher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.video.VaultVideoActivity

class VaultActivity : FeatureActivity() {
    private val root by lazy { File(noBackupFilesDir, "vault") }
    private val biometricStore by lazy { VaultBiometricStore(root, File(noBackupFilesDir, "vault-biometric.bin")) }
    private lateinit var biometricPrompt: BiometricPrompt
    private var biometricAttempt: BiometricAttempt? = null
    private var authEpoch = 0
    private var enrollmentPassword: ByteArray? = null
    private class BiometricAttempt(val cipher: Cipher, val vaultId: ByteArray,
        val generation: Long, val envelope: VaultBiometricEnvelope?)
    private var busy = false
    private var openingVideo = false
    private var renderEpoch = 0
    private var exportName: String? = null
    private var exportDestination: android.net.Uri? = null
    private var pendingUri: android.net.Uri? = null
    private var pendingPath: java8.nio.file.Path? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pendingUri = uri; importPending() }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        exportDestination = uri
        if (uri != null) render()
    }
    private val backup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) work {
            VaultSession.lock()
            contentResolver.openOutputStream(uri)?.use { VaultBackup.export(root, it) } ?: throw IOException("Unable to write backup")
            getString(R.string.fw_vault_backup_done)
        }
    }

    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            VaultSession.lock()
            contentResolver.openInputStream(uri)?.use { VaultBackup.restore(it, root) } ?: throw IOException("Unable to read backup")
            getString(R.string.fw_vault_restore_done)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        VaultSession.observeBackground()
        biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val attempt = biometricAttempt ?: return
                biometricAttempt = null
                val password = enrollmentPassword
                enrollmentPassword = null
                try {
                    check(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    check(result.cryptoObject?.cipher === attempt.cipher)
                    check(VaultSession.generation() == attempt.generation)
                    if (attempt.envelope == null) {
                        biometricStore.save(attempt.cipher, checkNotNull(password), attempt.vaultId)
                        Toast.makeText(this@VaultActivity, R.string.fw_vault_biometric_enabled, Toast.LENGTH_LONG).show()
                        render()
                    } else {
                        attempt.envelope.requireVault(biometricStore.vaultId())
                        val secret = attempt.cipher.doFinal(attempt.envelope.ciphertext)
                        unlockWithPassword(secret, false, attempt.generation)
                    }
                } catch (_: Exception) {
                    biometricUnavailable()
                } finally { password?.fill(0) }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (biometricAttempt == null) return
                cancelBiometric()
                // Cancellation never opens a session or removes an existing valid binding.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    Toast.makeText(this@VaultActivity, R.string.fw_vault_biometric_retry_password, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        })
        pendingPath = intent.extraPath
        exportName = savedInstanceState?.getString("exportName")
        exportDestination = savedInstanceState?.getString("exportDestination")?.let(android.net.Uri::parse)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportName", exportName)
        outState.putString("exportDestination", exportDestination?.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); openingVideo = false; render() }
    override fun onStop() {
        authEpoch++
        cancelBiometric()
        if (!openingVideo && !isChangingConfigurations) VaultSession.lock()
        super.onStop()
    }

    private fun render() {
        val epoch = ++renderEpoch
        screen(getString(R.string.fw_vault_title))
        if (busy || biometricAttempt != null) { text(getString(R.string.fw_working)); return }
        val exists = File(root, "gocryptfs.conf").exists()
        if (!VaultSession.isOpen()) {
            text(getString(if (exists) R.string.fw_vault_locked else R.string.fw_vault_intro))
            val input = TextInputEditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                hint = getString(R.string.fw_password)
            }
            content.addView(TextInputLayout(this).apply { addView(input) })
            var confirm: TextInputEditText? = null
            if (!exists) {
                confirm = TextInputEditText(this).apply {
                    inputType = input.inputType; isSaveEnabled = false
                    if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    hint = getString(R.string.fw_password_confirm)
                }
                content.addView(TextInputLayout(this).apply { addView(confirm) })
            }
            button(getString(if (exists) R.string.fw_unlock else R.string.fw_vault_create)) {
                val chars = input.text?.toString().orEmpty()
                if (chars.isEmpty() || !exists && (chars.length < 12 || chars != confirm?.text?.toString())) {
                    input.error = getString(R.string.fw_password_invalid)
                    return@button
                }
                val password = chars.toByteArray(Charsets.UTF_8)
                input.text?.clear(); confirm?.text?.clear()
                unlockWithPassword(password, !exists, VaultSession.generation())
            }
            if (exists && biometricStore.isEnabled()) {
                button(getString(R.string.fw_vault_biometric_unlock)) { unlockBiometric() }
                text(getString(R.string.fw_vault_biometric_explanation))
            }
            if (!exists) button(getString(R.string.fw_vault_restore)) { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
            if (exists) button(getString(R.string.fw_vault_backup)) { backup.launch("FileWeave-vault.zip") }
            return
        }
        text(getString(R.string.fw_vault_open))
        button(getString(R.string.fw_lock)) { authEpoch++; cancelBiometric(); VaultSession.lock(); render() }
        if (biometricStore.isEnabled()) {
            button(getString(R.string.fw_vault_biometric_disable)) {
                try { biometricStore.disable(); render() } catch (_: Exception) { biometricUnavailable() }
            }
        } else {
            button(getString(R.string.fw_vault_biometric_enable)) { enrollBiometric() }
            text(getString(R.string.fw_vault_biometric_explanation))
        }
        button(getString(R.string.fw_vault_import)) { picker.launch(arrayOf("*/*")) }
        if (pendingUri != null || pendingPath != null) button(getString(R.string.fw_vault_import_pending)) { importPending() }
        button(getString(R.string.fw_vault_backup)) { backup.launch("FileWeave-vault.zip") }
        if (exportDestination != null && exportName != null) button(getString(R.string.fw_vault_export_pending)) {
            val destination = exportDestination!!
            val name = exportName!!
            work {
                try {
                    contentResolver.openOutputStream(destination)?.use { VaultSession.export("/$name", it) }
                        ?: throw IOException("Destination unavailable")
                } catch (e: Exception) {
                    try { android.provider.DocumentsContract.deleteDocument(contentResolver, destination) } catch (_: Exception) { }
                    throw e
                }
                exportDestination = null; exportName = null
                getString(R.string.fw_vault_export_done)
            }
        }
        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { VaultSession.list("/") }
                if (!VaultSession.isOpen() || busy || epoch != renderEpoch) return@launch
                if (entries.isEmpty()) text(getString(R.string.fw_vault_empty))
                entries.filter { !it.name.startsWith(".fileweave-import-") }.sortedBy { it.name.lowercase() }.forEach { entry ->
                    button(entry.name) { openEntry(entry) }.setOnLongClickListener { showEntryActions(entry); true }
                }
            } catch (e: Exception) { if (VaultSession.isOpen()) text(getString(R.string.fw_operation_failed)) }
        }
    }

    private fun unlockWithPassword(password: ByteArray, create: Boolean, expected: Long) {
        if (busy) { password.fill(0); return }
        work {
            VaultSession.unlock(root, password, create, expected)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { VaultSession.lock(); return@work "" }
            getString(R.string.fw_vault_unlocked)
        }
    }

    private fun biometricsAvailable() = BiometricManager.from(this).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricUnavailable(revoke: Boolean = true) {
        cancelBiometric()
        // An invalidated/missing key or modified binding requires a fresh master-password setup.
        if (revoke) runCatching { biometricStore.disable() }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.fw_vault_biometric_enable)
                .setMessage(R.string.fw_vault_biometric_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
            render()
        }
    }

    private fun cancelBiometric() {
        val enrolling = biometricAttempt?.envelope == null && biometricAttempt != null
        biometricAttempt = null
        enrollmentPassword?.fill(0); enrollmentPassword = null
        if (::biometricPrompt.isInitialized) biometricPrompt.cancelAuthentication()
        if (enrolling) runCatching { biometricStore.disable() }
    }

    private fun promptBiometric(attempt: BiometricAttempt) {
        biometricAttempt = attempt
        render()
        biometricPrompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(if (attempt.envelope == null) R.string.fw_vault_biometric_enable else R.string.fw_vault_biometric_unlock))
            .setSubtitle(getString(R.string.fw_vault_biometric_prompt))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(if (attempt.envelope == null) android.R.string.cancel else R.string.fw_vault_use_password))
            .build(), BiometricPrompt.CryptoObject(attempt.cipher))
    }

    private fun unlockBiometric() {
        if (busy || biometricAttempt != null) return
        if (!biometricsAvailable()) { biometricUnavailable(false); return }
        try {
            val envelope = biometricStore.read()
            promptBiometric(BiometricAttempt(biometricStore.createDecryptionCipher(envelope),
                envelope.vaultId, VaultSession.generation(), envelope))
        } catch (_: Exception) { biometricUnavailable() }
    }

    private fun enrollBiometric() {
        if (!biometricsAvailable()) { biometricUnavailable(); return }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            hint = getString(R.string.fw_password)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.fw_vault_biometric_enable)
            .setMessage(R.string.fw_vault_biometric_enroll)
            .setView(TextInputLayout(this).apply { addView(input) })
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnDismissListener { input.text?.clear() }
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text?.toString().orEmpty().toByteArray(Charsets.UTF_8)
                if (password.isEmpty() || password.size > 4096) {
                    password.fill(0); input.error = getString(R.string.fw_password_invalid); return@setOnClickListener
                }
                dialog.dismiss()
                val expected = VaultSession.generation()
                val epoch = authEpoch
                enrollmentPassword = password
                val verificationPassword = password.copyOf()
                busy = true; render()
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { VaultSession.unlock(root, verificationPassword, false, expected) }
                        check(epoch == authEpoch && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        val id = biometricStore.vaultId()
                        val cipher = biometricStore.createEncryptionCipher()
                        busy = false
                        promptBiometric(BiometricAttempt(cipher, id, VaultSession.generation(), null))
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        cancelBiometric()
                        if (epoch == authEpoch) {
                            Toast.makeText(this@VaultActivity, R.string.fw_vault_biometric_setup_failed, Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        verificationPassword.fill(0)
                        if (biometricAttempt == null) { password.fill(0); enrollmentPassword = null }
                        busy = false
                        if (!isDestroyed) render()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun importPending() {
        if (!VaultSession.isOpen()) { render(); return }
        val uri = pendingUri
        val path = pendingPath
        if (uri == null && path == null) return
        work {
            val name = if (path != null) path.fileName.toString() else {
                contentResolver.query(uri!!, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "imported-file"
            }
            val input = path?.newInputStream() ?: contentResolver.openInputStream(uri!!) ?: throw IOException("Unable to read source")
            input.use { VaultSession.import(it, name) }
            pendingUri = null; pendingPath = null
            getString(R.string.fw_vault_import_done)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openEntry(entry: VaultEntry) {
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts")) {
            openingVideo = true
            startActivity(Intent(this, VaultVideoActivity::class.java)
                .putExtra("vault_path", "/${entry.name}").putExtra("vault_generation", VaultSession.generation()))
        } else if (extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "txt", "md", "json", "csv", "log", "xml", "yaml", "yml")) {
            openingVideo = true
            startActivity(Intent(this, VaultPreviewActivity::class.java)
                .putExtra("vault_path", "/${entry.name}").putExtra("vault_generation", VaultSession.generation()))
        } else showEntryActions(entry)
    }

    private fun showEntryActions(entry: VaultEntry) {
        MaterialAlertDialogBuilder(this).setTitle(entry.name)
            .setMessage(getString(R.string.fw_vault_stored, entry.size))
            .setPositiveButton(R.string.fw_vault_export) { _, _ -> exportName = entry.name; exportPicker.launch(entry.name) }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.fw_vault_delete) { _, _ ->
                MaterialAlertDialogBuilder(this).setTitle(R.string.fw_vault_delete)
                    .setMessage(R.string.fw_vault_delete_confirm)
                    .setPositiveButton(R.string.fw_vault_delete) { _, _ -> work { VaultSession.delete("/${entry.name}"); "" } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.show()
    }

    private fun work(action: suspend () -> String) {
        if (busy) return
        busy = true; render()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                if (result.isNotBlank()) Toast.makeText(this@VaultActivity, result, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) MaterialAlertDialogBuilder(this@VaultActivity)
                    .setTitle(R.string.fw_operation_failed).setMessage(R.string.fw_vault_failure)
                    .setPositiveButton(android.R.string.ok, null).show()
            } finally { busy = false; if (!isDestroyed) render() }
        }
    }
}
