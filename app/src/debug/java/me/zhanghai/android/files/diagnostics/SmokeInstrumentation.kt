package me.zhanghai.android.files.diagnostics

import android.app.Instrumentation
import android.app.Activity.RESULT_OK
import android.app.Activity.RESULT_CANCELED
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.zhanghai.android.files.bridge.BridgeClient
import me.zhanghai.android.files.bridge.BridgeConfig
import me.zhanghai.android.files.bridge.BridgeStore
import me.zhanghai.android.files.vault.VaultDataSource
import me.zhanghai.android.files.vault.VaultSession
import me.zhanghai.android.files.viewer.video.PathDataSource
import java8.nio.file.Paths

/** Debug-only instrumentation: isolated generated fixtures, no user files or real clipboard access. */
@androidx.media3.common.util.UnstableApi
class SmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = mutableListOf<String>()
        val root = File(targetContext.cacheDir, "vault-diagnostic")
        try {
            root.deleteRecursively()
            val password = "FileWeave-QA-only-2026!"
            VaultSession.unlock(root, password.toByteArray(), true)
            val sample = ByteArray(2 * 1024 * 1024 + 137) { (it % 251).toByte() }
            val name = "测试 🧵.bin"
            VaultSession.import(ByteArrayInputStream(sample), name)
            check(VaultSession.list("/").single().name == name)
            val oldGeneration = VaultSession.generation()
            val source = VaultDataSource("/$name", oldGeneration)
            check(source.open(DataSpec.Builder().setUri("fileweave://test").setPosition(1048501).setLength(1024).build()) == 1024L)
            val read = ByteArray(512)
            check(source.read(read, 0, read.size) == read.size)
            check(read.contentEquals(sample.copyOfRange(1048501, 1048501 + 512)))
            VaultSession.lock()
            check(runCatching { source.read(read, 0, 1) }.exceptionOrNull() is IOException)
            source.close() // Must not double-close a stale native handle.
            results += "vault Unicode names, cross-block random reads and lock revocation: PASS"
            check(runCatching { VaultSession.unlock(root, "wrong-password".toByteArray(), false) }.isFailure)
            check(!VaultSession.isOpen())
            VaultSession.unlock(root, password.toByteArray(), false)
            val duplicate = VaultSession.import(ByteArrayInputStream(byteArrayOf(7, 8, 9)), name)
            check(duplicate != name && VaultSession.list("/").size == 2)
            results += "wrong password, reopen and collision-safe import: PASS"
            val encrypted = root.listFiles()!!.first { it.isFile && it.length() > sample.size }
            val file = java.io.RandomAccessFile(encrypted, "rw")
            file.seek(100); val original = file.readByte(); file.seek(100); file.writeByte(original.toInt() xor 1); file.close()
            val damaged = VaultDataSource("/$name", VaultSession.generation())
            damaged.open(DataSpec.Builder().setUri("fileweave://test").build())
            check(runCatching { damaged.read(read, 0, read.size) }.exceptionOrNull() is IOException)
            damaged.close()
            java.io.RandomAccessFile(encrypted, "rw").use { it.seek(100); it.writeByte(original.toInt()) }
            results += "ciphertext tamper detection: PASS"
            val large = File(targetContext.cacheDir, "fileweave-large-qa.bin")
            try {
                val offset = 5L * 1024 * 1024 * 1024
                java.io.RandomAccessFile(large, "rw").use { it.setLength(offset + 16); it.seek(offset); it.write(ByteArray(16) { 99 }) }
                val largeSource = PathDataSource(Paths.get(large.toURI()))
                try {
                    check(largeSource.open(DataSpec.Builder().setUri("fileweave://large").setPosition(offset).build()) == 16L)
                    val tail = ByteArray(16)
                    check(largeSource.read(tail, 0, 16) == 16 && tail.all { it == 99.toByte() })
                } finally { largeSource.close() }
                results += "file reads beyond 4 GiB: PASS"
            } finally { large.delete() }
            val video = File(targetContext.cacheDir, "fileweave-qa.mp4")
            if (video.exists()) {
                playback(DataSource.Factory { PathDataSource(Paths.get(video.toURI())) })
                results += "local MP4 prepare, playback and seek: PASS"
                video.inputStream().use { VaultSession.import(it, "video.mp4") }
                val generation = VaultSession.generation()
                playback(DataSource.Factory { VaultDataSource("/video.mp4", generation) })
                results += "encrypted MP4 prepare, playback and seek without export: PASS"
            }
            val interrupted = object : java.io.InputStream() {
                var calls = 0
                override fun read(): Int = error("Bulk read expected")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++calls == 2) VaultSession.lock()
                    buffer.fill(1, offset, offset + minOf(length, 1024))
                    return minOf(length, 1024)
                }
            }
            check(runCatching { VaultSession.import(interrupted, "interrupted.bin") }.isFailure)
            VaultSession.unlock(root, password.toByteArray(), false)
            check(VaultSession.list("/").none { it.name == "interrupted.bin" || it.name.startsWith(".fileweave-import-") })
            val encryptedBackup = java.io.ByteArrayOutputStream()
            me.zhanghai.android.files.vault.VaultBackup.export(root, encryptedBackup)
            VaultSession.lock()
            val restored = File(targetContext.cacheDir, "vault-diagnostic-restored")
            restored.deleteRecursively()
            try {
                me.zhanghai.android.files.vault.VaultBackup.restore(ByteArrayInputStream(encryptedBackup.toByteArray()), restored)
                VaultSession.unlock(restored, password.toByteArray(), false)
                val original = java.io.ByteArrayOutputStream()
                VaultSession.export("/$name", original)
                check(original.toByteArray().contentEquals(sample))
                VaultSession.lock()
            } finally { restored.deleteRecursively() }
            results += "interrupted import cleanup, ciphertext backup/restore and byte-exact plaintext recovery: PASS"
            check(root.walkTopDown().filter { it.isFile }.none { it.name == name || it.name == "video.mp4" })
            val pairing = File(targetContext.cacheDir, "qa-pairing.txt")
            if (pairing.exists()) {
                val config = BridgeConfig.parse(pairing.readText())
                val bridge = BridgeClient(config)
                try {
                    check(bridge.json("status").getInt("protocol") == 1)
                    val wrong = BridgeClient(config.copy(pin = "00".repeat(32)))
                    try { check(runCatching { wrong.json("status") }.isFailure) } finally { wrong.close() }
                    bridge.upload("FileWeave-QA.txt", ByteArrayInputStream("FileWeave 中文 🧵".toByteArray()))
                    val files = bridge.json("files").getJSONArray("files")
                    check((0 until files.length()).any { files.getJSONObject(it).getString("name") == "FileWeave-QA.mp4" })
                    val downloaded = java.io.ByteArrayOutputStream()
                    bridge.download("FileWeave-QA.mp4", downloaded)
                    check(downloaded.toByteArray().contentEquals(video.readBytes()))
                    val factory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(bridge.http)
                        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${config.token}"))
                    playback(factory, Uri.parse(bridge.url("file", "FileWeave-QA.mp4").toString()))
                    BridgeStore.save(targetContext, config)
                    check(BridgeStore.read(targetContext) == config)
                    results += "Mac pinned TLS, wrong-pin rejection, upload/download, streamed video and encrypted pairing storage: PASS"
                } finally { bridge.close(); pairing.delete() }
            }
            finish(RESULT_OK, Bundle().apply { putString("stream", results.joinToString("\n")) })
        } catch (e: Throwable) {
            finish(RESULT_CANCELED, Bundle().apply { putString("stream", results.joinToString("\n") + "\nFAILED: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}") })
        } finally { VaultSession.lock(); root.deleteRecursively() }
    }

    private fun playback(factory: DataSource.Factory, uri: Uri = Uri.parse("fileweave://qa")) {
        val ready = CountDownLatch(1)
        val seeked = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        var player: ExoPlayer? = null
        runOnMainSync {
            player = ExoPlayer.Builder(targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { ready.countDown(); if (currentPosition >= 2000) seeked.countDown() } }
                    override fun onPlayerError(failure: PlaybackException) { error.set(failure); ready.countDown(); seeked.countDown() }
                })
                setMediaItem(MediaItem.Builder().setUri(uri).setMimeType("video/mp4").build())
                prepare(); playWhenReady = true
            }
        }
        try {
            check(ready.await(30, TimeUnit.SECONDS)) { "Player prepare timeout" }
            error.get()?.let { throw it }
            runOnMainSync { check(player!!.duration >= 4000); player!!.seekTo(2500) }
            check(seeked.await(30, TimeUnit.SECONDS)) { "Player seek timeout" }
            error.get()?.let { throw it }
        } finally { runOnMainSync { player?.release() } }
    }
}
