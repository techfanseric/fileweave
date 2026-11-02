package me.zhanghai.android.files.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.zhanghai.android.files.R

data class PageAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturePage(title: String, back: (() -> Unit)? = null, actions: List<PageAction> = emptyList(),
                busy: Boolean = false, content: LazyListScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { if (back != null) IconButton(onClick = back) {
                Icon(painterResource(R.drawable.fw_back), stringResource(R.string.fw_redesign_back))
            } }, actions = { ActionMenu(actions) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp), content = content)
            }
        }
    }
}

@Composable
fun ActionMenu(actions: List<PageAction>) {
    if (actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.more_vertical_icon_control_normal_24dp), stringResource(R.string.fw_redesign_tools))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { expanded = false; action.onClick() }) }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
fun ContentRow(title: String, subtitle: String? = null, @DrawableRes icon: Int = R.drawable.file_icon_white_24dp,
               enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) },
        trailingContent = trailing,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier))
}

@Composable
fun EmptyContent(title: String, hint: String, @DrawableRes icon: Int = R.drawable.file_icon_white_24dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
    }
}

@Composable
fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 48.dp)) { Text(label) }
}

@Composable
fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
}

/** Native document/image previews remain View-backed inside the same Material 3 navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyFeatureContent(title: String, view: android.view.View, back: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.fw_back), stringResource(R.string.fw_redesign_back)) } }) }) { padding ->
        androidx.compose.runtime.key(view) {
            androidx.compose.ui.viewinterop.AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}
