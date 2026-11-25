package com.tingben.app

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tingben.app.core.RequestArchive
import com.tingben.app.diag.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * R390 · 「请求文本」页改版（1.1.52 真机截图实证的问题）。
 *
 * ① 两个渠道不再「中间劈开左右并排」：改成整宽选项卡，点一下或左右滑都能切换，
 *    当前渠道铺满横屏，列表是这一页的重点。截图中右列 0 条空半屏的难看形态消失。
 * ② 说明文字不折叠、不藏小问号，但降级成页面底部两行微字：不占首屏、不抢列表，
 *    主次一眼分明。按 R370 全站 Hi* 令牌；按 R383 不自带滚动，外壳是唯一滚动层。
 * ③ 功能守恒：每条一键复制 / 展开全文、默认 10 条可展开到 100、一键复制、
 *    多选（复制选中 / 删除选中）、一键清空带二次确认、两渠道一起清。
 *
 * R391 · 1.1.53 真机反馈「上下滑滑不动、左右滑很生硬」：
 * 旧 detectDragGestures 会连竖向位移一起消费，外壳唯一那层 verticalScroll 收不到；
 * 改用 detectHorizontalDragGestures（只认横向、竖向放行），拖动带阻尼跟手偏移，
 * 松手 180ms 弹回，指示条同步伸缩动画；多选态横滑不误切 tab。
 * 仍不自带任何 verticalScroll / LazyColumn（R383 闪退根因零回潮）。
 */
@Composable
internal fun ArchiveScreen(c: Context) {
    var tick by remember { mutableStateOf(0) }
    // 0＝跨网中继，1＝语音网关；只有一个「当前渠道」，不再两列并存
    var tab by remember { mutableStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var openId by remember { mutableStateOf("") }
    var askClear by remember { mutableStateOf(false) }
    // R391 · 跟手位移用 Animatable：手指拖动时 snapTo，松手 animateTo(0) 弹回，
    // 不再「一松手瞬间跳过去」。detectHorizontalDragGestures 只认横向，
    // 竖向交给设置页外壳唯一那层 verticalScroll（R383 闪退根因不能再犯）。
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    val counts = remember(tick) { RequestArchive.counts(c) }
    val source = if (tab == 0) RequestArchive.SRC_RELAY else RequestArchive.SRC_GATEWAY
    val channelName = if (tab == 0) "跨网中继" else "语音网关"
    val total = if (tab == 0) counts.first else counts.second
    val items = remember(tick, tab, more) { RequestArchive.show(c, source, more) }

    fun goTab(next: Int) {
        if (next == tab || next !in 0..1) return
        tab = next
        more = false
        openId = ""
        picked = emptySet()
        selecting = false
        EventLog.log(EventLog.Kind.UI, "ARCHIVE_TAB", if (next == 0) "跨网中继" else "语音网关")
    }

    fun refresh() {
        tick++
        picked = emptySet()
    }

    fun copy(entries: List<RequestArchive.Entry>) {
        if (entries.isEmpty()) {
            Toast.makeText(c, "这个渠道还没有请求文本", Toast.LENGTH_SHORT).show()
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = {
                    picked = if (it) picked + e.id else picked - e.id
                }, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(e.text, fontSize = HiCaptionSp, color = palette.text,
                    maxLines = if (isOpen) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(e.ts)) + " · " + e.chars + " 字",
                    fontSize = HiMicroSp, color = palette.sub)
            }
            if (!selecting) {
                TextButton(onClick = {
                    clip.setText(AnnotatedString(e.text))
                    EventLog.log(EventLog.Kind.PIPELINE, "ARCHIVE_COPY", "复制 1 条")
                    Toast.makeText(c, "已复制", Toast.LENGTH_SHORT).show()
                }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)) {
                    Text("复制", fontSize = HiMicroSp)
                }
            }
        }
    }

    /** R390 · 选项卡：当前项主色加粗 + 底部指示条；两枚等宽，铺满整行。 */
    @Composable
    fun TabChip(mod: Modifier, title: String, n: Int, sel: Boolean, go: () -> Unit) {
        // R391 · 指示条宽度跟着选中态平滑伸缩（180ms），点一下 / 滑一下都不硬跳
        val barW by animateFloatAsState(if (sel) 1f else 0f, tween(180), label = "tabBar")
        Box(mod.height(42.dp)
            .clip(RoundedCornerShape(HiChipRadius))
            .background(palette.primarySoft.copy(alpha = if (sel) 1f else 0f))
            .border(1.dp, if (sel) palette.primary else palette.line,
                RoundedCornerShape(HiChipRadius))
            .clickable(onClick = go),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$title · $n", fontSize = HiCaptionSp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) palette.primary else palette.sub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width((26f * barW).dp).height(2.dp)
                    .background(if (sel) palette.primary else palette.line))
            }
        }
    }

    // ① 选项卡行：点一下切换；列表区还能左右滑切换，整宽铺满
    CardBox {
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TabChip(Modifier.weight(1f), "跨网中继", counts.first, tab == 0) { goTab(0) }
            TabChip(Modifier.weight(1f), "语音网关", counts.second, tab == 1) { goTab(1) }
        }
    }

    Spacer(Modifier.height(HiSectionGap))

    // ② 主区：当前渠道铺满整宽，是这一页的重点。
    // R391 真机反馈「上下滑滑不动、左右滑很生硬」的根因与修法：
    // 旧 detectDragGestures 内部 drag() 会把竖向位移也 consume 掉，父级外壳的
    // verticalScroll 收不到 → 整页滑不动；而且松手瞬间直接换 tab，没有过渡。
    // 现在换成 detectHorizontalDragGestures（只认横向、竖向原样放行给外壳），
    // 拖动时主区按 0.35 阻尼跟手偏移，松手 180ms 弹回并切 tab；多选态不误切。
    CardBox(Modifier
        .offset { IntOffset((dragX.value * 0.35f).roundToInt(), 0) }
        .pointerInput(tab, selecting) {
            detectHorizontalDragGestures(
                onDragStart = { scope.launch { dragX.snapTo(0f) } },
                onHorizontalDrag = { _, dx ->
                    if (!selecting) {
                        scope.launch {
                            dragX.snapTo((dragX.value + dx).coerceIn(-140f, 140f))
                        }
                    }
                },
                onDragEnd = {
                    val dx = dragX.value
                    if (!selecting && abs(dx) > 60f) goTab(if (dx < 0f) 1 else 0)
                    scope.launch { dragX.animateTo(0f, tween(180)) }
                },
                onDragCancel = { scope.launch { dragX.animateTo(0f, tween(180)) } }
            )
        }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channelName, fontSize = HiSectionSp, fontWeight = FontWeight.Bold,
                color = palette.text, modifier = Modifier.weight(1f))
            Text("共 $total 条", fontSize = HiMicroSp, color = palette.sub)
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            CardBox {
                Text("还没有请求", fontSize = HiCaptionSp, color = palette.sub)
                Spacer(Modifier.height(4.dp))
                Text("这个渠道收到合成请求后，文字会留在这里。",
                    fontSize = HiMicroSp, color = palette.sub)
            }
        } else {
            for (e in items) {
                EntryCard(e)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (total > RequestArchive.DEFAULT_SHOW) {
            TextButton(onClick = { more = !more },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (more) "只看最近 10 条 ⌃" else "展开全部 $total 条 ›",
                    fontSize = HiMicroSp)
            }
        }
        if (!selecting) {
            TextButton(onClick = { copy(RequestArchive.list(c, source)) },
                contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("复制本渠道", fontSize = HiMicroSp)
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
                    Text("一键复制全部", fontSize = HiCaptionSp)
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

    Spacer(Modifier.height(HiSectionGap))

    // ④ 说明降级：不折叠、不藏小问号，直接压成页面底部两行微字。
    // 列表是重点，说明是次要信息，主次一眼分得清。
    Text("只留文字，音频与中间文件都不留；存在本机私有区，不进诊断日志、不进导出的日志、不备份。",
        fontSize = HiMicroSp, color = palette.sub)
    Spacer(Modifier.height(3.dp))
    Text("每个渠道默认看最近 10 条，最多各留 100 条，超了自动丢最老。",
        fontSize = HiMicroSp, color = palette.sub)

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("清空全部请求文本？", fontSize = HiSectionSp) },
            text = {
                Text("跨网中继和语音网关一起清掉，共 " + (counts.first + counts.second) +
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
