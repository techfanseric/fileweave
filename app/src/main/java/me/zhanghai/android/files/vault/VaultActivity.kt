package me.zhanghai.android.files.vault

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
        if (!openingVideo && !isChangingConfigurations) VaultSession.lock()
        super.onStop()
    }

    private fun render() {
        val epoch = ++renderEpoch
        screen(getString(R.string.fw_vault_title))
        if (busy) { text(getString(R.string.fw_working)); return }
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
                work {
                    VaultSession.unlock(root, password, !exists)
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { VaultSession.lock(); return@work "" }
                    getString(R.string.fw_vault_unlocked)
                }
            }
            if (!exists) button(getString(R.string.fw_vault_restore)) { restore.launch(arrayOf("application/zip", "application/octet-stream")) }
            if (exists) button(getString(R.string.fw_vault_backup)) { backup.launch("FileWeave-vault.zip") }
            return
        }
        text(getString(R.string.fw_vault_open))
        button(getString(R.string.fw_lock)) { VaultSession.lock(); render() }
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
