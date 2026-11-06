package com.tingben.app

/**
 * U1 · 应用级「壳」：顶层目的地、顶栏、下拉刷新条、V1 抽屉集中在这一层。
 *
 * 不手搓依据：Now in Android 的 NiaAppState / TopLevelDestination 思路 ——
 * 顶层目的地集中定义，页面只负责内容，导航归属不散落在各个 when 分支里。
 * 逐条见 docs/R32_全局UI重构_审计与联动表.md。
 *
 * R33 · U1 返工：顶栏 ☰ 改一级菜单、下拉刷新零文案、状态栏安全区由外层 Column 统一负责。
 *
 * R46 · V1 抽屉壳：
 * - ☰ 从 DropdownMenu 浮层改成打开 ModalNavigationDrawer（一整页左侧抽屉）；
 * - 底栏 3 Tab 收进抽屉一列，抽屉形状抄本地 reference/OfflineLLM/ChatScreen.kt:151-160；
 * - 左滑/右滑用官方 gesturesEnabled，不自写横向手势；返回键优先关抽屉由 App() 处理；
 * - 底栏删除后，底部安全区改由外层 Column 的 navigationBarsPadding() 负责。
 * 详见 docs/R46_V1抽屉壳_联动表.md。
 *
 * R51 · 外层 Drawer 改 DismissibleNavigationDrawer：抽屉展开时右侧工作页一起推走（联动），
 * 不再是 ModalNavigationDrawer 的覆盖式。本文件的 Sheet 与 6 个条目一行未改。
 *
 * R47 · 抽屉收窄：M3 ModalDrawerSheet 默认 320dp 对 6 个三五字标签过宽，
 * 用 requiredWidth(240.dp) 覆盖（社区常用 250dp / M2 上限 280dp 之间取值），
 * 条目字号、padding 同步收紧；左滑手势与返回键优先行为一行不动。
 *
 * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器、不碰任何数据层。
 */
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 顶层目的地下标。TAB_NONE = 当前页不属于任何顶层组（设置域），不高亮。 */
internal const val TAB_LIBRARY = 0
internal const val TAB_TASKS = 1
internal const val TAB_AUDIO = 2
internal const val TAB_NONE = -1

/** V1 · 抽屉 7 个目的地下标。前 3 个与 TAB_* 同值，方便直接复用 chrome.activeTab。 */
internal const val NAV_LIBRARY = 0
internal const val NAV_TASKS = 1
internal const val NAV_AUDIO = 2
internal const val NAV_SETTINGS = 3
internal const val NAV_MIXER = 4
internal const val NAV_MODELS = 5

/**
 * 一屏的「壳」描述。由 App() 里的 chromeOf(Screen) 生成，本层只消费。
 * activeTab 在 V1 里同时用作抽屉顶层选中态，语义不变。
 */
internal data class ChromeSpec(
    val title: String,
    val activeTab: Int = TAB_NONE,
    val showImport: Boolean = false,
)

private val DRAWER_TOP = listOf(
    NAV_LIBRARY to "书架",
    NAV_TASKS to "任务",
    NAV_AUDIO to "我的音频",
)

private val DRAWER_MORE = listOf(
    NAV_SETTINGS to "设置",
    NAV_MIXER to "调音台",
    NAV_MODELS to "模型",
)

/**
 * 统一顶栏：左侧 ☰ 打开抽屉、中间当前页标题、右侧仅书架显示导入 ＋。
 * 不显示产品名 / 产品定位 / 刷新按钮 / 任何操作提示文字。
 */
@Composable
internal fun AppTopBar(
    spec: ChromeSpec,
    onMenu: () -> Unit,
    onImport: () -> Unit,
    surface: Color,
    primary: Color,
) {
    Row(
        Modifier.fillMaxWidth().background(surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onMenu) {
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

/**
 * V1 · 左侧抽屉一列：原底栏 3 个顶层 Tab + 4 个设置域页面，调音台是独立一项。
 * 形状抄 reference/OfflineLLM/ChatScreen.kt 的 ModalNavigationDrawer + DrawerState；
 * 条目用官方 NavigationDrawerItem，不画产品名、不写操作教程。
 */
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
