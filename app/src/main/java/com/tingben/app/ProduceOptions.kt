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

private val OptCard: Color get() = palette.card
private val OptChip: Color get() = palette.primarySoft
private val OptChipOn: Color get() = palette.primarySoft
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

@Composable
fun <T> OptionRow(
    label: String,
    hint: String = "",
    options: List<Pair<T, String>>,
    current: T,
    onChange: (T) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (hint.isNotBlank()) Text(hint, fontSize = 12.sp, color = OptSub)
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
