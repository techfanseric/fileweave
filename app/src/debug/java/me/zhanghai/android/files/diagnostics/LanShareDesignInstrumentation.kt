package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.lanshare.LanShareActivity
import me.zhanghai.android.files.lanshare.LanShareService
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.extraPathList
import java.io.File

/** Only QA activities get this font override; no system display setting is changed. */
internal object LanShareDesignConfig { var fontScale = 1f }

class LanShareDesignActivity : LanShareActivity() {
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = LanShareDesignConfig.fontScale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}

/** Generated-data view captures keep FLAG_SECURE intact; no system clipboard is read or written. */
class LanShareDesignInstrumentation : Instrumentation() {
    private var locale = "zh-CN"
    private var night = false
    override fun onCreate(arguments: Bundle?) {
        locale = arguments?.getString("locale") ?: locale
        night = arguments?.getString("night")?.toBoolean() ?: false
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val screens = ArrayList<Activity>()
        val outputs = ArrayList<String>()
        var fixtures: File? = null
        var originalLocales: LocaleListCompat? = null
        var originalNight: Int? = null
        var originalSettingNight: NightMode? = null
        var failure: Throwable? = null
        try {
            check(targetContext.packageName.endsWith(".recentqa")) { "Requires isolated .recentqa applicationId" }
            onMain {
                originalNight = AppCompatDelegate.getDefaultNightMode()
                originalSettingNight = Settings.NIGHT_MODE.valueCompat
                // AppActivity deliberately overrides AppCompat's default using its own setting.
                // Change only this disposable QA package, then restore it during cleanup.
                Settings.NIGHT_MODE.putValue(if (night) NightMode.ON else NightMode.OFF)
                originalLocales = if (Build.VERSION.SDK_INT >= 33) {
                    LocaleListCompat.wrap(targetContext.getSystemService(LocaleManager::class.java).applicationLocales)
                } else AppCompatDelegate.getApplicationLocales()
                setLocales(LocaleListCompat.forLanguageTags(locale))
                AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            fun open(fontScale: Float): Activity {
                LanShareDesignConfig.fontScale = fontScale
                val screen = startActivitySync(Intent(targetContext, LanShareDesignActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                screens += screen
                await { screen.hasWindowFocus() && screen.findViewById<View>(R.id.root).width > 0 }
                onMain {
                    check((screen.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) { "Requested QA theme was not applied" }
                }
                return screen
            }
            onMain { targetContext.stopService(Intent(targetContext, LanShareService::class.java)) }
            await { LanShareService.state.value?.let { it.url == null && !it.starting } == true }
            val normal = open(1f)
            await {
                LanShareService.state.value?.let { it.url != null && it.global } == true &&
                    normal.findViewById<ImageView>(R.id.qr_code).drawable != null
            }
            capture(normal, "global", outputs)
            capture(normal, "global-360dp", outputs, narrow = true)
            val largeGlobal = open(1.5f)
            await { largeGlobal.findViewById<ImageView>(R.id.qr_code).drawable != null }
            capture(largeGlobal, "global-360dp-large-font", outputs, narrow = true)
            onMain { check(largeGlobal.findViewById<View>(R.id.stop).performClick()) }
            await { LanShareService.state.value?.let { it.url == null && !it.starting } == true }
            waitForIdleSync()
            Thread.sleep(250)
            onMain { check(LanShareService.state.value?.url == null) { "Stop immediately restarted sharing" } }
            capture(largeGlobal, "stopped-360dp-large-font", outputs, narrow = true)
            val monitor = addMonitor(LanShareDesignActivity::class.java.name, null, false)
            val stopped = try {
                LanShareDesignConfig.fontScale = 1f
                onMain { largeGlobal.recreate() }
                checkNotNull(monitor.waitForActivityWithTimeout(15_000)) { "Stopped activity did not recreate" }
            } finally { removeMonitor(monitor) }
            screens += stopped
            await { stopped.hasWindowFocus() && stopped.findViewById<View>(R.id.start_global).isShown }
            waitForIdleSync()
            onMain { check(LanShareService.state.value?.url == null) { "Recreating a stopped hub restarted global sharing" } }
            capture(stopped, "stopped", outputs)
            capture(stopped, "stopped-360dp", outputs, narrow = true)
            fixtures = File(targetContext.cacheDir, "lan-ui-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
            val first = File(fixtures, "行程安排.txt").apply { writeText("Generated visual QA fixture") }
            val second = File(fixtures, "待办清单.txt").apply { writeText("Generated visual QA fixture") }
            onMain {
                ContextCompat.startForegroundService(targetContext, Intent(targetContext, LanShareService::class.java)
                    .setAction(LanShareService.ACTION_START).apply {
                        extraPathList = listOf(Paths.get(first.absolutePath), Paths.get(second.absolutePath))
                        putExtra(LanShareService.EXTRA_CLIPBOARD, "这是用于界面验收的示例文本。\n其他设备可在网页查看这些内容。")
                    })
            }
            await {
                LanShareService.state.value?.let { it.url != null && !it.global && it.names.size == 2 } == true &&
                    stopped.findViewById<ImageView>(R.id.qr_code).drawable != null
            }
            val scopedUrl = onMain { LanShareService.state.value!!.url }
            capture(stopped, "running", outputs)
            capture(stopped, "running-360dp", outputs, narrow = true)
            val large = open(1.5f)
            await { large.findViewById<ImageView>(R.id.qr_code).drawable != null }
            onMain { check(LanShareService.state.value?.let { !it.global && it.url == scopedUrl } == true) {
                "Opening the hub widened an existing selected-content share"
            } }
            capture(large, "running-360dp-large-font", outputs, narrow = true)
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            if (targetContext.packageName.endsWith(".recentqa")) {
                cleanup { onMain { targetContext.stopService(Intent(targetContext, LanShareService::class.java)) } }
                screens.reversed().forEach { screen -> cleanup { onMain { screen.finish() } } }
                cleanup { onMain {
                    originalLocales?.let(::setLocales)
                    originalSettingNight?.let(Settings.NIGHT_MODE::putValue)
                    originalNight?.let(AppCompatDelegate::setDefaultNightMode)
                    LanShareDesignConfig.fontScale = 1f
                } }
                cleanup { fixtures?.deleteRecursively() }
            }
        }
        val error = failure
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (error == null) "PASS: auto-global native state render (no global-root content reads); actual Stop; stopped recreation stays stopped; selected-fixture scope preserved; secure narrow/font1.5 captures.\n${outputs.joinToString("\n")}\n"
                else "FAIL: ${error.stackTraceToString()}\n${outputs.joinToString("\n")}\n")
        })
    }

    private fun setLocales(locales: LocaleListCompat) {
        if (Build.VERSION.SDK_INT >= 33) {
            targetContext.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(locales.toLanguageTags())
        } else AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun capture(screen: Activity, name: String, outputs: MutableList<String>, narrow: Boolean = false) {
        waitForIdleSync()
        val file = File(targetContext.cacheDir, "qa-lan-${if (night) "dark" else "light"}-$name.png")
        onMain {
            val root = screen.findViewById<View>(R.id.root)
            val width = root.width
            val height = root.height
            val left = root.left
            val top = root.top
            val targetWidth = if (narrow) (360 * screen.resources.displayMetrics.density).toInt() else width
            val targetHeight = if (narrow) (640 * screen.resources.displayMetrics.density).toInt() else height
            fun layout(w: Int, h: Int) {
                fun forceTree(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) for (index in 0 until view.childCount) forceTree(view.getChildAt(index))
                }
                repeat(3) {
                    // Flush both visibility changes and the responsive column's deferred
                    // width update before drawing; an idle Looper can precede the next vsync.
                    forceTree(root)
                    root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    root.layout(left, top, left + w, top + h)
                }
            }
            try {
                layout(targetWidth, targetHeight)
                val scroll = screen.findViewById<View>(R.id.share_scroll)
                val column = screen.findViewById<View>(R.id.content_column)
                check(column.width > 0 && column.left >= scroll.paddingLeft &&
                    column.right <= scroll.width - scroll.paddingRight) { "Content column escaped horizontal viewport" }
                val running = LanShareService.state.value?.url != null
                val primary = screen.findViewById<View>(if (running) R.id.send_block else R.id.start_global)
                check(primary.width > 0 && primary.height > 0) { "Visible actions were captured before their layout pass" }
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    val background = TypedValue()
                    screen.theme.resolveAttribute(android.R.attr.colorBackground, background, true)
                    canvas.drawColor(background.data)
                    root.draw(canvas)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
                if (running) {
                    for (id in listOf(R.id.copy_link, R.id.send_link)) {
                        val button = screen.findViewById<View>(id)
                        val bounds = Rect()
                        button.getDrawingRect(bounds)
                        (root as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
                        check(button.isShown && bounds.top >= root.paddingTop && bounds.bottom <= targetHeight - root.paddingBottom) {
                            "Primary sharing action is below the viewport: $bounds in ${targetWidth}x$targetHeight; render=$file"
                        }
                    }
                }
            } finally { if (narrow) layout(width, height) }
        }
        outputs += file.absolutePath
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        error("LAN design screen did not become ready")
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
