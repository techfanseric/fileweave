package me.zhanghai.android.files.webdavserver

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * WebDAV 服务端的文件数据源抽象。
 *
 * 与 folderbridge 的 FolderBridgeBackend 同一思路：把协议核（WebDavKernel）与实际文件访问
 * 解耦，协议核在 JVM 上可用假后端或临时目录完整测试；Android 端由 WebDavProviderBackend
 * 通过应用自身的 NIO FileSystemProvider 体系提供数据，因此共享根不限于主存储，也可以是
 * 应用能访问的任意 provider 路径（FTP 服务器同样如此）。
 *
 * 所有路径都是相对共享根的段列表（空列表即共享根本身）。路径安全是双保险：
 * WebDavKernel 在解析请求 href 时做词法校验（拒绝 ".." 等穿越段），各后端在执行操作前
 * 还必须再做规范化后的“仍在共享根内”校验，两者缺一不可。
 */
interface WebDavBackend {
    /** 目标不存在时返回 null；存在时返回资源属性。根目录永远视为存在的目录。 */
    @Throws(IOException::class)
    fun stat(path: List<String>): WebDavResource?

    /**
     * 列出目录的直接子项。路径不存在或不是目录时返回 null。
     * 返回顺序不敏感（协议核会按名字排序保证输出稳定）。
     */
    @Throws(IOException::class)
    fun list(path: List<String>): List<WebDavResource>?

    /**
     * 打开文件读取，支持从 [startOffset] 开始（Range 随机访问）。
     *
     * 后端应尽量实现真正的定位（文件通道 position、FileInputStream.skip 这类 seek 语义）
     * 而不是读后丢弃——视频播放器通过 WebDAV 挂载拖动进度条会产生大偏移的 Range 请求，
     * 读跳过会把拖动变成分钟级等待（AGENTS 对读取路径的随机访问要求）。
     * [size] 是整个文件的尺寸，用于 Range 计算。
     */
    @Throws(IOException::class)
    fun openRead(path: List<String>, startOffset: Long): WebDavOpenFile

    /**
     * 打开文件写入（创建或截断）。
     *
     * 有意不做临时文件加原子改名：与上游 FTP 服务器（ProviderFtpFile.createOutputStream）
     * 保持同等语义——直接写目标路径，客户端中途断开时可能留下不完整文件。共享根之外的
     * 目标必须抛 [IOException]。
     */
    @Throws(IOException::class)
    fun openWrite(path: List<String>): OutputStream

    /** 创建目录。父目录必须已存在（否则 409），目标已存在时抛 [WebDavConflictException]。 */
    @Throws(IOException::class)
    fun createDirectory(path: List<String>)

    /** 删除文件或递归删除目录。共享根本身不可删除（协议核已拦截，后端同样必须防御）。 */
    @Throws(IOException::class)
    fun delete(path: List<String>)

    /** 移动（重命名）文件或目录，目标已存在时的处理由协议核用 [stat] 预先决定。 */
    @Throws(IOException::class)
    fun move(from: List<String>, to: List<String>)

    /** 复制文件；目录由协议核通过 stat/list/openRead/openWrite/createDirectory 递归完成。 */
    @Throws(IOException::class)
    fun copyFile(from: List<String>, to: List<String>)
}

/** 一个资源的属性。size/lastModifiedMillis 为 -1/0 时协议核省略对应属性。 */
class WebDavResource(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long
)

// 实现 Closeable 以便协议核直接 use { } 管理生命周期。
class WebDavOpenFile(val size: Long, val input: InputStream) : java.io.Closeable {
    override fun close() {
        input.close()
    }
}

/** 目标已存在（MKCOL）等 409 场景，由后端抛出、协议核翻译成状态码。 */
class WebDavConflictException(message: String) : IOException(message)

/**
 * 路径词法校验，供协议核与后端共用。
 *
 * 规则与 folderbridge 的 FolderBridgePaths.isSafeRelativePath 对齐：每个段非空、无 NUL、
 * 无反斜杠（虽然 Linux 文件名允许反斜杠，但拒绝它可以挡住把 Windows 路径分隔符当目录
 * 分隔符的客户端误用），且都不是 "." 或 ".."；空格等其余合法字符（经 %XX 解码后）照常
 * 放行。规范化后的兜底校验（resolve 后 normalize 仍在共享根内）由后端负责，因为只有
 * 后端知道根的形态。
 */
object WebDavPathSafety {
    fun isSafePath(path: List<String>): Boolean =
        path.all {
            it.isNotEmpty() && it != "." && it != ".." && !it.contains('/') &&
                !it.contains('\\') && !it.contains('\u0000')
        }
}
