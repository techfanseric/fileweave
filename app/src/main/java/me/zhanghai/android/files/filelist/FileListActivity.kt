/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.isVisible
import me.zhanghai.android.files.R
import me.zhanghai.android.files.design.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.fragment.app.commit
import java8.nio.file.Path
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs

class FileListActivity : AppActivity() {
    private lateinit var fragment: FileListFragment
    private val destination = mutableIntStateOf(0)
    private val navigationInsets = androidx.compose.runtime.mutableStateOf(androidx.core.graphics.Insets.NONE)
    private var fileHost: FrameLayout? = null
    private var destinationHost: ComposeView? = null
    private var navigationSecureFlag = false
    private var destinationBack: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep document pickers and external path/archive intents in their original standalone flow.
        navigationSecureFlag = savedInstanceState?.getBoolean("navigationSecureFlag") ?: false
        val main = intent.action == null || intent.action == Intent.ACTION_MAIN
        val containerId = if (main) R.id.fw_file_host else android.R.id.content
        if (main) {
            // Legacy file containers request layout-in-status-bar only while visible.
            // Keep the whole shell edge-to-edge so hiding one cannot move its siblings.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            destination.intValue = savedInstanceState?.getInt("destination", 0) ?: 0
            val rail = resources.configuration.screenWidthDp >= 600
            val root = LinearLayout(this).apply {
                orientation = if (rail) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                // Baseline measurement otherwise probes a zero-width weighted child with
                // UNSPECIFIED, which DrawerLayout correctly refuses. The second pass is exact.
                isBaselineAligned = false
            }
            val body = FrameLayout(this)
            fileHost = FrameLayout(this).apply { id = containerId }
            body.addView(fileHost, FrameLayout.LayoutParams(-1, -1))
            destinationHost = ComposeView(this).apply {
                id = R.id.fw_destination_content
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { FileWeaveTheme {
                    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                    stateHolder.SaveableStateProvider(destination.intValue) {
                        when (destination.intValue) {
                            1 -> DevicesDestination()
                            2 -> TransfersDestination()
                        }
                    }
                } }
            }
            body.addView(destinationHost, FrameLayout.LayoutParams(-1, -1))
            val navigation = ComposeView(this).apply {
                id = R.id.fw_destination_navigation
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { FileWeaveTheme { DestinationNavigation(destination.intValue, rail, ::selectDestination, navigationInsets.value) } }
            }
            if (rail) root.addView(navigation, LinearLayout.LayoutParams(-2, -1))
            root.addView(body, if (rail) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
            if (!rail) root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
            // Apply system-bar space once, before legacy children can consume insets.
            // Compose defaults otherwise change the rail's top padding between destinations.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val windowInsets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView) ?: insets
                val bars = windowInsets.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                navigationInsets.value = bars
                insets
            }
            setContentView(root)
            androidx.core.view.ViewCompat.requestApplyInsets(root)
        } else findViewById<View>(android.R.id.content)
        fragment = supportFragmentManager.findFragmentById(containerId) as? FileListFragment
            ?: FileListFragment().putArgs(FileListFragment.Args(intent)).also {
                supportFragmentManager.commit { add(containerId, it) }
            }
        if (main) {
            // Register after the file fragment's callbacks: another destination always returns to files first.
            supportFragmentManager.executePendingTransactions()
            destinationBack = object : OnBackPressedCallback(destination.intValue != 0) {
                override fun handleOnBackPressed() { selectDestination(0) }
            }.also { onBackPressedDispatcher.addCallback(it) }
            selectDestination(destination.intValue)
        }
    }

    private fun selectDestination(value: Int) {
        destination.intValue = value
        fileHost?.isVisible = value == 0
        destinationHost?.isVisible = value != 0
        destinationBack?.let {
            // Fragment callbacks may become registered when their view reaches STARTED.
            // Reinsert this shell callback last so non-file destinations own system Back.
            it.remove()
            it.isEnabled = value != 0
            onBackPressedDispatcher.addCallback(it)
        }
        // Keep the file fragment and its view alive so path, scroll and selection survive navigation.
        fragment.setMenuVisibility(value == 0)
        fileHost?.descendantFocusability = if (value == 0) android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        fileHost?.importantForAccessibility = if (value == 0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (value != 0) {
            me.zhanghai.android.files.ui.ActionPanel.dismiss(this)
            fragment.closeNavigationDrawer()
        }
        if (!supportFragmentManager.isStateSaved) supportFragmentManager.commit {
            setMaxLifecycle(fragment, if (value == 0) androidx.lifecycle.Lifecycle.State.RESUMED else androidx.lifecycle.Lifecycle.State.STARTED)
        }
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (value == 2 && window.attributes.flags and secure == 0) {
            window.addFlags(secure)
            navigationSecureFlag = true
        } else if (value != 2 && navigationSecureFlag) {
            // Only remove the flag this navigation shell added.
            window.clearFlags(secure)
            navigationSecureFlag = false
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        destinationBack?.let {
            it.remove()
            onBackPressedDispatcher.addCallback(it)
        }
    }

    override fun onDestroy() {
        destinationBack?.remove()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("destination", destination.intValue)
        outState.putBoolean("navigationSecureFlag", navigationSecureFlag)
        super.onSaveInstanceState(outState)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (destination.intValue == 0 && fragment.onKeyShortcut(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
