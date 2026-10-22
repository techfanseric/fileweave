package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.text.TextUtils
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF backend over one user-granted document tree, ported from the battle-tested recbridge
 * SafBridge. All paths are relative to the tree root.
 *
 * Iron rules kept from the original implementation (verified on device back then):
 * * The trash is our own ".Trash" tree inside the granted directory, structured as
 *   ".Trash/<epoch-millis>/<original relative path>" so the Mac can infer each item's identity
 *   from the original path preserved inside the batch directory.
 * * Write operations always address documents by documentId, never by walking paths back down:
 *   path lookups compare display names level by level and were observed to miss freshly created
 *   deep directories (.Trash/<batch>/CallRecord/x.m4a), while the documentId is the authoritative
 *   provider handle already in hand from the traversal.
 * * Trash/restore copy first and verify the copied size; on any mismatch the destination is
 *   removed and the original is kept — a failed move must never lose data.
 * * createFile builds missing parent directories level by level.
 */
class FolderBridgeSafBackend(context: Context, private val tree: Uri) :
    FolderBridgeBackend {

    private val resolver = context.contentResolver

    override val rootName: String
        get() = FolderBridgePaths.rootNameOf(DocumentsContract.getTreeDocumentId(tree))

    override fun listFiles(): List<FolderBridgeItem> {
        val out = mutableListOf<FolderBridgeItem>()
        walk(DocumentsContract.getTreeDocumentId(tree), "", out)
        return out
    }

    // ---------------------------------------------------------------- Traversal

    /**
     * Must recurse: recordings actually live in nested directories (e.g. Sounds/CallRecord);
     * listing only the top level would show just the recorder files at the root.
     */
    private fun walk(docId: String, prefix: String, out: MutableList<FolderBridgeItem>) {
        for (row in rows(children(docId))) {
            val name = row.name ?: continue
            when {
                FolderBridgePaths.isAudio(name, row.mime) ->
                    out.add(item(prefix, row))
                row.isDirectory && FolderBridgePaths.TRASH_DIR == name && prefix.isEmpty() ->
                    for (batch in rows(children(row.docId))) {
                        collectTrash(batch.docId, batch.name.orEmpty(), "", out)
                    }
                row.isDirectory && FolderBridgePaths.TRASH_DIR != name ->
                    walk(row.docId, if (prefix.isEmpty()) name else "$prefix/$name", out)
            }
        }
    }

    /**
     * Batch directories keep the original sub-directory hierarchy (CallRecord/xxx.m4a) because
     * the Mac identifies a trashed item by its original path; flattening to file names would
     * make same-named recordings in different directories indistinguishable.
     */
    private fun collectTrash(trashDocId: String, batch: String, relPrefix: String,
                             out: MutableList<FolderBridgeItem>) {
        val moved = FolderBridgePaths.parseStamp(batch)
        for (row in rows(children(trashDocId))) {
            val name = row.name ?: continue
            val rel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
            if (FolderBridgePaths.isAudio(name, row.mime)) {
                out.add(FolderBridgeItem(
                    id = row.docId,
                    // Identity: the original location, restored directly from the batch-relative
                    // path without any string surgery.
                    path = rel,
                    size = row.size,
                    mtime = row.modified * 1000L,
                    trashPath = "${FolderBridgePaths.TRASH_DIR}/$batch/$rel",
                    movedAt = moved
                ))
            } else if (row.isDirectory) {
                collectTrash(row.docId, batch, rel, out)
            }
        }
    }

    private fun item(prefix: String, row: Row): FolderBridgeItem {
        val name = row.name.orEmpty()
        return FolderBridgeItem(
            id = row.docId,
            path = if (prefix.isEmpty()) name else "$prefix/$name",
            size = row.size,
            mtime = row.modified * 1000L
        )
    }

    // ---------------------------------------------------------------- Read

    override fun openFile(path: String): FolderBridgeOpenFile {
        if (!FolderBridgePaths.isSafeRelativePath(path)) {
            throw FolderBridgeNotFound(path)
        }
        val row = find(path) ?: throw FolderBridgeNotFound(path)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, row.docId)
        val input = resolver.openInputStream(documentUri) ?: throw FileNotFoundException(path)
        // A negative COLUMN_SIZE means unknown; the kernel then streams close-delimited.
        return FolderBridgeOpenFile(row.size, input)
    }

    /** Walks down the relative path segment by segment; null when not found, never throws. */
    private fun find(path: String): Row? {
        var currentDocId = DocumentsContract.getTreeDocumentId(tree)
        var found: Row? = null
        for (segment in path.split('/')) {
            val hit = rows(children(currentDocId)).firstOrNull { segment == it.name } ?: return null
            found = hit
            currentDocId = hit.docId
        }
        return found
    }

    // ---------------------------------------------------------------- Trash

    override fun trash(id: String, path: String): String {
        if (!FolderBridgePaths.isSafeRelativePath(path)) {
            throw IOException("Path outside the granted tree: $path")
        }
        val sourceSize = sizeById(id)
        val batch = System.currentTimeMillis().toString()
        // The batch directory keeps the original relative path so the Mac can infer identity.
        val trashRel = "${FolderBridgePaths.TRASH_DIR}/$batch/$path"
        val destination = createFile(trashRel, "audio/mp4")
        val copied = copyById(id, destination)
        if (copied != sourceSize) {
            // Size mismatch: undo and never delete the original — that would lose data.
            safeDelete(destination)
            throw IOException("Copy verification failed: destination $copied != source $sourceSize")
        }
        // Both source and destination use handles already in hand (documentId / the Uri returned
        // by createDocument); no "find it back by path" step in between — that mismatches on SAF.
        deleteById(id)
        return trashRel
    }

    override fun restore(id: String, path: String, to: String) {
        if (!FolderBridgePaths.isSafeRelativePath(to)) {
            throw IOException("Path outside the granted tree: $to")
        }
        val sourceSize = sizeById(id)
        val destination = createFile(to, "audio/mp4")
        val copied = copyById(id, destination)
        if (copied != sourceSize || copied <= 0) {
            // Something already exists at the original location, or the copy landed incomplete:
            // undo and never leave half a file behind.
            safeDelete(destination)
            if (copied <= 0) {
                throw IOException("A file already exists at the original location; not restored")
            }
            throw IOException("Restore verification failed: destination $copied != source $sourceSize")
        }
        deleteById(id)
    }

    // ---------------------------------------------------------------- Primitives

    private fun sizeById(docId: String): Long {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return -1L
    }

    private fun copyById(docId: String, destination: Uri): Long {
        val input = openById(docId)
        input.use {
            val output = resolver.openOutputStream(destination, "wt")
                ?: throw IOException("Unable to open destination document")
            output.use {
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
                return total
            }
        }
    }

    /** Opens a byte stream by documentId; the entry point for every write operation. */
    private fun openById(docId: String): InputStream {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return resolver.openInputStream(uri) ?: throw IOException("Unable to open documentId=$docId")
    }

    private fun deleteById(docId: String) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        if (!DocumentsContract.deleteDocument(resolver, uri)) {
            throw IOException("Delete failed for documentId=$docId")
        }
    }

    private fun safeDelete(documentUri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, documentUri)
        } catch (_: Exception) { }
    }

    /** Creates the file at the relative path, building missing parent directories level by level. */
    private fun createFile(rel: String, mime: String): Uri {
        val parentRel = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        var parentDocId = DocumentsContract.getTreeDocumentId(tree)
        if (parentRel.isNotEmpty()) {
            for (segment in parentRel.split('/')) {
                val hit = rows(children(parentDocId)).firstOrNull { segment == it.name }
                parentDocId = if (hit != null) {
                    hit.docId
                } else {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
                    val made = DocumentsContract.createDocument(
                        resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, segment
                    ) ?: throw IOException("Failed to create directory: $segment")
                    lastDocId(made)
                }
            }
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
        return DocumentsContract.createDocument(resolver, parentUri, mime, name)
            ?: throw IOException("Failed to create file: $name")
    }

    private fun lastDocId(documentUri: Uri): String {
        try {
            resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null)?.use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) }
        } catch (_: Exception) { }
        return ""
    }

    private fun children(docId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)

    // ---------------------------------------------------------------- Rows

    private class Row(val docId: String, val name: String?, val mime: String?,
                      val size: Long, val modified: Long) {
        val isDirectory: Boolean
            get() = mime != null && DocumentsContract.Document.MIME_TYPE_DIR == mime
    }

    private fun rows(uri: Uri): List<Row> {
        val out = mutableListOf<Row>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            resolver.query(uri, columns, null, null, null)?.use { cursor: Cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    if (TextUtils.isEmpty(docId)) {
                        continue
                    }
                    out.add(Row(docId, cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getLong(4)))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
