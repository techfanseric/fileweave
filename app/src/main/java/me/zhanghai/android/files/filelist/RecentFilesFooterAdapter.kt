package me.zhanghai.android.files.filelist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.RecentFilesFooterBinding

/** An actual final list row, so the action never overlays files or occupies a grid cell. */
internal class RecentFilesFooterAdapter(private val loadMore: () -> Unit) :
    RecyclerView.Adapter<RecentFilesFooterAdapter.Holder>() {
    private var visible = false
    private var loading = false
    private var loadingMore = false
    private var retry = false

    fun update(visible: Boolean, loading: Boolean, loadingMore: Boolean, retry: Boolean) {
        val wasVisible = this.visible
        val changed = this.loading != loading || this.loadingMore != loadingMore || this.retry != retry
        this.visible = visible
        this.loading = loading
        this.loadingMore = loadingMore
        this.retry = retry
        when {
            visible && !wasVisible -> notifyItemInserted(0)
            !visible && wasVisible -> notifyItemRemoved(0)
            visible && changed -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(RecentFilesFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.loadMoreRecentFiles.apply {
            setText(when {
                loadingMore -> R.string.fw_recent_loading_more
                retry -> R.string.fw_recent_retry_more
                else -> R.string.fw_recent_load_more
            })
            isEnabled = !loading
            setOnClickListener { loadMore() }
        }
    }

    class Holder(val binding: RecentFilesFooterBinding) : RecyclerView.ViewHolder(binding.root)
}
