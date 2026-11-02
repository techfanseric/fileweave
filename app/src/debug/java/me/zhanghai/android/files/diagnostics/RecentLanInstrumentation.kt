package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.Toolbar
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.filelist.RecentFileRules
import me.zhanghai.android.files.filelist.RecentFilesSettings
import me.zhanghai.android.files.lanshare.LanShareActivity
import me.zhanghai.android.files.lanshare.LanShareService
import me.zhanghai.android.files.lanshare.LanShareManualAccess
import me.zhanghai.android.files.util.SimpleActivityLifecycleCallbacks
import me.zhanghai.android.files.vault.VaultSession
import me.zhanghai.android.files.util.extraPathList
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/** QA-package-only UI/network smoke; all shared bytes come from generated cache fixtures. */
class RecentLanInstrumentation : Instrumentation() {
    private var holdBrowserPreview = false
    override fun onCreate(arguments: Bundle?) {
        holdBrowserPreview = arguments?.getString("hold_browser_preview")?.toBoolean() ?: false
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val screens = mutableListOf<Activity>()
        var fixture: File? = null
        var originalRules: RecentFileRules? = null
        var clipboardWritten = false
        var held: Socket? = null
        var generatedProviderUri: Uri? = null
        var globalProviderUri: Uri? = null
        var globalFixtureDirectory: File? = null
        var failure: Throwable? = null
        try {
            check(targetContext.packageName.endsWith(".recentqa")) {
                "This smoke check requires the isolated .recentqa applicationId"
            }
            fixture = File(targetContext.cacheDir, "recent-lan-${java.util.UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
            val selected = File(fixture, "Selected").apply { check(mkdir()) }
            File(selected, "sample.txt").writeText("0123456789")
            val png = ByteArrayOutputStream().use { out ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                    out.toByteArray()
                } finally { bitmap.recycle() }
            }
            File(selected, "photo.png").writeBytes(png)
            val replacement = File(fixture, "replacement.txt").apply { writeText("replacement fixture") }
            originalRules = RecentFilesSettings.read()
            onMain {
                val rules = RecentFileRules(RecentFileRules.Mode.EXCLUDE, listOf(selected.absolutePath))
                RecentFilesSettings.write(rules)
                val restored = RecentFilesSettings.read()
                check(restored == rules)
                check(!restored.includes(File(selected, "child/new.txt").absolutePath))
                check(restored.includes("${selected.absolutePath}-other/file.txt"))
                RecentFilesSettings.write(RecentFileRules(RecentFileRules.Mode.ONLY, listOf(selected.absolutePath)))
                check(RecentFilesSettings.read().includes(File(selected, "photo.png").absolutePath))
                check(!RecentFilesSettings.read().includes(replacement.absolutePath))
                VaultSession.observeBackground()
            }
            fun openHub(): LanShareActivity {
                val activity = startActivitySync(Intent(targetContext, LanShareActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)) as LanShareActivity
                screens += activity
                await("focused global hub") { activity.hasWindowFocus() }
                return activity
            }
            val globalFolderName = "FileWeaveLanGlobalQA-${java.util.UUID.randomUUID()}"
            val globalDirectory = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), globalFolderName).canonicalFile
            globalFixtureDirectory = globalDirectory
            val globalName = "generated-global-file.txt"
            val globalCollection = if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else MediaStore.Files.getContentUri("external")
            val globalValues = ContentValues().apply {
                put(OpenableColumns.DISPLAY_NAME, globalName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/$globalFolderName/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    check(globalDirectory.mkdirs())
                    @Suppress("DEPRECATION")
                    put(MediaStore.MediaColumns.DATA, File(globalDirectory, globalName).absolutePath)
                }
            }
            val globalUri = checkNotNull(targetContext.contentResolver.insert(globalCollection, globalValues))
            globalProviderUri = globalUri
            checkNotNull(targetContext.contentResolver.openOutputStream(globalUri, "wt")).use { it.write("0123456789".toByteArray()) }
            if (android.os.Build.VERSION.SDK_INT >= 29) targetContext.contentResolver.update(globalUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val hub = openHub()
            await("default global share") { LanShareService.state.value?.let { it.global && it.url != null } == true }
            val globalState = state()
            val globalUrl = checkNotNull(globalState.url)
            check(globalState.clipboardText == null && globalState.accessCode != null && globalState.directAccessUrl != null)
            check(globalState.accessCodeExpiresAt == Long.MAX_VALUE)
            // The real root response stays in memory: never print or capture phone directory names.
            val globalIndex = request(globalUrl)
            check(globalIndex.code == 200 && globalIndex.text.contains(android.os.Environment.DIRECTORY_DOWNLOADS))
            val globalRelative = "${android.os.Environment.DIRECTORY_DOWNLOADS}/$globalFolderName/$globalName"
            val globalRange = request(globalUrl + globalRelative, headers = "Range: bytes=3-5\r\n")
            check(globalRange.code == 206 && globalRange.text == "345")
            for (path in listOf("Android/data/", "Android/obb/", "Android/DATA/")) {
                check(request(globalUrl + path).code in setOf(403, 404)) { "Protected app-storage path was exposed" }
            }
            for (path in listOf("%2e%2e/data/", "Download%2f../", "%2fdata/")) {
                check(request(globalUrl + path).code in setOf(400, 403, 404)) { "Global path escaped its root" }
            }
            check(request(checkNotNull(globalState.directAccessUrl)).code == 303)
            shell("input keyevent 223")
            try {
                await("screen off") { !targetContext.getSystemService(PowerManager::class.java).isInteractive }
                check(state().url == globalUrl && state().global)
                check(request(globalUrl + globalRelative).text == "0123456789") { "Ordinary sharing stopped on screen off" }
            } finally { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
            await("hub focus after screen on") { hub.hasWindowFocus() }
            onMain { check(hub.findViewById<View>(R.id.stop).performClick()) }
            await("global explicit stop") { LanShareService.state.value?.url == null }
            checkUnavailable(globalUrl)
            val application = targetContext.applicationContext as Application
            var recreated: LanShareActivity? = null
            val taskId = hub.taskId
            val callbacks = object : SimpleActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (activity is LanShareActivity && activity.taskId == taskId) {
                        recreated = activity
                        screens += activity
                    }
                }
            }
            onMain { application.registerActivityLifecycleCallbacks(callbacks); hub.recreate() }
            try { await("stopped hub recreation") { recreated?.hasWindowFocus() == true } }
            finally { onMain { application.unregisterActivityLifecycleCallbacks(callbacks) } }
            check(state().url == null && !state().starting) { "A stopped hub automatically restarted sharing" }
            val stoppedHub = checkNotNull(recreated)
            onMain { check(stoppedHub.findViewById<View>(R.id.start_global).performClick()) }
            await("explicit global restart") { LanShareService.state.value?.let { it.global && it.url != null } == true }
            val vaultGuardedUrl = checkNotNull(state().url)
            check(vaultGuardedUrl != globalUrl)
            val qaVault = File(fixture, "generated-vault-safety")
            VaultSession.unlock(qaVault, "generated-only-vault-password".toByteArray(), true)
            check(state().url == vaultGuardedUrl) { "Opening a new vault revoked an unrelated ordinary share" }
            VaultSession.lock()
            await("vault lock revocation") { LanShareService.state.value?.url == null }
            checkUnavailable(vaultGuardedUrl)
            fun openShare(file: File): LanShareActivity {
                val intent = Intent(targetContext, LanShareActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .apply { extraPathList = listOf(Paths.get(file.absolutePath)) }
                val activity = startActivitySync(intent) as LanShareActivity
                screens += activity
                await("focused share activity") { activity.hasWindowFocus() }
                return activity
            }
            openShare(selected)
            await("LAN share startup") { LanShareService.state.value?.url != null }
            val oldUrl = state().url!!
            check(!state().global)
            val first = openHub()
            check(state().url == oldUrl && !state().global) { "Opening the hub widened an existing selected-file share" }
            check(request(oldUrl).text.contains("Selected"))
            check(request(oldUrl + "Selected/").text.contains("photo.png?preview=1"))
            val path = oldUrl + "Selected/sample.txt"
            val range = request(path, headers = "Range: bytes=3-5\r\n")
            check(range.code == 206 && range.text == "345")
            check(range.headers.contains("Content-Range: bytes 3-5/10"))
            val head = request(path, method = "HEAD")
            check(head.code == 200 && head.body.isEmpty())
            check(head.headers.contains("Content-Length: 10"))
            val preview = request(oldUrl + "Selected/photo.png?preview=1")
            check(preview.code == 200 && preview.text.contains("photo.png?inline=1"))
            val image = request(oldUrl + "Selected/photo.png?inline=1")
            check(image.code == 200 && image.headers.contains("Content-Type: image/png"))
            check(image.body.contentEquals(png))
            val wrong = URI(oldUrl).resolve("/wrong-token/?clipboard=1").toString()
            check(request(wrong).code == 404)
            check(request(oldUrl + "?clipboard=1").text.contains("尚未分享剪贴板"))
            val snapshot = "Generated clipboard </textarea> & test"
            onMain {
                targetContext.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Generated QA fixture", snapshot))
                clipboardWritten = true
            }
            chooseMore(first, R.string.fw_lan_append_text)
            await("explicit clipboard share") { LanShareService.state.value?.clipboardText == snapshot }
            val clipboard = request(oldUrl + "?clipboard=1")
            check(clipboard.code == 200 && clipboard.text.contains("Generated clipboard &lt;/textarea&gt; &amp; test"))
            check(!clipboard.text.contains(snapshot))
            chooseMore(first, R.string.fw_lan_clear_text)
            await("clipboard clear") { LanShareService.state.value?.clipboardText == null }
            check(!request(oldUrl + "?clipboard=1").text.contains("Generated clipboard"))
            held = Socket().apply {
                connect(InetSocketAddress("127.0.0.1", URI(oldUrl).port), 3000)
                soTimeout = 3000
                getOutputStream().write("GET ".toByteArray())
                getOutputStream().flush()
            }
            val second = openShare(replacement)
            await("replacement share") { LanShareService.state.value?.url?.let { it != oldUrl } == true }
            val newUrl = state().url!!
            check(state().clipboardText == null)
            check(request(newUrl + "replacement.txt").text == "replacement fixture")
            checkUnavailable(oldUrl)
            val oldSocket = checkNotNull(held)
            check(runCatching { oldSocket.getInputStream().read() }.getOrDefault(-1) == -1) { "Old socket remained open" }
            oldSocket.close(); held = null
            onMain { check(second.findViewById<View>(R.id.stop).performClick()) }
            await("stop button revocation") { LanShareService.state.value?.let { it.url == null && it.names.isEmpty() && it.clipboardText == null } == true }
            checkUnavailable(newUrl)
            // A stopped hub can create a text-only share without selecting any files.
            val standaloneText = "Generated standalone clipboard <only text>"
            onMain {
                targetContext.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Standalone QA fixture", standaloneText))
            }
            chooseScope(second, R.string.fw_lan_only_clipboard_text)
            await("standalone clipboard share") {
                LanShareService.state.value?.let { !it.global && it.url != null && it.names.isEmpty() && it.clipboardText == standaloneText } == true
            }
            val standalone = state()
            val standaloneUrl = checkNotNull(standalone.url)
            check(URI(standaloneUrl).path.trim('/').matches(Regex("[A-Za-z0-9_-]{22}")))
            val landing = request(standaloneUrl)
            check(landing.text.contains("Generated standalone clipboard &lt;only text&gt;"))
            check(!landing.text.contains("Empty folder"))
            val address = checkNotNull(standalone.accessAddress)
            val code = checkNotNull(standalone.accessCode)
            val form = request(address)
            check(form.code == 200 && form.text.contains("method=post"))
            check(!form.text.contains(standaloneText) && !form.text.contains(standaloneUrl))
            val denied = request(address, method = "POST", headers = "Content-Type: application/x-www-form-urlencoded\r\n", body = "code=invalid")
            check(denied.code == 403 && !denied.headers.contains("Location:"))
            val accepted = request(address, method = "POST", headers = "Content-Type: application/x-www-form-urlencoded\r\n", body = "code=$code")
            check(accepted.code == 303 && accepted.headers.contains("Location: ${URI(standaloneUrl).path}"))
            val directUrl = checkNotNull(standalone.directAccessUrl)
            check(directUrl == "$address?code=$code")
            val directHead = request(directUrl, method = "HEAD")
            check(directHead.code == 200 && directHead.body.isEmpty() && !directHead.headers.contains("Location:"))
            val directAccepted = request(directUrl)
            check(directAccepted.code == 303 && directAccepted.headers.contains("Location: ${URI(standaloneUrl).path}"))
            onMain {
                val copy = second.findViewById<View>(R.id.copy_link)
                check(copy.isShown && copy.isEnabled && copy.performClick())
                check(targetContext.getSystemService(ClipboardManager::class.java).primaryClip
                    ?.getItemAt(0)?.text?.toString() == directUrl) { "Copy link did not use the current direct access URL" }
            }
            check(state().clipboardText == standaloneText) { "Copying the link changed the shared text snapshot" }
            await("visible QR bitmap") { second.findViewById<ImageView>(R.id.qr_code).drawable is BitmapDrawable }
            val bitmap = onMain { (second.findViewById<ImageView>(R.id.qr_code).drawable as BitmapDrawable).bitmap }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val scanned = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
            check(scanned == standaloneUrl) { "Displayed QR did not contain the current full capability URL" }
            onMain {
                check(second.findViewById<View>(R.id.copy_link).isEnabled)
                check(second.findViewById<View>(R.id.send_link).isEnabled)
            }
            check(state().accessCodeExpiresAt == Long.MAX_VALUE)
            var clock = 0L
            val lifetime = LanShareManualAccess("314159") { clock }
            clock = TimeUnit.MINUTES.toNanos(11)
            check(lifetime.authorize("314159") == LanShareManualAccess.Result.GRANTED)
            clock = TimeUnit.DAYS.toNanos(3)
            check(lifetime.authorize("314159") == LanShareManualAccess.Result.GRANTED)
            onMain { check(second.findViewById<View>(R.id.stop).performClick()) }
            await("standalone stop") { LanShareService.state.value?.url == null }
            checkUnavailable(standaloneUrl)
            // An exported text share is reviewed before it opens any server.
            val incoming = startActivitySync(Intent(targetContext, me.zhanghai.android.files.lanshare.LanShareReceiveActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Generated received text")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            screens += incoming
            await("received text review") { incoming.hasWindowFocus() }
            check(state().url == null)
            onMain { check(incoming.findViewById<View>(R.id.start_incoming).performClick()) }
            await("confirmed received text") { LanShareService.state.value?.clipboardText == "Generated received text" && LanShareService.state.value?.url != null }
            val incomingUrl = checkNotNull(state().url)
            check(request(incomingUrl).text.contains("Generated received text"))
            onMain { check(incoming.findViewById<View>(R.id.stop).performClick()) }
            await("received text stop") { LanShareService.state.value?.url == null }
            checkUnavailable(incomingUrl)
            // A real foreign provider supplies only a newly inserted generated Downloads row.
            val providerName = "fileweave-generated-${java.util.UUID.randomUUID()}.txt"
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                else MediaStore.Files.getContentUri("external")
            val values = ContentValues().apply {
                put(OpenableColumns.DISPLAY_NAME, providerName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val generatedUri = checkNotNull(targetContext.contentResolver.insert(collection, values))
            generatedProviderUri = generatedUri
            checkNotNull(targetContext.contentResolver.openOutputStream(generatedUri, "wt")).use { it.write("0123456789".toByteArray()) }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                targetContext.contentResolver.update(generatedUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            onMain {
                targetContext.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newUri(targetContext.contentResolver, "Generated file", generatedUri))
            }
            val fileReceiver = startActivitySync(Intent(targetContext, me.zhanghai.android.files.lanshare.LanShareReceiveActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, generatedUri)
                .apply { clipData = ClipData.newUri(targetContext.contentResolver, "Generated file", generatedUri) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            screens += fileReceiver
            await("received file review") { fileReceiver.hasWindowFocus() && fileReceiver.findViewById<View>(R.id.start_incoming).isEnabled }
            check(state().url == null)
            onMain {
                check(fileReceiver.findViewById<android.widget.TextView>(R.id.incoming_preview).text.contains(providerName))
                check(fileReceiver.findViewById<View>(R.id.start_incoming).performClick())
            }
            await("URI snapshot share") { LanShareService.state.value?.url != null && LanShareService.state.value?.names == listOf(providerName) }
            val importedUrl = checkNotNull(state().url)
            val stagedCache = targetContext.cacheDir.listFiles()?.singleOrNull { it.name.startsWith("lan-share-") && it.isDirectory }
                ?: error("Expected one per-share staging cache")
            checkNotNull(targetContext.contentResolver.openOutputStream(generatedUri, "wt")).use { it.write("changed source".toByteArray()) }
            onMain { check(fileReceiver.findViewById<View>(R.id.copy_link).performClick()) }
            val copied = request(importedUrl + providerName, headers = "Range: bytes=3-5\r\n")
            check(copied.code == 206 && copied.text == "345") { "Provider snapshot changed with its source" }
            onMain { check(fileReceiver.findViewById<View>(R.id.stop).performClick()) }
            await("URI share stop") { LanShareService.state.value?.url == null }
            check(!stagedCache.exists()) { "URI snapshot cache survived stop" }
            checkUnavailable(importedUrl)
            if (holdBrowserPreview) {
                val browserDirectory = File(fixture, "Browser Preview").apply { check(mkdir()) }
                File(browserDirectory, "sample.png").writeBytes(png)
                File(browserDirectory, "notes.txt").writeText("Generated browser preview file.\nOnly generated data is shared.")
                openShare(browserDirectory)
                await("browser-only fixture share") { LanShareService.state.value?.let { !it.global && it.url != null } == true }
                val browserUrl = checkNotNull(state().url)
                onMain {
                    targetContext.startService(Intent(targetContext, LanShareService::class.java)
                        .setAction(LanShareService.ACTION_CLIPBOARD)
                        .putExtra(LanShareService.EXTRA_EXPECTED_URL, browserUrl)
                        .putExtra(LanShareService.EXTRA_CLIPBOARD, "Generated clipboard preview.\n只含本轮生成的示例内容。"))
                }
                await("browser fixture clipboard") { LanShareService.state.value?.clipboardText?.startsWith("Generated clipboard preview") == true }
                val done = File(targetContext.cacheDir, "qa-lan-browser-finish").apply { delete() }
                File(targetContext.cacheDir, "qa-lan-browser-preview.json").writeText(org.json.JSONObject().apply {
                    put("url", browserUrl)
                    put("port", URI(browserUrl).port)
                    put("path", URI(browserUrl).rawPath)
                    put("folder", "Browser%20Preview/")
                }.toString())
                val deadline = SystemClock.uptimeMillis() + TimeUnit.MINUTES.toMillis(15)
                while (!done.exists() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
                check(done.exists()) { "Browser preview timed out awaiting explicit completion" }
                done.delete()
                File(targetContext.cacheDir, "qa-lan-browser-preview.json").delete()
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun cleanup(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure?.addSuppressed(error)
                }
            }
            cleanup { held?.close() }
            if (targetContext.packageName.endsWith(".recentqa")) {
                cleanup { shell("input keyevent 224"); shell("wm dismiss-keyguard") }
                cleanup { VaultSession.lock() }
                cleanup { onMain { targetContext.stopService(Intent(targetContext, LanShareService::class.java)) } }
                screens.reversed().forEach { screen -> cleanup { onMain { screen.finish() } } }
                originalRules?.let { rules -> cleanup { onMain { RecentFilesSettings.write(rules) } } }
                cleanup {
                    File(targetContext.cacheDir, "qa-lan-browser-preview.json").delete()
                    File(targetContext.cacheDir, "qa-lan-browser-finish").delete()
                }
                if (clipboardWritten) cleanup {
                    onMain {
                        val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                        else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            }
            generatedProviderUri?.let { uri -> cleanup { check(targetContext.contentResolver.delete(uri, null, null) == 1) { "Generated Downloads fixture was not removed" } } }
            globalProviderUri?.let { uri -> cleanup { check(targetContext.contentResolver.delete(uri, null, null) == 1) { "Generated global-share fixture was not removed" } } }
            globalFixtureDirectory?.let { directory -> cleanup {
                directory.deleteRecursively()
                @Suppress("DEPRECATION")
                val directoryIds = targetContext.contentResolver.query(MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(directory.absolutePath), null)
                    ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()
                for (id in directoryIds) targetContext.contentResolver.delete(
                    android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id), null, null)
            } }
            cleanup { fixture?.deleteRecursively() }
        }
        val error = failure
        finish(if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (error == null)
                "PASS: default hub starts global read-only storage with code/QR and no clipboard capture; root index checked only in memory; generated shared-storage file Range and protected/path-escape denial; ordinary screen-off sharing continues; explicit stop survives Activity recreation, explicit restart changes token, independent QA vault lock revokes; opening hub preserves selected scope; actual append/clear and text-only popup actions; selected folder index, image payload, Range/HEAD, token denial; replacement/stop revokes old sockets; direct GET/POST same-token login and no HEAD credential leak; actual Copy and decoded strong QR; injected monotonic 11min/3day code lifetime remains valid; exported text/file review and provider snapshots; exact generated files/cache cleaned.\n"
            else "FAIL: ${error.stackTraceToString()}\n")
        })
    }

    /** Keep assertion failures off the main Looper so cleanup and result reporting still run. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun chooseMore(activity: Activity, label: Int) {
        onMain { check(activity.findViewById<Toolbar>(R.id.toolbar).showOverflowMenu()) }
        clickPopupText(activity.getString(label))
    }

    private fun chooseScope(activity: Activity, label: Int) {
        onMain { check(activity.findViewById<View>(R.id.change_content).performClick()) }
        clickPopupText(activity.getString(label))
    }

    private fun clickPopupText(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val match = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            if (match != null) {
                var clickable: AccessibilityNodeInfo? = match
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            }
            Thread.sleep(50)
        }
        error("LAN popup action unavailable: $label")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private data class Response(val code: Int, val headers: String, val body: ByteArray) {
        val text get() = body.toString(Charsets.UTF_8)
    }
    private fun request(url: String, method: String = "GET", headers: String = "", body: String? = null): Response {
        val uri = URI(url)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", uri.port), 3000)
            socket.soTimeout = 3000
            val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            val contentLength = body?.let { "Content-Length: ${it.toByteArray().size}\r\n" } ?: ""
            socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: localhost\r\n${headers}${contentLength}Connection: close\r\n\r\n${body ?: ""}".toByteArray())
            socket.getOutputStream().flush()
            val bytes = socket.getInputStream().readBytes()
            val split = (0 until bytes.size - 3).firstOrNull {
                bytes[it] == 13.toByte() && bytes[it + 1] == 10.toByte() && bytes[it + 2] == 13.toByte() && bytes[it + 3] == 10.toByte()
            } ?: error("HTTP response had no header boundary")
            val responseHeaders = bytes.copyOfRange(0, split).toString(Charsets.UTF_8)
            Response(responseHeaders.substringBefore("\r\n").split(' ')[1].toInt(), responseHeaders, bytes.copyOfRange(split + 4, bytes.size))
        }
    }
    private fun checkUnavailable(url: String) {
        val response = try { request(url) } catch (_: java.io.IOException) { return }
        check(response.code == 404 || response.code == 410) { "Revoked link still served ${response.code}" }
    }
    private fun state(): LanShareService.State {
        var value: LanShareService.State? = null
        onMain { value = LanShareService.state.value }
        return checkNotNull(value)
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            Thread.sleep(50)
        }
        error("Timed out: $label; state=${state()}")
    }
}
