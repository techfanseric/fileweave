package me.zhanghai.android.files.design

import android.content.res.Configuration
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.google.android.material.color.MaterialColors

/** Bridges the user's existing accent and night-mode preference into Material 3. */
@Composable
fun FileWeaveTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val seed = Color(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xff49664b.toInt()))
    val surface = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0xfffbfcf8.toInt()))
    val onSurface = Color(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xff1a1c19.toInt()))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Legacy accents can be too light for text (for example green 500). Material 3
    // uses the same hue with light/dark tonal roles, keeping foregrounds paired.
    val roles = MaterialColors.getColorRoles(seed.toArgb(), !dark)
    val primary = Color(roles.accent)
    fun color(attribute: Int, fallback: Color) = Color(MaterialColors.getColor(context, attribute, fallback.toArgb()))
    val container = Color(roles.accentContainer)
    val onContainer = Color(roles.onAccentContainer)
    val onPrimary = Color(roles.onAccent)
    val variant = onSurface.copy(alpha = .08f).compositeOn(surface)
    MaterialTheme(colorScheme = base.copy(primary = primary, onPrimary = onPrimary,
        secondary = primary, onSecondary = onPrimary, tertiary = primary, onTertiary = onPrimary,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondaryContainer = container, onSecondaryContainer = onContainer,
        tertiaryContainer = container, onTertiaryContainer = onContainer,
        surface = surface, background = surface, onSurface = onSurface, onBackground = onSurface,
        surfaceContainerLowest = surface, surfaceContainerLow = onSurface.copy(alpha = .025f).compositeOn(surface),
        surfaceContainer = onSurface.copy(alpha = .04f).compositeOn(surface),
        surfaceContainerHigh = variant, surfaceContainerHighest = onSurface.copy(alpha = .12f).compositeOn(surface),
        surfaceVariant = variant, onSurfaceVariant = onSurface.copy(alpha = .72f),
        outline = color(com.google.android.material.R.attr.colorOutline, onSurface.copy(alpha = .45f).compositeOn(surface)),
        outlineVariant = onSurface.copy(alpha = .16f).compositeOn(surface),
        error = color(androidx.appcompat.R.attr.colorError, base.error),
        onError = color(com.google.android.material.R.attr.colorOnError, base.onError)), content = content)
}

private fun Color.compositeOn(background: Color) = Color(
    red * alpha + background.red * (1 - alpha),
    green * alpha + background.green * (1 - alpha),
    blue * alpha + background.blue * (1 - alpha), 1f)
