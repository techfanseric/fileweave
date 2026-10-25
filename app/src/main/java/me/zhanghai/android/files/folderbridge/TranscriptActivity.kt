package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object TranscriptStore {
    private fun directory(context: Context) = File(context.filesDir, "transcripts").apply { mkdirs() }

    @Synchronized fun save(context: Context, path: String, text: String) {
        val name = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = android.util.AtomicFile(File(directory(context), "$name.json"))
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("path", path).put("text", text).toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }

    @Synchronized fun list(context: Context): List<Pair<String, String>> =
        directory(context).listFiles().orEmpty().filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }.mapNotNull {
                try { JSONObject(it.readText()).let { obj -> obj.getString("path") to obj.getString("text") } }
                catch (_: Exception) { null }
            }
}

class TranscriptActivity : FeatureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { TranscriptStore.list(this@TranscriptActivity) }
            screen(getString(R.string.fw_transcripts))
            if (entries.isEmpty()) text(getString(R.string.fw_transcripts_empty))
            entries.forEach { (name, body) ->
                button(name) {
                    screen(name)
                    text(body).setTextIsSelectable(true)
                    button(getString(R.string.fw_transcripts)) { recreate() }
                }
            }
        }
    }
}
