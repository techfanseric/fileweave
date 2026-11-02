package me.zhanghai.android.files.bridge

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Local-only history. Mutations publish cache changes only after a durable write. */
internal class BridgeHistoryRepository(private val file: File, private val limit: Int = 100) {
    private var cache: List<BridgeHistoryStore.Entry>? = null

    @Synchronized fun list(): List<BridgeHistoryStore.Entry> = load().toList()

    @Synchronized fun add(entry: BridgeHistoryStore.Entry) {
        val previous = load()
        val unique = if (previous.any { it.id == entry.id }) entry.copy(id = UUID.randomUUID().toString()) else entry
        val next = (listOf(unique) + previous).take(limit)
        save(next)
        cache = next
    }

    /** IDs, never row positions or previews, identify the one entry to remove. */
    @Synchronized fun remove(id: String): Boolean {
        val previous = load()
        if (previous.none { it.id == id }) return false
        val next = previous.filterNot { it.id == id }
        save(next)
        cache = next
        return true
    }

    @Synchronized fun clear() {
        if (file.exists() && !file.delete()) throw IOException("Unable to clear history")
        cache = emptyList()
    }

    private fun load(): List<BridgeHistoryStore.Entry> {
        cache?.let { return it }
        val entries = try {
            val array = JSONArray(file.readText())
            val ids = HashSet<String>()
            (0 until array.length()).mapNotNull { index ->
                val value = array.optJSONObject(index) ?: return@mapNotNull null
                try {
                    // Older entries had no ID. Include the ordinal so otherwise
                    // identical records can still be deleted independently. IDs
                    // are persisted on the next mutation, with no preview rewrite.
                    var id = value.optString("id").takeIf { it.isNotBlank() }
                        ?: legacyId(index, value, 0)
                    var collision = 0
                    while (!ids.add(id)) { collision++; id = legacyId(index, value, collision) }
                    BridgeHistoryStore.Entry(
                        kind = if (value.getString("kind") == "file") BridgeHistoryStore.Kind.FILE else BridgeHistoryStore.Kind.CLIPBOARD,
                        outgoing = value.getBoolean("out"),
                        name = value.optString("name").takeIf { it.isNotEmpty() },
                        size = if (value.has("size")) value.getLong("size") else null,
                        preview = value.optString("preview").takeIf { it.isNotEmpty() },
                        time = value.getLong("time"), id = id
                    )
                } catch (_: Exception) { null }
            }.take(limit)
        } catch (_: Exception) { emptyList() }
        cache = entries
        return entries
    }

    private fun legacyId(index: Int, value: JSONObject, collision: Int): String =
        UUID.nameUUIDFromBytes("$index:$collision:$value".toByteArray(Charsets.UTF_8)).toString()

    private fun save(entries: List<BridgeHistoryStore.Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("id", entry.id)
                .put("kind", if (entry.kind == BridgeHistoryStore.Kind.FILE) "file" else "clipboard")
                .put("out", entry.outgoing).put("time", entry.time).apply {
                    entry.name?.let { put("name", it) }
                    entry.size?.let { put("size", it) }
                    entry.preview?.let { put("preview", it) }
                })
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(array.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Unable to save history")
        } finally { temporary.delete() }
    }
}
