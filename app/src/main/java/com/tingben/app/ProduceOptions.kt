package com.tingben.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R20 · 「本次制作」预设页的折叠分组与档位控件。
 *
 * 不手搓依据（逐条见 docs/P1_制作准备页_联动表.md §1）：
 * - state hoisted 的 ExpandableSection：ProAndroidDev「Creating Expandable Sections with Compose」[citation:10]
 *   （这里用简单 if 展开而非 AnimatedVisibility，避免为动画再引一个依赖）
 * - header / child 折叠结构：SSComposeExpandableListView（MIT，R18 已用于分段看台）
 * - selectable Row 档位形态：OfflineLLM `SettingsScreen.kt`
 * - 「恢复默认」：PocketPal `CompletionSettings.tsx` 的 serverDefaultState/reset
 *
 * 与主界面解耦：不引用 MainActivity.kt 里文件私有的 CardBox / 颜色常量。
 */
private val OptCard = Color(0xFFFFFFFF)
private val OptChip = Color(0xFFF3F5FA)
private val OptChipOn = Color(0xFFEEF0FE)
private val OptPrimary = Color(0xFF4338CA)
private val OptSub = Color(0xFF6B7280)

@Composable
fun ExpandGroup(
    title: String,
    subtitle: String = "",
    defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var open by remember(title) { mutableStateOf(defaultOpen) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = OptCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (subtitle.isNotBlank()) Text(subtitle, fontSize = 11.sp, color = OptSub)
                }
                Text(if (open) "收起 ▲" else "展开 ▼", fontSize = 11.5.sp, color = OptPrimary)
            }
            if (open) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** 一行 2~3 个档位胶囊，选中高亮。泛型让枚举/整数档位共用一套控件。 */
@Composable
fun <T> OptionRow(
    label: String,
    hint: String = "",
    options: List<Pair<T, String>>,
    current: T,
    onChange: (T) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        if (hint.isNotBlank()) Text(hint, fontSize = 10.5.sp, color = OptSub)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            options.forEach { (v, name) ->
                val on = v == current
                Box(
                    Modifier.weight(1f).padding(end = 5.dp)
                        .background(if (on) OptChipOn else OptChip, RoundedCornerShape(10.dp))
                        .clickable { onChange(v) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(name, fontSize = 12.sp,
                        color = if (on) OptPrimary else OptSub,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
