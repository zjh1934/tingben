package com.tingben.app

/**
 * U1 · 应用级「壳」：顶层目的地、顶栏、底栏、下拉刷新条集中在这一层。
 *
 * 不手搓依据：Now in Android 的 NiaAppState / TopLevelDestination 思路 ——
 * 顶层目的地集中定义，页面只负责内容，导航归属不散落在各个 when 分支里；
 * 顶栏左侧导航图标用官方 CenterAlignedTopAppBar 的 navigationIcon 形态。
 * 逐条见 docs/R32_全局UI重构_审计与联动表.md。
 *
 * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器、不碰任何数据层。
 * 颜色由调用方传入，避免在这里复制一份设计常量造成两份真值源。
 *
 * ⚠️ U1 边界：下拉刷新沿用原手势阈值（acc > 55f），只把它抽成可复用组件，
 * 没有换成 Material3 PullToRefreshBox —— 换滚动容器会牵动 4 个页面，单列 U2。
 */
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 顶层目的地下标。TAB_NONE = 当前页不属于任何顶层组（设置域），底栏不高亮。 */
internal const val TAB_LIBRARY = 0
internal const val TAB_TASKS = 1
internal const val TAB_AUDIO = 2
internal const val TAB_NONE = -1

/**
 * 一屏的「壳」描述。由 App() 里的 chromeOf(Screen) 生成，本层只消费。
 * activeTab / showImport 取代原先散在顶栏和底栏里的 4 处 screen == ... 判断。
 */
internal data class ChromeSpec(
    val title: String,
    val activeTab: Int = TAB_NONE,
    val showImport: Boolean = false,
)

private val TOP_TABS = listOf(
    TAB_LIBRARY to "书架",
    TAB_TASKS to "任务",
    TAB_AUDIO to "音频",
)

/**
 * 统一顶栏：左侧设置入口（不再用底栏第 4 个 Tab）、中间当前页标题、右侧仅书架显示导入。
 * 删掉了产品名 + 产品定位 + 文字刷新按钮 —— 它们与状态栏/通知重叠且是「边用边读」的注释。
 */
@Composable
internal fun AppTopBar(
    spec: ChromeSpec,
    onSettings: () -> Unit,
    onImport: () -> Unit,
    surface: Color,
    primary: Color,
) {
    Row(
        Modifier.fillMaxWidth().background(surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSettings) {
            // 不引入 material-icons-extended 依赖：文字字形即可，离线构建零新增依赖
            Text("☰", fontSize = 19.sp, color = primary)
        }
        Text(
            spec.title,
            fontSize = 17.sp,
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

/** 3 个顶层 Tab。设置移走后底栏不再承担「设置」入口。 */
@Composable
internal fun AppBottomBar(
    active: Int,
    onTab: (Int) -> Unit,
    surface: Color,
    primary: Color,
    primarySoft: Color,
    sub: Color,
) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().background(surface).padding(vertical = 6.dp)
    ) {
        TOP_TABS.forEach { (idx, label) ->
            AppTab(label, active == idx, Modifier.weight(1f),
                primary, primarySoft, sub) { onTab(idx) }
        }
    }
}

@Composable
private fun AppTab(
    label: String,
    on: Boolean,
    mod: Modifier,
    primary: Color,
    primarySoft: Color,
    sub: Color,
    click: () -> Unit,
) {
    Column(
        mod.clickable { click() }.padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .background(if (on) primarySoft else Color.Transparent, RoundedCornerShape(50))
                .padding(horizontal = 13.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = if (on) primary else sub
            )
        }
    }
}

/**
 * 下拉刷新条。把原先写在 LibraryScreen 里的手搓手势抽到壳层，供后续页面一行接入。
 * 阈值与旧实现完全一致（累计下滑 > 55f 触发），行为等价，不改成 PullToRefreshBox。
 */
@Composable
internal fun RefreshStrip(
    onRefresh: () -> Unit,
    primary: Color,
    primarySoft: Color,
    sub: Color,
) {
    val c = LocalContext.current
    var pulled by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (pulled) 38.dp else 26.dp)
            .background(if (pulled) primarySoft else Color.Transparent, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = {
                        if (acc > 55f) {
                            onRefresh()
                            Toast.makeText(c, "已刷新", Toast.LENGTH_SHORT).show()
                        }
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
        Text(
            if (pulled) "松开即可刷新" else "下拉刷新",
            fontSize = 11.5.sp,
            color = if (pulled) primary else sub
        )
    }
}
