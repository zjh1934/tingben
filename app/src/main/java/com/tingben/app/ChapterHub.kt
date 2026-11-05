package com.tingben.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * R61 · 一本书点进去的目录页。
 *
 * 形状来自 v9 全 8 屏预览：同一份按章拆分的目录，平级排成四列左右滑；
 * 第一列勾选 + 底部小条，第二列标注进度，第三列合成状态，第四列成品控制。
 * 播放走软件内悬浮条，不做独立播放器页；看书页与标注结果页是本页的覆盖层，
 * 不扩张 Screen 枚举。状态只从磁盘成品 / Producer.state / segmap 取，不画假进度。
 */
private val HubBg = Color(0xFFF4F6FB)
private val HubCard = Color(0xFFFFFFFF)
private val HubPrimary = Color(0xFF4338CA)
private val HubSoft = Color(0xFFEEF0FE)
private val HubSub = Color(0xFF6B7280)
private val HubGood = Color(0xFF0E9F6E)
private val HubLine = Color(0xFFE7EAF2)
private val HubWarn = Color(0xFFB45309)
private val HubGrey = Color(0xFFB9BFCB)

private val HubTitles = listOf("原始", "标注", "合成", "可播")

private sealed class Pane {
    data class Reader(val i: Int) : Pane()
    data class Annotated(val i: Int) : Pane()
}

private enum class HubAction { ANNOTATE, DIRECT, ANNOTATE_SYNTH }

/** 一章在四列里的全部状态；null 进度 = 没有可信来源，界面只画灰。 */
private data class ChapterUi(
    val index: Int,
    val title: String,
    val annotated: Float?,
    val synthesized: Float?,
    val audio: AudioItem?,
    val playable: Boolean,
    val unannotatedAudio: Boolean
)

private fun chapterStates(
    finished: Map<Int, AudioItem>, playable: Set<Int>, chapters: List<Chapter>,
    running: Boolean, runningBook: String?, runningOnly: Set<Int>?,
    done: Int, total: Int
): List<ChapterUi> {
    // 进行中只有「整本进度」这一个真值：不做逐章假拆分，界面上标清楚。
    val live = if (running && runningBook != null && total > 0)
        (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
    val inScope: (Int) -> Boolean = { i -> runningOnly == null || i in runningOnly }
    return chapters.map { ch ->
        val a = finished[ch.index]
        ChapterUi(
            index = ch.index,
            title = ch.title,
            annotated = if (a != null && a.mode == "标注后合成") 1f
                        else if (live != null && inScope(ch.index)) live else 0f,
            synthesized = if (a != null) 1f
                          else if (live != null && inScope(ch.index)) live else 0f,
            audio = a,
            playable = ch.index in playable,
            unannotatedAudio = a != null && a.mode != "标注后合成"
        )
    }
}

@Composable
internal fun ChapterHubScreen(
    c: Context, id: String?, refresh: Int,
    onBack: () -> Unit,
    onProgress: () -> Unit,
    onPlayBook: (String) -> Unit
) {
    val book = remember(id, refresh) { id?.let { BookStore.list(c).find { b -> b.id == it } } }
    if (book == null) {
        Column(Modifier.padding(16.dp)) {
            Text("这本书不在了")
            TextButton(onClick = onBack) { Text("返回书架") }
        }
        return
    }

    // 全文与拆分只算一次；成品索引按 refresh 重读，避免每帧读磁盘 JSON（R51 卡顿根因）。
    val split = remember(book) { ChapterSplitter.split(BookStore.read(c, book)) }
    val chapters = split.first
    val chunks = split.second
    val finished = remember(book, refresh) { AudioLibrary.latestByChapter(c, book.id) }
    val playable = remember(book, refresh) { AudioLibrary.playableChapters(c, book.id) }

    // R51 · 叶子级订阅，不把高频引擎状态挂到 App() 根上。
    val st by Producer.state.collectAsState()
    val task by Producer.resume.collectAsState()
    val ui = remember(book, refresh, st.done, st.total, st.running, task) {
        chapterStates(finished, playable, chapters,
            st.running, task?.bookId, task?.only, st.done, st.total)
    }

    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    var pane by remember(book) { mutableStateOf<Pane?>(null) }
    var showProgressHint by remember(book) { mutableStateOf(false) }

    // 覆盖层内的返回键优先于 App 层的「回书架」。
    BackHandler(enabled = pane != null) { pane = null }

    val scope = rememberCoroutineScope()
    val colWidth = LocalConfiguration.current.screenWidthDp.dp
    val pagePx = with(LocalDensity.current) { colWidth.toPx() }
    val hScroll = rememberScrollState()
    val current by remember {
        derivedStateOf {
            if (pagePx <= 0f) 0 else ((hScroll.value + pagePx / 2f) / pagePx).toInt().coerceIn(0, 3)
        }
    }

    fun jumpTo(col: Int) { scope.launch { hScroll.animateScrollTo((col * pagePx).toInt()) } }

    fun playChapter(i: Int, single: Boolean) {
        if (single) {
            val a = ui.getOrNull(i)?.audio ?: return
            PlayQueue.setTracks(listOf(Track(a.file, "${book.title} · 第 ${i + 1} 章")))
            PlayQueue.playFrom(0)
        } else {
            val list = AudioLibrary.playableItems(c, book.id)
            if (list.isEmpty()) return
            PlayQueue.setTracks(list.map { (idx, a) ->
                Track(a.file, "${book.title} · 第 ${idx + 1} 章")
            })
            PlayQueue.playFrom(list.indexOfFirst { it.first == i }.coerceAtLeast(0))
        }
        onPlayBook(book.id)
    }

    fun startAction(a: HubAction) {
        if (selected.isEmpty()) return
        val n = selected.size
        val direct = a == HubAction.DIRECT
        Producer.start(c, book, selected, directRead = direct, config = ProduceConfig())
        selected = emptySet()
        showProgressHint = true
        EventLog.log(EventLog.Kind.PIPELINE, "HUB_ACTION",
            "书=${book.title} 章数=$n 档=${if (direct) "直接朗读" else "标注后合成"}")
    }

    Box(Modifier.fillMaxSize().background(HubBg)) {
        Column(Modifier.fillMaxSize()) {
            // 顶部四个文字标签：当前列下划线，不画圆点；滚动反向联动 current。
            Row(Modifier.fillMaxWidth().background(HubCard).padding(horizontal = 8.dp)) {
                HubTitles.forEachIndexed { col, t ->
                    val on = current == col
                    Box(
                        Modifier.weight(1f).clickable { jumpTo(col) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t, fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) HubPrimary else HubSub)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.width(24.dp).height(2.dp)
                                .background(if (on) HubPrimary else Color.Transparent))
                        }
                    }
                }
            }
            if (showProgressHint || st.running) {
                Row(Modifier.fillMaxWidth().background(HubSoft)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("正在制作 · ${st.done}/${st.total} 块", fontSize = 12.sp,
                        color = HubPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onProgress) { Text("进度页 ›", fontSize = 12.sp) }
                }
            }

            Row(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                repeat(4) { col ->
                    Column(Modifier.width(colWidth).fillMaxHeight()) {
                        when (col) {
                            0 -> RawColumn(ui, selected, { i, on ->
                                selected = if (on) selected + i else selected - i
                            }, { i -> pane = Pane.Reader(i) })
                            1 -> AnnColumn(ui, { i -> pane = Pane.Annotated(i) })
                            2 -> SynColumn(ui, { i -> playChapter(i, single = true) })
                            else -> PlayColumn(ui,
                                onAll = {
                                    val list = AudioLibrary.playableItems(c, book.id)
                                    if (list.isNotEmpty()) {
                                        PlayQueue.setTracks(list.map { (idx, a) ->
                                            Track(a.file, "${book.title} · 第 ${idx + 1} 章")
                                        })
                                        PlayQueue.playFrom(0)
                                        onPlayBook(book.id)
                                    }
                                },
                                onFrom = { i -> playChapter(i, single = false) },
                                onStop = { PlayQueue.release() })
                        }
                    }
                }
            }
        }

        // 第一列勾选后底部浮出的小选项条，不占常驻高度。
        if (selected.isNotEmpty()) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = HubCard),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("已选 ${selected.size} 章", fontSize = 12.sp, color = HubSub)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { startAction(HubAction.ANNOTATE) },
                            modifier = Modifier.weight(1f)) { Text("去标注", fontSize = 12.sp) }
                        TextButton(onClick = { startAction(HubAction.DIRECT) },
                            modifier = Modifier.weight(1f)) { Text("直接合成音频", fontSize = 12.sp) }
                        TextButton(onClick = { startAction(HubAction.ANNOTATE_SYNTH) },
                            modifier = Modifier.weight(1f)) { Text("标注并合成音频", fontSize = 12.sp) }
                    }
                    Text("这一档跑完会一并出音频", fontSize = 10.5.sp, color = HubSub)
                }
            }
        }

        // 覆盖层：看书页 / 标注结果页，都不进 Screen 枚举。
        val p = pane
        if (p != null) {
            when (p) {
                is Pane.Reader -> ReaderPane(c, book, chapters, p.i, { pane = null }) { next ->
                    pane = Pane.Reader(next)
                }
                is Pane.Annotated -> AnnotatedPane(c, book, chapters, chunks, p.i,
                    onClose = { pane = null },
                    onRedo = { i ->
                        selected = setOf(i)
                        pane = null
                        jumpTo(0)
                    })
            }
        }
    }
}

/** 第一列：章名 + 右侧勾选框；点章名看原文。 */
@Composable
private fun RawColumn(
    ui: List<ChapterUi>, selected: Set<Int>,
    onCheck: (Int, Boolean) -> Unit, onRead: (Int) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        items(ui, key = { it.index }) { r ->
            Row(
                Modifier.fillMaxWidth().height(52.dp)
                    .clickable { onRead(r.index) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${r.index + 1}. ${r.title}", fontSize = 14.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Checkbox(checked = r.index in selected,
                    onCheckedChange = { onCheck(r.index, it) })
            }
            HubDivider()
        }
    }
}

/** 第二列：半透明进度条衬在章名行上；没标完点不进。 */
@Composable
private fun AnnColumn(ui: List<ChapterUi>, onOpen: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(ui, key = { it.index }) { r ->
            val p = (r.annotated ?: 0f).coerceIn(0f, 1f)
            val done = r.annotated != null && r.annotated >= 1f
            Box(Modifier.fillMaxWidth().height(52.dp)
                .then(if (done) Modifier.clickable { onOpen(r.index) } else Modifier)) {
                if (p > 0f && p < 1f) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight()
                        .background(HubPrimary.copy(alpha = 0.14f)))
                }
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.index + 1}. ${r.title}", fontSize = 14.sp, maxLines = 1,
                        color = if (done) Color.Unspecified else HubGrey,
                        modifier = Modifier.weight(1f))
                    Text(when {
                        done -> "已标注"
                        p > 0f -> "${(p * 100).toInt()}%"
                        else -> "未开始"
                    }, fontSize = 11.sp, color = if (done) HubGood else HubSub)
                }
            }
            HubDivider()
        }
    }
}

/** 第三列：灰=没合 / 进度条=正在合 / 右侧播放暂停=完成。 */
@Composable
private fun SynColumn(ui: List<ChapterUi>, onPlay: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        items(ui, key = { it.index }) { r ->
            val p = (r.synthesized ?: 0f).coerceIn(0f, 1f)
            val done = r.audio != null
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                if (p > 0f && p < 1f) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight()
                        .background(HubGood.copy(alpha = 0.16f)))
                }
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.index + 1}. ${r.title}", fontSize = 14.sp, maxLines = 1,
                            color = if (done || p > 0f) Color.Unspecified else HubGrey)
                        if (r.unannotatedAudio) Text("未标注 · 可单章听", fontSize = 10.sp, color = HubWarn)
                        else if (!done && p > 0f) Text("整本进度 ${(p * 100).toInt()}%", fontSize = 10.sp, color = HubSub)
                    }
                    if (done) {
                        TextButton(onClick = { onPlay(r.index) }) { Text("▶ 播放", fontSize = 12.sp) }
                    }
                }
            }
            HubDivider()
        }
    }
}

/** 第四列：只有标注后合成的章变亮；三个控制，不画倍速/定时这类没有的开关。 */
@Composable
private fun PlayColumn(
    ui: List<ChapterUi>, onAll: () -> Unit, onFrom: (Int) -> Unit, onStop: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            TextButton(onClick = onAll, modifier = Modifier.weight(1f)) {
                Text("按顺序播放", fontSize = 12.sp)
            }
            TextButton(onClick = onStop) { Text("停止", fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
            items(ui, key = { it.index }) { r ->
                Row(Modifier.fillMaxWidth().height(52.dp)
                    .then(if (r.playable) Modifier.clickable { onFrom(r.index) } else Modifier)
                    .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.index + 1}. ${r.title}", fontSize = 14.sp, maxLines = 1,
                        color = if (r.playable) Color.Unspecified else HubGrey,
                        modifier = Modifier.weight(1f))
                    if (r.playable) Text("从本章起播 ›", fontSize = 11.sp, color = HubPrimary)
                }
                HubDivider()
            }
        }
    }
}

@Composable
private fun HubDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HubLine))
}

/** 看书就是看书：纯文本、字号、上下章，不认识播放器、不加系统 TTS。 */
@Composable
private fun ReaderPane(
    c: Context, book: Book, chapters: List<Chapter>, i: Int,
    onClose: () -> Unit, onJump: (Int) -> Unit
) {
    val text = remember(book) { BookStore.read(c, book) }
    val ch = chapters.getOrNull(i) ?: return
    val body = remember(i) {
        val s = ch.start.coerceIn(0, text.length)
        val e = ch.end.coerceIn(s, text.length)
        text.substring(s, e).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    var size by remember { mutableStateOf(16f) }
    Column(Modifier.fillMaxSize().background(HubCard)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ 目录") }
            Text(ch.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = { size = (size - 1f).coerceIn(12f, 24f) }) { Text("A-") }
            TextButton(onClick = { size = (size + 1f).coerceIn(12f, 24f) }) { Text("A+") }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            items(body) { p ->
                Text(p, fontSize = size.sp, lineHeight = (size * 1.7f).sp,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = { onJump(i - 1) }, modifier = Modifier.weight(1f),
                enabled = i > 0) { Text("‹ 上一章") }
            TextButton(onClick = { onJump(i + 1) }, modifier = Modifier.weight(1f),
                enabled = i < chapters.lastIndex) { Text("下一章 ›") }
        }
    }
}

/**
 * 标注结果页只读：真值来自 SegPlan 的 segmap + Chunk.chapter 映射。
 * 旧缓存没有 segmap 就明写「没有逐段记录」，不把直读块冒充模型标注。
 */
@Composable
private fun AnnotatedPane(
    c: Context, book: Book, chapters: List<Chapter>, chunks: List<Chunk>, i: Int,
    onClose: () -> Unit, onRedo: (Int) -> Unit
) {
    val rows = remember(book, i) {
        val t = TaskResume.load(c)
        if (t == null || t.bookId != book.id || t.direct) emptyList()
        else {
            val work = AnnCache.dirFor(c, book.id, false, t.think, t.only)
            val map = SegPlan.readMap(work)
            if (map == null) emptyList()
            else map.filter { m ->
                val ch = chunks.getOrNull(m.block)?.chapter ?: -1
                ch == i
            }.map { Triple(it.speaker, it.instruct, it.text) }
        }
    }
    val ch = chapters.getOrNull(i)
    Column(Modifier.fillMaxSize().background(HubCard)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ 目录") }
            Text("${ch?.title ?: "第 ${i + 1} 章"} · 标注结果", fontSize = 16.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRedo(i) }) { Text("重新标注") }
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("旧缓存没有逐段记录，或还没标注完", fontSize = 13.sp, color = HubSub)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                items(rows) { (speaker, instruct, text) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("$speaker · ${instruct.take(40)}", fontSize = 11.sp, color = HubPrimary)
                        Spacer(Modifier.height(3.dp))
                        Text(text.take(120), fontSize = 13.sp, color = Color(0xFF1F2430))
                    }
                    HubDivider()
                }
            }
        }
    }
}

/**
 * 软件内悬浮条：跨页常驻，订阅单例 PlayQueue.state（不新建播放器）。
 * 目录弹层只列第四列亮章，与「按顺序播放」用同一份 playableItems 顺序。
 */
@Composable
internal fun MiniPlayBar(c: Context, bookId: String?, onGoBook: (String) -> Unit) {
    val st by PlayQueue.state.collectAsState()
    if (st.index < 0 && st.label.isEmpty()) return
    var showList by remember { mutableStateOf(false) }
    val list = remember(bookId) { bookId?.let { AudioLibrary.playableItems(c, it) } ?: emptyList() }
    LaunchedEffect(st.isPlaying) {
        while (isActive) {
            PlayQueue.tick()
            delay(1000L)
        }
    }
    val frac = if (st.durationMs > 0)
        (st.positionMs.toFloat() / st.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth().background(HubCard)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(HubLine)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(HubPrimary))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.label.ifBlank { "未在播放" }, fontSize = 13.sp, maxLines = 1,
                    fontWeight = FontWeight.Bold)
                Text("${st.positionMs / 1000}s / ${st.durationMs / 1000}s",
                    fontSize = 10.sp, color = HubSub)
            }
            TextButton(onClick = { PlayQueue.previous() }) { Text("‹", fontSize = 16.sp) }
            TextButton(onClick = { PlayQueue.toggle() }) {
                Text(if (st.isPlaying) "⏸" else "▶", fontSize = 16.sp)
            }
            TextButton(onClick = { PlayQueue.next() }) { Text("›", fontSize = 16.sp) }
            TextButton(onClick = { showList = true }) { Text("目录", fontSize = 12.sp) }
        }
    }

    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            title = { Text("目录", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                if (list.isEmpty()) {
                    Text("这一本还没有可整本顺序播放的章节", fontSize = 13.sp, color = HubSub)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(list) { (idx, a) ->
                            Row(Modifier.fillMaxWidth().height(44.dp)
                                .clickable {
                                    PlayQueue.setTracks(list.map { (j, x) ->
                                        Track(x.file, "${a.title} · 第 ${j + 1} 章")
                                    })
                                    PlayQueue.playFrom(list.indexOfFirst { it.first == idx })
                                    showList = false
                                }.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("第 ${idx + 1} 章 · ${a.scope}", fontSize = 13.sp)
                            }
                            HubDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showList = false
                    bookId?.let { onGoBook(it) }
                }) { Text("去书页 ›") }
            },
            dismissButton = { TextButton(onClick = { showList = false }) { Text("关闭") } }
        )
    }
}
