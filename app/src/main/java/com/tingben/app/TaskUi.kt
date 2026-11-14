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
                             onAudio: (String) -> Unit, onLibrary: () -> Unit,
                             onResume: (TaskInfo) -> Unit) {
    val scope = rememberCoroutineScope()
    val st by Producer.state.collectAsState()
    val live by Producer.resume.collectAsState()
    var confirmDelete by remember { mutableStateOf<TaskInfo?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
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
    val done = remember(tasks) { tasks.filter { it.phase == "done" } }
    val isQueued: (String) -> Boolean = { id -> queued.any { it.bookId == id } }

    // R341 · 总览页与「已完成」页共用同一条播放路径，不再各写一遍队列组装。
    fun playAudio(a: AudioRow) {
        EventLog.ui("TASK_PLAY_AUDIO", "kind=${a.source}")
        PlayQueue.setTracks(
            listOf(Track(a.file, a.name, id = "task:" + a.file.absolutePath)),
            "task")
        PlayQueue.playFrom(0, id = "task")
    }

    Column(Modifier.fillMaxSize()) {
        // R341 · 常驻状态条：不管停在哪个筛选页，正在做什么都看得见，
        // 不再逼人先猜「合成中」那一页。
        if (runningTask != null || qPaused) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .background(PrimarySoft, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                UiIconGlyph(if (runningTask != null) UiIcon.SYNTH else UiIcon.RESUME,
                    Primary, 16.dp)
                Spacer(Modifier.width(8.dp))
                val statusText = if (runningTask != null) {
                    val t = runningTask!!
                    val total = maxOf(t.total, st.total, 1)
                    val pct = st.done.coerceIn(0, total) * 100 / total
                    "正在做《${t.title}》· $pct%"
                } else "队列已暂停 · ${pending.size} 条待续"
                Text(statusText, fontSize = 12.sp, color = Primary,
                    modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                TextButton(onClick = {
                    if (runningTask != null) onProgress(runningTask!!.bookId)
                    else {
                        EventLog.ui("TASK_QUEUE_RESUME", "from=overview")
                        Producer.resumeQueue(c)
                        loadTick++
                    }
                }) {
                    Text(if (runningTask != null) "看进度 ›" else "接着做 ›",
                        fontSize = 12.sp, color = Primary)
                }
            }
        }
        // 三段切换，代码自绘不引库。计数直接挂在标题上，不另写一行废话。
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // R341 · 「全部」当默认页：正在做的、待续的、做好的音频一屏排下来；
            // 三段筛选退成筛选，找一段音频不再靠猜它在哪一页。
            val labels = listOf("全部") + listOf("合成中", "队列中", "已完成")
            val counts = listOf(
                (if (runningTask != null) 1 else 0) + pending.size + audioRows.size,
                if (runningTask != null) 1 else 0, pending.size, audioRows.size)
            labels.forEachIndexed { i, label ->
                val on = tab == i
                Box(Modifier.weight(1f).height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) PrimarySoft else Card)
                    .clickable { tab = i },
                    contentAlignment = Alignment.Center) {
                    Text("$label ${counts[i]}", fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Primary else Sub)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            when (tab) {
                // ⓪ 全部（默认）：最近做好的音频、待续条数、入口都在一屏
                0 -> {
                    item(key = "all_head") {
                        SectionLabel("最近做好的音频")
                    }
                    if (audioRows.isEmpty()) {
                        item(key = "all_empty") {
                            EmptyHint("还没有做好的音频") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onLibrary) {
                                        Text("去挑一本 ›", fontSize = 13.sp, color = Primary)
                                    }
                                    if (pending.isNotEmpty()) {
                                        TextButton(onClick = { tab = 2 }) {
                                            Text("看待续 ${pending.size} 条 ›",
                                                fontSize = 13.sp, color = Primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(audioRows.take(3), key = { "all_" + it.key }) { a ->
                        AudioCard(a, play = { playAudio(a) },
                            onDelete = { confirmAudio = a })
                    }
                    if (audioRows.size > 3) {
                        item(key = "all_more") {
                            TextButton(onClick = { tab = 3 }) {
                                Text("看全部 ${audioRows.size} 份音频 ›",
                                    fontSize = 13.sp, color = Primary)
                            }
                        }
                    }
                    if (pending.isNotEmpty()) {
                        item(key = "all_pending") {
                            TextButton(onClick = { tab = 2 }) {
                                Text("还有 ${pending.size} 条待续 ›",
                                    fontSize = 13.sp, color = Primary)
                            }
                        }
                    }
                }
                // ① 合成中：真正跑着的那本，实时进度 + 能停能看
                1 -> {
                    val t = runningTask
                    if (t == null) {
                        item(key = "running_empty") {
                            EmptyHint("现在没有在合成的音频") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { tab = 2 }) {
                                        Text("看队列 ›", fontSize = 13.sp, color = Primary)
                                    }
                                    TextButton(onClick = onLibrary) {
                                        Text("去挑一本 ›", fontSize = 13.sp, color = Primary)
                                    }
                                }
                            }
                        }
                    } else {
                        item(key = "running_${t.bookId}") {
                            val total = maxOf(t.total, st.total, 1)
                            val doneNow = st.done.coerceIn(0, total)
                            CardBox {
                                SectionLabel("这一份音频")
                                Spacer(Modifier.height(8.dp))
                                // R308 · 这里管的是「正在合成的这一章音频」，
                                // 不是整本书；R316 · 进度只给章号、阶段与百分比，
                                // 不再把 26678 这类内部小块数甩给用户。
                                val curCh = st.chapterProgress.keys.maxOrNull()?.let { it + 1 }
                                val pct = (doneNow * 100 / total).coerceIn(0, 100)
                                val stageLabel =
                                    if (st.stage.isBlank()) "正在合成" else st.stage
                                Text(curCh?.let { "第 $curCh 章音频" } ?: "章节音频",
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("${t.title} · ${modeText(t.modeId)} · ${scopeText(t)}",
                                    fontSize = 12.sp, color = Sub)
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { doneNow.toFloat() / total.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(7.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("第 ${curCh ?: 1} 章 · $stageLabel · $pct%",
                                    fontSize = 12.sp, color = Sub)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onProgress(t.bookId) }) {
                                        Text("看进度 ›", fontSize = 12.sp, color = Primary)
                                    }
                                    TextButton(onClick = { onOpenTask(t.bookId) }) {
                                        Text("去这一章 ›", fontSize = 12.sp, color = Primary)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        EventLog.ui("TASK_STOP", "id=${t.bookId.take(8)}")
                                        Producer.stop()
                                        Toast.makeText(c,
                                            "已停止，队列也暂停；去「队列中」接着做",
                                            Toast.LENGTH_LONG).show()
                                        loadTick++
                                    }) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            UiIconGlyph(UiIcon.STOP, Warn)
                                            Spacer(Modifier.width(4.dp))
                                            Text("停止制作", fontSize = 12.sp, color = Warn)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // ② 队列中：排队项 + 待续任务，能移除、能继续、能开书
                2 -> {
                    if (qPaused) {
                        item(key = "queue_paused") {
                            CardBox(Modifier.padding(bottom = 8.dp)) {
                                SectionLabel("队列已暂停")
                                Spacer(Modifier.height(6.dp))
                                Text("点「接着做」，或从下面任意一条继续。",
                                    fontSize = 12.sp, color = Sub)
                                TextButton(onClick = {
                                    EventLog.ui("TASK_QUEUE_RESUME", "from=tasks")
                                    Producer.resumeQueue(c)
                                    loadTick++
                                }) { Text("接着做 ›", fontSize = 13.sp, color = Primary) }
                            }
                        }
                    }
                    if (pending.isEmpty() && !qPaused) {
                        item(key = "queue_empty") {
                            EmptyHint("队列里没有待做的音频") {
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
                        val real = realMap[t.bookId] ?: 0
                        val total = maxOf(t.total, real, 1)
                        val warn = warnMap[t.bookId]
                        CardBox(Modifier.padding(bottom = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(unitText(t), fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                // R316 · 四种状态统一形状，颜色只表达语义，
                                // 不再让右上角散着三种裸字。
                                Box(Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (inQ) PrimarySoft
                                        else Warn.copy(alpha = 0.12f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text(badge, fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (inQ) Primary else Warn)
                                }
                            }
                            Text("《${t.title}》 · ${modeText(t.modeId)}",
                                fontSize = 12.sp, color = Sub)
                            if (t.bookId !in books) {
                                Text("书已从抽屉删掉，成品还在",
                                    fontSize = 12.sp, color = Warn)
                            }
                            if (warn != null) Text(warn, fontSize = 12.sp, color = Warn)
                            if (!inQ && total > 0) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { real.toFloat() / total.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(6.dp))
                                val qPct = (real * 100 / total).coerceIn(0, 100)
                                Text("已做 $qPct% · 接着做不用重来",
                                    fontSize = 12.sp, color = Sub)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!inQ) {
                                    // R172 · 重新分章后旧 only 是旧章号，不能拿它直接开跑
                                    if (t.phase == "reconfirm") {
                                        // R316 · 原来只摆一个禁用的「需重新选章」，
                                        // 点不动、死路一条；改成点进目录页重新勾章。
                                        TextButton(onClick = {
                                            EventLog.ui("TASK_RECONFIRM", "id=${t.bookId.take(8)}")
                                            onOpenTask(t.bookId)
                                        }) {
                                            Text("重新选章 ›", fontSize = 12.sp, color = Primary)
                                        }
                                    } else {
                                        TextButton(onClick = {
                                            EventLog.ui("TASK_RESUME", "id=${t.bookId.take(8)}")
                                            onResume(t)
                                        }, enabled = t.phase != "reconfirm" && !Producer.isBusy()) {
                                            // R327 · 点停止后还要等当前那一小段推理收尾，
                                            // 这段时间按钮本来就禁用；写明在收尾，别让人
                                            // 对着一个点不动的「继续制作」一脸懵。
                                            Text(if (Producer.isStopping()) "正在收尾…" else "继续制作",
                                                fontSize = 12.sp, color = Primary)
                                        }
                                    }
                                }
                                TextButton(onClick = { onOpenTask(t.bookId) }) {
                                    Text("去这一章 ›", fontSize = 12.sp, color = Primary)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    if (inQ) {
                                        // 排队项不是断点，直接移除；任务记录走二次确认
                                        EventLog.ui("TASK_DEQUEUE", "id=${t.bookId.take(8)}")
                                        scope.launch(Dispatchers.IO) {
                                            TaskResume.dequeue(c, t.bookId)
                                            withContext(Dispatchers.Main) { loadTick++ }
                                        }
                                    } else confirmDelete = t
                                }) { Text(if (inQ) "移除" else "删除任务",
                                    fontSize = 12.sp, color = Warn) }
                            }
                        }
                    }
                }
                // ③ 已完成：一条 = 一份音频（一章音频 / 首页粘贴合成的一段），
                // R308 · 不再按「整本书」列任务记录，贴回软件真正管的东西。
                3 -> {
                    if (audioRows.isEmpty()) {
                        item(key = "done_empty") {
                            EmptyHint("还没有做好的音频") {
                                TextButton(onClick = onLibrary) {
                                    Text("去挑一本 ›", fontSize = 13.sp, color = Primary)
                                }
                            }
                        }
                    }
                    // R341 · 整行可点播放，删除收进「⋯」，不再两枚小胶囊对咬
                    items(audioRows, key = { it.key }) { a ->
                        AudioCard(a, play = { playAudio(a) },
                            onDelete = { confirmAudio = a })
                    }
                }
            }
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
    onDelete: () -> Unit
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
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("${a.source} · $sizeText · $timeText",
                    fontSize = 11.sp, color = Sub)
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
        Box(Modifier.width(3.dp).height(12.dp)
            .background(Primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary)
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
