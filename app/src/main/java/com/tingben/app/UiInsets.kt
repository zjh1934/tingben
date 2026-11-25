package com.tingben.app

import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * R396 · 布局契约（四向 inset 单一入口）。
 *
 * 依据（2025–2026 现行规范）：
 *  · Android 15（SDK 35）起强制 edge-to-edge、Android 16 起不能退出；
 *    本项目 targetSdk = 35，正好落在这一档。
 *  · 金标联盟 ITGSA《导航条适配公告》：内容延伸到屏幕底部，小白条透明浮在
 *    内容之上；15+ 用 enableEdgeToEdge，15 以下走「布局延伸 → 系统栏透明 →
 *    Padding 主动避让」三步；截止 2026-10-31 未适配应用商店打标。
 *  · Compose：safeDrawing = 系统栏 + 挖孔 + 键盘；safeGestures 让可拖控件
 *    避开系统手势区；列表用 contentPadding 保证最后一项仍可点。
 *  · 安全区三层：背景可延伸、内容要避让、控件要有安全点击边界。
 *
 * 这里刻意用 foundation 里稳定的 Modifier 逐个组合出等价语义，而不是直接
 * 依赖较新 BOM 才有的 WindowInsets.safeDrawing 符号 —— 阶段 2 真编译坐实
 * 依赖版本后再决定是否换成官方符号。若 displayCutoutPadding 在该 BOM 下
 * 不可用，退化为「statusBarsPadding 已包含状态栏区挖孔」并在守门里登记，
 * 不允许悄悄删掉避让。
 *
 * R398/R399 · 阶段 3 接入契约（R399 已落代码，由 rc + main 双真编译验收）：
 *   gradle/libs.versions.toml 里 androidx-activity-compose = 1.9.3、
 *   androidx-core-ktx = 1.13.1，均满足 ComponentActivity.enableEdgeToEdge()
 *   （activity 1.8+ / core 1.13+）的可用下限。
 *   已按此写法接入：MainActivity.onCreate 的 super 之后第一行
 *     enableEdgeToEdge()   // import androidx.activity.enableEdgeToEdge
 *   系统栏图标随浅深仍由 TingbenTheme 的 DisposableEffect 接管。
 *   前置条件：rc + main 两次真编译同时验证本文件四个 Modifier 与
 *   displayCutoutPadding() 在该 BOM 下确实可解析；沙盒无 Android SDK，
 *   本地静态绿不等于编译绿。守门 r398 验「依赖满足 + 接入在位 + import 在位」，
 *   防止有人把接入又摘掉。
 */

/** 页面主内容：等价于 WindowInsets.safeDrawing（系统栏 + 摄像头挖孔 + 键盘）。 */
fun Modifier.tbContentInsets(): Modifier = this
    .statusBarsPadding()
    .displayCutoutPadding()
    .navigationBarsPadding()
    .imePadding()

/**
 * 底部操作条 / 播放器收起条：吃导航栏 + 键盘，并避开系统手势区。
 * 之前按固定 y 画到 H-24，正好压在 24dp 小白条上；改由 inset 决定，
 * 不再出现「底部按钮与手势条抢位置」。
 */
fun Modifier.tbBottomActionInsets(): Modifier = this
    .navigationBarsPadding()
    .imePadding()

/** 列表内容区：最后一项仍可点到（配合 LazyColumn 的 contentPadding 使用）。 */
fun Modifier.tbListInsets(): Modifier = this
    .navigationBarsPadding()
    .imePadding()

internal val TbMinTouch = 44.dp
internal val TbGestureBar = 24.dp
