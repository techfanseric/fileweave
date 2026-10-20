package me.zhanghai.android.files.vault

/** JNI declarations for vendored libgocryptfs; crypto and disk format are upstream gocryptfs. */
internal object GocryptfsNative {
    init { System.loadLibrary("fileweave_vault") }
    external fun unlock(root: String, password: ByteArray, create: Boolean): Int
    external fun close(session: Int)
    external fun list(session: Int, directory: String): Array<VaultEntry>?
    external fun size(session: Int, path: String): Long
    external fun open(session: Int, path: String, write: Boolean): Int
    external fun read(session: Int, handle: Int, position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun write(session: Int, handle: Int, position: Long, buffer: ByteArray, length: Int): Int
    external fun closeFile(session: Int, handle: Int)
    external fun rename(session: Int, source: String, target: String): Boolean
    external fun remove(session: Int, path: String): Boolean
}

class VaultEntry(val name: String, val mode: Int, val size: Long, val modified: Long) {
    val isDirectory: Boolean get() = mode and 0xF000 == 0x4000
}
