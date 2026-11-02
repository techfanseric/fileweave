package me.zhanghai.android.files.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ViewOptionsDialogBinding

/** Display and sort are editable settings, not a chain of dismissing action menus. */
object ViewOptionsDialog {
    fun show(context: Context, owner: LifecycleOwner, menu: Menu, recent: Boolean,
             onAction: (MenuItem) -> Unit) {
        val binding = ViewOptionsDialogBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.file_list_action_view_sort)
            .setView(binding.root).setPositiveButton(android.R.string.ok, null).create()
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { dialog.dismiss() }
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        var syncing = false
        val checks = listOf(binding.viewDetails to R.id.action_show_details,
            binding.viewHidden to R.id.action_show_hidden_files,
            binding.sortAscending to R.id.action_sort_order_ascending,
            binding.sortDirectories to R.id.action_sort_directories_first,
            binding.sortScope to R.id.action_view_sort_path_specific)
        fun sync() {
            syncing = true
            checks.forEach { (view, id) ->
                val item = menu.findItem(id)
                view.isChecked = item.isChecked
                view.isEnabled = item.isEnabled
            }
            syncing = false
        }
        fun spinner(view: Spinner, ids: List<Int>) {
            val items = ids.map(menu::findItem)
            view.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items.map { it.title })
            view.setSelection(items.indexOfFirst { it.isChecked }.coerceAtLeast(0))
            view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                    if (!items[position].isChecked) { onAction(items[position]); sync() }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        spinner(binding.viewMode, listOf(R.id.action_view_list, R.id.action_view_grid,
            R.id.action_view_compact, R.id.action_view_preview))
        spinner(binding.sortField, listOf(R.id.action_sort_by_name, R.id.action_sort_by_type,
            R.id.action_sort_by_size, R.id.action_sort_by_last_modified))
        checks.forEach { (view, id) ->
            view.setOnCheckedChangeListener { _, _ ->
                if (!syncing) { onAction(menu.findItem(id)); sync() }
            }
        }
        binding.sortSection.isVisible = !recent
        binding.recentSortNote.isVisible = recent
        sync()
        dialog.show()
    }
}
