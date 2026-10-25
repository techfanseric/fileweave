package me.zhanghai.android.files.lanshare

import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Paths
import java8.nio.file.Path
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream

/** Virtual root contains only explicit selections; symbolic links never expand the scope. */
class LanShareBackend(paths: List<Path>, rootLabel: String? = null) : LanShareKernel.Backend {
    private val roots = linkedMapOf<String, Path>().apply {
        paths.distinct().forEach { path ->
            val canonical = canonical(path)
            val name = rootLabel ?: path.fileName?.toString() ?: "Storage"
            var unique = name
            var index = 2
            while (containsKey(unique)) unique = "$name (${index++})"
            put(unique, canonical)
        }
    }
    private fun canonical(path: Path): Path = if (path.isLinuxPath) {
        // LinuxPath deliberately has no toRealPath implementation; canonicalFile resolves
        // storage aliases without relying on that unsupported provider API.
        Paths.get(path.toFile().canonicalPath)
    } else path.normalize()

    private fun resolve(parts: List<String>): Path {
        require(parts.isNotEmpty())
        val root = roots[parts.first()] ?: throw java.io.FileNotFoundException()
        var path = root
        for (part in parts.drop(1)) {
            require(part.isNotEmpty() && part != "." && part != ".." && '/' !in part && '\\' !in part && '\u0000' !in part)
            path = path.resolve(part)
            require(!(if (path.isLinuxPath) android.system.OsConstants.S_ISLNK(android.system.Os.lstat(path.toString()).st_mode)
                else Files.isSymbolicLink(path))) { "Symbolic links are not shared" }
        }
        val real = canonical(path)
        require(real.startsWith(root)) { "Outside shared selection" }
        return real
    }
    private fun entry(path: Path, name: String): LanShareKernel.Entry {
        if (path.isLinuxPath) {
            val stat = android.system.Os.lstat(path.toString())
            val directory = android.system.OsConstants.S_ISDIR(stat.st_mode)
            require(directory || android.system.OsConstants.S_ISREG(stat.st_mode))
            return LanShareKernel.Entry(name, directory, if (directory) 0 else stat.st_size)
        }
        val attr = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attr.isDirectory || attr.isRegularFile)
        return LanShareKernel.Entry(name, attr.isDirectory, if (attr.isDirectory) 0 else attr.size())
    }
    override fun stat(path: List<String>): LanShareKernel.Entry? = if (path.isEmpty()) {
        LanShareKernel.Entry("共享文件 / Shared files", true, 0)
    } else try { entry(resolve(path), path.last()) } catch (_: Exception) { null }
    override fun list(path: List<String>): List<LanShareKernel.Entry> {
        if (path.isEmpty()) return roots.mapNotNull { (name, _) -> stat(listOf(name)) }
        val directory = resolve(path)
        if (directory.isLinuxPath) {
            return (directory.toFile().listFiles() ?: throw java.io.IOException("Directory unavailable"))
                .mapNotNull { child -> stat(path + child.name) }
        }
        return directory.newDirectoryStream().use { stream ->
            stream.mapNotNull { child -> stat(path + child.fileName.toString()) }
        }
    }
    override fun open(path: List<String>, offset: Long): java.io.InputStream {
        val file = resolve(path)
        if (file.isLinuxPath) {
            val descriptor = android.system.Os.open(file.toString(), android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
            val input = java.io.FileInputStream(descriptor)
            try { input.channel.position(offset); return input }
            catch (error: Exception) { input.close(); throw error }
        }
        val channel = file.newByteChannel(LinkOption.NOFOLLOW_LINKS)
        try { channel.position(offset); return channel.newInputStream() }
        catch (e: Exception) { channel.close(); throw e }
    }
}
