package com.dostup.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Fork: the first colour of each pair is the night one (the original palette of the app), the second
 * is the day one. Every value is read when drawing, so screens follow [AppTheme] without changes.
 */
object Palette {
    private fun pick(dark: Long, light: Long) = Color(if (AppTheme.isDark) dark else light)

    val bgBase get() = pick(0xFF101012, 0xFFF4F5F7)
    val bgSurface get() = pick(0xFF18181B, 0xFFFFFFFF)
    val bgSurfaceAlt get() = pick(0xFF1F1F23, 0xFFEDEEF1)
    val bgSheet get() = pick(0xFF313136, 0xFFE1E3E8)
    val bgPanel get() = pick(0xFF3F3F46, 0xFFCDD1D8)
    val textPrimary get() = pick(0xFFFAFAFA, 0xFF16181D)
    val textSecondary get() = pick(0xFFE4E4E7, 0xFF2E323A)
    val textTertiary get() = pick(0xFFA1A1AA, 0xFF5A606B)
    val textQuaternary get() = pick(0xFF86868F, 0xFF7C828C)
    /** ByeByeRKN brand cyan: the connected ring, accent text and active controls. */
    val brand get() = pick(0xFF00D5FE, 0xFF0090B0)
    val brandDark get() = pick(0xFF009FC0, 0xFF006E88)
    /** Brand washed onto the background, for selected chips and tabs. */
    val brandTint get() = pick(0xFF0A2A31, 0xFFD3F1F8)
    val danger get() = pick(0xFFF87171, 0xFFDC2626)

    internal fun onPrimary() = pick(0xFF00191F, 0xFFFFFFFF)
    internal fun disabledPrimary() = pick(0xFF0C3640, 0xFFD5ECF1)
    internal fun disabledOnPrimary() = pick(0xFF4F7C86, 0xFF7FA7B0)
    internal fun disabledPrimarySlider() = pick(0xFF12505E, 0xFFB5DCE5)
    internal fun disabledOnSurface() = pick(0xFF52525B, 0xFFA1A7B0)
}

private fun colors(dark: Boolean) = if (dark) darkColorScheme(
    primary = Palette.brand,
    onPrimary = Palette.onPrimary(),
    primaryVariant = Palette.brandDark,
    onPrimaryVariant = Palette.brand,
    disabledPrimary = Palette.disabledPrimary(),
    disabledOnPrimary = Palette.disabledOnPrimary(),
    disabledPrimaryButton = Palette.disabledPrimary(),
    disabledOnPrimaryButton = Palette.disabledOnPrimary(),
    disabledPrimarySlider = Palette.disabledPrimarySlider(),
    primaryContainer = Palette.brandDark,
    onPrimaryContainer = Color.White,
    secondary = Palette.bgPanel,
    onSecondary = Palette.textPrimary,
    secondaryVariant = Palette.bgSheet,
    onSecondaryVariant = Palette.textSecondary,
    secondaryContainer = Palette.bgSurfaceAlt,
    onSecondaryContainer = Palette.textQuaternary,
    secondaryContainerVariant = Palette.bgSheet,
    onSecondaryContainerVariant = Palette.textTertiary,
    tertiaryContainer = Palette.brandTint,
    onTertiaryContainer = Palette.brand,
    tertiaryContainerVariant = Palette.bgSheet,
    background = Palette.bgBase,
    onBackground = Palette.textPrimary,
    onBackgroundVariant = Palette.textTertiary,
    surface = Palette.bgBase,
    onSurface = Palette.textPrimary,
    surfaceVariant = Palette.bgSurface,
    onSurfaceSecondary = Palette.textSecondary,
    onSurfaceVariantSummary = Palette.textTertiary,
    onSurfaceVariantActions = Palette.textQuaternary,
    disabledOnSurface = Palette.disabledOnSurface(),
    surfaceContainer = Palette.bgSurface,
    onSurfaceContainer = Palette.textPrimary,
    onSurfaceContainerVariant = Palette.textQuaternary,
    surfaceContainerHigh = Palette.bgSurfaceAlt,
    onSurfaceContainerHigh = Palette.textTertiary,
    surfaceContainerHighest = Palette.bgSheet,
    onSurfaceContainerHighest = Palette.textPrimary,
    outline = Palette.bgPanel,
    dividerLine = Palette.bgSheet,
    sliderKeyPointForeground = Palette.brand,
) else lightColorScheme(
    primary = Palette.brand,
    onPrimary = Palette.onPrimary(),
    primaryVariant = Palette.brandDark,
    onPrimaryVariant = Palette.brand,
    disabledPrimary = Palette.disabledPrimary(),
    disabledOnPrimary = Palette.disabledOnPrimary(),
    disabledPrimaryButton = Palette.disabledPrimary(),
    disabledOnPrimaryButton = Palette.disabledOnPrimary(),
    disabledPrimarySlider = Palette.disabledPrimarySlider(),
    primaryContainer = Palette.brandDark,
    onPrimaryContainer = Color.White,
    secondary = Palette.bgPanel,
    onSecondary = Palette.textPrimary,
    secondaryVariant = Palette.bgSheet,
    onSecondaryVariant = Palette.textSecondary,
    secondaryContainer = Palette.bgSurfaceAlt,
    onSecondaryContainer = Palette.textQuaternary,
    secondaryContainerVariant = Palette.bgSheet,
    onSecondaryContainerVariant = Palette.textTertiary,
    tertiaryContainer = Palette.brandTint,
    onTertiaryContainer = Palette.brand,
    tertiaryContainerVariant = Palette.bgSheet,
    background = Palette.bgBase,
    onBackground = Palette.textPrimary,
    onBackgroundVariant = Palette.textTertiary,
    surface = Palette.bgBase,
    onSurface = Palette.textPrimary,
    surfaceVariant = Palette.bgSurface,
    onSurfaceSecondary = Palette.textSecondary,
    onSurfaceVariantSummary = Palette.textTertiary,
    onSurfaceVariantActions = Palette.textQuaternary,
    disabledOnSurface = Palette.disabledOnSurface(),
    surfaceContainer = Palette.bgSurface,
    onSurfaceContainer = Palette.textPrimary,
    onSurfaceContainerVariant = Palette.textQuaternary,
    surfaceContainerHigh = Palette.bgSurfaceAlt,
    onSurfaceContainerHigh = Palette.textTertiary,
    surfaceContainerHighest = Palette.bgSheet,
    onSurfaceContainerHighest = Palette.textPrimary,
    outline = Palette.bgPanel,
    dividerLine = Palette.bgSheet,
    sliderKeyPointForeground = Palette.brand,
)

@Composable
fun DostupTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    SideEffect { AppTheme.systemDark = systemDark }
    val dark = AppTheme.isDark
    val activity = LocalContext.current.findComponentActivity()
    // Status and navigation bar icons have to be dark on a light screen. The window itself shows
    // for a moment while the screen starts or turns, so it gets the screen's colour too.
    val background = Palette.bgBase
    SideEffect {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        activity?.window?.setBackgroundDrawable(ColorDrawable(background.toArgb()))
    }
    MiuixTheme(colors = colors(dark), content = content)
}

private fun Context.findComponentActivity(): ComponentActivity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>().firstOrNull()
