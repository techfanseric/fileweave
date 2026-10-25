package me.zhanghai.android.files.diagnostics

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import me.zhanghai.android.files.bridge.ShareToMacActivity
import me.zhanghai.android.files.bridge.SendClipboardActivity
import me.zhanghai.android.files.folderbridge.TranscriptStore
import java.io.File
import java.security.MessageDigest

/** Generated fixture only: no clipboard reads, pairing changes or private transcript output. */
class QuickShareInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val path = "fileweave-qa-${java.util.UUID.randomUUID()}.m4a"
        try {
            for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)) {
                val intent = Intent(action).setType("text/plain").setPackage(targetContext.packageName)
                @Suppress("DEPRECATION")
                val matches = targetContext.packageManager.queryIntentActivities(intent, 0)
                check(matches.any { it.activityInfo.name == ShareToMacActivity::class.java.name })
            }
            @Suppress("DEPRECATION")
            val privateEntry = targetContext.packageManager.getActivityInfo(
                android.content.ComponentName(targetContext, SendClipboardActivity::class.java), 0)
            check(!privateEntry.exported)
            TranscriptStore.save(targetContext, path, "生成的测试文本")
            check(TranscriptStore.list(targetContext).single { it.first == path }.second == "生成的测试文本")
            TranscriptStore.save(targetContext, path, "替换后的测试文本")
            check(TranscriptStore.list(targetContext).single { it.first == path }.second == "替换后的测试文本")
            me.zhanghai.android.files.folderbridge.FolderBridgeSettings.getTreeUri(targetContext)
            verifyScopedFiles(targetContext)
            runOnMainSync {
                val switches = me.zhanghai.android.files.navigation.navigationItems.filterNotNull()
                    .filter { it.switchChecked != null }
                check(switches.size == 4)
            }
            finish(0, Bundle().apply { putString("result", "PASS: share targets, private clipboard entry, transcript persistence/idempotence, four direct sharing switches, real scoped file range/symlink/revocation checks") })
        } catch (error: Throwable) {
            finish(1, Bundle().apply { putString("result", "FAIL: ${error.stackTraceToString()}") })
        } finally {
            val name = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
            File(targetContext.filesDir, "transcripts/$name.json").delete()
        }
    }
}

internal fun verifyScopedFiles(context: android.content.Context) {
    val fixture = File(context.cacheDir, "share-qa-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    try {
        val selected = File(fixture, "Selected").apply { mkdirs() }
        File(selected, "sample.txt").writeText("0123456789")
        val secret = File(fixture, "outside.txt").apply { writeText("not shared") }
        android.system.Os.symlink(secret.absolutePath, File(selected, "escape.txt").absolutePath)
        val backend = me.zhanghai.android.files.lanshare.LanShareBackend(listOf(java8.nio.file.Paths.get(selected.absolutePath)))
        check(backend.stat(listOf("outside.txt")) == null)
        check(backend.stat(listOf("Selected", "escape.txt")) == null)
        check(backend.list(listOf("Selected")).map { it.name } == listOf("sample.txt"))
        val kernel = me.zhanghai.android.files.lanshare.LanShareKernel("fixture", backend)
        fun read(): String {
            val out = java.io.ByteArrayOutputStream()
            kernel.handle("GET /fixture/Selected/sample.txt HTTP/1.1\r\nRange: bytes=3-5\r\n\r\n".byteInputStream(), out)
            return out.toString("UTF-8")
        }
        check(read().endsWith("345"))
        val server = me.zhanghai.android.files.webdavserver.WebDavServer(0, kernel::handle)
        try {
            server.start()
            java.net.Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /fixture/Selected/sample.txt HTTP/1.1\r\nRange: bytes=3-5\r\n\r\n".toByteArray())
                check(socket.getInputStream().readBytes().toString(Charsets.UTF_8).endsWith("345"))
            }
        } finally { server.stop() }
        kernel.active = false
        check(read().startsWith("HTTP/1.1 410"))
    } finally { fixture.deleteRecursively() }
}
