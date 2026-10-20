package me.zhanghai.android.files.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.features.FeatureActivity

/** Small image/text previews stay in memory; no FileProvider or plaintext cache is used. */
class VaultPreviewActivity : FeatureActivity() {
    private var bitmap: Bitmap? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val path = intent.getStringExtra("vault_path") ?: run { finish(); return }
        val generation = intent.getLongExtra("vault_generation", -1)
        screen(path.substringAfterLast('/'))
        text(getString(R.string.fw_working))
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val output = java.io.ByteArrayOutputStream()
                    val size = VaultSession.size(path, generation)
                    if (size > 16 * 1024 * 1024) throw java.io.IOException("Preview too large")
                    VaultSession.export(path, output)
                    output.toByteArray()
                }
                try {
                    if (!VaultSession.isOpen() || generation != VaultSession.generation()) { finish(); return@launch }
                    content.removeAllViews()
                    val extension = path.substringAfterLast('.').lowercase()
                    if (extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, 0, data.size, options)
                        if (options.outWidth <= 0 || options.outHeight <= 0) throw java.io.IOException("Invalid image")
                        options.inSampleSize = 1
                        while (options.outWidth.toLong() * options.outHeight / (options.inSampleSize.toLong() * options.inSampleSize) > 8_000_000) options.inSampleSize *= 2
                        options.inJustDecodeBounds = false
                        bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: throw java.io.IOException("Invalid image")
                        content.addView(ImageView(this@VaultPreviewActivity).apply {
                            setImageBitmap(bitmap); adjustViewBounds = true; contentDescription = path.substringAfterLast('/')
                        })
                    } else {
                        if (data.size > 2 * 1024 * 1024) throw java.io.IOException("Text preview too large")
                        text(String(data, Charsets.UTF_8))
                    }
                } finally { data.fill(0) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                content.removeAllViews(); text(getString(R.string.fw_vault_preview_failure))
            }
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) { VaultSession.lock(); finish() }
        super.onStop()
    }
    override fun onDestroy() { bitmap?.recycle(); bitmap = null; super.onDestroy() }
}
