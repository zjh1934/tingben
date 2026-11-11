package com.tingben.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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


/**
 * J1 · 分段格子底色，两列共用同一口径。
 *
 * 原来「已生成 / 未生成」两态都用 alpha=0.18f：浅色下未生成格叠在白卡上
 * ≈ #F5F6F8，与卡片几乎同色 —— 按真实色值画的界面草图里，71 号以后
 * 只剩飘着的数字，完全看不出「这里还有格子」；深色下同样偏糊。
 * 这不是数据问题（R128 修过灰格消失），是对比度问题。按主题分别给可见度。
 */
private fun segCellColor(playable: Boolean, playing: Boolean,
                         multi: Boolean = false, checked: Boolean = false,
                         pendingRedo: Boolean = false): Color = when {
    multi && checked -> HubPrimary
    playing -> HubPrimary
    // R253 · 标记重做后立刻转琥珀「待重做」，不再看着还是绿的、点了没反应。
    pendingRedo -> HubWarn.copy(alpha = if (appThemeDark) 0.44f else 0.36f)
    playable -> HubGood.copy(alpha = if (appThemeDark) 0.34f else 0.26f)
    else -> HubGrey.copy(alpha = if (appThemeDark) 0.46f else 0.48f)
}

/** R164 · 一排固定 10 列。整块居中、行内左对齐，不满一行也靠左。 */
internal const val SEG_GRID_COLUMNS = 10

/**
 * R164 · 深色下格子底色偏亮，序号再用 sub / primary 只有 2.93 / 2.87:1，
 * 看着就是「字不显眼」。改取 text 后是 5.29 / 4.84:1；浅色沿用原色不动。
 */
private fun segNumberColor(playing: Boolean, playable: Boolean,
                           multi: Boolean, checked: Boolean,
                           pendingRedo: Boolean = false): Color = when {
    multi && checked -> palette.onPrimary
    playing -> palette.onPrimary
    pendingRedo -> palette.text
    appThemeDark -> palette.text
    playable -> HubPrimary
    else -> HubSub
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


/**
 * R163 · 目录页合成一列。
 *
 * 用户实证：17:18 一分钟内「章节 / 合成 / 可播」三列来回切十几次，
 * 「像在不同卡片里切来切去，不像在看一本有声书」。三列本质上是同一份
 * 章节数据的三种视图，硬拆成三页只会逼用户来回找。现在：
 *   · 点章名 = 进阅读（文本可复制），不再专门给「读」按钮
 *   · 右侧两枚图标：听本章 / 展开小片（R171 撤掉三点「更多」，选中一律长按）
 *   · 长按章行 = 进多选，勾选框**只在多选态出现**，平时不占位
 *     R171：勾选态里按返回键 / 点「取消」都退出勾选并留在目录页，
 *     不会一按返回就离开这本书；选空了底部条也还在，取消入口不消失
 *   · 展开的章就地铺小片格子，点一下播再点一下停，长按可批量删/试听
 *   · 整本与章进度、停止/继续、可播汇总、按顺序播放、任务队列全在顶部
 *   · 原第三列的「拼整章 / 另存 WAV / 转 M4A / 删章音频」搬进章级多选条，
 *     能力搬干净了才删列，不随列一起丢
 *
 * 沙盒无 Android SDK，真编译由 CI 负责；静态绿 ≠ 编译过。
 */
@Composable
internal fun ChapterHubScreen(
    c: Context, id: String?, refresh: Int,
    onBack: () -> Unit,
    onResumeTask: () -> Unit = {},
    onPlayBook: (String) -> Unit,
    onMixer: () -> Unit,
    // R197 · 制作设置页的唯一入口。之前导航壳重排只留了「一键按默认配置
    // 合成」，把档位/语速/情绪/缓存摘要/从头重做那套能力变成了够不着的孤儿。
    onProduceSetup: (String) -> Unit = {},
    onStarted: (String, Set<Int>) -> Unit,
    onOpenBook: (String) -> Unit = {},
    // R178 · 通知点进来时直接展开就地任务看板，不用再切独立任务页
    openTasksOnStart: Boolean = false,
    // R180 · 模型没齐时「去导入模型 ›」的出口
    onOpenModels: () -> Unit = {},
    // R218 · 制作设置页点「重新分章」回本页时唤起弹窗
    resplitRequest: Int = 0,
    // R226 · 顶栏「任务」按钮点一下就开就地看板；计数器递增，不复位不误弹
    tasksRequest: Int = 0
) {
    var localTick by remember { mutableStateOf(0) }
    var planTick by remember { mutableStateOf(0) }
    var showResplit by remember { mutableStateOf(false) }
    LaunchedEffect(resplitRequest) {
        if (resplitRequest > 0) {
            showResplit = true
            EventLog.ui("RESPLIT_OPEN", "from=produce_setup")
        }
    }
    var resplitBusy by remember { mutableStateOf(false) }
    var resplitPreview by remember { mutableStateOf<Pair<String, Pair<Int, List<String>>>?>(null) }
    val resplitScope = rememberCoroutineScope()
    var loadDone by remember(id, refresh, planTick) { mutableStateOf(false) }
    var load by remember(id, refresh, planTick) { mutableStateOf<HubLoad?>(null) }
    var loadError by remember(id, refresh, planTick) { mutableStateOf<String?>(null) }
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
            TextButton(onClick = onBack) { Text("返回") }
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

    // R163 · 勾选框平时不渲染，长按 / 点三点才进多选态。
    var selected by rememberSaveable(book.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var multiMode by remember(book.id) { mutableStateOf(false) }
    var expandedChapter by remember(book.id) { mutableStateOf<Int?>(null) }
    // R253 · 长按章行弹就地菜单（像桌面右键），与「勾选后进底部批量条」是两组操作。
    var menuChapter by remember(book.id) { mutableStateOf<Int?>(null) }
    var segMulti by remember(book.id) { mutableStateOf(false) }
    var segChecked by remember(book.id) { mutableStateOf(setOf<Int>()) }
    var showQueue by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(openTasksOnStart) }
    LaunchedEffect(tasksRequest) {
        if (tasksRequest > 0) {
            showTasks = true
            EventLog.ui("HUB_TASKS_OPEN", "from=topbar")
        }
    }
    // R178 · 真机日志里 17:59:40 STOP → 17:59:41 RESUME 只隔 0.3 秒，
    // R219 · 点进度条滚到正在制作的那一章，不用一章章翻
    val chapterListState = rememberLazyListState()

    // 一次读盘、书内共用同一份真值：按章展开的小段与整本分段计划同源。
    var hubSegs by remember(book.id) { mutableStateOf(emptyList<SegRow>()) }
    var hubSegChapters by remember(book.id) { mutableStateOf<Map<Int, Int>?>(null) }
    val pqSeg by PlayQueue.previewState.collectAsState()
    // R262 · 没展开任何章时不再每段完成都全量读一遍整本分段计划（2 万+ 段），
    // 只在展开制作章时随进度刷；redo / 删段 / 拼章靠 localTick 显式刷一次。
    LaunchedEffect(book.id, st.running, localTick, task?.bookId,
        if (expandedChapter != null) st.done else 0) {
        val (all, map) = withContext(Dispatchers.IO) {
            val t = task?.takeIf { it.bookId == book.id } ?: TaskResume.byBook(c, book.id)
            Workbench.segments(c, t) to Workbench.segChapters(c, t)
        }
        hubSegs = all
        hubSegChapters = map
    }
    // R257 · 回溯补齐从 st.done 驱动里拆出来。1.1.30 真机日志：
    // CHAPTER_MERGE_ENSURE 共 50 次、全是「补拼 0 章 / 跳过 338 章」，且每段
    // 合成完都跟一条——制作每完成一段就全量扫 26678 段 + 338 章，白烧 IO，
    // 还把诊断日志刷满、看起来像拼接坏了。制作中必然缺块，没必要扫；
    // 只在「切书 / 任务跑与不跑切换、且当前没在跑」时补一次。收尾 Producer
    // 自己也调 ensureChapterAudios，两处幂等（audioFor 去重）。
    LaunchedEffect(book.id, st.running) {
        if (st.running) return@LaunchedEffect
        val made = withContext(Dispatchers.IO) {
            val t = task?.takeIf { it.bookId == book.id } ?: TaskResume.byBook(c, book.id)
            val (all, map) = Workbench.segments(c, t) to Workbench.segChapters(c, t)
            val drops = SegDrops.snapshot(c, book.id)
            val byCh = LinkedHashMap<Int, MutableList<File>>()
            for (sg in all) {
                val ch = map?.get(sg.index) ?: continue
                if (ch < 0) continue
                if (SegDrops.matches(drops, sg.wavName, sg.text.take(200))) continue
                byCh.getOrPut(ch) { mutableListOf() }
                    .add(sg.wav ?: File(c.filesDir, "work/${book.id}/${sg.wavName}"))
            }
            AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,
                gapMs = Prefs.defaultPerf(c).gapMs,
                declickMs = Prefs.mergeDeclickMs(c)).first
        }
        // 拼出成品才刷一次磁盘快照，让小耳机当页就亮；无成品不空转。
        if (made > 0) localTick++
    }
    val chapterSegs = remember(expandedChapter, hubSegs, hubSegChapters) {
        val ch = expandedChapter
        val map = hubSegChapters
        if (ch == null || map == null) emptyList()
        else hubSegs.filter { map[it.index] == ch }
    }
    // R220 · 小段编号每章从 1 重新开始，不再接着前一章的一堆大数字。
    // 文件里的段号仍是整本连续的（续跑/拼接都靠它），只改显示。
    val chapterBase = remember(chapterSegs) {
        chapterSegs.minOfOrNull { it.index } ?: 0
    }
    var beatTick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) return@LaunchedEffect
        while (true) { delay(1000); beatTick++ }
    }
    val heartbeat = remember(beatTick, st.done) {
        val (act, _) = EventLog.lastEngine()
        val age = (EventLog.lastEngineAgeMs() / 1000).coerceAtLeast(0)
        val what = when {
            act.contains("PREFILL") -> "正在把这一段读进模型"
            act.contains("DECODE") -> "正在逐字生成这一块的朗读分段"
            act.contains("TOKENIZE") || act.contains("ENTER") -> "正在准备文字"
            act.contains("TTS") -> "正在把文字读成声音"
            else -> "正在准备"
        }
        what + if (st.running && age > 100) " · 已 ${age} 秒没新动作" else ""
    }

    // 章级导出 / 转格式 / 删除：从原第三列整段搬来，删列不丢能力。
    // R252 · 两个 CreateDocument 回调都在主线程，原来 copyTo / toM4a 同步跑：
    // 转 M4A 是 MediaCodec 把整章 PCM 编成 AAC，长章节几十秒，必卡 UI 甚至 ANR。
    // 统一切 IO，转码前给个即时反馈；临时文件与 pending 状态在协程末尾清。
    val exportScope = rememberCoroutineScope()
    var pendingSrc by remember { mutableStateOf<File?>(null) }
    var tmpCopy by remember { mutableStateOf<File?>(null) }
    val saveWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val src = pendingSrc ?: tmpCopy
        if (uri != null && src != null) {
            exportScope.launch {
                val ok = withContext(Dispatchers.IO) { AudioOps.copyTo(c, src, uri) }
                Toast.makeText(c, if (ok) "已复制" else "复制失败",
                    Toast.LENGTH_SHORT).show()
                tmpCopy?.delete(); tmpCopy = null; pendingSrc = null
            }
        } else { tmpCopy?.delete(); tmpCopy = null; pendingSrc = null }
    }
    val saveM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val src = pendingSrc ?: tmpCopy
        if (uri != null && src != null) {
            Toast.makeText(c, "正在转 M4A…", Toast.LENGTH_SHORT).show()
            exportScope.launch {
                val tmp = File(c.cacheDir, "conv_${System.currentTimeMillis()}.m4a")
                val r = withContext(Dispatchers.IO) { AudioOps.toM4a(c, src, tmp) }
                if (r.ok) {
                    val ok = withContext(Dispatchers.IO) { AudioOps.copyTo(c, tmp, uri) }
                    Toast.makeText(c, if (ok) "已转存 M4A" else "写出失败",
                        Toast.LENGTH_SHORT).show()
                } else Toast.makeText(c, r.reason, Toast.LENGTH_LONG).show()
                tmp.delete()
                tmpCopy?.delete(); tmpCopy = null; pendingSrc = null
            }
        } else { tmpCopy?.delete(); tmpCopy = null; pendingSrc = null }
    }
    fun filesOf(i: Int): Pair<List<File>, File?> {
        val ch = AudioTree.load(c).firstOrNull { it.bookId == book.id }
            ?.chapters?.firstOrNull { it.chapter == i }
        val segs = ch?.segments?.map { it.file } ?: emptyList()
        return segs to ch?.merged?.takeIf { it.isFile }
    }
    fun srcOf(i: Int): File? {
        val (segs, merged) = filesOf(i)
        return merged ?: if (segs.size == 1) segs.firstOrNull()
        else AudioOps.mergedTemp(c, segs).let { rr ->
            if (rr.ok) { tmpCopy = rr.file; rr.file } else null
        }
    }

    var confirmSegDel by remember { mutableStateOf<List<File>?>(null) }
    var confirmChDel by remember { mutableStateOf<Map<Int, Pair<List<File>, File?>>?>(null) }
    var pane by remember(book) { mutableStateOf<Pane?>(null) }
    fun openReader(i: Int) {
        EventLog.ui("OPEN_READER", "ch=$i", mapOf("book" to book.id))
        if (i in chapters.indices) {
            ReadingState.save(c, book.id, i)
            pane = Pane.Reader(i)
        }
    }
    // R253 · 「上次读到」细条已从目录页撤掉，阅读位置仍由 openReader 落盘。
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
        // R235 · 目录页启动也读全局默认制作设置，不再用写死默认值
        val hint = Producer.enqueueOrStart(c, book, selected, null, Prefs.defaultPerf(c))
        Toast.makeText(c, hint, Toast.LENGTH_LONG).show()
        onStarted(book.id, selected)
        selected = emptySet()
        multiMode = false
        EventLog.log(EventLog.Kind.PIPELINE, "HUB_ACTION",
            "书=${EventLog.safe(book.title)} 章数=$n 档=直接朗读")
    }
    fun enterMulti(i: Int) {
        multiMode = true
        selected = setOf(i)
        EventLog.ui("HUB_MULTI", "ch=$i")
    }
    fun exitMulti() { multiMode = false; selected = emptySet() }
    fun exitSegMulti() { segMulti = false; segChecked = emptySet() }
    // R171 · 勾选态 / 分段勾选态 / 任务看板都自己消费返回键：
    // 先关掉当前这层并留在目录页，不再让全局 goBack 直接把人送回书架。
    // 注册在 pane 的 BackHandler 之后（Compose 后注册优先），弹层优先收起。
    BackHandler(enabled = pane != null) { pane = null }
    BackHandler(enabled = multiMode) { exitMulti() }
    // R255 · 长按小菜单优先消费返回键：原来菜单弹着按返回会直接退勾选态
    // 甚至离开这本书，和左勾 / 底部条抢操作。菜单与底部条也互斥显示。
    BackHandler(enabled = menuChapter != null) { menuChapter = null }
    BackHandler(enabled = segMulti) { exitSegMulti() }
    BackHandler(enabled = showTasks) { showTasks = false }
    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = showResplit) { showResplit = false }
    fun tapSeg(sg: SegRow) {
        val segId = sg.wav?.let { "seg:" + it.absolutePath } ?: ""
        val playable = sg.hasAudio && segId.isNotEmpty()
        val onThis = playable && pqSeg.trackId == segId
        val playing = onThis && pqSeg.isPlaying
        EventLog.ui("SEG_TAP", "seg=${sg.index} play=$playing")
        when {
            playing -> PlayQueue.pausePreview()
            onThis -> PlayQueue.resumePreview()
            else -> sg.wav?.let { wav ->
                PlayQueue.setTracks(
                    listOf(Track(wav, "第 ${sg.index + 1} 段", id = segId)), "seg")
                PlayQueue.playFrom(0, id = "seg")
            }
        }
    }

    LaunchedEffect(showResplit, book.id, planTick) {
        if (!showResplit) return@LaunchedEffect
        resplitBusy = true
        resplitPreview = withContext(Dispatchers.IO) {
            try { ChapterPlan.preview(c, book, null) } catch (_: Throwable) { null }
        }
        resplitBusy = false
    }

    val curRule = remember(book.id, planTick) { ChapterPlan.currentRuleId(c, book.id) }
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
    // R218 · 没展开任何章时，本章进度条取「正在做的最靠后一章」，不再因展开才出现
    val chapterPctNow = if (chNow != null) chPct else run {
        var m = 0f
        for ((_, v) in st.chapterProgress) if (v > m) m = v
        m
    }
    val showHead = bookRunning || stalledTask || chNow != null ||
        (task?.bookId == book.id && st.stage == "完成")
    // R219 · 正在做的那一章：章进度里最靠后且没做完的；没有就取选章范围
    val makingChapter = remember(st.chapterProgress, task, ui.size) {
        var idx = -1
        for ((k, v) in st.chapterProgress) if (v < 1f && k > idx) idx = k
        if (idx < 0) idx = task?.only?.maxOrNull() ?: 0
        idx.coerceIn(0, (ui.size - 1).coerceAtLeast(0))
    }
    // R262 · 只有「正在合成」的章才有资格展开小段：章进度里跑着且没做完的，
    // 加上进度条定位到的当前章。没在制作、或这章没在跑 → 点不开、不读盘、
    // 不过滤整本分段计划，连展开体的 item 都不生成。
    val liveChapters: Set<Int> = remember(st.chapterProgress, makingChapter, bookRunning) {
        if (!bookRunning) emptySet()
        else st.chapterProgress.filter { (_, v) -> v > 0f && v < 1f }.keys.toSet() + makingChapter
    }
    LaunchedEffect(bookRunning) { if (!bookRunning) expandedChapter = null }
    fun scrollToMaking() {
        resplitScope.launch {
            chapterListState.animateScrollToItem(makingChapter)
        }
    }
    // R253 · 长按菜单「整章重做」：该章所有段标记重做、撤掉旧整章成品（否则
    // ensureChapterAudios 会因 audioFor 非空跳过、小耳机还播旧的），再启动只做这章。
    // 小块 WAV 不删：重做失败也能用「拼成整章」手动恢复。
    fun redoWholeChapter(ch: Int) {
        val t = task?.takeIf { it.bookId == book.id } ?: TaskResume.byBook(c, book.id)
        val work = Workbench.workDir(c, t)
        val map = hubSegChapters
        val segsHere = if (map != null) hubSegs.filter { map[it.index] == ch } else emptyList()
        if (work == null || segsHere.isEmpty()) {
            Toast.makeText(c, "没找到这一章的制作记录", Toast.LENGTH_SHORT).show()
            return
        }
        AudioLibrary.audioFor(c, book.id, ch)?.let { AudioLibrary.removeFile(c, it.file) }
        segsHere.forEach { SegPlan.markSeg(work, it.index) }
        Producer.enqueueOrStart(c, book, setOf(ch), null, Prefs.defaultPerf(c))
        EventLog.ui("CH_REDO", "ch=$ch n=${segsHere.size}")
        localTick++
        Toast.makeText(c, "第 ${ch + 1} 章已排重做，按当前调音台重新合成",
            Toast.LENGTH_LONG).show()
    }
    val finishedChapters = ui.count { it.playable || it.audio != null }
    val totalBytes = ui.sumOf { it.audio?.bytes ?: 0L }
    val queued = remember(refresh, localTick, st.running) { TaskResume.queue(c) }
    // R180 · 这本书上次听到哪。只在有成品时读一次盘，不随高频进度重算。
    // 必须放在 finishedChapters 之后，否则前向引用编译不过。
    val resumePos = remember(refresh, finishedChapters) {
        if (finishedChapters > 0) ListenState.get(c, "chapter:${book.id}") else null
    }
    // R180 · 模型没齐时别只给一句「没开始」，给一个能按的出口。
    val needModels = remember(refresh) { ModelStore.missing(c).isNotEmpty() }

    Box(Modifier.fillMaxSize().background(HubBg)) {
        Column(Modifier.fillMaxSize()) {
            // R226 · 三枚操作已上移到系统顶栏，与书名同一排右侧；这里不再单独占一行。
            front?.let { fi ->
                if (fi.chars > 40 && fi.noisy) {
                    Text("书前 ${fi.chars} 字已跳过",
                        fontSize = 10.sp, color = HubWarn, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().background(HubCard)
                            .padding(horizontal = 14.dp, vertical = 3.dp))
                }
            }
            // R253/R255 · 顶部只留两条纯进度条，不写任何解释文字：
            // 「上次读到 第 N 章 · 继续」「整体 X%」「已完成 X%」逐条撤掉。
            // 条高与可点滚到制作章照旧；百分比靠条走了多少表达。
            Column(Modifier.fillMaxWidth().background(HubCard)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clickable { scrollToMaking() }) {
                LinearProgressIndicator(progress = { bookPct },
                    modifier = Modifier.fillMaxWidth().height(22.dp),
                    color = HubPrimary, trackColor = HubLine)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { chapterPctNow },
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    color = HubGood, trackColor = HubLine)
            }
            HubDivider()
            // 可播汇总 / 顺序播放 / 队列：原来散在第三列和任务页，现在一行收口。
            Row(Modifier.fillMaxWidth().background(HubCard)
                .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (finishedChapters > 0) {
                    // R180 · 一处构造播放队列，两个入口共用，只读一次盘。
                    fun playBook(atId: String?, fromMs: Long?) {
                        val list = AudioLibrary.playableItems(c, book.id)
                        if (list.isEmpty()) return
                        val q = "chapter:${book.id}"
                        val ts = list.map { (idx, a) ->
                            Track(a.file, "${book.title} · 第 ${idx + 1} 章",
                                AudioLibrary.chapterStartMs(c, a, idx),
                                id = "chapter:${a.file.absolutePath}")
                        }
                        val at = if (atId == null) 0
                        else ts.indexOfFirst { it.id == atId }.coerceAtLeast(0)
                        PlayQueue.setTracks(ts, q)
                        PlayQueue.playFrom(at, fromMs, id = q)
                        onPlayBook(book.id)
                    }
                    if (resumePos != null && resumePos.positionMs > 5000L) {
                        TextButton(onClick = {
                            playBook(resumePos.trackId, resumePos.positionMs)
                            EventLog.ui("HUB_RESUME_LISTEN", "book=${book.id.take(8)}")
                        }) {
                            Text("接着听 · %d:%02d ›".format(
                                resumePos.positionMs / 60000,
                                (resumePos.positionMs / 1000) % 60),
                                fontSize = 12.sp, color = HubPrimary)
                        }
                    }
                    TextButton(onClick = { playBook(null, null) }) {
                        Text("按顺序播放", fontSize = 12.sp, color = HubPrimary)
                    }
                } else if (needModels) {
                    TextButton(onClick = { onOpenModels() }) {
                        Text("去导入模型 ›", fontSize = 12.sp, color = HubWarn)
                    }
                }
                if (queued.isNotEmpty()) {
                    TextButton(onClick = {
                        EventLog.ui("HUB_QUEUE", "n=${queued.size}")
                        showQueue = true
                    }) { Text("队列 ${queued.size}", fontSize = 12.sp, color = HubPrimary) }
                }
            }
            HubDivider()

            LazyColumn(Modifier.fillMaxSize(), state = chapterListState,
                contentPadding = PaddingValues(bottom = 100.dp)) {
                ui.forEach { r ->
                    item(key = "ch_${r.index}") {
                        ChapterRow(
                            r = r, multi = multiMode, checked = r.index in selected,
                            // R218 · 点章名 = 展开小段；看书交给右侧小眼睛
                            onTap = {
                                // R262 · 没在合成的章点了就是没反应：直接收起，
                                // 连整本分段计划的 filter 都不跑（2 万+ 段不用算）。
                                if (!bookRunning || r.index !in liveChapters) {
                                    expandedChapter = null
                                } else {
                                    val map = hubSegChapters
                                    val segsHere = if (map != null)
                                        hubSegs.filter { map[it.index] == r.index }
                                    else emptyList()
                                    val generated = segsHere.any { it.hasAudio }
                                    val making = (bookRunning &&
                                        (task?.only?.contains(r.index) != false)) ||
                                        ((st.chapterProgress[r.index] ?: 0f) > 0f)
                                    if (generated || making) {
                                        val willOpen = expandedChapter != r.index
                                        expandedChapter = if (willOpen) r.index else null
                                        EventLog.ui("HUB_CH_EXPAND",
                                            "ch=${r.index} open=$willOpen",
                                            mapOf("book" to book.id))
                                    }
                                }
                            },
                            onLong = { menuChapter = r.index },
                            onToggle = { i ->
                                val on = i !in selected
                                val next = if (on) selected + i else selected - i
                                selected = next
                                multiMode = next.isNotEmpty()
                                EventLog.ui("CH_TOGGLE", "ch=$i on=$on n=${next.size}")
                            },
                            onRead = { openReader(r.index) },
                            onListen = {
                                EventLog.ui("HUB_LISTEN", "ch=${r.index}")
                                playChapter(r.index, single = true)
                            },
                            menuExpanded = menuChapter == r.index,
                            onDismissMenu = { menuChapter = null },
                            onMenuSelect = { enterMulti(r.index) },
                            onMenuRedo = { redoWholeChapter(r.index) },
                            canRedo = r.audio != null || r.playable)
                    }
                    // R262 · 没在合成的章连这个 item 都不生成：不组合、不渲染、不计算。
                    if (r.index == expandedChapter && r.index in liveChapters) {
                    // R218 · 展开收进同一个 item 用 AnimatedVisibility 进出，
                    // 不再整块突然插进来；两条进度条已常驻，不会「从上面弹出来」。
                    item(key = "seg_body_${r.index}") {
                        AnimatedVisibility(
                            visible = expandedChapter == r.index,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(Modifier.fillMaxWidth().background(HubSoft)) {
                                SegGridHead(chapterSegs, segMulti, segChecked.size,
                                    onRedo = {
                                        val rows = chapterSegs.filter {
                                            it.index in segChecked && it.hasAudio
                                        }
                                        val t = task?.takeIf { it.bookId == book.id }
                                            ?: TaskResume.byBook(c, book.id)
                                        val work = Workbench.workDir(c, t)
                                        val ch = expandedChapter
                                        when {
                                            rows.isEmpty() -> Toast.makeText(c,
                                                "先勾几个已生成的小块",
                                                Toast.LENGTH_SHORT).show()
                                            work == null -> Toast.makeText(c,
                                                "没找到这本书的制作记录",
                                                Toast.LENGTH_SHORT).show()
                                            else -> {
                                                // R253 · 不再在制作中一口拒绝。标记即排队：
                                                // 没在跑 → 立刻开一轮；正在跑 → enqueueOrStart
                                                // 把重做任务排进队列，当前这段读完由队列泵
                                                // 自动接上，绝不算断正在合成的段。
                                                rows.forEach { SegPlan.markSeg(work, it.index) }
                                                EventLog.ui("SEG_REDO",
                                                    "n=${rows.size} running=${st.running}")
                                                exitSegMulti()
                                                if (ch != null) {
                                                    Producer.enqueueOrStart(c, book, setOf(ch),
                                                        null, Prefs.defaultPerf(c))
                                                }
                                                localTick++   // 立刻重读 redo.json，格子转琥珀
                                                Toast.makeText(c,
                                                    if (st.running)
                                                        "已标记 ${rows.size} 段（格子转黄）：当前这段读完自动接重做"
                                                    else
                                                        "已标记 ${rows.size} 段，按当前调音台重新合成",
                                                    Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onAll = {
                                        segChecked = chapterSegs.filter { it.hasAudio }
                                            .map { it.index }.toSet()
                                        EventLog.ui("SEG_ALL", "n=${segChecked.size}")
                                    },
                                    onTrial = {
                                        val picked = segChecked.sorted().mapNotNull { i ->
                                            chapterSegs.firstOrNull { it.index == i }?.wav?.let { w ->
                                                Track(w, "第 ${i - chapterBase + 1} 段",
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
                                            exitSegMulti()
                                        }
                                    },
                                    onDelete = {
                                        val files = segChecked.sorted()
                                            .mapNotNull { i ->
                                                chapterSegs.firstOrNull { it.index == i }?.wav
                                            }
                                        if (files.isEmpty()) {
                                            Toast.makeText(c, "先勾几个已生成的小块",
                                                Toast.LENGTH_SHORT).show()
                                        } else {
                                            EventLog.ui("SEG_DELETE_ASK", "n=${files.size}")
                                            confirmSegDel = files
                                            exitSegMulti()
                                        }
                                    })
                                chapterSegs.chunked(SEG_GRID_COLUMNS).forEach { row ->
                                    ChapterSegGrid(
                                        row = row, pq = pqSeg, base = chapterBase,
                                        multi = segMulti, checked = segChecked,
                                        onLong = { i -> segMulti = true; segChecked = setOf(i) },
                                        onToggle = { i ->
                                            segChecked = if (i in segChecked) segChecked - i
                                                        else segChecked + i
                                        },
                                        onTap = { sg -> tapSeg(sg) })
                                }
                            }
                        }
                    }
                    }
                }
            }
        }

        // 章节多选：合成 + 调音 + 单章成品操作，全在这一条胶囊里。
        // R171 · 条件从「有勾选」放宽到「在勾选态」：全取消勾选后条子不再凭空
        // 消失，用户始终看得见「取消」，按返回也能退（BackHandler 已接）。
        // R255 · 长按小菜单弹出时收起底部批量条，两者不同时出现，不抢操作。
        // 已勾选的章不丢（selected 不清），菜单一关条子回来。
        if (multiMode && menuChapter == null) {
            MultiSelectBar(
                count = selected.size,
                onSelectAll = {
                    selected = ui.indices.toSet()
                    EventLog.ui("MULTI_ALL", "n=${selected.size}")
                },
                onCancel = { exitMulti() },
                allLabel = "全选章节",
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (selected.isNotEmpty()) {
                    // R224 · 一排字 → 有底色的按钮，长按选中后一眼能看出哪些能点
                    ActionChip("调音 ›", icon = UiIcon.MIXER, onClick = onMixer)
                    ActionChip("制作设置 ›", icon = UiIcon.SETTINGS, onClick = { onProduceSetup(book.id) })
                    ActionChip("合成音频", icon = UiIcon.SYNTH, onClick = { startAction(HubAction.SYNTH) })
                    if (selected.size == 1) {
                        val i = selected.first()
                        val has = ui.getOrNull(i)?.let { it.audio != null || it.playable } ?: false
                        if (has) {
                            ActionChip("拼成整章", icon = UiIcon.MERGE, onClick = {
                                val (segs, _) = filesOf(i)
                                val rr = AudioOps.mergeChapter(c, book.id, book.title, i, segs)
                                Toast.makeText(c, if (rr.ok) rr.reason.ifBlank { "已拼成整章" }
                                    else rr.reason, Toast.LENGTH_LONG).show()
                                exitMulti(); localTick++
                            })
                            ActionChip("另存 WAV", icon = UiIcon.WAV, onClick = {
                                val src = srcOf(i)
                                if (src != null) { pendingSrc = src; saveWav.launch("chapter.wav") }
                                else Toast.makeText(c, "这一章还没有可导出的音频",
                                    Toast.LENGTH_SHORT).show()
                                exitMulti()
                            })
                            ActionChip("转 M4A", icon = UiIcon.M4A, onClick = {
                                val src = srcOf(i)
                                if (src != null) { pendingSrc = src; saveM4a.launch("chapter.m4a") }
                                else Toast.makeText(c, "这一章还没有可转的音频",
                                    Toast.LENGTH_SHORT).show()
                                exitMulti()
                            })
                        }
                    }
                    ActionChip("删除选中音频", danger = true, icon = UiIcon.DELETE, onClick = {
                        confirmChDel = selected.associateWith { filesOf(it) }
                    })
                }
            }
        }

        // 任务队列就地看，不切页；移除排队项与继续队列都在这里。
        if (showQueue) {
            AlertDialog(
                onDismissRequest = { showQueue = false },
                title = { Text("任务队列 · ${queued.size} 个", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        queued.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("《${t.title}》", fontSize = 13.sp,
                                    maxLines = 1, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    TaskResume.dequeue(c, t.bookId)
                                    localTick++
                                }) { Text("移除", fontSize = 12.sp, color = HubWarn) }
                            }
                        }
                    }
                },
                // R243 · 「继续队列」砍掉：队列恢复由自动续跑/重新制作接管，
                // 这枚按钮看不懂，留着反而像卡住要手动点。底层能力保留。
                confirmButton = { },
                dismissButton = {
                    TextButton(onClick = { showQueue = false }) { Text("关闭") }
                }
            )
        }

        // R171 · 任务看板就地展开：跨书任务不用离开这本书去独立任务页。
        // 与任务页同源（TaskResume.list / realProgress / invalidationReason），
        // 不另造一套状态口径；弹层自己消费返回键（见上面的 BackHandler）。
        if (showTasks) {
            TaskBoardDialog(
                c = c,
                onDismiss = { showTasks = false },
                onOpenBook = { bid -> showTasks = false; onOpenBook(bid) },
                onStartTask = { t ->
                    showTasks = false
                    resplitScope.launch {
                        val b = withContext(Dispatchers.IO) {
                            BookStore.list(c).find { it.id == t.bookId }
                        }
                        if (b == null) {
                            Toast.makeText(c, "这本书已从书架删掉，没法续跑",
                                Toast.LENGTH_LONG).show()
                        } else if (Producer.isBusy()) {
                            Toast.makeText(c, "另一本正在制作，等它跑完或先停它",
                                Toast.LENGTH_LONG).show()
                        } else {
                            Producer.start(c, b, t.only, true, t.think, t.config)
                            onOpenBook(t.bookId)
                            EventLog.ui("HUB_TASK_RESUME", "book=${t.bookId.take(8)}")
                        }
                    }
                },
                onChanged = { localTick++ })
        }

        // 删小段：二次确认 → IO 删除 → 索引记账 → 重读，分段计划保留。
        confirmSegDel?.let { files ->
            AlertDialog(
                onDismissRequest = { confirmSegDel = null },
                title = { Text("删除这 ${files.size} 个小块？", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = {
                    // R233 · 旧文案「下次续跑会按原顺序重新生成」与用户意图相反：
                    // 删掉的段就是不要了，从此不参与合成，也不参与拼接。
                    Text("只删已生成的 WAV，分段计划还在；删掉的段从此不参与合成与拼接，" +
                            "已拼好的整章音频不受影响。",
                        fontSize = 13.sp, color = HubSub)
                },
                confirmButton = {
                    TextButton(onClick = {
                        EventLog.ui("SEG_DELETE", "n=${files.size}")
                        resplitScope.launch(Dispatchers.IO) {
                            val killedPaths = files.map { it.absolutePath }
                            val failed = AudioOps.deleteAll(files)
                            AudioTree.forget(c, killedPaths)
                            // R233 · 单独记一笔：合成循环与自动拼章都靠它排除这些段
                            val dropped = SegDrops.mark(c, book.id, files)
                            EventLog.log(EventLog.Kind.PIPELINE, "SEG_DROPPED",
                                "n=${files.size} · booked=$dropped")
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

        // 删除章级成品：一次确认，逐章清文件与索引（从原第三列搬来）。
        confirmChDel?.let { all ->
            AlertDialog(
                onDismissRequest = { confirmChDel = null },
                title = { Text("删除选中的 ${all.size} 章音频？", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = { Text("共 ${all.values.sumOf {
                        it.first.size + (if (it.second != null) 1 else 0) }} 个文件，不能恢复；" +
                    "没拼过的章，下次续跑会重新生成这些段。", fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        var failed = 0
                        all.forEach { (i, files) ->
                            val (segs, merged) = files
                            val killedPaths = segs.map { it.absolutePath }
                            failed += AudioOps.deleteAll(segs)
                            merged?.let { if (!AudioOps.deleteMerged(c, it)) failed++ }
                            AudioTree.forget(c, killedPaths)
                            AudioTree.clearDone(c, book.id, i)
                        }
                        PlayQueue.release()
                        Toast.makeText(c, if (failed > 0) "有 $failed 个没删干净"
                            else "已删除 ${all.size} 章的音频", Toast.LENGTH_SHORT).show()
                        confirmChDel = null
                        exitMulti(); localTick++
                    }) { Text("删除", color = HubWarn) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmChDel = null }) { Text("取消") }
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

        // 重新分章：先预览章数与头尾标题，确认才应用（成熟阅读器的闭环）。
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
                        // R172 · 说清真实后果：旧任务标待确认、分段作废、成品仍可听。
                        Text("章号会变，旧任务需重新确认；分段音频作废，成品仍可听；原书文字不动。",
                            fontSize = 12.sp, color = HubWarn)
                        // R193 · 别的书在制作不挡这本书；但本书正在制作或
                        // 刚点停止还在收尾时，必须等停稳，否则会删掉正在写的分段。
                        Text("这本书正在制作或收尾时先等它停稳；别的书在制作不影响。",
                            fontSize = 12.sp, color = HubSub)
                    }
                },
                confirmButton = {
                    TextButton(enabled = pv != null &&
                        !(st.running && task?.bookId == book.id) &&
                        !Producer.isBusyForBook(book.id), onClick = {
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
                                    "已按${ChapterPlan.ruleName(rid)}重新分为 $n 章 · 旧任务请重新选章",
                                    Toast.LENGTH_LONG).show()
                                EventLog.log(EventLog.Kind.MODEL, "CHAPTER_RESPLIT",
                                    "书=${EventLog.safe(book.title)} 规则=$rid 章数=$n")
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

/**
 * R219 · 章名左边常驻一枚勾（点勾即选中、不再靠长按进多选），
 * 右边只留小眼睛（看原文）/ 小耳机（听成品）；点章名展开小段。
 * 章名下一根细进度条直接表达合成进度。
 */
@Composable
private fun ChapterRow(
    r: ChapterUi, multi: Boolean, checked: Boolean,
    onTap: () -> Unit, onLong: () -> Unit, onToggle: (Int) -> Unit,
    onRead: () -> Unit, onListen: () -> Unit,
    menuExpanded: Boolean, onDismissMenu: () -> Unit, onMenuSelect: () -> Unit,
    onMenuRedo: () -> Unit, canRedo: Boolean
) {
    val p = r.synthesized?.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .pointerInput(r.index, multi, checked) {
                    // detectTapGestures 的回调签名带 Offset，不能直接传 () -> Unit
                    detectTapGestures(onLongPress = { onLong() }, onTap = { onTap() })
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // R169 · 真机截图（1.1.5 浅色）：103 章整列几乎看不见。HubGrey 对白卡
            // 只有 1.74~1.85:1，远低于 4.5:1。R167 把「浅色保留灰字层级」当层级，
            // 实际是读不了。深浅两色统一 palette.text（浅色近黑 / 深色纯白），
            // 「有没有做好」交给右侧耳机图标的颜色，不再靠把字压淡来表达。
            // R219 · 章名左边一排勾：点一下即选中，不需要长按才出现
            Box(Modifier.size(34.dp).clickable { onToggle(r.index) },
                contentAlignment = Alignment.Center) {
                SelectBox(checked = checked, tint = HubPrimary,
                    boxColor = HubCard, modifier = Modifier.size(18.dp))
            }
            Text("${r.index + 1}. ${r.title}", fontSize = 14.sp, maxLines = 1,
                color = palette.text,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onRead, modifier = Modifier.size(36.dp)) {
                EyeIcon(HubPrimary)
            }
            IconButton(onClick = onListen,
                enabled = r.audio != null || r.playable,
                modifier = Modifier.size(36.dp)) {
                HeadphoneIcon(if (r.audio != null || r.playable) HubPrimary else HubGrey)
            }
        }
        if (p != null && p > 0f) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(HubLine)) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight()
                    .background(if (r.audio != null) HubGood else HubPrimary))
            }
        }
        HubDivider()
    }
    // R253 · 长按就地菜单：只放两组里「章级」的动作，勾选后的批量操作仍在底部条。
    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
        DropdownMenuItem(
            text = { Text("勾选此章", fontSize = 13.sp) },
            onClick = { onDismissMenu(); onMenuSelect() })
        if (canRedo) {
            DropdownMenuItem(
                text = { Text("整章重做", fontSize = 13.sp) },
                onClick = { onDismissMenu(); onMenuRedo() })
        }
    }
    }
}

/** R218 · 代码绘制的小眼睛：看这一章原文。 */
@Composable
private fun EyeIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val eye = Path().apply {
            moveTo(w * 0.08f, h * 0.5f)
            cubicTo(w * 0.30f, h * 0.18f, w * 0.70f, h * 0.18f, w * 0.92f, h * 0.5f)
            cubicTo(w * 0.70f, h * 0.82f, w * 0.30f, h * 0.82f, w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(eye, color = tint, style = s)
        drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.5f, h * 0.5f))
    }
}

/** 代码绘制的耳机：听本章。 */
@Composable
private fun HeadphoneIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val band = Path().apply {
            moveTo(w * 0.14f, h * 0.56f)
            cubicTo(w * 0.14f, h * 0.06f, w * 0.86f, h * 0.06f, w * 0.86f, h * 0.56f)
        }
        drawPath(band, color = tint, style = s)
        drawLine(tint, Offset(w * 0.14f, h * 0.54f), Offset(w * 0.14f, h * 0.86f),
            3.dp.toPx(), StrokeCap.Round)
        drawLine(tint, Offset(w * 0.86f, h * 0.54f), Offset(w * 0.86f, h * 0.86f),
            3.dp.toPx(), StrokeCap.Round)
    }
}

/** 代码绘制的下三角：展开 / 收起本章小片。 */
@Composable
private fun ChevronIcon(tint: Color, up: Boolean) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val y1 = if (up) h * 0.62f else h * 0.38f
        val y2 = if (up) h * 0.38f else h * 0.62f
        val p = Path().apply {
            moveTo(w * 0.22f, y1); lineTo(w * 0.5f, y2); lineTo(w * 0.78f, y1)
        }
        drawPath(p, color = tint, style = s)
    }
}

/** 展开章的小片头部：状态 + 分段多选操作，不写教程。 */
@Composable
private fun SegGridHead(
    segs: List<SegRow>, multi: Boolean, picked: Int,
    onAll: () -> Unit, onTrial: () -> Unit, onRedo: () -> Unit, onDelete: () -> Unit
) {
    // R219 · 非多选态整块不渲染，展开后顶部不再留一块空白
    if (!multi) return
    Column(Modifier.fillMaxWidth().background(HubSoft)
        .padding(horizontal = 16.dp, vertical = 2.dp)) {
        if (multi) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 $picked", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = HubPrimary)
                listOf("全选已生成" to onAll, "试听选中" to onTrial,
                       "重做选中" to onRedo,
                       "删除选中" to onDelete).forEach { (label, act) ->
                    TextButton(onClick = act, modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text(label, fontSize = 11.sp,
                            color = if (label == "删除选中") HubWarn else HubPrimary)
                    }
                }
            }
        }
    }
}

/**
 * 固定 10 列的小片网格。
 *
 * R158 曾把「居中」做成每行各自居中，实测最后一行不满时会像文字一样飘在中间、
 * 和上一行错开；R164 按真实使用感受纠正：网格宽度恒为 10 列 → 整块在屏幕里
 * 居中，行内一律从第 1 列往右排，不满 10 个的行也靠左，右侧留空。
 * 列数固定、格子边长随可用宽度自适应，360dp 窄屏也不会溢出。
 * R155 起按行拆成父列表的独立 item，长章只组合看得见的行；
 * R163 起长按进分段多选，格子显示勾选框。
 */
@Composable
private fun ChapterSegGrid(
    row: List<SegRow>, pq: PlaybackState, base: Int = 0,
    multi: Boolean, checked: Set<Int>,
    onLong: (Int) -> Unit, onToggle: (Int) -> Unit, onTap: (SegRow) -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        val gap = 4.dp
        val cell = ((maxWidth - gap * (SEG_GRID_COLUMNS - 1)) / SEG_GRID_COLUMNS)
            .coerceAtMost(30.dp).coerceAtLeast(20.dp)
        val gridWidth = cell * SEG_GRID_COLUMNS + gap * (SEG_GRID_COLUMNS - 1)
        Row(Modifier.width(gridWidth),
            horizontalArrangement = Arrangement.spacedBy(gap)) {
            repeat(SEG_GRID_COLUMNS) { col ->
                val sg = row.getOrNull(col)
                if (sg == null) {
                    Spacer(Modifier.size(cell))
                } else {
                    val segId = sg.wav?.let { "seg:" + it.absolutePath } ?: ""
                    val playable = sg.hasAudio && segId.isNotEmpty()
                    val onThis = playable && pq.trackId == segId
                    val playing = onThis && pq.isPlaying
                    val isChecked = sg.index in checked
                    Box(
                        Modifier.size(cell)
                            .background(segCellColor(playable, playing, multi, isChecked,
                                    sg.pendingRedo), RoundedCornerShape(8.dp))
                            .pointerInput(sg.index, multi, isChecked, playable, playing,
                                    onThis, sg.pendingRedo) {
                                detectTapGestures(
                                    onLongPress = { if (playable) onLong(sg.index) },
                                    onTap = {
                                        if (multi) { if (playable) onToggle(sg.index) }
                                        else onTap(sg)
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (multi) {
                            SelectBox(checked = isChecked, tint = HubPrimary,
                                boxColor = HubCard, modifier = Modifier.size(16.dp))
                        } else {
                            Text("${sg.index - base + 1}", fontSize = 11.sp,
                                color = segNumberColor(playing, playable, multi, isChecked,
                                    sg.pendingRedo))
                        }
                    }
                }
            }
        }
    }
}

// 代码绘制的三点操作图标（R132 引入，R171 起不再使用）：
// 章行选中一律长按，右侧只留听与展开两枚图标；这行留作格子行的分段标记。

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
        // R155 · 用户实证「进去读的话，这些文本应该可以复制」：
        // 原来只是裸 Text，长按没有任何反应。包一层 SelectionContainer，
        // 长按选词 / 拖动选段 / 系统复制都能用，滚动与字号按钮不受影响。
        SelectionContainer {
            LazyColumn(state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 18.dp)) {
                items(body) { p ->
                    Text(p, fontSize = size.sp, lineHeight = (size * 1.7f).sp,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
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

    // R262 · 与全屏播放器同款视觉：小封面 + mm:ss + 倍速胶囊 + 三枚图标，
    // 进度条落到卡片底边而不是压在顶上；整条仍可点开全屏页。
    val timeText = "%02d:%02d / %02d:%02d".format(
        st.positionMs / 60000, st.positionMs / 1000 % 60,
        st.durationMs / 60000, st.durationMs / 1000 % 60)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        .background(HubCard, RoundedCornerShape(20.dp))
        .clickable { onExpand() }
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerCover(HubPrimary, 44.dp, st.isPlaying)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(st.label.ifBlank { "未在播放" }, fontSize = 13.sp, maxLines = 1,
                    fontWeight = FontWeight.Bold, color = palette.text)
                Spacer(Modifier.height(3.dp))
                Text(timeText, fontSize = 11.sp, color = HubSub)
            }
            // K1 · 用户实证「播放整章应该能调倍速」。原来倍速只藏在全屏
            // 播放器页里，听整章时看到的是这条常驻条，根本找不到入口。
            // 这里给一枚常驻胶囊：点一下循环切档，播放器页仍留五档直选。
            Box(Modifier.height(28.dp)
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
            Spacer(Modifier.width(4.dp))
            // 全部换成代码绘制的真图标，不再用「‹ ⏸ ▶ ›」字符
            IconButton(onClick = { PlayQueue.previous() }, modifier = Modifier.size(34.dp)) {
                IconSkip(false, HubPrimary, Modifier.size(18.dp))
            }
            IconButton(onClick = { PlayQueue.toggle() },
                modifier = Modifier.size(36.dp)
                    .background(HubPrimary, RoundedCornerShape(50))) {
                IconPlayPause(st.isPlaying, palette.onPrimary, Modifier.size(18.dp))
            }
            IconButton(onClick = { PlayQueue.next() }, modifier = Modifier.size(34.dp)) {
                IconSkip(true, HubPrimary, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().height(3.dp)
            .background(HubLine, RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight()
                .background(HubPrimary, RoundedCornerShape(50)))
        }
    }
}

/**
 * R171 · 任务看板（目录页就地版）。
 *
 * 用户实证：「像任务这种看板，还没有合进这个目录页」。原来要看跨书任务必须
 * 切到独立的任务页，而目录页只有「队列 N」一个数字。这里把看板做成目录页的
 * 就地弹层：数据全部来自 TaskResume / Producer 的现行 API，与独立任务页同源，
 * 不复制状态口径；删除同样走二次确认，只删记录不删音频。
 *
 * 组合期不碰磁盘：全部在 LaunchedEffect + IO 里算完再显示。
 */
@Composable
private fun TaskBoardDialog(
    c: Context,
    onDismiss: () -> Unit,
    onOpenBook: (String) -> Unit,
    onStartTask: (TaskInfo) -> Unit,
    onChanged: () -> Unit
) {
    val st by Producer.state.collectAsState()
    val live by Producer.resume.collectAsState()
    var tick by remember { mutableStateOf(0) }
    var tasks by remember { mutableStateOf(emptyList<TaskInfo>()) }
    var queued by remember { mutableStateOf(emptyList<TaskInfo>()) }
    var qPaused by remember { mutableStateOf(false) }
    var realMap by remember { mutableStateOf(emptyMap<String, Int>()) }
    var warnMap by remember { mutableStateOf(emptyMap<String, String?>()) }
    var bookIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf<TaskInfo?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val ts = TaskResume.list(c)
            val running = Producer.isBusy()
            tasks = ts
            queued = TaskResume.queue(c)
            qPaused = Producer.queueState(c).second
            bookIds = BookStore.list(c).map { it.id }.toSet()
            realMap = ts.associate {
                it.bookId to if (running) 0 else TaskResume.realProgress(c, it)
            }
            warnMap = ts.associate { it.bookId to TaskResume.invalidationReason(c, it) }
        }
    }

    val deleting = confirmDelete
    if (deleting != null) {
        val t = deleting
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删掉这条任务？", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text("已做好的音频不会删", fontSize = 13.sp, color = HubSub)
            },
            confirmButton = {
                TextButton(onClick = {
                    EventLog.ui("TASK_DELETE", "board id=${t.bookId.take(8)}")
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            TaskResume.remove(c, t.bookId)
                            TaskResume.dequeue(c, t.bookId)
                        }
                        confirmDelete = null
                        tick++
                        onChanged()
                    }
                }) { Text("删除", fontSize = 13.sp, color = HubWarn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    } else {
        // R243 · 同 showQueue 弹窗口径：任务弹窗不再给「继续队列」按钮，
        // 「队列已暂停」提示仍在，底层 Producer.resumeQueue 不动。
        val resumeQueueButton: (@Composable () -> Unit)? = null
        AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("任务 · ${tasks.size} 本", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (queued.isNotEmpty() || qPaused) {
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = HubSoft)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (queued.isNotEmpty())
                                    "队列 ${queued.size} 个待做 · 当前这本做完自动接下一个"
                                 else "队列里没有待做任务",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (qPaused) HubWarn else HubPrimary)
                            if (queued.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(queued.joinToString("、") { "《${it.title}》" }.take(60),
                                    fontSize = 11.sp, color = HubSub, maxLines = 2)
                            }
                            if (qPaused) {
                                Spacer(Modifier.height(4.dp))
                                Text("队列已暂停", fontSize = 11.sp, color = HubWarn)
                            }
                        }
                    }
                }
                if (tasks.isEmpty()) {
                    Text("还没有制作任务", fontSize = 12.sp, color = HubSub)
                }
                val unfinished = tasks.filter { it.phase != "done" }
                val done = tasks.filter { it.phase == "done" }
                (unfinished + done).forEach { t ->
                    val runningThis = st.running && live?.bookId == t.bookId
                    val badge = when {
                        runningThis -> "进行中"
                        t.phase == "done" -> "已完成"
                        t.phase == "reconfirm" -> "需重新确认"
                        t.phase == "blocked" -> "没开始"
                        t.phase == "stopped" -> "已停止"
                        else -> "已中断"
                    }
                    val modeText = when (t.modeId) {
                        "custom_voice" -> "预设音色"
                        "base" -> "参考音频"
                        else -> "描述造音色"
                    }
                    val scopeText = if (t.only == null) "整本"
                        else "选中 ${t.only?.size ?: 0} 章"
                    val real = realMap[t.bookId] ?: 0
                    val total = maxOf(t.total, real, if (runningThis) st.total else 0)
                    val doneNow = (if (runningThis) st.done else real)
                        .coerceIn(0, maxOf(total, 1))
                    val warn = warnMap[t.bookId]
                    val otherBusy = st.running && live?.bookId != t.bookId
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = HubCard)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.title, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, modifier = Modifier.weight(1f),
                                    color = palette.text)
                                Text(badge, fontSize = 11.sp,
                                    color = if (runningThis) HubPrimary
                                            else if (t.phase == "done") HubGood else HubWarn)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("$modeText · $scopeText", fontSize = 11.sp, color = HubSub)
                            if (t.bookId !in bookIds) {
                                Text("书已从书架删掉，成品仍在「我的音频」",
                                    fontSize = 11.sp, color = HubWarn)
                            }
                            warn?.let {
                                Text(it, fontSize = 11.sp, color = HubWarn)
                            }
                            if (total > 0) {
                                Spacer(Modifier.height(7.dp))
                                LinearProgressIndicator(
                                    progress = { doneNow.toFloat() / total.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(6.dp))
                                Spacer(Modifier.height(4.dp))
                                Text("已做好 $doneNow/$total 块", fontSize = 11.sp, color = HubSub)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!runningThis && t.phase != "done") {
                                    // R172 · 旧 only 是旧章号，绝不能拿它直接开跑
                                    TextButton(onClick = { onStartTask(t) },
                                        enabled = !otherBusy && t.bookId in bookIds &&
                                            t.phase != "reconfirm") {
                                        Text(if (t.phase == "reconfirm") "需重新选章"
                                             else "继续制作",
                                            fontSize = 11.sp,
                                            color = if (t.phase == "reconfirm") HubSub
                                                    else HubPrimary)
                                    }
                                }
                                TextButton(onClick = {
                                    onDismiss()
                                    onOpenBook(t.bookId)
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UiIconGlyph(UiIcon.EXPORT, HubPrimary)
                                        Spacer(Modifier.width(3.dp))
                                        Text("打开这本书 ›", fontSize = 11.sp, color = HubPrimary)
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { confirmDelete = t },
                                    enabled = !runningThis) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UiIconGlyph(UiIcon.DELETE,
                                            if (runningThis) HubSub else HubWarn)
                                        Spacer(Modifier.width(3.dp))
                                        Text("删除任务", fontSize = 11.sp,
                                            color = if (runningThis) HubSub else HubWarn)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
        dismissButton = resumeQueueButton
    )
    }
}
