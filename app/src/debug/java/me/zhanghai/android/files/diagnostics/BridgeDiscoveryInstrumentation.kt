package me.zhanghai.android.files.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.zhanghai.android.files.bridge.BridgeActivity
import me.zhanghai.android.files.bridge.BridgeClient
import me.zhanghai.android.files.bridge.BridgeConfig
import me.zhanghai.android.files.bridge.BridgeDiscovery
import me.zhanghai.android.files.bridge.BridgeStore

/** Uses the existing approved Mac; never reads clipboard contents or alters shared files. */
class BridgeDiscoveryInstrumentation : Instrumentation() {
    private var fixture: String? = null
    override fun onCreate(arguments: Bundle?) {
        fixture = arguments?.getString("fixture")
        require(fixture == null || fixture!!.matches(Regex("fileweave-qa-[a-f0-9]{12}\\.txt")))
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        var discovery: BridgeDiscovery? = null
        var original: BridgeConfig? = null
        var activity: Activity? = null
        val result = Bundle()
        var success = false
        try {
            original = checkNotNull(BridgeStore.read(targetContext)) { "Pair a Mac before running this check" }
            val saved = original
            val endpoint = AtomicReference<BridgeDiscovery.MacEndpoint>()
            val found = CountDownLatch(1)
            runOnMainSync {
                discovery = BridgeDiscovery(targetContext, {
                    if (it.pin == saved.pin) { endpoint.set(it); found.countDown() }
                }, {}, {}).also { it.start() }
            }
            check(found.await(30, TimeUnit.SECONDS)) { "Approved Mac not discovered through Bonjour" }
            runOnMainSync { discovery?.stop(); discovery = null }
            val current = checkNotNull(endpoint.get()).config(saved.token)
            BridgeClient(current).let { client ->
                try { check(client.json("status").getInt("protocol") == 1) } finally { client.close() }
            }
            val wrong = BridgeClient(current.copy(pin = if (current.pin == "0".repeat(64)) "1".repeat(64) else "0".repeat(64)))
            try { check(runCatching { wrong.json("status") }.isFailure) { "Incorrect TLS pin accepted" } } finally { wrong.close() }
            // Simulate a remembered address that has become stale after a Wi-Fi/DHCP change.
            BridgeStore.save(targetContext, saved.copy(host = "192.0.2.1"))
            activity = startActivitySync(Intent(targetContext, BridgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = SystemClock.elapsedRealtime() + 45000
            var reconnected = false
            while (!reconnected && SystemClock.elapsedRealtime() < deadline) {
                val result = BridgeStore.read(targetContext)
                reconnected = result?.host == current.host && result.port == current.port && result.pin == saved.pin && result.token == saved.token
                Thread.sleep(200)
            }
            check(reconnected) { "Activity did not recover the stale address" }
            fixture?.let { name ->
                val screen = checkNotNull(activity)
                awaitText(screen) { texts -> texts.any { it.startsWith("已连接") || it.startsWith("Connected to") } }
                val forbidden = setOf("检查连接 / 刷新", "重新发现附近 Mac", "忘记此 Mac", "使用二维码或配对链接",
                    "Check connection / refresh", "Search again", "Forget this Mac", "Use QR code or pairing link")
                awaitText(screen) { texts -> texts.none { it in forbidden } }
                marker("QA_ADD")
                awaitText(screen) { name in it }
                marker("QA_REMOVE")
                awaitText(screen) { name !in it }
                marker("QA_RESTART")
                awaitText(screen) { name in it }
                marker("QA_REMOVE")
                awaitText(screen) { name !in it }
            }
            success = true
            result.putString("stream", "Bonjour discovery, existing credential reconnect, changed-address recovery and incorrect-certificate rejection: PASS\n" +
                if (fixture != null) "Automatic list add/remove, Mac service crash recovery and simplified controls: PASS\n" else "")
        } catch (error: Throwable) {
            result.putString("stream", "FAILED: ${error.stackTraceToString()}\n")
        } finally {
            runOnMainSync { discovery?.stop(); activity?.finish() }
            original?.let { BridgeStore.save(targetContext, it) }
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
    private fun marker(value: String) { sendStatus(0, Bundle().apply { putString("stream", "$value\n") }) }
    private fun awaitText(activity: Activity, predicate: (List<String>) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45000
        var matched = false
        while (!matched && SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync {
                val texts = mutableListOf<String>()
                fun collect(view: android.view.View) {
                    if (view is android.widget.TextView) texts += view.text.toString()
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(activity.findViewById(android.R.id.content))
                matched = predicate(texts)
            }
            Thread.sleep(200)
        }
        check(matched) { "Automatic UI update timed out" }
    }

}
