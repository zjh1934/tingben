package com.tingben.app

import android.content.Context
import android.media.MediaPlayer
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingben.app.core.*
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { LIBRARY, PRODUCE, PROGRESS, AUDIO, SETTINGS, MODELS }

private val Bg = Color(0xFFF7F5FA)
private val Card = Color(0xFFFFFFFF)
private val Primary = Color(0xFF5B4B8A)
private val Sub = Color(0xFF625D6E)
private val Good = Color(0xFF1E6B4A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.LIBRARY) }
    var bookId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var modelsFrom by remember { mutableStateOf(Screen.LIBRARY) }
    var importing by remember { mutableStateOf<String?>(null) }
    val importBytesFlow = remember { MutableStateFlow(0L) }
    val importBytes by importBytesFlow.collectAsState()
    var importError by remember { mutableStateOf<String?>(null) }
    val st by Producer.state.collectAsState()

    // 系统返回键/手势统一回书架，而不是直接退出 App。
    // 之前没接管：用户按一次返回进程就退了，重开又回到书架，正在跑的制作就"找不到"了。
    BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }

    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) { }
            val name = queryName(ctx, uri).removeSuffix(".txt").ifBlank { "未命名" }
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrBlank()) Toast.makeText(ctx, "这个文件里没有文字", Toast.LENGTH_SHORT).show()
                else { BookStore.add(ctx, name, text); refresh++ }
            } catch (t: Throwable) {
                EventLog.log(EventLog.Kind.CRASH, "BOOK_IMPORT_FAIL", t.message ?: "")
                Toast.makeText(ctx, "这本书没读进来，换个文件试试", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 关键：2.5GB 的复制绝不能在主线程做，否则系统会按 ANR 杀掉进程。
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { }
        val name = queryName(ctx, uri).ifBlank {
            uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        }
        importing = name
        importBytesFlow.value = 0L
        importError = null
        EventLog.log(EventLog.Kind.MODEL, "MODEL_IMPORT_BEGIN", name)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    ModelStore.import(ctx, name, s) { n -> importBytesFlow.value = n }
                } ?: throw IllegalStateException("打不开这个文件")
            }
            withContext(Dispatchers.Main) {
                importing = null
                val err = result.exceptionOrNull()
                if (err != null) {
                    importError = err.message ?: "导入失败"
                    Toast.makeText(ctx, "没导入成功：${importError}", Toast.LENGTH_LONG).show()
                } else {
                    refresh++
                    val left = ModelStore.missing(ctx)
                    if (left.isEmpty()) {
                        // 三个齐了就别让用户自己找「下一步」，直接回到主路
                        screen = Screen.LIBRARY
                        Toast.makeText(ctx, "模型都齐了，导入一本书就能开始", Toast.LENGTH_LONG).show()
                    } else {
                        val leftText = left.joinToString("、")
                        Toast.makeText(ctx, "已导入，还差 ${left.size} 个：$leftText",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // 顶栏：只有产品名 + 一个动作
        Row(
            Modifier.fillMaxWidth().background(Card).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("听本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primary)
            Spacer(Modifier.weight(1f))
            if (screen == Screen.LIBRARY) {
                TextButton(onClick = { pickBook.launch(arrayOf("text/plain", "*/*")) }) { Text("＋ 导入一本书") }
            }
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; screen = Screen.PRODUCE },
                    onModels = { modelsFrom = Screen.LIBRARY; screen = Screen.MODELS },
                    onImportBook = { pickBook.launch(arrayOf("text/plain", "*/*")) },
                    onChanged = { refresh++ },
                    producing = st.running, progressText = if (st.total > 0) "${st.done}/${st.total} 块" else "",
                    onProgress = { screen = Screen.PROGRESS })
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { screen = Screen.LIBRARY },
                    onProgress = { screen = Screen.PROGRESS },
                    onDone = { screen = Screen.AUDIO })
                Screen.PROGRESS -> ProgressScreen(ctx, onBack = { screen = Screen.LIBRARY })
                Screen.AUDIO -> AudioScreen(ctx, refresh)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; screen = Screen.MODELS },
                    onLibrary = { screen = Screen.LIBRARY })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { screen = modelsFrom },
                    onLibrary = { screen = Screen.LIBRARY },
                    onChanged = { refresh++ })
            }
        }

        // 跨页面常驻：只要做过/正在做，任何一屏都点得到，不靠用户记住「进度在哪」
        val hasTask = st.running || st.output != null ||
                (st.stage != "还没开始" && st.message.isNotBlank())
        if (hasTask) {
            Row(
                Modifier.fillMaxWidth().background(if (st.running) Color(0xFFEDE7F6) else Color(0xFFE7F4EC))
                    .clickable { screen = if (st.output != null) Screen.AUDIO else Screen.PROGRESS }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(
                    if (st.running) Primary else Good, RoundedCornerShape(50)))
                Spacer(Modifier.width(9.dp))
                Text(
                    if (st.running) {
                        if (st.total > 0) "正在制作 · ${st.done}/${st.total} 块" else "正在制作…"
                    } else if (st.output != null) "做好了，点这里去听"
                    else st.stage,
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    color = if (st.running) Primary else Good,
                    modifier = Modifier.weight(1f))
                Text("查看 ›", fontSize = 12.5.sp, color = Primary)
            }
        }
        // 底栏 3 个 Tab：避开系统三键导航栏，别和返回键叠在一起
        Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Card).padding(vertical = 6.dp)) {
            TabButton("书架", screen == Screen.LIBRARY || screen == Screen.PRODUCE || screen == Screen.PROGRESS,
                Modifier.weight(1f)) { screen = Screen.LIBRARY }
            TabButton("我的音频", screen == Screen.AUDIO, Modifier.weight(1f)) { screen = Screen.AUDIO; refresh++ }
            TabButton("设置", screen == Screen.SETTINGS || screen == Screen.MODELS,
                Modifier.weight(1f)) { screen = Screen.SETTINGS; refresh++ }
        }
    }
}

private fun queryName(c: Context, uri: Uri): String {
    var name = ""
    c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && cur.moveToFirst()) name = cur.getString(i)
    }
    return name
}

@Composable
private fun TabButton(label: String, on: Boolean, mod: Modifier, click: () -> Unit) {
    Column(mod.clickable { click() }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            color = if (on) Primary else Sub)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(if (on) 5.dp else 0.dp).background(Primary, RoundedCornerShape(50)))
    }
}

@Composable
private fun CardBox(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(mod.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

// ── ① 书架 ──────────────────────────────────────────────────
@Composable
private fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit,
                           onModels: () -> Unit, onImportBook: () -> Unit,
                           onChanged: () -> Unit, producing: Boolean,
                           progressText: String, onProgress: () -> Unit) {
    val books = remember(refresh) { BookStore.list(c) }
    val ready = remember(refresh) { ModelStore.ready(c) }
    val clipboard = LocalClipboardManager.current
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteTitle by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("把小说变成能听的音频", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("导入一本书，点一次开始，剩下的交给手机自己跑。", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))
        if (producing) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("有一本书正在制作", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Primary)
                Spacer(Modifier.height(4.dp))
                Text(if (progressText.isBlank()) "手机正在算，可以退出这个页面，它不会停。"
                     else "已完成 $progressText。可以退出这个页面，它不会停。",
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(9.dp))
                FilledTonalButton(onClick = onProgress, Modifier.fillMaxWidth()) { Text("查看进度") }
            }
        }
        if (!ready) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("还差模型文件", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("第一次用要准备 3 个模型文件（合计约 5GB），下好导入一次，以后每本书都用它们。",
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("去准备") }
            }
        } else if (books.isEmpty()) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("模型都齐了 ✓", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Good)
                Spacer(Modifier.height(4.dp))
                Text("下一步：导入一本 txt 小说，或直接粘贴一段文字。", fontSize = 12.sp, color = Sub)
            }
        }

        // 没有现成文件时，粘贴是第二条路：默认收起，不占首屏主按钮
        TextButton(onClick = { pasteOpen = !pasteOpen }) {
            Text(if (pasteOpen) "收起粘贴框" else "没有文件？粘贴一段文字",
                fontSize = 12.5.sp, color = Primary)
        }
        if (pasteOpen) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                OutlinedTextField(
                    value = pasteTitle,
                    onValueChange = { pasteTitle = it },
                    label = { Text("书名（可以不填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("把小说文字粘在这里") },
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = {
                        val got = clipboard.getText()?.text ?: ""
                        if (got.isNotBlank()) pasteText = got
                        else Toast.makeText(c, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
                    }) { Text("粘贴") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (pasteText.isBlank()) {
                                Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                            } else {
                                BookStore.add(c, pasteTitle.trim().ifBlank { "粘贴的文字" }, pasteText)
                                pasteText = ""; pasteTitle = ""; pasteOpen = false
                                onChanged()
                                Toast.makeText(c, "书加好了", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("用这段文字建一本书") }
                }
            }
        }
        if (books.isEmpty()) {
            CardBox {
                Text("书架还是空的", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("选一个 txt 文件就行，也可以粘贴文字。", fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onImportBook, Modifier.fillMaxWidth().height(48.dp)) {
                    Text("导入一本书", fontSize = 15.sp)
                }
            }
        } else {
            books.forEach { b ->
                CardBox(Modifier.padding(bottom = 10.dp)) {
                    Text(b.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("${b.chars} 字 · 纯文本", fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { BookStore.delete(c, b.id) }) { Text("删除", color = Sub) }
                        Button(onClick = { onOpen(b.id) }) { Text("制作有声书") }
                    }
                }
            }
        }
    }
}

// ── ② 制作：一屏一个主按钮 ────────────────────────────────────
@Composable
private fun ProduceScreen(c: Context, id: String?, refresh: Int, onBack: () -> Unit,
                          onProgress: () -> Unit, onDone: () -> Unit) {
    val book = remember(id, refresh) { id?.let { BookStore.list(c).find { b -> b.id == it } } }
    val st by Producer.state.collectAsState()
    val chapters = remember(book) { book?.let { ChapterSplitter.split(BookStore.read(c, it)).first } ?: emptyList() }
    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    if (book == null) {
        Column(Modifier.padding(16.dp)) { Text("这本书不在了"); TextButton(onClick = onBack) { Text("返回书架") } }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("${chapters.size} 章 · ${book.chars} 字", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))

        if (st.output != null) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("做好了", fontWeight = FontWeight.Bold, color = Good)
                Spacer(Modifier.height(4.dp))
                Text(st.message, fontSize = 12.5.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("去听") }
            }
        }

        CardBox(Modifier.padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("制作范围", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.index }.toSet() }) {
                    Text(if (selected.size == chapters.size) "清空" else "全选")
                }
            }
            Text(if (selected.isEmpty()) "整本（不勾就是全部）" else "已选 ${selected.size} 章",
                fontSize = 12.sp, color = Sub)
            if (chapters.size in 2..40) {
                Spacer(Modifier.height(8.dp))
                chapters.forEach { ch ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (ch.index in selected) selected - ch.index else selected + ch.index
                    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ch.index in selected, onCheckedChange = {
                            selected = if (it) selected + ch.index else selected - ch.index
                        })
                        Spacer(Modifier.width(6.dp))
                        Text(ch.title.take(28), fontSize = 12.5.sp, maxLines = 1)
                    }
                }
            }
        }

        Button(
            onClick = {
                Producer.start(c, book, if (selected.isEmpty()) null else selected)
                onProgress()
            },
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (st.running) "正在制作…" else "开始制作", fontSize = 16.sp) }

        if (st.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(st.message, fontSize = 12.5.sp, color = if (st.output != null) Good else Sub)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onProgress) { Text("查看进度") }
    }
}

// ── ③ 进度：只给人话，但必须让人看得出「它还活着」 ──────────────
@Composable
private fun ProgressScreen(c: Context, onBack: () -> Unit) {
    val st by Producer.state.collectAsState()
    val pct = if (st.total > 0) st.done.toFloat() / st.total else 0f
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    // 每秒读一次最近一条引擎事件：prefill 一批在手机上要 70 秒，
    // 期间没有块完成，块进度条纹丝不动，必须给「还在算」的心跳。
    val heartbeat = remember(tick) {
        val (act, _) = EventLog.lastEngine()
        val age = (EventLog.lastEngineAgeMs() / 1000).coerceAtLeast(0)
        val what = when {
            act.contains("PREFILL") -> "正在把这一段读进模型，手机上这一步可能要一两分钟"
            act.contains("DECODE") -> "正在逐字生成这一块的朗读标注"
            act.contains("TOKENIZE") || act.contains("ENTER") -> "正在准备文字"
            act.contains("TTS") -> "正在把文字读成声音"
            else -> "正在准备"
        }
        val extra = if (st.running && age > 100) " · 已 ${age} 秒没新动作，可能算得很慢" else ""
        "$what$extra"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text("制作进度", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        CardBox {
            Text(st.stage, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(6.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                if (st.total > 0) "已完成 ${st.done}/${st.total} 块" + if (st.failed > 0) " · ${st.failed} 块改走直读" else ""
                else st.message.ifBlank { "还没开始" },
                fontSize = 12.5.sp, color = Sub)
            Spacer(Modifier.height(6.dp))
            Text(st.message, fontSize = 12.sp, color = Sub)
            if (st.running) {
                Spacer(Modifier.height(12.dp))
                Text(heartbeat, fontSize = 12.sp, color = Primary)
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                Spacer(Modifier.height(5.dp))
                Text("算完一块才会往上走一格，中间不会一直跳数字。", fontSize = 11.sp, color = Sub)
            }
        }
        Spacer(Modifier.height(14.dp))
        val steps = listOf("拆章", "理解文字", "生成声音", "整理音频", "完成")
        steps.forEach { s ->
            val active = when (s) {
                "拆章" -> st.stage.contains("分章")
                "理解文字" -> st.stage.contains("理解")
                "生成声音" -> st.stage.contains("生成声音")
                "整理音频" -> st.stage.contains("整理")
                "完成" -> st.output != null || st.stage == "完成"
                else -> false
            }
            val done = st.output != null
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(
                    if (done || active) Primary else Color(0xFFD6D0E2), RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Text(s, fontSize = 13.sp, color = if (done || active) Primary else Sub)
            }
        }

        // 思考外显：开关打开时，这里直接展开最近一块的推理全文
        if (Prefs.thinking(c)) {
            Spacer(Modifier.height(16.dp))
            var showThink by remember { mutableStateOf(false) }
            CardBox {
                Row(Modifier.clickable { showThink = !showThink }, verticalAlignment = Alignment.CenterVertically) {
                    Text("思考过程", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    Text(if (showThink) "收起" else "展开", fontSize = 12.sp, color = Primary)
                }
                if (showThink) {
                    val (idx, txt) = remember(tick) { ThinkingStore.latest(c) }
                    Spacer(Modifier.height(9.dp))
                    Text(if (txt.isBlank()) "还没有思考记录，跑起来后这里会逐块出现。"
                         else "第 ${idx + 1} 块：\n$txt",
                        fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                }
            }
        }
        if (!st.running) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("回书架，随时能再进来看") }
        }
    }
}

// ── ④ 我的音频 ───────────────────────────────────────────────
@Composable
private fun AudioScreen(c: Context, refresh: Int) {
    val dir = File(c.filesDir, "audio")
    val files = remember(refresh) {
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    var playing by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val clipboard = LocalClipboardManager.current
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val f = playing
        if (uri != null && f != null) {
            try { c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().copyTo(o) }
                Toast.makeText(c, "已保存", Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("我的音频", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("做好的有声书都在这儿，点开就听。", fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))
        if (files.isEmpty()) {
            CardBox {
                Text("还没有音频", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("回书架选一本书，点「制作有声书」。", fontSize = 12.sp, color = Sub)
            }
        } else files.forEach { f ->
            val title = remember(f) {
                val id = f.name.removeSuffix(".wav")
                BookStore.list(c).find { it.id == id }?.title ?: "有声书"
            }
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("%.1f MB".format(f.length() / 1024f / 1024f), fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(onClick = {
                        if (playing == f) {
                            player?.stop(); player?.release(); player = null; playing = null
                        } else {
                            player?.release()
                            val mp = MediaPlayer()
                            try {
                                mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
                                player = mp; playing = f
                                mp.setOnCompletionListener { playing = null }
                            } catch (t: Throwable) {
                                Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text(if (playing == f) "停止" else "试听") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { playing = f; export.launch(title + ".wav") }) { Text("保存到手机") }
                }
            }
        }
    }
}

// ── ⑤ 设置：分组折叠，不把整页塞满 ─────────────────────────────
@Composable
private fun SettingOption(label: String, options: List<String>, current: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 12.sp, color = Sub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, o ->
                TextButton(onClick = { onPick(i) }) {
                    Text(o, fontSize = 12.sp,
                        fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == current) Primary else Sub)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit, onLibrary: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val missing = remember(refresh) { ModelStore.missing(c) }
    val st by Producer.state.collectAsState()
    var advanced by remember { mutableStateOf(false) }
    var cfg by remember { mutableStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf("") }
    val probeScope = rememberCoroutineScope()

    // cfg 只是为了让 Preference 改动立刻反映到界面
    val thinkOn = remember(cfg) { Prefs.thinking(c) }
    val nCtx = remember(cfg) { Prefs.nCtx(c) }
    val threads = remember(cfg) { Prefs.threads(c) }
    val temp = remember(cfg) { Prefs.temperature(c) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("模型", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                if (missing.isEmpty()) "都准备好了，可以制作有声书。" else "还差：" + missing.joinToString("、"),
                fontSize = 12.5.sp, color = if (missing.isEmpty()) Good else Sub)
            Spacer(Modifier.height(9.dp))
            FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("导入 / 查看模型") }
            if (missing.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onLibrary, Modifier.fillMaxWidth().height(46.dp)) { Text("去书架制作") }
            }
        }

        // 思考外显开关：打开后进度页能逐块看推理，代价是明显更慢
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("思考过程", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(if (thinkOn) "开着：每块先想再标，进度页能逐块看推理。"
                         else "关着：直接出标注，最快最省。",
                        fontSize = 11.5.sp, color = Sub)
                }
                Switch(checked = thinkOn, onCheckedChange = {
                    Prefs.setThinking(c, it); cfg++
                    EventLog.log(EventLog.Kind.ENGINE, "THINK_MODE", if (it) "on" else "off")
                })
            }
            if (thinkOn) {
                Spacer(Modifier.height(6.dp))
                Text("打开后每块要多吐几百字推理，整本会明显变慢；长块可能超预算走降级直读。",
                    fontSize = 11.sp, color = Sub)
            }
        }

        // 模型参数：只暴露三个真能改的
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("模型设置", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            SettingOption("上下文窗口", listOf("4096", "6144", "8192"),
                listOf(4096, 6144, 8192).indexOf(nCtx).coerceAtLeast(0)) { i ->
                Prefs.setNCtx(c, listOf(4096, 6144, 8192)[i]); cfg++
            }
            SettingOption("运算线程", listOf("2", "4", "6"),
                listOf(2, 4, 6).indexOf(threads).coerceAtLeast(0)) { i ->
                Prefs.setThreads(c, listOf(2, 4, 6)[i]); cfg++
            }
            SettingOption("随机度（思考关时有效）", listOf("0 稳", "0.3", "0.6"),
                listOf(0f, 0.3f, 0.6f).indexOf(temp).coerceAtLeast(0)) { i ->
                Prefs.setTemperature(c, listOf(0f, 0.3f, 0.6f)[i]); cfg++
            }
            Text("8192 在部分手机上会因内存不足崩，不确定就别动。", fontSize = 11.sp, color = Sub)
        }

        // 音频模型调试：真合成一句，不是 Toast 假按钮
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("音频调试", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("不跑整本，只让声音模型读一句话，确认它能出声。", fontSize = 11.5.sp, color = Sub)
            Spacer(Modifier.height(9.dp))
            Button(
                onClick = {
                    probing = true; probeResult = "正在合成…"
                    probeScope.launch(Dispatchers.IO) {
                        val res = runCatching {
                            val tk = ModelStore.talker(c) ?: throw IllegalStateException("缺声音主文件")
                            val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺声音配套文件")
                            val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                            if (h == 0L) throw IllegalStateException("声音模型打不开")
                            try {
                                val f = File(c.filesDir, "probe.wav")
                                val code = NativeEngine.ttsSynthesize(h, "你好，这是一句试音。",
                                    "平稳、清晰地叙述", null, "auto", f.absolutePath, 42L)
                                if (code != 0 || !f.exists() || f.length() <= 44)
                                    throw IllegalStateException("没生成声音，code=$code")
                                f.absolutePath
                            } finally { NativeEngine.ttsFree(h) }
                        }
                        withContext(Dispatchers.Main) {
                            probing = false
                            val path = res.getOrNull()
                            probeResult = if (path != null)
                                "出声了 ✓ ${File(path).length()} 字节，点右边试听"
                            else "失败：" + (res.exceptionOrNull()?.message ?: "未知原因")
                            res.exceptionOrNull()?.let {
                                EventLog.log(EventLog.Kind.CRASH, "TTS_PROBE_FAIL", it.message ?: "")
                            }
                        }
                    }
                },
                enabled = !probing && !st.running && missing.isEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (probing) "正在合成…" else "试合成一句") }
            if (probeResult.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(probeResult, fontSize = 11.5.sp, color = Sub, modifier = Modifier.weight(1f))
                    if (probeResult.startsWith("出声了")) {
                        TextButton(onClick = {
                            try {
                                val mp = MediaPlayer()
                                mp.setDataSource(File(c.filesDir, "probe.wav").absolutePath)
                                mp.prepare(); mp.start()
                                mp.setOnCompletionListener { it.release() }
                            } catch (_: Throwable) {
                                Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("试听") }
                    }
                }
            }
            if (st.running) Text("正在制作整本，等跑完再试。", fontSize = 11.sp, color = Sub)
        }

        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("关于", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text("听本 · 本地离线把小说变成能听的音频。模型后导入、不联网；正文一个字不改。",
                fontSize = 12.5.sp, color = Sub)
        }
        CardBox {
            Row(Modifier.clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                Text("高级（诊断）", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (advanced) "收起" else "展开", fontSize = 12.sp, color = Primary)
            }
            if (advanced) {
                Spacer(Modifier.height(10.dp))
                Text(Prefs.summary(c), fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val info = buildString {
                        append("听本 ").append(com.tingben.app.BuildConfig.VERSION_NAME).append('\n')
                        append("设备 ").append(android.os.Build.MODEL).append(" / Android ")
                            .append(android.os.Build.VERSION.RELEASE).append(" / ")
                            .append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").append('\n')
                        append("引擎 ").append(if (NativeEngine.isAvailable()) NativeEngine.version() else "未装载").append('\n')
                        append("设置 ").append(Prefs.summary(c)).append('\n')
                        append("模型 ").append(ModelStore.files(c).joinToString("、") { it.name }).append('\n')
                        val crash = EventLog.lastCrash()
                        if (crash.isNotBlank()) {
                            append("\n=== 最近一次崩溃 ===\n").append(crash).append('\n')
                        }
                        append("最近事件（不含正文）：\n")
                        append(EventLog.read().lines().takeLast(30).joinToString("\n"))
                    }
                    clipboard.setText(AnnotatedString(info))
                    Toast.makeText(c, "已复制，直接贴给我即可", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth()) { Text("复制诊断信息") }
            }
        }
    }
}

// ── ⑤b 模型页 ────────────────────────────────────────────────
@Composable
private fun ModelScreen(c: Context, refresh: Int,
                        importing: String?, importBytes: Long, importError: String?,
                        onPick: () -> Unit, onBack: () -> Unit, onLibrary: () -> Unit,
                        onChanged: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val files = remember(refresh) { ModelStore.files(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    val allReady = missing.isEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text(if (allReady) "准备好了" else "准备模型", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (allReady) "三个模型都在手机里了，接下来导入一本书。"
            else "还差 ${missing.size} 个文件。下好后在文件管理器里选中，回这里导入。",
            fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))

        // 导入中：明确告诉用户「别关」，而不是一片静默像死机
        if (importing != null) {
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Text("正在复制，别关掉 App", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Primary)
                Spacer(Modifier.height(5.dp))
                Text("已复制 %.2f GB".format(importBytes / 1024f / 1024f / 1024f),
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
                Spacer(Modifier.height(5.dp))
                Text("文件很大，可能要一两分钟。", fontSize = 11.5.sp, color = Sub)
            }
        }
        if (importError != null) {
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Text("上一个文件没导入成功", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(importError, fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(5.dp))
                Text("文件可能没下完整，重新下载再导入一次；也可以先重启 App。", fontSize = 11.5.sp, color = Sub)
            }
        }

        // 下一步永远是一个显眼的主按钮，不让用户自己找路
        if (allReady) {
            Button(onClick = onLibrary, Modifier.fillMaxWidth().height(52.dp)) {
                Text("回书架，导入一本书", fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        val slots = listOf(
            "语言模型" to ModelStore.LLM,
            "声音主文件" to ModelStore.TALKER,
            "声音配套文件" to ModelStore.TOKENIZER
        )
        slots.forEach { (label, name) ->
            val f = files.find { it.name.equals(name, true) }
            CardBox(Modifier.padding(bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                    Text(if (f != null) "已导入 ✓" else "还缺", fontSize = 12.sp,
                        color = if (f != null) Good else Sub)
                }
                if (f != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("%.2f GB".format(f.length() / 1024f / 1024f / 1024f), fontSize = 11.sp, color = Sub)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { ModelStore.delete(c, f); onChanged() }) { Text("删除重试", color = Sub) }
                } else {
                    Spacer(Modifier.height(5.dp))
                    TextButton(onClick = {
                        val url = when (label) {
                            "语言模型" -> "https://hf-mirror.com/bartowski/mlabonne_Qwen3-4B-abliterated-GGUF/resolve/main/mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf"
                            "声音主文件" -> "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-talker-1.7b-voicedesign-Q8_0.gguf"
                            else -> "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/qwen-tokenizer-12hz-Q8_0.gguf"
                        }
                        clipboard.setText(AnnotatedString(url))
                        Toast.makeText(c, "地址已复制，用浏览器下载", Toast.LENGTH_SHORT).show()
                    }) { Text("复制下载地址") }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onPick, enabled = importing == null,
            modifier = Modifier.fillMaxWidth().height(50.dp)) {
            Text(if (allReady) "重新导入某个文件" else "导入模型文件")
        }
        Spacer(Modifier.height(8.dp))
        Text("App 自己不联网下载。复制地址到浏览器或用电脑下好，再点上面导入。", fontSize = 11.5.sp, color = Sub)
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("已导入 ${files.size} 个文件", fontSize = 11.sp, color = Sub)
        }
    }
}
