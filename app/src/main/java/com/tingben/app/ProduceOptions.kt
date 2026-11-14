package com.tingben.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private val OptCard: Color get() = palette.card
// R320 · 原来选中与未选中底色都取 primarySoft，四个档位看着全一样，
// 只能靠文字深浅猜选了哪个。现在未选中淡底、选中实底加一圈主色描边。
private val OptChip: Color get() = palette.primarySoft.copy(alpha = 0.38f)
private val OptChipOn: Color get() = palette.primarySoft
private val OptPrimary: Color get() = palette.primary
private val OptSub: Color get() = palette.sub

@Composable
fun ExpandGroup(
    title: String,
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = OptCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = OptSub)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * R341 · 合成设置的四组参数原来是「制作设置页」时代的裸控件：
 * 粗体标题 + 灰字 + 三枚等宽色块连着排，和设置页 SettingLine /
 * 主题三段选择器不是一套语言，看着像外挂进来的。
 * 现在对齐设置页：32dp 前导图标槽、标题右端亮出当前值、下方一条
 * 柔底分段选择器（选中实底 + 主色描边），与「主题」那一行完全同款。
 * 调用签名、label / hint / options 一字未改（r114/r197/r221/r231 按它定位）。
 */
@Composable
fun <T> OptionRow(
    label: String,
    hint: String = "",
    options: List<Pair<T, String>>,
    current: T,
    onChange: (T) -> Unit
) {
    val currentName = options.firstOrNull { it.first == current }?.second ?: ""
    val icon = when {
        label.contains("静音") -> UiIcon.WAV
        label.contains("重做") -> UiIcon.RESUME
        label.contains("拼成") -> UiIcon.MERGE
        label.contains("接缝") -> UiIcon.AUDIO
        else -> UiIcon.SETTINGS
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp)
                .background(OptPrimary.copy(alpha = 0.10f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center) {
                UiIconGlyph(icon, OptPrimary, 18.dp)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.text, modifier = Modifier.weight(1f))
                    Text(currentName, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = OptPrimary)
                }
                if (hint.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(hint, fontSize = 11.sp, color = OptSub, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        // 和设置页「跟随系统 / 浅色 / 深色」同一套分段选择器
        Row(Modifier.fillMaxWidth()
            .background(OptChip, RoundedCornerShape(10.dp))
            .padding(3.dp)) {
            options.forEach { (v, name) ->
                val on = v == current
                Box(Modifier.weight(1f).height(32.dp)
                    .background(if (on) OptCard else Color.Transparent,
                        RoundedCornerShape(8.dp))
                    .let { if (on) it.border(1.dp, OptPrimary,
                        RoundedCornerShape(8.dp)) else it }
                    .clickable { onChange(v) },
                    contentAlignment = Alignment.Center) {
                    Text(name, fontSize = 12.sp,
                        color = if (on) OptPrimary else OptSub,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
