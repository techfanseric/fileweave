package me.zhanghai.android.files.folderbridge

import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/** Restricted phone screenshot scope. Reuses verified copy/trash/restore operations. */
class ScreenshotBackend(private val primary: File, private val changed: (File) -> Unit = {}) : FolderBridgeBackend {
    override val rootName = "Screenshots"
    private fun scopes(): List<Pair<String, FolderBridgeFileBackend>> = DIRECTORIES.mapNotNull { relative ->
        val root = File(primary, relative)
        // Never follow a renamed/symlinked screenshot root into another directory.
        if (root.isDirectory && root.canonicalFile == File(primary.canonicalFile, relative).absoluteFile) relative to FolderBridgeFileBackend(root, ::isImage) else null
    }
    override fun listFiles(): List<FolderBridgeItem> = scopes().flatMap { (scope, backend) ->
        backend.listFiles().filter { !it.path.split('/').any { part -> part.startsWith('.') } }.map {
            FolderBridgeItem(id = "$scope/${it.id}", path = "$scope/${it.path}", size = it.size, mtime = it.mtime,
                trashPath = it.trashPath?.let { path -> "$scope/$path" }, movedAt = it.movedAt)
        }
    }.sortedByDescending { it.mtime }

    private fun resolve(path: String, trash: Boolean = false): Pair<FolderBridgeFileBackend, String> {
        require(FolderBridgePaths.isSafeRelativePath(path)) { "Invalid screenshot path" }
        val scope = scopes().firstOrNull { path.startsWith(it.first + "/") } ?: throw FileNotFoundException()
        val relative = path.removePrefix(scope.first + "/")
        require(isImage(relative) && relative.split('/').withIndex().none { (index, part) ->
            part.startsWith('.') && !(trash && index == 0 && part == FolderBridgePaths.TRASH_DIR)
        }) { "Not a screenshot" }
        if (!trash) require(!relative.startsWith(FolderBridgePaths.TRASH_DIR + "/"))
        return scope.second to relative
    }
    override fun openFile(path: String): FolderBridgeOpenFile {
        val (backend, relative) = resolve(path, trash = true)
        return backend.openFile(relative)
    }
    override fun trash(id: String, path: String): String {
        require(id == path) { "Screenshot identity changed" }
        val (backend, relative) = resolve(path)
        val scope = path.removeSuffix(relative)
        val result = backend.trash(relative, relative)
        try { changed(File(primary, path)) } catch (_: Exception) { }
        return scope + result
    }
    override fun restore(id: String, path: String, to: String) {
        val (backend, relative) = resolve(id, trash = true)
        val (_, target) = resolve(to)
        // The original relative scope must match; never restore across screenshot roots.
        val scope = id.removeSuffix(relative)
        require(path == to && to.startsWith(scope) && to.removePrefix(scope) == target) { "Invalid restore destination" }
        require(id.startsWith(scope + FolderBridgePaths.TRASH_DIR + "/"))
        backend.restore(relative, path.removePrefix(scope), target)
        try { changed(File(primary, to)) } catch (_: Exception) { }
    }
    companion object {
        val DIRECTORIES = listOf("Pictures/Screenshots", "DCIM/Screenshots", "Screenshots")
        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp", "heic", "heif", "avif", "bmp", "gif")
    }
}
