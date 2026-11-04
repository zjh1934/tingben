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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
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

private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, CAST }

/** R17 · 制作方式是正式选择，不是藏在按钮底下的调试捷径。第三档「改编剧本」= 换逐字契约，单列二期。 */
private enum class ProduceMode { ANNOTATE, DIRECT }

// R11 · 视觉现代化：冷灰底 + 靛蓝主色 + 明确的层级，去掉旧版淡紫调试台观感
private val Bg = Color(0xFFF4F6FB)
private val Card = Color(0xFFFFFFFF)
private val Primary = Color(0xFF4338CA)
private val PrimarySoft = Color(0xFFEEF0FE)
private val Sub = Color(0xFF6B7280)
private val Good = Color(0xFF0E9F6E)
private val Line = Color(0xFFE7EAF2)
private val Warn = Color(0xFFB45309)

class MainActivity : ComponentActivity() {
    /**
     * R11 · 外部「打开方式 / 分享」加不进书架的根因之一：
     * App 已经在后台任务里时，MIUI / 多数文件管理器会把新 intent 投到 onNewIntent，
     * 而不是 onCreate。旧代码只读 onCreate，于是点了「打开方式」什么都没发生、也没提示。
     * 改成 Activity 持有一条流，onCreate / onNewIntent 都往里推，Compose 消费后置空。
     */
    private val incomingFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pushIncoming(intent)
        setContent {
            val incoming by incomingFlow.collectAsState()
            App(incoming = incoming, onConsumed = { incomingFlow.value = null })
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushIncoming(intent)
    }

    private fun pushIncoming(i: android.content.Intent?) {
        if (i == null) return
        val raw = when (i.action) {
            android.content.Intent.ACTION_VIEW, android.content.Intent.ACTION_EDIT -> uriFrom(i)
            android.content.Intent.ACTION_SEND -> uriFrom(i)
                ?: i.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    ?.take(20000)?.let { "text:$it" }
            else -> null
        }
        EventLog.log(EventLog.Kind.MODEL, "EXTERNAL_INTENT",
            "action=${i.action ?: "无"} uri=${raw?.take(90) ?: "无"}")
        // 加时间戳：连续两次分享同一个文件，StateFlow 不会因为「值相同」而吞掉第二次
        // 没抽到 Uri 也给个明确信号，让用户知道「听本收到了但没拿到文件」，而不是静默
        incomingFlow.value = System.currentTimeMillis().toString() + "|" + (raw ?: "empty:")
    }

    /** 三种来源全兜：data / EXTRA_STREAM / clipData。微信、QQ 分享常常只给 clipData。 */
    private fun uriFrom(i: android.content.Intent): String? {
        i.data?.let { return it.toString() }
        @Suppress("DEPRECATION")
        (i.getParcelableExtra<android.os.Parcelable>(android.content.Intent.EXTRA_STREAM)
                as? Uri)?.let { return it.toString() }
        return try {
            i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.toString()
        } catch (_: Throwable) { null }
    }
}

@Composable
private fun App(incoming: String? = null, onConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.LIBRARY) }
    var bookId by remember { mutableStateOf<String?>(null) }
    var lastSelection by remember { mutableStateOf<Set<Int>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var modelsFrom by remember { mutableStateOf(Screen.LIBRARY) }
    var importing by remember { mutableStateOf<String?>(null) }
    val importBytesFlow = remember { MutableStateFlow(0L) }
    val importBytes by importBytesFlow.collectAsState()
    var importError by remember { mutableStateOf<String?>(null) }
    val st by Producer.state.collectAsState()
    val resumeTask by Producer.resume.collectAsState()

    // 系统返回键/手势统一回书架，而不是直接退出 App。
    BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }

    // R10/R11 · 导入逻辑抽成局部函数：主动选择器、兜底选择器、外部打开方式共用一条路。
    fun importBook(uri: Uri?, tag: String = "PICKER") {
        if (uri == null) {
            EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_NONE", "$tag 没拿到文件")
            Toast.makeText(ctx, "没拿到文件，换个方式再试", Toast.LENGTH_SHORT).show()
            return
        }
        // 外部 content:// 授权是一次性的，不 take 会在真正读流时被系统拒掉
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_BEGIN", "$tag ${uri.toString().take(90)}")
        scope.launch(Dispatchers.IO) {
            val result = runCatching { Importer.read(ctx, uri) }
            withContext(Dispatchers.Main) {
                val r = result.getOrNull()
                if (r == null) {
                    EventLog.log(EventLog.Kind.CRASH, "BOOK_IMPORT_FAIL",
                        "$tag ${result.exceptionOrNull()?.message ?: ""}")
                    Toast.makeText(ctx, "这本书没读进来：${result.exceptionOrNull()?.message ?: "换个文件试试"}",
                        Toast.LENGTH_LONG).show()
                } else {
                    BookStore.add(ctx, r.title, r.text)
                    refresh++
                    Toast.makeText(ctx, "已加入书架：${r.title}（${r.kind}）", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importBook(uri, "PICKER")
    }

    // 兜底：某些厂商（小米/MIUI 等）的 DocumentsUI 对 OpenDocument contract 会直接静默返回。
    val fallbackPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val picked = res.data?.data ?: try {
            res.data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } catch (_: Throwable) { null }
        importBook(picked, "FALLBACK")
    }

    fun openBookPicker() {
        EventLog.log(EventLog.Kind.MODEL, "BOOK_PICKER_LAUNCH", "点击导入一本书")
        try {
            pickBook.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            EventLog.log(EventLog.Kind.CRASH, "BOOK_PICKER_FAIL", t.message ?: "")
            try {
                fallbackPicker.launch(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                })
            } catch (t2: Throwable) {
                Toast.makeText(ctx, "系统文件选择器打不开：${t2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── 外部打开方式 / 分享：onCreate 和 onNewIntent 都会推进来，只消费一次 ──
    var pendingIncoming by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(incoming) { if (incoming != null) pendingIncoming = incoming }
    LaunchedEffect(pendingIncoming) {
        val full = pendingIncoming ?: return@LaunchedEffect
        pendingIncoming = null
        onConsumed()
        val raw = full.substringAfter('|', full)
        when {
            raw == "empty:" -> {
                EventLog.log(EventLog.Kind.CRASH, "EXTERNAL_IMPORT_EMPTY", "收到分享但没拿到 Uri/文字")
                Toast.makeText(ctx, "听本收到打开了，但对方没给文件；试试先在文件管理器里长按分享",
                    Toast.LENGTH_LONG).show()
            }
            raw.startsWith("text:") -> {
                val text = raw.removePrefix("text:")
                if (text.isNotBlank()) {
                    BookStore.add(ctx, "分享的文字", text)
                    refresh++
                    screen = Screen.LIBRARY
                    Toast.makeText(ctx, "分享的文字已加入书架", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                importBook(android.net.Uri.parse(raw), "EXTERNAL")
                screen = Screen.LIBRARY
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
        // 顶栏：产品名 + 一句定位 + 一个主动作；刷新按钮常驻书架，下拉也能刷
        Row(
            Modifier.fillMaxWidth().background(Card).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("听本", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Primary)
                Text("把小说变成能听的音频", fontSize = 11.sp, color = Sub)
            }
            if (screen == Screen.LIBRARY) {
                FilledTonalButton(
                    onClick = { refresh++; Toast.makeText(ctx, "书架已刷新", Toast.LENGTH_SHORT).show() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("⟳ 刷新", fontSize = 12.5.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { openBookPicker() },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("＋ 导入", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; screen = Screen.PRODUCE },
                    onModels = { modelsFrom = Screen.LIBRARY; screen = Screen.MODELS },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ },
                    producing = st.running, progressText = if (st.total > 0) "${st.done}/${st.total} 块" else "",
                    onProgress = { screen = Screen.PROGRESS },
                    pendingTask = resumeTask?.takeIf { it.phase != "done" },
                    onTasks = { screen = Screen.TASKS })
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { screen = Screen.LIBRARY },
                    onProgress = { screen = Screen.PROGRESS },
                    onDone = { screen = Screen.AUDIO },
                    onStarted = { lastSelection = it })
                Screen.PROGRESS -> ProgressScreen(ctx,
                    onBack = { screen = Screen.LIBRARY },
                    onResume = {
                        val t = resumeTask
                        val b = if (t != null) BookStore.list(ctx).find { x -> x.id == t.bookId }
                                else bookId?.let { BookStore.list(ctx).find { x -> x.id == it } }
                        if (b != null) {
                            val sel = t?.only ?: lastSelection
                            bookId = b.id; lastSelection = sel
                            Producer.start(ctx, b, sel, t?.direct ?: false,
                                if (t != null) t.think else null)
                        }
                    })
                Screen.TASKS -> TaskCenterScreen(ctx, refresh,
                    onProgress = { screen = Screen.PROGRESS },
                    onAudio = { screen = Screen.AUDIO; refresh++ },
                    onLibrary = { screen = Screen.LIBRARY },
                    onResume = { t ->
                        val b = BookStore.list(ctx).find { x -> x.id == t.bookId }
                        if (b != null) {
                            bookId = b.id; lastSelection = t.only
                            Producer.start(ctx, b, t.only, t.direct, t.think)
                            screen = Screen.PROGRESS
                        } else {
                            Toast.makeText(ctx, "这本书已经从书架删掉了，任务没法续跑", Toast.LENGTH_LONG).show()
                        }
                    })
                Screen.AUDIO -> AudioScreen(ctx, refresh)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; screen = Screen.MODELS },
                    onLibrary = { screen = Screen.LIBRARY },
                    onCast = { screen = Screen.CAST })
                Screen.CAST -> CastScreen(ctx, onBack = { screen = Screen.SETTINGS })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { screen = modelsFrom },
                    onLibrary = { screen = Screen.LIBRARY },
                    onChanged = { refresh++ })
            }
        }

        // 跨页面常驻：正在跑 / 进程重启后留下的未完成任务，任何一屏都点得到
        val pendingTask = resumeTask?.takeIf { it.phase != "done" && !st.running }
        val hasTask = st.running || st.output != null || pendingTask != null
        if (hasTask) {
            Row(
                Modifier.fillMaxWidth().background(
                    if (st.running) PrimarySoft else if (pendingTask != null) Color(0xFFFFF3E0) else Color(0xFFE7F4EC))
                    .clickable {
                        screen = when {
                            st.running -> Screen.PROGRESS
                            pendingTask != null -> Screen.TASKS
                            else -> Screen.AUDIO
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).background(
                    if (st.running) Primary else if (pendingTask != null) Warn else Good,
                    RoundedCornerShape(50)))
                Spacer(Modifier.width(9.dp))
                Text(
                    when {
                        st.running -> if (st.total > 0) "正在制作 · ${st.done}/${st.total} 块" else "正在制作…"
                        pendingTask != null -> "《${pendingTask.title}》有没做完的任务 · 点这里继续"
                        else -> "做好了，点这里去听"
                    },
                    fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    color = if (st.running) Primary else if (pendingTask != null) Warn else Good,
                    modifier = Modifier.weight(1f))
                Text(if (pendingTask != null) "去任务 ›" else "查看 ›",
                    fontSize = 12.5.sp, color = Primary)
            }
        }
        // 底栏 4 个 Tab：长任务有自己独立的区域，不再藏在书架/设置里
        Row(Modifier.fillMaxWidth().navigationBarsPadding().background(Card).padding(vertical = 6.dp)) {
            TabButton("书架", screen == Screen.LIBRARY || screen == Screen.PRODUCE,
                Modifier.weight(1f)) { screen = Screen.LIBRARY }
            TabButton("任务", screen == Screen.TASKS || screen == Screen.PROGRESS,
                Modifier.weight(1f)) { screen = Screen.TASKS }
            TabButton("音频", screen == Screen.AUDIO, Modifier.weight(1f)) { screen = Screen.AUDIO; refresh++ }
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
    Column(mod.clickable { click() }.padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.background(if (on) PrimarySoft else Color.Transparent, RoundedCornerShape(50))
                .padding(horizontal = 13.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = if (on) Primary else Sub)
        }
    }
}

@Composable
private fun CardBox(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(mod.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

// ── ① 书架 ──────────────────────────────────────────────────
@Composable
private fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit,
                           onModels: () -> Unit, onImportBook: () -> Unit,
                           onChanged: () -> Unit, producing: Boolean,
                           progressText: String, onProgress: () -> Unit,
                           pendingTask: TaskInfo?, onTasks: () -> Unit) {
    val books = remember(refresh) { BookStore.list(c) }
    val ready = remember(refresh) { ModelStore.ready(c) }
    val clipboard = LocalClipboardManager.current
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteTitle by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // R11 · 书架刷新：下拉手势 + 顶栏 ⟳ 按钮双保险。放在固定小条上，避免和列表滚动抢手势。
        var pulled by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxWidth().height(if (pulled) 42.dp else 30.dp)
                .background(if (pulled) PrimarySoft else Color.Transparent, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = {
                            if (acc > 55f) {
                                onChanged()
                                Toast.makeText(c, "书架已刷新", Toast.LENGTH_SHORT).show()
                            }
                            pulled = false; acc = 0f
                        },
                        onVerticalDrag = { _, dy ->
                            if (dy > 0) { acc += dy; pulled = acc > 20f }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(if (pulled) "松开即可刷新" else "下拉刷新 · 或点右上角 ⟳ 刷新",
                fontSize = 11.5.sp, color = if (pulled) Primary else Sub)
        }
        Spacer(Modifier.height(10.dp))
        Text("把小说变成能听的音频", fontSize = 21.sp, fontWeight = FontWeight.Bold)
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
        } else if (pendingTask != null) {
            // 进程被杀 / 用户退出后回来：任务不是没了，是在「任务」页等着续跑
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("有没做完的任务", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Warn)
                Spacer(Modifier.height(4.dp))
                Text("《${pendingTask.title}》上次没跑完，已做好的块都在。", fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(9.dp))
                Button(onClick = onTasks, Modifier.fillMaxWidth().height(44.dp)) {
                    Text("去任务页继续")
                }
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
                Text("下一步：导入 txt / md / epub，或在别的 App 里用「打开方式」选听本，也可以直接粘贴文字。",
                    fontSize = 12.sp, color = Sub)
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
                Text("支持 txt / md / epub；别的 App 里点「打开方式 → 听本」也能直接加进来。",
                    fontSize = 12.sp, color = Sub)
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
                          onProgress: () -> Unit, onDone: () -> Unit,
                          onStarted: (Set<Int>?) -> Unit) {
    val book = remember(id, refresh) { id?.let { BookStore.list(c).find { b -> b.id == it } } }
    val st by Producer.state.collectAsState()
    val chapters = remember(book) { book?.let { ChapterSplitter.split(BookStore.read(c, it)).first } ?: emptyList() }
    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    var cacheTick by remember { mutableStateOf(0) }
    val thinkOn = remember(refresh) { Prefs.thinking(c) }
    // R17 · 进页面时对齐已有任务：书正以「直接朗读」跑着，别把高亮停在标注档，
    // 否则用户看到的模式和任务页/断点续跑用的不是同一套。
    var mode by remember(book, refresh) {
        val t = TaskResume.load(c)
        mutableStateOf(if (t != null && t.bookId == id && t.direct)
            ProduceMode.DIRECT else ProduceMode.ANNOTATE)
    }
    val directMode = mode == ProduceMode.DIRECT
    // 两种模式的缓存分桶不同，hasCached 必须跟着 mode 走，
    // 不然切到直接朗读还会显示「继续制作」，点的却是标注档的旧缓存。
    val hasCached = remember(book, refresh, selected, cacheTick, thinkOn, mode) {
        book?.let { Producer.hasProgress(c, it.id, directMode, thinkOn,
            if (selected.isEmpty() || selected.size == chapters.size) null else selected) } ?: false
    }
    if (book == null) {
        Column(Modifier.padding(16.dp)) { Text("这本书不在了"); TextButton(onClick = onBack) { Text("返回书架") } }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text(book.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
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
            Text("制作方式", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            ProduceMode.values().forEach { m ->
                val on = mode == m
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (on) PrimarySoft else Color.Transparent,
                            RoundedCornerShape(14.dp))
                        .clickable { mode = m }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = on, onClick = { mode = m })
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (m == ProduceMode.DIRECT) "直接朗读" else "标注后合成",
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            if (m == ProduceMode.DIRECT)
                                "原文照读，不过语言模型；不分角色、不加语气，几分钟就能出声"
                            else
                                "先逐块判断谁说的、怎么读，再合成；手机会慢不少，但更有戏",
                            fontSize = 11.5.sp, color = Sub
                        )
                    }
                }
                if (m != ProduceMode.DIRECT) Spacer(Modifier.height(6.dp))
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
            // R10 · epub 常有几十上百章，之前写死 2..40 导致长书根本没法选单章。
            // 改成默认只列 20 章 + 「显示全部」，几百章也不刷屏。
            var showAllChapters by remember(book) { mutableStateOf(false) }
            val visibleChapters = if (chapters.size > 20 && !showAllChapters)
                chapters.take(20) else chapters
            if (chapters.size >= 2) {
                Spacer(Modifier.height(8.dp))
                visibleChapters.forEach { ch ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (ch.index in selected) selected - ch.index else selected + ch.index
                    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ch.index in selected, onCheckedChange = {
                            selected = if (it) selected + ch.index else selected - ch.index
                        })
                        Spacer(Modifier.width(6.dp))
                        Text("${ch.index + 1}. ${ch.title.take(28)}", fontSize = 12.5.sp, maxLines = 1)
                    }
                }
                if (chapters.size > 20) {
                    TextButton(onClick = { showAllChapters = !showAllChapters }) {
                        Text(if (showAllChapters) "收起" else "显示全部 ${chapters.size} 章")
                    }
                }
            } else {
                Text("这本书只识别出 1 章，按章制作不可用；可改用整本或换 txt。",
                    fontSize = 11.sp, color = Sub)
            }
        }

        Button(
            onClick = {
                val sel = if (selected.isEmpty() || selected.size == chapters.size) null else selected
                onStarted(sel)
                Producer.start(c, book, sel, directRead = directMode)
                onProgress()
            },
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (st.running) "正在制作…"
                else if (hasCached) "继续制作"
                else if (directMode) "开始直接朗读"
                else "开始制作",
                fontSize = 16.sp
            )
        }

        if (hasCached && !st.running) {
            TextButton(onClick = {
                // 清的是当前模式的那一桶，不能切到直接朗读却还去清标注缓存。
                Producer.clearProgress(c, book.id, directMode, thinkOn,
                    if (selected.isEmpty() || selected.size == chapters.size) null else selected)
                cacheTick++
            }, Modifier.fillMaxWidth()) { Text("从头重做（清掉已做的块）", fontSize = 12.sp) }
            Text("上次没跑完，直接点「继续制作」会从停下的地方接着跑，不重算已做好的块。",
                fontSize = 11.sp, color = Sub, modifier = Modifier.padding(horizontal = 6.dp))
        }

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
private fun ProgressScreen(c: Context, onBack: () -> Unit, onResume: () -> Unit) {
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

    // R11 · 下滑收敛 / 上滑展开：核心进度永远在，细节可以整块收起来
    var expanded by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("制作进度", fontSize = 23.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 ↑" else "展开 ↓", fontSize = 12.5.sp, color = Primary)
            }
        }
        // 手势条独立成一块，避免和页面滚动抢事件；也留了上面的按钮兜底
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .background(PrimarySoft, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = {
                            if (acc > 45f) expanded = false
                            else if (acc < -45f) expanded = true
                            acc = 0f
                        },
                        onVerticalDrag = { _, dy -> acc += dy }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(if (expanded) "下滑收起细节 · 上滑可再展开" else "上滑展开细节",
                fontSize = 11.sp, color = Primary)
        }
        Spacer(Modifier.height(14.dp))
        CardBox {
            // 大号百分比：不用读小字也知道还剩多少
            Text(if (st.total > 0) "${(pct * 100).toInt()}%" else "—",
                fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Primary)
            Spacer(Modifier.height(4.dp))
            Text(st.stage, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth().height(8.dp))
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
        if (expanded) {
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
                        if (done || active) Primary else Line, RoundedCornerShape(50)))
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
        }
        Spacer(Modifier.height(18.dp))
        // 点一次就锁住按钮：真机上用户狂点 10 次「停止」反而说明它没生效，
        // 现在按钮立刻变「正在停止…」，等 native 退出再解锁。
        var stopping by remember { mutableStateOf(false) }
        LaunchedEffect(st.running) { if (!st.running) stopping = false }
        if (st.running) {
            Button(
                onClick = { stopping = true; Producer.stop() },
                enabled = !stopping,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A4A4A)),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) { Text(if (stopping) "正在停止…" else "停止") }
            Spacer(Modifier.height(7.dp))
            Text("已做好的块会留着，不会白跑。点停止后回来点「继续制作」接着跑。",
                fontSize = 11.sp, color = Sub, modifier = Modifier.padding(horizontal = 6.dp))
        } else {
            if (st.stage == "已停止") {
                Button(onClick = onResume, Modifier.fillMaxWidth().height(48.dp)) {
                    Text("继续制作（从停下的地方接着跑）")
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("回书架，随时能再进来看") }
        }
    }
}

// ── ③b 任务中心：长任务单独一个区域管，不藏在书架或设置里 ──────────
@Composable
private fun TaskCenterScreen(c: Context, refresh: Int, onProgress: () -> Unit,
                             onAudio: () -> Unit, onLibrary: () -> Unit,
                             onResume: (TaskInfo) -> Unit) {
    val st by Producer.state.collectAsState()
    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick.value++ } }
    // 每次进页面 / 每秒都从磁盘读，不依赖进程内存
    val saved = remember(refresh, tick.value / 2) { TaskResume.load(c) }
    val t = saved
    val real = remember(t, refresh) { t?.let { TaskResume.realProgress(c, it) } ?: 0 }
    val bookExists = remember(t, refresh) {
        t?.let { BookStore.list(c).any { b -> b.id == it.bookId } } ?: false
    }
    val modeText = when {
        t == null -> ""
        t.direct -> "直接朗读"
        t.think -> "标注 · 思考档"
        else -> "标注 · 快速档"
    }
    val scopeText = t?.let {
        if (it.only == null) "整本" else "选中 ${it.only!!.size} 章"
    } ?: ""

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("任务", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("长任务都放在这里。退出或被系统杀掉不会丢，回来点继续就行。",
            fontSize = 12.5.sp, color = Sub)
        Spacer(Modifier.height(14.dp))

        if (t == null) {
            CardBox {
                Text("还没有任务", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(5.dp))
                Text("去书架选一本书点「开始制作」，这里就会出现它的进度、状态和续跑入口。",
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onLibrary, Modifier.fillMaxWidth()) { Text("去书架") }
            }
        } else {
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    val badge = when {
                        st.running -> "进行中"
                        t.phase == "done" -> "已完成"
                        t.phase == "stopped" -> "已停止"
                        else -> "已中断"
                    }
                    Text(badge, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                        color = if (st.running) Primary else if (t.phase == "done") Good else Warn)
                }
                Spacer(Modifier.height(6.dp))
                Text("$modeText · $scopeText", fontSize = 12.sp, color = Sub)
                val total = maxOf(t.total, real)
                if (total > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (if (st.running) st.done else real).toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(7.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("已做好 ${if (st.running) st.done else real}/$total 块",
                        fontSize = 12.sp, color = Sub)
                }
                Spacer(Modifier.height(5.dp))
                Text("最后更新 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(t.updatedAt)), fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(12.dp))

                when {
                    st.running -> {
                        Button(onClick = onProgress, Modifier.fillMaxWidth().height(46.dp)) {
                            Text("查看实时进度")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("现在正在算。可以切走、锁屏；若进程被系统回收，已做好的块仍在。",
                            fontSize = 11.sp, color = Sub)
                    }
                    t.phase == "done" -> {
                        Button(onClick = onAudio, Modifier.fillMaxWidth().height(46.dp)) {
                            Text("去「我的音频」试听")
                        }
                    }
                    !bookExists -> {
                        Text("这本书已经从书架删掉了，任务没法续跑。", fontSize = 12.sp, color = Warn)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                        }, Modifier.fillMaxWidth()) { Text("清除这条失效任务") }
                    }
                    else -> {
                        Button(onClick = { onResume(t) }, Modifier.fillMaxWidth().height(48.dp)) {
                            Text("继续制作（接着已做好的块跑）", fontSize = 15.sp)
                        }
                        Spacer(Modifier.height(7.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                                val b = BookStore.list(c).find { x -> x.id == t.bookId }
                                if (b != null) { Producer.start(c, b, t.only, t.direct, t.think); onProgress() }
                            }, Modifier.weight(1f)) { Text("从头重做", fontSize = 12.sp) }
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                            }, Modifier.weight(1f)) { Text("放弃任务", fontSize = 12.sp, color = Warn) }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text("续跑沿用原档位与选章，已标好的块和已生成的声音直接跳过，不重算。",
                            fontSize = 11.sp, color = Sub)
                    }
                }
            }

            // 最近完成：给「做完了但忘了在哪听」一个落脚点
            if (t.phase == "done" && t.output != null) {
                Spacer(Modifier.height(12.dp))
                CardBox {
                    Text("最近完成", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(t.title, fontSize = 12.sp, color = Sub)
                    Spacer(Modifier.height(9.dp))
                    TextButton(onClick = onAudio, Modifier.fillMaxWidth()) { Text("去试听 ›") }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        CardBox {
            Text("关于后台运行", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("手机系统不允许 App 在后台长时间占着 CPU 跑模型，锁屏或切出去久了进程会被回收，"
                    + "这在 Android 上是系统行为，不是软件坏了。\n\n"
                    + "听本的做法是：每完成一块就把结果写进手机，任务状态单独记一份。"
                    + "回来后在这里点「继续制作」，从停下的那块接着算，前面几小时的活不会白干。",
                fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
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
        Text("我的音频", fontSize = 23.sp, fontWeight = FontWeight.Bold)
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
private fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit,
                            onLibrary: () -> Unit, onCast: () -> Unit) {
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
        Text("设置", fontSize = 23.sp, fontWeight = FontWeight.Bold)
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
                Text("这台手机给不起模型要的思考长度：开了之后推理会吃掉大半预算，JSON 常被截断，"
                        + "整本大概率不分角色、全部降级直读，还慢几倍。建议只在「试一小块看推理」时开，"
                        + "正式做书请关掉。",
                    fontSize = 11.sp, color = Color(0xFF8A4A4A))
            }
        }

        // R11 · 非语言表达：哭腔 / 笑 / 叹气 / 抽泣。只增强导演指令，正文一字不改。
        CardBox(Modifier.padding(bottom = 11.dp)) {
            val nv = remember(cfg) { Prefs.nonVerbal(c) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("哭声、笑声、叹气", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(if (nv) "开着：原文写到哭 / 笑 / 叹气时，让声音带出哭腔、气声笑或叹息。"
                         else "关着：一律平稳朗读。",
                        fontSize = 11.5.sp, color = Sub)
                }
                Switch(checked = nv, onCheckedChange = {
                    Prefs.setNonVerbal(c, it); cfg++
                    EventLog.log(EventLog.Kind.PIPELINE, "NONVERBAL_MODE", if (it) "on" else "off")
                })
            }
            Spacer(Modifier.height(6.dp))
            Text("不是插入音效，也不是 [laughter] 这类标签（本模型会把标签当字念）。"
                 + "正文一个字不改，只给声音模型加一句导演指令；效果取决于模型，可能只是语气更贴近。",
                fontSize = 11.sp, color = Sub)
        }

        // R14 · 声音：固定音色层 + 角色映射 + 9 个采样参数（默认值来自 qwentts.cpp，非手搓）
        CardBox(Modifier.padding(bottom = 11.dp)) {
            val knobs = remember(cfg) { Prefs.ttsKnobs(c) }
            val castOn = remember(cfg) { Prefs.castEnabled(c) }
            var descDraft by remember(cfg) { mutableStateOf(Prefs.voiceDescription(c)) }
            var samplingOpen by remember { mutableStateOf(false) }
            fun putKnobs(k: TtsKnobs) { Prefs.setTtsKnobs(c, k); cfg++ }

            Text("声音", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(5.dp))
            Text("给整本书定一个固定的嗓子，角色不再每块自己变声。留空 = 沿用原来的默认声音。",
                fontSize = 11.5.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = descDraft,
                onValueChange = { descDraft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 3,
                placeholder = { Text("例如：沉稳的青年男声，中低音，语速适中，吐字清晰", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    Prefs.setVoiceDescription(c, descDraft); cfg++
                    EventLog.log(EventLog.Kind.ENGINE, "VOICE_DESC",
                        if (descDraft.isBlank()) "清空，回默认音色" else "已更新")
                }) { Text("保存音色", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCast) { Text("角色音色 →", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色映射", fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
                Switch(checked = castOn, onCheckedChange = {
                    Prefs.setCastEnabled(c, it); cfg++
                })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.clickable { samplingOpen = !samplingOpen }, verticalAlignment = Alignment.CenterVertically) {
                Text("声音采样（进阶）", fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(if (samplingOpen) "收起" else "展开", fontSize = 12.sp, color = Primary)
            }
            if (samplingOpen) {
                Spacer(Modifier.height(5.dp))
                Text("默认值逐字取自 qwentts.cpp，改了之后已生成的旧音频会自动作废重做。",
                    fontSize = 11.sp, color = Sub)
                SettingOption("随机种子 seed（固定可复现）", listOf("42", "1234", "随机"),
                    listOf(42L, 1234L, -1L).indexOf(knobs.seed).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(seed = listOf(42L, 1234L, -1L)[i]))
                }
                SettingOption("温度 temperature", listOf("0.6", "0.9", "1.1"),
                    listOf(0.6f, 0.9f, 1.1f).indexOf(knobs.temperature).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(temperature = listOf(0.6f, 0.9f, 1.1f)[i]))
                }
                SettingOption("top_k（0 = 不限）", listOf("0", "20", "50"),
                    listOf(0, 20, 50).indexOf(knobs.topK).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(topK = listOf(0, 20, 50)[i]))
                }
                SettingOption("top_p", listOf("0.8", "0.95", "1.0"),
                    listOf(0.8f, 0.95f, 1.0f).indexOf(knobs.topP).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(topP = listOf(0.8f, 0.95f, 1.0f)[i]))
                }
                SettingOption("重复惩罚", listOf("1.0", "1.05", "1.2"),
                    listOf(1.0f, 1.05f, 1.2f).indexOf(knobs.repetitionPenalty).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(repetitionPenalty = listOf(1.0f, 1.05f, 1.2f)[i]))
                }
                SettingOption("单次最长 token", listOf("1024", "2048", "3072"),
                    listOf(1024, 2048, 3072).indexOf(knobs.maxNewTokens).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(maxNewTokens = listOf(1024, 2048, 3072)[i]))
                }
                SettingOption("副解码器温度", listOf("0.6", "0.9", "1.1"),
                    listOf(0.6f, 0.9f, 1.1f).indexOf(knobs.subTemperature).coerceAtLeast(0)) { i ->
                    putKnobs(knobs.copy(subTemperature = listOf(0.6f, 0.9f, 1.1f)[i]))
                }
                SettingOption("副解码器 top_k / top_p", listOf("50/1.0", "20/0.95", "0/1.0"),
                    when { knobs.subTopK == 50 && knobs.subTopP == 1.0f -> 0
                           knobs.subTopK == 20 && knobs.subTopP == 0.95f -> 1
                           else -> 2 }) { i ->
                    val (k, p) = when (i) { 0 -> 50 to 1.0f; 1 -> 20 to 0.95f; else -> 0 to 1.0f }
                    putKnobs(knobs.copy(subTopK = k, subTopP = p))
                }
                TextButton(onClick = { Prefs.resetTtsKnobs(c); cfg++ }) {
                    Text("恢复上游默认值", fontSize = 12.sp)
                }
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
            SettingOption("运算线程", listOf("2", "4", "6", "8"),
                listOf(2, 4, 6, 8).indexOf(threads).coerceAtLeast(0)) { i ->
                Prefs.setThreads(c, listOf(2, 4, 6, 8)[i]); cfg++
            }
            SettingOption("随机度（思考关时有效）", listOf("0 稳", "0.3", "0.6"),
                listOf(0f, 0.3f, 0.6f).indexOf(temp).coerceAtLeast(0)) { i ->
                Prefs.setTemperature(c, listOf(0f, 0.3f, 0.6f)[i]); cfg++
            }
            Text("8192 在部分手机上会因内存不足崩，不确定就别动。", fontSize = 11.sp, color = Sub)
        }

        // 音频模型调试：真合成，不是 Toast 假按钮；第二个按钮直接验哭/笑/叹气效果
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("音频调试", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("不跑整本，只让声音模型读一句，确认能出声、能带情绪。", fontSize = 11.5.sp, color = Sub)
            Spacer(Modifier.height(9.dp))
            var probeFile by remember { mutableStateOf("") }
            val enabled = !probing && !st.running && missing.isEmpty()

            fun doProbe(text: String, instruct: String, outName: String) {
                probing = true; probeResult = "正在合成…"; probeFile = ""
                probeScope.launch(Dispatchers.IO) {
                    val res = runCatching {
                        if (!NativeEngine.isAvailable()) throw IllegalStateException(
                            NativeEngine.shortReason().ifBlank { "本地引擎没装载，请重启 App" })
                        val tk = ModelStore.talker(c) ?: throw IllegalStateException("缺声音主文件")
                        val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺声音配套文件")
                        val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                        if (h == 0L) throw IllegalStateException("声音模型打不开")
                        try {
                            val f = File(c.filesDir, outName)
                            val code = NativeEngine.ttsSynthesize(h, text, instruct, null, "auto",
                                f.absolutePath, Prefs.ttsKnobs(c))
                            if (code != 0 || !f.exists() || f.length() <= 44)
                                throw IllegalStateException("没生成声音，code=$code")
                            f.absolutePath
                        } finally { NativeEngine.ttsFree(h) }
                    }
                    withContext(Dispatchers.Main) {
                        probing = false
                        val path = res.getOrNull()
                        probeFile = path ?: ""
                        probeResult = if (path != null)
                            "出声了 ✓ ${File(path).length()} 字节，点右边试听"
                        else "失败：" + (res.exceptionOrNull()?.message ?: "未知原因")
                        res.exceptionOrNull()?.let {
                            EventLog.log(EventLog.Kind.ENGINE, "TTS_PROBE_FAIL", it.message ?: "")
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { doProbe("你好，这是一句试音。", "平稳、清晰地叙述", "probe.wav") },
                    enabled = enabled, modifier = Modifier.weight(1f)
                ) { Text(if (probing) "…" else "试合成一句", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        doProbe(
                            "她终于忍不住笑出声，又轻轻叹了口气，眼眶却红了。",
                            "读到笑时带出一点轻声的笑，读到叹气时先有一口听得见的呼气，"
                                + "结尾带压抑的哭腔、声音微颤，用气声，语气克制；不要另加字词",
                            "probe_emote.wav")
                    },
                    enabled = enabled, modifier = Modifier.weight(1f)
                ) { Text(if (probing) "…" else "试哭笑叹气", fontSize = 12.sp) }
            }
            if (probeResult.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(probeResult, fontSize = 11.5.sp, color = Sub, modifier = Modifier.weight(1f))
                    if (probeResult.startsWith("出声了") && probeFile.isNotBlank()) {
                        TextButton(onClick = {
                            try {
                                val mp = MediaPlayer()
                                mp.setDataSource(File(probeFile).absolutePath)
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
        Text(if (allReady) "准备好了" else "准备模型", fontSize = 23.sp, fontWeight = FontWeight.Bold)
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

// ── ⑤b 角色音色：字段抄 ReaderVoice CharacterManagerActivity / CharacterProfileStore，
//      别名检测抄 novel-voice-studio/generate_personas.py ──────────────────────
@Composable
private fun CastScreen(c: Context, onBack: () -> Unit) {
    var ver by remember { mutableStateOf(0) }
    // 每次进来扫一遍已落盘的标注，自动把「李寻欢 / 小李探花」并成同一角色
    val entries = remember(ver) { VoiceCast.reconcile(c) }
    val global = remember(ver) { Prefs.voiceDescription(c) }
    val observedCount = remember(ver) { VoiceCast.collect(c).size }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("角色音色", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回设置", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("给每个角色写一句声音描述，全书都用这个嗓子，不再每块重新变声。"
                + "留空的角色沿用设置页里的全局音色。改过之后，已生成的旧音频会自动作废重做。",
            fontSize = 12.sp, color = Sub)
        Spacer(Modifier.height(6.dp))
        Text("当前模型是 VoiceDesign：靠文字描述造声，没有预置音色表，也不需要重新下载模型。"
                + "识别到 $observedCount 种说话人标签，归成 ${entries.size} 个角色。",
            fontSize = 11.sp, color = Sub)
        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            CardBox {
                Text("还没有可分配的角色", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(5.dp))
                Text("先跑一次「开始制作」并让语言模型标完几块，这里会自动列出书里出现的人物。"
                        + "也可以先在设置页填一个全局音色，整本立刻生效。",
                    fontSize = 12.sp, color = Sub)
            }
        }

        entries.forEach { e ->
            var draft by remember(e.id, ver) { mutableStateOf(e.description) }
            var mergeOpen by remember(e.id) { mutableStateOf(false) }
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.canonical, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    if (e.locked) Text("已锁定", fontSize = 11.sp, color = Good)
                }
                Spacer(Modifier.height(4.dp))
                Text("出场约 ${e.count} 段" +
                        if (e.aliases.isEmpty()) "" else " · 别名：" + e.aliases.joinToString("、"),
                    fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3,
                    placeholder = { Text("例如：清亮的少女声，语速偏快，尾音上扬", fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp)
                )
                Spacer(Modifier.height(6.dp))
                Text("实际生效：" + VoiceProfile.identity(
                    if (draft.isBlank()) global else draft, null),
                    fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        VoiceCast.setOverride(c, e.id, draft, true); ver++
                        EventLog.log(EventLog.Kind.ENGINE, "VOICE_CAST", "${e.canonical} 已保存并锁定")
                    }) { Text("保存并锁定", fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { mergeOpen = !mergeOpen }) { Text("并入其他角色", fontSize = 12.sp) }
                }
                if (mergeOpen) {
                    Spacer(Modifier.height(5.dp))
                    Text("同一个人的不同称呼点这里合并，合并后共用同一份音色描述（别名链）。",
                        fontSize = 11.sp, color = Sub)
                    entries.filter { it.id != e.id }.forEach { t ->
                        TextButton(onClick = {
                            VoiceCast.merge(c, e.id, t.id); mergeOpen = false; ver++
                            EventLog.log(EventLog.Kind.ENGINE, "VOICE_ALIAS", "${e.canonical} → ${t.canonical}")
                        }) { Text("并入「${t.canonical}」", fontSize = 12.sp, color = Primary) }
                    }
                }
            }
        }

        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { VoiceCast.clear(c); ver++ }) {
                Text("清空角色表（不影响已导入的模型和书）", fontSize = 12.sp, color = Warn)
            }
        }
    }
}
