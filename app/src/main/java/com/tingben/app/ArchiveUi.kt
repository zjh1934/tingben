package com.tingben.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tingben.app.core.RequestArchive
import com.tingben.app.diag.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R387 · 「请求文本」独立页（设置分类第八个入口）。
 *
 * 按 R354「首页只列入入口、子页各自成页」；按 R370 全站 Hi* 层级令牌；
 * 按 R383 **不自带 verticalScroll / LazyColumn** —— 设置页外壳是唯一滚动层，
 * 这里只铺普通 Column，展开到 100 条也只是把外壳撑长，不制造 infinity 高度。
 *
 * 一个外框卡片里左右两列：左＝跨网中继，右＝语音网关，默认各 10 条、可展开。
 * 每条：点一下展开全文、一枚「复制」；多选态给勾选位 +「复制选中 / 删除选中」；
 * 「一键清空」带二次确认。只展示文本，不展示音频、不回显 Token。
 */
@Composable
internal fun ArchiveScreen(c: Context) {
    var tick by remember { mutableStateOf(0) }
    var relayMore by remember { mutableStateOf(false) }
    var gatewayMore by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var openId by remember { mutableStateOf("") }
    var askClear by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    val counts = remember(tick) { RequestArchive.counts(c) }
    val relay = remember(tick, relayMore) {
        RequestArchive.show(c, RequestArchive.SRC_RELAY, relayMore)
    }
    val gateway = remember(tick, gatewayMore) {
        RequestArchive.show(c, RequestArchive.SRC_GATEWAY, gatewayMore)
    }

    fun refresh() {
        tick++
        picked = emptySet()
    }

    fun copy(entries: List<RequestArchive.Entry>) {
        if (entries.isEmpty()) {
            Toast.makeText(c, "这一列还没有请求文本", Toast.LENGTH_SHORT).show()
            return
        }
        val s = RequestArchive.copyText(entries)
        clip.setText(AnnotatedString(s))
        EventLog.log(EventLog.Kind.PIPELINE, "ARCHIVE_COPY", "复制 ${entries.size} 条")
        Toast.makeText(c, "已复制 ${entries.size} 条", Toast.LENGTH_SHORT).show()
    }

    @Composable
    fun EntryCard(e: RequestArchive.Entry) {
        val isOpen = openId == e.id
        val checked = e.id in picked
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(HiChipRadius))
                .background(palette.primarySoft.copy(alpha = 0.35f))
                .border(1.dp, palette.line, RoundedCornerShape(HiChipRadius))
                .clickable {
                    if (selecting) picked = if (checked) picked - e.id else picked + e.id
                    else openId = if (isOpen) "" else e.id
                }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = {
                    picked = if (it) picked + e.id else picked - e.id
                }, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(e.text, fontSize = HiCaptionSp, color = palette.text,
                    maxLines = if (isOpen) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(e.ts)) + " · " + e.chars + " 字",
                    fontSize = HiMicroSp, color = palette.sub)
            }
            if (!selecting) {
                TextButton(onClick = {
                    clip.setText(AnnotatedString(e.text))
                    EventLog.log(EventLog.Kind.PIPELINE, "ARCHIVE_COPY", "复制 1 条")
                    Toast.makeText(c, "已复制", Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)) {
                    Text("复制", fontSize = HiMicroSp)
                }
            }
        }
    }

    @Composable
    fun ColumnScope.Channel(
        title: String, badge: String, source: String,
        items: List<RequestArchive.Entry>, total: Int, more: Boolean,
        onMore: (Boolean) -> Unit
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
                color = palette.primary, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(badge, fontSize = HiMicroSp, color = palette.sub)
        }
        Spacer(Modifier.height(6.dp))
        if (items.isEmpty()) {
            Text("还没有请求", fontSize = HiMicroSp, color = palette.sub)
            Spacer(Modifier.height(8.dp))
            Text("这一列空着", fontSize = HiMicroSp, color = palette.sub)
            return@Channel
        }
        for (e in items) {
            EntryCard(e)
            Spacer(Modifier.height(6.dp))
        }
        if (total > RequestArchive.DEFAULT_SHOW) {
            TextButton(onClick = { onMore(!more) },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (more) "只看最近 10 条 ⌃" else "展开全部 $total 条 ›",
                    fontSize = HiMicroSp)
            }
        }
        if (!selecting) {
            TextButton(onClick = { copy(RequestArchive.list(c, source)) },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("复制本列", fontSize = HiMicroSp)
            }
        }
    }

    // ① 板块头：一句话说清只留文本、两个渠道、不进诊断
    CardBox {
        Text("请求文本", fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
            color = palette.text)
        Spacer(Modifier.height(5.dp))
        Text("每次过来合成的请求，只留下文字；音频与中间文件都不留。"
            + "左边是跨网中继，右边是局域网网关，默认各 10 条。",
            fontSize = HiCaptionSp, color = palette.sub)
        Spacer(Modifier.height(4.dp))
        Text("存在本机私有区，不进诊断日志、不进导出的日志、不备份；"
            + "最多各留 100 条，超了自动丢最老。",
            fontSize = HiMicroSp, color = palette.sub)
    }

    Spacer(Modifier.height(HiSectionGap))

    // ② 一个外框里左右两列
    CardBox {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Channel("跨网中继", "共 ${counts.first} 条",
                    RequestArchive.SRC_RELAY, relay, counts.first, relayMore,
                    { relayMore = it })
            }
            Column(Modifier.weight(1f)) {
                Channel("语音网关", "共 ${counts.second} 条",
                    RequestArchive.SRC_GATEWAY, gateway, counts.second, gatewayMore,
                    { gatewayMore = it })
            }
        }
    }

    Spacer(Modifier.height(HiSectionGap))

    // ③ 板块级动作：一键复制 / 多选 / 一键清空（清空带二次确认）
    CardBox {
        if (!selecting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    copy(RequestArchive.list(c, RequestArchive.SRC_RELAY) +
                         RequestArchive.list(c, RequestArchive.SRC_GATEWAY))
                }, Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50)) {
                    Text("一键复制", fontSize = HiCaptionSp)
                }
                OutlinedButton(onClick = { selecting = true; openId = "" },
                    Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50)) {
                    Text("多选", fontSize = HiCaptionSp)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { askClear = true }, Modifier.fillMaxWidth()) {
                Text("一键清空全部文本", fontSize = HiCaptionSp, color = palette.warn)
            }
        } else {
            Text("已选 ${picked.size} 条", fontSize = HiCaptionSp, color = palette.text)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val all = RequestArchive.list(c, RequestArchive.SRC_RELAY) +
                              RequestArchive.list(c, RequestArchive.SRC_GATEWAY)
                    copy(all.filter { it.id in picked })
                }, Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50)) {
                    Text("复制选中", fontSize = HiCaptionSp)
                }
                OutlinedButton(onClick = {
                    RequestArchive.delete(c, picked)
                    refresh()
                    selecting = false
                    Toast.makeText(c, "已删掉选中的文本", Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50)) {
                    Text("删除选中", fontSize = HiCaptionSp)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { selecting = false; picked = emptySet() },
                Modifier.fillMaxWidth()) {
                Text("退出多选", fontSize = HiCaptionSp, color = palette.sub)
            }
        }
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("清空全部请求文本？", fontSize = HiSectionSp) },
            text = {
                Text("两列一起清掉，共 " + (counts.first + counts.second) +
                    " 条。只删文字留存，不影响合成功能，也不删任何音频。",
                    fontSize = HiCaptionSp)
            },
            confirmButton = {
                Button(onClick = {
                    RequestArchive.clear(c)
                    askClear = false
                    selecting = false
                    refresh()
                    Toast.makeText(c, "已清空", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = palette.warn)) {
                    Text("确认清空", fontSize = HiCaptionSp)
                }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }) {
                    Text("取消", fontSize = HiCaptionSp)
                }
            }
        )
    }

    // 悬浮进度球常驻左下，与其它二级页同一条安全区
    Spacer(Modifier.height(72.dp))
}
