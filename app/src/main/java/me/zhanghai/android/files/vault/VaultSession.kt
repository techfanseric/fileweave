package me.zhanghai.android.files.vault

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** All native calls share a lock: gcf_close must never race a read/write or close a handle twice. */
object VaultSession {
    private var session = -1
    private var generation = 0L
    private var observerInstalled = false
    private val handles = mutableSetOf<Int>()
    @Synchronized fun isOpen() = session >= 0
    @Synchronized fun generation() = generation

    fun observeBackground() {
        if (observerInstalled) return
        observerInstalled = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { lock() }
        })
    }

    @Synchronized fun unlock(root: File, password: ByteArray, create: Boolean) {
        lock()
        if (create && root.exists() && root.list()?.isNotEmpty() == true) throw IOException("Vault already exists")
        root.mkdirs()
        try {
            val id = GocryptfsNative.unlock(root.absolutePath, password, create)
            if (id < 0) throw IOException(if (create) "Unable to create vault" else "Wrong password or unreadable vault")
            session = id
            generation++
            // A process termination may leave an unfinished encrypted import. No source is deleted.
            GocryptfsNative.list(id, "/")?.filter {
                it.name.matches(Regex("\\.fileweave-import-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            }?.forEach { GocryptfsNative.remove(id, "/${it.name}") }
        } finally { password.fill(0) }
    }
    @Synchronized fun lock() {
        if (session >= 0) {
            handles.forEach { GocryptfsNative.closeFile(session, it) }
            handles.clear()
            GocryptfsNative.close(session)
        }
        session = -1
        generation++
    }
    private fun requireOpen(expected: Long? = null): Int {
        if (session < 0 || expected != null && expected != generation) throw IOException("Vault is locked")
        return session
    }
    @Synchronized fun list(path: String): List<VaultEntry> =
        GocryptfsNative.list(requireOpen(), path)?.toList() ?: throw IOException("Unable to read vault")
    @Synchronized fun size(path: String, expected: Long): Long =
        GocryptfsNative.size(requireOpen(expected), path).also { if (it < 0) throw IOException("File unavailable") }
    @Synchronized fun open(path: String, expected: Long): Int =
        GocryptfsNative.open(requireOpen(expected), path, false).also { if (it < 0) throw IOException("File unavailable"); handles.add(it) }
    @Synchronized fun read(handle: Int, position: Long, buffer: ByteArray, offset: Int, length: Int, expected: Long): Int {
        val id = requireOpen(expected)
        if (handle !in handles) throw IOException("Read session expired")
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size && length <= 128 * 1024)
        return GocryptfsNative.read(id, handle, position, buffer, offset, length)
    }
    @Synchronized fun closeFile(handle: Int, expected: Long) {
        if (session >= 0 && expected == generation && handles.remove(handle)) GocryptfsNative.closeFile(session, handle)
    }
    @Synchronized fun delete(path: String) {
        if (!GocryptfsNative.remove(requireOpen(), path)) throw IOException("Unable to delete file")
    }

    fun export(path: String, output: java.io.OutputStream) {
        val expected = generation()
        val total = size(path, expected)
        val handle = open(path, expected)
        val buffer = ByteArray(128 * 1024)
        try {
            var position = 0L
            while (position < total) {
                if (Thread.currentThread().isInterrupted) throw IOException("Export cancelled")
                val n = read(handle, position, buffer, 0, minOf(buffer.size.toLong(), total - position).toInt(), expected)
                if (n <= 0) throw IOException("Encrypted read failed")
                output.write(buffer, 0, n)
                position += n
            }
        } finally { buffer.fill(0); closeFile(handle, expected) }
    }

    /** Writes to a temporary encrypted name; a failed/cancelled import never replaces an existing file. */
    fun import(input: InputStream, name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\u0000' !in name && !name.startsWith(".fileweave-import-"))
        val expected: Long
        val handle: Int
        val temporary = "/.fileweave-import-${UUID.randomUUID()}"
        synchronized(this) {
            expected = generation
            handle = GocryptfsNative.open(requireOpen(), temporary, true)
            if (handle < 0) throw IOException("Unable to create encrypted file")
            handles.add(handle)
        }
        var position = 0L
        val buffer = ByteArray(128 * 1024)
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Import cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                synchronized(this) {
                    val written = GocryptfsNative.write(requireOpen(expected), handle, position, buffer, count)
                    if (written != count) throw IOException("Encrypted write failed or storage is full")
                }
                position += count
            }
            synchronized(this) {
                closeFile(handle, expected)
                requireOpen(expected)
                val entries = list("/").map { it.name }.toSet()
                val targetName = if (name in entries) "${UUID.randomUUID().toString().take(8)}-$name" else name
                if (!GocryptfsNative.rename(session, temporary, "/$targetName")) throw IOException("Unable to finalize import")
                return targetName
            }
        } finally {
            buffer.fill(0)
            synchronized(this) {
                closeFile(handle, expected)
                if (session >= 0 && expected == generation) GocryptfsNative.remove(session, temporary)
            }
        }
    }
}
