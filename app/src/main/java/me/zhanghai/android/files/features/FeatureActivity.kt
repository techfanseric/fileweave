package me.zhanghai.android.files.features

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import me.zhanghai.android.files.design.FileWeaveTheme
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity

/** Uses the existing app theme and standard Material controls. */
abstract class FeatureActivity : AppActivity() {
    private var composeView: ComposeView? = null
    private val composeBody = mutableStateOf<(@Composable () -> Unit)?>(null)
    protected fun composeScreen(body: @Composable () -> Unit) {
        composeBody.value = body
        if (composeView == null) {
            composeView = ComposeView(this).apply {
                id = R.id.fw_feature_content
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { FileWeaveTheme { composeBody.value?.invoke() } }
            }
            setContentView(composeView)
        }
    }
    protected lateinit var content: LinearLayout
    protected fun screen(title: String) {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        composeScreen { me.zhanghai.android.files.design.LegacyFeatureContent(title, scroll) { finish() } }
    }
    protected fun text(value: String): TextView = TextView(this).apply {
        text = value; textSize = 16f
        val density = resources.displayMetrics.density
        setPadding(0, (12 * density).toInt(), 0, (20 * density).toInt())
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    protected fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
