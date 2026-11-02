package me.zhanghai.android.files.lanshare

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.os.Process
import android.widget.FrameLayout
import android.view.View
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.filelist.FileListActivity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.LanShareActivityBinding
import me.zhanghai.android.files.util.extraPathList

/** Private hub; external share intents enter through the separate validated receiver below. */
open class LanShareActivity : AppActivity() {
    protected open val receivesExternalShare = false
    private lateinit var binding: LanShareActivityBinding
    private var qrJob: Job? = null
    private var qrUrl: String? = null
    private var incomingStarted = false
    private var reviewingIncoming = false
    private var incomingExpanded = false
    private val reviewCancellation = CancellationSignal()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) try {
            validateUris(uris)
            startShare(uris = uris)
        } catch (_: Exception) { message(R.string.fw_lan_receive_invalid) }
    }

    private val folderPicker = registerForActivityResult(FileListActivity.OpenDirectoryContract()) { path ->
        path?.let { startShare(paths = listOf(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = LanShareActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.apply {
            title = getString(R.string.fw_lan_share)
            setNavigationIcon(R.drawable.fw_back)
            setNavigationOnClickListener { finish() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        incomingExpanded = savedInstanceState?.getBoolean("incoming_expanded") ?: false
        binding.shareScroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val available = right - left - binding.shareScroll.paddingLeft - binding.shareScroll.paddingRight
            val width = minOf(available, (560 * resources.displayMetrics.density).toInt())
            val params = binding.contentColumn.layoutParams as FrameLayout.LayoutParams
            if (width > 0 && params.width != width) {
                params.width = width
                binding.contentColumn.layoutParams = params
            }
        }
        binding.changeContent.setOnClickListener { showScopeMenu(it) }
        binding.startGlobal.setOnClickListener { startShare(global = true) }
        binding.toggleIncomingDetails.setOnClickListener {
            incomingExpanded = !incomingExpanded
            updateIncomingDetails()
        }
        setUpMoreMenu()
        binding.copyLink.setOnClickListener {
            currentDirectLink()?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("FileWeave", it))
                message(R.string.fw_lan_copied)
            }
        }
        binding.sendLink.setOnClickListener {
            currentDirectLink()?.let {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), getString(R.string.fw_lan_send)))
            }
        }
        binding.stop.setOnClickListener { startService(Intent(this, LanShareService::class.java).setAction(LanShareService.ACTION_STOP)) }
        LanShareService.state.observe(this, ::render)
        incomingStarted = savedInstanceState?.getBoolean("incoming_started") ?: false
        if (receivesExternalShare) {
            try {
                val payload = incomingPayload(intent)
                reviewingIncoming = !incomingStarted
                binding.incomingSection.isVisible = reviewingIncoming
                binding.incomingPreview.text = if (payload.first.isNotEmpty()) getString(R.string.fw_lan_importing, payload.first.size) else payload.second
                binding.incomingPreview.isVisible = !incomingStarted
                binding.startIncoming.isVisible = !incomingStarted
                if (payload.first.isNotEmpty() && !incomingStarted) {
                    binding.startIncoming.isEnabled = false
                    lifecycleScope.launch {
                        val details = withContext(Dispatchers.IO) {
                            payload.first.joinToString("\n\n") { uri ->
                                if (reviewCancellation.isCanceled) throw CancellationException("Share review cancelled")
                                val name = try {
                                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, reviewCancellation)
                                        ?.use { if (it.moveToFirst()) it.getString(0)?.take(256) else null }
                                } catch (error: Exception) {
                                    if (reviewCancellation.isCanceled) throw CancellationException("Share review cancelled", error)
                                    null
                                }
                                "${name ?: getString(R.string.fw_lan_received_file)}\n$uri"
                            }
                        }
                        binding.incomingPreview.text = details
                        updateIncomingDetails()
                        binding.startIncoming.isEnabled = true
                    }
                }
                binding.startIncoming.setOnClickListener {
                    startShare(uris = payload.first, text = payload.second)
                    incomingStarted = true
                    reviewingIncoming = false
                    binding.incomingSection.isVisible = false
                    render(LanShareService.state.value!!)
                }
                updateIncomingDetails()
                render(LanShareService.state.value!!)
            } catch (_: Exception) { message(R.string.fw_lan_receive_invalid); finish() }
        } else if (savedInstanceState == null) {
            val paths = intent.extraPathList
            if (paths.isNotEmpty()) {
                startShare(paths = paths)
            } else {
                val state = LanShareService.state.value
                if (state?.url == null && state?.starting != true) startShare(global = true)
            }
        }
    }
    override fun onDestroy() {
        reviewCancellation.cancel()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("incoming_started", incomingStarted)
        outState.putBoolean("incoming_expanded", incomingExpanded)
        super.onSaveInstanceState(outState)
    }
    private fun render(state: LanShareService.State) {
        val running = state.url != null
        binding.sessionTitle.setText(when {
            reviewingIncoming -> R.string.fw_lan_review_heading
            running -> R.string.fw_lan_running
            state.starting -> R.string.fw_lan_preparing
            else -> R.string.fw_lan_stopped
        })
        binding.scope.isVisible = running && !reviewingIncoming
        binding.scope.text = when {
            state.global -> getString(R.string.fw_lan_scope_global)
            state.names.isNotEmpty() -> getString(R.string.fw_lan_scope_selected, state.names.size)
            state.clipboardText != null -> getString(R.string.fw_lan_scope_text_only)
            else -> getString(R.string.fw_lan_scope_empty)
        }
        binding.selectionPreview.isVisible = running && !state.global && state.names.isNotEmpty() && !reviewingIncoming
        val names = state.names.take(2)
        binding.selectionPreview.text = (names + if (state.names.size > names.size)
            listOf(getString(R.string.fw_lan_more_items, state.names.size - names.size)) else emptyList()).joinToString(" · ")
        binding.statusMessage.text = state.error
        binding.statusMessage.isVisible = state.error != null && !reviewingIncoming
        binding.sendBlock.isVisible = running && !reviewingIncoming
        binding.address.text = state.directAccessUrl ?: state.url
        binding.copyLink.isEnabled = running
        binding.sendLink.isEnabled = running
        binding.startGlobal.isVisible = !running && !state.starting && !reviewingIncoming
        binding.changeContent.isVisible = !reviewingIncoming
        binding.changeContent.isEnabled = !state.starting
        binding.changeContent.setText(if (running) R.string.fw_lan_change_scope else R.string.fw_lan_choose_other_content)
        binding.clipboardPreview.text = state.clipboardText?.take(1000)
        binding.clipboardPreview.isVisible = running && state.clipboardText != null && !reviewingIncoming
        binding.stop.isVisible = !reviewingIncoming && (running || state.starting)
        binding.stop.isEnabled = running || state.starting
        binding.toolbar.menu.findItem(R.id.lan_more_append_text).isVisible = running && !reviewingIncoming
        binding.toolbar.menu.findItem(R.id.clear_clipboard).isVisible = running && state.clipboardText != null && !reviewingIncoming
        binding.toolbar.menu.findItem(R.id.access_code).isVisible = running && state.accessCode != null && !reviewingIncoming
        updateQr(state.url)
    }

    private fun setUpMoreMenu() {
        binding.toolbar.menu.apply {
            add(Menu.NONE, R.id.lan_more_append_text, 0, R.string.fw_lan_append_text).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            add(Menu.NONE, R.id.clear_clipboard, 1, R.string.fw_lan_clear_text).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            add(Menu.NONE, R.id.access_code, 2, R.string.fw_lan_copy_access_code).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            add(Menu.NONE, R.id.toggle_notes, 3, R.string.fw_lan_notes_show).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.lan_more_append_text -> shareClipboardText(append = true)
                R.id.clear_clipboard -> updateClipboard(null)
                R.id.access_code -> LanShareService.state.value?.accessCode?.let { code ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.fw_lan_access_code), code))
                    message(R.string.fw_lan_access_code_copied)
                }
                R.id.toggle_notes -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.fw_lan_notes_show).setMessage(R.string.fw_lan_scope_help)
                    .setPositiveButton(android.R.string.ok, null).show()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun showScopeMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, R.id.lan_scope_global, 0, R.string.fw_lan_scope_global)
                .apply { isCheckable = true; isChecked = LanShareService.state.value?.global == true }
            menu.add(Menu.NONE, R.id.pick_files, 1, R.string.fw_lan_pick_files)
            menu.add(Menu.NONE, R.id.lan_scope_folder, 2, R.string.fw_lan_pick_folder)
            menu.add(Menu.NONE, R.id.share_clipboard, 3, R.string.fw_lan_only_clipboard_text)
            menu.add(Menu.NONE, R.id.share_clipboard_files, 4, R.string.fw_lan_action_copied_files)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.lan_scope_global -> if (LanShareService.state.value?.let { it.global && it.url != null } != true) startShare(global = true)
                    R.id.pick_files -> picker.launch(arrayOf("*/*"))
                    R.id.lan_scope_folder -> folderPicker.launch(null)
                    R.id.share_clipboard -> shareClipboardText(append = false)
                    R.id.share_clipboard_files -> shareClipboardFiles()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun shareClipboardText(append: Boolean) {
        val text = foregroundClip()?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        when {
            text.isNullOrEmpty() -> message(R.string.fw_lan_clipboard_empty)
            text.length > LanShareService.MAX_CLIPBOARD_LENGTH -> message(R.string.fw_lan_clipboard_too_long)
            append -> updateClipboard(text)
            else -> startShare(text = text)
        }
    }

    private fun shareClipboardFiles() {
        val clip = foregroundClip()
        val uris = clip?.let { (0 until it.itemCount).mapNotNull { index -> it.getItemAt(index).uri } }.orEmpty().distinct()
        if (uris.isEmpty()) message(R.string.fw_lan_clipboard_no_files) else try {
            validateUris(uris)
            startShare(uris = uris)
        } catch (_: Exception) { message(R.string.fw_lan_receive_invalid) }
    }

    private fun currentDirectLink(): String? = LanShareService.state.value?.let { it.directAccessUrl ?: it.url }

    private fun updateIncomingDetails() {
        binding.incomingPreview.maxLines = if (incomingExpanded) Int.MAX_VALUE else 5
        val preview = binding.incomingPreview.text
        binding.toggleIncomingDetails.isVisible = preview.length > 200 || preview.count { it == '\n' } >= 5
        binding.toggleIncomingDetails.setText(if (incomingExpanded) R.string.fw_lan_review_less else R.string.fw_lan_review_details)
    }

    private fun foregroundClip(): ClipData? {
        // The app's focused popup can own focus while the Activity's window does not.
        // Android still enforces clipboard access for the foreground UID.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        return try { getSystemService(ClipboardManager::class.java).primaryClip } catch (_: SecurityException) { null }
    }
    @Suppress("DEPRECATION")
    private fun incomingPayload(source: Intent): Pair<List<Uri>, String?> {
        require(source.action == Intent.ACTION_SEND || source.action == Intent.ACTION_SEND_MULTIPLE)
        val uris = (if (source.action == Intent.ACTION_SEND_MULTIPLE) source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)))
            .ifEmpty { source.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty() }.distinct()
        validateUris(uris)
        val text = if (uris.isEmpty()) source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        require(uris.isNotEmpty() || (!text.isNullOrEmpty() && text.length <= LanShareService.MAX_CLIPBOARD_LENGTH))
        return uris to text
    }
    @Suppress("DEPRECATION")
    private fun validateUris(uris: List<Uri>) {
        require(uris.size <= LanShareService.MAX_IMPORTED_FILES)
        for (uri in uris) {
            require(uri.scheme == "content" && !uri.authority.isNullOrEmpty() && uri.toString().length <= 4096)
            val provider = packageManager.resolveContentProvider(uri.authority!!, 0)
            // Never let an external URI act as a request to read our own private provider.
            require(provider != null && provider.applicationInfo.uid != Process.myUid())
        }
    }
    private fun startShare(
        paths: List<Path> = emptyList(), uris: List<Uri> = emptyList(), text: String? = null,
        global: Boolean = false
    ) {
        val start = Intent(this, LanShareService::class.java).setAction(LanShareService.ACTION_START).apply {
            extraPathList = paths
            putExtra(LanShareService.EXTRA_GLOBAL, global)
            putParcelableArrayListExtra(LanShareService.EXTRA_URIS, ArrayList(uris))
            putExtra(LanShareService.EXTRA_CLIPBOARD, text)
            if (uris.isNotEmpty()) {
                clipData = ClipData(ClipDescription("Selected files", arrayOf("*/*")), ClipData.Item(uris.first())).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        ContextCompat.startForegroundService(this, start)
    }
    private fun updateClipboard(text: String?) {
        val url = LanShareService.state.value?.url ?: return
        startService(Intent(this, LanShareService::class.java).setAction(LanShareService.ACTION_CLIPBOARD)
            .putExtra(LanShareService.EXTRA_EXPECTED_URL, url).putExtra(LanShareService.EXTRA_CLIPBOARD, text))
    }
    private fun updateQr(url: String?) {
        if (qrUrl == url) return
        qrUrl = url
        qrJob?.cancel()
        binding.qrCode.setImageDrawable(null)
        if (url == null) return
        qrJob = lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    val matrix = LanShareQr.encode(url)
                    val pixels = IntArray(matrix.width * matrix.height) { index ->
                        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                }
                if (qrUrl == url) binding.qrCode.setImageBitmap(bitmap)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { message(R.string.fw_lan_qr_failed) }
        }
    }
    private fun message(resource: Int) { Toast.makeText(this, resource, Toast.LENGTH_LONG).show() }
}

/** Only the sanitized, explicitly confirmed system-share flow is exported. */
class LanShareReceiveActivity : LanShareActivity() {
    override val receivesExternalShare = true
}
