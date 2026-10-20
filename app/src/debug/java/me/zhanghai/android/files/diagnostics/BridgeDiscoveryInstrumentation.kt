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
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
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
            success = true
            result.putString("stream", "Bonjour discovery, existing credential reconnect, changed-address recovery and incorrect-certificate rejection: PASS\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAILED: ${error.stackTraceToString()}\n")
        } finally {
            runOnMainSync { discovery?.stop(); activity?.finish() }
            original?.let { BridgeStore.save(targetContext, it) }
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
