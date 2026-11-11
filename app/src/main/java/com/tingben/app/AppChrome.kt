package com.tingben.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        // R169 · 真齿轮轮廓：8 枚方齿与轮体连成一条起伏闭合路径。
        // 第一版只是把射线加粗，画出来仍像太阳/亮度；用户要的是齿轮（点它进设置）。
        val teeth = 8
        // Double 运算完再转 Float：Kotlin 不允许 Float × Double / Float ÷ Int
        // 的隐式提升，写成 2f * PI 真编译会直接报错（静态脚本看不见）。
        val step = (2.0 * kotlin.math.PI / teeth).toFloat()
        val root = r * 0.68f
        val tip = r * 0.96f
        val gear = Path()
        var first = true
        for (i in 0 until teeth) {
            val a = i * step
            for ((ang, rad) in listOf(
                a + step * 0.00f to root,
                a + step * 0.18f to tip,
                a + step * 0.32f to tip,
                a + step * 0.50f to root
            )) {
                val x = cx + kotlin.math.cos(ang) * rad
                val y = cy + kotlin.math.sin(ang) * rad
                if (first) { gear.moveTo(x, y); first = false } else gear.lineTo(x, y)
            }
        }
        gear.close()
        drawPath(gear, color = tint,
            style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
        drawCircle(tint, radius = r * 0.26f, center = center,
            style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AppTopBar(
    spec: ChromeSpec,
    onMenu: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    surface: Color,
    primary: Color,
    onBack: (() -> Unit)? = null,
    // R226 · 二级页可把操作挂到书名同一排的右侧，不再单独占一行。
    trailing: @Composable RowScope.() -> Unit = {},
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
            // R243 · 书名太长向左滚动，不换行把顶栏撑宽 / 不压掉右侧按钮
            Text(
                spec.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = primary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).basicMarquee()
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
        trailing()
    }
}

/**
 * R230 · 抽屉壳按常见侧滑抽屉重排：
 *   · 顶部标题单独一行，「导入」独占一整排（主页右上角 ＋ 撤掉后这里是常驻入口）
 *   · 中间是书列表
 *   · 左下角一枚设置（主页右上角齿轮撤掉后收在这里）
 * 模型 / 调音台 / 任务 / 输入朗读仍各归其位，不再往抽屉塞功能菜单。
 */
/**
 * R244 · 顶栏字按钮改代码自绘图标：混音推子 / 任务清单 / 停止 / 播放。
 * 用户口径「几竖加几竖里面有几个点」「三角和两竖」；全部 Canvas 画、不引图标库，
 * 深浅主题随调用方传的 tint。界面上不再出现文字，无障碍文案走 semantics。
 * internal 是因为 MainActivity 的顶栏要跨文件调用；本文件不参与 ui_sources 合并视图。
 */
@Composable
internal fun MixerIcon(tint: Color, label: String = "调音",
                       modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier.semantics { contentDescription = label }) {
        val w = size.width; val h = size.height
        val sw = 2.dp.toPx()
        for (i in 0..2) {
            val x = w * (0.20f + 0.30f * i)
            // R246 · drawLine 没有 (Color, Offset, Offset, Stroke) 重载，
            // 真编译报 None of the following candidates is applicable
            drawLine(tint, Offset(x, h * 0.08f), Offset(x, h * 0.92f),
                strokeWidth = sw, cap = StrokeCap.Round)
        }
        // 三根推子上各一枚滑块，高度错开才像调音台
        val ys = listOf(0.32f, 0.62f, 0.44f)
        for (i in 0..2) {
            drawCircle(tint, radius = w * 0.11f,
                center = Offset(w * (0.20f + 0.30f * i), h * ys[i]))
        }
    }
}

@Composable
internal fun TasksIcon(tint: Color, label: String = "任务",
                       modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier.semantics { contentDescription = label }) {
        val w = size.width; val h = size.height
        val sw = 2.dp.toPx()
        for (i in 0..2) {
            val y = h * (0.22f + 0.28f * i)
            drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.16f, y))
            drawLine(tint, Offset(w * 0.34f, y), Offset(w * 0.88f, y),
                strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun StopIcon(tint: Color, label: String = "停止",
                      modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier.semantics { contentDescription = label }) {
        val w = size.width; val h = size.height
        val sq = Path().apply {
            moveTo(w * 0.26f, h * 0.26f); lineTo(w * 0.74f, h * 0.26f)
            lineTo(w * 0.74f, h * 0.74f); lineTo(w * 0.26f, h * 0.74f); close()
        }
        drawPath(sq, color = tint)
    }
}

@Composable
internal fun PlayIcon(tint: Color, label: String = "继续",
                      modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier.semantics { contentDescription = label }) {
        val w = size.width; val h = size.height
        val tri = Path().apply {
            moveTo(w * 0.28f, h * 0.14f); lineTo(w * 0.86f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.86f); close()
        }
        drawPath(tri, color = tint)
    }
}

@Composable
internal fun AppDrawerSheet(
    title: String,
    onImport: () -> Unit,
    onSettings: () -> Unit,
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
            // R243 · 抽屉顶上「我的书」是废话，标题为空就整行不渲染
            if (title.isNotEmpty()) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = primary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
            }
            // R230 · 导入独占一排，不再跟标题挤同一行
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("＋ 导入一本书", fontSize = 13.sp)
            }
            Box(Modifier.weight(1f)) { content() }
            // R230 · 设置入口收进抽屉左下角
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSettings) {
                    // R247 · ⚙ 字符换成 R162 就画好的真齿轮，文字保留
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GearIcon(primary)
                        Spacer(Modifier.width(6.dp))
                        Text("设置", fontSize = 13.sp, color = primary)
                    }
                }
            }
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
            // R178 · 主页改成输入框后书列表搬进抽屉，compact 模式原来不渲染这条
            // 刷新热区，等于把全软件唯一的刷新入口弄丢了。现在常显，且下拉之外
            // 点一下也能刷新：整页下拉手势与 LazyColumn 滚动抢事件，风险高于收益，
            // 不硬上；「刷新能力不丢」这条按可见热区 + 双触发方式兑现。
            .clickable { onRefresh() }
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
