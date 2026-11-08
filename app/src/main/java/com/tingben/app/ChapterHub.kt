package com.tingben.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import java.io.File

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
    initialCol: Int = 0,
    onBack: () -> Unit,
    onResumeTask: () -> Unit = {},
    onProgress: () -> Unit,
    onPlayBook: (String) -> Unit,
    onMixer: () -> Unit,
    onStarted: (String, Set<Int>) -> Unit
) {
    // 第三列删 / 拼 / 转格式之后自己触发一次重读，不必退回书架再进来
    var localTick by remember { mutableStateOf(0) }
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

    val diskKey = "${!st.running}|${st.stage}"
    var disk by remember(book.id, refresh) {
        mutableStateOf<Pair<Map<Int, AudioItem>, Set<Int>>?>(null)
    }
    LaunchedEffect(book.id, refresh, diskKey, localTick) {
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
    var savedCol by rememberSaveable(book.id) { mutableStateOf(0) }
    // 真分页：由 Pager 自己处理 fling 与吸附，不再靠手写 snapshotFlow 猜列宽
    // 外部带来的目标列优先（从任务 / 音频入口归并过来时直接落在第二 / 第三列）
    val pagerState = rememberPagerState(
        initialPage = if (initialCol > 0) initialCol.coerceIn(0, 2)
                      else savedCol.coerceIn(0, 2), pageCount = { 3 })
    val current = pagerState.currentPage
    LaunchedEffect(initialCol) {
        if (initialCol > 0 && pagerState.currentPage != initialCol)
            pagerState.scrollToPage(initialCol)
    }
    LaunchedEffect(pagerState.currentPage) { savedCol = pagerState.currentPage }

    fun jumpTo(col: Int) {
        scope.launch { pagerState.animateScrollToPage(col.coerceIn(0, 2)) }
    }

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
        val hint = Producer.enqueueOrStart(c, book, selected, null, ProduceConfig())
        Toast.makeText(c, hint, Toast.LENGTH_LONG).show()
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2
            ) { col ->
                when (col) {
                    0 -> RawColumn(ui, selected, { i, on ->
                        selected = if (on) selected + i else selected - i
                    }, { i -> openReader(i) })
                    1 -> SynColumn(ui, { i -> playChapter(i, single = true) },
                        onResumeTask = onResumeTask, onTasks = onProgress,
                        stalled = ui.any { it.stalled })
                    else -> PlayColumn(
                        c = c, book = book, ui = ui,
                        onChanged = { localTick++ },
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

        // 三处多选共用同一条胶囊操作条：章节列 / 可播列 / 分段看台
        if (selected.isNotEmpty()) {
            MultiSelectBar(
                count = selected.size,
                onSelectAll = { selected = ui.indices.toSet() },
                onCancel = { selected = emptySet() },
                allLabel = "全选章节",
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                TextButton(onClick = onMixer) {
                    Text("调音 ›", fontSize = 12.sp, color = HubPrimary)
                }
                TextButton(onClick = { startAction(HubAction.SYNTH) }) {
                    Text("合成音频", fontSize = 12.sp, color = HubPrimary)
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
            val isChecked = r.index in selected
            Row(
                Modifier.fillMaxWidth().height(52.dp)
                    // 章节列本来常驻勾选框；长按整行也能直接勾/取消，
                    // 与可播列、看台的长按手势保持同一套肌肉记忆（R134）。
                    .pointerInput(r.index, isChecked) {
                        detectTapGestures(
                            onLongPress = { onCheck(r.index, !isChecked) },
                            onTap = { onRead(r.index) }
                        )
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${r.index + 1}. ${r.title}", fontSize = 14.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                Checkbox(checked = isChecked,
                    onCheckedChange = { onCheck(r.index, it) })
            }
            HubDivider()
        }
    }
}

@Composable
private fun SynColumn(
    ui: List<ChapterUi>, onPlay: (Int) -> Unit,
    onResumeTask: () -> Unit, onTasks: () -> Unit, stalled: Boolean
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {
        // 任务详情页的章节层已归并到这一列：队列状态 + 续跑 + 去任务中心，
        // 不再需要单独开一页看「哪些章做了、哪些没做」。
        item(key = "syn_task_head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("合成任务", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = HubPrimary)
                Spacer(Modifier.height(4.dp))
                Text("${ui.count { it.audio != null }}/${ui.size} 章已出成品" +
                        if (stalled) " · 有任务没做完" else "",
                    fontSize = 12.sp, color = HubSub)
                Row(Modifier.fillMaxWidth()) {
                    if (stalled) {
                        TextButton(onClick = onResumeTask) {
                            Text("继续制作 ›", fontSize = 12.sp, color = HubPrimary)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onTasks) {
                        Text("任务队列 ›", fontSize = 12.sp, color = HubPrimary)
                    }
                }
            }
            HubDivider()
        }
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
    c: Context, book: Book, ui: List<ChapterUi>, onChanged: () -> Unit,
    onAll: () -> Unit, onFrom: (Int) -> Unit, onStop: () -> Unit
) {
    // R134 · 统一多选态：长按任意章（或点最右三点）进多选 → 方块勾选 → 底部胶囊条批量操作。
    // 原来的「长按/点 ⋯ 弹一个界面选操作」整套撤掉，操作集中在底部条里。
    val finished = ui.mapNotNull { it.audio }
    val totalBytes = finished.sumOf { it.bytes }
    var multiMode by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(setOf<Int>()) }
    // 点删除时把文件清单算好存 state，不在对话框重组里同步读盘
    var confirmDel by remember { mutableStateOf<Map<Int, Pair<List<File>, File?>>?>(null) }
    var pendingSrc by remember { mutableStateOf<File?>(null) }
    var tmpCopy by remember { mutableStateOf<File?>(null) }

    val saveWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val src = pendingSrc ?: tmpCopy
        if (uri != null && src != null)
            Toast.makeText(c, if (AudioOps.copyTo(c, src, uri)) "已复制" else "复制失败",
                Toast.LENGTH_SHORT).show()
        tmpCopy?.delete(); tmpCopy = null; pendingSrc = null
    }
    val saveM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val src = pendingSrc ?: tmpCopy
        if (uri != null && src != null) {
            val tmp = File(c.cacheDir, "conv_${System.currentTimeMillis()}.m4a")
            val r = AudioOps.toM4a(c, src, tmp)
            if (r.ok) Toast.makeText(c,
                if (AudioOps.copyTo(c, tmp, uri)) "已转存 M4A" else "写出失败",
                Toast.LENGTH_SHORT).show()
            else Toast.makeText(c, r.reason, Toast.LENGTH_LONG).show()
            tmp.delete()
        }
        tmpCopy?.delete(); tmpCopy = null; pendingSrc = null
    }

    fun filesOf(i: Int): Pair<List<File>, File?> {
        val ch = AudioTree.load(c).firstOrNull { it.bookId == book.id }
            ?.chapters?.firstOrNull { it.chapter == i }
        val segs = ch?.segments?.map { it.file } ?: emptyList()
        val merged = ch?.merged?.takeIf { it.isFile }
        return segs to merged
    }

    fun enterMulti(i: Int) {
        multiMode = true
        checked = setOf(i)
    }

    fun exitMulti() {
        multiMode = false
        checked = emptySet()
    }

    // 单章动作沿用 3c 的口径，只是从弹菜单搬到底部条
    fun srcOf(i: Int): File? {
        val (segs, merged) = filesOf(i)
        return merged ?: if (segs.size == 1) segs.firstOrNull()
        else AudioOps.mergedTemp(c, segs).let { rr ->
            if (rr.ok) { tmpCopy = rr.file; rr.file } else null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("可播音频 · ${finished.size}/${ui.size} 章",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HubPrimary)
                Text(if (totalBytes > 0)
                        "共 %.1f MB · 本地文件".format(totalBytes / 1024f / 1024f)
                    else "还没有整章成品，先在合成列做几章",
                    fontSize = 12.sp, color = HubSub)
                if (!multiMode)
                    Text("长按任意章可多选 · 选完在底部操作",
                        fontSize = 11.sp, color = HubGrey)
            }
            if (!multiMode) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    TextButton(onClick = onAll, modifier = Modifier.weight(1f)) {
                        Text("按顺序播放", fontSize = 12.sp)
                    }
                    TextButton(onClick = onStop) { Text("停止", fontSize = 12.sp) }
                }
            }
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = if (multiMode) 100.dp else 90.dp)) {
                items(ui, key = { it.index }) { r ->
                    val has = r.playable || r.audio != null
                    Row(Modifier.fillMaxWidth().height(52.dp)
                        // checked 必须进 key：否则 onTap 闭包捕获的是进多选那一刻的旧集合，
                        // 连续勾选会一直基于同一个旧值算，表现为「只能选中一章」。
                        .pointerInput(r.index, multiMode, checked) {
                            detectTapGestures(
                                onLongPress = { if (has) enterMulti(r.index) },
                                onTap = {
                                    if (multiMode) {
                                        if (has) checked = if (r.index in checked)
                                            checked - r.index else checked + r.index
                                    } else if (r.playable) onFrom(r.index)
                                }
                            )
                        }
                        .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.index + 1}. ${r.title}", fontSize = 14.sp, maxLines = 1,
                            color = if (r.playable) Color.Unspecified else HubGrey,
                            modifier = Modifier.weight(1f))
                        if (r.audio != null)
                            Text("%.1f MB".format(r.audio.bytes / 1024f / 1024f),
                                fontSize = 11.sp, color = HubSub)
                        if (multiMode) {
                            SelectBox(
                                checked = r.index in checked,
                                tint = HubPrimary,
                                boxColor = HubCard,
                                modifier = Modifier.size(24.dp)
                                    .padding(start = 6.dp))
                        } else {
                            if (r.playable) Text("从本章起播 ›", fontSize = 12.sp,
                                color = HubPrimary)
                            if (has) {
                                // 点三点 = 只勾这一章并进多选态，不再是弹菜单
                                TextButton(onClick = { enterMulti(r.index) }) {
                                    MoreDots(HubPrimary)
                                }
                            }
                        }
                    }
                    HubDivider()
                }
            }
        }

        if (multiMode) {
            MultiSelectBar(
                count = checked.size,
                onSelectAll = {
                    checked = ui.filter { it.playable || it.audio != null }
                        .map { it.index }.toSet()
                },
                onCancel = { exitMulti() },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (checked.size == 1) {
                    val i = checked.first()
                    TextButton(onClick = {
                        val (segs, _) = filesOf(i)
                        val r = AudioOps.mergeChapter(c, book.id, book.title, i, segs)
                        Toast.makeText(c, if (r.ok) r.reason.ifBlank { "已拼成整章" }
                            else r.reason, Toast.LENGTH_LONG).show()
                        exitMulti(); onChanged()
                    }) { Text("把这一章拼成一个音频", fontSize = 12.sp) }
                    TextButton(onClick = {
                        val src = srcOf(i)
                        if (src != null) { pendingSrc = src; saveWav.launch("chapter.wav") }
                        else Toast.makeText(c, "这一章还没有可导出的音频",
                            Toast.LENGTH_SHORT).show()
                        exitMulti()
                    }) { Text("复制 / 另存为 WAV", fontSize = 12.sp) }
                    TextButton(onClick = {
                        val src = srcOf(i)
                        if (src != null) { pendingSrc = src; saveM4a.launch("chapter.m4a") }
                        else Toast.makeText(c, "这一章还没有可转的音频",
                            Toast.LENGTH_SHORT).show()
                        exitMulti()
                    }) { Text("转格式（WAV → M4A）", fontSize = 12.sp) }
                }
                TextButton(onClick = {
                    if (checked.isEmpty()) {
                        Toast.makeText(c, "先勾几章", Toast.LENGTH_SHORT).show()
                    } else {
                        confirmDel = checked.associateWith { filesOf(it) }
                    }
                }) { Text("删除选中", fontSize = 12.sp, color = HubWarn) }
            }
        }
    }

    // 批量删除：一次确认，逐章清文件与索引
    if (confirmDel != null) {
        val all = confirmDel!!
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删除选中的 ${all.size} 章音频？", fontSize = 15.sp,
                fontWeight = FontWeight.Bold) },
            text = { Text("共 ${all.values.sumOf { it.first.size + (if (it.second != null) 1 else 0) }} 个文件，" +
                "不能恢复；没拼过的章，下次续跑会重新生成这些段。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    var failed = 0
                    all.forEach { (i, files) ->
                        val (segs, merged) = files
                        failed += AudioOps.deleteAll(segs)
                        merged?.let { if (!AudioOps.deleteMerged(c, it)) failed++ }
                        AudioTree.forget(c, segs.filter { !it.isFile }.map { it.absolutePath })
                        AudioTree.clearDone(c, book.id, i)
                    }
                    PlayQueue.release()
                    Toast.makeText(c, if (failed > 0) "有 $failed 个没删干净"
                        else "已删除 ${all.size} 章的音频", Toast.LENGTH_SHORT).show()
                    confirmDel = null
                    exitMulti(); onChanged()
                }) { Text("删除", color = HubWarn) }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } }
        )
    }
}

// 代码绘制的三点操作图标（R132）：不再用「⋯」字符当图标。
@Composable
private fun MoreDots(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val r = 1.8.dp.toPx()
        val y = size.height / 2f
        listOf(0.28f, 0.50f, 0.72f).forEach { x ->
            drawCircle(tint, r, androidx.compose.ui.geometry.Offset(size.width * x, y))
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

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun MiniPlayBar(
    c: Context, bookId: String?, onGoBook: (String) -> Unit, onExpand: () -> Unit
) {
    val st by PlayQueue.state.collectAsState()
    // 两态隔离（R133）：制作中的小段 WAV / 调音台试听只在原格子就地播，
    // 不占这条常驻栏，也不会被误当成「一次收听」塞进播放器页和通知栏。
    if (st.preview) return
    if (st.index < 0 && st.label.isEmpty()) return
    LaunchedEffect(st.isPlaying) {
        if (!st.isPlaying) return@LaunchedEffect
        while (isActive) {
            PlayQueue.tick()
            delay(500L)
        }
    }
    val frac = if (st.durationMs > 0)
        (st.positionMs.toFloat() / st.durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    // 整条可点：点一下展开全屏播放器页，像音乐 App 那样
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
        .background(HubCard, RoundedCornerShape(18.dp))
        .clickable { onExpand() }
        .padding(horizontal = 4.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp)
            .background(HubLine, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight()
                .background(HubPrimary, RoundedCornerShape(50)))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.label.ifBlank { "未在播放" }, fontSize = 13.sp, maxLines = 1,
                    fontWeight = FontWeight.Bold)
                Text("${st.positionMs / 1000}s / ${st.durationMs / 1000}s",
                    fontSize = 12.sp, color = HubSub)
            }
            // 全部换成代码绘制的真图标，不再用「‹ ⏸ ▶ ›」字符
            IconButton(onClick = { PlayQueue.previous() }, modifier = Modifier.size(38.dp)) {
                IconSkip(false, HubPrimary, Modifier.size(20.dp))
            }
            IconButton(onClick = { PlayQueue.toggle() },
                modifier = Modifier.size(38.dp)
                    .background(HubPrimary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, Color.White, Modifier.size(18.dp))
            }
            IconButton(onClick = { PlayQueue.next() }, modifier = Modifier.size(38.dp)) {
                IconSkip(true, HubPrimary, Modifier.size(20.dp))
            }
        }
    }
}
