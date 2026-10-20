package me.zhanghai.android.files.vault

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A ZIP of ciphertext files only. The source directory is never decrypted for backup. */
object VaultBackup {
    fun export(root: File, output: OutputStream) {
        require(File(root, "gocryptfs.conf").isFile)
        ZipOutputStream(output).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                // Avoid backing up a symlink outside the private vault directory.
                if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw IOException("Invalid vault entry")
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Restores only into an absent vault; failed or malicious ZIPs never replace user data. */
    fun restore(input: InputStream, root: File, maxBytes: Long = 8L * 1024 * 1024 * 1024) {
        if (root.exists() && root.list()?.isNotEmpty() != false) throw IOException("Vault already exists")
        val staging = File(root.parentFile, ".vault-restore-${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            var total = 0L
            var entries = 0
            val seen = mutableSetOf<String>()
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > 100000 || !seen.add(entry.name) || entry.name.startsWith('/') || entry.name.length > 4096 || '\\' in entry.name || entry.name.split('/').any { it == ".." || it == "." } || entry.name.split('/').size > 64) throw IOException("Invalid backup")
                    val file = File(staging, entry.name)
                    if (!file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) throw IOException("Invalid backup path")
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { output ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > maxBytes) throw IOException("Backup exceeds size limit")
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!File(staging, "gocryptfs.conf").isFile || File(staging, "gocryptfs.diriv").length() != 16L) throw IOException("Not a supported vault backup")
            if (root.exists() && !root.delete()) throw IOException("Destination unavailable")
            if (!staging.renameTo(root)) throw IOException("Unable to finalize restored vault")
        } finally { staging.deleteRecursively() }
    }
}
