package me.zhanghai.android.files.viewer.audio

import me.zhanghai.android.files.ui.ActionPanel
import androidx.appcompat.widget.PopupMenu
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.TimeBar
import java.util.Locale
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.AudioBarBinding

/** A themed playback dock; its height is reserved inside the file browser, below the drawer. */
@androidx.media3.common.util.UnstableApi
class AudioBar(private val fragment: androidx.fragment.app.Fragment, parent: FrameLayout) {
    private val context = fragment.requireContext()
    private val host = parent.findViewById<FrameLayout>(R.id.audio_host)
    private val browser = parent.findViewById<View>(R.id.persistentBarLayout)
    private val initialBrowserMargin = (browser.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
    private val binding = AudioBarBinding.inflate(LayoutInflater.from(context), host, true)
    private var bottomInset = 0
    private var started = false
    private var scrubPlayer: Player? = null
    private var scrubIndex = -1
    private val observer: () -> Unit = { update() }
    private val tick = Runnable { update() }

    init {
        val padding = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bottomInset = safe.bottom
            binding.root.setPadding(dp(16) + safe.left, dp(8), dp(16) + safe.right, padding + safe.bottom)
            reserveSpace()
            insets
        }
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reserveSpace() }
        binding.audioClose.setOnClickListener { AudioPlayback.close() }
        binding.audioPrevious.setOnClickListener { AudioPlayback.player?.seekToPreviousMediaItem() }
        binding.audioNext.setOnClickListener { AudioPlayback.player?.seekToNextMediaItem() }
        binding.audioToggle.setOnClickListener {
            AudioPlayback.player?.let { p ->
                if (p.playWhenReady && p.playerError == null && p.playbackState != Player.STATE_ENDED) p.pause()
                else {
                    if (p.playerError != null) p.prepare()
                    if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                    p.play()
                }
            }
        }
        binding.audioSpeed.setOnClickListener {
            val p = AudioPlayback.player ?: return@setOnClickListener
            val values = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
            AlertDialog.Builder(context).setTitle(R.string.fw_audio_speed)
                .setSingleChoiceItems(values.map(::speedLabel).toTypedArray(), values.indexOfFirst { it == p.playbackParameters.speed }) { dialog, index ->
                    if (AudioPlayback.player === p) p.setPlaybackSpeed(values[index])
                    dialog.dismiss()
                }.show()
        }
        binding.audioQueue.setOnClickListener { showQueue() }
        binding.audioRepeat.setOnClickListener {
            val p = AudioPlayback.player ?: return@setOnClickListener
            val labels = intArrayOf(R.string.fw_repeat_off, R.string.fw_repeat_all, R.string.fw_repeat_one, R.string.fw_repeat_shuffle)
            val selected = if (p.shuffleModeEnabled) 3 else when (p.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 }
            AlertDialog.Builder(context).setTitle(R.string.fw_repeat_title)
                .setSingleChoiceItems(labels.map(context::getString).toTypedArray(), selected) { dialog, index ->
                    if (AudioPlayback.player === p) {
                        p.shuffleModeEnabled = index == 3
                        p.repeatMode = when (index) { 1, 3 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                    }
                    dialog.dismiss()
                }.show()
        }
        binding.audioProgress.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                scrubPlayer = AudioPlayback.player
                scrubIndex = scrubPlayer?.currentMediaItemIndex ?: -1
                binding.audioPosition.text = formatTime(position)
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                binding.audioPosition.text = formatTime(position)
            }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                val p = scrubPlayer
                scrubPlayer = null
                if (!canceled && p != null && p === AudioPlayback.player && p.currentMediaItemIndex == scrubIndex && p.isCurrentMediaItemSeekable) {
                    p.seekTo(position.coerceIn(0, p.duration.coerceAtLeast(0)))
                }
                update()
            }
        })
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                started = true
                AudioPlayback.observe(observer)
                ViewCompat.requestApplyInsets(host)
            }
            override fun onStop(owner: LifecycleOwner) {
                started = false
                AudioPlayback.removeObserver(observer)
                binding.root.removeCallbacks(tick)
                // Disabling the time bar cancels an unfinished drag without seeking.
                binding.audioProgress.isEnabled = false
                scrubPlayer = null
            }
            override fun onDestroy(owner: LifecycleOwner) {
                AudioPlayback.removeObserver(observer)
                binding.root.removeCallbacks(tick)
            }
        })
        binding.audioProgress.setUnplayedColor(themeColor(android.R.attr.textColorPrimary, 0.12f))
        binding.audioProgress.setBufferedColor(themeColor(android.R.attr.textColorPrimary, 0.24f))
    }

    private fun showQueue() {
        val p = AudioPlayback.player ?: return
        var snapshot = AudioPlayback.paths.toList()
        if (snapshot.isEmpty()) return
        val menu = PopupMenu(context, binding.audioQueue).menu
        val actions = java.util.IdentityHashMap<MenuItem, () -> Unit>()
        lateinit var panel: ActionPanel
        fun valid() = AudioPlayback.player === p && AudioPlayback.paths == snapshot
        fun rebuild() {
            menu.clear()
            actions.clear()
            snapshot.forEachIndexed { index, path ->
                val label = "${if (index == p.currentMediaItemIndex) "▶ " else ""}${index + 1}. ${path.fileName}"
                val child = menu.addSubMenu(0, View.generateViewId(), index, label)
                fun action(title: Int, run: () -> Unit) {
                    val item = child.add(0, View.generateViewId(), child.size(), title)
                    actions[item] = {
                        if (valid()) {
                            run()
                            snapshot = AudioPlayback.paths.toList()
                            if (snapshot.isEmpty() || AudioPlayback.player !== p) panel.dismiss()
                            else { rebuild(); panel.returnToRoot() }
                        } else panel.dismiss()
                    }
                }
                action(R.string.fw_audio_play) { p.seekToDefaultPosition(index); p.play() }
                if (index > 0) action(R.string.fw_queue_up) { AudioPlayback.move(index, index - 1) }
                if (index < snapshot.lastIndex) action(R.string.fw_queue_down) { AudioPlayback.move(index, index + 1) }
                action(R.string.fw_queue_remove) { AudioPlayback.remove(index) }
            }
            val clear = menu.add(1, View.generateViewId(), snapshot.size, R.string.fw_queue_clear)
            actions[clear] = {
                AlertDialog.Builder(context).setMessage(R.string.fw_queue_clear_confirm)
                    .setPositiveButton(R.string.fw_queue_clear) { _, _ ->
                        if (valid()) AudioPlayback.close()
                        panel.dismiss()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        rebuild()
        panel = ActionPanel(context, fragment.viewLifecycleOwner, context.getString(R.string.fw_audio_queue),
            menu, keepOpen = { true }, isValid = ::valid) { actions[it]?.invoke() }.show()
    }

    private fun reserveSpace() {
        val margin = initialBrowserMargin + if (host.visibility == View.VISIBLE) (host.height - bottomInset).coerceAtLeast(0) else 0
        val params = browser.layoutParams as ViewGroup.MarginLayoutParams
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            browser.layoutParams = params
        }
    }

    private fun update() {
        binding.root.removeCallbacks(tick)
        if (!started) return
        val p = AudioPlayback.player
        host.visibility = if (p == null) View.GONE else View.VISIBLE
        reserveSpace()
        if (p == null) {
            binding.audioProgress.isEnabled = false
            scrubPlayer = null
            return
        }
        val name = AudioPlayback.paths.getOrNull(p.currentMediaItemIndex)?.fileName?.toString().orEmpty()
        binding.audioTitle.text = name
        binding.audioStatus.text = when {
            p.playerError != null -> context.getString(R.string.fw_audio_error, name)
            p.playbackState == Player.STATE_BUFFERING -> context.getString(R.string.fw_audio_loading)
            else -> context.getString(R.string.fw_audio_ready, p.currentMediaItemIndex + 1, p.mediaItemCount)
        }
        val willPlay = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playerError == null
        binding.audioToggle.setIconResource(if (willPlay) R.drawable.player_pause else R.drawable.player_play)
        binding.audioToggle.contentDescription = context.getString(when {
            p.playerError != null -> R.string.fw_audio_retry
            willPlay -> R.string.fw_audio_pause
            else -> R.string.fw_audio_play
        })
        binding.audioPrevious.isEnabled = p.hasPreviousMediaItem()
        binding.audioNext.isEnabled = p.hasNextMediaItem()
        binding.audioPrevious.alpha = if (binding.audioPrevious.isEnabled) 1f else .38f
        binding.audioNext.alpha = if (binding.audioNext.isEnabled) 1f else .38f
        binding.audioRepeat.contentDescription = context.getString(when {
            p.shuffleModeEnabled -> R.string.fw_repeat_shuffle
            p.repeatMode == Player.REPEAT_MODE_ONE -> R.string.fw_repeat_one
            p.repeatMode == Player.REPEAT_MODE_ALL -> R.string.fw_repeat_all
            else -> R.string.fw_repeat_off
        })
        binding.audioRepeat.alpha = if (p.repeatMode != Player.REPEAT_MODE_OFF || p.shuffleModeEnabled) 1f else .5f
        val speed = speedLabel(p.playbackParameters.speed)
        binding.audioSpeed.text = speed
        binding.audioSpeed.contentDescription = context.getString(R.string.fw_audio_speed_value, speed)
        if (scrubPlayer != null && (scrubPlayer !== p || scrubIndex != p.currentMediaItemIndex)) {
            scrubPlayer = null
            binding.audioProgress.isEnabled = false
        }
        val duration = p.duration.coerceAtLeast(0)
        binding.audioProgress.isEnabled = p.isCurrentMediaItemSeekable && duration > 0 && p.playerError == null
        binding.audioProgress.setDuration(duration)
        binding.audioProgress.setBufferedPosition(p.bufferedPosition.coerceAtLeast(0))
        if (scrubPlayer == null) {
            binding.audioProgress.setPosition(p.currentPosition.coerceAtLeast(0))
            binding.audioPosition.text = formatTime(p.currentPosition)
        }
        binding.audioDuration.text = if (duration > 0) formatTime(duration) else "—"
        binding.audioProgress.contentDescription = context.getString(R.string.fw_audio_position,
            binding.audioPosition.text, binding.audioDuration.text)
        binding.root.postDelayed(tick, 500)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun themeColor(attribute: Int, alpha: Float): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        val color = values.getColor(0, 0)
        values.recycle()
        return androidx.core.graphics.ColorUtils.setAlphaComponent(color, (255 * alpha).toInt())
    }
    private fun speedLabel(speed: Float) = "${java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString()}×"
    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
