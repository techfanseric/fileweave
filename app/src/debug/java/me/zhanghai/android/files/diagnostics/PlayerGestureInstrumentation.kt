package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.viewer.video.VideoGestureLayout
import me.zhanghai.android.files.viewer.video.VideoViewerActivity
import kotlin.math.abs

/** Actual input dispatch, in portrait and landscape, with isolated generated cache media. */
@androidx.media3.common.util.UnstableApi
class PlayerGestureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var activity: Activity? = null
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            for (landscape in listOf(false, true)) {
                activity = startActivitySync(Intent(targetContext, VideoViewerActivity::class.java)
                    .setDataAndType(Uri.fromFile(java.io.File(targetContext.cacheDir, "player-gesture-qa.mp4")), "video/mp4")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val screen = checkNotNull(activity)
                // Keep the test Activity instance while its window follows the new display rotation.
                runOnMainSync { screen.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                var root: VideoGestureLayout? = null
                var view: PlayerView? = null
                await {
                    // Rotation can recreate the Activity: use the latest resumed instance.
                    val current = currentScreen ?: screen
                    root = current.findViewById(R.id.video_root)
                    view = current.findViewById(R.id.video_player)
                    view?.player?.playbackState == Player.STATE_READY &&
                        (root!!.width > root!!.height) == landscape
                }
                val current = currentScreen ?: screen
                activity = current
                val gestures = checkNotNull(root)
                val playerView = checkNotNull(view)
                runOnMainSync {
                    val p = checkNotNull(playerView.player)
                    p.pause(); p.seekTo(60000); playerView.hideController()
                    // Horizontal seek works at left/right/top/bottom, including visible controls.
                    for ((x, y) in listOf(.05f to .5f, .65f to .5f, .4f to .03f, .4f to .93f, .4f to .96f)) {
                        p.seekTo(60000)
                        playerView.showController()
                        swipe(gestures, x, y, x + .25f, y)
                        check(abs(p.currentPosition - 90000) < 1000) { "Seek from $x,$y gave ${p.currentPosition}" }
                        check(!p.playWhenReady)
                        check(!playerView.isControllerFullyVisible) { "Gesture revealed controls" }
                        check(current.findViewById<View>(R.id.video_toolbar).visibility == View.GONE)
                    }
                    p.play()
                    val before = p.currentPosition
                    swipe(gestures, .4f, .5f, .7f, .5f, MotionEvent.ACTION_CANCEL)
                    check(abs(p.currentPosition - before) < 1000 && p.playWhenReady)
                    p.pause()
                    val oldBrightness = current.window.attributes.screenBrightness
                    swipe(gestures, .15f, .6f, .15f, .4f, MotionEvent.ACTION_CANCEL)
                    check(current.window.attributes.screenBrightness == oldBrightness)
                    swipe(gestures, .15f, .6f, .15f, .4f)
                    check(current.window.attributes.screenBrightness >= .4f)
                    check(!playerView.isControllerFullyVisible)
                    swipe(gestures, .85f, .6f, .85f, .4f, MotionEvent.ACTION_CANCEL)
                    check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume)
                    // Commit volume, then restore it to avoid changing the user's setting after QA.
                    swipe(gestures, .85f, .6f, .85f, .4f)
                    check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= originalVolume)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                    p.seekTo(60000)
                    doubleTap(gestures, .2f)
                    check(abs(p.currentPosition - 50000) < 1000 && !p.playWhenReady)
                }
                Thread.sleep(800)
                runOnMainSync {
                    val p = checkNotNull(playerView.player)
                    doubleTap(gestures, .8f)
                    check(abs(p.currentPosition - 60000) < 1000)
                    check(!playerView.isControllerFullyVisible)
                    playerView.showController()
                }
                Thread.sleep(3000)
                runOnMainSync {
                    check(!playerView.isControllerFullyVisible) { "Controls failed to auto-hide" }
                    val now = SystemClock.uptimeMillis()
                    send(gestures, now, MotionEvent.ACTION_DOWN, .5f, .5f)
                    send(gestures, now, MotionEvent.ACTION_MOVE, .6f, .5f)
                    current.finish()
                }
                waitForIdleSync()
                Thread.sleep(200)
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream",
                "portrait + landscape: seek from all regions, feedback-only UI, pause/resume, cancel, brightness, volume, double tap ±10s, auto-hide and exit during drag: PASS\n") })
        } catch (error: Throwable) {
            (currentScreen ?: activity)?.let { runOnMainSync { it.finish() } }
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${error.stackTraceToString()}\n") })
        } finally { runOnMainSync { audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0) } }
    }
    private var currentScreen: Activity? = null
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is VideoViewerActivity) currentScreen = activity
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync { ready = condition() }
            Thread.sleep(100)
        }
        check(ready) { "Player/window did not become ready" }
    }
    private fun swipe(root: View, x: Float, y: Float, toX: Float, toY: Float, end: Int = MotionEvent.ACTION_UP) {
        val now = SystemClock.uptimeMillis()
        send(root, now, MotionEvent.ACTION_DOWN, x, y)
        send(root, now, MotionEvent.ACTION_MOVE, (x + toX) / 2, (y + toY) / 2)
        send(root, now, MotionEvent.ACTION_MOVE, toX, toY)
        send(root, now, end, toX, toY)
    }
    private fun doubleTap(root: View, x: Float) {
        repeat(2) { index ->
            val now = SystemClock.uptimeMillis()
            send(root, now, MotionEvent.ACTION_DOWN, x, .5f)
            send(root, now, MotionEvent.ACTION_UP, x, .5f)
            if (index == 0) Thread.sleep(70)
        }
    }
    private fun send(view: View, down: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, view.width * x, view.height * y, 0).let {
            view.dispatchTouchEvent(it); it.recycle()
        }
    }
}
