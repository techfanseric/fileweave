package me.zhanghai.android.files.lanshare

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LanSharePathPolicyTest {
    private val policy = LanSharePathPolicy(listOf("/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb"))
    @Test fun privateExternalAppDirectoriesCannotBeAddressedDirectly() {
        for (path in listOf("/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb/app/file",
            "/storage/emulated/0/Android/DATA/app/file", "/storage/emulated/0/Android/ObB/app/file",
            "/storage/emulated/0/Download/../Android/data/app", "/storage/emulated/0/Android//data/./app")) {
            assertTrue(path, policy.excludes(path))
        }
    }
    @Test fun componentBoundaryDoesNotHideUnrelatedPhoneFolders() {
        for (path in listOf("/storage/emulated/0/Download", "/storage/emulated/0/DCIM/photo.jpg",
            "/storage/emulated/0/Android/media/app", "/storage/emulated/0/Android/database/file",
            "/storage/emulated/0/Android/obba/file")) assertFalse(path, policy.excludes(path))
    }
    @Test fun uriComponentsCannotEscapeTheFixedStorageRoot() {
        for (parts in listOf(listOf("..", "data"), listOf("Android", ".", "data"), listOf("Android/data"),
            listOf("Android\\data"), listOf(""), listOf("file\u0000"))) {
            try { LanSharePathPolicy.requireComponents(parts); fail(parts.toString()) }
            catch (_: IllegalArgumentException) { }
        }
        LanSharePathPolicy.requireComponents(listOf("Download", "测试 + 文件.txt"))
        LanSharePathPolicy.requireComponents(emptyList()) // A flattened root is browsable.
    }
    @Test fun canonicalAliasesStillMatchTheExcludedDirectory() {
        val temporary = java.nio.file.Files.createTempDirectory("fileweave-global-policy-").toFile()
        try {
            val privateRoot = File(temporary, "Android/data").apply { check(mkdirs()) }
            val publicRoot = File(temporary, "Download").apply { check(mkdir()) }
            val alias = File(publicRoot, "alias")
            try { java.nio.file.Files.createSymbolicLink(alias.toPath(), privateRoot.toPath()) }
            catch (error: UnsupportedOperationException) { org.junit.Assume.assumeNoException(error) }
            val scoped = LanSharePathPolicy(listOf(privateRoot.canonicalPath))
            assertTrue(scoped.excludes(File(alias, "app/file").canonicalPath))
            java.nio.file.Files.deleteIfExists(alias.toPath())
        } finally { temporary.deleteRecursively() }
    }
}
