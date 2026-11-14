package com.tingben.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingben.app.core.DescPresetStore

/**
 * R304 · 标签右边那两枚按钮：▾ 选已存的预设、＋添加 新建预设。
 * 「声音描述」(slot=voice_design) 与「风格描述」(slot=cv_style) 共用这一套。
 * 选择列表只显示简短描述；点中后把详细描述交给调用方填进输入框。
 */
@Composable
internal fun DescPresetRow(
    label: String,
    slot: String,
    onPick: (String) -> Unit,
    onClear: (() -> Unit)? = null,
    current: String = ""
) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(DescPresetStore.list(ctx, slot)) }
    var showPick by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var shortText by remember { mutableStateOf("") }
    var longText by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = palette.text)
        Spacer(Modifier.width(8.dp))
        // R307 · 「选择 ▾」合成一枚按钮：文字 + 小三角，不再只有一个孤零零的三角。
        Row(Modifier.height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.primarySoft)
            .clickable {
                items = DescPresetStore.list(ctx, slot)
                showPick = true
            }
            .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("选择", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = palette.primary)
            Spacer(Modifier.width(4.dp))
            Text("▾", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = palette.primary)
        }
        Spacer(Modifier.width(6.dp))
        // ＋ 添加
        Box(Modifier.height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.primarySoft)
            .clickable {
                shortText = ""
                longText = ""
                showAdd = true
            }
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("＋添加", fontSize = 12.sp,
                fontWeight = FontWeight.Medium, color = palette.primary)
        }
        Spacer(Modifier.weight(1f))
        if (onClear != null) {
            Text("清空", fontSize = 12.sp, color = Sub,
                modifier = Modifier.clickable { onClear() })
        }
    }

    if (showPick) {
        AlertDialog(
            onDismissRequest = { showPick = false },
            title = { Text("选一个「$label」预设", fontSize = 14.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("列表里只显示简短描述；点一下把详细描述填进输入框。",
                        fontSize = 11.sp, color = Sub)
                    Spacer(Modifier.height(8.dp))
                    if (items.isEmpty()) {
                        Text("还没有预设，先存一个", fontSize = 12.sp, color = Sub)
                        TextButton(onClick = { showPick = false; showAdd = true }) {
                            Text("＋添加预设 ›", fontSize = 12.sp)
                        }
                    } else {
                        items.forEach { p ->
                            // R307 · 列表只显示简短描述；点条子把「详细描述」交给调用方，
                            // 它才是真正喂进模型的那一份，简短版永远不进合成参数。
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { onPick(p.long); showPick = false },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(p.short, fontSize = 13.sp,
                                            color = palette.text)
                                        if (current.isNotBlank() && p.long == current) {
                                            Spacer(Modifier.width(8.dp))
                                            Text("在用", fontSize = 11.sp,
                                                color = palette.primary,
                                                modifier = Modifier
                                                    .background(palette.primarySoft,
                                                        RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp,
                                                        vertical = 2.dp))
                                        }
                                    }
                                }
                                // R307 · 右侧垃圾桶：点一下直接删，不弹「确定删除吗」。
                                Box(Modifier.size(36.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        DescPresetStore.delete(ctx, slot, p.id)
                                        items = DescPresetStore.list(ctx, slot)
                                    }, contentAlignment = Alignment.Center) {
                                    UiIconGlyph(UiIcon.DELETE, Warn, glyphSize = 18.dp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPick = false }) {
                    Text("关闭", fontSize = 12.sp)
                }
            },
            dismissButton = {}
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加「$label」预设", fontSize = 14.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = shortText,
                        onValueChange = { shortText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("简短描述（列表里显示）", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = longText,
                        onValueChange = { longText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("详细描述（真正喂给模型）", fontSize = 11.sp) },
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("两份都要填：简短的给你认，详细的才送进模型。",
                        fontSize = 11.sp, color = Sub)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = shortText.isNotBlank() && longText.isNotBlank(),
                    onClick = {
                    if (DescPresetStore.save(ctx, slot, shortText, longText)) {
                        items = DescPresetStore.list(ctx, slot)
                        showAdd = false
                    }
                }) { Text("保存", fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text("取消", fontSize = 12.sp)
                }
            }
        )
    }
}

/**
 * R350 · 第二种模式的音色表是模型自带英文名，中文短名按 Qwen3-TTS CustomVoice
 * 官方音色表的描述译，保留每个音色最能区分的特征（京味 / 成都 / 动感 / 美式 /
 * 日系 / 韩语），不再自己按「男声 / 女声 / 少年音」编一套。
 * 送进引擎的仍是模型原名，只在这里显示中文；没收录的名字原样显示，不猜、不硬翻。
 */
internal fun speakerCn(name: String): String = when (name.trim().lowercase()) {
    "vivian" -> "明亮女声"      // 明亮、略带锋芒的年轻女声
    "serena" -> "温暖女声"      // 温暖、柔和的年轻女声
    "uncle_fu" -> "醇厚男声"    // 低沉醇厚的成熟男声
    "dylan" -> "京味男声"       // 清亮自然的京味年轻男声（北京方言）
    "eric" -> "成都男声"        // 略带沙哑的活泼成都男声（四川方言）
    "ryan" -> "动感男声"        // 节奏感强的动感男声
    "aiden" -> "美式男声"       // 阳光清澈的美式男中音
    "ono_anna" -> "日系女声"    // 轻快俏皮的日系女声
    "sohee" -> "韩语女声"       // 温暖富有情感的韩语女声
    else -> name
}

/** R350 · 选中项给官方音色描述原文，中文短名的依据一眼可查、也能对上模型文档。 */
internal fun speakerOfficial(name: String): String = when (name.trim().lowercase()) {
    "vivian" -> "Vivian · 明亮、略带锋芒的年轻女声"
    "serena" -> "Serena · 温暖、柔和的年轻女声"
    "uncle_fu" -> "Uncle_Fu · 低沉醇厚的成熟男声"
    "dylan" -> "Dylan · 清亮自然的京味年轻男声"
    "eric" -> "Eric · 略带沙哑的活泼成都男声"
    "ryan" -> "Ryan · 节奏感强的动感男声"
    "aiden" -> "Aiden · 阳光清澈的美式男中音"
    "ono_anna" -> "Ono_Anna · 轻快俏皮的日系女声"
    "sohee" -> "Sohee · 温暖富有情感的韩语女声"
    else -> name
}
