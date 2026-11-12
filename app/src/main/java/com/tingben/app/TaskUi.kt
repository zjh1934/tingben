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
    LaunchedEffect(refresh, loadTick) {
        withContext(Dispatchers.IO) {
            val ts = TaskResume.list(c)
            val qs = TaskResume.queue(c)
            val bs = BookStore.list(c).map { it.id }.toSet()
            val rm = ts.associate { it.bookId to
                if (st.running) 0 else TaskResume.realProgress(c, it) }
            val wm = ts.associate { it.bookId to TaskResume.invalidationReason(c, it) }
            val qp = Producer.queueState(c).second
            withContext(Dispatchers.Main) {
                tasks = ts
                queued = qs
                books = bs
                realMap = rm
                warnMap = wm
                qPaused = qp
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

    Column(Modifier.fillMaxSize()) {
        // 三段切换，代码自绘不引库。计数直接挂在标题上，不另写一行废话。
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = listOf("合成中", "队列中", "已完成")
            val counts = listOf(if (runningTask != null) 1 else 0, pending.size, done.size)
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
                // ① 合成中：真正跑着的那本，实时进度 + 能停能看
                0 -> {
                    val t = runningTask
                    if (t == null) {
                        item(key = "running_empty") {
                            EmptyHint("现在没有在合成的书") {
                                TextButton(onClick = { tab = 1 }) {
                                    Text("看队列 ›", fontSize = 13.sp, color = Primary)
                                }
                            }
                        }
                    } else {
                        item(key = "running_${t.bookId}") {
                            val total = maxOf(t.total, st.total, 1)
                            val doneNow = st.done.coerceIn(0, total)
                            CardBox {
                                SectionLabel("合成中")
                                Spacer(Modifier.height(8.dp))
                                Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("${modeText(t.modeId)} · ${scopeText(t)}",
                                    fontSize = 12.sp, color = Sub)
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { doneNow.toFloat() / total.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(7.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("已做好 $doneNow/$total 块 · ${st.stage}",
                                    fontSize = 12.sp, color = Sub)
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { onProgress(t.bookId) }) {
                                        Text("看进度 ›", fontSize = 12.sp, color = Primary)
                                    }
                                    TextButton(onClick = { onOpenTask(t.bookId) }) {
                                        Text("打开这本书 ›", fontSize = 12.sp, color = Primary)
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
                1 -> {
                    if (qPaused) {
                        item(key = "queue_paused") {
                            CardBox(Modifier.padding(bottom = 8.dp)) {
                                SectionLabel("队列已暂停")
                                Spacer(Modifier.height(6.dp))
                                Text("点「接着做」或下面任意一本继续。",
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
                        item(key = "queue_empty") { EmptyHint("队列里没有待做的书") }
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
                                Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                Text(badge, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (inQ) Primary else Warn)
                            }
                            Text("${modeText(t.modeId)} · ${scopeText(t)}",
                                fontSize = 12.sp, color = Sub)
                            if (t.bookId !in books) {
                                Text("书已从书架删掉，成品仍在「我的音频」",
                                    fontSize = 12.sp, color = Warn)
                            }
                            if (warn != null) Text(warn, fontSize = 12.sp, color = Warn)
                            if (!inQ && total > 0) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { real.toFloat() / total.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(6.dp))
                                Text("已做好 $real/$total 块", fontSize = 12.sp, color = Sub)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!inQ) {
                                    // R172 · 重新分章后旧 only 是旧章号，不能拿它直接开跑
                                    TextButton(onClick = {
                                        EventLog.ui("TASK_RESUME", "id=${t.bookId.take(8)}")
                                        onResume(t)
                                    }, enabled = t.phase != "reconfirm" && !Producer.isBusy()) {
                                        Text(if (t.phase == "reconfirm") "需重新选章"
                                             else "继续制作", fontSize = 12.sp, color = Primary)
                                    }
                                }
                                TextButton(onClick = { onOpenTask(t.bookId) }) {
                                    Text("打开书 ›", fontSize = 12.sp, color = Primary)
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
                // ③ 已完成：成品能听、能开、能删任务记录
                2 -> {
                    if (done.isEmpty()) {
                        item(key = "done_empty") { EmptyHint("还没有做完的书") }
                    }
                    items(done, key = { it.bookId }) { t ->
                        CardBox(Modifier.padding(bottom = 10.dp)) {
                            SectionLabel("已完成")
                            Spacer(Modifier.height(6.dp))
                            Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("${modeText(t.modeId)} · ${scopeText(t)}",
                                fontSize = 12.sp, color = Sub)
                            Text("最后更新 " + java.text.SimpleDateFormat(
                                "MM-dd HH:mm", java.util.Locale.US)
                                .format(java.util.Date(t.updatedAt)),
                                fontSize = 12.sp, color = Sub)
                            if (t.bookId !in books) {
                                Text("书已从书架删掉，成品仍在「我的音频」",
                                    fontSize = 12.sp, color = Warn)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    EventLog.ui("TASK_PLAY", "id=${t.bookId.take(8)}")
                                    onAudio(t.bookId)
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UiIconGlyph(UiIcon.LISTEN, Primary)
                                        Spacer(Modifier.width(4.dp))
                                        Text("听成品 ›", fontSize = 12.sp, color = Primary)
                                    }
                                }
                                TextButton(onClick = { onOpenTask(t.bookId) }) {
                                    Text("打开书 ›", fontSize = 12.sp, color = Primary)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { confirmDelete = t }) {
                                    Text("删除任务", fontSize = 12.sp, color = Warn)
                                }
                            }
                        }
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
                    "书还在书架里，随时可以重新选章制作。",
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
}

internal fun modeText(id: String): String = when (id) {
    "custom_voice" -> "预设音色"
    "base" -> "参考音频"
    else -> "描述造音色"
}

internal fun scopeText(t: TaskInfo): String =
    if (t.only == null) "整本" else "选中 ${t.only!!.size} 章"

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
    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, fontSize = 13.sp, color = Sub)
        action()
    }
}
