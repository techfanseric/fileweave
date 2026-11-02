package me.zhanghai.android.files.design

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import me.zhanghai.android.files.R
import me.zhanghai.android.files.bridge.*
import me.zhanghai.android.files.folderbridge.FolderBridgeActivity
import me.zhanghai.android.files.folderbridge.TranscriptActivity
import me.zhanghai.android.files.lanshare.LanShareActivity
import me.zhanghai.android.files.settings.SettingsActivity
import me.zhanghai.android.files.vault.VaultActivity
import me.zhanghai.android.files.file.asFileSize

@Composable
fun DevicesDestination() {
    val context = LocalContext.current
    val state by BridgeConnectionService.stateLiveData.observeAsState()
    fun open(type: Class<*>) { context.startActivity(Intent(context, type)) }
    FeaturePage(stringResource(R.string.fw_redesign_devices), actions = listOf(
        PageAction(stringResource(R.string.settings_title)) { open(SettingsActivity::class.java) }
    )) {
        item { SectionTitle(stringResource(R.string.fw_redesign_nearby)) }
        item {
            ContentRow(state?.macName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.fw_redesign_mac),
                if (state?.connected == true) stringResource(R.string.fw_bridge_connection_connected, state?.macName.orEmpty())
                else stringResource(when (state?.phase) {
                    BridgeConnectionService.Phase.PAIRING -> R.string.fw_bridge_connection_pairing
                    BridgeConnectionService.Phase.RETRYING -> R.string.fw_bridge_connection_retrying
                    else -> R.string.fw_bridge_connection_finding
                }), R.drawable.computer_icon_white_24dp,
                onClick = { open(BridgeActivity::class.java) })
        }
        if (state?.connected != true) item { StatusLine(stringResource(R.string.fw_redesign_device_hint)) }
        item { SectionTitle(stringResource(R.string.fw_redesign_sharing)) }
        item { ContentRow(stringResource(R.string.fw_lan_share), icon = R.drawable.file_icon_white_24dp, onClick = { open(LanShareActivity::class.java) }) }
        item { ContentRow(stringResource(R.string.fw_clipboard_title), icon = R.drawable.clipboard_icon_white_24dp, onClick = { open(ClipboardActivity::class.java) }) }
        item { ContentRow(stringResource(R.string.fw_folder_bridge_title), icon = R.drawable.audio_icon_white_24dp, onClick = { open(FolderBridgeActivity::class.java) }) }

    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransfersDestination(back: (() -> Unit)? = null) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val change by BridgeHistoryStore.changes.observeAsState(0L)
    val history = remember(change) { BridgeHistoryStore.list(context) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    fun delete(id: String) {
        if (id in deleting) return
        deleting = deleting + id
        scope.launch {
            try {
                withContext(Dispatchers.IO) { BridgeHistoryStore.remove(context, id) }
                Toast.makeText(context, R.string.fw_bridge_history_entry_deleted, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, R.string.fw_bridge_history_delete_failed, Toast.LENGTH_LONG).show()
            } finally { deleting = deleting - id }
        }
    }
    val shown = history.filter { filter == 0 || (filter == 1) == (it.kind == BridgeHistoryStore.Kind.FILE) }
    FeaturePage(stringResource(R.string.fw_redesign_transfers), back,
        actions = if (history.isNotEmpty()) listOf(PageAction(stringResource(R.string.fw_bridge_history_clear)) { confirmClear = true }) else emptyList()) {
        item { StatusLine(stringResource(R.string.fw_bridge_history_delete_scope)) }
        item {
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.fw_redesign_history_filter_all, R.string.fw_redesign_history_filter_files, R.string.fw_redesign_history_filter_text).forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(stringResource(label)) })
                }
            }
        }
        if (shown.isEmpty()) item { EmptyContent(stringResource(R.string.fw_redesign_transfer_empty), stringResource(R.string.fw_redesign_transfer_hint)) }
        items(shown, key = { it.id }) { entry ->
            val text = entry.kind == BridgeHistoryStore.Kind.CLIPBOARD
            val action = stringResource(if (text) {
                if (entry.outgoing) R.string.fw_redesign_history_text_sent else R.string.fw_redesign_history_text_received
            } else if (entry.outgoing) R.string.fw_redesign_history_file_sent else R.string.fw_redesign_history_file_received)
            val size = entry.size?.asFileSize()?.formatHumanReadable(context)
            ContentRow(if (text) entry.preview.orEmpty() else entry.name.orEmpty(),
                listOfNotNull(action, size, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, androidx.core.os.ConfigurationCompat.getLocales(configuration)[0] ?: java.util.Locale.ENGLISH).format(Date(entry.time))).joinToString(" · "),
                if (text) R.drawable.clipboard_icon_white_24dp else R.drawable.file_icon_white_24dp,
                trailing = {
                    IconButton(onClick = { delete(entry.id) }, enabled = entry.id !in deleting) {
                        Icon(painterResource(R.drawable.delete_icon_control_normal_24dp),
                            stringResource(R.string.fw_bridge_history_entry_delete))
                    }
                })
            HorizontalDivider(Modifier.padding(start = 56.dp))
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.fw_bridge_history_clear)) },
        text = { Text(stringResource(R.string.fw_bridge_history_clear_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            scope.launch {
                try { withContext(Dispatchers.IO) { BridgeHistoryStore.clear(context) } }
                catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Toast.makeText(context, R.string.fw_bridge_history_delete_failed, Toast.LENGTH_LONG).show()
                }
            }
        }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } })
}

class TransfersActivity : me.zhanghai.android.files.features.FeatureActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        composeScreen { TransfersDestination { finish() } }
    }
}
