package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** Short pages fit their content; longer pages scroll without pushing the header off-screen. */
class ActionPanelScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maximum = (resources.displayMetrics.heightPixels * .65f).toInt()
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maximum
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(maximum, available), MeasureSpec.AT_MOST))
    }
}
