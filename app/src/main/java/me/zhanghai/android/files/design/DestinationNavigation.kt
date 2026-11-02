package me.zhanghai.android.files.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.zhanghai.android.files.R

@Composable
fun DestinationNavigation(selected: Int, rail: Boolean, onSelect: (Int) -> Unit,
                          insets: androidx.core.graphics.Insets) {
    val padding = with(LocalDensity.current) { PaddingValues(
        start = insets.left.toDp(), top = if (rail) insets.top.toDp() else 0.dp,
        end = if (rail) 0.dp else insets.right.toDp(), bottom = insets.bottom.toDp()) }
    val modifier = Modifier.padding(padding)
    val titles = listOf(R.string.fw_redesign_files, R.string.fw_redesign_devices, R.string.fw_redesign_transfers)
    val icons = listOf(R.drawable.directory_icon_white_24dp, R.drawable.computer_icon_white_24dp, R.drawable.fw_transfers_24dp)
    if (rail) NavigationRail(modifier = modifier, windowInsets = WindowInsets(0, 0, 0, 0)) {
        titles.forEachIndexed { index, title -> NavigationRailItem(selected == index, { onSelect(index) },
            icon = { Icon(painterResource(icons[index]), null) }, label = { Text(stringResource(title)) }) }
    } else NavigationBar(modifier = modifier, windowInsets = WindowInsets(0, 0, 0, 0)) {
        titles.forEachIndexed { index, title -> NavigationBarItem(selected == index, { onSelect(index) },
            icon = { Icon(painterResource(icons[index]), null) }, label = { Text(stringResource(title)) }) }
    }
}
