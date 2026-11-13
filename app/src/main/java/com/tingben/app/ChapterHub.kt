package com.tingben.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
 *   · 右侧两枚图标：看原文 / 听成品（R171 撤掉三点「更多」，选中一律长按）
 *   · R264 · 章名不再管展开/收起，制作中的那一章恒定展开小段区
 *   · 长按章行 = 进多选，勾选框**只在多选态出现**，平时不占位
 *     R171：勾选态里按返回键 / 点「取消」都退出勾选并留在目录页，
 *     不会一按返回就离开这本书；选空了底部条也还在，取消入口不消失
 *   · 制作章就地铺小片格子，点一下播再点一下停，长按可批量删/试听
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
    // R180 · 模型没齐时「去导入模型 ›」的出口
    onOpenModels: () -> Unit = {},
    // R218 · 制作设置页点「重新分章」回本页时唤起弹窗
    resplitRequest: Int = 0
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
                    // R287 · 一次扫描出章级成品与可播章集合，不再各扫一遍全盘。
                    val snap = AudioLibrary.chapterSnapshot(c, b.id)
                    Triple(
                        HubLoad(b, s.first, snap.first, snap.second,
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
    // R308 · 章级「拼接中 / 转码中」的实时阶段，章名蒙层与格子转圈都读它。
    val mergeBusy by AudioOps.mergeBusy.collectAsState()

    val diskKey = "${!st.running}|${st.stage}"
    var disk by remember(book.id, refresh) {
        mutableStateOf<Pair<Map<Int, AudioItem>, Set<Int>>?>(null)
    }
    LaunchedEffect(book.id, refresh, diskKey, localTick) {
        if (disk != null) delay(350)
        // R287 · 原来两个查询各做一次全盘扫描（index.json + 所有成品目录 +
        // 逐文件伴生 meta），制作中 stage 一变就双份 IO。合并成一次。
        disk = withContext(Dispatchers.IO) {
            AudioLibrary.chapterSnapshot(c, book.id)
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
    // R264 · 制作中的那一章恒定展开：没有「展开/收起」状态、没有切换按钮、
    // 也不再靠点章名开关。liveChapter 是派生值，收工即无展开体。
    val bookRunning = st.running && task?.bookId == book.id
    val makingChapter = remember(st.chapterProgress, task, ui.size) {
        var idx = -1
        for ((k, v) in st.chapterProgress) if (v < 1f && k > idx) idx = k
        if (idx < 0) idx = task?.only?.maxOrNull() ?: 0
        idx.coerceIn(0, (ui.size - 1).coerceAtLeast(0))
    }
    val liveChapter: Int? = if (bookRunning) makingChapter else null
    // R253 · 长按章行弹就地菜单（像桌面右键），与「勾选后进底部批量条」是两组操作。
    var menuChapter by remember(book.id) { mutableStateOf<Int?>(null) }
    var segMulti by remember(book.id) { mutableStateOf(false) }
    var segChecked by remember(book.id) { mutableStateOf(setOf<Int>()) }
    // R315 · 勾选后菜单的「导出与另存」二级菜单开关；旧的 showQueue（目录页
    // 就地任务浮窗）随浮窗一起撤掉，队列统一进全局任务页。
    var exportMenu by remember(book.id) { mutableStateOf(false) }
    // R178 · 真机日志里 17:59:40 STOP → 17:59:41 RESUME 只隔 0.3 秒，
    // R219 · 点进度条滚到正在制作的那一章，不用一章章翻
    val chapterListState = rememberLazyListState()

    // 一次读盘、书内共用同一份真值：按制作章过滤的小段与整本分段计划同源。
    // R287 · 整本计划（2 万+ 段）只在切书 / 任务切换 / 显式刷新时读一次，并在
    // IO 线程直接建好「按章索引」。原来这个 effect 把 st.done 当 key，制作中每
    // 完成一段就重读 segmap、对 26678 个 wav 逐个 stat，组合层再全量 filter，
    // 这是目录页乃至整个软件发卡的硬根因。
    var hubByChapter by remember(book.id) {
        mutableStateOf<Map<Int, List<SegRow>>>(emptyMap())
    }
    // R287 · 当前制作章的小段：每秒只 stat 几百个文件，有变化才写回，不空转重组。
    var liveSegs by remember(book.id) { mutableStateOf(emptyList<SegRow>()) }
    // 这份快照属于哪一章；切章时还没刷新到就先回退到按章索引，不串上一章的格子。
    var liveSegsFor by remember(book.id) { mutableStateOf<Int?>(null) }
    // R266 · 重做标记单独轻量观察：只秒读 redo.json（几 KB），不重扫 2 万段。
    // 标记后转黄、Producer 清标记后转绿，都不必等整本做完或 stage 合并。
    var redoKeys by remember(book.id) { mutableStateOf(emptySet<String>()) }
    // R330 · 哪些章还有可拼的音频小块（磁盘实存份数）：「拼接并转码」状态行
    // 的显示与段数都读它。一次 AudioTree.load 扫完，不逐章 stat 2 万段。
    var mergeReady by remember(book.id) { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    // R333 · 回看章：关掉自动开关后小块留着、制作已走到下一章，点章行上的
    // 「待拼」标签把这一章的小块区摊开；仍最多只展开一章，不会整本铺格子。
    var peekChapter by remember(book.id) { mutableStateOf<Int?>(null) }
    // R333 · 手动拼章进行中：点下去当场锁这一章，不等 mergeBusy 回流，
    // 防止连点把同一章重复拼 / 转码、重复删内部 WAV。
    var mergingChapter by remember(book.id) { mutableStateOf<Int?>(null) }
    // R333 · 制作中永远显示正在做的那一章，回看不抢它；停下来才允许回看
    // 上一章留着的小块，避免把制作中的格子突然顶掉、把软件改坏。
    val shownChapter: Int? = if (bookRunning) liveChapter else (peekChapter ?: liveChapter)
    // R297 · 批量标记放 IO，主线程只管界面反馈，勾几十段也不卡。
    val redoScope = rememberCoroutineScope()
    val pqSeg by PlayQueue.previewState.collectAsState()
    LaunchedEffect(book.id, st.running, localTick, task?.bookId) {
        mergeReady = withContext(Dispatchers.IO) {
            AudioTree.load(c).firstOrNull { it.bookId == book.id }
                ?.chapters?.associate { it.chapter to it.segments.size } ?: emptyMap()
        }
    }
    LaunchedEffect(book.id, st.running, localTick, task?.bookId, liveChapter) {
        hubByChapter = withContext(Dispatchers.IO) {
            val t = task?.takeIf { it.bookId == book.id } ?: TaskResume.byBook(c, book.id)
            val all = Workbench.segments(c, t)
            val m = Workbench.segChapters(c, t)
            if (m == null) emptyMap()
            else all.groupBy { m[it.index] ?: -1 }.filterKeys { it >= 0 }
        }
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
            // R301 · 正常跑完的书每章成品已在章边界拼好、中间小块已清（R300），
            // 再每次进目录页全量读 segmap 只是空转：1.1.32 真机日志 7 次
            // 「补拼 0 章 / 跳过 3 章」全是这类白烧 IO。只有中断 / 停止 /
            // 待确认的任务，或一个章成品都还没有的旧书，才值得回溯补拼。
            val hasAudio = AudioLibrary.chapterSnapshot(c, book.id).second.isNotEmpty()
            val pendingPhase = t != null && (t.phase == "interrupted" ||
                t.phase == "stopped" || t.phase == "blocked" || t.phase == "reconfirm")
            if (hasAudio && !pendingPhase) return@withContext 0
            val (all, map) = Workbench.segments(c, t) to Workbench.segChapters(c, t)
            // R329 · 只做选中章时工作目录是带 only 分桶的子目录，硬拼
            // work/{bookId} 会指到别的桶甚至认错同名文件。
            val w = Workbench.workDir(c, t)
            val drops = SegDrops.snapshot(c, book.id)
            val byCh = LinkedHashMap<Int, MutableList<File>>()
            for (sg in all) {
                val ch = map?.get(sg.index) ?: continue
                if (ch < 0) continue
                if (SegDrops.matches(drops, sg.wavName, sg.text.take(200))) continue
                byCh.getOrPut(ch) { mutableListOf() }
                    .add(sg.wav ?: File(w ?: File(c.filesDir, "work/${book.id}"), sg.wavName))
            }
            AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,
                gapMs = Prefs.defaultPerf(c).gapMs,
                declickMs = Prefs.mergeDeclickMs(c)).first
        }
        // 拼出成品才刷一次磁盘快照，让小耳机当页就亮；无成品不空转。
        if (made > 0) localTick++
    }
    // R287 · 组合层只处理当前章几百行，不再每次重组都 filter 26678 段。
    val chapterSegs = remember(shownChapter, liveSegs, liveSegsFor, hubByChapter, redoKeys) {
        val ch = shownChapter
        if (ch == null) emptyList()
        else (liveSegs.takeIf { liveSegsFor == ch } ?: hubByChapter[ch] ?: emptyList())
            .map { sg ->
                sg.copy(pendingRedo = "seg:${sg.index}" in redoKeys)
            }
    }
    // R220 · 小段编号每章从 1 重新开始，不再接着前一章的一堆大数字。
    // 文件里的段号仍是整本连续的（续跑/拼接都靠它），只改显示。
    val chapterBase = remember(chapterSegs) {
        chapterSegs.minOfOrNull { it.index } ?: 0
    }
    // R308 · 制作中这一章，第一个还没生成的小块就是「正在合成」的那一格；
    // 重做中的格子由 pendingRedo 单独认。两者都在格子内转圈，不再让用户
    // 对着一片灰格子猜进度走到哪了。
    val segBusyIndex = remember(chapterSegs, st.running, st.done, shownChapter, liveChapter) {
        // R333 · 回看的是上一章待固化小块时，不该把「正在合成」套在它头上。
        if (!st.running || chapterSegs.isEmpty() || shownChapter != liveChapter) null
        else chapterSegs.firstOrNull { !it.hasAudio }?.index
    }
    var beatTick by remember { mutableStateOf(0) }
    // R287 · 每秒除读 redo.json，只 stat 当前制作章的小段（几百个文件）。
    // 整本 segmap 不重读、26678 次 stat 不再发生；只有格子转绿/切章才写回状态。
    LaunchedEffect(book.id, st.running, localTick, liveChapter, hubByChapter) {
        if (!st.running) return@LaunchedEffect
        var lastFlags = emptyList<Boolean>()
        var lastCh: Int? = null
        while (true) {
            delay(1000); beatTick++
            val t = TaskResume.byBook(c, book.id)
            val w = Workbench.workDir(c, t)
            redoKeys = if (w == null) emptySet()
            else withContext(Dispatchers.IO) { SegPlan.pending(w) }
            val ch = liveChapter
            val rows = if (ch == null) emptyList() else hubByChapter[ch] ?: emptyList()
            val fresh = if (w == null || rows.isEmpty()) emptyList()
            else withContext(Dispatchers.IO) {
                rows.map { sg ->
                    val f = File(w, sg.wavName)
                    sg.copy(wav = f.takeIf { it.exists() && it.length() > 44 })
                }
            }
            if (ch != null) {
                val flags = fresh.map { it.wav != null }
                if (flags != lastFlags || ch != lastCh) {
                    liveSegs = fresh
                    liveSegsFor = ch
                    lastFlags = flags
                    lastCh = ch
                }
            }
        }
    }
    // 非制作时标记/清标记也要立刻反映（重做一轮走的就是这条路径）
    LaunchedEffect(book.id, st.running, localTick) {
        if (st.running) return@LaunchedEffect
        val t = TaskResume.byBook(c, book.id)
        val w = Workbench.workDir(c, t)
        redoKeys = if (w == null) emptySet()
        else withContext(Dispatchers.IO) { SegPlan.pending(w) }
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
            // R308 · 章成品已经是 M4A 时这是「手动另存兜底」：直接写出去，
            // 不再把压缩音频当 WAV 重新编一遍；只有 WAV 成品才走转码。
            val alreadyM4a = src.name.endsWith(".m4a", ignoreCase = true)
            if (!alreadyM4a) Toast.makeText(c, "正在转 M4A…", Toast.LENGTH_SHORT).show()
            exportScope.launch {
                val tmp = if (alreadyM4a) src
                    else File(c.cacheDir, "conv_${System.currentTimeMillis()}.m4a")
                val r = if (alreadyM4a) null
                    else withContext(Dispatchers.IO) { AudioOps.toM4a(c, src, tmp) }
                if (r == null || r.ok) {
                    val ok = withContext(Dispatchers.IO) { AudioOps.copyTo(c, tmp, uri) }
                    Toast.makeText(c, if (ok) "已另存 M4A" else "写出失败",
                        Toast.LENGTH_SHORT).show()
                } else Toast.makeText(c, r.reason, Toast.LENGTH_LONG).show()
                if (!alreadyM4a) tmp.delete()
                tmpCopy?.delete(); tmpCopy = null; pendingSrc = null
            }
        } else { tmpCopy?.delete(); tmpCopy = null; pendingSrc = null }
    }
    fun filesOf(i: Int): Pair<List<File>, File?> {
        val ch = AudioTree.load(c).firstOrNull { it.bookId == book.id }
            ?.chapters?.firstOrNull { it.chapter == i }
        // R329 · 整本做过又单独重做过某章时，work 下会并存多个 only 分桶，
        // AudioTree.load 会把各桶同章的小块全扫进来，手动「拼成整章」就
        // 把两套声音拼一起。优先用当前任务的按章索引（只来自本次任务的
        // segmap + seg_chapters），并现 stat 一遍，不拿到已删的旧块。
        val cur = hubByChapter[i]?.mapNotNull {
            it.wav?.takeIf { f -> f.isFile && f.length() > 44 }
        }
        val segs = if (!cur.isNullOrEmpty()) cur
            else ch?.segments?.map { it.file } ?: emptyList()
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
            // R283 · 旧版整本成品兜底时没有伴生偏移表，起点只能从 0 起；标签明示，不假装精确
            PlayQueue.setTracks(listOf(Track(a.file, "${book.title} · 第 ${i + 1} 章" +
                (if (AudioLibrary.isWholeBook(a) && a.chapters.size > 1) "（从整本开头播）" else ""),
                AudioLibrary.chapterStartMs(c, a, i),
                id = "chapter:${a.file.absolutePath}")), q)
            PlayQueue.playFrom(0, id = q)
        } else {
            val list = AudioLibrary.playableItems(c, book.id)
            if (list.isEmpty()) return
            PlayQueue.setTracks(list.map { (idx, a) ->
                Track(a.file, "${book.title} · 第 ${idx + 1} 章" +
                    (if (AudioLibrary.isWholeBook(a) && a.chapters.size > 1) "（从整本开头播）" else ""),
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
    // R171 · 勾选态 / 分段勾选态都自己消费返回键：
    // 先关掉当前这层并留在目录页，不再让全局 goBack 直接把人送回书架。
    // 注册在 pane 的 BackHandler 之后（Compose 后注册优先），弹层优先收起。
    BackHandler(enabled = pane != null) { pane = null }
    BackHandler(enabled = multiMode) { exitMulti() }
    // R255 · 长按小菜单优先消费返回键：原来菜单弹着按返回会直接退勾选态
    // 甚至离开这本书，和左勾 / 底部条抢操作。菜单与底部条也互斥显示。
    BackHandler(enabled = menuChapter != null) { menuChapter = null }
    BackHandler(enabled = segMulti) { exitSegMulti() }
    // R315 · 目录页不再有就地任务浮窗，返回键不再需要为它单独兜一层。
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
    val stalledTask = !st.running && task?.bookId == book.id &&
        st.stage in listOf("没完成", "已停止", "出错了")
    val totalNow = maxOf(st.total, task?.total ?: 0)
    val doneNow = (if (bookRunning) st.done else (task?.done ?: 0))
        .coerceIn(0, maxOf(totalNow, 1))
    val bookPct = if (totalNow > 0) doneNow.toFloat() / totalNow.toFloat() else 0f
    val chNow = liveChapter
    val chPct = if (chNow == null) 0f else
        (ui.getOrNull(chNow)?.synthesized
            ?: st.chapterProgress[chNow] ?: 0f).coerceIn(0f, 1f)
    // R264 · 制作中本章进度条就取恒定展开的那一章；收工后取最靠后一章
    val chapterPctNow = if (chNow != null) chPct else run {
        var m = 0f
        for ((_, v) in st.chapterProgress) if (v > m) m = v
        m
    }
    val showHead = bookRunning || stalledTask ||
        (task?.bookId == book.id && st.stage == "完成")
    fun scrollToMaking() {
        resplitScope.launch {
            chapterListState.animateScrollToItem(makingChapter)
        }
    }
    /**
     * R270 · 只给「整章重做」用：已拼好的章只有整章成品、小块早被清掉，
     * 撤掉旧成品与 done 记录，主循环才会重新生成这一章的全部段。
     * 不连文件一起删的话 `AudioLibrary.list()` 的兜底恢复扫描会把它重新登记回来，
     * 补拼依旧跳过、小耳机仍播旧声音。整本成品 scope=整本，`chapterOnlyFor` 不会误删。
     * 制作中的章没有章级成品，不走这里。
     */
    fun invalidateChapterAudio(ch: Int) {
        AudioLibrary.chapterOnlyFor(c, book.id, ch)?.let { AudioLibrary.removeFile(c, it.file) }
        AudioTree.clearDone(c, book.id, ch)
        EventLog.ui("CH_AUDIO_INVALIDATE", "ch=$ch")
    }

    // R253 · 长按菜单「整章重做」：该章所有段标记重做、撤掉旧整章成品，再启动只做这章。
    // R270 · 旧注释「小块 WAV 不删、失败可手动拼回」是 R255 之前的口径；
    // 拼成整章后中间小块已清掉，重做就是重新生成全章，不存在留着的小块。
    fun redoWholeChapter(ch: Int) {
        val t = task?.takeIf { it.bookId == book.id } ?: TaskResume.byBook(c, book.id)
        val work = Workbench.workDir(c, t)
        // R287 · 按章索引直接取，不再在主线程 filter 整本 26678 段。
        val segsHere = hubByChapter[ch] ?: emptyList()
        if (work == null || segsHere.isEmpty()) {
            Toast.makeText(c, "没找到这一章的制作记录", Toast.LENGTH_SHORT).show()
            return
        }
        // R270 · 只撤「已拼好的章」的旧成品；制作中的章没有成品，不走这条。
        invalidateChapterAudio(ch)
        // R266 · 制作中同样只标记，让主循环逐段回头重做；排整章任务进队列会等整本跑完。
        val busyHere = Producer.isBusy()
        // R329 · 空闲时新任务按 only={ch} 落在另一个工作分桶，旧目录没有
        // 小块可复用、本来就会整章重合成；往旧分桶写标记只会留下无人消费的
        // 孤儿，目录页还会把它当成「永远在重做」。制作中才需要就地标记。
        if (busyHere) segsHere.forEach { SegPlan.markSeg(work, it.index) }
        else Producer.enqueueOrStart(c, book, setOf(ch), null, Prefs.defaultPerf(c))
        EventLog.ui("CH_REDO", "ch=$ch n=${segsHere.size} running=$busyHere")
        localTick++
        Toast.makeText(c, if (busyHere)
            "第 ${ch + 1} 章已标记重做：当前这段做完就逐段接上"
        else "第 ${ch + 1} 章已排重做，按当前调音台重新合成",
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
    // R291 · 用「当前模式能不能合成」判，不用「4 份是否全装」判：
    // 只装够当前模式就能开跑，不该一直挂「去导入」。
    val needModels = remember(refresh) { ModelStore.synthesisBlockReason(c) != null }

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
            // R315 · 图 10/11/15 里两条裸进度条谁也认不出是哪根：各补一行
            // 极短标签（消歧义，不是说明文），右侧给百分比；条高收细一点，
            // 点整块滚到制作章照旧，数据源一根没动。
            Column(Modifier.fillMaxWidth().background(HubCard)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clickable { scrollToMaking() }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("整本制作", fontSize = 10.sp, color = HubSub)
                    Spacer(Modifier.weight(1f))
                    Text("${(bookPct * 100).toInt()}%", fontSize = 10.sp, color = HubSub)
                }
                LinearProgressIndicator(progress = { bookPct },
                    modifier = Modifier.fillMaxWidth().height(18.dp),
                    color = HubPrimary, trackColor = HubLine)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已出章音频", fontSize = 10.sp, color = HubSub)
                    Spacer(Modifier.weight(1f))
                    Text("${(chapterPctNow * 100).toInt()}%", fontSize = 10.sp, color = HubSub)
                }
                LinearProgressIndicator(progress = { chapterPctNow },
                    modifier = Modifier.fillMaxWidth().height(14.dp),
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
                        // R315 · 图 10：两枚入口原来挤在一个浅色小胶囊里，
                        // 看着像一个按钮。接着听做实心主色，从头那枚做浅底，
                        // 中间留白，一眼分得开；文案与事件名不动。
                        Box(Modifier.height(34.dp)
                            .background(HubPrimary, RoundedCornerShape(50))
                            .clickable {
                                playBook(resumePos.trackId, resumePos.positionMs)
                                EventLog.ui("HUB_RESUME_LISTEN", "book=${book.id.take(8)}")
                            }
                            .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center) {
                            Text("接着听 · %d:%02d ›".format(
                                resumePos.positionMs / 60000,
                                (resumePos.positionMs / 1000) % 60),
                                fontSize = 12.sp, color = palette.onPrimary,
                                fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(Modifier.height(34.dp)
                        .background(HubSoft, RoundedCornerShape(50))
                        .clickable { playBook(null, null) }
                        .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center) {
                        Text("按顺序播放", fontSize = 12.sp, color = HubPrimary)
                    }
                } else if (needModels) {
                    TextButton(onClick = { onOpenModels() }) {
                        Text("去导入模型 ›", fontSize = 12.sp, color = HubWarn)
                    }
                }
                if (queued.isNotEmpty()) {
                    // R315 · 图 17 揭出 R298 没做干净：目录页还留着第二枚任务
                    // 按钮、还弹按整本书列的任务浮窗。这里按钮改成一行不可点的
                    // 状态文字，队列的查看 / 移除统一从主页右上角进全局任务页。
                    // R315 · 队列只留一行状态，诊断埋点照旧打，不弹窗、不摆按钮。
                    LaunchedEffect(queued.size) {
                        EventLog.ui("HUB_QUEUE", "n=${queued.size}")
                    }
                    Text("队列 ${queued.size} · 去任务页看", fontSize = 12.sp,
                        color = HubSub)
                }
            }
            HubDivider()

            LazyColumn(Modifier.fillMaxSize(), state = chapterListState,
                contentPadding = PaddingValues(bottom = 100.dp)) {
                ui.forEach { r ->
                    item(key = "ch_${r.index}") {
                        ChapterRow(
                            r = r, multi = multiMode, checked = r.index in selected,
                            // R264 · 章名不再承担展开/收起：制作中的那一章自动展开，
                            // 看书交给右侧小眼睛，章行只保留长按菜单。
                            // R304 · 长按就地菜单只在「没进勾选态」时弹：进了勾选态
                            // 就该由底部那张菜单管，长按不再跟它抢操作。
                            onLong = { if (!multiMode) menuChapter = r.index },
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
                            canRedo = r.audio != null || r.playable,
                            busyLabel = mergeBusy
                                ?.takeIf { it.bookId == book.id && it.chapter == r.index }
                                ?.phase,
                            // R333 · 有待固化小块的章，章行右侧给一枚「待拼 N 段」小标签；
                            // 点它把这一章的小块区摊开，拼章收尾条就在那堆格子下面。
                            peekHint = (mergeReady[r.index] ?: 0)
                                .takeIf { it > 0 && r.index != shownChapter }
                                ?.let { "待拼 $it 段" },
                            onPeek = {
                                if (!bookRunning) {
                                    exitSegMulti()
                                    peekChapter = r.index
                                }
                            })
                    }
                    // R333 · 旧形态作废：章名下一行那条整行实心大蓝条，对已完成的章
                    // 也常驻，下面却常常没有小块格子，看着不知道在管哪一章的哪堆声音。
                    // 收尾条改到「这一章的小块区」末尾，跟它要处理的格子同一个视觉块。
                    // R264 · 只给制作中的那一章生成小段区，恒定展开、无收起入口；
                    // R333 · 回看章（关掉自动开关后留着小块、制作已走下一章）也摊开，
                    // 但 shownChapter 仍只取一个，绝不会整本几百章一起铺格子。
                    if (r.index == shownChapter) {
                    item(key = "seg_head_${r.index}") {
                        Box(Modifier.fillMaxWidth().background(HubSoft)) {
                                SegGridHead(chapterSegs, segMulti, segChecked.size,
                                    onRedo = {
                                        val rows = chapterSegs.filter {
                                            it.index in segChecked && it.hasAudio
                                        }
                                        val t = task?.takeIf { it.bookId == book.id }
                                            ?: TaskResume.byBook(c, book.id)
                                        val work = Workbench.workDir(c, t)
                                        // R333 · 回看章的小块也能勾坏块重做，修完再点收尾条固化。
                                        val ch = shownChapter
                                        when {
                                            rows.isEmpty() -> Toast.makeText(c,
                                                "先勾几个已生成的小块",
                                                Toast.LENGTH_SHORT).show()
                                            work == null -> Toast.makeText(c,
                                                "没找到这本书的制作记录",
                                                Toast.LENGTH_SHORT).show()
                                            else -> {
                                                // R266 · 制作中绝不再排「整章新任务」进队列：
                                                // 那样得等整本跑完才轮到，当前任务一停队列
                                                // 还会永久暂停，看着就像这一章不做了。只标记，
                                                // 由 Producer 主循环在当前段收尾后就地回头重做。
                                                // R297 · 旧做法逐段 markSeg（每段一次 fsync +
                                                // 备份 + rename），勾几十段就把主线程卡死；还会和
                                                // Producer 的 clearRedo 并发覆盖。改成一次批量写、
                                                // 放 IO，界面反馈仍回主线程。
                                                val busyHere = Producer.isBusy()
                                                // R270 · 小块只存在于制作中这一章，此时还没有章级成品，
                                                // 没有任何「旧成品」要撤。拼章后小块会被清掉，
                                                // 已合成的章根本没有小块可操作（要动走整章重做）。
                                                val w = work
                                                val idxs = rows.map { it.index }
                                                val n = rows.size
                                                redoScope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        SegPlan.markSegs(w, idxs)
                                                    }
                                                    EventLog.ui("SEG_REDO",
                                                        "n=$n running=$busyHere")
                                                    exitSegMulti()
                                                    if (!busyHere && ch != null) {
                                                        Producer.enqueueOrStart(c, book, setOf(ch),
                                                            null, Prefs.defaultPerf(c))
                                                    }
                                                    localTick++   // 立刻重读 redo.json，格子转琥珀
                                                    Toast.makeText(c,
                                                        if (busyHere)
                                                            "已标记 $n 段（格子转黄）：当前这段做完马上重做，做完变回绿色"
                                                        else
                                                            "已标记 $n 段，按当前调音台重新合成",
                                                        Toast.LENGTH_LONG).show()
                                                }
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
                        }
                    }
                    // R287 · 恢复 R155「按行拆成父列表独立 item」：R264 曾把几百行
                    // 格子塞进一个 item 全量组合，长章滚动/每次刷新都必卡。
                    items(chapterSegs.chunked(SEG_GRID_COLUMNS),
                        key = { row -> "seg_row_${r.index}_${row.first().index}" }) { row ->
                        Box(Modifier.fillMaxWidth().background(HubSoft)) {
                            ChapterSegGrid(
                                row = row, pq = pqSeg, base = chapterBase,
                                multi = segMulti, checked = segChecked,
                                busyIndex = segBusyIndex,
                                onLong = { i -> segMulti = true; segChecked = setOf(i) },
                                onToggle = { i ->
                                    segChecked = if (i in segChecked) segChecked - i
                                                else segChecked + i
                                },
                                onTap = { sg -> tapSeg(sg) })
                        }
                    }
                    // R333 · 收尾条跟上面那一堆小块同一个视觉块（HubSoft 连续），
                    // 不再是章名下一行孤零零一整条实心主按钮；只在真有小块 / 正在拼时出。
                    val readyHere = mergeReady[r.index] ?: 0
                    val hasDone = r.audio != null || r.playable
                    val redoHere = hubByChapter[r.index].orEmpty()
                        .count { "seg:${it.index}" in redoKeys }
                    val phaseHere = mergeBusy
                        ?.takeIf { it.bookId == book.id && it.chapter == r.index }
                        ?.phase
                    val busyHere = (st.running && r.index == liveChapter)
                        || mergingChapter == r.index || phaseHere != null
                    val canMerge = readyHere > 0 && redoHere == 0 && !busyHere
                    val status = when {
                        phaseHere != null -> phaseHere
                        mergingChapter == r.index -> "正在拼这一章…"
                        busyHere -> "这一章还在制作中，做完再拼"
                        redoHere > 0 -> "还有 $redoHere 段待重做，先重做完再拼"
                        readyHere == 0 -> "这一章还没有音频小块"
                        else -> "$readyHere 段就绪 · 把上面这些小块做成一个章音频"
                    }
                    if (readyHere > 0 || hasDone || busyHere) {
                        item(key = "merge_footer_${r.index}") {
                            MergeChapterFooter(
                                label = if (hasDone) "重新拼接并转码" else "拼接并转码",
                                status = status, enabled = canMerge, busy = busyHere,
                                onClick = {
                                    // R333 · 点下去当场锁这一章，不等状态回流，防连点
                                    // 把同一章重复拼 / 转码、重复删内部 WAV。
                                    if (mergingChapter == null) {
                                        mergingChapter = r.index
                                        redoScope.launch {
                                            // R335 · 磁盘 / 转码抛异常时也必须解锁，否则这一章
                                            // 永久转圈，连别章的拼章入口都被这把锁挡住。
                                            try {
                                                val parts = withContext(Dispatchers.IO) {
                                                    filesOf(r.index).first
                                                }
                                                val rr = if (parts.isEmpty()) null
                                                else withContext(Dispatchers.IO) {
                                                    AudioOps.mergeChapter(c, book.id, book.title,
                                                        r.index, parts,
                                                        gapMs = Prefs.defaultPerf(c).gapMs,
                                                        declickMs = Prefs.mergeDeclickMs(c))
                                                }
                                                Toast.makeText(c,
                                                    if (rr == null) "这一章还没有可拼的音频小块"
                                                    else if (rr.ok) rr.reason.ifBlank { "已拼接并转码" }
                                                    else rr.reason, Toast.LENGTH_LONG).show()
                                            } catch (t: Throwable) {
                                                EventLog.log(EventLog.Kind.MODEL, "MERGE_MANUAL_FAIL",
                                                    "ch=${r.index} msg=${EventLog.safe(t.message ?: "")}")
                                                Toast.makeText(c, "拼接没走完，小块都留着，可以再试一次",
                                                    Toast.LENGTH_LONG).show()
                                            } finally {
                                                mergingChapter = null
                                                localTick++
                                            }
                                        }
                                    }
                                })
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
                    // R304 · 点一下全选、再点一下反选取消打勾。
                    // 原来永远是全选，勾满了想一次清掉只能逐章点回去。
                    selected = if (ui.isNotEmpty() && selected.size == ui.size)
                        emptySet() else ui.indices.toSet()
                    EventLog.ui("MULTI_ALL", "n=${selected.size}")
                },
                onCancel = { exitMulti() },
                allLabel = if (ui.isNotEmpty() && selected.size == ui.size)
                    "取消全选" else "全选章节",
                showCount = false,
                // R291 · 制作固定在动作行最左边，不跟着次要动作横滑。
                // R304 · 这张菜单重新设计：不带「已选多少章」，也不再重复摆
                // 调音台 / 制作设置（右上角已有），只管选中的这几章本身。
                primaryLabel = if (selected.isEmpty()) null else "制作选中章节",
                onPrimary = { startAction(HubAction.SYNTH) },
                primaryIcon = UiIcon.SYNTH,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                if (selected.isNotEmpty()) {
                    // R315 · 图 12：主按钮之外只留两枚（导出 / 删除），拼章、
                    // 转码、另存收进「导出与另存」二级菜单，四个能力一条没丢，
                    // 也不再让一排胶囊把「制作选中章节」挤成配角。
                    Box {
                        ActionChip("导出与另存", icon = UiIcon.M4A,
                            onClick = { exportMenu = true })
                        DropdownMenu(expanded = exportMenu,
                            onDismissRequest = { exportMenu = false }) {
                            val i = selected.firstOrNull()
                            val has = i?.let { idx ->
                                ui.getOrNull(idx)?.let { it.audio != null || it.playable }
                            } ?: false
                            if (i != null && has) {
                                DropdownMenuItem(leadingIcon = { UiIconGlyph(UiIcon.MERGE, HubPrimary, glyphSize = 18.dp) },
                                    text = { Text("拼成整章", fontSize = 13.sp) },
                                    onClick = {
                                        exportMenu = false
                                        // R334 · 和收尾条同一个闸：待重做的坏块没消化、
                                        // 正在制作 / 正在拼、刚点过，都不许再固化；
                                        // 拼接 + 转码是重活，一律进 IO，别卡住界面线程。
                                        val pendingHere = hubByChapter[i].orEmpty()
                                            .count { "seg:${it.index}" in redoKeys }
                                        val busyHere = (st.running && i == liveChapter)
                                            || mergingChapter == i
                                            || mergeBusy?.takeIf {
                                                it.bookId == book.id && it.chapter == i
                                            } != null
                                        when {
                                            busyHere -> Toast.makeText(c,
                                                "这一章还在制作 / 拼接中，做完再拼",
                                                Toast.LENGTH_SHORT).show()
                                            pendingHere > 0 -> Toast.makeText(c,
                                                "还有 $pendingHere 段待重做，先重做完再拼",
                                                Toast.LENGTH_SHORT).show()
                                            mergingChapter == null -> {
                                                mergingChapter = i
                                                redoScope.launch {
                                                    // R335 · 与收尾条同一条兜底：异常也要解锁并退出多选，
                                                    // 不能让一章的失败把整本拼章入口锁死。
                                                    try {
                                                        val parts = withContext(Dispatchers.IO) {
                                                            filesOf(i).first
                                                        }
                                                        val rr = if (parts.isEmpty()) null
                                                        else withContext(Dispatchers.IO) {
                                                            AudioOps.mergeChapter(c, book.id,
                                                                book.title, i, parts,
                                                                gapMs = Prefs.defaultPerf(c).gapMs,
                                                                declickMs = Prefs.mergeDeclickMs(c))
                                                        }
                                                        Toast.makeText(c,
                                                            if (rr == null) "这一章还没有可拼的音频小块"
                                                            else if (rr.ok) rr.reason.ifBlank { "已拼成整章" }
                                                            else rr.reason, Toast.LENGTH_LONG).show()
                                                    } catch (t: Throwable) {
                                                        EventLog.log(EventLog.Kind.MODEL,
                                                            "MERGE_MENU_FAIL",
                                                            "ch=$i msg=${EventLog.safe(t.message ?: "")}")
                                                        Toast.makeText(c,
                                                            "拼接没走完，小块都留着，可以再试一次",
                                                            Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        mergingChapter = null
                                                        exitMulti(); localTick++
                                                    }
                                                }
                                            }
                                        }
                                    })
                                // R308 · 成品形态按真实文件来：M4A 只给「另存 M4A」，
                                // 转码失败回落的 WAV 仍保留「另存 WAV / 转 M4A」手动兜底。
                                val isM4a = ui.firstOrNull { it.index == i }?.audio?.file
                                    ?.name?.endsWith(".m4a", ignoreCase = true) == true
                                if (isM4a) {
                                    DropdownMenuItem(leadingIcon = { UiIconGlyph(UiIcon.M4A, HubPrimary, glyphSize = 18.dp) },
                                    text = { Text("另存 M4A", fontSize = 13.sp) },
                                        onClick = {
                                            exportMenu = false
                                            val src = srcOf(i)
                                            if (src != null) {
                                                pendingSrc = src; saveM4a.launch("chapter.m4a")
                                            } else Toast.makeText(c, "这一章还没有可导出的音频",
                                                Toast.LENGTH_SHORT).show()
                                            exitMulti()
                                        })
                                } else {
                                    DropdownMenuItem(leadingIcon = { UiIconGlyph(UiIcon.WAV, HubPrimary, glyphSize = 18.dp) },
                                    text = { Text("另存 WAV", fontSize = 13.sp) },
                                        onClick = {
                                            exportMenu = false
                                            val src = srcOf(i)
                                            if (src != null) { pendingSrc = src; saveWav.launch("chapter.wav") }
                                            else Toast.makeText(c, "这一章还没有可导出的音频",
                                                Toast.LENGTH_SHORT).show()
                                            exitMulti()
                                        })
                                    DropdownMenuItem(leadingIcon = { UiIconGlyph(UiIcon.M4A, HubPrimary, glyphSize = 18.dp) },
                                    text = { Text("转 M4A", fontSize = 13.sp) },
                                        onClick = {
                                            exportMenu = false
                                            val src = srcOf(i)
                                            if (src != null) { pendingSrc = src; saveM4a.launch("chapter.m4a") }
                                            else Toast.makeText(c, "这一章还没有可转的音频",
                                                Toast.LENGTH_SHORT).show()
                                            exitMulti()
                                        })
                                }
                            } else {
                                DropdownMenuItem(enabled = false,
                                    text = { Text("选一章已做好的才能导出", fontSize = 12.sp,
                                        color = HubSub) }, onClick = { })
                            }
                        }
                    }
                    ActionChip("删除选中音频", danger = true, icon = UiIcon.DELETE, onClick = {
                        confirmChDel = selected.associateWith { filesOf(it) }
                    })
                }
            }
        }

        // R315 · 目录页就地任务浮窗整段撤掉（R298 口径到这轮才真正落干净）：
        // 队列的查看 / 移除统一从主页右上角进全局任务页，目录页不再弹、
        // 不再摆第二枚任务按钮；顶部只留一行不可点的排队状态。

        // R298 · 目录页就地任务看板撤掉：任务统一从主页右上角那枚进全局任务页，
        // 不再在书里弹浮层、也不再挂第二枚任务按钮。

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
                            // R315 · 图 18 识别阶段只一行字 + 一大片空白，
                            // 看着像卡住；补转圈与「在按什么规则找」。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp),
                                    strokeWidth = 2.dp, color = HubPrimary)
                                Spacer(Modifier.width(8.dp))
                                Text("正在识别这本书的章节…", fontSize = 12.sp, color = HubSub)
                            }
                        } else {
                            // R315 · 先把「现在几章 → 识别几章」摆出来，
                            // 338 章对 3 章这种落差不该等点了应用才发现。
                            Text("当前 ${chapters.size} 章 → 识别 ${pv.second.first} 章",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = palette.text)
                            Text("自动识别：${ChapterPlan.ruleName(pv.first)}",
                                fontSize = 12.sp, color = HubSub)
                            Spacer(Modifier.height(6.dp))
                            pv.second.second.forEach {
                                Text("· $it", fontSize = 12.sp, color = HubSub, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // R315 · 后果说明收进警示底色块里，不再像图 18 那样
                        // 掉在蒙层外面几乎看不见；原文一句没删。
                        Box(Modifier.fillMaxWidth()
                            .background(HubWarn.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(8.dp)) {
                            // R172 · 说清真实后果：旧任务标待确认、分段作废、成品仍可听。
                            Text("章号会变，旧任务需重新确认；分段音频作废，成品仍可听；原书文字不动。",
                                fontSize = 12.sp, color = HubWarn)
                        }
                        Spacer(Modifier.height(4.dp))
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
 * 右边只留小眼睛（看原文）/ 小耳机（听成品）。
 * R264 · 制作中的那一章自动展开小段区，章名不再管展开/收起，只保留长按菜单。
 * 章名下一根细进度条直接表达合成进度。
 */
@Composable
private fun ChapterRow(
    r: ChapterUi, multi: Boolean, checked: Boolean,
    onLong: () -> Unit, onToggle: (Int) -> Unit,
    onRead: () -> Unit, onListen: () -> Unit,
    menuExpanded: Boolean, onDismissMenu: () -> Unit, onMenuSelect: () -> Unit,
    onMenuRedo: () -> Unit, canRedo: Boolean,
    busyLabel: String? = null,
    peekHint: String? = null, onPeek: () -> Unit = {}
) {
    val p = r.synthesized?.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp)
                .pointerInput(r.index, multi, checked) {
                    // R264 · 只留长按菜单，点章名不再切换展开/收起
                    detectTapGestures(onLongPress = { onLong() })
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
            // R333 · 有待固化小块的章给一枚回看入口，点开就摊开那一章的小块，
            // 收尾条就在格子下面；不再让按钮孤零零挂在章名下一行。
            if (peekHint != null) {
                Box(Modifier.height(26.dp)
                    .background(HubSoft, RoundedCornerShape(50))
                    .clickable { onPeek() }
                    .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(peekHint, fontSize = 10.sp, maxLines = 1, color = HubPrimary)
                }
                Spacer(Modifier.width(4.dp))
            }
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
    // R308 · 拼章 / 转码期间在章名上盖一层，明说在干什么。
    // 整章 WAV 大、转码要几十秒，没有这层用户只看到进度条不动。
    if (busyLabel != null) {
        // R315 · 图 14：整块深底把章名盖没了，用户不知道是哪一章在流转。
        // 蒙层降到半透，第一行把章名亮出来，第二行才是阶段 + 转圈；
        // 同一时刻只有这一章在拼 / 转，不会两章各显示一个阶段。
        Box(Modifier.matchParentSize()
            .background(HubCard.copy(alpha = 0.78f))
            .clickable(enabled = false) { },
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${r.index + 1}. ${r.title}", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp,
                        color = HubPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(busyLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = HubPrimary)
                }
            }
        }
    }
    // R315 · 长按就地菜单先亮出是哪一章、现在什么状态，再按状态给动作。
    DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
        val status = when {
            r.audio != null || r.playable -> "已完成 · 章音频"
            (r.synthesized ?: 0f) > 0f -> "制作中"
            else -> "未开始"
        }
        DropdownMenuItem(enabled = false, onClick = { },
            text = { Column {
                Text("${r.index + 1}. ${r.title}", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 1)
                Text(status, fontSize = 11.sp, color = HubSub)
            } })
        if (r.audio != null || r.playable) {
            DropdownMenuItem(text = { Text("试听本章", fontSize = 13.sp) },
                onClick = { onDismissMenu(); onListen() })
        }
        DropdownMenuItem(
            text = { Text(if (status == "未开始") "从此章开始制作" else "勾选此章",
                fontSize = 13.sp) },
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

/** R333 · 小块区末尾的收尾条：跟它要处理的格子同一个视觉块。
 *  不再整行实心大蓝条；左边说清这一章现在能不能拼、还差什么，
 *  右边只留一枚小圆角按钮。忙碌时按钮位转圈，阶段文字直接亮出来。 */
@Composable
private fun MergeChapterFooter(label: String, status: String,
                               enabled: Boolean, busy: Boolean,
                               onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(HubCard)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(status, fontSize = 12.sp, maxLines = 2,
                color = if (busy) HubPrimary
                        else if (enabled) palette.text else HubWarn)
        }
        Spacer(Modifier.width(10.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                color = HubPrimary)
        } else {
            Box(Modifier.height(34.dp)
                .background(if (enabled) HubPrimary else HubGrey.copy(alpha = 0.35f),
                    RoundedCornerShape(50))
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.White else HubSub)
            }
        }
    }
}

/** R315 · 制作章的小片头部：常驻进度 + 图例，多选时再叠一行操作。 */
@Composable
private fun SegGridHead(
    segs: List<SegRow>, multi: Boolean, picked: Int,
    onAll: () -> Unit, onTrial: () -> Unit, onRedo: () -> Unit, onDelete: () -> Unit
) {
    val done = segs.count { it.hasAudio }
    val redoing = segs.count { it.pendingRedo }
    Column(Modifier.fillMaxWidth().background(HubSoft)
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        // 图 15：一片色块没有任何说明，用户不知道哪种颜色是做完了、
        // 哪种在重做、哪种被删了。这里给一行真实进度 + 一行极简图例。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("已生成 $done / ${segs.size} 段", fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.weight(1f))
            Text(if (redoing > 0) "重做中 $redoing 段" else "正在往下做",
                fontSize = 11.sp, color = HubSub)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("已生成" to HubPrimary, "合成中" to HubPrimary,
                   "重做中" to HubWarn, "待生成" to HubLine,
                   "已删" to HubCard).forEach { (label, tint) ->
                Box(Modifier.size(9.dp).background(tint, RoundedCornerShape(2.dp)))
                Text(label, fontSize = 10.sp, color = HubSub,
                    modifier = Modifier.padding(start = 3.dp, end = 10.dp))
            }
        }
        // 图 16：四个动作全是同样大小的文字按钮，主次不分。重做做实心主按钮，
        // 删除警示色，其余仍是可点文字；计数跟着真实勾选数走，不再对不上。
        if (multi) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 $picked", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = HubPrimary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAll, modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("全选已生成", fontSize = 11.sp, color = HubPrimary)
                }
                TextButton(onClick = onTrial, modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("试听选中", fontSize = 11.sp, color = HubPrimary)
                }
                ActionChip("重做选中", onClick = onRedo, primary = true,
                    modifier = Modifier.height(36.dp))
                TextButton(onClick = onDelete, modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                    Text("删除选中", fontSize = 11.sp, color = HubWarn)
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
    onLong: (Int) -> Unit, onToggle: (Int) -> Unit, onTap: (SegRow) -> Unit,
    busyIndex: Int? = null
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
                        } else if (sg.pendingRedo || sg.index == busyIndex) {
                            // R308 · 重做中 / 正在合成这一格：就地转圈，用户一眼看得出
                            // 进度停在哪，不必对着一片灰格子猜。
                            CircularProgressIndicator(
                                modifier = Modifier.size((cell.value * 0.5f).dp),
                                strokeWidth = 2.dp,
                                color = if (sg.pendingRedo) HubWarn else HubPrimary)
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
// 章行选中一律长按，右侧只留看原文 / 听成品两枚图标；这行留作格子行的分段标记。

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
    // R315 · 图 19 把阅读正文和章卡画叠在一起了：代码里 ReaderPane 本来
    // 就是独立全屏 Pane，这层 fillMaxSize + 实底就是不许底下列表透上来。
    // 顺手补章进度与当前字号，翻页按钮给底色，正文区仍填满。
    Column(Modifier.fillMaxSize().background(HubCard)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ 目录") }
            Column(Modifier.weight(1f)) {
                Text(ch.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1)
                Text("第 ${i + 1} / ${chapters.size} 章", fontSize = 11.sp, color = HubSub)
            }
            Text("${size.toInt()}", fontSize = 11.sp, color = HubSub)
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
        Row(Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(44.dp)
                .background(if (i > 0) HubSoft else HubLine, RoundedCornerShape(12.dp))
                .clickable(enabled = i > 0) { onJump(i - 1) },
                contentAlignment = Alignment.Center) {
                Text("‹ 上一章", fontSize = 13.sp,
                    color = if (i > 0) HubPrimary else HubSub)
            }
            Box(Modifier.weight(1f).height(44.dp)
                .background(if (i < chapters.lastIndex) HubPrimary else HubLine,
                    RoundedCornerShape(12.dp))
                .clickable(enabled = i < chapters.lastIndex) { onJump(i + 1) },
                contentAlignment = Alignment.Center) {
                Text("下一章 ›", fontSize = 13.sp,
                    color = if (i < chapters.lastIndex) palette.onPrimary else HubSub)
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun MiniPlayBar(
    c: Context, bookId: String?, onGoBook: (String) -> Unit, onExpand: () -> Unit,
    onPullUp: (Float) -> Unit = {},
    onPullEnd: () -> Unit = {}
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

    // R262 · 与全屏播放器同款视觉：小封面 + 状态时间 + 倍速胶囊 + 三枚图标，
    // 进度条落到卡片底边而不是压在顶上；整条仍可点开全屏页。
    val timeText = "%02d:%02d / %02d:%02d".format(
        st.positionMs / 60000, st.positionMs / 1000 % 60,
        st.durationMs / 60000, st.durationMs / 1000 % 60)
    // R319 · 副标题给真实播放状态，不再写「上滑展开 · 下滑收起」这类操作指引。
    val statusText = (if (st.isPlaying) "正在播放" else "已暂停") + " · " + timeText
    // R332 · 点击与拖拽分到不同节点：轻扒不再被 clickable 吞成「点了直接跳」，
    // 上扒也只在把手那一带给反馈，不再出现「扒的位置和小横条打架」。
    var dragging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        .background(HubCard, RoundedCornerShape(20.dp))
        // R272 / R332 · 上扒只挂这条小栏本身，不铺到外层页面免得抢列表滚动。
        // 净位移双向累计：往上扒加、往回带减，跟手不再只增不减。
        .pointerInput(Unit) {
            var acc = 0f
            detectVerticalDragGestures(
                onDragStart = { acc = 0f; dragging = true },
                onDragEnd = { dragging = false; onPullEnd(); acc = 0f },
                onDragCancel = { dragging = false; onPullEnd(); acc = 0f },
                onVerticalDrag = { _, dy ->
                    acc = (acc - dy).coerceAtLeast(0f)
                    onPullUp(acc)
                }
            )
        }
        .padding(horizontal = 12.dp, vertical = 10.dp)) {
        // R319 / R332 · 顶部把手不再是纯装饰：按住这一带往上扒就是展开，
        // 拖起来变色变长，和全屏页顶部那条收起把手上下呼应。
        Box(Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.Center) {
            Box(Modifier.width(if (dragging) 48.dp else 36.dp).height(4.dp)
                .background(if (dragging) HubPrimary else HubLine, RoundedCornerShape(50)))
        }
        Row(Modifier.fillMaxWidth().clickable { onExpand() },
            verticalAlignment = Alignment.CenterVertically) {
            PlayerCover(HubPrimary, 44.dp, st.isPlaying)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(st.label.ifBlank { "未在播放" }, fontSize = 13.sp, maxLines = 1,
                    fontWeight = FontWeight.Bold, color = palette.text)
                Spacer(Modifier.height(3.dp))
                Text(statusText, fontSize = 11.sp, color = HubSub)
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
        // R332 · 点卡片主体（内容行 / 进度条）仍展开；上扒由整条的 pointerInput 统一处理。
        Box(Modifier.fillMaxWidth().height(3.dp)
            .background(HubLine, RoundedCornerShape(50))
            .clickable { onExpand() }) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight()
                .background(HubPrimary, RoundedCornerShape(50)))
        }
    }
}
