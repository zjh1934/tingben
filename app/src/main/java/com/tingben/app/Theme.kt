package com.tingben.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

internal data class Pal(
    val bg: Color, val card: Color, val primary: Color, val primarySoft: Color,
    val sub: Color, val good: Color, val line: Color, val warn: Color,
    val text: Color, val onPrimary: Color, val grey: Color
)

internal val LightPal = Pal(
    bg = Color(0xFFF4F6FB), card = Color(0xFFFFFFFF), primary = Color(0xFF4338CA),
    primarySoft = Color(0xFFEEF0FE), sub = Color(0xFF6B7280), good = Color(0xFF0E9F6E),
    line = Color(0xFFE7EAF2), warn = Color(0xFFB45309), text = Color(0xFF1B2030),
    onPrimary = Color(0xFFFFFFFF), grey = Color(0xFFB9BFCB)
)

internal val DarkPal = Pal(
    bg = Color(0xFF12151C), card = Color(0xFF1C212C), primary = Color(0xFFA5B4FC),
    primarySoft = Color(0xFF2A3350), sub = Color(0xFF9AA3B5), good = Color(0xFF34D399),
    line = Color(0xFF2E3545), warn = Color(0xFFFBBF24), text = Color(0xFFE8ECF5),
    onPrimary = Color(0xFF10131A), grey = Color(0xFF6B7280)
)

private var _appThemeDark by mutableStateOf(false)

internal val appThemeDark: Boolean get() = _appThemeDark

internal val palette: Pal get() = if (_appThemeDark) DarkPal else LightPal

internal fun isSystemNight(c: Context): Boolean =
    (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

internal fun applyAppTheme(c: Context, mode: Int) {
    _appThemeDark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemNight(c)
    }
}

@Composable
internal fun TingbenTheme(content: @Composable () -> Unit) {
    val dark = appThemeDark
    val view = LocalView.current
    DisposableEffect(dark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
        onDispose { }
    }
    val p = palette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, background = p.bg, surface = p.card,
            onPrimary = p.onPrimary, onBackground = p.text, onSurface = p.text,
            outline = p.line
        )
    } else {
        lightColorScheme(
            primary = p.primary, background = p.bg, surface = p.card,
            onPrimary = p.onPrimary, onBackground = p.text, onSurface = p.text,
            outline = p.line
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
