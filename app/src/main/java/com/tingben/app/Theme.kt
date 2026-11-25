package com.tingben.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat

internal data class Pal(
    val bg: Color, val card: Color, val primary: Color, val primarySoft: Color,
    val sub: Color, val good: Color, val line: Color, val warn: Color,
    val text: Color, val onPrimary: Color, val grey: Color,
    // I2 · 警示色底上的前景：浅色用白字，深色必须换深字。
    // 原来写死 Color.White，深色下 Warn 是亮黄 #FBBF24，白字对比只有 1.67:1。
    val onWarn: Color,
    // R396 · 语义色角色：PRIMARY / SECONDARY / TERTIARY / DANGER 四种角色，
    // 浅深只是同一角色的两套取值。SECONDARY 之前在深色下是「soft 深底 + 浅紫字」，
    // 看起来像禁用；TERTIARY 给常态批量动作，避免把警示色用在「一键清空」入口；
    // DANGER 只留给确认弹窗的最终按钮（见 UiSpec.dangerAllowed）。
    val btnSecondary: Color, val onBtnSecondary: Color,
    val btnTertiary: Color, val onBtnTertiary: Color, val btnTertiaryLine: Color
)

internal val LightPal = Pal(
    bg = Color(0xFFF4F6FB), card = Color(0xFFFFFFFF), primary = Color(0xFF4338CA),
    primarySoft = Color(0xFFEEF0FE), sub = Color(0xFF6B7280), good = Color(0xFF0E9F6E),
    line = Color(0xFFE7EAF2), warn = Color(0xFFB45309), text = Color(0xFF1B2030),
    onPrimary = Color(0xFFFFFFFF), grey = Color(0xFFB9BFCB),
    onWarn = Color(0xFFFFFFFF),
    btnSecondary = Color(0xFFEEF0FE), onBtnSecondary = Color(0xFF4338CA),
    btnTertiary = Color(0xFFFFFFFF), onBtnTertiary = Color(0xFF4338CA),
    btnTertiaryLine = Color(0xFFC7CEE2)
)

// I2 · 用户实证「开深色模式以后，字体颜色和底色感觉就不好看」。
// 客观测过对比度：旧色板 卡片/底=1.13、柔底/卡=1.30，层次几乎分不出来；
// 亮黄警示底上的白字只有 1.67:1，主色底上的白字 1.99:1，基本看不见。
// 新色板保持深蓝灰调性，把卡片、柔底、分隔线逐层提亮并重算全部配对：
// 正文/卡 12.34、次要/卡 6.85、主色/柔底 5.38、卡/底 1.29、柔底/卡 1.36。
// R164 实证补漏：上次只修了写死白字与主色块，漏掉分段格子里的小序号 ——
// 深色未生成格底 #54607F 配 sub 只有 2.93:1，已生成格底 #326F75 配 primary
// 只有 2.87:1。序号改取 text 后分别 5.29 / 4.84，才真看得清。
// R167 · 纠正上一轮的偏差：用户要的是「深色下字体就是白色、对比更明显」，
// 我上一轮只把主文字给纯白、把 48 处 Sub 留成提亮的灰，还拿对比度解释了一通。
// 现在深底上的常规文字（text + sub）统一纯白，层次交给字号 / 字重 / 底色块，
// 不再靠灰度分主次。唯一例外：亮黄警示底、主色按钮底上仍用深字
// （onWarn / onPrimary），白字压亮黄只有 1.67:1，物理上读不出来。
// grey 仍保留：它是未生成格子的底色，不是文字前景，刷白会让格子消失。
internal val DarkPal = Pal(
    bg = Color(0xFF0E1117), card = Color(0xFF222936), primary = Color(0xFFA5B4FC),
    primarySoft = Color(0xFF313C63), sub = Color(0xFFFFFFFF), good = Color(0xFF34D399),
    line = Color(0xFF3B455A), warn = Color(0xFFFBBF24), text = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF10131A), grey = Color(0xFF7E8AA0),
    onWarn = Color(0xFF1A1206),
    // 深色：次按钮底提亮到 #3D4A72、前景改纯白（不再浅紫压深蓝像禁用）；
    // 三级按钮 card 底 + 白字 + 提亮描边，对比全部过 WCAG 2.2 AA 4.5:1。
    btnSecondary = Color(0xFF3D4A72), onBtnSecondary = Color(0xFFFFFFFF),
    btnTertiary = Color(0xFF222936), onBtnTertiary = Color(0xFFFFFFFF),
    btnTertiaryLine = Color(0xFF5C6A85)
)

private var _appThemeDark by mutableStateOf(false)

internal val appThemeDark: Boolean get() = _appThemeDark

internal val palette: Pal get() = if (_appThemeDark) DarkPal else LightPal

// R370 · 全站视觉层级令牌：主页、目录、任务、设置、模型、调音、输入朗读、
// 播放器与抽屉共用同一套「标题 / 分区 / 正文 / 注释」尺寸和留白。
// 以前每个页面各写一个数字，同一个层级在七八个页面里能差 2~3sp；
// 现在只在这里定义一次，改一处全站跟着走，功能与逻辑一条不动。
internal val HiPagePad = 16.dp
internal val HiCardPad = 16.dp
internal val HiCardRadius = 18.dp
internal val HiChipRadius = 14.dp
internal val HiSectionGap = 14.dp
internal val HiTitleSp = 18.sp
internal val HiSectionSp = 14.sp
internal val HiBodySp = 14.sp
internal val HiCaptionSp = 12.sp
internal val HiMicroSp = 11.sp
internal const val HiHairAlpha = 0.55f

// 没有显式写 fontSize 的 Text 一律走这套排版，层级由字重 + 字号承担，
// 不再靠「把字调灰」分主次（深色下灰字看不清，I2/R167 已实证）。
private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = HiTitleSp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = HiSectionSp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = HiBodySp),
    bodyMedium = TextStyle(fontSize = HiSectionSp),
    bodySmall = TextStyle(fontSize = HiCaptionSp),
    labelLarge = TextStyle(fontSize = HiCaptionSp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = HiMicroSp)
)

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
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
