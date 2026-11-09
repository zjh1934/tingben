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

// @ui-part: TaskUi
@Composable
internal fun TaskCenterScreen(c: Context, refresh: Int, onOpenTask: (String) -> Unit,
                             onProgress: (String) -> Unit,
                             onAudio: (String) -> Unit, onLibrary: () -> Unit,
                             onResume: (TaskInfo) -> Unit) {
    val scope = rememberCoroutineScope()
    // G1 · 用户实证：任务页只有一个巨大的「继续」，找不到怎么删任务。
    // 现在一张卡 = 书名 + 状态 + 几个小操作，删除走二次确认。
    var confirmDelete by remember { mutableStateOf<TaskInfo?>(null) }
    val st by Producer.state.collectAsState()
    val live by Producer.resume.collectAsState()
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) { loadTick++; return@LaunchedEffect }
        while (true) { delay(2000); loadTick++ }
    }
    val tasks = remember(refresh, loadTick) { TaskResume.list(c) }
    var realMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var warnMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var books by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(tasks, loadTick) {
        withContext(Dispatchers.IO) {
            books = BookStore.list(c).map { it.id }.toSet()
            realMap = tasks.associate { it.bookId to
                if (st.running) 0 else TaskResume.realProgress(c, it) }
            warnMap = tasks.associate { it.bookId to TaskResume.invalidationReason(c, it) }
        }
    }
    val queued = remember(refresh, loadTick, st.running) { TaskResume.queue(c) }
    val qState = remember(refresh, loadTick, st.running) { Producer.queueState(c) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (queued.isNotEmpty() || st.running) {
            item(key = "tasks_queue") {
                CardBox(Modifier.padding(bottom = 8.dp)) {
                    Text(
                        if (queued.isNotEmpty())
                            "队列 ${queued.size} 个待做 · 当前这本做完自动接下一个"
                        else "制作中 · 队列里没有待做任务",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (qState.second) Warn else Primary)
                    if (queued.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(queued.joinToString("、") { "《${it.title}》" }.take(60),
                            fontSize = 12.sp, color = Sub, maxLines = 2)
                    }
                    if (qState.second) {
                        Spacer(Modifier.height(4.dp))
                        Text("队列已暂停", fontSize = 12.sp, color = Warn)
                        TextButton(onClick = { Producer.resumeQueue(c) }) {
                            Text("继续队列 ›", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (tasks.isNotEmpty()) {
            item(key = "tasks_count") {
                Text("共 ${tasks.size} 本 · 一本一个任务",
                    fontSize = 12.sp, color = Sub,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            val unfinished = tasks.filter { it.phase != "done" }
            val finishedTasks = tasks.filter { it.phase == "done" }
            if (unfinished.isNotEmpty()) {
                item(key = "tasks_unfinished_label") {
                    Text("${unfinished.size} 本未完成",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Warn,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            items(unfinished + finishedTasks, key = { it.bookId }) { t ->
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
                val scopeText = if (t.only == null) "整本" else "选中 ${t.only!!.size} 章"
                val real = realMap[t.bookId] ?: 0
                val total = maxOf(t.total, real, if (runningThis) st.total else 0)
                val doneNow = (if (runningThis) st.done else real).coerceIn(0, maxOf(total, 1))
                val warn = warnMap[t.bookId]
                val otherBusy = st.running && live?.bookId != t.bookId
                CardBox(Modifier.clickable { onOpenTask(t.bookId) }
                    .padding(bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Text(badge, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (runningThis) Primary else if (t.phase == "done") Good else Warn)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("$modeText · $scopeText", fontSize = 12.sp, color = Sub)
                    if (t.bookId !in books) {
                        Spacer(Modifier.height(4.dp))
                        Text("书已从书架删掉，成品仍在「我的音频」", fontSize = 12.sp, color = Warn)
                    }
                    if (warn != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(warn, fontSize = 12.sp, color = Warn)
                    }
                    if (total > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { doneNow.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(7.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("已做好 $doneNow/$total 块", fontSize = 12.sp, color = Sub)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("最后更新 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(t.updatedAt)), fontSize = 12.sp, color = Sub)
                    if (otherBusy && !runningThis && t.phase != "done") {
                        Text("另一本正在制作，等它跑完或先停它。",
                            fontSize = 12.sp, color = Warn)
                    }
                    // 一行小操作，不再拿一个占满整宽的按钮当主视觉。
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!runningThis && t.phase != "done") {
                            // R172 · 重新分章后旧 only 是旧章号，不能拿它直接开跑
                            Button(onClick = {
                                EventLog.ui("TASK_RESUME", "book=${t.title}")
                                onResume(t)
                            }, enabled = !otherBusy && t.phase != "reconfirm",
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 14.dp)) {
                                Text(if (t.phase == "reconfirm") "需重新选章"
                                     else "继续制作", fontSize = 12.sp)
                            }
                        } else if (runningThis) {
                            TextButton(onClick = { onProgress(t.bookId) }) {
                                Text("看进度 ›", fontSize = 12.sp)
                            }
                        } else {
                            TextButton(onClick = {
                                EventLog.ui("TASK_PLAY", "book=${t.title}")
                                onAudio(t.bookId)
                            }) { Text("听成品 ›", fontSize = 12.sp) }
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { onOpenTask(t.bookId) }) {
                            Text("打开这本书 ›", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = t }, enabled = !runningThis) {
                            Text("删除任务", fontSize = 12.sp,
                                color = if (runningThis) Sub else Warn)
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
                    EventLog.ui("TASK_DELETE", "book=${t.title}")
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

/** R161 · 顶层组合函数，不能写进 SettingsScreen 里（Compose 不接受局部组合函数）。 */
