package com.tingben.app

/**
 * U1 · 应用级「壳」：顶层目的地、顶栏、底栏、下拉刷新条集中在这一层。
 *
 * 不手搓依据：Now in Android 的 NiaAppState / TopLevelDestination 思路 ——
 * 顶层目的地集中定义，页面只负责内容，导航归属不散落在各个 when 分支里。
 * 逐条见 docs/R32_全局UI重构_审计与联动表.md。
 *
 * R33 · U1 返工：
 * - 顶栏 ☰ 改成一级导航菜单（设置 / 调音台 / 模型 / 角色音色），调音台不再藏在二层；
 * - 下拉刷新条去掉全部提示文字与 Toast，只留一条随手势变色的细横杠；
 * - 状态栏安全区由 App() 外层 Column 的 statusBarsPadding() 统一负责，本层不重复加。
 *
 * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器、不碰任何数据层。
 */
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

private val MENU_ITEMS = listOf("设置", "调音台", "模型", "角色音色")

/**
 * 统一顶栏：左侧 ☰ 一级菜单、中间当前页标题、右侧仅书架显示导入 ＋。
 * 不显示产品名 / 产品定位 / 刷新按钮 / 任何操作提示文字。
 */
@Composable
internal fun AppTopBar(
    spec: ChromeSpec,
    onMenu: (Int) -> Unit,
    onImport: () -> Unit,
    surface: Color,
    primary: Color,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                // 不引入 material-icons-extended 依赖：文字字形即可，离线构建零新增依赖
                Text("☰", fontSize = 19.sp, color = primary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MENU_ITEMS.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp) },
                        onClick = { menuOpen = false; onMenu(i) }
                    )
                }
            }
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

/** 3 个顶层 Tab。设置入口在顶栏菜单，底栏不再承担「设置」。 */
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
 * 下拉刷新条：沿用原手势阈值（累计下滑 > 55f 触发），零文字、零 Toast。
 * 视觉反馈只有一条随下拉变长变色的细横杠 —— 基础操作不需要写在界面上教人。
 */
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
