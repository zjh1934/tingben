package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val TAB_LIBRARY = 0
internal const val TAB_TASKS = 1
internal const val TAB_AUDIO = 2
internal const val TAB_NONE = -1

internal const val NAV_LIBRARY = 0
internal const val NAV_TASKS = 1
internal const val NAV_AUDIO = 2
internal const val NAV_SETTINGS = 3
internal const val NAV_MIXER = 4
internal const val NAV_MODELS = 5
internal const val NAV_INPUT = 6

internal data class ChromeSpec(
    val title: String,
    val activeTab: Int = TAB_NONE,
    val showImport: Boolean = false,
    // 统一层级：根级页没有返回键，二级页一律由顶栏给同一枚返回键。
    // 以前各页自己塞「‹ 目录 / ‹ 任务 / ‹ 返回」，有有无无，位置文案都不齐。
    val canBack: Boolean = false,
)

private val DRAWER_TOP = listOf(
    NAV_LIBRARY to "书架",
    NAV_TASKS to "任务",
    NAV_MIXER to "调音台",
    NAV_INPUT to "输入朗读",
)
// 「我的音频」独立入口已撤（R131）：音频管理归到书的目录页第三列。
// NAV_AUDIO / TAB_AUDIO 常量留着，供旧状态恢复与历史索引兼容，不再挂任何抽屉项。

private val DRAWER_MORE = listOf(
    NAV_SETTINGS to "设置",
    NAV_MODELS to "模型",
)

// 代码绘制的返回箭头，不再用「‹」字符当图标（R130 起逐步替换全工程字符图标）。
@Composable
private fun BackChevron(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.62f, h * 0.22f)
            lineTo(w * 0.34f, h * 0.50f)
            lineTo(w * 0.62f, h * 0.78f)
        }
        drawPath(
            p, color = tint,
            style = Stroke(
                width = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
internal fun AppTopBar(
    spec: ChromeSpec,
    onMenu: () -> Unit,
    onImport: () -> Unit,
    surface: Color,
    primary: Color,
    onBack: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().background(surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (spec.canBack && onBack != null) {
            IconButton(onClick = onBack) { BackChevron(primary) }
        }
        IconButton(onClick = onMenu) {
            Text("☰", fontSize = 20.sp, color = primary)
        }
        Text(
            spec.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = primary,
            modifier = Modifier.weight(1f)
        )
        if (spec.showImport) {
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 7.dp)
            ) {
                Text("＋", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun AppDrawerSheet(
    selected: Int,
    onNavigate: (Int) -> Unit,
    surface: Color,
    primary: Color,
    sub: Color,
) {
    ModalDrawerSheet(
        modifier = Modifier.requiredWidth(240.dp),
        drawerContainerColor = surface
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            DRAWER_TOP.forEach { (idx, label) ->
                DrawerRow(label, selected == idx, primary, sub) { onNavigate(idx) }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = palette.line
            )
            DRAWER_MORE.forEach { (idx, label) ->
                DrawerRow(label, selected == idx, primary, sub) { onNavigate(idx) }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    on: Boolean,
    primary: Color,
    sub: Color,
    click: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) primary else sub
            )
        },
        selected = on,
        onClick = click,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp)
    )
}

@Composable
internal fun RefreshStrip(
    onRefresh: () -> Unit,
    primary: Color,
    primarySoft: Color,
    sub: Color,
) {
    var pulled by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (pulled) 30.dp else 22.dp)
            .background(if (pulled) primarySoft else Color.Transparent, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = {
                        if (acc > 55f) onRefresh()
                        pulled = false
                        acc = 0f
                    },
                    onVerticalDrag = { _, dy ->
                        if (dy > 0) {
                            acc += dy
                            pulled = acc > 20f
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(if (pulled) 44.dp else 26.dp)
                .height(3.dp)
                .background(if (pulled) primary else sub.copy(alpha = 0.35f), RoundedCornerShape(50))
        )
    }
}
