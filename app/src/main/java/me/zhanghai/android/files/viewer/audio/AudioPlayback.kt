package me.zhanghai.android.files.viewer.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java8.nio.file.Path
import me.zhanghai.android.files.viewer.video.PathDataSource

@androidx.media3.common.util.UnstableApi
object AudioPlayback {
    var player: ExoPlayer? = null
        private set
    var paths: List<Path> = emptyList()
        private set
    private val observers = mutableSetOf<() -> Unit>()
    fun observe(observer: () -> Unit) { observers.add(observer); observer() }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    private fun notifyChanged() { observers.toList().forEach { it() } }
    fun play(context: Context, items: List<Path>, selected: Path) {
        close()
        paths = items.toList()
        val queue = paths
        val factory = DataSource.Factory {
            object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners.add(listener); delegate?.addTransferListener(listener) }
                override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                    val index = spec.uri.lastPathSegment?.toIntOrNull() ?: throw java.io.IOException("Invalid audio item")
                    val source = PathDataSource(queue.getOrNull(index) ?: throw java.io.IOException("Audio item unavailable"))
                    delegate = source
                    listeners.forEach(source::addTransferListener)
                    return source.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(delegate).read(buffer, offset, length)
                override fun getUri() = delegate?.uri
                override fun close() { delegate?.close(); delegate = null }
            }
        }
        player = ExoPlayer.Builder(context.applicationContext)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().also { p ->
                p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                p.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = notifyChanged()
                })
                p.setMediaItems(paths.mapIndexed { index, _ -> MediaItem.fromUri(Uri.parse("fileweave://audio/$index")) }, paths.indexOf(selected).coerceAtLeast(0), 0)
                p.prepare()
                p.play()
            }
        notifyChanged()
    }
    fun move(from: Int, to: Int) {
        val p = player ?: return
        if (from !in paths.indices || to !in paths.indices || from == to) return
        paths = paths.toMutableList().apply { add(to, removeAt(from)) }
        // MediaItem URIs keep their original source IDs when reordered.
        p.moveMediaItem(from, to)
        notifyChanged()
    }
    fun remove(index: Int) {
        val p = player ?: return
        if (index !in paths.indices) return
        if (paths.size == 1) { close(); return }
        paths = paths.toMutableList().apply { removeAt(index) }
        p.removeMediaItem(index)
        notifyChanged()
    }
    fun close() { player?.release(); player = null; paths = emptyList(); notifyChanged() }
}
