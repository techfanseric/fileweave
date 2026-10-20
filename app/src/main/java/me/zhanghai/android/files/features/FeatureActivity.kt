package me.zhanghai.android.files.features

import android.os.Bundle
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
    protected lateinit var content: LinearLayout
    protected fun screen(title: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(MaterialToolbar(this).apply {
            this.title = title
            setNavigationIcon(R.drawable.fw_back)
            setNavigationOnClickListener { finish() }
        })
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }
    protected fun text(value: String): TextView = TextView(this).apply {
        text = value; textSize = 16f
        setPadding(0, 12, 0, 20)
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    protected fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
