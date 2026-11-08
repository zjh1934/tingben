package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

// R162 · 抽屉不再是一组功能导航项，而是「我导进去的书」。
// 这几个 id 只留着给旧状态恢复与历史脚本兼容，界面一处都不再渲染它们。
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
    val showSettings: Boolean = false,
    // 统一层级：根级页没有返回键，二级页一律由顶栏给同一枚返回键。
    val canBack: Boolean = false,
)

// 代码绘制的返回箭头，不再用「‹」字符当图标。
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

// R162 · 右上角齿轮：代码绘制，不用「⚙」字符，深浅主题都随色板取色。
@Composable
private fun GearIcon(tint: Color) {
    Canvas(modifier = Modifier.size(21.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        val center = Offset(cx, cy)
        for (i in 0 until 8) {
            val a = (i * kotlin.math.PI.toFloat() / 4f)
            val c = kotlin.math.cos(a)
            val s = kotlin.math.sin(a)
            drawLine(
                color = tint,
                start = Offset(cx + c * r * 0.66f, cy + s * r * 0.66f),
                end = Offset(cx + c * r * 0.96f, cy + s * r * 0.96f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawCircle(tint, radius = r * 0.56f, center = center,
            style = Stroke(width = 2.dp.toPx()))
        drawCircle(tint, radius = r * 0.20f, center = center,
            style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
internal fun AppTopBar(
    spec: ChromeSpec,
    onMenu: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
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
        // ☰ 打开的是书抽屉，不再是功能菜单。
        IconButton(onClick = onMenu) {
            Text("☰", fontSize = 20.sp, color = primary)
        }
        // R164 · 首页不显示品牌名（「听本」是画蛇添足），二级页仍显示页面名。
        if (spec.title.isNotEmpty()) {
            Text(
                spec.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = primary,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (spec.showSettings) {
            IconButton(onClick = onSettings) { GearIcon(primary) }
        }
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

/**
 * R162 · 抽屉壳：标题 + 一个导入按钮 + 调用方给的书列表。
 * 功能导航项（任务 / 调音台 / 模型 / 输入朗读 / 设置）全部撤掉：
 *   · 设置与导入在右上角齿轮 / ＋
 *   · 模型收进设置页那张卡
 *   · 调音台只在合成前弹层
 *   · 任务能力在书里第二列与开软件弹窗
 *   · 输入朗读就是主页本身
 */
@Composable
internal fun AppDrawerSheet(
    title: String,
    onImport: () -> Unit,
    surface: Color,
    primary: Color,
    sub: Color,
    content: @Composable () -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.requiredWidth(272.dp),
        drawerContainerColor = surface
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Text("＋ 导入", fontSize = 12.sp, color = primary)
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** 书架那条小横条：按住往下拖一段刷新。R155 查实全软件只有这一处刷新手势。 */
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
