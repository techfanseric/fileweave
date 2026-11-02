package me.zhanghai.android.files.bridge

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import org.json.JSONObject

class BridgeHistoryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.newFolder(), "history.json")
    private fun clip(text: String = "private generated preview") = BridgeHistoryStore.Entry.clipboard(false, text)

    @Test fun deletionPersistsAndKeepsOtherClipboardAndFileRecords() {
        val file = file(); val store = BridgeHistoryRepository(file)
        val first = clip("remove this"); val second = clip("keep this")
        val transfer = BridgeHistoryStore.Entry.file(true, "keep.pdf", 123)
        listOf(first, second, transfer).forEach(store::add)
        assertTrue(store.remove(first.id))
        assertFalse(file.readText().contains("remove this"))
        val reloaded = BridgeHistoryRepository(file).list()
        assertEquals(listOf(transfer.id, second.id), reloaded.map { it.id })
        assertFalse(store.remove(first.id))
        store.add(clip("a new event"))
        assertFalse(BridgeHistoryRepository(file).list().any { it.id == first.id })
    }
    @Test fun identicalOldEntriesGetDistinctStableIdsAndOnlyOneIsDeleted() {
        val file = file()
        val legacy = JSONObject().put("kind", "clipboard").put("out", false).put("preview", "same").put("time", 1000)
        file.writeText(JSONArray().put(legacy).put(legacy).toString())
        val store = BridgeHistoryRepository(file); val rows = store.list()
        assertEquals(2, rows.map { it.id }.distinct().size)
        assertEquals(rows.map { it.id }, BridgeHistoryRepository(file).list().map { it.id })
        assertTrue(store.remove(rows[0].id))
        assertEquals(listOf(rows[1].id), BridgeHistoryRepository(file).list().map { it.id })
        assertTrue(JSONArray(file.readText()).getJSONObject(0).has("id"))
    }
    @Test fun duplicateStoredIdsAreRepairedBeforeSelection() {
        val file = file()
        val legacy = JSONObject().put("kind", "clipboard").put("out", false).put("preview", "same").put("time", 1000).put("id", "duplicate")
        file.writeText(JSONArray().put(legacy).put(legacy).toString())
        val store = BridgeHistoryRepository(file); val rows = store.list()
        assertEquals(2, rows.map { it.id }.distinct().size)
        store.remove(rows[1].id)
        assertEquals(listOf(rows[0].id), BridgeHistoryRepository(file).list().map { it.id })
    }
    @Test fun failedWriteDoesNotPretendDeletionSucceededAndCanBeRetried() {
        val file = file(); val store = BridgeHistoryRepository(file); val item = clip()
        store.add(item); val before = file.readBytes()
        val blocker = File(file.parentFile, "history.json.tmp").apply { mkdir(); File(this, "blocker").writeText("fixture") }
        try { store.remove(item.id); fail("Deletion must report a failed write") } catch (_: IOException) { }
        assertEquals(listOf(item.id), store.list().map { it.id })
        assertArrayEquals(before, file.readBytes())
        blocker.deleteRecursively()
        assertTrue(store.remove(item.id))
        assertTrue(BridgeHistoryRepository(file).list().isEmpty())
    }
    @Test fun concurrentAppendCannotRestoreTheDeletedRecord() {
        val file = file(); val store = BridgeHistoryRepository(file); val target = clip()
        store.add(target)
        val start = CountDownLatch(1)
        val thread = Thread { start.await(); repeat(20) { store.add(clip("new-$it")) } }
        thread.start(); start.countDown(); store.remove(target.id); thread.join()
        val reloaded = BridgeHistoryRepository(file).list()
        assertEquals(20, reloaded.size)
        assertFalse(reloaded.any { it.id == target.id })
    }
    @Test fun previewLimitHistoryBoundAndMissingFileReadsArePreserved() {
        val file = file(); val store = BridgeHistoryRepository(file, limit = 3)
        assertTrue(store.list().isEmpty()); assertFalse(file.exists())
        repeat(5) { store.add(clip("$it".repeat(200))) }
        assertEquals(3, store.list().size)
        assertTrue(store.list().all { it.preview!!.length == 80 })
        store.clear()
        assertFalse(file.exists()); assertTrue(BridgeHistoryRepository(file).list().isEmpty())
        assertFalse(File(file.parentFile, "history.json.tmp").exists())
    }
}
