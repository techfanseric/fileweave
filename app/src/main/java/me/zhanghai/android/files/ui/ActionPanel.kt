package me.zhanghai.android.files.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ActionPanelBinding
import java.util.WeakHashMap

/** A single themed surface: back navigates within the menu before closing the dialog.
 * Menus are models only; no PopupMenu is displayed. Close on stop rather than retaining callbacks
 * or a stale file selection across a new Activity/Fragment view lifecycle.
 */
class ActionPanel(
    private val context: Context,
    private val owner: LifecycleOwner,
    title: CharSequence,
    menu: Menu,
    private val keepOpen: (MenuItem) -> Boolean = { false },
    private val isValid: () -> Boolean = { true },
    private val searchable: (Menu) -> Boolean = { false },
    private val onAction: (MenuItem) -> Unit
) : DefaultLifecycleObserver {
    private data class Page(val title: CharSequence, val menu: Menu, var y: Int = 0,
                            var focusId: Int = 0, var query: String = "")
    private val key = activityContext(context)
    private val pages = mutableListOf(Page(title, menu))
    private val dialog = BottomSheetDialog(context)
    private val binding = ActionPanelBinding.inflate(LayoutInflater.from(dialog.context))
    private var rendering = false
    override fun onStop(owner: LifecycleOwner) { dismiss() }
    override fun onDestroy(owner: LifecycleOwner) { dismiss() }
    private inner class BackCallback : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() { back() }
    }
    internal val contentView: View get() = binding.root


    init {
        dialog.setContentView(binding.root)
        binding.panelClose.setOnClickListener { dismiss() }
        binding.panelBack.setOnClickListener { back() }
        binding.panelSearch.doAfterTextChanged {
            if (!rendering) {
                pages.last().query = it.toString()
                renderItems()
            }
        }
        dialog.onBackPressedDispatcher.addCallback(BackCallback())
        dialog.setOnDismissListener {
            owner.lifecycle.removeObserver(this)
            if (active[key]?.get() === this) active.remove(key)
        }
    }

    fun show(): ActionPanel {
        if (!owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) || !isValid()) return this
        active[key]?.get()?.dismiss()
        active[key] = java.lang.ref.WeakReference(this)
        owner.lifecycle.addObserver(this)
        render()
        dialog.show()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        return this
    }

    fun dismiss() { dialog.dismiss() }

    fun push(title: CharSequence, menu: Menu) {
        savePage()
        pages += Page(title, menu)
        render()
    }

    fun returnToRoot() {
        while (pages.size > 1) pages.removeAt(pages.lastIndex)
        render()
    }

    fun refresh() {
        if (!isValid()) { dismiss(); return }
        savePage()
        render()
    }

    private fun savePage() {
        pages.last().y = binding.panelScroll.scrollY
    }

    private fun back() {
        if (pages.size == 1) { dismiss(); return }
        pages.removeAt(pages.lastIndex)
        render()
    }

    private fun render() {
        if (!isValid()) { dismiss(); return }
        val page = pages.last()
        binding.panelTitle.text = page.title
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(binding.root, page.title)
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.panelTitle, true)
        binding.panelBack.isVisible = pages.size > 1
        binding.panelBack.contentDescription = context.getString(R.string.fw_menu_back, pages.getOrNull(pages.lastIndex - 1)?.title ?: "")
        rendering = true
        binding.panelSearch.isVisible = searchable(page.menu)
        binding.panelSearch.setText(page.query)
        rendering = false
        renderItems()
        binding.panelScroll.post {
            binding.panelScroll.scrollTo(0, page.y)
            if (page.focusId != 0) binding.panelItems.findViewById<View>(page.focusId)?.requestFocus()
        }
    }

    private fun renderItems() {
        val page = pages.last()
        binding.panelItems.removeAllViews()
        val items = page.menu.children.filter { it.isVisible &&
            (page.query.isBlank() || it.title.toString().contains(page.query, ignoreCase = true)) }.toList()
        var group: Int? = null
        for (item in items) {
            if (group != null && group != item.groupId) {
                binding.panelItems.addView(View(context).apply {
                    setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(context,
                        com.google.android.material.R.attr.colorOutlineVariant, 0x1F888888))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(8); bottomMargin = dp(8)
                })
            }
            group = item.groupId
            val row = CheckedTextView(context).apply {
                id = item.itemId
                text = if (item.hasSubMenu()) context.getString(R.string.fw_menu_category, item.title) else item.title
                if (item.hasSubMenu()) contentDescription = context.getString(R.string.fw_menu_open_category, item.title)
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
                setPaddingRelative(dp(24), dp(12), dp(24), dp(12))
                minHeight = dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (item.isCheckable) {
                    setCheckMarkDrawable(android.R.drawable.checkbox_on_background)
                    isChecked = item.isChecked
                    if (!item.isChecked) setCheckMarkDrawable(android.R.drawable.checkbox_off_background)
                }
                isEnabled = item.isEnabled
                isFocusable = true
                val backgroundAttr = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                background = backgroundAttr.getDrawable(0)
                backgroundAttr.recycle()
                setOnClickListener {
                    if (!isValid()) { dismiss(); return@setOnClickListener }
                    if (!item.isVisible || !item.isEnabled) return@setOnClickListener
                    page.focusId = item.itemId
                    if (item.hasSubMenu()) { push(item.title ?: "", item.subMenu!!); return@setOnClickListener }
                    val stay = keepOpen(item)
                    if (!stay) dismiss()
                    onAction(item)
                    if (stay && dialog.isShowing) refresh()
                }
            }
            binding.panelItems.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (items.isEmpty()) binding.panelItems.addView(TextView(context).apply {
            setText(R.string.fw_menu_no_results); setPadding(dp(24), dp(16), dp(24), dp(16))
        })
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    companion object {
        private val active = WeakHashMap<Context, java.lang.ref.WeakReference<ActionPanel>>()
        internal fun current(context: Context): ActionPanel? = active[activityContext(context)]?.get()
        fun dismiss(context: Context) { current(context)?.dismiss() }
        private fun activityContext(context: Context): Context {
            var current = context
            while (current is android.content.ContextWrapper && current !is android.app.Activity) {
                current = current.baseContext
            }
            return current
        }
    }
}
