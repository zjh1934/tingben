package com.tingben.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import java.io.File

// @ui-part: ProduceUi
@Composable
internal fun ProduceScreen(c: Context, id: String?, refresh: Int, onBack: () -> Unit,
                          onProgress: () -> Unit, onDone: () -> Unit,
                          onStarted: (Set<Int>?) -> Unit) {
    val st by Producer.state.collectAsState()
    var selected by remember(id, refresh) { mutableStateOf<Set<Int>>(emptySet()) }
    var cacheTick by remember { mutableStateOf(0) }
    var cfg by remember(id, refresh) { mutableStateOf(Prefs.defaultPerf(c)) }
    var loadDone by remember(id, refresh) { mutableStateOf(false) }
    var load by remember(id, refresh) { mutableStateOf<ProduceLoad?>(null) }
    // R142 · 调音是「点合成之前」的准备工作，不该是另一张一级页。
    var mixerOpen by remember { mutableStateOf(false) }
    var loadError by remember(id, refresh) { mutableStateOf<String?>(null) }
    var hasCached by remember(id, refresh) { mutableStateOf(false) }
    // R185 · 用户常调音色，旧实现一保存调优就把成果全删。现在分套留着，
    // 制作页说清「这套做了几段 / 还留着几套 / 切回原设置就能接着用」。
    var cacheInfo by remember(id, refresh) {
        mutableStateOf(VoiceCache.Summary(0, 0, 0, 0L))
    }

    // R139 · 组合期不再同步读整本 + 切块（原来 64MB 文本会卡主线程甚至 ANR）。
    // 与目录页同一套口径：IO 线程读落盘章节表，加载中给进度圈，出错给中文原因。
    LaunchedEffect(id, refresh) {
        loadDone = false
        val r: Pair<ProduceLoad?, String?> = withContext(Dispatchers.IO) {
            try {
                val b = id?.let { BookStore.list(c).find { it.id == id } }
                if (b == null) null to null else {
                    val (chs, chunks) = ChapterPlan.resolve(c, b)
                    val saved = TaskResume.load(c)
                    ProduceLoad(b, chs, chunks,
                        saved?.takeIf { it.bookId == id }?.config) to null
                }
            } catch (t: Throwable) {
                null to (t.message ?: "读这本书时出错")
            }
        }
        load = r.first
        loadError = r.second
        r.first?.savedConfig?.let { cfg = it }
        loadDone = true
    }

    val snapshot = load
    val chapters = snapshot?.chapters ?: emptyList()
    val allChunks = snapshot?.chunks ?: emptyList()
    val estimate = remember(selected, snapshot) {
        if (selected.isEmpty() || selected.size == chapters.size) allChunks.size
        else allChunks.count { it.chapter in selected }
    }

    // 断点检查也读盘，同样放 IO；selected / cacheTick 变化时自动刷新
    LaunchedEffect(id, refresh, selected, cacheTick, loadDone, snapshot, cfg) {
        if (!loadDone) return@LaunchedEffect
        val b = snapshot?.book ?: return@LaunchedEffect
        val only = if (selected.isEmpty() || selected.size == chapters.size) null else selected
        val (cached, info) = withContext(Dispatchers.IO) {
            Producer.hasProgress(c, b.id, only) to
                Producer.voiceCacheSummary(c, b.id, only, cfg)
        }
        hasCached = cached
        cacheInfo = info
    }

    if (!loadDone) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (snapshot == null) {
        Column(Modifier.padding(16.dp)) {
            Text(loadError ?: "这本书不在了")
            TextButton(onClick = onBack) { Text("返回书架") }
        }
        return
    }
    val book = snapshot.book
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("${chapters.size} 章 · ${book.chars} 字", fontSize = 13.sp, color = Sub)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("合成前调音", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { mixerOpen = true; EventLog.ui("OVERLAY_MIXER", "open") }) {
                Text("调音 ›", fontSize = 12.sp, color = Primary)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (st.output != null) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("做好了", fontWeight = FontWeight.Bold, color = Good)
                Spacer(Modifier.height(4.dp))
                Text(st.message, fontSize = 13.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("去听") }
            }
        }

        var showAllChapters by remember(book) { mutableStateOf(false) }

        ExpandGroup("① 做什么", "") {
            Text("原文整段送声音模型", fontSize = 13.sp, color = Sub)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("制作范围", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.index }.toSet() }) {
                    Text(if (selected.size == chapters.size) "清空" else "全选")
                }
            }
            Text(if (selected.isEmpty()) "整本 · 预计约 $estimate 块" else "已选 ${selected.size} 章 · 预计约 $estimate 块",
                fontSize = 12.sp, color = Sub)
            // R183 · 真机实测每段 50~220 秒。只给一句量级，不写教程、不常驻解释。
            Text("每段约 1–3 分钟，块越多等得越久", fontSize = 11.sp, color = Sub)
            if (cacheInfo.current > 0) {
                Spacer(Modifier.height(4.dp))
                Text("这套音色已做好 ${cacheInfo.current} 段，接着做不用重来",
                    fontSize = 12.sp, color = Good)
            }
            if (cacheInfo.others > 0) {
                Spacer(Modifier.height(4.dp))
                Text("另有 ${cacheInfo.others} 套音色留着（最多 ${cacheInfo.bestOther} 段），" +
                    "切回原来的设置就能接着用", fontSize = 12.sp, color = Sub)
            }
            val visibleChapters = if (chapters.size > 20 && !showAllChapters)
                chapters.take(20) else chapters
            if (chapters.size >= 2) {
                Spacer(Modifier.height(8.dp))
                visibleChapters.forEach { ch ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (ch.index in selected) selected - ch.index else selected + ch.index
                    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ch.index in selected, onCheckedChange = {
                            selected = if (it) selected + ch.index else selected - ch.index
                        })
                        Spacer(Modifier.width(6.dp))
                        Text("${ch.index + 1}. ${ch.title.take(28)}", fontSize = 13.sp, maxLines = 1)
                    }
                }
                if (chapters.size > 20) {
                    TextButton(onClick = { showAllChapters = !showAllChapters }) {
                        Text(if (showAllChapters) "收起" else "显示全部 ${chapters.size} 章")
                    }
                }
            } else {
            }
        }

        ExpandGroup("② 怎么念", cfg.summary()) {
            OptionRow("朗读档位", "",
                ReadingStyle.values().map { it to it.label }, cfg.style) { cfg = cfg.copy(style = it) }
            OptionRow("语速倾向", "",
                Pace.values().map { it to it.label }, cfg.pace) { cfg = cfg.copy(pace = it) }
            OptionRow("情绪强度", "",
                Intensity.values().map { it to it.label }, cfg.intensity) { cfg = cfg.copy(intensity = it) }
        }

        ExpandGroup("③ 怎么跑", "") {
            OptionRow("段间静音", "",
                ProduceConfig.GAP_OPTIONS.map { it to "${it}ms" }, cfg.gapMs) { cfg = cfg.copy(gapMs = it) }
            OptionRow("分段失败重做", "",
                ProduceConfig.RETRY_OPTIONS.map { it to "${it}次" }, cfg.gateRetries) { cfg = cfg.copy(gateRetries = it) }
            TextButton(onClick = { cfg = Prefs.defaultPerf(c) }) { Text("恢复默认", fontSize = 12.sp) }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                val sel = if (selected.isEmpty() || selected.size == chapters.size) null else selected
                onStarted(sel)
                Producer.start(c, book, sel, directRead = true, config = cfg)
                onProgress()
            },
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (st.running) "正在制作…"
                else if (hasCached) "继续合成"
                else "合成音频",
                fontSize = 16.sp
            )
        }

        if (hasCached && !st.running) {
            TextButton(onClick = {
                Producer.clearProgress(c, book.id,
                    if (selected.isEmpty() || selected.size == chapters.size) null else selected)
                cacheTick++
            }, Modifier.fillMaxWidth()) { Text("从头重做（清掉已做的块）", fontSize = 12.sp) }
        }

        if (st.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(st.message, fontSize = 13.sp, color = if (st.output != null) Good else Sub)
        }
        // R142 · 合成前调音弹层：不进导航栈，关闭/返回都回到本页
        if (mixerOpen) MixerDialog(c, onClose = {
            mixerOpen = false; EventLog.ui("OVERLAY_MIXER", "close")
        })
    }
}

