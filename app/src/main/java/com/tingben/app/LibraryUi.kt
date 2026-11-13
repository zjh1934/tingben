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
                           compact: Boolean = false,
                           currentId: String? = null) {
    val books = remember(refresh) { BookStore.list(c) }
    var lastReads by remember { mutableStateOf(emptyMap<String, Int>()) }
    // H1 · 书架上的进度条：一眼看出这本书合成到哪了。
    // 用户实证「统计字数和本地离线对用户没有任何价值」→ 换成真实进度。
    // 章节表已落盘（R138 批次 B），resolve 命中后不再重扫整本；全部走 IO。
    var progress by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    // R298 · 抽屉书名下的进度条跟着制作实时动：原来只在 refresh / books 变化时
    // 按「已做好的章数」算一次，制作中每完成一段都不重算，看着像卡住不动。
    val prodState by Producer.state.collectAsState()
    val liveTask by Producer.resume.collectAsState()
    LaunchedEffect(refresh, books) {
        val (reads, prog) = withContext(Dispatchers.IO) {
            val reads = ReadingState.allLast(c)
            // R287 · 原来每本书各调一次 playableChapters → 每本都全盘扫一遍
            // index.json + 成品目录 + 伴生 meta。改成一次 list() 按书分组统计。
            val counts = try {
                AudioLibrary.playableCountByBook(c)
            } catch (_: Throwable) { emptyMap() }
            val prog = books.associate { b ->
                val total = try {
                    ChapterPlan.resolve(c, b).first.size
                } catch (_: Throwable) { 0 }
                val done = counts[b.id] ?: 0
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
        val isPinned = b.id in pinned
        // R314 · 原来标题只写书名、正文写「置顶或删除」当提示，三个按钮还把
        // 「置顶」和「取消置顶」同时摆出来。现在首行亮出书名与置顶状态，
        // 置顶按状态只留一个，删除单独警示色，并把「连音频一起删」提前说清。
        AlertDialog(
            onDismissRequest = { menuBook = null },
            title = {
                Column {
                    Text(b.title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(if (isPinned) "已置顶" else "未置顶",
                        fontSize = 12.sp, color = Sub)
                }
            },
            text = { Text("删除会连已做好的音频一起删掉", fontSize = 12.sp, color = Sub) },
            dismissButton = {
                TextButton(onClick = {
                    val next = !isPinned
                    libScope.launch(Dispatchers.IO) {
                        BookStore.setPinned(c, b.id, next)
                        EventLog.ui("BOOK_PIN_TAP", "id=${b.id.take(8)} on=$next")
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                    menuBook = null
                }) { Text(if (isPinned) "取消置顶" else "置顶",
                    fontSize = 13.sp, color = Primary) }
            },
            confirmButton = {
                TextButton(onClick = { menuBook = null; confirmDelete = b.id }) {
                    Text("删除", fontSize = 13.sp, color = Warn)
                }
            }
        )
    }

    confirmDelete?.let { id ->
        val title = books.find { it.id == id }?.title ?: ""
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除《$title》？") },
            // R314 · 书名挪进标题，框内只讲后果；重要说明不再散到蒙层外面。
            // 首句短、以「只删」起头（R158b 后果告知放行），后续把位置 / 停机 /
            // 收尾窗口说全，一条不省。
            text = { Text("只删这一本：音频、章节表、" +
                "收听与阅读位置会一起删掉；正在播放或制作会一并停止；" +
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
                }) { Text("删除", color = Warn, fontWeight = FontWeight.Bold) }
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
                val live = prodState.running && liveTask?.bookId == b.id
                val pct = if (live) {
                    val t = maxOf(prodState.total, 1)
                    prodState.done.coerceIn(0, t).toFloat() / t.toFloat()
                } else if (total > 0) done.toFloat() / total.toFloat() else 0f
                val isPinned = b.id in pinned
                // R230 · 点书名开书、长按书名弹置顶/删除，两个手势同一个检测器
                CardBox(Modifier.padding(bottom = 12.dp).pointerInput(b.id) {
                    detectTapGestures(
                        onTap = { onOpen(b.id) },
                        onLongPress = { menuBook = b.id })
                }) {
                    // R320 · 图 47：抽屉里三本书长得一模一样，不知道当前开的是
                    // 哪一本。当前书顶部给一条主色横条，书名也转主色；不写
                    // 「正在看」这类字，进度条下面依旧只有一条跟着动的条。
                    if (compact && currentId != null && b.id == currentId) {
                        Box(Modifier.fillMaxWidth().height(3.dp)
                            .background(Primary, RoundedCornerShape(50)))
                        Spacer(Modifier.height(8.dp))
                    }
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
                                    maxLines = 1,
                                    color = if (compact && currentId != null &&
                                        b.id == currentId) Primary else palette.text,
                                    modifier = Modifier.weight(1f))
                            }
                            // 书名后面紧跟一条颜色条：已做好的章数 / 总章数。
                            // R314 · 原来三态都是同一根紫色条，制作中、已做完、
                            // 还没开始根本分不出来。用颜色 + 制作中的小转圈区分，
                            // 一个字都不加（进度条下面不写废话是定好的口径）。
                            Spacer(Modifier.height(7.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(5.dp)
                                    .background(PrimarySoft, RoundedCornerShape(50))) {
                                    Box(Modifier.fillMaxWidth(pct).fillMaxHeight()
                                        .background(
                                            if (total > 0 && done >= total && !live) Good else Primary,
                                            RoundedCornerShape(50)))
                                }
                                if (live) {
                                    Spacer(Modifier.width(10.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Primary)
                                }
                            }
                            // R298 · 进度条下面不写「已做好 X/Y 章」这类废话，
                            // 书名下面只留这一条跟着制作动的进度条。
                        }
                    }
                }
            }
        } else if (!compact) {
            // R291 · 抽屉顶部已经有「＋ 导入一本书」那枚实心按钮，这里原来的
            // 「导入第一本 ›」是第二个入口，已删。R314 · 但整页留白让新装 /
            // 清干净后对着白屏不知道去哪，按主页片段空槽的样子补 3 个骨架：
            // 不写「还没有书」这类废话，也不再加任何导入按钮（顶部那枚是唯一入口）。
            items(3, key = { "empty-slot-$it" }) {
                Box(Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PrimarySoft.copy(alpha = 0.35f))
                    .border(1.dp, Color(0xFFE7EAF2), RoundedCornerShape(18.dp)))
                Spacer(Modifier.height(12.dp))
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

