package me.zhanghai.android.files.folderbridge

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.zhanghai.android.files.design.*
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
    private var selected by mutableStateOf<Pair<String, String>?>(null)
    private var entries by mutableStateOf<List<Pair<String, String>>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        composeScreen {
            BackHandler(selected != null) { selected = null }
            val current = selected
            FeaturePage(current?.first ?: getString(R.string.fw_transcripts), { if (current == null) finish() else selected = null }, busy = entries == null) {
                if (current != null) item { SelectionContainer { Text(current.second, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge) } }
                else if (entries?.isEmpty() == true) item { EmptyContent(getString(R.string.fw_transcripts_empty), getString(R.string.fw_redesign_transcript_hint), R.drawable.text_file_icon_white_24dp) }
                else items(entries.orEmpty(), key = { it.first }) { entry ->
                    ContentRow(entry.first.substringAfterLast('/'), entry.first, R.drawable.text_file_icon_white_24dp, onClick = { selected = entry })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { entries = withContext(Dispatchers.IO) { TranscriptStore.list(this@TranscriptActivity) } }
    }
}
