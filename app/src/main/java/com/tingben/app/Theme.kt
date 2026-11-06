package com.tingben.app

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * R73 · P2 软件设置：夜间模式与主题色板。
 *
 * 设计取舍（不是想当然）：全项目 22 个颜色常量原本是 file-private 硬编码 val，
 * 改成「普通 getter 读顶层 snapshot state」后，Compose 在组合期会跟踪这次读取，
 * 切换主题即触发重组，**所有调用点一行都不用改**，也不依赖 @Composable get() 这类
 * 编译器特性。比逐处替换成 MaterialTheme.colorScheme.* 风险低得多。
 *
 * 色值取自现有亮色板（R11），暗色板按同一层级给出对比色；不做「只把背景刷黑」。
 */
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

/** 只读快照；写只走 applyAppTheme，避免外部绕过三档语义。 */
internal val appThemeDark: Boolean get() = _appThemeDark

internal val palette: Pal get() = if (_appThemeDark) DarkPal else LightPal

internal fun isSystemNight(c: Context): Boolean =
    (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** mode：0 跟随系统 / 1 浅色 / 2 深色。设置页拨动即调用，Ctx 只用于读系统夜间态。 */
internal fun applyAppTheme(c: Context, mode: Int) {
    _appThemeDark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemNight(c)
    }
}

/** 给未显式着色的 M3 组件（Switch 轨道 / Button 默认态）也套上同一套色。 */
@Composable
internal fun TingbenTheme(content: @Composable () -> Unit) {
    val dark = appThemeDark
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
