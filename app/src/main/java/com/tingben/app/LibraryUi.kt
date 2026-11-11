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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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

// @ui-part: LibraryUi
@Composable
internal fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit,
                           onModels: () -> Unit, onImportBook: () -> Unit,
                           onChanged: () -> Unit,
                           importError: String? = null,
                           onDismissImportError: () -> Unit = {},
                           compact: Boolean = false) {
    val books = remember(refresh) { BookStore.list(c) }
    var lastReads by remember { mutableStateOf(emptyMap<String, Int>()) }
    // H1 · 书架上的进度条：一眼看出这本书合成到哪了。
    // 用户实证「统计字数和本地离线对用户没有任何价值」→ 换成真实进度。
    // 章节表已落盘（R138 批次 B），resolve 命中后不再重扫整本；全部走 IO。
    var progress by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    LaunchedEffect(refresh, books) {
        val (reads, prog) = withContext(Dispatchers.IO) {
            val reads = ReadingState.allLast(c)
            val prog = books.associate { b ->
                val total = try {
                    ChapterPlan.resolve(c, b).first.size
                } catch (_: Throwable) { 0 }
                val done = try {
                    AudioLibrary.playableChapters(c, b.id).size
                } catch (_: Throwable) { 0 }
                b.id to Pair(done.coerceAtMost(maxOf(total, 0)), total)
            }
            reads to prog
        }
        lastReads = reads
        progress = prog
    }
    val pinned = remember(refresh) { BookStore.pinned(c) }
    val libScope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    // R230 · 置顶 / 删除不再常驻卡片右侧，长按书名才弹
    var menuBook by remember { mutableStateOf<String?>(null) }

    menuBook?.let { id ->
        val b = books.find { it.id == id } ?: return@let
        AlertDialog(
            onDismissRequest = { menuBook = null },
            title = { Text(b.title, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = { Text("置顶或删除", fontSize = 12.sp, color = Sub) },
            confirmButton = {
                TextButton(onClick = {
                    val next = !(b.id in pinned)
                    libScope.launch(Dispatchers.IO) {
                        BookStore.setPinned(c, b.id, next)
                        EventLog.ui("BOOK_PIN_TAP", "id=${b.id.take(8)} on=$next")
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                    menuBook = null
                }) { Text(if (b.id in pinned) "取消置顶" else "置顶", fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { menuBook = null; confirmDelete = b.id }) {
                    Text("删除", fontSize = 13.sp, color = Warn)
                }
            }
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这本书？") },
            text = { Text((books.find { it.id == id }?.title ?: "") +
                "\n\n会一起删掉这本书的章节表、已做好的音频、分段缓存、制作任务，" +
                "以及收听与阅读位置。若正在播放或制作这本书，会一并停止；" +
                "还在收尾上一段时会先停，稍等再删一次。") },
            confirmButton = {
                TextButton(onClick = {
                    // R193 · 返回 false = 后台还在收尾，已替用户点停止，
                    // 这次什么都不删，给明确反馈，别让书「点了还在」显得没反应。
                    val deleted = BookStore.delete(c, id)
                    confirmDelete = null
                    if (deleted) onChanged()
                    else Toast.makeText(c, "这本书还在收尾上一段，已点停止；稍等再删一次",
                        Toast.LENGTH_LONG).show()
                }) { Text("删除", color = Warn) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        // R178 · 书列表搬进抽屉（compact=true）后，原来这个 item 被 if (!compact)
        // 挡掉，刷新入口整个消失。现在抽屉里也常显，配合 RefreshStrip 的下拉 + 点按。
        item(key = "head") {
            RefreshStrip(
                onRefresh = onChanged,
                primary = Primary,
                primarySoft = PrimarySoft,
                sub = Sub
            )
            Spacer(Modifier.height(6.dp))
        }
        if (!compact && importError != null) {
            item(key = "import-error") {
                CardBox(Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("上次没导进来", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = Warn, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissImportError) {
                            Text("关闭", fontSize = 12.sp, color = Sub)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(importError, fontSize = 12.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onImportBook) {
                        Text("再选一次文件 ›", fontSize = 12.sp, color = Primary)
                    }
                }
            }
        }

        if (books.isNotEmpty()) {
            if (!compact) {
                item(key = "count") {
                    Text("共 ${books.size} 本", fontSize = 12.sp, color = Sub)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(books, key = { it.id }) { b ->
                val lastRead = lastReads[b.id]
                val (done, total) = progress[b.id] ?: (0 to 0)
                val pct = if (total > 0) done.toFloat() / total.toFloat() else 0f
                val isPinned = b.id in pinned
                // R230 · 点书名开书、长按书名弹置顶/删除，两个手势同一个检测器
                CardBox(Modifier.padding(bottom = 12.dp).pointerInput(b.id) {
                    detectTapGestures(
                        onTap = { onOpen(b.id) },
                        onLongPress = { menuBook = b.id })
                }) {
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isPinned) {
                                    Box(Modifier.size(6.dp)
                                        .background(Primary, RoundedCornerShape(50)))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(b.title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    maxLines = 1, color = palette.text,
                                    modifier = Modifier.weight(1f))
                            }
                            // 书名后面紧跟一条颜色条：已做好的章数 / 总章数。
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.fillMaxWidth().height(5.dp)
                                .background(PrimarySoft, RoundedCornerShape(50))) {
                                Box(Modifier.fillMaxWidth(pct).fillMaxHeight()
                                    .background(if (done > 0 && done >= total) Good else Primary,
                                        RoundedCornerShape(50)))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (total > 0) "已做好 $done/$total 章"
                                else "还没分章",
                                fontSize = 11.sp, color = Sub)
                            // R230 · 「上次读到 第 N 章」撤掉，制作进度条保留
                        }
                    }
                }
            }
        } else {
            // R174 · 自审发现：书架为空时 LazyColumn 一片白，新用户只能靠抽屉
            // 右上角那枚小「＋ 导入」。给一个能按的入口，不写教程、不加说明文字。
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = onImportBook) {
                        Text("导入第一本 ›", fontSize = 14.sp, color = Primary)
                    }
                }
            }
        }
    }
}

/**
 * R176 · 粘贴 / 分享进来的文字原来一律叫「粘贴的文字」，书架里几本同名书
 * 根本分不清。取首行前 18 字当书名；空文本退回旧名。不写说明、不加 label。
 */
internal fun autoBookTitle(text: String, fallback: String): String {
    val line = text.lineSequence().map { it.trim() }
        .firstOrNull { it.isNotEmpty() } ?: return fallback
    val clean = line.replace(Regex("[\r\n\t]"), " ").trim()
    return if (clean.length <= 18) clean else clean.take(18) + "…"
}

