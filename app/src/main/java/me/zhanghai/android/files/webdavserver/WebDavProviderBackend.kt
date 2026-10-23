package me.zhanghai.android.files.webdavserver

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.isRegularFile
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.size

/**
 * WebDAV 服务端的 Android 端后端：通过应用自身的 NIO FileSystemProvider 体系访问文件
 * （与 FTP 服务器的 ProviderFtpFile/ProviderFileSystemView 同源），因此共享根可以是应用
 * 能访问的任意 provider 路径——主存储、SD 卡、root 分区乃至已装载的网络存储。
 *
 * 路径安全的兜底在这里完成：协议核已做词法校验（拒绝 ".." 段），本后端在每次解析后
 * normalize 再检查是否仍在共享根内，越界一律抛 [IOException]（映射为 403/404/500）。
 * 这是上游 FTP 服务器 ProviderFileSystemView.getFile 同款的双保险。
 */
class WebDavProviderBackend(
    rootDirectory: Path
) : WebDavBackend {
    // 根先规范化一次：用户选的根可能是 /sdcard 这类符号链接路径，比较时统一以规范化
    // 形态为准，避免“合法子路径因根未规范化而被误判越界”。
    private val root = rootDirectory.normalize()

    private fun resolve(path: List<String>): Path {
        var resolved = root
        for (segment in path) {
            resolved = resolved.resolve(segment)
        }
        resolved = resolved.normalize()
        if (!resolved.startsWith(root)) {
            throw IOException("Path escapes the shared root")
        }
        return resolved
    }

    private fun statPath(path: Path, name: String): WebDavResource? {
        if (!path.exists()) {
            return null
        }
        val isDirectory = path.isDirectory()
        return WebDavResource(
            name, isDirectory,
            if (isDirectory) -1L else try {
                path.size()
            } catch (error: IOException) {
                -1L
            },
            try {
                path.getLastModifiedTime().toMillis()
            } catch (error: IOException) {
                0L
            }
        )
    }

    override fun stat(path: List<String>): WebDavResource? {
        if (path.isEmpty()) {
            // 共享根永远存在且是目录；名字用最后一节，便于客户端显示。
            val name = root.fileName?.toString() ?: "/"
            return WebDavResource(name, true, -1L, try {
                root.getLastModifiedTime().toMillis()
            } catch (error: IOException) {
                0L
            })
        }
        val physicalPath = resolve(path)
        return statPath(physicalPath, path.last())
    }

    override fun list(path: List<String>): List<WebDavResource>? {
        val physicalPath = resolve(path)
        if (!physicalPath.isDirectory()) {
            return null
        }
        return physicalPath.newDirectoryStream().use { stream ->
            stream.mapNotNull { childPath ->
                val name = childPath.fileName?.toString() ?: return@mapNotNull null
                statPath(childPath, name)
            }
        }
    }

    override fun openRead(path: List<String>, startOffset: Long): WebDavOpenFile {
        val physicalPath = resolve(path)
        if (!physicalPath.isRegularFile()) {
            throw IOException("Not a file: $path")
        }
        val size = physicalPath.size()
        // 随机访问与 ProviderFtpFile.createInputStream(offset) 同款：偏移不为零时用
        // SeekableByteChannel 定位，视频客户端拖动进度条不需要读跳过整个前缀。
        val input: InputStream = if (startOffset == 0L) {
            physicalPath.newInputStream()
        } else {
            val channel = physicalPath.newByteChannel()
            var successful = false
            try {
                channel.position(startOffset)
                val stream = channel.newInputStream()
                successful = true
                stream
            } finally {
                if (!successful) {
                    channel.close()
                }
            }
        }
        return WebDavOpenFile(size, input)
    }

    override fun openWrite(path: List<String>): OutputStream {
        val physicalPath = resolve(path)
        // 无参 newOutputStream 即 CREATE + TRUNCATE_EXISTING + WRITE，PUT 语义。
        return physicalPath.newOutputStream()
    }

    override fun createDirectory(path: List<String>) {
        if (path.isEmpty()) {
            throw WebDavConflictException("Root already exists")
        }
        resolve(path).createDirectory()
    }

    override fun delete(path: List<String>) {
        if (path.isEmpty()) {
            throw IOException("Cannot delete the shared root")
        }
        deleteRecursively(resolve(path))
    }

    /** java.nio 的 Files.delete 只能删空目录，WebDAV 的 DELETE 语义是递归删除。 */
    private fun deleteRecursively(path: Path) {
        if (path.isDirectory()) {
            path.newDirectoryStream().use { stream ->
                for (child in stream) {
                    deleteRecursively(child)
                }
            }
        }
        path.delete()
    }

    override fun move(from: List<String>, to: List<String>) {
        resolve(from).moveTo(resolve(to))
    }

    override fun copyFile(from: List<String>, to: List<String>) {
        resolve(from).copyTo(resolve(to))
    }
}
