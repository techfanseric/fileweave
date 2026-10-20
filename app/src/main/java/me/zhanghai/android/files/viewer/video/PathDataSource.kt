package me.zhanghai.android.files.viewer.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.RandomAccessFile
import java.io.IOException
import me.zhanghai.android.files.provider.linux.isLinuxPath
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newByteChannel

/** Reads existing NIO providers directly, including SMB/SFTP, without a plaintext download cache. */
@androidx.media3.common.util.UnstableApi
class PathDataSource(private val path: Path) : BaseDataSource(true) {
    private var channel: SeekableByteChannel? = null
    private var sourceUri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        // The upstream root heuristic treats /data ancestors as root-only even for our own cache.
        // Use ordinary access first for Linux paths; preserve the existing root-aware fallback.
        val local = if (path.isLinuxPath) try { RandomAccessFile(path.toFile(), "r") } catch (_: IOException) { null } else null
        val channel = if (local == null) path.newByteChannel() else object : SeekableByteChannel {
            override fun read(buffer: ByteBuffer) = local.channel.read(buffer)
            override fun write(buffer: ByteBuffer): Int = throw IOException("Read-only")
            override fun position() = local.channel.position()
            override fun position(value: Long): SeekableByteChannel { local.channel.position(value); return this }
            override fun size() = local.channel.size()
            override fun truncate(size: Long): SeekableByteChannel = throw IOException("Read-only")
            override fun isOpen() = local.channel.isOpen
            override fun close() = local.close()
        }
        this.channel = channel
        val size = channel.size()
        if (dataSpec.position > size) throw EOFException("Requested position exceeds file size")
        channel.position(dataSpec.position)
        sourceUri = dataSpec.uri
        remaining = size - dataSpec.position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(channel).read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), remaining).toInt()))
        if (count == -1) throw EOFException("File changed or connection closed during playback")
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = sourceUri

    override fun close() {
        try { channel?.close() } finally {
            channel = null
            sourceUri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
