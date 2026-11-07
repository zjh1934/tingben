package com.tingben.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val HubBg: Color get() = palette.bg
private val HubCard: Color get() = palette.card
private val HubPrimary: Color get() = palette.primary
private val HubSoft: Color get() = palette.primarySoft
private val HubSub: Color get() = palette.sub
private val HubGood: Color get() = palette.good
private val HubLine: Color get() = palette.line
private val HubWarn: Color get() = palette.warn
private val HubGrey: Color get() = palette.grey

private val HubTitles = listOf("章节", "合成", "可播")

private data class HubLoad(
    val book: Book,
    val chapters: List<Chapter>,
    val finished: Map<Int, AudioItem>,
    val playable: Set<Int>,
    val lastRead: Int?
)

private sealed class Pane {
    data class Reader(val i: Int) : Pane()
}

private enum class HubAction { SYNTH }

private data class ChapterUi(
    val index: Int,
    val title: String,
    val synthesized: Float?,
    val audio: AudioItem?,
    val playable: Boolean,
    val stalled: Boolean = false
)

private fun chapterStates(
    finished: Map<Int, AudioItem>, playable: Set<Int>, chapters: List<Chapter>,
    running: Boolean, runningBook: String?, runningOnly: Set<Int>?,
    chapterProgress: Map<Int, Float>, bookId: String, stalled: Boolean
): List<ChapterUi> {
    val inScope: (Int) -> Boolean = { i -> runningBook == bookId &&
        (runningOnly == null || i in runningOnly) }
    return chapters.map { ch ->
        val a = finished[ch.index]
        val live = if (running && inScope(ch.index))
            chapterProgress[ch.index] else null
        ChapterUi(
            index = ch.index,
            title = ch.title,
            synthesized = if (a != null) 1f else live,
            audio = a,
            playable = ch.index in playable,
            stalled = stalled
        )
    }
}

@Composable
internal fun ChapterHubScreen(
    c: Context, id: String?, refresh: Int,
    onBack: () -> Unit,
    onProgress: () -> Unit,
    onPlayBook: (String) -> Unit,
    onMixer: () -> Unit,
    onStarted: (String, Set<Int>) -> Unit
) {
    var loadDone by remember(id, refresh) { mutableStateOf(false) }
    var load by remember(id, refresh) { mutableStateOf<HubLoad?>(null) }
    LaunchedEffect(id, refresh) {
        val r = withContext(Dispatchers.IO) {
            val b = id?.let { BookStore.list(c).find { it.id == id } }
            if (b == null) null else {
                val s = ChapterSplitter.split(BookStore.read(c, b))
                HubLoad(b, s.first,
                    AudioLibrary.latestByChapter(c, b.id),
                    AudioLibrary.playableChapters(c, b.id),
                    ReadingState.last(c, b.id))
            }
        }
        load = r
        loadDone = true
    }
    if (!loadDone) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val snapshot = load
    if (snapshot == null) {
        Column(Modifier.padding(16.dp)) {
            Text("这本书不在了")
            TextButton(onClick = onBack) { Text("返回书架") }
        }
        return
    }
    val book = snapshot.book
    val chapters = snapshot.chapters
    val st by Producer.state.collectAsState()
    val task by Producer.resume.collectAsState()

    val diskKey = "${st.running}|${st.stage}|${st.done}"
    var disk by remember(book.id, refresh) {
        mutableStateOf<Pair<Map<Int, AudioItem>, Set<Int>>?>(null)
    }
    LaunchedEffect(book.id, refresh, diskKey) {
        if (disk != null) delay(350)
        disk = withContext(Dispatchers.IO) {
            AudioLibrary.latestByChapter(c, book.id) to AudioLibrary.playableChapters(c, book.id)
        }
    }
    val ui by remember(chapters, disk, task) {
        derivedStateOf {
            val d = disk
            val stalled = !st.running && task?.bookId == book.id &&
                st.stage in listOf("没完成", "已停止", "出错了")
            chapterStates(d?.first ?: emptyMap(), d?.second ?: emptySet(), chapters,
                st.running, task?.bookId, task?.only, st.chapterProgress, book.id, stalled)
        }
    }

    var selected by rememberSaveable(book.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var pane by remember(book) { mutableStateOf<Pane?>(null) }
    fun openReader(i: Int) {
        if (i in chapters.indices) {
            ReadingState.save(c, book.id, i)
            pane = Pane.Reader(i)
        }
    }
    val lastRead = snapshot.lastRead

    BackHandler(enabled = pane != null) { pane = null }

    val scope = rememberCoroutineScope()
    val colWidth = LocalConfiguration.current.screenWidthDp.dp
    val pagePx = with(LocalDensity.current) { colWidth.toPx() }
    val hScroll = rememberScrollState()
    val current by remember {
        derivedStateOf {
            if (pagePx <= 0f) 0 else ((hScroll.value + pagePx / 2f) / pagePx).toInt().coerceIn(0, 2)
        }
    }

    fun jumpTo(col: Int) { scope.launch { hScroll.animateScrollTo((col * pagePx).toInt()) } }

    fun playChapter(i: Int, single: Boolean) {
        val q = "chapter:${book.id}"
        if (single) {
            val a = ui.getOrNull(i)?.audio ?: return
            PlayQueue.setTracks(listOf(Track(a.file, "${book.title} · 第 ${i + 1} 章",
                AudioLibrary.chapterStartMs(c, a, i),
                id = "chapter:${a.file.absolutePath}")), q)
            PlayQueue.playFrom(0, id = q)
        } else {
            val list = AudioLibrary.playableItems(c, book.id)
            if (list.isEmpty()) return
            PlayQueue.setTracks(list.map { (idx, a) ->
                Track(a.file, "${book.title} · 第 ${idx + 1} 章",
                    AudioLibrary.chapterStartMs(c, a, idx),
                    id = "chapter:${a.file.absolutePath}")
            }, q)
            PlayQueue.playFrom(list.indexOfFirst { it.first == i }.coerceAtLeast(0), id = q)
        }
        onPlayBook(book.id)
    }

    fun startAction(a: HubAction) {
        if (selected.isEmpty()) return
        val n = selected.size
        Producer.start(c, book, selected, directRead = true, config = ProduceConfig())
        onStarted(book.id, selected)
        selected = emptySet()
        EventLog.log(EventLog.Kind.PIPELINE, "HUB_ACTION",
            "书=${book.title} 章数=$n 档=直接朗读")
    }

    Box(Modifier.fillMaxSize().background(HubBg)) {
        Column(Modifier.fillMaxSize()) {
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
            if (!st.running && lastRead != null && lastRead in chapters.indices) {
                Row(Modifier.fillMaxWidth().background(HubCard)
                    .clickable { openReader(lastRead) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("上次读到 第 ${lastRead + 1} 章 · ${chapters[lastRead].title}",
                        fontSize = 12.sp, color = HubSub, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    Text("继续 ›", fontSize = 12.sp, color = HubPrimary)
                }
            }
            val barText = when {
                st.running -> "正在制作 · ${st.done}/${st.total} 块"
                task?.bookId != book.id -> null
                st.stage == "完成" -> "做好了 · 合成列已更新，可播列已点亮"
                st.stage in listOf("没完成", "已停止", "出错了", "没开始") ->
                    st.message.ifBlank { "这次没做完，已生成的段都留着" }
                else -> null
            }
            if (barText != null) {
                Row(Modifier.fillMaxWidth().background(HubSoft)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(barText, fontSize = 12.sp, maxLines = 2,
                        color = HubPrimary, modifier = Modifier.weight(1f))
                    if (st.running) {
                        TextButton(onClick = onProgress) { Text("任务 ›", fontSize = 12.sp) }
                    }
                }
            }

            Row(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                repeat(3) { col ->
                    Column(Modifier.width(colWidth).fillMaxHeight()) {
                        when (col) {
                            0 -> RawColumn(ui, selected, { i, on ->
                                selected = if (on) selected + i else selected - i
                            }, { i -> openReader(i) })
                            1 -> SynColumn(ui, { i -> playChapter(i, single = true) })
                            else -> PlayColumn(ui,
                                onAll = {
                                    val list = AudioLibrary.playableItems(c, book.id)
                                    if (list.isNotEmpty()) {
                                        val q = "chapter:${book.id}"
                                        PlayQueue.setTracks(list.map { (idx, a) ->
                                            Track(a.file, "${book.title} · 第 ${idx + 1} 章",
                                                AudioLibrary.chapterStartMs(c, a, idx),
                                                id = "chapter:${a.file.absolutePath}")
                                        }, q)
                                        PlayQueue.playFrom(0, id = q)
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

        if (selected.isNotEmpty()) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = HubCard),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 章", fontSize = 12.sp, color = HubSub,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onMixer) { Text("调音 ›", fontSize = 12.sp) }
                        TextButton(onClick = { startAction(HubAction.SYNTH) }) {
                            Text("合成音频", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        val p = pane
        if (p != null) {
            when (p) {
                is Pane.Reader -> ReaderPane(c, book, chapters, p.i, { pane = null },
                    { next -> openReader(next) })
            }
        }
    }
}

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

@Composable
private fun SynColumn(ui: List<ChapterUi>, onPlay: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        items(ui, key = { it.index }) { r ->
            val p = r.synthesized?.coerceIn(0f, 1f)
            val done = r.audio != null
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                if (p != null && p > 0f && p < 1f) {
                    Box(Modifier.fillMaxWidth(p).fillMaxHeight()
                        .background(HubGood.copy(alpha = 0.16f)))
                }
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.index + 1}. ${r.title}", fontSize = 14.sp, maxLines = 1,
                            color = if (done || (p ?: 0f) > 0f) Color.Unspecified else HubGrey)
                        if (!done && p != null && p > 0f)
                            Text("本章已生成 ${(p * 100).toInt()}%", fontSize = 12.sp, color = HubSub)
                        if (!done && p == null) {
                            if (r.stalled) Text("没做完 · 接着做会续上", fontSize = 12.sp, color = HubWarn)
                            else Text("等待中", fontSize = 12.sp, color = HubSub)
                        }
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
                    if (r.playable) Text("从本章起播 ›", fontSize = 12.sp, color = HubPrimary)
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

@Composable
private fun ReaderPane(
    c: Context, book: Book, chapters: List<Chapter>, i: Int,
    onClose: () -> Unit, onJump: (Int) -> Unit
) {
    var text by remember(book) { mutableStateOf<String?>(null) }
    LaunchedEffect(book) {
        text = withContext(Dispatchers.IO) { BookStore.read(c, book) }
    }
    val ch = chapters.getOrNull(i) ?: return
    val t = text
    if (t == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val body = remember(i, t) {
        val s = ch.start.coerceIn(0, t.length)
        val e = ch.end.coerceIn(s, t.length)
        t.substring(s, e).split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    var size by remember { mutableStateOf(Prefs.readerFontScale(c)) }
    val saved = remember(book, i) { ReadingState.pos(c, book.id)?.takeIf { it.chapter == i } }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.para ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.offset ?: 0
    )
    LaunchedEffect(book.id, i, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (p, off) ->
            ReadingState.save(c, book.id, i, p, off)
        }
    }
    Column(Modifier.fillMaxSize().background(HubCard)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ 目录") }
            Text(ch.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                size = (size - 1f).coerceIn(12f, 24f)
                Prefs.setReaderFontScale(c, size)
            }) { Text("A-") }
            TextButton(onClick = {
                size = (size + 1f).coerceIn(12f, 24f)
                Prefs.setReaderFontScale(c, size)
            }) { Text("A+") }
        }
        LazyColumn(state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 18.dp)) {
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

@Composable
internal fun MiniPlayBar(c: Context, bookId: String?, onGoBook: (String) -> Unit) {
    val st by PlayQueue.state.collectAsState()
    if (st.index < 0 && st.label.isEmpty()) return
    var showList by remember { mutableStateOf(false) }
    val list = remember(bookId) { bookId?.let { AudioLibrary.playableItems(c, it) } ?: emptyList() }
    LaunchedEffect(st.isPlaying) {
        if (!st.isPlaying) return@LaunchedEffect
        while (isActive) {
            PlayQueue.tick()
            delay(500L)
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
                    fontSize = 12.sp, color = HubSub)
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
                                    val q = "chapter:" + bookId.orEmpty()
                                    PlayQueue.setTracks(list.map { (j, x) ->
                                        Track(x.file, "${x.title} · 第 ${j + 1} 章",
                                            AudioLibrary.chapterStartMs(c, x, j),
                                            id = "chapter:${x.file.absolutePath}")
                                    }, q)
                                    PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)
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
