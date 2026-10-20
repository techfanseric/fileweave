package me.zhanghai.android.files.viewer.video

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar
import java.security.MessageDigest
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.startActivitySafe

@androidx.media3.common.util.UnstableApi
open class VideoViewerActivity : AppActivity() {
    private val vaultPath: String? by lazy { if (this is VaultVideoActivity) intent.getStringExtra("vault_path") else null }
    private val vaultGeneration by lazy { intent.getLongExtra("vault_generation", -1) }
    private val bridgeClient by lazy {
        if (this is BridgeVideoActivity) me.zhanghai.android.files.bridge.BridgeStore.read(this)?.let { me.zhanghai.android.files.bridge.BridgeClient(it) } else null
    }
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var gestures: VideoGestureLayout
    private lateinit var errorView: TextView
    private lateinit var retryView: Button
    private var path: Path? = null
    private var subtitleUri: Uri? = null
    private var position = 0L
    private var shouldPlay = true
    private val positions by lazy { getSharedPreferences("video_positions", MODE_PRIVATE) }
    private val videoUri by lazy { if (path != null || vaultPath != null) Uri.parse("fileweave://video") else intent.data }
    private val positionKey by lazy {
        MessageDigest.getInstance("SHA-256").digest((path?.toUri()?.toString() ?: intent.data.toString()).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
            saveAndRelease()
            subtitleUri = uri
            initializePlayer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.extraPath
        if (path == null && vaultPath == null && intent.data == null) { finish(); return }
        if (vaultPath != null) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            if (!me.zhanghai.android.files.vault.VaultSession.isOpen() || me.zhanghai.android.files.vault.VaultSession.generation() != vaultGeneration) { finish(); return }
        }
        position = savedInstanceState?.getLong("position") ?: if (vaultPath == null) positions.getLong(positionKey, 0) else 0
        shouldPlay = savedInstanceState?.getBoolean("playing") ?: true
        subtitleUri = savedInstanceState?.getString("subtitle")?.let(Uri::parse)
        setContentView(R.layout.video_viewer_activity)
        gestures = findViewById(R.id.video_root)
        val root = gestures
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val systemUi = androidx.core.view.WindowInsetsControllerCompat(window, root)
        systemUi.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        systemUi.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val toolbar = findViewById<MaterialToolbar>(R.id.video_toolbar).apply {
            title = vaultPath?.substringAfterLast('/') ?: path?.fileName?.toString() ?: intent.data?.lastPathSegment ?: getString(R.string.fw_video_title)
            setNavigationOnClickListener { finish() }
        }
        playerView = findViewById(R.id.video_player)
        val preview = findViewById<TextView>(R.id.video_seek_preview)
        val audio = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val stream = android.media.AudioManager.STREAM_MUSIC
        var startPosition = 0L
        var seekDuration = 0L
        var seekTarget = 0L
        var resume = false
        var brightnessBefore = -1f
        var startBrightness = .5f
        var startVolume = 0
        var maxVolume = 1
        val dismissFeedback = Runnable { preview.visibility = View.GONE }
        fun showFeedback(text: String) {
            preview.removeCallbacks(dismissFeedback)
            preview.text = text
            preview.visibility = View.VISIBLE
        }
        fun hideChrome() {
            playerView.hideController()
            toolbar.visibility = View.GONE
            systemUi.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        gestures.isControlHit = { event ->
            gestures.hits(toolbar, event) || gestures.hits(playerView.findViewById(androidx.media3.ui.R.id.exo_bottom_bar), event)
        }
        gestures.listener = object : VideoGestureLayout.Listener {
            override fun onDown() {
                player?.let { p -> startPosition = p.currentPosition; seekDuration = p.duration; resume = p.playWhenReady }
                brightnessBefore = window.attributes.screenBrightness
                startBrightness = if (brightnessBefore >= 0) brightnessBefore else
                    (android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.01f, 1f)
                startVolume = audio.getStreamVolume(stream)
                maxVolume = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
            }
            override fun onStart(kind: VideoGestureLayout.Kind): Boolean {
                val p = player ?: return false
                if (kind == VideoGestureLayout.Kind.SEEK) {
                    if (!p.isCurrentMediaItemSeekable || seekDuration <= 0) return false
                    seekTarget = startPosition
                    p.pause()
                }
                hideChrome()
                return true
            }
            override fun onMove(kind: VideoGestureLayout.Kind, dx: Float, dy: Float) {
                when (kind) {
                    VideoGestureLayout.Kind.SEEK -> {
                        seekTarget = SeekGesture.target(startPosition, seekDuration, dx, 1)
                        showFeedback(seekFeedback(seekTarget, seekDuration, seekTarget - startPosition))
                    }
                    VideoGestureLayout.Kind.BRIGHTNESS -> {
                        val value = (startBrightness + dy * 2).coerceIn(.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = value }
                        showFeedback(getString(R.string.fw_video_brightness, kotlin.math.round(value * 100).toInt()))
                    }
                    VideoGestureLayout.Kind.VOLUME -> {
                        val value = kotlin.math.round(startVolume + dy * 2 * maxVolume).toInt().coerceIn(0, maxVolume)
                        try { audio.setStreamVolume(stream, value, 0) } catch (_: SecurityException) { }
                        showFeedback(getString(R.string.fw_video_volume, audio.getStreamVolume(stream) * 100 / maxVolume))
                    }
                }
            }
            override fun onEnd(kind: VideoGestureLayout.Kind, commit: Boolean) {
                when (kind) {
                    VideoGestureLayout.Kind.SEEK -> player?.let { p ->
                        if (commit) p.seekTo(seekTarget)
                        p.playWhenReady = resume
                    }
                    VideoGestureLayout.Kind.BRIGHTNESS -> if (!commit) {
                        window.attributes = window.attributes.apply { screenBrightness = brightnessBefore }
                    }
                    VideoGestureLayout.Kind.VOLUME -> if (!commit) {
                        try { audio.setStreamVolume(stream, startVolume, 0) } catch (_: SecurityException) { }
                    }
                }
                hideChrome()
                if (commit) preview.postDelayed(dismissFeedback, 500) else dismissFeedback.run()
            }
            override fun onTap() {
                dismissFeedback.run()
                if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
            }
            override fun onDoubleTap(right: Boolean) {
                val p = player ?: return
                if (!p.isCurrentMediaItemSeekable || p.duration <= 0) return
                val start = p.currentPosition
                val target = (start + if (right) 10000 else -10000).coerceIn(0, p.duration)
                p.seekTo(target)
                hideChrome()
                showFeedback(seekFeedback(target, p.duration, target - start))
                preview.postDelayed(dismissFeedback, 650)
            }
        }
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            toolbar.visibility = visibility
        })
        playerView.hideController()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            toolbar.setPadding(safe.left, safe.top, safe.right, 0)
            playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                ?.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
        errorView = findViewById(R.id.video_error)
        retryView = findViewById<Button>(R.id.video_retry).apply {
            setOnClickListener { saveAndRelease(); initializePlayer() }
        }
        if (vaultPath == null) {
            toolbar.menu.add(R.string.fw_video_subtitle).setOnMenuItemClickListener {
                subtitlePicker.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
                true
            }
            toolbar.menu.add(R.string.fw_video_external).setOnMenuItemClickListener {
                val uri = path?.fileProviderUri ?: intent.data
                if (uri != null) {
                    val external = Intent(Intent.ACTION_VIEW).setDataAndType(uri, intent.type ?: "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivitySafe(Intent.createChooser(external, getString(R.string.fw_video_external))
                        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(componentName)))
                }
                true
            }
        }
        toolbar.overflowIcon?.setTint(android.graphics.Color.WHITE)
    }

    private fun seekFeedback(target: Long, duration: Long, delta: Long): String =
        "${formatTime(target)} / ${formatTime(duration)}\n${if (delta >= 0) "+" else "−"}${formatTime(kotlin.math.abs(delta))}"

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return if (seconds >= 3600) java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }

    override fun onStart() { super.onStart(); initializePlayer() }

    private fun initializePlayer() {
        if (player != null || videoUri == null) return
        errorView.visibility = View.GONE
        retryView.visibility = View.GONE
        val factory = DataSource.Factory {
            val filePath = path
            if (vaultPath != null) me.zhanghai.android.files.vault.VaultDataSource(vaultPath!!, vaultGeneration)
            else if (filePath != null) {
                val fallback = DefaultDataSource.Factory(this)
                object : DataSource {
                    private var delegate: DataSource? = null
                    private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()
                    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { listeners.add(listener); delegate?.addTransferListener(listener) }
                    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                        val source = if (spec.uri == videoUri) PathDataSource(filePath) else fallback.createDataSource()
                        delegate = source
                        listeners.forEach(source::addTransferListener)
                        return source.open(spec)
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(delegate).read(buffer, offset, length)
                    override fun getUri() = delegate?.uri
                    override fun close() { delegate?.close(); delegate = null }
                }
            } else if (this is BridgeVideoActivity) {
                val bridge = bridgeClient ?: throw java.io.IOException("Pairing unavailable")
                DefaultDataSource.Factory(this, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(bridge.http)
                    .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${bridge.config.token}"))).createDataSource()
            } else DefaultDataSource.Factory(this).createDataSource()
        }
        val media = MediaItem.Builder().setUri(videoUri!!)
        // The synthetic URI has no suffix, so preserve the actual media type for extraction.
        intent.type?.takeIf { it != "video/*" }?.let(media::setMimeType)
        subtitleUri?.let { uri ->
            val name = (uri.lastPathSegment ?: "").lowercase()
            val mime = when {
                name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            media.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).setLabel(getString(R.string.fw_video_subtitle)).build()))
        }
        player = ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().also { p ->
            playerView.player = p
            p.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errorView.text = getString(R.string.fw_video_error, error.errorCodeName)
                    errorView.visibility = View.VISIBLE
                    retryView.visibility = View.VISIBLE
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) { position = 0; positions.edit().remove(positionKey).apply() }
                }
            })
            p.setMediaItem(media.build())
            p.seekTo(position)
            p.prepare()
            p.playWhenReady = shouldPlay
        }
    }

    private fun saveAndRelease() {
        if (::gestures.isInitialized) gestures.cancelGesture()
        player?.let { p ->
            position = if (p.playbackState == Player.STATE_ENDED) 0 else p.currentPosition.coerceAtLeast(0)
            shouldPlay = p.playWhenReady
            // Bound the history; only hashes and positions are stored, never remote credentials.
            if (vaultPath == null) {
                if (positions.all.size > 200) positions.edit().clear().apply()
                positions.edit().putLong(positionKey, position).apply()
            }
            playerView.player = null
            p.release()
        }
        player = null
    }

    override fun onDestroy() { bridgeClient?.close(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::gestures.isInitialized) gestures.cancelGesture()
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playing", player?.playWhenReady ?: shouldPlay)
        outState.putString("subtitle", subtitleUri?.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        saveAndRelease()
        if (vaultPath != null && !isChangingConfigurations) { me.zhanghai.android.files.vault.VaultSession.lock(); finish() }
        super.onStop()
    }
}

/** Internal-only entry point: exported VIEW intents cannot request decrypted vault content. */
@androidx.media3.common.util.UnstableApi
class VaultVideoActivity : VideoViewerActivity()

@androidx.media3.common.util.UnstableApi
class BridgeVideoActivity : VideoViewerActivity()
