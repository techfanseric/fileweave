package me.zhanghai.android.files.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class VaultBackupTest {
    @Test fun encryptedBackupRoundTripAndExistingVaultProtection() {
        val directory = Files.createTempDirectory("fileweave-test").toFile()
        try {
            val original = File(directory, "source").apply { mkdir() }
            File(original, "gocryptfs.conf").writeText("encrypted-key-config")
            File(original, "gocryptfs.diriv").writeBytes(ByteArray(16) { it.toByte() })
            File(original, "ciphertext-name").writeBytes(ByteArray(16384) { (it % 251).toByte() })
            val output = ByteArrayOutputStream()
            VaultBackup.export(original, output)
            val restored = File(directory, "restored")
            VaultBackup.restore(ByteArrayInputStream(output.toByteArray()), restored)
            original.listFiles()!!.forEach { assertArrayEquals(it.readBytes(), File(restored, it.name).readBytes()) }
            assertThrows(IOException::class.java) { VaultBackup.restore(ByteArrayInputStream(output.toByteArray()), restored) }
            assertEquals("encrypted-key-config", File(restored, "gocryptfs.conf").readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun maliciousAndOversizedBackupsAreRejectedWithoutPartialVault() {
        val directory = Files.createTempDirectory("fileweave-test").toFile()
        try {
            for (name in listOf("../escape", "/escape", "x\\escape", "ordinary")) {
                val output = ByteArrayOutputStream()
                ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write(ByteArray(2048)); zip.closeEntry() }
                val root = File(directory, "vault")
                assertThrows(IOException::class.java) { VaultBackup.restore(ByteArrayInputStream(output.toByteArray()), root, 1024) }
                assertFalse(root.exists())
                assertFalse(File(directory, "escape").exists())
                assertTrue(directory.list()!!.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }
}
