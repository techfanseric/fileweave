package me.zhanghai.android.files.viewer.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.util.extraPath

class PdfViewerActivity : AppActivity() {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var destroyed = false
    private var pageIndex = 0
    private var pageCount = 0
    private var continuous = true
    private lateinit var list: RecyclerView
    private lateinit var layout: LinearLayoutManager
    private lateinit var adapter: PageAdapter
    private lateinit var status: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var modeItem: MenuItem
    private val pageHeights = mutableMapOf<Int, Int>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.extraPath ?: run { finish(); return }
        pageIndex = savedInstanceState?.getInt("page") ?: 0
        continuous = savedInstanceState?.getBoolean("continuous") ?: true
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = path.fileName.toString()
            setNavigationIcon(R.drawable.fw_back)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        modeItem = toolbar.menu.add(0, 1, 0, "").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { switchMode(); true }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        previous = Button(this).apply { setText(R.string.fw_document_previous); setOnClickListener { showPage(pageIndex - 1) } }
        next = Button(this).apply { setText(R.string.fw_document_next); setOnClickListener { showPage(pageIndex + 1) } }
        status = TextView(this).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0) }
        bar.addView(previous)
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(next)
        layout = LinearLayoutManager(this)
        adapter = PageAdapter()
        list = RecyclerView(this).apply {
            layoutManager = layout
            adapter = this@PdfViewerActivity.adapter
            setItemViewCacheSize(0)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (continuous) {
                        val first = layout.findFirstVisibleItemPosition()
                        if (first != RecyclerView.NO_POSITION) { pageIndex = first; updateControls() }
                    }
                }
            })
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        updateControls()
        executor.execute {
            try {
                val count = contentResolver.openFileDescriptor(path.fileProviderUri, "r")!!.use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
                require(count > 0)
                runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    pageCount = count
                    pageIndex = pageIndex.coerceIn(0, count - 1)
                    adapter.notifyDataSetChanged()
                    if (continuous) layout.scrollToPositionWithOffset(pageIndex, 0)
                    updateControls()
                }
            } catch (_: Exception) {
                runOnUiThread { if (!destroyed) status.setText(R.string.fw_document_error) }
            }
        }
    }

    private fun switchMode() {
        if (pageCount == 0) return
        if (continuous) {
            val first = layout.findFirstVisibleItemPosition()
            if (first != RecyclerView.NO_POSITION) pageIndex = first
        }
        continuous = !continuous
        adapter.notifyDataSetChanged()
        layout.scrollToPositionWithOffset(if (continuous) pageIndex else 0, 0)
        updateControls()
    }

    private fun showPage(index: Int) {
        if (index !in 0 until pageCount) return
        pageIndex = index
        adapter.notifyDataSetChanged()
        layout.scrollToPositionWithOffset(0, 0)
        updateControls()
    }

    private fun updateControls() {
        modeItem.setTitle(if (continuous) R.string.fw_document_single_mode else R.string.fw_document_continuous_mode)
        previous.visibility = if (continuous) View.GONE else View.VISIBLE
        next.visibility = if (continuous) View.GONE else View.VISIBLE
        previous.isEnabled = pageIndex > 0
        next.isEnabled = pageIndex + 1 < pageCount
        if (pageCount == 0) status.setText(R.string.fw_document_loading)
        else status.text = getString(R.string.fw_document_page, pageIndex + 1, pageCount)
    }

    private inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var task: Future<*>? = null
        var bitmap: Bitmap? = null
        var bindId = 0
        fun clear() {
            bindId++
            task?.cancel(true)
            task = null
            image.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = null
            executor.purge()
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount() = if (pageCount == 0) 0 else if (continuous) pageCount else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val image = ImageView(parent.context).apply {
                setBackgroundColor(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)).apply { bottomMargin = dp(8) }
            }
            return PageHolder(image)
        }
        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.clear()
            val index = if (continuous) position else pageIndex
            val id = holder.bindId
            val width = list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val params = holder.image.layoutParams
            params.height = pageHeights[index] ?: (width * 1.414f).toInt().coerceAtLeast(1)
            holder.image.layoutParams = params
            val path = intent.extraPath ?: return
            holder.task = executor.submit {
                var result: Bitmap? = null
                try {
                    if (Thread.currentThread().isInterrupted) return@submit
                    val output = contentResolver.openFileDescriptor(path.fileProviderUri, "r")!!.use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            renderer.openPage(index).use { page ->
                                val displayHeight = (width.toLong() * page.height / page.width).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                                val scale = minOf(width.toDouble() / page.width, 1536.0 / page.width, 2400.0 / page.height)
                                val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
                                val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                                result = bitmap
                                bitmap.eraseColor(Color.WHITE)
                                if (Thread.currentThread().isInterrupted) return@submit
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                displayHeight to bitmap
                            }
                        }
                    }
                    val bitmap = output.second
                    result = null
                    runOnUiThread {
                        if (destroyed || holder.bindId != id) { bitmap.recycle(); return@runOnUiThread }
                        pageHeights[index] = output.first
                        holder.image.layoutParams = holder.image.layoutParams.apply { height = output.first }
                        holder.bitmap = bitmap
                        holder.image.setImageBitmap(bitmap)
                    }
                } catch (_: Exception) {
                    runOnUiThread { if (!destroyed && holder.bindId == id) status.setText(R.string.fw_document_error) }
                } finally { result?.recycle() }
            }
        }
        override fun onViewRecycled(holder: PageHolder) { holder.clear(); super.onViewRecycled(holder) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pageIndex)
        outState.putBoolean("continuous", continuous)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        destroyed = true
        list.adapter = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
