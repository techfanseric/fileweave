package me.zhanghai.android.files.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

@androidx.media3.common.util.UnstableApi
class VaultDataSource(private val path: String, private val generation: Long) : BaseDataSource(false) {
    private var handle = -1
    private var position = 0L
    private var remaining = 0L
    private var sourceUri: Uri? = null
    private var opened = false
    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        val size = VaultSession.size(path, generation)
        if (spec.position > size) throw IOException("Position exceeds file size")
        handle = VaultSession.open(path, generation)
        position = spec.position
        remaining = size - position
        if (spec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, spec.length)
        sourceUri = spec.uri
        opened = true
        transferStarted(spec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = VaultSession.read(handle, position, buffer, offset, minOf(length.toLong(), remaining, 128 * 1024L).toInt(), generation)
        if (n <= 0) throw IOException("Encrypted read failed, file is damaged, or vault is locked")
        position += n; remaining -= n; bytesTransferred(n)
        return n
    }
    override fun getUri() = sourceUri
    override fun close() {
        if (handle >= 0) VaultSession.closeFile(handle, generation)
        handle = -1; sourceUri = null
        if (opened) { opened = false; transferEnded() }
    }
}
