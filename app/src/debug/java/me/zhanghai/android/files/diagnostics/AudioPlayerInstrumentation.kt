package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import java.io.File
import java.io.FileOutputStream
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.viewer.audio.AudioPlayback
import kotlin.math.abs

/** Generated cache-only WAVs and real time-bar input; never reads personal recordings. */
@androidx.media3.common.util.UnstableApi
class AudioPlayerInstrumentation : Instrumentation() {
    private var screen: Activity? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is FileListActivity) screen = activity
    }
    override fun onStart() {
        val directory = File(targetContext.cacheDir, "audio-player-qa")
        try {
            check(targetContext.packageName.endsWith(".playerqa")) { "Requires an isolated .playerqa applicationId" }
            check(directory.mkdirs() || directory.isDirectory)
            val first = File(directory, "A recording with a long descriptive filename.wav")
            val second = File(directory, "Second recording.wav")
            wav(first); wav(second)
            val paths = listOf(first, second).map { Paths.get(it.absolutePath) }
            // File-list path observers have a separately tracked Root initialization issue.
            // Isolate player UI checks from it; production settings are never touched.
            onMain { me.zhanghai.android.files.settings.Settings.ROOT_STRATEGY.putValue(me.zhanghai.android.files.provider.root.RootStrategy.NEVER) }
            startActivitySync(Intent(targetContext, FileListActivity::class.java)
                .setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { extraPath = Paths.get(directory.absolutePath) })
            await { screen?.findViewById<View>(R.id.audio_host) != null }
            onMain { AudioPlayback.play(targetContext, paths, paths.first()) }
            await { AudioPlayback.player?.playbackState == Player.STATE_READY && screen!!.findViewById<View>(R.id.audio_host).height > 0 }
            for (landscape in listOf(false, true)) {
                onMain { screen!!.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await { screen!!.resources.configuration.orientation == if (landscape) 2 else 1 }
                await { screen!!.findViewById<View>(R.id.audio_progress)?.isShown == true }
                onMain {
                    val current = checkNotNull(screen)
                    val p = checkNotNull(AudioPlayback.player)
                    p.pause(); p.seekTo(10000)
                    val bar = current.findViewById<DefaultTimeBar>(R.id.audio_progress)
                    check(bar.isEnabled)
                    check(current.findViewById<TextView>(R.id.audio_duration).text.toString() == "02:00")
                    check(current.findViewById<View>(R.id.audio_title).width > 0)
                    val host = current.findViewById<View>(R.id.audio_host)
                    val browser = current.findViewById<View>(R.id.persistentBarLayout)
                    check(browser.bottom <= host.top + 100) { "Audio dock obscures file browser" }
                    scrub(bar, .5f)
                    check(abs(p.currentPosition - 60000) < 3000 && !p.playWhenReady)
                    p.seekTo(10000)
                    scrub(bar, .8f, MotionEvent.ACTION_CANCEL)
                    check(abs(p.currentPosition - 10000) < 1000 && !p.playWhenReady)
                    current.findViewById<View>(R.id.audio_toggle).performClick()
                    check(p.playWhenReady)
                    scrub(bar, .25f)
                    check(abs(p.currentPosition - 30000) < 3000 && p.playWhenReady)
                    current.findViewById<View>(R.id.audio_next).performClick()
                    check(p.currentMediaItemIndex == 1)
                    current.findViewById<View>(R.id.audio_previous).performClick()
                    check(p.currentMediaItemIndex == 0)
                    p.seekTo(5000); p.play()
                }
                await { AudioPlayback.player?.isPlaying == true && AudioPlayback.player!!.currentPosition >= 6000 }
                await { screen!!.findViewById<TextView>(R.id.audio_position).text.toString() !in listOf("00:00", "00:05") }
                screenshot("audio-${if (landscape) "landscape" else "portrait"}")
            }
            onMain {
                val p = checkNotNull(AudioPlayback.player)
                p.pause(); p.seekTo(0, 10000)
                val source = p.currentMediaItem!!.localConfiguration!!.uri
                AudioPlayback.move(0, 1)
                check(AudioPlayback.paths[1] == paths[0])
                check(p.currentMediaItemIndex == 1 && p.currentMediaItem!!.localConfiguration!!.uri == source)
                AudioPlayback.remove(0)
                check(p.mediaItemCount == 1 && AudioPlayback.paths.single() == paths[0])
                check(p.currentMediaItem!!.localConfiguration!!.uri == source)
                p.repeatMode = Player.REPEAT_MODE_ONE
                p.seekTo(119800); p.play()
            }
            await { AudioPlayback.player!!.currentPosition < 5000 && AudioPlayback.player!!.isPlaying }
            onMain { screen!!.findViewById<View>(R.id.audio_close).performClick() }
            await { AudioPlayback.player == null && screen!!.findViewById<View>(R.id.audio_host).visibility == View.GONE }
            val picture = File(directory, "picture.png")
            android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.BLUE)
                picture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val imageIntent = Intent(targetContext, me.zhanghai.android.files.viewer.image.ImageViewerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            me.zhanghai.android.files.viewer.image.ImageViewerActivity.putExtras(imageIntent, listOf(Paths.get(picture.absolutePath)), 0)
            val imageScreen = startActivitySync(imageIntent)
            try {
                await { imageScreen.hasWindowFocus() && imageScreen.findViewById<android.widget.ImageView>(R.id.image)?.let { it.drawable != null && it.isShown && it.width > 0 && it.alpha == 1f } == true }
                Thread.sleep(500)
                await { imageScreen.findViewById<View>(R.id.appBarLayout).visibility == View.INVISIBLE }
                fun tapImage() {
                    val point = IntArray(2)
                    var x = 0f; var y = 0f
                    onMain {
                        val view = imageScreen.findViewById<View>(R.id.image)
                        view.getLocationOnScreen(point)
                        x = point[0] + view.width / 2f; y = point[1] + view.height / 2f
                    }
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).let { sendPointerSync(it); it.recycle() }
                    }
                }
                tapImage()
                await { imageScreen.findViewById<View>(R.id.appBarLayout).visibility == View.VISIBLE }
                Thread.sleep(500)
                tapImage()
                await { imageScreen.findViewById<View>(R.id.appBarLayout).visibility == View.INVISIBLE }
            } finally { onMain { imageScreen.finish() } }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "audio portrait + landscape: themed dock, reserved space, duration, real scrub, canceled scrub, paused/playing preservation, previous/next, progress timer, rotation, queue reorder/remove, repeat current, close and image immersive/tap toggle: PASS\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${error.stackTraceToString()}\n") })
        } finally {
            onMain { AudioPlayback.close(); screen?.finish() }
            directory.deleteRecursively()
        }
    }
    private fun onMain(action: () -> Unit) = runOnMainSync(action)
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < end) {
            onMain { ready = condition() }
            Thread.sleep(100)
        }
        check(ready) { "Audio UI/player did not become ready" }
    }
    private fun scrub(bar: View, fraction: Float, end: Int = MotionEvent.ACTION_UP) {
        val now = SystemClock.uptimeMillis()
        for ((action, x) in listOf(MotionEvent.ACTION_DOWN to .1f, MotionEvent.ACTION_MOVE to fraction, end to fraction)) {
            MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bar.width * x, bar.height / 2f, 0).let {
                bar.dispatchTouchEvent(it); it.recycle()
            }
        }
    }
    private fun screenshot(name: String) {
        waitForIdleSync()
        Thread.sleep(150)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        try { File(targetContext.cacheDir, "qa-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun wav(file: File) {
        val size = 120 * 8000 * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        FileOutputStream(file).use { it.write(header.array()); it.write(ByteArray(size)) }
    }
}
