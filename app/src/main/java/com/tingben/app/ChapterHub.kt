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

/**
 * J1 · 分段格子底色，两列共用同一口径。
 *
 * 原来「已生成 / 未生成」两态都用 alpha=0.18f：浅色下未生成格叠在白卡上
 * ≈ #F5F6F8，与卡片几乎同色 —— 按真实色值画的界面草图里，71 号以后
 * 只剩飘着的数字，完全看不出「这里还有格子」；深色下同样偏糊。
 * 这不是数据问题（R128 修过灰格消失），是对比度问题。按主题分别给可见度。
 */
private fun segCellColor(playable: Boolean, playing: Boolean,
                         multi: Boolean = false, checked: Boolean = false): Color = when {
    multi && checked -> HubPrimary
    playing -> HubPrimary
    playable -> HubGood.copy(alpha = if (appThemeDark) 0.34f else 0.26f)
    else -> HubGrey.copy(alpha = if (appThemeDark) 0.46f else 0.48f)
}

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
    // R138 · 重新分章后强制重读落盘章节表
    var planTick by remember { mutableStateOf(0) }
    var showResplit by remember { mutableStateOf(false) }
    var resplitBusy by remember { mutableStateOf(false) }
    var resplitPreview by remember { mutableStateOf<Pair<String, Pair<Int, List<String>>>?>(null) }
    val resplitScope = rememberCoroutineScope()
    var loadDone by remember(id, refresh, planTick) { mutableStateOf(false) }
    var load by remember(id, refresh, planTick) { mutableStateOf<HubLoad?>(null) }
    var loadError by remember(id, refresh, planTick) { mutableStateOf<String?>(null) }
    // I3 · 首章前文本的诊断跟章节表共用同一次 IO 读盘，不额外读一遍整本。
    var front by remember(id, refresh, planTick) {
        mutableStateOf<ChapterPlan.FrontInfo?>(null)
    }
    LaunchedEffect(id, refresh, planTick) {
        val r = withContext(Dispatchers.IO) {
            try {
                val b = id?.let { BookStore.list(c).find { it.id == id } }
                if (b == null) Triple(null, null, null) else {
                    val text = BookStore.read(c, b)
                    val s = ChapterPlan.resolve(c, b, text)
                    Triple(
                        HubLoad(b, s.first,
                            AudioLibrary.latestByChapter(c, b.id),
                            AudioLibrary.playableChapters(c, b.id),
                            ReadingState.last(c, b.id)),
                        null,
                        ChapterPlan.frontMatter(text, s.first))
                }
            } catch (t: Throwable) {
                Triple(null, t.message ?: "读这本书时出错", null)
            }
        }
        load = r.first
        loadError = r.second
        front = r.third
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
            Text(loadError ?: "这本书不在了")
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
    // G2 · 章节就地展开小段：点章名展开/收起，不再跳到独立看台页。
    // 小段只走 preview 试听通道，不占底部常驻条、不进播放器页。
    var expandedChapter by remember(book.id) { mutableStateOf<Int?>(null) }
    // G2b · 一次读盘、两列共用同一份真值：第一列「按章展开」与第二列
    // 「全量分段看台」都从 hubSegs 派生，不再各自扫盘、口径不会打架。
    var hubSegs by remember(book.id) { mutableStateOf(emptyList<SegRow>()) }
    var hubSegChapters by remember(book.id) { mutableStateOf<Map<Int, Int>?>(null) }
    val pqSeg by PlayQueue.previewState.collectAsState()
    LaunchedEffect(book.id, st.done, localTick, task?.bookId) {
        val (all, map) = withContext(Dispatchers.IO) {
            val t = task?.takeIf { it.bookId == book.id } ?: TaskResume.byBook(c, book.id)
            Workbench.segments(c, t) to Workbench.segChapters(c, t)
        }
        hubSegs = all
        hubSegChapters = map
    }
    val chapterSegs = remember(expandedChapter, hubSegs, hubSegChapters) {
        val ch = expandedChapter
        val map = hubSegChapters
        if (ch == null || map == null) emptyList()
        else hubSegs.filter { map[it.index] == ch }
    }
    // 制作中每秒刷一次「模型正在干嘛」，从独立看台页搬进书内。
    var beatTick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) return@LaunchedEffect
        while (true) { delay(1000); beatTick++ }
    }
    val heartbeat = remember(beatTick, st.done) {
        val (act, _) = EventLog.lastEngine()
        val age = (EventLog.lastEngineAgeMs() / 1000).coerceAtLeast(0)
        val what = when {
            act.contains("PREFILL") -> "正在把这一段读进模型，手机上这一步可能要一两分钟"
            act.contains("DECODE") -> "正在逐字生成这一块的朗读分段"
            act.contains("TOKENIZE") || act.contains("ENTER") -> "正在准备文字"
            act.contains("TTS") -> "正在把文字读成声音"
            else -> "正在准备"
        }
        what + if (st.running && age > 100) " · 已 ${age} 秒没新动作，可能算得很慢" else ""
    }
    // 删小段是破坏性操作：第二列发起，这里统一做二次确认与 IO 落盘。
    var confirmSegDel by remember { mutableStateOf<List<File>?>(null) }
    var pane by remember(book) { mutableStateOf<Pane?>(null) }
    fun openReader(i: Int) {
        EventLog.ui("OPEN_READER", "ch=$i", mapOf("book" to book.id))
        if (i in chapters.indices) {
            ReadingState.save(c, book.id, i)
            pane = Pane.Reader(i)
        }
    }
    val lastRead = snapshot.lastRead

    BackHandler(enabled = pane != null) { pane = null }

    val scope = rememberCoroutineScope()
    // R148 · 用户实证：删书重导后直接落在第二列。rememberSaveable 把「上次看哪列」
    // 跨会话记住了，而 bookId 由书名派生，重导同一本就沿用旧值。改成仅本次组合内记忆：
    // 进书默认第一列（章节），只有从任务/制作等入口显式指定才落到别的列。
    var savedCol by remember(book.id) { mutableStateOf(0) }
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
    LaunchedEffect(pagerState.currentPage) {
        savedCol = pagerState.currentPage
        EventLog.ui("HUB_COL", "col=${pagerState.currentPage}", mapOf("book" to book.id))
    }

    fun jumpTo(col: Int) {
        EventLog.ui("HUB_JUMP", "col=$col")
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

    // R138 · 预览放 IO 线程：只读文本扫一遍标题，不切块、不占常驻状态
    LaunchedEffect(showResplit, book.id, planTick) {
        if (!showResplit) return@LaunchedEffect
        resplitBusy = true
        resplitPreview = withContext(Dispatchers.IO) {
            try { ChapterPlan.preview(c, book, null) } catch (_: Throwable) { null }
        }
        resplitBusy = false
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
            // R138 · 让用户一眼看到「这本书分了几章、用的哪套规则」，不再靠猜
            val curRule = remember(book.id, planTick) {
                ChapterPlan.currentRuleId(c, book.id)
            }
            Row(Modifier.fillMaxWidth().background(HubCard)
                .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${chapters.size} 章 · ${ChapterPlan.ruleName(curRule)}",
                    fontSize = 11.sp, color = HubSub, maxLines = 1,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    EventLog.ui("RESPLIT_OPEN", "", mapOf("book" to book.id))
                    showResplit = true
                }, enabled = !st.running) {
                    Text("重新分章 ›", fontSize = 12.sp, color = HubPrimary)
                }
            }
            // I3 · 只在书前确实有一段「站点/出版信息」时出现，正常书不占空间。
            front?.let { fi ->
                if (fi.chars > 40 && fi.noisy) {
                    Text("书前 ${fi.chars} 字是下载/出版信息，不纳入朗读（不是漏读）",
                        fontSize = 10.sp, color = HubWarn, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().background(HubCard)
                            .padding(horizontal = 14.dp, vertical = 3.dp))
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
            // G2 · 连贯骨架：整本进度、当前章进度、一个停止/继续，全在目录页顶部。
            // 用户实证「像在不同卡片里切来切去」——不再让用户去任务页/看台页
            // 才能知道跑到哪、才能按停止。
            val bookRunning = st.running && task?.bookId == book.id
            val stalledTask = !st.running && task?.bookId == book.id &&
                st.stage in listOf("没完成", "已停止", "出错了")
            val totalNow = maxOf(st.total, task?.total ?: 0)
            val doneNow = (if (bookRunning) st.done else (task?.done ?: 0))
                .coerceIn(0, maxOf(totalNow, 1))
            val bookPct = if (totalNow > 0) doneNow.toFloat() / totalNow.toFloat() else 0f
            val chNow = expandedChapter
            val chPct = if (chNow == null) 0f else
                (ui.getOrNull(chNow)?.synthesized
                    ?: st.chapterProgress[chNow] ?: 0f).coerceIn(0f, 1f)
            val showHead = bookRunning || stalledTask || chNow != null ||
                (task?.bookId == book.id && st.stage == "完成")
            if (showHead) {
                Column(Modifier.fillMaxWidth().background(HubCard)
                    .padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                bookRunning -> "正在制作 · $doneNow/$totalNow 块"
                                stalledTask -> "没做完 · 已做好的都留着"
                                chNow != null -> "整本 $doneNow/$totalNow 块"
                                else -> "这本书做好了"
                            },
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = if (stalledTask) HubWarn else HubPrimary,
                            modifier = Modifier.weight(1f))
                        if (bookRunning) {
                            Button(onClick = {
                                EventLog.ui("HUB_STOP", "book=${book.id}")
                                Producer.stop()
                            }, modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = HubWarn)) {
                                Text("停止", fontSize = 12.sp, color = palette.onWarn)
                            }
                        } else if (stalledTask) {
                            Button(onClick = {
                                EventLog.ui("HUB_RESUME", "book=${book.id}")
                                onResumeTask()
                            }, modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text("继续", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { bookPct },
                        modifier = Modifier.fillMaxWidth().height(6.dp))
                    if (chNow != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("第 ${chNow + 1} 章 · ${(chPct * 100).toInt()}%" +
                                if (chapterSegs.isEmpty()) " · 还没有小段" else
                                    " · ${chapterSegs.count { it.hasAudio }}/${chapterSegs.size} 小段已生成",
                            fontSize = 11.sp, color = HubSub)
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(progress = { chPct },
                            modifier = Modifier.fillMaxWidth().height(4.dp))
                    }
                }
                HubDivider()
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 2
            ) { col ->
                when (col) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        // R142 · 原来只有章节名右边一枚小 Checkbox，能点的范围
                        // 只有 24dp，也没有全选/反选。顶部常驻一排批量操作，
                        // 勾选热区整块 52dp，不用再靠长按或瞄准小方框。
                        Row(Modifier.fillMaxWidth().background(HubSoft)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selected.size}/${ui.size} 章", fontSize = 12.sp,
                                color = HubPrimary, modifier = Modifier.weight(1f))
                            listOf("全选" to {
                                       selected = ui.map { it.index }.toSet()
                                       EventLog.ui("CH_SELECT_ALL", "n=${selected.size}")
                                   },
                                   "反选" to {
                                       selected = ui.map { it.index }.toSet() - selected
                                       EventLog.ui("CH_SELECT_INVERT", "n=${selected.size}")
                                   },
                                   "清空" to {
                                       selected = emptySet<Int>()
                                       EventLog.ui("CH_SELECT_CLEAR", "")
                                   }).forEach { (label, act) ->
                                TextButton(onClick = act,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                    Text(label, fontSize = 12.sp, color = HubPrimary)
                                }
                            }
                        }
                        RawColumn(
                            ui = ui, selected = selected,
                            onCheck = { i, on ->
                                selected = if (on) selected + i else selected - i
                                EventLog.ui("CH_TOGGLE", "ch=$i on=$on n=${selected.size}")
                            },
                            onRead = { i -> openReader(i) },
                            expanded = expandedChapter,
                            onExpand = { i ->
                                expandedChapter = if (expandedChapter == i) null else i
                                EventLog.ui("HUB_CH_EXPAND",
                                    "ch=$i open=${expandedChapter != null}",
                                    mapOf("book" to book.id))
                            },
                            segs = chapterSegs, pq = pqSeg,
                            modifier = Modifier.weight(1f))
                    }
                    1 -> SynColumn(c, ui, { i -> playChapter(i, single = true) },
                        onResumeTask = onResumeTask, onTasks = onProgress,
                        stalled = ui.any { it.stalled },
                        segs = hubSegs, pq = pqSeg,
                        running = bookRunning, heartbeat = heartbeat,
                        onDeleteSegs = { files -> confirmSegDel = files })
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
                onSelectAll = {
                    selected = ui.indices.toSet()
                    EventLog.ui("MULTI_ALL", "n=${selected.size}")
                },
                onCancel = {
                    selected = emptySet()
                    EventLog.ui("MULTI_CANCEL", "")
                },
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

        // G2b · 分段看台搬进第二列后，删小段的破坏性操作在这里统一收口：
        // 二次确认 → IO 删除 → 索引记账 → 刷新两列，不留在独立页里。
        confirmSegDel?.let { files ->
            AlertDialog(
                onDismissRequest = { confirmSegDel = null },
                title = { Text("删除这 ${files.size} 个小块？", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = {
                    Text("只删已生成的 WAV，分段计划还在；下次续跑会按原顺序重新生成，" +
                            "已拼好的整章音频不受影响。",
                        fontSize = 13.sp, color = HubSub)
                },
                confirmButton = {
                    TextButton(onClick = {
                        EventLog.ui("SEG_DELETE", "n=${files.size}")
                        resplitScope.launch(Dispatchers.IO) {
                            val failed = AudioOps.deleteAll(files)
                            AudioTree.forget(c,
                                files.filter { !it.isFile }.map { it.absolutePath })
                            withContext(Dispatchers.Main) {
                                PlayQueue.release()
                                confirmSegDel = null
                                localTick++
                                Toast.makeText(c,
                                    if (failed > 0) "有 $failed 个没删干净"
                                    else "已删除 ${files.size} 个小块",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("删除", fontSize = 13.sp, color = HubWarn) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSegDel = null }) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            )
        }

        val p = pane
        if (p != null) {
            when (p) {
                is Pane.Reader -> ReaderPane(c, book, chapters, p.i, { pane = null },
                    { next -> openReader(next) })
            }
        }

        // R138 · 重新分章：先预览章数与头尾标题，确认才应用（成熟阅读器的闭环）
        if (showResplit) {
            val pv = resplitPreview
            AlertDialog(
                onDismissRequest = { showResplit = false },
                title = { Text("重新分章", fontSize = 15.sp) },
                text = {
                    Column {
                        if (resplitBusy || pv == null) {
                            Text("正在识别这本书的章节…", fontSize = 12.sp, color = HubSub)
                        } else {
                            Text("自动识别：${ChapterPlan.ruleName(pv.first)} · ${pv.second.first} 章",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            pv.second.second.forEach {
                                Text("· $it", fontSize = 12.sp, color = HubSub, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("章号会变，已做好的音频需要重新生成；原书文字不动。",
                            fontSize = 12.sp, color = HubWarn)
                    }
                },
                confirmButton = {
                    TextButton(enabled = pv != null && !st.running, onClick = {
                        showResplit = false
                        resplitScope.launch {
                            val rr = withContext(Dispatchers.IO) {
                                runCatching { ChapterPlan.refresh(c, book) }
                            }
                            val err = rr.exceptionOrNull()
                            if (err != null) {
                                Toast.makeText(c, err.message ?: "重新分章失败",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                val (rid, n) = rr.getOrThrow()
                                planTick++
                                Toast.makeText(c,
                                    "已按${ChapterPlan.ruleName(rid)}重新分为 $n 章",
                                    Toast.LENGTH_LONG).show()
                                EventLog.log(EventLog.Kind.MODEL, "CHAPTER_RESPLIT",
                                    "书=${book.title} 规则=$rid 章数=$n")
                            }
                        }
                    }) { Text("应用", color = HubPrimary) }
                },
                dismissButton = {
                    TextButton(onClick = { showResplit = false }) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun RawColumn(
    ui: List<ChapterUi>, selected: Set<Int>,
    onCheck: (Int, Boolean) -> Unit, onRead: (Int) -> Unit,
    expanded: Int?, onExpand: (Int) -> Unit,
    segs: List<SegRow>, pq: PlaybackState,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 90.dp)) {
        items(ui, key = { it.index }) { r ->
            val isChecked = r.index in selected
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        // G2 · 点章名 = 就地展开/收起小段（用户明确要的折叠）。
                        // 阅读挪到行尾独立小按钮；长按仍是勾选，肌肉记忆不变。
                        .pointerInput(r.index, isChecked) {
                            detectTapGestures(
                                onLongPress = { onCheck(r.index, !isChecked) },
                                onTap = { onExpand(r.index) }
                            )
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${r.index + 1}. ${r.title}", fontSize = 14.sp,
                        maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        if (expanded == r.index) "收起 ⌃" else "展开 ›",
                        fontSize = 11.sp, color = HubSub,
                        modifier = Modifier.padding(end = 6.dp))
                    TextButton(onClick = { onRead(r.index) },
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("读", fontSize = 11.sp, color = HubPrimary)
                    }
                    Box(Modifier.size(52.dp)
                            .clickable { onCheck(r.index, !isChecked) },
                        contentAlignment = Alignment.Center) {
                        SelectBox(checked = isChecked, tint = HubPrimary,
                            boxColor = HubCard, modifier = Modifier.size(20.dp))
                    }
                }
                if (expanded == r.index) {
                    ChapterSegGrid(segs, pq)
                }
                HubDivider()
            }
        }
    }
}

/**
 * G2 · 章节下面就地展开的小段格子。
 * 用户实证：「序号不需要有播放/停止这样的按钮盖在数字上面」——
 * 所以格子只显示序号，靠底色与高亮表达状态；点一下播、再点一下停，
 * 走 PlayQueue 的 preview 通道，不占常驻条、不进播放器页、不进通知栏。
 */
@Composable
private fun ChapterSegGrid(segs: List<SegRow>, pq: PlaybackState) {
    Column(Modifier.fillMaxWidth().background(HubSoft)
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (segs.isEmpty()) {
            Text("这一章还没生成小段 · 先在合成列开始制作",
                fontSize = 12.sp, color = HubSub)
        } else {
        Text("本章 ${segs.size} 小段 · 点一下试听，再点一下停",
            fontSize = 11.sp, color = HubSub)
        Spacer(Modifier.height(6.dp))
        segs.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { sg ->
                    val segId = sg.wav?.let { "seg:" + it.absolutePath } ?: ""
                    val playable = sg.hasAudio && segId.isNotEmpty()
                    val onThis = playable && pq.trackId == segId
                    val playing = onThis && pq.isPlaying
                    Box(
                        Modifier.size(30.dp)
                            .background(segCellColor(playable, playing),
                                RoundedCornerShape(8.dp))
                            .clickable(enabled = playable) {
                                EventLog.ui("SEG_TAP", "seg=${sg.index} play=$playing")
                                if (playing) PlayQueue.pausePreview()
                                else if (onThis) PlayQueue.resumePreview()
                                else sg.wav?.let { wav ->
                                    PlayQueue.setTracks(
                                        listOf(Track(wav, "第 ${sg.index + 1} 段", id = segId)),
                                        "seg")
                                    PlayQueue.playFrom(0, id = "seg")
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${sg.index + 1}", fontSize = 11.sp,
                            color = if (playing) palette.onPrimary
                                    else if (playable) HubPrimary else HubSub)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SynColumn(
    c: Context, ui: List<ChapterUi>, onPlay: (Int) -> Unit,
    onResumeTask: () -> Unit, onTasks: () -> Unit, stalled: Boolean,
    segs: List<SegRow>, pq: PlaybackState, running: Boolean,
    heartbeat: String, onDeleteSegs: (List<File>) -> Unit
) {
    // G2b · 第二列就是原来的「分段看台」，不再另开一页。
    // 多选条放在列内顶部（和第一列「全选/反选/清空」同风格），
    // 不与章节多选的底部胶囊条叠层。
    var segMulti by remember { mutableStateOf(false) }
    var segChecked by remember { mutableStateOf(setOf<Int>()) }
    fun exitMulti() { segMulti = false; segChecked = emptySet() }
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
        // G2b · 全量分段看台：整本次序的灰/绿格子，独立于第一列「按章展开」的便捷视图。
        // G2b · 大书可能有几千段：整片塞进一个 item 会一次性组合、直接卡死。
        // 拆成「头部一个 item + 每行一个惰性 item」，LazyColumn 只组合看得见的行。
        item(key = "syn_seg_bench_head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分段看台 · ${segs.size} 段", fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = HubPrimary,
                        modifier = Modifier.weight(1f))
                    Text("${segs.count { it.hasAudio }} 段已生成",
                        fontSize = 12.sp, color = HubSub)
                }
                if (running) {
                    Spacer(Modifier.height(4.dp))
                    Text(heartbeat, fontSize = 11.sp, color = HubPrimary, maxLines = 2)
                }
                if (segMulti) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${segChecked.size}", fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, color = HubPrimary)
                        listOf(
                            "全选" to {
                                segChecked = segs.filter { it.hasAudio }
                                    .map { it.index }.toSet()
                                EventLog.ui("SEG_ALL", "n=${segChecked.size}")
                            },
                            "试听选中" to {
                                val picked = segChecked.sorted().mapNotNull { i ->
                                    segs.getOrNull(i)?.wav?.let { w ->
                                        Track(w, "第 ${i + 1} 段",
                                            id = "seg:${w.absolutePath}")
                                    }
                                }
                                if (picked.isEmpty()) {
                                    Toast.makeText(c, "先勾几个已生成的小块",
                                        Toast.LENGTH_SHORT).show()
                                } else {
                                    PlayQueue.setTracks(picked, "seg")
                                    PlayQueue.playFrom(0, id = "seg")
                                    EventLog.ui("SEG_TRIAL_PICKED", "n=${picked.size}")
                                    exitMulti()
                                }
                            },
                            "删除选中" to {
                                val files = segChecked.sorted()
                                    .mapNotNull { i -> segs.getOrNull(i)?.wav }
                                if (files.isEmpty()) {
                                    Toast.makeText(c, "先勾几个已生成的小块",
                                        Toast.LENGTH_SHORT).show()
                                } else {
                                    EventLog.ui("SEG_DELETE_ASK", "n=${files.size}")
                                    onDeleteSegs(files)
                                    exitMulti()
                                }
                            },
                            "取消" to { exitMulti() }
                        ).forEach { (label, act) ->
                            TextButton(onClick = act,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 5.dp,
                                    vertical = 0.dp)) {
                                Text(label, fontSize = 11.sp,
                                    color = if (label == "删除选中") HubWarn else HubPrimary)
                            }
                        }
                    }
                } else if (segs.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text("点格子试听 · 长按可多选", fontSize = 11.sp, color = HubSub)
                }
                if (segs.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("还没开始制作 · 在章节列选章后点「合成音频」",
                        fontSize = 12.sp, color = HubSub)
                }
            }
        }
        items(segs.chunked(10), key = { row -> "seg_row_${row.first().index}" }) { row ->
            Row(Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { sg ->
                                val segId = sg.wav?.let { "seg:" + it.absolutePath } ?: ""
                                val playable = sg.hasAudio && segId.isNotEmpty()
                                val onThis = playable && pq.trackId == segId
                                val playing = onThis && pq.isPlaying
                                val checked = sg.index in segChecked
                                Box(
                                    Modifier.size(28.dp)
                                        .background(
                                            segCellColor(playable, playing, segMulti, checked),
                                            RoundedCornerShape(7.dp))
                                        .pointerInput(sg.index, segMulti, checked,
                                            playable, playing, onThis) {
                                            detectTapGestures(
                                                onLongPress = {
                                                    if (playable) {
                                                        segMulti = true
                                                        segChecked = setOf(sg.index)
                                                    }
                                                },
                                                onTap = {
                                                    if (segMulti) {
                                                        if (playable) segChecked =
                                                            if (checked) segChecked - sg.index
                                                            else segChecked + sg.index
                                                    } else if (playing) {
                                                        PlayQueue.pausePreview()
                                                    } else if (onThis) {
                                                        PlayQueue.resumePreview()
                                                    } else sg.wav?.let { wav ->
                                                        PlayQueue.setTracks(
                                                            listOf(Track(wav,
                                                                "第 ${sg.index + 1} 段",
                                                                id = segId)), "seg")
                                                        PlayQueue.playFrom(0, id = "seg")
                                                        EventLog.ui("SEG_TAP",
                                                            "seg=${sg.index}")
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (segMulti) {
                                        SelectBox(checked = checked, tint = HubPrimary,
                                            boxColor = HubCard,
                                            modifier = Modifier.size(16.dp))
                                    } else {
                                        Text("${sg.index + 1}", fontSize = 10.sp,
                                            color = if (playing) palette.onPrimary
                                                    else if (playable) HubPrimary else HubSub)
                                    }
                                }
                            }
            }
        }
        item(key = "syn_seg_bench_tail") { HubDivider() }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${st.positionMs / 1000}s / ${st.durationMs / 1000}s",
                        fontSize = 12.sp, color = HubSub)
                    Spacer(Modifier.weight(1f))
                    // K1 · 用户实证「播放整章应该能调倍速」。原来倍速只藏在全屏
                    // 播放器页里，听整章时看到的是这条常驻条，根本找不到入口。
                    // 这里给一枚常驻胶囊：点一下循环切档，播放器页仍留五档直选。
                    Box(Modifier.height(26.dp)
                            .background(HubSoft, RoundedCornerShape(50))
                            .clickable {
                                val next = PlayQueue.nextSpeed(st.speed)
                                PlayQueue.setSpeed(next)
                                EventLog.ui("PLAY_SPEED", "x=$next from=bar")
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(PlayQueue.speedLabel(st.speed), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, color = HubPrimary)
                    }
                }
            }
            // 全部换成代码绘制的真图标，不再用「‹ ⏸ ▶ ›」字符
            IconButton(onClick = { PlayQueue.previous() }, modifier = Modifier.size(38.dp)) {
                IconSkip(false, HubPrimary, Modifier.size(20.dp))
            }
            IconButton(onClick = { PlayQueue.toggle() },
                modifier = Modifier.size(38.dp)
                    .background(HubPrimary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, palette.onPrimary, Modifier.size(18.dp))
            }
            IconButton(onClick = { PlayQueue.next() }, modifier = Modifier.size(38.dp)) {
                IconSkip(true, HubPrimary, Modifier.size(20.dp))
            }
        }
    }
}
