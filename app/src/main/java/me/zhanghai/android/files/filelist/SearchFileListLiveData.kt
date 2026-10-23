/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import java8.nio.file.Path
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.provider.common.search
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

class SearchFileListLiveData(
    private val path: Path,
    private val query: String,
    private val filters: SearchFilters = SearchFilters()
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        loadValue()
    }

    fun loadValue() {
        val currentGeneration = generation.incrementAndGet()
        fun publish(state: Stateful<List<FileItem>>) {
            handler.post { if (generation.get() == currentGeneration) value = state }
        }
        future?.cancel(true)
        value = Loading(emptyList())
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val fileList = mutableListOf<FileItem>()
            try {
                val zone = java.time.ZoneId.systemDefault()
                val consume: (List<Path>) -> Unit = { paths ->
                    for (path in paths) {
                        if (Thread.currentThread().isInterrupted || generation.get() != currentGeneration) throw java.io.InterruptedIOException()
                        val fileItem = try {
                            path.loadFileItem()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            // TODO: Support file without information.
                            continue
                        }
                        val attributes = fileItem.attributes
                        val created = if ((attributes as? me.zhanghai.android.files.provider.common.KnownCreationTime)
                                ?.hasCreationTime == true) attributes.creationTime().toMillis() else null
                        if (filters.matches(fileItem.name, fileItem.mimeType.value,
                                attributes.isDirectory, attributes.lastModifiedTime().toMillis(), created, zone)) {
                            fileList.add(fileItem)
                        }
                    }
                    publish(Loading(fileList.toList()))
                }
                if (filters.recursive) {
                    path.search(query, INTERVAL_MILLIS, consume)
                } else {
                    // Enumerate only direct children: never walk descendants or rely on provider search.
                    java8.nio.file.Files.newDirectoryStream(path).use { stream ->
                        val batch = mutableListOf<Path>()
                        for (child in stream) {
                            if (Thread.currentThread().isInterrupted || generation.get() != currentGeneration) throw java.io.InterruptedIOException()
                            if (child.fileName.toString().contains(query, true)) batch.add(child)
                            if (batch.size >= 64) { consume(batch.toList()); batch.clear() }
                        }
                        if (batch.isNotEmpty()) consume(batch)
                    }
                }
                if (!Thread.currentThread().isInterrupted) publish(Success(fileList.toList()))
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                if (!Thread.currentThread().isInterrupted && e !is java.io.InterruptedIOException) {
                    publish(Failure(fileList.toList(), e))
                }
            }
        }
    }

    override fun close() {
        generation.incrementAndGet()
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
    }
}
