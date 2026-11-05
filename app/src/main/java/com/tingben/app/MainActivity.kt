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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.tingben.app.diag.AuditLog
import com.tingben.app.diag.EventLog
import com.tingben.app.diag.RawDump
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, CAST, MIXER }

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
        // R25 · 真机日志：冷启动 ACTION_MAIN 也被当成「收到分享但没拿到 Uri」记进 CRASH。
        // 只有 VIEW / EDIT / SEND 是外部导入，普通启动根本不推 incoming。
        val act = i.action
        if (act != android.content.Intent.ACTION_VIEW &&
            act != android.content.Intent.ACTION_EDIT &&
            act != android.content.Intent.ACTION_SEND) return
        val raw = when (act) {
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

    // R19 · 长任务在跑时保持屏幕常亮：只加前台窗口 flag，零权限、零 WakeLock。
    // ⚠️ 这不等于保活：锁屏/切后台后 CPU 仍会睡、进程仍可能被回收。
    // 后台续跑要走前台服务 + PARTIAL_WAKE_LOCK + 电池白名单，单列下一轮，不混在这轮验。
    val act = ctx as? android.app.Activity
    DisposableEffect(st.running) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (st.running) act?.window?.addFlags(flag) else act?.window?.clearFlags(flag)
        onDispose { act?.window?.clearFlags(flag) }
    }

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
                    val before = BookStore.list(ctx).size
                    BookStore.add(ctx, r.title, r.text)
                    val added = BookStore.list(ctx).size > before
                    refresh++
                    Toast.makeText(ctx,
                        if (added) "已加入书架：${r.title}（${r.kind}）"
                        else "这本书已经在书架上了：${r.title}",
                        Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(ctx, "文字已就绪（相同内容不会重复添加）", Toast.LENGTH_SHORT).show()
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

    // U1 · 顶层目的地归属集中到 chromeOf()，顶栏/底栏只消费规格，不再各自写 screen == ...
    val chrome = chromeOf(screen)
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        AppTopBar(
            spec = chrome,
            onMenu = { idx ->
                screen = when (idx) {
                    0 -> Screen.SETTINGS
                    1 -> Screen.MIXER
                    2 -> Screen.MODELS
                    3 -> Screen.CAST
                    else -> Screen.SETTINGS
                }
                if (idx == 0) refresh++
                if (idx == 2) modelsFrom = Screen.SETTINGS
            },
            onImport = { openBookPicker() },
            surface = Card,
            primary = Primary
        )
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
                    onDone = { screen = Screen.AUDIO; refresh++ },
                    onStarted = { lastSelection = it },
                    onGoCast = { screen = Screen.CAST })
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
                                if (t != null) t.think else null,
                                t?.config ?: ProduceConfig())
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
                            Producer.start(ctx, b, t.only, t.direct, t.think, t.config)
                            screen = Screen.PROGRESS
                        } else {
                            Toast.makeText(ctx, "这本书已经从书架删掉了，任务没法续跑", Toast.LENGTH_LONG).show()
                        }
                    })
                Screen.AUDIO -> AudioScreen(ctx, refresh)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; screen = Screen.MODELS },
                    onLibrary = { screen = Screen.LIBRARY },
                    onCast = { screen = Screen.CAST },
                    onMixer = { screen = Screen.MIXER })
                Screen.CAST -> CastScreen(ctx, onBack = { screen = Screen.SETTINGS })
                Screen.MIXER -> MixerScreen(ctx, onBack = { screen = Screen.SETTINGS })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { screen = modelsFrom },
                    onLibrary = { screen = Screen.LIBRARY },
                    onChanged = { refresh++ })
            }
        }

        // 跨页面常驻任务条：上扒展开 / 下扒收起，点整卡不再跳页
        val pendingTask = resumeTask?.takeIf { it.phase != "done" && !st.running }
        val hasTask = st.running || st.output != null || pendingTask != null
        var taskExpanded by remember { mutableStateOf(false) }
        if (hasTask) {
            val barBg = if (st.running) PrimarySoft
                else if (pendingTask != null) Color(0xFFFFF3E0) else Color(0xFFE7F4EC)
            Column(
                Modifier.fillMaxWidth().background(barBg)
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = {
                                if (acc < -24f) taskExpanded = true
                                else if (acc > 24f) taskExpanded = false
                                acc = 0f
                            },
                            onVerticalDrag = { _, dy -> acc += dy }
                        )
                    }
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { taskExpanded = !taskExpanded }
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
                            pendingTask != null -> "《${pendingTask.title}》有没做完的任务"
                            else -> "做好了"
                        },
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        color = if (st.running) Primary else if (pendingTask != null) Warn else Good,
                        modifier = Modifier.weight(1f))
                    Text(if (taskExpanded) "收起 ↑" else "展开 ↓",
                        fontSize = 12.5.sp, color = Primary)
                }
                if (taskExpanded) {
                    Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 10.dp)) {
                        Text(
                            when {
                                st.running -> "${st.stage} · 已完成 ${st.done}/${st.total} 块"
                                pendingTask != null -> "《${pendingTask.title}》· 已中断，回来点继续"
                                else -> "成品已生成"
                            },
                            fontSize = 11.5.sp, color = Sub)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            screen = when {
                                st.running -> Screen.PROGRESS
                                pendingTask != null -> Screen.TASKS
                                else -> Screen.AUDIO
                            }
                            if (screen == Screen.AUDIO) refresh++
                        }) {
                            Text(if (st.running) "查看进度 ›"
                                else if (pendingTask != null) "去任务页 ›" else "去试听 ›",
                                fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        // U1 · 3 个顶层 Tab；设置入口移到顶栏左侧，设置域页面不高亮任何 Tab
        AppBottomBar(
            active = chrome.activeTab,
            onTab = { idx ->
                screen = when (idx) {
                    TAB_TASKS -> Screen.TASKS
                    TAB_AUDIO -> Screen.AUDIO
                    else -> Screen.LIBRARY
                }
                if (idx == TAB_AUDIO) refresh++
            },
            surface = Card,
            primary = Primary,
            primarySoft = PrimarySoft,
            sub = Sub
        )
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

/**
 * U1 · 全 App 唯一的「目的地 → 壳规格」映射（Now in Android 的 TopLevelDestination 思路）。
 * 原先 4 处 screen == ... 判断散在顶栏和底栏里，Screen.CAST 还漏了高亮；这里一次定义。
 * ⚠️ 只描述显示，不改导航：when(screen) 分发、refresh 计数、modelsFrom、BackHandler 全不动。
 */
private fun chromeOf(s: Screen): ChromeSpec = when (s) {
    Screen.LIBRARY  -> ChromeSpec("书架", TAB_LIBRARY, showImport = true)
    Screen.PRODUCE  -> ChromeSpec("制作", TAB_LIBRARY)
    Screen.PROGRESS -> ChromeSpec("进度", TAB_TASKS)
    Screen.TASKS    -> ChromeSpec("任务", TAB_TASKS)
    Screen.AUDIO    -> ChromeSpec("我的音频", TAB_AUDIO)
    Screen.SETTINGS -> ChromeSpec("设置")
    Screen.MODELS   -> ChromeSpec("模型")
    Screen.CAST     -> ChromeSpec("角色音色")
    Screen.MIXER    -> ChromeSpec("调音台")
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
    var pasteOpen by remember { mutableStateOf(true) }
    var pasteText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // U1 · 下拉刷新条抽到 AppChrome 壳层；手势阈值与旧实现一致，删掉「点右上角」注释
        RefreshStrip(
            onRefresh = onChanged,
            primary = Primary,
            primarySoft = PrimarySoft,
            sub = Sub
        )
        Spacer(Modifier.height(6.dp))
        if (!ready) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("还差模型文件", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("去准备") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("粘贴文字", fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { pasteOpen = !pasteOpen }) {
                Text(if (pasteOpen) "收起" else "展开", fontSize = 12.sp, color = Primary)
            }
        }
        if (pasteOpen) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("正文") },
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val got = clipboard.getText()?.text ?: ""
                        if (got.isNotBlank()) pasteText = got
                        else Toast.makeText(c, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
                    }) { Text("粘贴") }
                    TextButton(onClick = { pasteText = "" }) { Text("清除") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (pasteText.isBlank()) {
                            Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                        } else {
                            BookStore.add(c, "粘贴的文字", pasteText)
                            pasteText = ""; pasteOpen = false
                            onChanged()
                            Toast.makeText(c, "书加好了", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("建书") }
                }
            }
        }
        if (books.isEmpty()) {
            CardBox {
                Text("书架还是空的", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                    Text("${b.chars} 字", fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { confirmDelete = b.id }) {
                            Text("✕", fontSize = 16.sp, color = Sub)
                        }
                        IconButton(onClick = { onOpen(b.id) }) {
                            Text("▶", fontSize = 16.sp, color = Primary)
                        }
                    }
                }
            }
        }
        confirmDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("删除这本书？") },
                text = { Text(books.find { it.id == id }?.title ?: "") },
                confirmButton = {
                    TextButton(onClick = {
                        BookStore.delete(c, id); confirmDelete = null; onChanged()
                    }) { Text("删除", color = Warn) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("取消") }
                }
            )
        }
    }
}

// ── ② 制作：一屏一个主按钮 ────────────────────────────────────
@Composable
private fun ProduceScreen(c: Context, id: String?, refresh: Int, onBack: () -> Unit,
                          onProgress: () -> Unit, onDone: () -> Unit,
                          onStarted: (Set<Int>?) -> Unit,
                          onGoCast: () -> Unit = {}) {
    val book = remember(id, refresh) { id?.let { BookStore.list(c).find { b -> b.id == it } } }
    val st by Producer.state.collectAsState()
    // R20 · 一次 split 同时拿章节与全部块，用来显示「预计几块」，不再只取 .first。
    val split = remember(book) { book?.let { ChapterSplitter.split(BookStore.read(c, it)) } }
    val chapters = split?.first ?: emptyList()
    val allChunks = split?.second ?: emptyList()
    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    var cacheTick by remember { mutableStateOf(0) }
    val thinkOn = remember(refresh) { Prefs.thinking(c) }
    // R17 · 进页面时对齐已有任务：书正以「直接朗读」跑着，别把高亮停在标注档，
    // 否则用户看到的模式和任务页/断点续跑用的不是同一套。
    // R20 · 本次预设也对齐已有任务书签，续跑时界面显示的档位与实际跑的一致。
    var mode by remember(book, refresh) {
        val t = TaskResume.load(c)
        // R25 · 真机证据：标注档 643 秒 0 条标注，日志还把它写成「通过」。
        // 新书默认走直接朗读；已有任务仍按书签原样恢复，不擅自改正在跑的档。
        mutableStateOf(if (t != null && t.bookId == id)
            (if (t.direct) ProduceMode.DIRECT else ProduceMode.ANNOTATE)
        else ProduceMode.DIRECT)
    }
    var cfg by remember(book, refresh) {
        val t = TaskResume.load(c)
        mutableStateOf(if (t != null && t.bookId == id) t.config else Prefs.defaultPerf(c))
    }
    val directMode = mode == ProduceMode.DIRECT
    val estimate = remember(selected, book) {
        if (selected.isEmpty() || selected.size == chapters.size) allChunks.size
        else allChunks.count { it.chapter in selected }
    }
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

        // R20 · 「本次制作」预设页：四组折叠，默认只露第一组。
        // 改的是本次级配置，不写回 Prefs；随任务书签落盘，续跑不丢（联动表 §3）。
        var showAllChapters by remember(book) { mutableStateOf(false) }

        ExpandGroup("① 做什么 · 仅本次", "", defaultOpen = true) {
            Text("制作方式", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
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
                        Text(if (m == ProduceMode.DIRECT) "直接朗读" else "标注后合成（实验性）",
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (m != ProduceMode.DIRECT) Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("制作范围", fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.index }.toSet() }) {
                    Text(if (selected.size == chapters.size) "清空" else "全选")
                }
            }
            Text(if (selected.isEmpty()) "整本 · 预计约 $estimate 块" else "已选 ${selected.size} 章 · 预计约 $estimate 块",
                fontSize = 12.sp, color = Sub)
            // R10 · epub 常有几十上百章，之前写死 2..40 导致长书根本没法选单章。
            // 改成默认只列 20 章 + 「显示全部」，几百章也不刷屏。
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
            }
        }

        ExpandGroup("② 怎么读 · 仅本次", cfg.summary()) {
            OptionRow("朗读档位", "", 
                ReadingStyle.values().map { it to it.label }, cfg.style) { cfg = cfg.copy(style = it) }
            OptionRow("语速倾向", "",
                Pace.values().map { it to it.label }, cfg.pace) { cfg = cfg.copy(pace = it) }
            OptionRow("情绪强度", "",
                Intensity.values().map { it to it.label }, cfg.intensity) { cfg = cfg.copy(intensity = it) }
            TextButton(onClick = onGoCast) { Text("给角色单独指定音色 →", fontSize = 12.sp) }
        }

        ExpandGroup("③ 怎么跑 · 仅本次", "") {
            OptionRow("段间静音", "",
                ProduceConfig.GAP_OPTIONS.map { it to "${it}ms" }, cfg.gapMs) { cfg = cfg.copy(gapMs = it) }
            OptionRow("标注失败重做", "",
                ProduceConfig.RETRY_OPTIONS.map { it to "${it}次" }, cfg.gateRetries) { cfg = cfg.copy(gateRetries = it) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("思考过程", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if (directMode) "不适用" else if (thinkOn) "开（会明显变慢）" else "关（推荐）",
                    fontSize = 12.sp, color = Sub)
            }
        }

        ExpandGroup("④ 高级 · 跨书默认值（只读）", "") {
            listOf(
                "上下文窗口" to "${Prefs.nCtx(c)} token",
                "推理线程" to "${Prefs.threads(c)} 线程",
                "随机度" to "${if (thinkOn) 0.6f else Prefs.temperature(c)}"
            ).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(k, fontSize = 11.5.sp, color = Sub, modifier = Modifier.weight(1f))
                    Text(v, fontSize = 11.5.sp)
                }
            }
            TextButton(onClick = { cfg = Prefs.defaultPerf(c) }) { Text("本次恢复默认", fontSize = 12.sp) }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                val sel = if (selected.isEmpty() || selected.size == chapters.size) null else selected
                onStarted(sel)
                Producer.start(c, book, sel, directRead = directMode, config = cfg)
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
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 ↑" else "展开 ↓", fontSize = 12.5.sp, color = Primary)
            }
        }
        // 手势条：上扒展开 / 下扒收起，零文案
        Box(
            Modifier.fillMaxWidth().height(22.dp)
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
            Box(Modifier.width(34.dp).height(3.dp)
                .background(Primary, RoundedCornerShape(50)))
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
                        Text(if (txt.isBlank()) ""
                             else "第 ${idx + 1} 块：\n$txt",
                            fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                    }
                }
            }

            // R18 · 工作看台：分段折叠小模块。
            // 结构改写自 SimformSolutionsPvtLtd/SSComposeExpandableListView（MIT）的
            // header/child 折叠模型，分列方式参照 ensemble-reads/ChaptersScreen；
            // 用 Compose 原生实现，不引 JitPack 库。数据来自 Workbench 扫 work 目录。
            Spacer(Modifier.height(16.dp))
            val seenTask = remember(tick) { TaskResume.load(c) }
            // R28 · 点「重做」后靠这个 nonce 重扫磁盘，不靠猜状态。
            var redoNonce by remember { mutableStateOf(0) }
            val segs = remember(tick, seenTask, redoNonce) { Workbench.segments(c, seenTask) }
            val rest = remember(tick, seenTask, redoNonce) { Workbench.remainingCount(c, seenTask) }
            val verdict = remember(tick, seenTask, redoNonce) { Workbench.verdict(c, seenTask) }
            var segOpen by remember { mutableStateOf(false) }
            var segSel by remember { mutableStateOf<Int?>(null) }
            // 与音频页共用 PlayQueue：不再有第二套 MediaPlayer 抢音频焦点。
            val pqSeg by PlayQueue.state.collectAsState()
            // R28 · 「语言模型到底有没有起作用」不用贴日志给我，App 里一眼判。
            // 四种口径分开：直接朗读 / 还没数据 / 模型生效 / 全是直读。
            CardBox {
                Text("语言模型是否生效", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                val vText = when {
                    seenTask?.direct == true -> "本次是「直接朗读」，没有使用语言模型"
                    verdict == null -> "还没落盘段映射；跑一次「标注后合成」后这里给出结论"
                    else -> verdict.label
                }
                Text(vText, fontSize = 12.sp, color = when {
                    seenTask?.direct == true -> Sub
                    verdict == null -> Sub
                    verdict.effective -> Good
                    else -> Warn
                })
            }
            Spacer(Modifier.height(8.dp))
            CardBox {
                Row(Modifier.clickable { segOpen = !segOpen }, verticalAlignment = Alignment.CenterVertically) {
                    Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(if (segOpen) "收起" else "展开", fontSize = 12.sp, color = Primary)
                }
                Text("绿=声音 · 蓝=模型标 · 黄=直读 · 灰=未记",
                    fontSize = 10.5.sp, color = Sub)
                if (segOpen) {
                    Spacer(Modifier.height(10.dp))
                    if (segs.isEmpty()) {
                    } else segs.chunked(8).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { s ->
                                val base = when {
                                    s.hasAudio -> Good
                                    s.src == "MODEL" -> Primary
                                    s.src == "FILL" -> Warn
                                    else -> Line
                                }
                                Box(
                                    Modifier.size(30.dp)
                                        .background(
                                            if (segSel == s.index) PrimarySoft else base.copy(alpha = 0.18f),
                                            RoundedCornerShape(8.dp))
                                        .clickable { segSel = if (segSel == s.index) null else s.index },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${s.index + 1}", fontSize = 10.sp,
                                        color = if (segSel == s.index) Primary else base)
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    if (rest > 0) Text("后面还有约 $rest 段待处理", fontSize = 11.sp, color = Sub)
                    segSel?.let { sel ->
                        val s = segs.find { it.index == sel }
                        if (s != null) {
                            // R28 · 这里显示的是「最终进 TTS 的那一条」，不是模型原始产出。
                            // 逐段标来源，不按「前 N 条」猜（R27 的缺口补齐会插在中间）。
                            val srcText = when (s.fromModel) {
                                true -> "模型真标注"
                                false -> "模型产出没被采纳，按原文直读"
                                null -> "旧缓存，没记来源"
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("第 ${s.index + 1} 段 · 第 ${s.block + 1} 块第 ${s.ann + 1} 条 · " +
                                    srcText + if (s.hasAudio) " · 已合成" else " · 还没声音",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (s.fromModel == true) Good else if (s.fromModel == false) Warn else Sub)
                            if (s.pendingRedo)
                                Text("已标记待重做，去任务页点「继续制作」", fontSize = 11.sp, color = Warn)
                            Spacer(Modifier.height(5.dp))
                            Text(if (s.text.isBlank()) "（这一格没有落盘文字，可能是旧缓存）" else s.text,
                                fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                            if (s.instruct.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("${s.speaker}｜${s.instruct}", fontSize = 11.sp, color = Primary)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row {
                                if (s.wav != null) {
                                    val label = "第 ${s.index + 1} 段"
                                    val onThis = pqSeg.label == label
                                    Button(onClick = {
                                        PlayQueue.setTracks(listOf(Track(s.wav, label)))
                                        PlayQueue.playFrom(0)
                                    }) {
                                        Text(if (onThis && pqSeg.isPlaying) "重播这一段" else "试听这一段",
                                            fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { PlayQueue.release() }) {
                                        Text("关掉预览", fontSize = 11.5.sp, color = Sub)
                                    }
                                } else {
                                    Text("这一段还没生成声音", fontSize = 11.5.sp, color = Sub)
                                }
                            }
                            // R28 · 段级重做：语义等同 ReaderVoice Scheduler.invalidate(unitIds)，
                            // 只把目标单元标脏，禁整章 / 整本清缓存。
                            if (seenTask?.direct != true && s.wavName.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Row {
                                    TextButton(onClick = {
                                        val w = Workbench.workDir(c, seenTask)
                                        val ok = w?.let { SegPlan.redoSynthesize(it,
                                            SegMeta(s.index, s.block, s.ann, s.fromModel,
                                                s.text, s.speaker, s.instruct, s.wavName)) } ?: false
                                        Toast.makeText(c,
                                            if (ok) "已删这段声音，去任务页点「继续制作」重合成" else "没找到这段文件",
                                            Toast.LENGTH_SHORT).show()
                                        redoNonce++
                                    }) { Text("重合成这一段", fontSize = 11.5.sp, color = Primary) }
                                    Spacer(Modifier.width(6.dp))
                                    TextButton(onClick = {
                                        val w = Workbench.workDir(c, seenTask)
                                        val all = w?.let { SegPlan.readMap(it) } ?: emptyList()
                                        val killed = w?.let { SegPlan.redoBlock(it, s.block, all) } ?: 0
                                        Toast.makeText(c,
                                            "已清第 ${s.block + 1} 块标注和 $killed 段声音，点「继续制作」重跑语言模型",
                                            Toast.LENGTH_LONG).show()
                                        redoNonce++
                                    }) { Text("重新标注本块", fontSize = 11.5.sp, color = Warn) }
                                }
                            }
                        }
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
        } else {
            if (st.stage == "已停止") {
                Button(onClick = onResume, Modifier.fillMaxWidth().height(48.dp)) {
                    Text("继续制作（从停下的地方接着跑）")
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onBack, Modifier.fillMaxWidth()) {  }
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
        if (t == null) {
            CardBox {
                Text("还没有任务", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                                if (b != null) { Producer.start(c, b, t.only, t.direct, t.think, t.config); onProgress() }
                            }, Modifier.weight(1f)) { Text("从头重做", fontSize = 12.sp) }
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                            }, Modifier.weight(1f)) { Text("放弃任务", fontSize = 12.sp, color = Warn) }
                        }
                        Spacer(Modifier.height(5.dp))
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

    }
}

// ── ④ 我的音频 ───────────────────────────────────────────────
// R18 · 播放状态与 300ms 快照轮询改写自 m9939418/AlexExoPlayerMP3 的
// PlaybackState / PlayerController / MiniPlayingBar；控件集（播放暂停 / 上下条 /
// ±15s / 进度拖动）参照 PaperBack 与 andriishpek-sigma/AudiobookPlayer 的通行做法。
// 本项目仍用系统 MediaPlayer，不引入 Media3 / Hilt / JitPack 新依赖。
private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun AudioScreen(c: Context, refresh: Int) {
    var localTick by remember { mutableStateOf(0) }
    val items = remember(refresh, localTick) { AudioLibrary.list(c) }
    var exportId by remember { mutableStateOf<String?>(null) }
    // R18 · 播放器不再手搓：PlayQueue 由三份现成源码改写（ReaderVoice CachedAudioPlayer
    // / AlexExoPlayerMP3 PlayerController / ensemble-reads PlaybackController）。
    // 音频页与「分段看台」共用一个实例，切页不停播、不会两套 MediaPlayer 抢焦点。
    val pq by PlayQueue.state.collectAsState()
    LaunchedEffect(items) {
        PlayQueue.setTracks(items.map { Track(it.file, "${it.title} · ${it.scope}") })
    }
    LaunchedEffect(Unit) { while (true) { delay(300); PlayQueue.tick() } }
    val isPlaying = pq.isPlaying
    val posMs = pq.positionMs
    val durMs = pq.durationMs
    val current = items.getOrNull(pq.index)

    val release: () -> Unit = { PlayQueue.release() }
    val step: (Int) -> Unit = { d -> if (d < 0) PlayQueue.previous() else PlayQueue.next() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val f = items.find { it.id == exportId }?.file
        if (uri != null && f != null) {
            try {
                c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().copyTo(o) }
                Toast.makeText(c, "已保存", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) { Toast.makeText(c, "保存失败", Toast.LENGTH_SHORT).show() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        // 顶部「现在播放」：控件集合取自 MiniPlayingBar 与 Media3 的
        // PreviousButton / PlayPauseButton / NextButton 语义；状态由 PlayQueue 单向驱动。
        if (current != null) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text(if (pq.ended) "已播完" else if (isPlaying) "播放中" else "已暂停",
                    fontSize = 11.5.sp, color = Primary)
                Spacer(Modifier.height(4.dp))
                Text("${current.title} · ${current.scope}", fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${current.mode} · ${fmtTime(AudioLibrary.durationMs(current.file))}",
                    fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = if (durMs > 0) posMs.toFloat() / durMs.toFloat() else 0f,
                    onValueChange = { v -> PlayQueue.seekTo((v * durMs.toFloat()).toLong()) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(posMs), fontSize = 11.sp, color = Sub)
                    Spacer(Modifier.weight(1f))
                    Text(fmtTime(durMs), fontSize = 11.sp, color = Sub)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { step(-1) }) { Text("⏮ 上一条", fontSize = 12.sp) }
                    TextButton(onClick = { PlayQueue.seekTo((posMs - 15000).coerceAtLeast(0)) }) {
                        Text("−15s", fontSize = 12.sp)
                    }
                    Button(onClick = { PlayQueue.toggle() }) {
                        Text(if (isPlaying) "⏸ 暂停" else "▶ 播放", fontSize = 13.sp)
                    }
                    TextButton(onClick = { PlayQueue.seekTo((posMs + 15000).coerceAtMost(durMs)) }) {
                        Text("+15s", fontSize = 12.sp)
                    }
                    TextButton(onClick = { step(1) }) { Text("下一条 ⏭", fontSize = 12.sp) }
                }
                // ensemble-reads PlaybackController.setSpeed 的同款档位
                TextButton(onClick = {
                    val nextSpeed = when (pq.speed) {
                        1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f
                    }
                    PlayQueue.setSpeed(nextSpeed)
                }) { Text("倍速 ${"%.2f".format(pq.speed).trimEnd('0').trimEnd('.')}x", fontSize = 11.5.sp, color = Sub) }
                TextButton(onClick = release, Modifier.fillMaxWidth()) {
                    Text("关闭播放器", fontSize = 11.5.sp, color = Sub)
                }
            }
        }

        if (items.isEmpty()) {
            CardBox {
                Text("还没有音频", fontWeight = FontWeight.Bold)
            }
        } else items.forEachIndexed { idx, item ->
            val on = idx == pq.index
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${item.title} · ${item.scope}", fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp, maxLines = 2)
                        Text("${item.mode} · ${fmtTime(AudioLibrary.durationMs(item.file))} · %.1f MB"
                            .format(item.bytes / 1024f / 1024f), fontSize = 11.5.sp, color = Sub)
                    }
                    if (on) Text("●", color = Primary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(9.dp))
                Row {
                    Button(onClick = {
                        if (!on || pq.ended) PlayQueue.playFrom(idx) else PlayQueue.toggle()
                    }) {
                        Text(if (on && isPlaying) "暂停" else if (on) "继续" else "试听", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = {
                        exportId = item.id
                        export.launch("${item.title}_${item.scope}.wav")
                    }) { Text("保存到手机", fontSize = 12.sp) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        if (on) release()
                        AudioLibrary.remove(c, item.id); localTick++
                    }) { Text("删除", fontSize = 11.5.sp, color = Warn) }
                }
            }
        }
    }
}

// ── ⑤ 设置：分组折叠，不把整页塞满 ─────────────────────────────
/** R25 · 设置页按模型分组的小标题，明确「长期默认」作用域。 */
@Composable
private fun GroupLabel(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary)
        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = Sub)
    }
}

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
                            onLibrary: () -> Unit, onCast: () -> Unit,
                            onMixer: () -> Unit) {
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
        // R31 · 调音台是独立一页，不把 9 个参数埋在折叠里
        Card(
            Modifier.fillMaxWidth().padding(bottom = 11.dp)
                .clickable { onMixer() },
            colors = CardDefaults.cardColors(containerColor = PrimarySoft),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🎛 调音台", fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = Primary,
                    modifier = Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = Primary)
            }
        }


        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("模型文件（两个模型共用）", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

        GroupLabel("① 语言模型 · 长期默认", "")

        // 思考外显开关：打开后进度页能逐块看推理，代价是明显更慢
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("语言模型 · 思考过程", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(3.dp))
                }
                Switch(checked = thinkOn, onCheckedChange = {
                    Prefs.setThinking(c, it); cfg++
                    EventLog.log(EventLog.Kind.ENGINE, "THINK_MODE", if (it) "on" else "off")
                })
            }
            if (thinkOn) {
                Spacer(Modifier.height(6.dp))
            }
        }

        // R26 维护订正：R25 文档声称这张卡「物理挪回 ①」，实际代码还挂在 ② 下面。
        // 上下文 / 线程 / 随机度全是语言模型参数，必须和思考开关同组。
        // 模型参数：只暴露三个真能改的
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("语言模型 · 推理参数", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
        }

        GroupLabel("② 音频模型 · 长期默认", "")

        // R11 · 非语言表达：哭腔 / 笑 / 叹气 / 抽泣。只增强导演指令，正文一字不改。
        CardBox(Modifier.padding(bottom = 11.dp)) {
            val nv = remember(cfg) { Prefs.nonVerbal(c) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("音频模型 · 拟声表达", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(3.dp))
                }
                Switch(checked = nv, onCheckedChange = {
                    Prefs.setNonVerbal(c, it); cfg++
                    EventLog.log(EventLog.Kind.PIPELINE, "NONVERBAL_MODE", if (it) "on" else "off")
                })
            }
            Spacer(Modifier.height(6.dp))
        }

        // R36 · 音频模型 9 项采样与 12 维音色全部收敛到调音台，设置页不再画第二遍。
        // 两处都只读写 Prefs 同一份真值源，不算两份配置。
        CardBox(Modifier.padding(bottom = 11.dp)) {
            val castOn = remember(cfg) { Prefs.castEnabled(c) }
            Text("音频模型 · 全部参数", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色映射", fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
                Switch(checked = castOn, onCheckedChange = {
                    Prefs.setCastEnabled(c, it); cfg++
                })
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onMixer, Modifier.fillMaxWidth()) {
                Text("音色 12 维 · 采样 9 项 · 预设 · 角色卡 ›", fontSize = 12.sp)
            }
        }

        // 音频模型调试：真合成，不是 Toast 假按钮；第二个按钮直接验哭/笑/叹气效果
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("音频模型 · 试音", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
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
                            // R38 · 官方 README §167：目标语言已知时显式传，别用 auto。
                            // 主链与 A/B 都是 chinese，试音口径统一，避免「同一套参数试听不一样」。
                            val code = NativeEngine.ttsSynthesize(h, text, instruct, null, "chinese",
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
            Spacer(Modifier.height(8.dp))
            // R26 · 只引导用户去系统设置，不申请新权限、不反射厂商 Activity、不假装豁免。
            TextButton(onClick = {
                try {
                    c.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    EventLog.log(EventLog.Kind.PIPELINE, "BATTERY_SETTINGS_OPEN",
                        "用户主动打开系统电池优化设置")
                } catch (_: Throwable) {
                    Toast.makeText(c, "这台手机没提供该设置入口", Toast.LENGTH_SHORT).show()
                }
            }, Modifier.fillMaxWidth()) { Text("后台运行设置（系统电池优化）", fontSize = 12.sp) }
        }
        CardBox {
            Row(Modifier.clickable { advanced = !advanced }, verticalAlignment = Alignment.CenterVertically) {
                Text("高级（诊断）", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(if (advanced) "收起" else "展开", fontSize = 12.sp, color = Primary)
            }
            if (advanced) {
                // R21 · 诊断分两级：常态只给结论，想深挖再展开。每 3 秒跟着任务刷新。
                var showBlocks by remember { mutableStateOf(false) }
                var showRules by remember { mutableStateOf(false) }
                var showPreview by remember { mutableStateOf(false) }
                var showContent by remember { mutableStateOf(false) }
                var showRaw by remember { mutableStateOf(false) }
                var auditTick by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) { delay(3000); auditTick++ }
                }
                val auditText = remember(auditTick) { AuditLog.summaryText(c) }
                val rulesText = remember(auditTick) { AuditLog.ruleText(c) }
                val blocksText = remember(auditTick) { AuditLog.blocksText(c, 12) }
                val previewText = remember(auditTick) { AuditLog.previewText(c, 3) }
                val contentText = remember(auditTick) { AuditLog.contentText(c, 6) }
                val rawText = remember(auditTick) { RawDump.latest(c) }
                val hasEmpty = auditText.contains("零标注 ") && !auditText.contains("零标注 0")

                fun buildDiag(full: Boolean): String = buildString {
                    append("听本 ").append(com.tingben.app.BuildConfig.VERSION_NAME).append('\n')
                    append("设备 ").append(android.os.Build.MODEL).append(" / Android ")
                        .append(android.os.Build.VERSION.RELEASE).append(" / ")
                        .append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").append('\n')
                    append("引擎 ").append(if (NativeEngine.isAvailable()) NativeEngine.version() else "未装载").append('\n')
                    append("前台服务 ").append(ProduceService.status()).append('\n')
                    append("设置 ").append(Prefs.summary(c)).append('\n')
                    append("模型 ").append(ModelStore.files(c).joinToString("、") { it.name }).append('\n')
                    append("\n=== 标注审计 ===\n").append(AuditLog.summaryText(c)).append('\n')
                    // R22 · 摘要也要能直接看到「标了什么」，不用用户再点一次展开。
                    append("\n=== 标注结果（最近 3 块 · 逐条 text/speaker/instruct）===\n")
                        .append(AuditLog.contentText(c, 3)).append('\n')
                    append("\n=== 规则生效 ===\n").append(AuditLog.ruleText(c)).append('\n')
                    append("\n=== 模型原始产出（未经清洗，最近一块）===\n")
                    if (rawText.isBlank()) append("（暂无；标注后合成才有）\n")
                    else append(if (full) rawText else rawText.take(400)).append('\n')
                    append("原始产出全文目录：").append(RawDump.runDir(c)).append('\n')
                    append("\n=== 逐块明细（最近 12 块）===\n").append(AuditLog.blocksText(c, 12)).append('\n')
                    val crash = EventLog.lastCrash()
                    if (crash.isNotBlank()) append("\n=== 最近一次崩溃 ===\n").append(crash).append('\n')
                    append("\n=== 最近事件（不含正文）===\n")
                    append(EventLog.read().lines().takeLast(60).joinToString("\n"))
                    if (full) {
                        append("\n\n=== 产出截取（含原文片段，仅本机）===\n")
                            .append(AuditLog.previewText(c, 6)).append('\n')
                        append("\n=== 诊断态完整日志（含 TRACE，最近 300 行）===\n")
                            .append(EventLog.readDetail().lines().takeLast(300).joinToString("\n"))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("静态快照", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                Text(Prefs.summary(c), fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Text("标注审计 · 这次到底标了没有", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                Spacer(Modifier.height(4.dp))
                Text(auditText, fontSize = 11.sp, color = if (hasEmpty) Warn else Sub)
                if (hasEmpty) {
                    Spacer(Modifier.height(4.dp))
                    Text("⚠ 有块跑完却没标出条目，展开「产出截取」看模型吐了什么",
                        fontSize = 11.sp, color = Warn)
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showContent = !showContent }, Modifier.fillMaxWidth()) {
                    Text(if (showContent) "收起标注内容" else "查看标注内容（每条 text / speaker / instruct）",
                        fontSize = 12.sp, color = Primary)
                }
                if (showContent) {
                    Text("〈模型标注〉= 语言模型真标出来的；〈程序补齐〉= 没标完的尾部由程序补成旁白。",
                        fontSize = 10.sp, color = Warn)
                    Text(contentText, fontSize = 10.5.sp, color = Sub)
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(AuditLog.contentText(c, 20)))
                        Toast.makeText(c, "已复制标注全文（最近 20 块），仅本机保存", Toast.LENGTH_SHORT).show()
                    }, Modifier.fillMaxWidth()) { Text("复制标注全文（最近 20 块）", fontSize = 11.5.sp) }
                }
                // R24 · 未经任何清洗的模型原始产出：能直接自证「真标还是假标」，
                // 而不是只靠解析后的统计数字。移植 Alexandria llm_responses.log 的思路。
                TextButton(onClick = { showRaw = !showRaw }, Modifier.fillMaxWidth()) {
                    Text(if (showRaw) "收起模型原始产出" else "查看模型原始产出（未经清洗，最近一块）",
                        fontSize = 11.5.sp, color = Primary)
                }
                if (showRaw) Text(rawText.ifBlank { "（暂无；标注后合成才有）" },
                    fontSize = 10.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showBlocks = !showBlocks }) {
                        Text(if (showBlocks) "收起逐块" else "逐块明细", fontSize = 11.5.sp)
                    }
                    TextButton(onClick = { showRules = !showRules }) {
                        Text(if (showRules) "收起规则" else "规则生效", fontSize = 11.5.sp)
                    }
                    TextButton(onClick = { showPreview = !showPreview }) {
                        Text(if (showPreview) "收起截取" else "产出截取", fontSize = 11.5.sp)
                    }
                }
                if (showBlocks) Text(blocksText, fontSize = 10.5.sp, color = Sub)
                if (showRules) Text(rulesText, fontSize = 10.5.sp, color = Sub)
                if (showPreview) {
                    Text(previewText, fontSize = 10.5.sp, color = Sub)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(rawText.ifBlank { "（暂无模型原始产出）" }))
                    Toast.makeText(c, "已复制最近一块模型原始产出全文，仅本机保存", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth()) { Text("复制最近一块模型原始产出（全文）", fontSize = 11.5.sp) }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(buildDiag(false)))
                    Toast.makeText(c, "已复制诊断摘要，直接贴给我即可", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth()) { Text("复制诊断信息（摘要）") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(buildDiag(true)))
                    Toast.makeText(c, "已复制完整日志（含产出片段），仅本机保存", Toast.LENGTH_SHORT).show()
                }, Modifier.fillMaxWidth()) { Text("复制完整日志（含产出片段）") }
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
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回设置", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            CardBox {
                Text("还没有可分配的角色", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(5.dp))
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
                Text("清空角色表", fontSize = 12.sp, color = Warn)
            }
        }
    }
}

// ── ⑥ 调音台（R40 · P0 统一提交）：音频模型控制台 ─────────────────────
// 评审实查：旧版 5 个写入口（身份保存 / 九项各自提交 / 12 维直接覆盖 / 表演层长期默认 /
// 预设一键覆盖）各自握着生效权，界面没有「待提交」这一层。R40 收成：
//   active（Prefs 唯一真值源） ←→ draft（唯一编辑态） → 唯一出口 MixerCommit.commit()
// 不手搓依据见 docs/R39_调音台统一提交_联动表与分轮.md §6 与 core/MixerDraft.kt 头注。
// 折叠分组沿用 R36 的 MixerSection（rememberSaveable 跨旋转保留展开态）。
@Composable
private fun MixerSection(
    title: String,
    key: String,
    defaultOpen: Boolean = false,
    badge: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    var open by rememberSaveable(key) { mutableStateOf(defaultOpen) }
    CardBox(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = Primary,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (open) "收起" else "展开", fontSize = 11.5.sp, color = Primary)
        }
        if (open) {
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

/** R40 · 时机提示统一组件（评审第 17 条：别每处各写一种说法）。 */
@Composable
private fun TimingHint(timing: String) {
    Text("改动先进入待提交，按顶部「提交变更」写入；$timing",
        fontSize = 10.sp, color = Sub)
}

@Composable
private fun MixerScreen(c: Context, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    // active 只从 Prefs 读；draft 是界面唯一可写的编辑态。
    var active by remember { mutableStateOf(MixerDraft.active(c)) }
    var draft by remember { mutableStateOf(active) }
    val changes = MixerCommit.diff(active, draft)
    val dirty = changes.isNotEmpty()
    val stamp = remember(tick) { Prefs.voiceStamp(c) }
    val rows = remember(tick) { Mixer.snapshot(c) }
    val presets = remember(tick) { MixerPresetStore.list(c) }
    val scope = rememberCoroutineScope()
    val missing = remember(tick) { ModelStore.missing(c) }

    var showDiff by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var paramDrafts by remember { mutableStateOf(emptyMap<String, String>()) }
    var paramErr by remember { mutableStateOf(emptyMap<String, String>()) }
    var dimEdited by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var castTick by remember { mutableStateOf(0) }
    val casts = remember(castTick) { VoiceCast.load(c) }
    var newCastName by remember { mutableStateOf("") }
    var newCastGrid by remember { mutableStateOf("P02") }
    var editCastId by remember { mutableStateOf("") }
    var editCastText by remember { mutableStateOf("") }
    var gridTargetId by remember { mutableStateOf("") }
    var confirmDeleteId by remember { mutableStateOf("") }

    // R41a · 试听只看两态：草稿（未提交）/ 已生效。不再有 A/B、预设对比、合成/播放两步。
    var trialText by remember {
        mutableStateOf("雨下了一整夜，她终于忍不住笑出声，又轻轻叹了口气。")
    }
    var busyDraftTrial by remember { mutableStateOf(false) }
    var busyActiveTrial by remember { mutableStateOf(false) }
    var trialMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (CastPresets.seedIfEmpty(c)) {
            EventLog.log(EventLog.Kind.ENGINE, "MIXER_CAST", "内置角色卡已写入 4 张")
            castTick++
        }
    }

    /** 全页唯一生效动作。写完重读真值源，保留生成器工作区（dims/dimText）。 */
    fun commitAll() {
        val n = changes.size
        val after = MixerCommit.commit(c, draft, n)
        active = MixerDraft.active(c)
        draft = active.copy(dims = draft.dims, dimText = draft.dimText)
        paramDrafts = emptyMap(); paramErr = emptyMap()
        tick++
        msg = "已提交 $n 项变更 · 指纹 ${after.take(8)}"
    }
    fun discardAll() {
        draft = active.copy(dims = draft.dims, dimText = draft.dimText)
        paramDrafts = emptyMap(); paramErr = emptyMap()
        msg = "已丢弃待提交变更"
    }
    /** 载入预设只写 draft，绝不越过顶部状态条直接生效。 */
    fun loadPreset(p: MixerPreset) {
        val next = draft.copy(
            knobs = p.knobs,
            perf = p.perf,
            voice = if (p.voice.isBlank()) draft.voice else p.voice,
            dims = p.fields,
            dimText = p.fields.let { f ->
                VoicePreset("edit", "自定义", f.filter { it.value != "不指定" }, "").render()
            }
        )
        draft = next
        dimEdited = true
        Mixer.logPreset("载入预设为待提交", p)
        msg = "已把「${p.name}」载入待提交，按顶部「提交变更」生效"
    }
    /**
     * R41a · 一步试听：合成完自动播放。整套配置来自传入的「已生效」或「草稿」，
     * 身份层 / 风格层 / 采样 9 项全跟着走，不写 Prefs、不进 draft、不改 voiceStamp。
     * instruct 复用主链同一个 VoiceProfile.build()，不复制第二份拼串逻辑。
     */
    fun trial(label: String, src: MixerDraft, out: String, busy: (Boolean) -> Unit) {
        busy(true)
        val tag = if (label == "未提交参数" && !dirty) "未提交参数（与已生效一致）" else label
        trialMsg = "正在用$label合成…（手机 CPU 慢，约 1~3 分钟）"
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                if (!NativeEngine.isAvailable()) throw IllegalStateException("本地引擎没装载，请重启 App")
                val tk = ModelStore.talker(c) ?: throw IllegalStateException("缺声音主文件")
                val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺声音配套文件")
                val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                if (h == 0L) throw IllegalStateException("声音模型打不开")
                try {
                    val identity = src.voice.trim().ifBlank { DEFAULT_IDENTITY }
                    val instruct = VoiceProfile.build(
                        identity, src.perf.styleInstruct(), "平稳、清晰地叙述，句间自然停顿。")
                    val f = File(c.filesDir, out)
                    val code = NativeEngine.ttsSynthesize(h, trialText, instruct, null, "chinese",
                        f.absolutePath, src.knobs)
                    if (code != 0 || !f.exists() || f.length() <= 44)
                        throw IllegalStateException("没生成声音，code=$code")
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL",
                        "来源=$tag · textChars=${trialText.length} · seed=${src.knobs.seed} · temp=${src.knobs.temperature} · " +
                            "subTemp=${src.knobs.subTemperature} · rep=${src.knobs.repetitionPenalty} · topK=${src.knobs.topK} · " +
                            "topP=${src.knobs.topP} · subTopK=${src.knobs.subTopK} · subTopP=${src.knobs.subTopP} · " +
                            "max=${src.knobs.maxNewTokens} · hash=${src.knobs.hash()} · " +
                            "voiceChars=${identity.length} · styleChars=${src.perf.styleInstruct().length} · " +
                            "bytes=${f.length()} · instruct=$instruct")
                    f.absolutePath
                } finally { NativeEngine.ttsFree(h) }
            }
            withContext(Dispatchers.Main) {
                busy(false)
                val p = res.getOrNull()
                if (!p.isNullOrBlank()) play(p)
                trialMsg = if (p != null) "$label 出声了 ✓ ${File(p).length()} 字节，已自动播放"
                           else "$label 失败：" + (res.exceptionOrNull()?.message ?: "未知原因")
                res.exceptionOrNull()?.let {
                    EventLog.log(EventLog.Kind.CRASH, "MIXER_TRIAL_FAIL", it.message ?: "")
                }
            }
        }
    }
    /** 复用 R18 的全局播放内核，不在这里手搓第二套 MediaPlayer。 */
    fun play(path: String) {
        val f = File(path)
        if (!f.isFile) { Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show(); return }
        PlayQueue.setTracks(listOf(Track(f, "调音台试听")))
        PlayQueue.playFrom(0)
    }

    val autoText = VoicePreset("edit", "自定义",
        draft.dims.filter { it.value != "不指定" }, "").render()
    val preview = if (dimEdited) draft.dimText else autoText
    val voiceChange = changes.firstOrNull { it.label.startsWith("音色身份") }
    val unbound = casts.count { it.description.isBlank() }

    // 有未提交变更时，系统返回键先问一次，别让草稿静默丢失。
    BackHandler(enabled = dirty) { showLeave = true }

    Column(Modifier.fillMaxSize()) {
        // ── 吸顶状态条：R40 三段式 + R41a 返回 / 两个试听按钮，同一模块常驻 ──
        CardBox(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (dirty) showLeave = true else onBack() }) {
                    Text("‹ 返回", fontSize = 12.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("已生效 v$INSTRUCT_TEMPLATE_VERSION · 指纹 ${stamp.take(8)}",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (dirty) "未提交变更 ${changes.size} 项" else "无待提交变更",
                        fontSize = 11.sp, color = if (dirty) Warn else Good)
                }
                TextButton(onClick = { showDiff = true }) { Text("查看差异", fontSize = 11.5.sp) }
                Button(onClick = { commitAll() }, enabled = dirty) {
                    Text("提交变更", fontSize = 11.5.sp)
                }
            }
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(msg, fontSize = 10.5.sp, color = Sub)
            }
            if (dirty) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { discardAll() }) {
                    Text("丢弃全部待提交", fontSize = 10.5.sp, color = Warn)
                }
            }
            Spacer(Modifier.height(6.dp))
            val canTrial = missing.isEmpty() && !busyDraftTrial && !busyActiveTrial
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    trial("未提交参数", draft, "mixer_trial_draft.wav") { busyDraftTrial = it }
                }, enabled = canTrial, modifier = Modifier.weight(1f)) {
                    Text(if (busyDraftTrial) "合成中…" else "试听未提交参数", fontSize = 11.5.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    trial("已生效参数", active, "mixer_trial_active.wav") { busyActiveTrial = it }
                }, enabled = canTrial, modifier = Modifier.weight(1f)) {
                    Text(if (busyActiveTrial) "合成中…" else "试听已生效参数", fontSize = 11.5.sp)
                }
            }
            OutlinedTextField(
                value = trialText, onValueChange = { trialText = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("试听文本", fontSize = 10.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
            )
            if (trialMsg.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(trialMsg, fontSize = 10.5.sp, color = Sub)
            }
            if (missing.isNotEmpty()) {
                Text("还缺模型文件，先去设置页导入。", fontSize = 10.5.sp, color = Warn)
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))

            // ── 固定首屏：已生效全文（只读） vs 草稿（可编辑）+ diff 高亮 ────
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text("音色身份", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("当前生效 · 全书固定（只读）", fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(4.dp))
                Text(active.voice.trim().ifBlank { DEFAULT_IDENTITY },
                    fontSize = 12.5.sp, modifier = Modifier.background(PrimarySoft))
                Spacer(Modifier.height(7.dp))
                if (voiceChange != null) {
                    Text("已改：${voiceChange.old.take(32)} → ${voiceChange.new.take(32)}",
                        fontSize = 10.5.sp, color = Warn)
                    Spacer(Modifier.height(5.dp))
                }
                OutlinedTextField(
                    value = draft.voice, onValueChange = { draft = draft.copy(voice = it) },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                    label = { Text("编辑音色描述（草稿）", fontSize = 11.sp) },
                    placeholder = { Text("例如：温和的中年女声，音区偏低，吐字清晰，语速偏慢，无明显口音", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp)
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = draft.copy(voice = "") }) {
                        Text("清空回默认（待提交）", fontSize = 11.sp)
                    }
                }
                TimingHint(TIMING_NOW)
            }

            // ── ① 采样参数：9 项，行内改值只进 draft，删掉 9 个逐项提交按钮 ──
            MixerSection("① 采样参数 · 9 项（全部到引擎）", "sampling", false, "可编辑 · O4/O5/O6") {
                Text(Mixer.CHAIN, fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                ParamCatalog.SAMPLING.forEach { spec ->
                    CardBox(Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(spec.label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            Text(spec.official, fontSize = 9.5.sp, color = Sub)
                        }
                        Text("${spec.hint} · 官方默认 ${spec.def} · 本次 ${spec.read(draft.knobs)}",
                            fontSize = 10.5.sp, color = Sub)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            spec.options.forEach { o ->
                                val target = if (o == "随机") "-1" else o
                                val k = spec.withValue(draft.knobs, target)
                                val on = spec.read(draft.knobs) == target ||
                                    (o == "随机" && draft.knobs.seed < 0)
                                TextButton(enabled = k != null,
                                    onClick = { if (k != null) draft = draft.copy(knobs = k) }) {
                                    Text(o, fontSize = 11.sp,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        color = if (on) Primary else Sub)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = paramDrafts[spec.key] ?: "",
                            onValueChange = { s ->
                                paramDrafts = paramDrafts + (spec.key to s)
                                val k = spec.withValue(draft.knobs, s)
                                paramErr = paramErr + (spec.key to
                                    if (s.isBlank() || k != null) "" else "数值不在允许范围")
                                if (k != null) draft = draft.copy(knobs = k)
                            },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("精确值（改完进待提交）", fontSize = 10.sp) },
                            placeholder = { Text(spec.read(draft.knobs), fontSize = 11.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
                        )
                        val err = paramErr[spec.key] ?: ""
                        if (err.isNotBlank()) Text(err, fontSize = 10.sp, color = Warn)
                        if (!spec.isDefault(draft.knobs)) {
                            TextButton(onClick = {
                                spec.withValue(draft.knobs, spec.def)?.let {
                                    draft = draft.copy(knobs = it)
                                    paramDrafts = paramDrafts - spec.key
                                    paramErr = paramErr - spec.key
                                }
                            }) { Text("恢复官方默认 ${spec.def}", fontSize = 10.5.sp, color = Primary) }
                        }
                    }
                }
                TextButton(onClick = {
                    draft = draft.copy(knobs = TtsKnobs())
                    paramDrafts = emptyMap(); paramErr = emptyMap()
                }) { Text("全部恢复上游默认（待提交）", fontSize = 11.5.sp) }
                TimingHint(TIMING_NOW)
            }

            // ── ② 身份生成器：只有一种语义（生成 → 可编辑预览 → 填入草稿）──
            MixerSection("② 音色身份生成器（12 维 / 现成网格）", "identity", true, "填入草稿") {
                Text("12 维是帮你写 instruct 的拼装模板，不是模型字段（ReaderVoice 096b_personas.py，MIT）。",
                    fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(5.dp))
                VoiceDimension.entries.forEach { d ->
                    SettingOption(d.label, d.options,
                        d.options.indexOf(draft.dims[d.name] ?: "不指定").coerceAtLeast(0)) { i ->
                        val v = d.options[i]
                        draft = draft.copy(dims = if (v == "不指定") draft.dims - d.name
                        else draft.dims + (d.name to v))
                        if (!dimEdited) draft = draft.copy(dimText = "")
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM",
                            "${d.name}=${v} · 已选 ${draft.dims.size} 维 · 仅草稿")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("拼出的描述（可手动改，未生效）", fontSize = 11.sp, color = Sub)
                OutlinedTextField(
                    value = preview,
                    onValueChange = { dimEdited = true; draft = draft.copy(dimText = it) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val merged = listOf(preview, draft.voice.trim().removeSuffix("。"))
                            .filter { it.isNotBlank() }.joinToString("")
                        draft = draft.copy(voice = merged)
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM_DRAFT",
                            "生成描述填入草稿 ${merged.length} 字 · 未生效")
                    }) { Text("填入上方草稿", fontSize = 11.5.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        draft = draft.copy(dims = emptyMap(), dimText = "")
                        dimEdited = false
                    }) { Text("清空 12 维", fontSize = 11.sp) }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                Text("现成音色网格（点一条填入草稿，不会自己生效）",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                VoicePresets.ALL.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable {
                        draft = draft.copy(voice = p.render(), dims = emptyMap(), dimText = p.render())
                        dimEdited = true
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM_DRAFT",
                            "网格 ${p.id} 填入草稿 · 未生效")
                    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.id} ${p.name}", fontSize = 11.5.sp)
                            Text(p.chips(), fontSize = 10.sp, color = Sub)
                        }
                        Text("填入草稿", fontSize = 11.sp, color = Primary)
                    }
                }
                TimingHint(TIMING_NOW)
            }

            // ── ③ 表演层：只写 draft.perf，提交后对所有新任务生效 ──────────
            MixerSection("③ 表演层 · 上层（下个任务起）", "perf", false, "不进模型 API") {
                SettingOption("朗读档位", ReadingStyle.entries.map { it.label },
                    ReadingStyle.entries.indexOf(draft.perf.style).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(style = ReadingStyle.entries[i]))
                }
                SettingOption("语速倾向", Pace.entries.map { it.label },
                    Pace.entries.indexOf(draft.perf.pace).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(pace = Pace.entries[i]))
                }
                SettingOption("情绪强度", Intensity.entries.map { it.label },
                    Intensity.entries.indexOf(draft.perf.intensity).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(intensity = Intensity.entries[i]))
                }
                SettingOption("段间静音", ProduceConfig.GAP_OPTIONS.map { "${it}ms" },
                    ProduceConfig.GAP_OPTIONS.indexOf(draft.perf.gapMs).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(gapMs = ProduceConfig.GAP_OPTIONS[i]))
                }
                SettingOption("失败重做", ProduceConfig.RETRY_OPTIONS.map { "${it} 次" },
                    ProduceConfig.RETRY_OPTIONS.indexOf(draft.perf.gateRetries).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(gateRetries = ProduceConfig.RETRY_OPTIONS[i]))
                }
                TimingHint(TIMING_NEXT)
            }

            // ── ④ 音频开关：只写 draft，角色未绑定给可见提示 ────────────────
            MixerSection("④ 音频开关 · 上层", "switches", false, "对所有新任务生效") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("拟声表达", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (draft.nonVerbal) "开" else "关", fontSize = 11.sp, color = Sub)
                    Switch(checked = draft.nonVerbal,
                        onCheckedChange = { draft = draft.copy(nonVerbal = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("角色映射", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (draft.castEnabled) "开" else "关", fontSize = 11.sp, color = Sub)
                    Switch(checked = draft.castEnabled,
                        onCheckedChange = { draft = draft.copy(castEnabled = it) })
                }
                if (draft.castEnabled && unbound > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("有 $unbound 个角色未绑定音色：会沿用全书音色，不会静默变成另一个旁白声。",
                        fontSize = 10.5.sp, color = Warn)
                }
                TimingHint(TIMING_NOW)
            }

            // ── ⑤ 角色预设：补音色 chip / 未绑定 / 删除二次确认 ─────────────
            MixerSection("⑤ 角色预设", "casts", false, "可增删改") {
                if (casts.isEmpty()) Text("暂无角色卡", fontSize = 11.sp, color = Sub)
                casts.forEach { e ->
                    val bound = VoicePresets.ALL.firstOrNull { it.render() == e.description }
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.canonical, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    e.description.isBlank() -> "未绑定音色"
                                    bound != null -> "${bound.id} ${bound.name}"
                                    else -> "自定义音色"
                                },
                                fontSize = 10.sp,
                                color = if (e.description.isBlank()) Warn else Good)
                        }
                        TextButton(onClick = { editCastId = e.id; editCastText = e.description }) {
                            Text("编辑", fontSize = 11.sp)
                        }
                        TextButton(onClick = { gridTargetId = e.id }) { Text("换音色", fontSize = 11.sp) }
                        TextButton(onClick = { confirmDeleteId = e.id }) {
                            Text("删除", fontSize = 11.sp, color = Warn)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                OutlinedTextField(
                    value = newCastName, onValueChange = { newCastName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("新角色名", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                SettingOption("初始音色", VoicePresets.ALL.map { it.name },
                    VoicePresets.ALL.indexOfFirst { it.id == newCastGrid }.coerceAtLeast(0)) { i ->
                    newCastGrid = VoicePresets.ALL[i].id
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (CastPresets.add(c, newCastName, newCastGrid)) {
                            EventLog.log(EventLog.Kind.ENGINE, "MIXER_CAST", "新增角色 ${newCastName.trim()}")
                            newCastName = ""; castTick++
                        } else Toast.makeText(c, "名字为空或已存在", Toast.LENGTH_SHORT).show()
                    }) { Text("添加角色", fontSize = 11.5.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val list = VoiceCast.reconcile(c)
                        castTick++
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_CAST", "扫描标注角色，表内 ${list.size} 人")
                    }) { Text("扫描标注里的角色", fontSize = 11.sp) }
                }
            }

            // ── ⑥ 预设库：载入只写 draft；保存的是已生效配置，空名禁存 ──────
            MixerSection("⑥ 预设库", "presets", false, "可自定义") {
                presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(p.chips(), fontSize = 10.sp, color = Sub)
                        }
                        TextButton(onClick = { loadPreset(p) }) {
                            Text("载入为待提交", fontSize = 11.sp)
                        }
                        if (!p.builtin) {
                            TextButton(onClick = {
                                if (MixerPresetStore.delete(c, p.id)) {
                                    Mixer.logPreset("删除预设", p); tick++
                                    msg = "已删除「${p.name}」"
                                }
                            }) { Text("删除", fontSize = 11.sp, color = Warn) }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                OutlinedTextField(
                    value = presetName, onValueChange = { presetName = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("新预设名", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (presetName.trim().isEmpty()) {
                            msg = "先填预设名再保存"
                        } else {
                            val p = MixerPreset(
                                id = "", name = presetName,
                                voice = active.voice,
                                fields = emptyMap(),
                                knobs = active.knobs, perf = active.perf, builtin = false
                            )
                            if (MixerPresetStore.save(c, p)) {
                                Mixer.logPreset("保存自定义预设", p)
                                msg = "已存「${MixerPresetStore.safeName(presetName)}」（保存的是已生效配置）"
                                presetName = ""; tick++
                            } else msg = "保存失败"
                        }
                    }) { Text("把已生效配置存为预设", fontSize = 11.5.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        draft = draft.copy(knobs = TtsKnobs())
                        msg = "9 项已回到上游默认（待提交）"
                    }) { Text("恢复上游默认（待提交）", fontSize = 11.sp) }
                }
            }

            // ── ⑦ 降级为导出 / 诊断入口；全貌搬进「查看差异」抽屉 ──────────
            MixerSection("⑦ 导出与诊断", "snapshot", false, "只读") {
                Text("已生效 v$INSTRUCT_TEMPLATE_VERSION · 指纹 $stamp",
                    fontSize = 11.sp, color = Sub)
                Text("合成并发 1 · 语言 chinese · 24kHz 单声道 WAV",
                    fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    Mixer.logProfile(c, "手动导出当前配置到日志")
                    Toast.makeText(c, "已写进诊断日志，复制诊断信息即可贴出", Toast.LENGTH_SHORT).show()
                }) { Text("复制进诊断日志", fontSize = 12.sp) }
            }

            // ── ⑧ 触达矩阵 + 官方资料索引（一一对应，用户自己去看）────────
            MixerSection("⑧ 参数触达矩阵与官方资料", "trace", false, "只读 · O1~O7") {
                var last = ""
                Mixer.coverage().forEach { (g, item, kind) ->
                    if (g != last) {
                        last = g
                        Text(g, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(item, fontSize = 10.5.sp, color = Sub, modifier = Modifier.weight(1f))
                        Text(kind, fontSize = 10.5.sp,
                            color = when (kind) { "可编辑" -> Good; "上层" -> Warn; else -> Sub })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("触达链路", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Text("UI → Prefs.ttsKnobs → NativeEngine.ttsSynthesize → JNI → qt_tts_params → qt_synthesize",
                    fontSize = 10.sp, color = Sub)
                Text("日志核对：TTS_PARAMS（Kotlin 传出） / TTS_PARAMS_APPLIED（引擎实收）",
                    fontSize = 10.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                Text("官方资料（按名称自行查；App 无联网权限，不摆假链接）",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                ParamCatalog.DOCS.forEach { (name, where) ->
                    Spacer(Modifier.height(4.dp))
                    Text(name, fontSize = 10.5.sp, color = Primary)
                    Text(where, fontSize = 10.sp, color = Sub)
                }
                Spacer(Modifier.height(8.dp))
                rows.filter { it.group == "边界" }.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(r.label, fontSize = 10.5.sp, color = Sub, modifier = Modifier.weight(1f))
                        Text(r.value, fontSize = 10.5.sp, modifier = Modifier.weight(1.2f))
                    }
                }
            }

            // R41a · 试听已移进吸顶状态条那一块（草稿 / 已生效各一个按钮，一步合成并播放）。
            Spacer(Modifier.height(18.dp))
        }
    }

    // ── 查看差异：当前生效全貌（instruct 不截断）+ 逐项 旧→新 ──────────────
    if (showDiff) {
        AlertDialog(
            onDismissRequest = { showDiff = false },
            title = { Text("当前生效 / 待提交差异", fontSize = 14.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("当前生效全貌", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(r.label, fontSize = 10.5.sp, color = Sub,
                                modifier = Modifier.weight(1f))
                            Text(r.value, fontSize = 10.5.sp, modifier = Modifier.weight(1.35f))
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Text(if (dirty) "待提交 ${changes.size} 项" else "无待提交变更",
                        fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        color = if (dirty) Warn else Good)
                    changes.forEach { ch ->
                        CardBox(Modifier.padding(vertical = 3.dp)) {
                            Text(ch.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${ch.old} → ${ch.new}", fontSize = 11.sp, color = Primary)
                            Text(ch.timing, fontSize = 10.sp, color = Sub)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { commitAll(); showDiff = false }, enabled = dirty) {
                    Text("提交变更", fontSize = 12.sp)
                }
            },
            dismissButton = { TextButton(onClick = { showDiff = false }) { Text("关闭") } }
        )
    }

    // ── 离开确认：有未提交变更时别静默丢草稿 ──────────────────────────────
    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text("有未提交变更", fontSize = 14.sp) },
            text = { Text("还有 ${changes.size} 项改动没提交，离开会丢弃。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = { showLeave = false; discardAll(); onBack() }) {
                    Text("丢弃并离开", fontSize = 12.sp, color = Warn)
                }
            },
            dismissButton = { TextButton(onClick = { showLeave = false }) { Text("继续编辑") } }
        )
    }

    if (confirmDeleteId.isNotEmpty()) {
        val target = casts.firstOrNull { it.id == confirmDeleteId }
        AlertDialog(
            onDismissRequest = { confirmDeleteId = "" },
            title = { Text("删除角色", fontSize = 14.sp) },
            text = { Text("确定删除「${target?.canonical ?: ""}」？此角色的音色绑定会一起移除。",
                fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    CastPresets.remove(c, confirmDeleteId)
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_CAST",
                        "删除角色 ${target?.canonical ?: confirmDeleteId}")
                    confirmDeleteId = ""; castTick++
                }) { Text("删除", fontSize = 12.sp, color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = "" }) { Text("取消") } }
        )
    }

    if (editCastId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { editCastId = "" },
            title = { Text("角色音色描述", fontSize = 14.sp) },
            text = {
                OutlinedTextField(value = editCastText, onValueChange = { editCastText = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            },
            confirmButton = {
                TextButton(onClick = {
                    VoiceCast.setOverride(c, editCastId, editCastText, false)
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_CAST", "编辑角色 $editCastId")
                    editCastId = ""; castTick++
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editCastId = "" }) { Text("取消") } }
        )
    }
    if (gridTargetId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { gridTargetId = "" },
            title = { Text("换一个现成音色", fontSize = 14.sp) },
            text = {
                Column {
                    VoicePresets.ALL.forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable {
                            CastPresets.applyGrid(c, gridTargetId, p.id)
                            gridTargetId = ""; castTick++
                        }.padding(vertical = 5.dp)) {
                            Text("${p.id} ${p.name}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(p.chips(), fontSize = 10.sp, color = Sub)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { gridTargetId = "" }) { Text("关闭") } }
        )
    }
}
