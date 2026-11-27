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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.Job
import java.io.File

// @ui-part: TaskUi

/**
 * R308 · 任务页的一条 = 一份「合成出来的音频」，不是「一整本书」。
 * 章节音频来自 audio/ 的章成品，首页片段来自 clips/；clipId 非空表示片段，
 * 删除走各自的登记层，绝不互相牵连。
 */
internal data class AudioRow(
    val key: String,
    val name: String,
    val source: String,
    val file: File,
    val createdAt: Long,
    val bytes: Long,
    val clipId: String? = null
)

/**
 * R287 · 任务页改成「合成中 / 队列中 / 已完成」三状态，参照音乐下载管理页的信息架构。
 *
 * 自己拍的取舍：
 *   · 竖屏不做真三列，做成三个可切分段，每段带数量，一眼看得出哪段有东西；
 *   · 不做拖动排序——队列是书级，顺序就是入队顺序，拖乱了和续跑口径对不上；
 *   · 不做「速度」——TTS 单段耗时不稳，给个假速率不如给真实段数与阶段；
 *   · 已停止 / 已中断 / 需重新选章 / 没开始 这四类不是「已完成」，归进「队列中」段
 *     标成「待续」，能力一条不丢（继续制作、移除、打开书都还在）；
 *   · 磁盘读取全部进 IO，组合期不碰盘（原来每 2 秒主线程 TaskResume.list 会卡整页）。
 */
@Composable
internal fun TaskCenterScreen(c: Context, refresh: Int, onOpenTask: (String) -> Unit,
                             onProgress: (String) -> Unit,
                             onLibrary: () -> Unit,
                             onResume: (TaskInfo) -> Unit) {
    val scope = rememberCoroutineScope()
    val st by Producer.state.collectAsState()
    val live by Producer.resume.collectAsState()
    var confirmDelete by remember { mutableStateOf<TaskInfo?>(null) }
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) { loadTick++; return@LaunchedEffect }
        while (true) { delay(2000); loadTick++ }
    }
    // R287 · 任务/队列/书架/真实进度/失效原因/暂停态一次性在 IO 读齐。
    var tasks by remember { mutableStateOf(emptyList<TaskInfo>()) }
    var queued by remember { mutableStateOf(emptyList<TaskInfo>()) }
    var realMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var warnMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var books by remember { mutableStateOf<Set<String>>(emptySet()) }
    var qPaused by remember { mutableStateOf(false) }
    // R308 · 「已完成」按音频成品列：一章音频一条、首页粘贴合成的一段一条。
    var audioRows by remember { mutableStateOf(emptyList<AudioRow>()) }
    var confirmAudio by remember { mutableStateOf<AudioRow?>(null) }
    LaunchedEffect(refresh, loadTick) {
        withContext(Dispatchers.IO) {
            val ts = TaskResume.list(c)
            val qs = TaskResume.queue(c)
            val bs = BookStore.list(c).map { it.id }.toSet()
            val rm = ts.associate { it.bookId to
                if (st.running) 0 else TaskResume.realProgress(c, it) }
            val wm = ts.associate { it.bookId to TaskResume.invalidationReason(c, it) }
            val qp = Producer.queueState(c).second
            val chapterRows = AudioLibrary.list(c).filter {
                it.chaptersKnown && !AudioLibrary.isWholeBook(it) &&
                    it.file.isFile && it.file.length() > 44L
            }.map { a ->
                AudioRow("ch:" + a.file.absolutePath,
                    "${a.scope} · ${a.title}", "章节音频",
                    a.file, a.createdAt, a.bytes)
            }
            val clipRows = ClipLibrary.list(c).filter { it.isDone }.map { t ->
                AudioRow("clip:" + t.id, t.name, "首页片段",
                    t.audio, t.createdAt, t.audio.length(), t.id)
            }
            val rows = (chapterRows + clipRows).sortedByDescending { it.createdAt }
            withContext(Dispatchers.Main) {
                tasks = ts
                queued = qs
                books = bs
                realMap = rm
                warnMap = wm
                qPaused = qp
                audioRows = rows
            }
        }
    }
    val runningTask = remember(tasks, live, st.running) {
        if (!st.running) null
        else live?.let { l -> tasks.find { it.bookId == l.bookId } ?: l }
    }
    val runningId = runningTask?.bookId
    // 排队项 + 待续任务，去重后同段展示；正在跑的那本不重复出现。
    val pending = remember(tasks, queued, runningId) {
        val fromQueue = queued.filter { it.bookId != runningId }
        val fromTasks = tasks.filter { t ->
            t.phase != "done" && t.bookId != runningId &&
                fromQueue.none { it.bookId == t.bookId }
        }
        fromQueue + fromTasks
    }
    val isQueued: (String) -> Boolean = { id -> queued.any { it.bookId == id } }

    // R341 · 总览页与「已完成」页共用同一条播放路径，不再各写一遍队列组装。
    fun playAudio(a: AudioRow) {
        EventLog.ui("TASK_PLAY_AUDIO", "kind=${a.source}")
        PlayQueue.setTracks(
            listOf(Track(a.file, a.name, id = "task:" + a.file.absolutePath)),
            "task")
        PlayQueue.playFrom(0, id = "task")
    }

    // R352 · 任务页重新设计：不再把「合成中 / 队列中 / 已完成」切成互斥的四页、
    // 每页只摆几张卡加一堆跳转小字。改成「顶部常驻现在卡 + 一屏分区 + 批量管理」：
    //   · 进行中的那一份永远在最上面，停止 / 去这一章两枚等大主按钮；
    //   · 待续与做好的音频同屏分区，各自能全选、勾选、批量播放 / 导出 / 删除；
    //   · 筛选只收窄分区，不把管理动作收没；筛选态下批量条照样在。
    var filter by rememberSaveable { mutableStateOf(0) } // 0 全部 / 1 待续 / 2 已完成
    var checkedAudio by remember { mutableStateOf(setOf<String>()) }
    var checkedPending by remember { mutableStateOf(setOf<String>()) }
    var confirmBatch by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val showPending = filter != 2
    val showDone = filter != 1
    val checkedCount = checkedAudio.size + checkedPending.size
    val pickedAudios = audioRows.filter { it.key in checkedAudio }
    val pickedPending = pending.filter { it.bookId in checkedPending }

    fun playPicked(rows: List<AudioRow>) {
        if (rows.isEmpty()) {
            Toast.makeText(c, "先勾一份做好的音频，待续任务还不能播",
                Toast.LENGTH_SHORT).show()
            return
        }
        EventLog.ui("TASK_PLAY_BATCH", "n=${rows.size}")
        PlayQueue.setTracks(rows.map { a ->
            Track(a.file, a.name, id = "task:" + a.file.absolutePath)
        }, "task")
        PlayQueue.playFrom(0, id = "task")
    }
    fun exportPicked(rows: List<AudioRow>) {
        if (rows.isEmpty()) {
            Toast.makeText(c, "先勾一份做好的音频再导出",
                Toast.LENGTH_SHORT).show()
            return
        }
        if (exporting) return
        val tree = AudioExport.treeUri(c)
        if (tree == null) {
            Toast.makeText(c, "先到设置里挑一个导出文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            exporting = true
            val res = withContext(Dispatchers.IO) {
                AudioExport.exportPicked(c, tree, rows.map { a ->
                    AudioExport.Item(AudioLibrary.safeName(a.source), a.file.name, a.file)
                })
            }
            exporting = false
            Toast.makeText(c, "已导出 ${res.ok} 份到「${AudioExport.treeLabel(c)}」" +
                if (res.failed > 0) "，${res.failed} 份没导出去" else "",
                Toast.LENGTH_SHORT).show()
        }
    }
    fun deletePicked() {
        scope.launch(Dispatchers.IO) {
            pickedAudios.forEach { a ->
                if (a.clipId != null) ClipLibrary.remove(c, setOf(a.clipId!!))
                else AudioLibrary.removeFile(c, a.file)
            }
            pickedPending.forEach { t ->
                TaskResume.remove(c, t.bookId)
                TaskResume.dequeue(c, t.bookId)
            }
            withContext(Dispatchers.Main) {
                checkedAudio = emptySet()
                checkedPending = emptySet()
                confirmBatch = false
                loadTick++
            }
        }
    }
    fun selectAll() {
        if (showDone) checkedAudio = audioRows.map { it.key }.toSet()
        if (showPending) checkedPending = pending.map { it.bookId }.toSet()
    }
    fun invertAll() {
        if (showDone) checkedAudio = audioRows.map { it.key }.toSet() - checkedAudio
        if (showPending) checkedPending = pending.map { it.bookId }.toSet() - checkedPending
    }

    Column(Modifier.fillMaxSize()) {
        // ① 常驻「现在」卡：进行中 / 队列暂停 / 空闲，三态都有出口
        if (runningTask != null) {
            val t = runningTask!!
            NowCard(
                title = "《${t.title}》· ${unitText(t)}",
                pct = st.done.coerceIn(0, maxOf(t.total, st.total, 1)) * 100 /
                    maxOf(t.total, st.total, 1),
                stage = if (st.stage.isBlank()) "正在合成" else st.stage,
                onProgress = { onProgress(t.bookId) },
                onOpen = { onOpenTask(t.bookId) },
                onStop = {
                    EventLog.ui("TASK_STOP", "id=${t.bookId.take(8)}")
                    Producer.stop()
                    Toast.makeText(c, "已停止，队列也暂停；去「待续」接着做",
                        Toast.LENGTH_LONG).show()
                    loadTick++
                })
        } else if (qPaused) {
            NowCard(
                title = "队列已暂停 · ${pending.size} 条待续",
                pct = 0, stage = "点「接着做」恢复，或先去挑一本",
                onProgress = { filter = 1 },
                onOpen = onLibrary,
                primary = "接着做 ›",
                tag = "现在",
                progressLabel = "看待续 ›",
                secondLabel = "去挑一本 ›",
                showProgress = false,
                onStop = {
                    EventLog.ui("TASK_QUEUE_RESUME", "from=nowcard")
                    // R412 · 低电时 resumeQueue 返回拒绝原因，要让用户看见，不能静默。
                    Producer.resumeQueue(c)?.let { why ->
                        android.widget.Toast.makeText(c, why,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    loadTick++
                })
        } else {
            IdleCard(pending.size,
                onLibrary = onLibrary, onPending = { filter = 1 })
        }
        // ② 筛选：只收窄分区，不把内容切成互不相见的四页
        FilterRow(filter) { filter = it }
        // ③ 一屏分区，每区自带全选与逐条管理
        LazyColumn(Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp,
                bottom = if (checkedCount > 0) 210.dp else 116.dp)) {
            if (showPending) {
                item(key = "sec_pending") {
                    SectionRow("待续 · ${pending.size}", "全选待续") {
                        checkedPending = pending.map { it.bookId }.toSet()
                    }
                }
                if (pending.isEmpty()) {
                    item(key = "pending_empty") {
                        EmptyHint("没有待续的音频") {
                            TextButton(onClick = onLibrary) {
                                Text("去挑一本 ›", fontSize = 13.sp, color = Primary)
                            }
                        }
                    }
                }
                items(pending, key = { it.bookId }) { t ->
                    val inQ = isQueued(t.bookId)
                    val badge = when {
                        inQ -> "排队中"
                        t.phase == "reconfirm" -> "需重新确认"
                        t.phase == "blocked" -> "没开始"
                        t.phase == "stopped" -> "已停止"
                        else -> "已中断"
                    }
                    PendingCard(
                        t = t, inQ = inQ, badge = badge,
                        real = realMap[t.bookId] ?: 0,
                        total = maxOf(t.total, realMap[t.bookId] ?: 0, 1),
                        warn = warnMap[t.bookId],
                        bookGone = t.bookId !in books,
                        busy = Producer.isBusy(), stopping = Producer.isStopping(),
                        checked = t.bookId in checkedPending,
                        onCheck = {
                            checkedPending = if (t.bookId in checkedPending)
                                checkedPending - t.bookId else checkedPending + t.bookId
                        },
                        onResume = {
                            EventLog.ui("TASK_RESUME", "id=${t.bookId.take(8)}")
                            onResume(t)
                        },
                        onReconfirm = {
                            EventLog.ui("TASK_RECONFIRM", "id=${t.bookId.take(8)}")
                            onOpenTask(t.bookId)
                        },
                        onOpen = { onOpenTask(t.bookId) },
                        onRemove = {
                            if (inQ) {
                                EventLog.ui("TASK_DEQUEUE", "id=${t.bookId.take(8)}")
                                scope.launch(Dispatchers.IO) {
                                    TaskResume.dequeue(c, t.bookId)
                                    withContext(Dispatchers.Main) { loadTick++ }
                                }
                            } else confirmDelete = t
                        })
                }
            }
            if (showDone) {
                item(key = "sec_audio") {
                    SectionRow("做好的音频 · ${audioRows.size}", "全选音频") {
                        checkedAudio = audioRows.map { it.key }.toSet()
                    }
                }
                if (audioRows.isEmpty()) {
                    item(key = "done_empty") {
                        EmptyHint("还没有做好的音频") {
                            TextButton(onClick = onLibrary) {
                                Text("去挑一本 ›", fontSize = 13.sp, color = Primary)
                            }
                        }
                    }
                }
                items(audioRows, key = { it.key }) { a ->
                    AudioCard(a,
                        play = { playAudio(a) },
                        onDelete = { confirmAudio = a },
                        checked = a.key in checkedAudio,
                        onCheck = {
                            checkedAudio = if (a.key in checkedAudio)
                                checkedAudio - a.key else checkedAudio + a.key
                        },
                        onExport = { exportPicked(listOf(a)) })
                }
            }
            if (showPending && pending.isEmpty() && showDone && audioRows.isEmpty()) {
                item(key = "queue_link") {
                    TextButton(onClick = { filter = 1 }) {
                        Text("看队列 ›", fontSize = 13.sp, color = Primary)
                    }
                }
            }
        }
        // ④ 批量条：落在全局播放条上方，不被盖住；筛选态照样在
        if (checkedCount > 0) {
            BatchBar(checkedCount, pickedAudios.size, exporting,
                onAll = { selectAll() }, onInvert = { invertAll() },
                onPlay = { playPicked(pickedAudios) },
                onExport = { exportPicked(pickedAudios) },
                onDelete = { confirmBatch = true })
        }
        if (confirmBatch) {
            AlertDialog(
                onDismissRequest = { confirmBatch = false },
                title = { Text("删掉选中的 ${checkedCount} 项？", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = { Text("选中的 $checkedCount 项会删掉，别的音频不动。",
                    fontSize = 13.sp, color = Sub) },
                confirmButton = {
                    TextButton(onClick = { deletePicked() }) {
                        Text("删除", fontSize = 13.sp, color = Warn)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBatch = false }) {
                        Text("取消", fontSize = 13.sp)
                    }
                })
        }
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删掉这条任务？", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text("只删《${t.title}》这条任务记录，已做好的音频不会删；" +
                    "书还在，随时可以重新选章制作。",
                    fontSize = 13.sp, color = Sub)
            },
            confirmButton = {
                TextButton(onClick = {
                    EventLog.ui("TASK_DELETE", "id=${t.bookId.take(8)}")
                    scope.launch(Dispatchers.IO) {
                        TaskResume.remove(c, t.bookId)
                        TaskResume.dequeue(c, t.bookId)
                        withContext(Dispatchers.Main) {
                            loadTick++
                            confirmDelete = null
                        }
                    }
                }) { Text("删除", fontSize = 13.sp, color = Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("取消", fontSize = 13.sp)
                }
            }
        )
    }
    // R308 · 删的是「这一份音频」，不是整本书的任务记录。
    confirmAudio?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmAudio = null },
            title = { Text("删掉这条音频？", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text("只删「${a.name}」这一份成品，书、别的章音频和首页其他片段都不动。",
                    fontSize = 13.sp, color = Sub)
            },
            confirmButton = {
                TextButton(onClick = {
                    EventLog.ui("TASK_AUDIO_DELETE", "kind=${a.source}")
                    scope.launch(Dispatchers.IO) {
                        if (a.clipId != null) ClipLibrary.remove(c, setOf(a.clipId!!))
                        else AudioLibrary.removeFile(c, a.file)
                        withContext(Dispatchers.Main) {
                            loadTick++
                            confirmAudio = null
                        }
                    }
                }) { Text("删除", fontSize = 13.sp, color = Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAudio = null }) {
                    Text("取消", fontSize = 13.sp)
                }
            }
        )
    }
}

internal fun modeText(id: String): String = when (id) {
    "custom_voice" -> "预设音色"
    "base" -> "参考音频"
    else -> "描述造音色"
}

internal fun scopeText(t: TaskInfo): String =
    if (t.only == null) "整本" else "选中 ${t.only!!.size} 章"

/** R316 · 任务卡主体写「一份音频」，书名退到副行，不再拿整本书当条目。 */
internal fun unitText(t: TaskInfo): String =
    if (t.only == null) "整本章音频" else "选中 ${t.only!!.size} 章音频"

/**
 * R341 · 一份音频 = 一张卡。整行可点播放（以前得对准 12sp 的「播放 ›」小字），
 * 主色「播放」给明确落点，删除收进右上角「⋯」；来源 / 体积 / 时间退成一行。
 * 保留 UiIcon.LISTEN 与「删除音频」文案（r224 与语义判据锚）。
 */
@Composable
internal fun AudioCard(
    a: AudioRow,
    play: () -> Unit,
    onDelete: () -> Unit,
    checked: Boolean = false,
    onCheck: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null
) {
    var menu by remember { mutableStateOf(false) }
    val sizeText = java.lang.String.format(java.util.Locale.US,
        "%.1f MB", a.bytes / 1048576.0)
    val timeText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date(a.createdAt))
    CardBox(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { play() }
            .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp)
                .background(PrimarySoft, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) {
                UiIconGlyph(UiIcon.LISTEN, Primary, 18.dp)
            }
            if (onCheck != null) {
                CheckGlyph(checked, onCheck)
                Spacer(Modifier.width(9.dp))
            }
            Spacer(Modifier.width(if (onCheck != null) 2.dp else 11.dp))
            Column(Modifier.weight(1f)) {
                // R352 · 和设置页「本机音频」同一口径：名字固定两行，长文件名不掩盖。
                Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("${a.source} · $sizeText · $timeText",
                    fontSize = 11.sp, color = Sub, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier
                .background(Primary, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center) {
                Text("播放 ›", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = palette.onPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { menu = true },
                contentAlignment = Alignment.Center) {
                Text("⋯", fontSize = 16.sp, color = Sub)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onExport != null) {
                        DropdownMenuItem(
                            text = { Text("导出这一份", fontSize = 13.sp, color = Primary) },
                            onClick = { menu = false; onExport() })
                    }
                    DropdownMenuItem(
                        text = { Text("删除音频", fontSize = 13.sp, color = Warn) },
                        onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** R287 · 分组小标题：主色小字 + 一根细竖条，不摆彩色徽标框。 */
@Composable
internal fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // R370 · 分区锚条与字号跟设置分组同款，任务页、目录页不再各写一套。
        Box(Modifier.width(3.dp).height(14.dp)
            .background(Primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = HiCaptionSp, fontWeight = FontWeight.Bold, color = Primary)
    }
}

@Composable
internal fun EmptyHint(text: String, action: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // R316 · 空态也给两条槽位轮廓，和主页/书架一致；不再只摆一块大白卡。
        repeat(2) {
            Box(Modifier.fillMaxWidth().height(72.dp)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, Line, RoundedCornerShape(18.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text(text, fontSize = 13.sp, color = Sub)
        action()
    }
}

/**
 * R352 · 几何自绘勾选框：不拿放大汉字当图标，深浅主题都随色板取色。
 */
@Composable
internal fun CheckGlyph(checked: Boolean, onToggle: () -> Unit) {
    Canvas(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
        .background(if (checked) Primary else Card)
        .border(1.dp, if (checked) Primary else Line, RoundedCornerShape(8.dp))
        .clickable { onToggle() }) {
        val w = size.width; val h = size.height
        if (checked) {
            drawLine(palette.onPrimary, Offset(w * 0.22f, h * 0.52f),
                Offset(w * 0.44f, h * 0.72f), strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round)
            drawLine(palette.onPrimary, Offset(w * 0.44f, h * 0.72f),
                Offset(w * 0.80f, h * 0.28f), strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round)
        }
    }
}

/** R352 · 顶部常驻「现在」卡：两枚等大主按钮 + 看进度出口。 */
@Composable
internal fun NowCard(title: String, pct: Int, stage: String,
                    onProgress: () -> Unit, onOpen: () -> Unit,
                    onStop: () -> Unit, primary: String = "停止制作",
                    tag: String = "正在做",
                    progressLabel: String = "看进度 ›",
                    secondLabel: String = "去这一章 ›",
                    showProgress: Boolean = true) {
    CardBox(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(tag, fontSize = 11.sp, color = Sub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (showProgress)
                Text("$pct%", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primary)
        }
        // 队列暂停态没有进度可看，就不再画一根 0% 的空条占位。
        if (showProgress) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(7.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stage, fontSize = 11.sp, color = Sub, modifier = Modifier.weight(1f))
            TextButton(onClick = onProgress) {
                Text(progressLabel, fontSize = 12.sp, color = Primary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (primary == "停止制作") Warn.copy(alpha = 0.12f) else PrimarySoft)
                .clickable { onStop() }, contentAlignment = Alignment.Center) {
                Text(primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (primary == "停止制作") Warn else Primary)
            }
            Box(Modifier.weight(1f).height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(PrimarySoft)
                .clickable { onOpen() }, contentAlignment = Alignment.Center) {
                Text(secondLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = Primary)
            }
        }
    }
}

/** R352 · 空闲态也给两个等大出口，不留一片白屏。 */
@Composable
internal fun IdleCard(pendingN: Int,
                     onLibrary: () -> Unit, onPending: () -> Unit) {
    CardBox(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("现在没有在做的音频", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("做好的音频和待续任务都在下面", fontSize = 12.sp, color = Sub)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(38.dp)
                .clip(RoundedCornerShape(12.dp)).background(Primary)
                .clickable { onLibrary() }, contentAlignment = Alignment.Center) {
                Text("去挑一本 ›", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = palette.onPrimary)
            }
            Box(Modifier.weight(1f).height(38.dp)
                .clip(RoundedCornerShape(12.dp)).background(PrimarySoft)
                .clickable(enabled = pendingN > 0) { onPending() },
                contentAlignment = Alignment.Center) {
                Text(if (pendingN > 0) "看待续 $pendingN 条 ›" else "没有待续",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary)
            }
        }
    }
}

/** R352 · 三段筛选：只收窄分区，不把内容切没。 */
@Composable
internal fun FilterRow(filter: Int, onPick: (Int) -> Unit) {
    // 计数只放在分区标题里，筛选格不再重复一遍数字；三格等宽同高。
    val labels = listOf("全部", "待续", "已完成")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            val on = filter == i
            Box(Modifier.weight(1f).height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (on) PrimarySoft else Card)
                .clickable { onPick(i) }, contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Primary else Sub)
            }
        }
    }
}

/** R352 · 分区标题：右侧写明全选范围，不再摆一枚含糊的「全选」。 */
@Composable
internal fun SectionRow(title: String, action: String, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAll) {
            Text(action, fontSize = HiCaptionSp, color = Primary)
        }
    }
}

/** R352 · 待续卡：勾选 / 状态 / 进度 / 继续 / 重新选章 / 去这一章 / 移除，能力一条不丢。 */
@Composable
internal fun PendingCard(
    t: TaskInfo, inQ: Boolean, badge: String, real: Int, total: Int,
    warn: String?, bookGone: Boolean, busy: Boolean, stopping: Boolean,
    checked: Boolean, onCheck: () -> Unit, onResume: () -> Unit,
    onReconfirm: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    CardBox(Modifier.padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckGlyph(checked, onCheck)
            Spacer(Modifier.width(10.dp))
            Text(unitText(t), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (inQ) PrimarySoft else Warn.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(badge, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = if (inQ) Primary else Warn)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .clickable { menu = true }, contentAlignment = Alignment.Center) {
                Text("⋯", fontSize = 16.sp, color = Sub)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("去这一章 ›", fontSize = 13.sp, color = Primary) },
                        onClick = { menu = false; onOpen() })
                    DropdownMenuItem(
                        text = { Text(if (inQ) "移除" else "删除任务",
                            fontSize = 13.sp, color = Warn) },
                        onClick = { menu = false; onRemove() })
                }
            }
        }
        Text("《${t.title}》 · ${modeText(t.modeId)} · ${scopeText(t)}",
            fontSize = 12.sp, color = Sub, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (bookGone) Text("书已从抽屉删掉，成品还在", fontSize = 12.sp, color = Warn)
        if (warn != null) Text(warn, fontSize = 12.sp, color = Warn)
        if (!inQ && total > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { real.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp))
            Text("已做 ${(real * 100 / total).coerceIn(0, 100)}% · 接着做不用重来",
                fontSize = 12.sp, color = Sub)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!inQ) {
                if (t.phase == "reconfirm") {
                    TextButton(onClick = onReconfirm) {
                        Text("重新选章 ›", fontSize = 12.sp, color = Primary)
                    }
                } else {
                    TextButton(onClick = onResume,
                        enabled = t.phase != "reconfirm" && !busy) {
                        Text(if (stopping) "正在收尾…" else "继续制作",
                            fontSize = 12.sp, color = Primary)
                    }
                }
            }
            TextButton(onClick = onOpen) {
                Text("去这一章 ›", fontSize = 12.sp, color = Primary)
            }
        }
    }
}

/** R352 · 批量条：落在全局播放条上方，筛选态下照样在。
 *  待续任务不是「一份音频」，条上把项数与可播/可导出的音频份数分开写；
 *  没勾音频时播放 / 导出置灰，点下去也不再静默无反应（调用侧给 Toast）。
 */
@Composable
internal fun BatchBar(n: Int, audioN: Int, exporting: Boolean, onAll: () -> Unit,
                      onInvert: () -> Unit, onPlay: () -> Unit,
                      onExport: () -> Unit, onDelete: () -> Unit) {
    val canAudio = audioN > 0 && !exporting
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF262242))
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("已选 $n 项 · $audioN 份音频", fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = WHITE_SOLID)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAll) { Text("全选", fontSize = 12.sp, color = SOFT_SOLID) }
        TextButton(onClick = onInvert) { Text("反选", fontSize = 12.sp, color = SOFT_SOLID) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
            .background(if (canAudio) Primary else Line).clickable { onPlay() }
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("播放", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (canAudio) palette.onPrimary else WHITE_SOLID)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
            .background(if (canAudio) Primary else Line).clickable { onExport() }
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(if (exporting) "正在导出…" else "导出", fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (canAudio) palette.onPrimary else WHITE_SOLID)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
            .background(Warn).clickable { onDelete() }
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("删除", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = palette.onPrimary)
        }
    }
}

internal val WHITE_SOLID: Color = Color(0xFFFFFFFF)
internal val SOFT_SOLID: Color = Color(0xFFCDC8EE)
