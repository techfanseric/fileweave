package me.zhanghai.android.files.folderbridge

import java.io.File
import java.io.IOException

/**
 * 基于 java.io.File 的共享后端，与 [FolderBridgeSafBackend] 实现同一个
 * [FolderBridgeBackend] 接口、对外行为一致，供已授予所有文件访问权限时直接共享
 * [FolderBridgeDefaults] 自动识别出的录音目录，免去 SAF 授权这一步。
 *
 * 与 SAF 版共同遵守的约定：
 * * 所有对外路径都是相对共享根目录的相对路径；回收站是我们自己在根内维护的
 *   “.Trash/&lt;毫秒时间戳&gt;/&lt;原相对路径&gt;”结构，Mac 靠批内保留的原路径识别条目。
 * * 删除 = 复制进 .Trash 并校验字节数，校验不过就撤销复制、绝不删原件——失败的
 *   移动绝不能丢数据；还原同理，目标位置已有文件时拒绝覆盖。
 * * 每个相对路径先经 [FolderBridgePaths.isSafeRelativePath] 词法校验（挡住 ../ 等
 *   穿越段），解析后再规范化（canonicalFile）并要求结果仍落在共享根内，双保险防
 *   路径穿越与符号链接把访问带到根外。
 * * 条目的 id 即其相对路径（回收站内条目从 .Trash 起算）；内核对 id 做的形状校验
 *   （非空、无 NUL、无 “..” 段）与之相容。
 */
class FolderBridgeFileBackend(root: File) : FolderBridgeBackend {

    /** 规范化后的共享根目录；构造失败（根不可访问）会让后续所有解析失败并返回未命中。 */
    private val rootDir: File = try {
        root.canonicalFile
    } catch (_: IOException) {
        root.absoluteFile
    }

    /** 根路径前缀（带分隔符），用于判定解析结果是否仍在根内。 */
    private val rootPath: String = rootDir.path + File.separator

    override val rootName: String
        get() = rootDir.name

    /** 规范化后的共享根目录；仅供同包的 FolderBridgeObserver 注册文件监听使用。 */
    val rootDirectory: File
        get() = rootDir

    // ---------------------------------------------------------------- 遍历

    override fun listFiles(): List<FolderBridgeItem> {
        val out = mutableListOf<FolderBridgeItem>()
        walk(rootDir, "", out)
        return out
    }

    /**
     * 必须递归：录音实际存在嵌套目录里（如 Recordings/Call）；只列顶层会漏掉大部分
     * 文件。顶层 .Trash 按回收站语义单独收集，更深层同名目录与 SAF 版一致直接跳过；
     * 规范化后越出根外的目录（符号链接）不进入。
     */
    private fun walk(directory: File, prefix: String, out: MutableList<FolderBridgeItem>) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            val name = child.name
            when {
                child.isFile && FolderBridgeDefaults.isAudioName(name) -> out.add(FolderBridgeItem(
                    id = if (prefix.isEmpty()) name else "$prefix/$name",
                    path = if (prefix.isEmpty()) name else "$prefix/$name",
                    size = child.length(),
                    mtime = child.lastModified()
                ))
                child.isDirectory && FolderBridgePaths.TRASH_DIR == name && prefix.isEmpty() ->
                    collectTrashBatches(child, out)
                child.isDirectory && FolderBridgePaths.TRASH_DIR != name && isInsideRoot(child) ->
                    walk(child, if (prefix.isEmpty()) name else "$prefix/$name", out)
            }
        }
    }

    private fun collectTrashBatches(trashDir: File, out: MutableList<FolderBridgeItem>) {
        val batches = trashDir.listFiles() ?: return
        for (batch in batches) {
            // 批目录名是毫秒时间戳；解析不出的按 0 处理，内核会省略 movedAt 字段。
            val movedAt = FolderBridgePaths.parseStamp(batch.name)
            collectTrash(batch, "${FolderBridgePaths.TRASH_DIR}/${batch.name}", "", movedAt, out)
        }
    }

    /**
     * 批目录内保留原有的子目录层级（如 Call/xxx.m4a）：Mac 靠原相对路径识别条目，
     * 拍平成文件名会让不同目录里的同名录音无法区分。
     */
    private fun collectTrash(directory: File, trashPrefix: String, relPrefix: String,
                             movedAt: Long, out: MutableList<FolderBridgeItem>) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            val name = child.name
            when {
                child.isFile && FolderBridgeDefaults.isAudioName(name) -> {
                    val rel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
                    out.add(FolderBridgeItem(
                        // 回收站内条目的 id 从 .Trash 起算，restore 靠它找回文件。
                        id = "$trashPrefix/$rel",
                        path = rel,
                        size = child.length(),
                        mtime = child.lastModified(),
                        trashPath = "$trashPrefix/$rel",
                        movedAt = movedAt
                    ))
                }
                child.isDirectory && FolderBridgePaths.TRASH_DIR != name && isInsideRoot(child) ->
                    collectTrash(child, trashPrefix,
                        if (relPrefix.isEmpty()) name else "$relPrefix/$name", movedAt, out)
            }
        }
    }

    // ---------------------------------------------------------------- 读取

    override fun openFile(path: String): FolderBridgeOpenFile {
        val file = resolveInside(path)
        if (file == null || !file.isFile) {
            throw FolderBridgeNotFound(path)
        }
        // File API 没有“大小未知”一说，内核总能按确切大小回 Content-Length。
        return FolderBridgeOpenFile(file.length(), file.inputStream())
    }

    // ---------------------------------------------------------------- 回收站

    override fun trash(id: String, path: String): String {
        if (!FolderBridgePaths.isSafeRelativePath(path)) {
            throw IOException("Path outside the shared root: $path")
        }
        val source = resolveInside(id) ?: throw FolderBridgeNotFound(path)
        if (!source.isFile) {
            throw FolderBridgeNotFound(path)
        }
        val sourceSize = source.length()
        var stamp = System.currentTimeMillis()
        var trashRel = "${FolderBridgePaths.TRASH_DIR}/$stamp/$path"
        // 同一毫秒内两次删除会撞批目录名，顺延到空闲的时间戳为止。
        while (resolveInside(trashRel)?.exists() == true) {
            ++stamp
            trashRel = "${FolderBridgePaths.TRASH_DIR}/$stamp/$path"
        }
        val destination = resolveInside(trashRel) ?: throw IOException("Path outside the root: $path")
        val copied = copy(source, destination)
        if (copied != sourceSize) {
            // 字节数对不上：撤销复制，绝不动原件——失败的移动绝不能丢数据。
            safeDelete(destination)
            throw IOException(
                "Copy verification failed: destination $copied != source $sourceSize")
        }
        if (!source.delete()) {
            safeDelete(destination)
            throw IOException("Unable to delete the original file: $source")
        }
        return trashRel
    }

    override fun restore(id: String, path: String, to: String) {
        if (!FolderBridgePaths.isSafeRelativePath(to)) {
            throw IOException("Path outside the shared root: $to")
        }
        val source = resolveInside(id) ?: throw FolderBridgeNotFound(id)
        if (!source.isFile) {
            throw FolderBridgeNotFound(id)
        }
        val destination = resolveInside(to) ?: throw IOException("Path outside the root: $to")
        if (destination.exists()) {
            // 原位置已有文件时拒绝覆盖；SAF 版靠改名副本间接暴露这一情况，这里显式拒绝。
            throw IOException("A file already exists at the original location; not restored")
        }
        val sourceSize = source.length()
        val copied = copy(source, destination)
        if (copied != sourceSize) {
            safeDelete(destination)
            throw IOException(
                "Restore verification failed: destination $copied != source $sourceSize")
        }
        if (!source.delete()) {
            throw IOException("Unable to delete the trashed original: $source")
        }
    }

    // ---------------------------------------------------------------- 原语

    /** 复制并返回写出的字节数；逐层补齐缺失的父目录，与 SAF 版 createFile 的行为对齐。 */
    private fun copy(source: File, destination: File): Long {
        destination.parentFile?.mkdirs()
        return source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    output.write(buffer, 0, count)
                    total += count
                }
                output.flush()
                total
            }
        }
    }

    private fun safeDelete(file: File) {
        try {
            file.delete()
        } catch (_: Exception) { }
    }

    /**
     * 把相对路径解析成根内的文件：先做词法校验，再规范化并要求规范化结果仍落在共享
     * 根内（挡住 ../ 逃逸与指向根外的符号链接）。不合法或越界返回 null，调用方按
     * “未找到”处理。
     */
    private fun resolveInside(path: String): File? {
        if (!FolderBridgePaths.isSafeRelativePath(path)) {
            return null
        }
        return try {
            val file = File(rootDir, path).canonicalFile
            if (file == rootDir || file.path.startsWith(rootPath)) file else null
        } catch (_: IOException) {
            null
        }
    }

    /** 遍历时判定目录规范化后是否仍在根内，符号链接指向根外时不跟进。 */
    private fun isInsideRoot(directory: File): Boolean = try {
        val canonical = directory.canonicalFile
        canonical == rootDir || canonical.path.startsWith(rootPath)
    } catch (_: IOException) {
        false
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
