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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import java.io.File

// R132：任务详情页 / 我的音频页的独立入口与目的地都撤了，能力已归进目录页三列。
private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, SETTINGS, MODELS, MIXER, CHAPTERS }

private class NavHost(
    initialStack: String,
    private val read: () -> Screen,
    private val write: (Screen) -> Unit,
    private val persist: (String) -> Unit
) {
    private var stack = initialStack
    private fun parts(): List<String> = stack.split(',').filter { it.isNotEmpty() }
    fun canPop(): Boolean = parts().isNotEmpty()
    fun push(target: Screen) {
        val cur = read()
        if (target == cur) return
        stack = (parts() + cur.name).joinToString(",")
        persist(stack)
        write(target)
    }
    fun pop() {
        val p = parts()
        if (p.isEmpty()) {
            write(Screen.LIBRARY)
            return
        }
        write(runCatching { Screen.valueOf(p.last()) }.getOrDefault(Screen.LIBRARY))
        stack = p.dropLast(1).joinToString(",")
        persist(stack)
    }
    fun clear() {
        stack = ""
        persist(stack)
    }
}

private val Bg: Color get() = palette.bg
private val Card: Color get() = palette.card
private val Primary: Color get() = palette.primary
private val PrimarySoft: Color get() = palette.primarySoft
private val Sub: Color get() = palette.sub
private val Good: Color get() = palette.good
private val Line: Color get() = palette.line
private val Warn: Color get() = palette.warn

class MainActivity : ComponentActivity() {
    private val incomingFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyAppTheme(this, Prefs.themeMode(this))
        PlayQueue.attach(this)
        pushIncoming(intent)
        setContent {
            TingbenTheme {
                val incoming by incomingFlow.collectAsState()
                App(incoming = incoming, onConsumed = { incomingFlow.value = null })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushIncoming(intent)
    }

    private fun pushIncoming(i: android.content.Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("open_tasks", false)) {
            incomingFlow.value = System.currentTimeMillis().toString() + "|tasks:"
            return
        }
        // 播放控制通知主体：回到软件并直接展开播放器页（R133）
        if (i.getBooleanExtra("open_player", false)) {
            incomingFlow.value = System.currentTimeMillis().toString() + "|player:"
            return
        }
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
        incomingFlow.value = System.currentTimeMillis().toString() + "|" + (raw ?: "empty:")
    }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(incoming: String? = null, onConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var miniBook by rememberSaveable { mutableStateOf<String?>(null) }
    var lastSelection by rememberSaveable { mutableStateOf<Set<Int>?>(null) }
    var taskBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskChapter by rememberSaveable { mutableStateOf<Int?>(null) }
    // 从别处跳进书时落在第几列：0 章节 / 1 合成(任务) / 2 可播(音频)
    var hubCol by rememberSaveable { mutableStateOf(0) }
    var refresh by rememberSaveable { mutableStateOf(0) }
    var modelsFrom by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    var inputReadOpen by rememberSaveable { mutableStateOf(false) }
    // 底部常驻条点开 / 通知点进来，展开同一个全屏播放器浮层
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    val importBytesFlow = remember { MutableStateFlow(0L) }
    val importBytes by importBytesFlow.collectAsState()
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var bookImportError by rememberSaveable { mutableStateOf<String?>(null) }

    var navStack by rememberSaveable { mutableStateOf("") }
    val nav = remember { NavHost(navStack, { screen }, { screen = it }, { navStack = it }) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }

    // 从任务 / 音频 / 制作完成等入口跳进书时，统一落到目录页并指定目标列。
    // 必须放在 nav 声明之后：局部函数不能引用尚未声明的局部变量。
    fun openBookCol(id: String?, col: Int) {
        if (id != null) bookId = id
        taskBookId = id ?: taskBookId
        hubCol = col.coerceIn(0, 2)
        nav.push(Screen.CHAPTERS)
    }

    // 全局唯一回退出口：有栈就回来源页，栈被清掉就按统一返回表兜底。
    fun goBack() {
        if (nav.canPop()) nav.pop()
        else {
            screen = backTargetOf(screen, modelsFrom, drawerReturn)
            nav.clear()
        }
    }
    val backAction: () -> Unit = { goBack() }
    BackHandler(enabled = canGoBack(screen) || nav.canPop()) { goBack() }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    KeepScreenOn()

    // 开软件时自动接上被中断的制作任务；只跑一次（Unit key），旋转/切主题不重跑。
    LaunchedEffect(Unit) {
        val hint = Producer.autoResume(ctx)
        if (hint != null) Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
    }

    fun importBook(uri: Uri?, tag: String = "PICKER") {
        if (uri == null) {
            EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_NONE", "$tag 没拿到文件")
            Toast.makeText(ctx, "没拿到文件，换个方式再试", Toast.LENGTH_SHORT).show()
            return
        }
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
                    val why = result.exceptionOrNull()?.message ?: "换个文件试试"
                    EventLog.log(EventLog.Kind.CRASH, "BOOK_IMPORT_FAIL", "$tag $why")
                    bookImportError = why
                    Toast.makeText(ctx, "这本书没读进来：$why", Toast.LENGTH_LONG).show()
                } else {
                    bookImportError = null
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

    var pendingIncoming by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(incoming) { if (incoming != null) pendingIncoming = incoming }
    LaunchedEffect(pendingIncoming) {
        val full = pendingIncoming ?: return@LaunchedEffect
        pendingIncoming = null
        onConsumed()
        val raw = full.substringAfter('|', full)
        when {
            raw == "tasks:" -> {
                screen = Screen.TASKS
                refresh++
            }
            raw == "player:" -> { playerOpen = true }
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
    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importError = null
        EventLog.log(EventLog.Kind.MODEL, "MODEL_IMPORT_BEGIN", "${uris.size} 个文件")
        scope.launch(Dispatchers.IO) {
            var totalBytes = 0L
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) { }
                val name = queryName(ctx, uri).ifBlank {
                    uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
                }
                withContext(Dispatchers.Main) {
                    importing = name
                    importBytesFlow.value = totalBytes
                }
                val result = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { s ->
                        ModelStore.import(ctx, name, s) { n ->
                            importBytesFlow.value = totalBytes + n
                        }
                    } ?: throw IllegalStateException("打不开这个文件")
                }
                val f = result.getOrNull()
                if (f != null) {
                    totalBytes += f.length()
                } else {
                    failures.add("$name：${result.exceptionOrNull()?.message ?: "导入失败"}")
                }
            }
            withContext(Dispatchers.Main) {
                importing = null
                if (failures.isNotEmpty()) {
                    importError = failures.joinToString("；")
                    Toast.makeText(ctx, "有 ${failures.size} 个没导入成功：$importError",
                        Toast.LENGTH_LONG).show()
                } else {
                    refresh++
                    val left = ModelStore.missing(ctx)
                    if (left.isEmpty()) {
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

    val resumeTask: (TaskInfo) -> Unit = { t ->
        val b = BookStore.list(ctx).find { x -> x.id == t.bookId }
        if (b != null) {
            bookId = b.id; taskBookId = b.id
            taskChapter = TaskResume.currentChapter(ctx, t)
            lastSelection = t.only
            Producer.start(ctx, b, t.only, true, t.think, t.config)
            if (taskChapter != null) nav.push(Screen.PROGRESS)
            else openBookCol(b.id, 1)
        } else {
            Toast.makeText(ctx, "这本书已经从书架删掉了，任务没法续跑", Toast.LENGTH_LONG).show()
        }
    }
    val chrome = chromeOf(screen).let { base ->
        when {
            screen == Screen.PROGRESS && taskChapter != null -> base.copy(title = "本章进度")
            else -> base
        }
    }
    val drawerSelected = when (screen) {
        Screen.LIBRARY, Screen.PRODUCE, Screen.CHAPTERS -> NAV_LIBRARY
        Screen.PROGRESS, Screen.TASKS -> NAV_TASKS
        Screen.SETTINGS -> NAV_SETTINGS
        Screen.MIXER -> NAV_MIXER
        Screen.MODELS -> NAV_MODELS
    }
    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            AppDrawerSheet(
                selected = drawerSelected,
                onNavigate = { idx ->
                    if (idx == NAV_INPUT) {
                        inputReadOpen = true
                        scope.launch { drawerState.close() }
                    } else {
                        val from = when (screen) {
                            Screen.LIBRARY, Screen.TASKS,
                            Screen.PRODUCE, Screen.PROGRESS -> screen
                            else -> drawerReturn
                        }
                        drawerReturn = from
                        if (idx == NAV_MODELS) modelsFrom = from
                        val target = when (idx) {
                            NAV_LIBRARY -> Screen.LIBRARY
                            NAV_TASKS -> Screen.TASKS
                            NAV_SETTINGS -> Screen.SETTINGS
                            NAV_MIXER -> Screen.MIXER
                            NAV_MODELS -> Screen.MODELS
                            else -> Screen.LIBRARY
                        }
                        if (target == Screen.LIBRARY) {
                            screen = Screen.LIBRARY
                            nav.clear()
                        } else {
                            nav.push(target)
                        }
                        if (idx == NAV_SETTINGS) refresh++
                        scope.launch { drawerState.close() }
                    }
                },
                surface = Card,
                primary = Primary,
                sub = Sub
            )
        }
    ) {
        Box(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
        AppTopBar(
            spec = chrome,
            onMenu = { scope.launch { drawerState.open() } },
            onImport = { openBookPicker() },
            surface = Card,
            primary = Primary,
            onBack = if (chrome.canBack) backAction else null
        )
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; nav.push(Screen.CHAPTERS) },
                    onModels = { modelsFrom = Screen.LIBRARY; nav.push(Screen.MODELS) },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ },
                    importError = bookImportError,
                    onDismissImportError = { bookImportError = null })
                Screen.CHAPTERS -> ChapterHubScreen(ctx, bookId, refresh,
                    initialCol = hubCol,
                    onBack = { nav.pop() },
                    onResumeTask = {
                        TaskResume.byBook(ctx, bookId)?.let { resumeTask(it) }
                    },
                    onProgress = { taskBookId = bookId; nav.push(Screen.TASKS) },
                    onPlayBook = { miniBook = it },
                    onMixer = { drawerReturn = Screen.CHAPTERS; nav.push(Screen.MIXER) },
                    onStarted = { id, sel ->
                        taskBookId = id; lastSelection = sel
                        openBookCol(id, 1)
                    })
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { nav.pop() },
                    onProgress = { taskBookId = bookId; taskChapter = null; nav.push(Screen.PROGRESS) },
                    onDone = { nav.clear(); openBookCol(bookId, 2); refresh++ },
                    onStarted = { taskBookId = bookId; lastSelection = it })
                Screen.PROGRESS -> ProgressScreen(ctx, taskBookId, taskChapter,
                    onBack = { nav.pop() },
                    onResume = {
                        val t = Producer.resume.value
                        val b = if (t != null) BookStore.list(ctx).find { x -> x.id == t.bookId }
                                else bookId?.let { BookStore.list(ctx).find { x -> x.id == it } }
                        if (b != null) {
                            val sel = t?.only ?: lastSelection
                            bookId = b.id; lastSelection = sel
                            Producer.start(ctx, b, sel, true,
                                if (t != null) t.think else null,
                                t?.config ?: ProduceConfig())
                        }
                    })
                Screen.TASKS -> TaskCenterScreen(ctx, refresh,
                    onOpenTask = { id -> openBookCol(id, 1) },
                    onProgress = {
                        val t = Producer.resume.value
                        taskBookId = t?.bookId ?: taskBookId
                        taskChapter = t?.let { TaskResume.currentChapter(ctx, it) }
                        nav.push(Screen.PROGRESS)
                    },
                    onAudio = { openBookCol(taskBookId, 2); refresh++ },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onResume = resumeTask)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; nav.push(Screen.MODELS) },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onMixer = { drawerReturn = Screen.SETTINGS; nav.push(Screen.MIXER) })
                Screen.MIXER -> MixerScreen(ctx, onBack = { nav.pop() })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { nav.pop() },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onChanged = { refresh++ })
            }
        }

        MiniPlayBar(ctx, miniBook,
            onExpand = { playerOpen = true },
            onGoBook = { id ->
                bookId = id
                nav.push(Screen.CHAPTERS)
            })
        }
        // 播放器是覆盖全屏的浮层，不进 Screen 枚举 / 导航栈：
        // 它不是「另一页」，是同一份 PlayQueue 状态的展开视图。
        if (playerOpen) {
            PlayerSheet(ctx, miniBook ?: bookId,
                onClose = { playerOpen = false },
                onGoBook = { id ->
                    bookId = id
                    nav.push(Screen.CHAPTERS)
                })
        }
        if (inputReadOpen) {
            InputReadScreen(ctx,
                onBack = { inputReadOpen = false },
                onAdded = {
                    inputReadOpen = false
                    screen = Screen.LIBRARY
                    nav.clear()
                    refresh++
                })
        }
    }
    }
}

@Composable
private fun KeepScreenOn() {
    val st by Producer.state.collectAsState()
    val act = LocalContext.current as? android.app.Activity
    DisposableEffect(st.running) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (st.running) act?.window?.addFlags(flag) else act?.window?.clearFlags(flag)
        onDispose { act?.window?.clearFlags(flag) }
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

// 统一返回表（R130）：哪一页返回到哪一页，只在这里定义一次。
// 根级页（书架 / 任务 / 设置）靠抽屉切换，没有返回键；
// 二级页一律回自己的来源，栈被清掉时也按这张表兜底，不会莫名跳回书架。
private val ROOT_SCREENS = setOf(
    Screen.LIBRARY, Screen.TASKS, Screen.SETTINGS)

private fun canGoBack(s: Screen): Boolean = s !in ROOT_SCREENS

private fun backTargetOf(s: Screen, modelsFrom: Screen, drawerReturn: Screen): Screen =
    when (s) {
        Screen.LIBRARY, Screen.TASKS, Screen.SETTINGS -> Screen.LIBRARY
        Screen.CHAPTERS, Screen.PRODUCE -> Screen.LIBRARY
        Screen.PROGRESS -> Screen.TASKS
        Screen.MODELS -> modelsFrom
        Screen.MIXER -> drawerReturn
    }

private fun chromeOf(s: Screen): ChromeSpec = when (s) {
    Screen.LIBRARY  -> ChromeSpec("书架", TAB_LIBRARY, showImport = true)
    Screen.PRODUCE  -> ChromeSpec("制作", TAB_LIBRARY, canBack = true)
    Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY, canBack = true)
    Screen.PROGRESS -> ChromeSpec("进度", TAB_TASKS, canBack = true)
    Screen.TASKS    -> ChromeSpec("任务", TAB_TASKS)
    Screen.SETTINGS -> ChromeSpec("设置")
    Screen.MODELS   -> ChromeSpec("模型", canBack = true)
    Screen.MIXER    -> ChromeSpec("调音台", canBack = true)
}

@Composable
private fun CardBox(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(mod.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit,
                           onModels: () -> Unit, onImportBook: () -> Unit,
                           onChanged: () -> Unit,
                           importError: String? = null,
                           onDismissImportError: () -> Unit = {}) {
    val books = remember(refresh) { BookStore.list(c) }
    var lastReads by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(refresh, books) {
        lastReads = withContext(Dispatchers.IO) { ReadingState.allLast(c) }
    }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item(key = "head") {
            RefreshStrip(
                onRefresh = onChanged,
                primary = Primary,
                primarySoft = PrimarySoft,
                sub = Sub
            )
            Spacer(Modifier.height(6.dp))
        }
        if (importError != null) {
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
            item(key = "count") {
                Text("共 ${books.size} 本", fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
            }
            items(books, key = { it.id }) { b ->
                val lastRead = lastReads[b.id]
                CardBox(Modifier.padding(bottom = 12.dp).clickable { onOpen(b.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(36.dp)
                            .background(Primary, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                maxLines = 1, color = palette.text)
                            Spacer(Modifier.height(4.dp))
                            Text("${b.chars} 字 · 本地离线", fontSize = 12.sp, color = Sub)
                            if (lastRead != null) {
                                Spacer(Modifier.height(7.dp))
                                Text("上次读到 第 ${lastRead + 1} 章", fontSize = 12.sp,
                                    color = Primary,
                                    modifier = Modifier
                                        .background(PrimarySoft, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("›", fontSize = 20.sp, color = Sub)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = b.id }) {
                            Text("删除", fontSize = 12.sp, color = Sub)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputReadScreen(c: Context, onBack: () -> Unit, onAdded: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 20.sp, color = Primary,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
            Text("输入朗读", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = palette.text)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("要念的正文", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = TextStyle(fontSize = 14.sp)
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val got = clipboard.getText()?.text ?: ""
                if (got.isNotBlank()) text = got
                else Toast.makeText(c, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
            }) { Text("粘贴", fontSize = 12.sp) }
            TextButton(onClick = { text = "" }) { Text("清除", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                if (text.isBlank()) {
                    Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                } else {
                    BookStore.add(c, "粘贴的文字", text)
                    onAdded()
                    Toast.makeText(c, "书加好了", Toast.LENGTH_SHORT).show()
                }
            }) { Text("建书", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun ProduceScreen(c: Context, id: String?, refresh: Int, onBack: () -> Unit,
                          onProgress: () -> Unit, onDone: () -> Unit,
                          onStarted: (Set<Int>?) -> Unit) {
    val book = remember(id, refresh) { id?.let { BookStore.list(c).find { b -> b.id == it } } }
    val st by Producer.state.collectAsState()
    val split = remember(book) { book?.let { ChapterSplitter.split(BookStore.read(c, it)) } }
    val chapters = split?.first ?: emptyList()
    val allChunks = split?.second ?: emptyList()
    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    var cacheTick by remember { mutableStateOf(0) }
    var cfg by remember(book, refresh) {
        val t = TaskResume.load(c)
        mutableStateOf(if (t != null && t.bookId == id) t.config else Prefs.defaultPerf(c))
    }
    val estimate = remember(selected, book) {
        if (selected.isEmpty() || selected.size == chapters.size) allChunks.size
        else allChunks.count { it.chapter in selected }
    }
    val hasCached = remember(book, refresh, selected, cacheTick) {
        book?.let { Producer.hasProgress(c, it.id,
            if (selected.isEmpty() || selected.size == chapters.size) null else selected) } ?: false
    }
    if (book == null) {
        Column(Modifier.padding(16.dp)) { Text("这本书不在了"); TextButton(onClick = onBack) { Text("返回书架") } }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("${chapters.size} 章 · ${book.chars} 字", fontSize = 13.sp, color = Sub)
        Spacer(Modifier.height(5.dp))
        Text("按章切分 → 逐段送模型 → 每段落盘 → 拼成整章；正文不改，失败即停，续跑不重算",
            fontSize = 12.sp, color = Sub)
        Spacer(Modifier.height(14.dp))

        if (st.output != null) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("做好了", fontWeight = FontWeight.Bold, color = Good)
                Spacer(Modifier.height(4.dp))
                Text(st.message, fontSize = 13.sp, color = Sub)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("去听") }
            }
        }

        var showAllChapters by remember(book) { mutableStateOf(false) }

        ExpandGroup("① 做什么", "") {
            Text("原文整段送声音模型", fontSize = 13.sp, color = Sub)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("制作范围", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == chapters.size) emptySet() else chapters.map { it.index }.toSet() }) {
                    Text(if (selected.size == chapters.size) "清空" else "全选")
                }
            }
            Text(if (selected.isEmpty()) "整本 · 预计约 $estimate 块" else "已选 ${selected.size} 章 · 预计约 $estimate 块",
                fontSize = 12.sp, color = Sub)
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
                        Text("${ch.index + 1}. ${ch.title.take(28)}", fontSize = 13.sp, maxLines = 1)
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

        ExpandGroup("② 怎么念", cfg.summary()) {
            OptionRow("朗读档位", "",
                ReadingStyle.values().map { it to it.label }, cfg.style) { cfg = cfg.copy(style = it) }
            OptionRow("语速倾向", "",
                Pace.values().map { it to it.label }, cfg.pace) { cfg = cfg.copy(pace = it) }
            OptionRow("情绪强度", "",
                Intensity.values().map { it to it.label }, cfg.intensity) { cfg = cfg.copy(intensity = it) }
        }

        ExpandGroup("③ 怎么跑", "") {
            OptionRow("段间静音", "",
                ProduceConfig.GAP_OPTIONS.map { it to "${it}ms" }, cfg.gapMs) { cfg = cfg.copy(gapMs = it) }
            OptionRow("分段失败重做", "",
                ProduceConfig.RETRY_OPTIONS.map { it to "${it}次" }, cfg.gateRetries) { cfg = cfg.copy(gateRetries = it) }
            TextButton(onClick = { cfg = Prefs.defaultPerf(c) }) { Text("恢复默认", fontSize = 12.sp) }
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                val sel = if (selected.isEmpty() || selected.size == chapters.size) null else selected
                onStarted(sel)
                Producer.start(c, book, sel, directRead = true, config = cfg)
                onProgress()
            },
            enabled = !st.running,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (st.running) "正在制作…"
                else if (hasCached) "继续合成"
                else "合成音频",
                fontSize = 16.sp
            )
        }

        if (hasCached && !st.running) {
            TextButton(onClick = {
                Producer.clearProgress(c, book.id,
                    if (selected.isEmpty() || selected.size == chapters.size) null else selected)
                cacheTick++
            }, Modifier.fillMaxWidth()) { Text("从头重做（清掉已做的块）", fontSize = 12.sp) }
        }

        if (st.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(st.message, fontSize = 13.sp, color = if (st.output != null) Good else Sub)
        }
    }
}

@Composable
private fun ProgressScreen(c: Context, bookId: String?, chapter: Int?,
                            onBack: () -> Unit, onResume: () -> Unit) {
    val st by Producer.state.collectAsState()
    val pct = if (st.total > 0) st.done.toFloat() / st.total else 0f
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) return@LaunchedEffect
        while (true) { delay(1000); tick++ }
    }

    val heartbeat = remember(tick) {
        val (act, _) = EventLog.lastEngine()
        val age = (EventLog.lastEngineAgeMs() / 1000).coerceAtLeast(0)
        val what = when {
            act.contains("PREFILL") -> "正在把这一段读进模型，手机上这一步可能要一两分钟"
            act.contains("DECODE") -> "正在逐字生成这一块的朗读分段"
            act.contains("TOKENIZE") || act.contains("ENTER") -> "正在准备文字"
            act.contains("TTS") -> "正在把文字读成声音"
            else -> "正在准备"
        }
        val extra = if (st.running && age > 100) " · 已 ${age} 秒没新动作，可能算得很慢" else ""
        "$what$extra"
    }

    var redoNonce by remember { mutableStateOf(0) }
    val seenTask = remember(st.done, redoNonce, bookId) {
        bookId?.let { TaskResume.byBook(c, it) } ?: TaskResume.load(c)
    }
    val allSegs = remember(st.done, seenTask, redoNonce) { Workbench.segments(c, seenTask) }
    val segChapters = remember(seenTask) { Workbench.segChapters(c, seenTask) }
    var treeChapter by remember { mutableStateOf<ChapterAudio?>(null) }
    LaunchedEffect(bookId, chapter, st.done, redoNonce) {
        treeChapter = if (bookId == null || chapter == null) null else
            withContext(Dispatchers.IO) {
                AudioTree.load(c).firstOrNull { it.bookId == bookId }
                    ?.chapters?.firstOrNull { it.chapter == chapter }
            }
    }
    val mergedOnly = chapter != null && treeChapter?.segments.isNullOrEmpty() &&
        treeChapter?.merged?.isFile == true
    // 看台骨架只认「计划段」：已出声的绿、没出声的灰，段数恒定。
    // 音频树里只有落盘的小块，拿它当列表来源会让未合成的灰格整片消失，
    // 再随合成一块块冒出来（页面切出去再回来尤其明显，R128）。
    // 这里改成：树只负责告诉骨架「这一段已经有音频了」，不决定列表长度。
    val plannedSegs = if (chapter != null && segChapters != null)
        allSegs.filter { segChapters[it.index] == chapter } else allSegs
    // 只认还在盘上的小块：批量删段后索引若还留着 File，格子不该继续显示绿
    val treeWav = treeChapter?.segments?.associate { (it.seq - 1) to it.file }
        ?.filterValues { it.isFile && it.length() > 44 }
    val segs = plannedSegs.mapIndexed { i, r ->
        val w = r.wav ?: treeWav?.get(r.index)
        r.copy(index = i, wav = w, wavName = w?.name ?: r.wavName)
    }
    val rest = remember(st.done, seenTask, redoNonce) { Workbench.remainingCount(c, seenTask) }
    val verdict = remember(st.done, seenTask, redoNonce) { Workbench.verdict(c, seenTask) }
    var showCompare by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val pqSeg by PlayQueue.state.collectAsState()
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(st.running) { if (!st.running) stopping = false }
    // R134 · 分段看台多选态：长按格子进入、方块勾选、底部条批量操作
    var segMulti by remember { mutableStateOf(false) }
    var segChecked by remember { mutableStateOf(setOf<Int>()) }
    var confirmSegDel by remember { mutableStateOf<List<File>?>(null) }
    fun exitSegMulti() {
        segMulti = false
        segChecked = emptySet()
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        if (chapter != null) {
            Text(treeChapter?.title ?: "第 ${chapter + 1} 章",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Primary)
            Spacer(Modifier.height(3.dp))
            Text(if (mergedOnly) "第 ${chapter + 1} 章 · 已拼成整章音频"
                else "第 ${chapter + 1} 章 · 本章 ${segs.count { it.hasAudio }}/${segs.size} 段",
                fontSize = 12.sp, color = Sub)
            if (mergedOnly) {
                Spacer(Modifier.height(4.dp))
                Text("这一章已拼成整章音频，小块已清理", fontSize = 12.sp, color = Good)
            } else if (segChapters == null && treeChapter == null) {
                Spacer(Modifier.height(4.dp))
                Text("旧记录没记章，这里显示整次分段", fontSize = 12.sp, color = Warn)
            }
            Spacer(Modifier.height(10.dp))
        }

        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val canStop = st.running
                val canResume = !st.running && st.stage == "已停止"
                if (canStop || canResume) {
                    Box(
                        Modifier.size(42.dp, 34.dp)
                            .background(if (canStop) PrimarySoft else Primary,
                                RoundedCornerShape(8.dp))
                            .clickable(enabled = !stopping) {
                                if (canStop) { stopping = true; Producer.stop() } else onResume()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (canStop) "⏸" else "▶", fontSize = 15.sp,
                            color = if (canStop) Primary else Card)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { if (mergedOnly) 1f
                            else if (chapter != null && segs.isNotEmpty())
                            segs.count { it.hasAudio }.toFloat() / segs.size else pct },
                        modifier = Modifier.fillMaxWidth().height(6.dp))
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.stage, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        Text(if (mergedOnly) "100%" else
                            if (chapter != null && segs.isNotEmpty())
                            "${(segs.count { it.hasAudio } * 100 / segs.size)}%" else
                            if (st.total > 0) "${(pct * 100).toInt()}%" else "—",
                            fontSize = 12.sp, color = Primary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (mergedOnly) "这一章已拼成整章音频" +
                        if (st.total > 0) " · 整本 ${st.done}/${st.total} 块" else ""
                else if (chapter != null) "本章 ${segs.count { it.hasAudio }}/${segs.size} 段" +
                        if (st.total > 0) " · 整本 ${st.done}/${st.total} 块" else ""
                else if (st.total > 0) "已完成 ${st.done}/${st.total} 块" +
                        if (st.failed > 0) " · ${st.failed} 块改走直读" else ""
                else st.message.ifBlank { "还没开始" },
                fontSize = 12.sp, color = Sub)
            if (st.running) {
                Spacer(Modifier.height(6.dp))
                Text(heartbeat, fontSize = 12.sp, color = Primary)
            }
        }

        Spacer(Modifier.height(10.dp))
        val steps = listOf("拆章", "生成声音", "整理音频", "完成")
        val curStep = when {
            st.output != null -> 4
            st.stage.contains("分章") -> 0
            st.stage.contains("生成声音") -> 1
            st.stage.contains("整理") -> 2
            st.stage == "完成" -> 3
            else -> -1
        }
        if (chapter == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEachIndexed { i, stepName ->
                    val doneStep = curStep > i
                    val nowStep = curStep == i
                    Column(Modifier.weight(1f)) {
                        Text(stepName, fontSize = 12.sp, maxLines = 1,
                            textAlign = TextAlign.Center,
                            fontWeight = if (nowStep || doneStep) FontWeight.Bold else FontWeight.Normal,
                            color = if (nowStep || doneStep) Primary else Sub,
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).background(
                            if (doneStep) Primary else Color.Transparent, RoundedCornerShape(50)))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        CardBox {
            if (mergedOnly) {
                Text("整章音频", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val f = treeChapter?.merged ?: return@Button
                    PlayQueue.setTracks(listOf(Track(f, treeChapter?.title ?: "整章",
                        id = "merged:${f.absolutePath}")), "chapterMerged")
                    PlayQueue.playFrom(0, id = "chapterMerged")
                }) { Text("▶ 播这一章", fontSize = 13.sp) }
            } else {
            Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (segs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("还没有分段", fontSize = 12.sp, color = Sub)
            } else {
                Spacer(Modifier.height(9.dp))
                segs.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { sg ->
                            val label = "第 ${sg.index + 1} 段"
                            val segId = sg.wav?.let { "seg:" + it.absolutePath } ?: ""
                            val playable = sg.hasAudio && segId.isNotEmpty()
                            val onThis = playable && pqSeg.trackId == segId
                            val playing = onThis && pqSeg.isPlaying
                            val base = if (playable) Good else Line
                            Box(
                                Modifier.size(30.dp)
                                    .background(
                                        if (playing) PrimarySoft else base.copy(alpha = 0.18f),
                                        RoundedCornerShape(8.dp))
                                    // 播放态与勾选集合都进 key，避免闭包捕获旧状态
                                    .pointerInput(sg.index, segMulti, segChecked,
                                        playable, playing, onThis) {
                                        detectTapGestures(
                                            onLongPress = {
                                                if (playable) {
                                                    segMulti = true
                                                    segChecked = setOf(sg.index)
                                                }
                                            },
                                            onTap = {
                                                if (segMulti) {
                                                    if (playable) segChecked =
                                                        if (sg.index in segChecked)
                                                            segChecked - sg.index
                                                        else segChecked + sg.index
                                                } else if (playing) PlayQueue.pause()
                                                else if (onThis) PlayQueue.resume()
                                                else sg.wav?.let { wav ->
                                                    PlayQueue.setTracks(listOf(Track(wav, label, id = segId)), "seg")
                                                    PlayQueue.playFrom(0, id = "seg")
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (segMulti) {
                                    SelectBox(checked = sg.index in segChecked,
                                        tint = Primary, boxColor = Card,
                                        modifier = Modifier.size(18.dp))
                                } else {
                                    Text("${sg.index + 1}", fontSize = 12.sp,
                                        color = if (playing) Primary else base)
                                    if (playable)
                                        Text(if (playing) "⏸" else "▶", fontSize = 12.sp, color = Good,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
            }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
    if (segMulti) {
        MultiSelectBar(
            count = segChecked.size,
            onSelectAll = {
                segChecked = segs.filter { it.hasAudio }.map { it.index }.toSet()
            },
            onCancel = { exitSegMulti() },
            allLabel = "全选已生成",
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            TextButton(onClick = {
                val picked = segChecked.sorted().mapNotNull { i ->
                    segs.getOrNull(i)?.wav?.let { w ->
                        Track(w, "第 ${i + 1} 段", id = "seg:${w.absolutePath}")
                    }
                }
                if (picked.isEmpty()) {
                    Toast.makeText(c, "先勾几个已生成的小块", Toast.LENGTH_SHORT).show()
                } else {
                    // preview 队列：不占常驻条、不进播放器页、不进通知栏（R133 两态隔离）
                    PlayQueue.setTracks(picked, "seg")
                    PlayQueue.playFrom(0, id = "seg")
                    exitSegMulti()
                }
            }) { Text("试听选中", fontSize = 12.sp, color = Primary) }
            TextButton(onClick = {
                val files = segChecked.sorted().mapNotNull { i -> segs.getOrNull(i)?.wav }
                if (files.isEmpty()) Toast.makeText(c, "先勾几个已生成的小块",
                    Toast.LENGTH_SHORT).show()
                else confirmSegDel = files
            }) { Text("删除选中", fontSize = 12.sp, color = Warn) }
        }
    }
    }

    if (confirmSegDel != null) {
        val files = confirmSegDel!!
        AlertDialog(
            onDismissRequest = { confirmSegDel = null },
            title = { Text("删除这 ${files.size} 个小块？", fontSize = 15.sp,
                fontWeight = FontWeight.Bold) },
            text = { Text("只删已生成的 WAV，分段计划还在；下次续跑会按原顺序重新生成，" +
                "已拼好的整章音频不受影响。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val failed = AudioOps.deleteAll(files)
                    AudioTree.forget(c, files.filter { !it.isFile }.map { it.absolutePath })
                    if (bookId != null && chapter != null)
                        AudioTree.clearDone(c, bookId, chapter)
                    PlayQueue.release()
                    Toast.makeText(c, if (failed > 0) "有 $failed 个没删干净"
                        else "已删除 ${files.size} 个小块", Toast.LENGTH_SHORT).show()
                    confirmSegDel = null
                    exitSegMulti()
                    redoNonce++
                }) { Text("删除", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmSegDel = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TaskCenterScreen(c: Context, refresh: Int, onOpenTask: (String) -> Unit,
                             onProgress: () -> Unit,
                             onAudio: () -> Unit, onLibrary: () -> Unit,
                             onResume: (TaskInfo) -> Unit) {
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
                        Text("队列已暂停 · 停止或失败后不会自动往下跑",
                            fontSize = 12.sp, color = Warn)
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
                    Text("没做完 ${unfinished.size} 本 · 点「继续制作」接着跑",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Warn,
                        modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            items(unfinished + finishedTasks, key = { it.bookId }) { t ->
                val runningThis = st.running && live?.bookId == t.bookId
                val badge = when {
                    runningThis -> "进行中"
                    t.phase == "done" -> "已完成"
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
                    if (!runningThis && t.phase != "done") {
                        Button(onClick = { onResume(t) }, enabled = !otherBusy,
                            modifier = Modifier.fillMaxWidth().height(42.dp)) {
                            Text("继续制作 · 接着已做好的块跑", fontSize = 13.sp)
                        }
                        if (otherBusy) {
                            Text("另一本正在制作，等它跑完或先停它。",
                                fontSize = 12.sp, color = Warn)
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        if (runningThis) {
                            TextButton(onClick = onProgress) {
                                Text("看实时进度 ›", fontSize = 12.sp)
                            }
                        }
                        if (t.phase == "done") {
                            TextButton(onClick = onAudio) {
                                Text("去「我的音频」试听 ›", fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("看章节 ›", fontSize = 12.sp, color = Primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String, badge: String, open: Boolean = true, onToggle: (Boolean) -> Unit = {},
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth()
                .let { if (collapsible) it.clickable { onToggle(!open) } else it },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (collapsible) {
                Text(if (open) "收起 ⌃" else "展开 ›", fontSize = 12.sp, color = Sub)
            }
        }
        if (!collapsible || open) {
            Spacer(Modifier.height(7.dp))
            content()
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit,
                           onLibrary: () -> Unit,
                           onMixer: () -> Unit) {
    val missing = remember(refresh) { ModelStore.missing(c) }
    val modeCount = remember(refresh) { ModelStore.installedModes(c).size }
    var themeMode by remember { mutableStateOf(Prefs.themeMode(c)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (missing.isEmpty()) "模型已就绪 · $modeCount 个模式"
                 else "还差：" + missing.joinToString("、"),
                fontSize = 13.sp, color = if (missing.isEmpty()) Good else Warn,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onModels) { Text("查看 ›", fontSize = 12.sp) }
        }
        Text("软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME +
             " · 本机 Android " + android.os.Build.VERSION.RELEASE +
             " / " + (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
            fontSize = 12.sp, color = Sub)

        SettingsSection("外观与显示", "软件", collapsible = false) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("主题", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("切换立即生效", fontSize = 12.sp, color = Sub)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, m) ->
                    TextButton(onClick = {
                        themeMode = m
                        Prefs.setThemeMode(c, m)
                        applyAppTheme(c, m)
                    }, modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                        Text(label, fontSize = 12.sp,
                            color = if (themeMode == m) Primary else Sub)
                    }
                }
            }
        }

        SettingsSection("本机数据", "本机", collapsible = false) {
            ClearSegmentsRow(c)
        }

        SettingsSection("通知与权限", "系统", collapsible = false) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通知权限", fontSize = 13.sp)
                    Text("制作进度与播放控制都用通知栏；本机不做系统悬浮窗",
                        fontSize = 12.sp, color = Sub)
                }
                Text("去开启 ›", fontSize = 12.sp, color = Primary,
                    modifier = Modifier.clickable {
                        try {
                            val i = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
                            c.startActivity(i)
                        } catch (_: Throwable) {
                            Toast.makeText(c, "这台机器没有通知设置入口", Toast.LENGTH_SHORT).show()
                        }
                    })
            }
        }
    }
}

@Composable
private fun ClearSegmentsRow(c: Context) {
    var confirmClear by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("清理分段音频", fontSize = 13.sp)
            Text("只删制作过程的分段 WAV，不碰原书、成品与模型",
                fontSize = 12.sp, color = Sub)
        }
        Text("清理 ›", fontSize = 12.sp, color = Primary,
            modifier = Modifier.clickable { confirmClear = true })
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理分段音频？", fontSize = 14.sp) },
            text = { Text("只删制作过程里生成的分段 WAV，书架原书、成品和模型文件都不动。",
                fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    var n = 0
                    try {
                        val work = File(c.filesDir, "work")
                        work.walkTopDown().filter { it.isFile && it.name.endsWith(".wav") }
                            .forEach { if (it.delete()) n++ }
                    } catch (_: Throwable) { }
                    confirmClear = false
                    Toast.makeText(c, "已清理 $n 个分段音频", Toast.LENGTH_SHORT).show()
                }) { Text("确认清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ModelScreen(c: Context, refresh: Int,
                         importing: String?, importBytes: Long, importError: String?,
                         onPick: () -> Unit, onBack: () -> Unit, onLibrary: () -> Unit,
                         onChanged: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val st by Producer.state.collectAsState()
    val files = remember(refresh) { ModelStore.files(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    val installed = remember(refresh) { ModelStore.installedModes(c) }
    val current = remember(refresh) { ModelStore.currentMode(c) }
    val modeFiles = remember(refresh) { ModelStore.modeFiles(c) }
    val baseFile = remember(refresh) { ModelStore.tokenizer(c) }
    val allReady = missing.isEmpty()

    fun copyUrl(url: String) {
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(c, "地址已复制，用浏览器下载", Toast.LENGTH_SHORT).show()
    }
    fun cnUrl(name: String) =
        "https://hf-mirror.com/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$name"
    fun hfUrl(name: String) =
        "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$name"

    // R136 · 一屏装得下：去掉整页滚动，默认只显示「缺什么 / 装了什么 / 点哪导入」。
    // 文件名、下载地址、删除重试、启用模式这些低频细节收进可展开区，
    // 一次只展开一项 —— 功能一个没删，但常规手机不用往下拉。
    // 展开态是用户主动操作，不算默认态要滚动。
    var expanded by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) { expanded = if (expanded == key) null else key }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (allReady) "已就绪 · ${installed.size} 个模式"
                 else if (baseFile == null) "还缺共用底座" else "还缺模式主文件",
                fontSize = 12.sp, color = if (allReady) Good else Warn)
        }
        Text("App 不联网：复制地址 → 浏览器下载 → 回来点导入。一次可勾选 4 个文件。",
            fontSize = 11.sp, color = Sub)

        if (importing != null) {
            CardBox(Modifier.padding(vertical = 4.dp)) {
                Text("正在复制，别关掉 App · 已复制 %.2f GB".format(
                    importBytes / 1024f / 1024f / 1024f),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Primary)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
            }
        }
        if (importError != null) {
            CardBox(Modifier.padding(vertical = 4.dp)) {
                Text("上一个文件没导入成功 · $importError", fontSize = 12.sp, color = Warn)
                Text("重新下载再导入一次，或先重启 App。", fontSize = 11.sp, color = Sub)
            }
        }

        Spacer(Modifier.height(6.dp))

        // 共用底座：一行摘要 + 点「详情」展开完整信息
        CardBox(Modifier.padding(bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("共用底座", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(if (baseFile != null)
                            "已导入 ✓ · %.2f GB".format(baseFile.length() / 1024f / 1024f / 1024f)
                        else "还缺这一份 · 三个模式都要用",
                        fontSize = 11.sp, color = if (baseFile != null) Good else Warn)
                }
                TextButton(onClick = { toggle("base") }) {
                    Text(if (expanded == "base") "收起" else "详情",
                        fontSize = 12.sp, color = Primary)
                }
            }
            if (expanded == "base") {
                Text(ModelStore.TOKENIZER, fontSize = 11.sp, color = Sub,
                    fontFamily = FontFamily.Monospace)
                Text("12Hz 码 ↔ 24kHz 波形，导入一次就够。", fontSize = 11.sp, color = Sub)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { copyUrl(cnUrl(ModelStore.TOKENIZER)) }) {
                        Text("复制国内地址 ›", fontSize = 12.sp, color = Primary)
                    }
                    TextButton(onClick = { copyUrl(hfUrl(ModelStore.TOKENIZER)) }) {
                        Text("备用 ›", fontSize = 12.sp, color = Sub)
                    }
                    Spacer(Modifier.weight(1f))
                    if (baseFile != null) {
                        TextButton(onClick = { ModelStore.delete(c, baseFile); onChanged() }) {
                            Text("删除重试", fontSize = 12.sp, color = Sub)
                        }
                    }
                }
            }
        }

        Text("模式主文件 · 装一份就能用", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        val modes = ModelStore.Mode.values()
        modes.toList().chunked(2).forEach { rowModes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { mode ->
                    val f = modeFiles[mode]
                    val isCurrent = mode == current
                    val open = expanded == mode.id
                    CardBox(Modifier.weight(1f).padding(bottom = 6.dp)
                        .clickable { toggle(mode.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                color = if (isCurrent) Primary else Color.Unspecified)
                            Text(if (f != null) "✓" else "＋", fontSize = 12.sp,
                                color = if (f != null) Good else Warn)
                        }
                        Text(if (f != null)
                                "%.2f GB%s".format(f.length() / 1024f / 1024f / 1024f,
                                    if (isCurrent) " · 当前" else "")
                            else "待导入", fontSize = 11.sp,
                            color = if (f != null) Good else Warn)
                        if (open) {
                            Text(mode.id, fontSize = 11.sp, color = Sub)
                            Text(mode.file, fontSize = 11.sp, color = Sub,
                                fontFamily = FontFamily.Monospace)
                            Text(mode.hint, fontSize = 11.sp, color = Sub)
                            if (f != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isCurrent) {
                                        TextButton(onClick = {
                                            if (!st.running) {
                                                Prefs.setModelMode(c, mode.id); onChanged()
                                            }
                                        }, enabled = !st.running) {
                                            Text("启用 ›", fontSize = 11.sp, color = Primary)
                                        }
                                    } else Text("当前模式", fontSize = 11.sp, color = Sub)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        ModelStore.delete(c, f); onChanged()
                                    }) { Text("删除重试", fontSize = 11.sp, color = Sub) }
                                }
                                if (!isCurrent && st.running)
                                    Text("正在制作，跑完前不能切", fontSize = 11.sp, color = Warn)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = onPick, enabled = importing == null) {
                                        Text("导入这份 ›", fontSize = 11.sp, color = Primary)
                                    }
                                    TextButton(onClick = { copyUrl(cnUrl(mode.file)) }) {
                                        Text("国内地址", fontSize = 11.sp, color = Primary)
                                    }
                                }
                                TextButton(onClick = { copyUrl(hfUrl(mode.file)) }) {
                                    Text("备用地址 ›", fontSize = 11.sp, color = Sub)
                                }
                            }
                        }
                    }
                }
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(2.dp))

        CardBox(Modifier.padding(bottom = 6.dp)) {
            if (allReady) {
                Text("底座 + 至少 1 个模式都在", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Text(current.label + " 是当前模式。" + (if (current == ModelStore.Mode.VOICE_DESIGN)
                    "可以合成音频了。" else "切回「描述造音色」后即可继续。"),
                    fontSize = 11.sp, color = Sub)
                val canGo = current == ModelStore.Mode.VOICE_DESIGN
                Button(onClick = {
                    if (canGo) onLibrary()
                    else if (!st.running) {
                        Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id); onChanged()
                    }
                }, enabled = canGo || !st.running,
                    modifier = Modifier.fillMaxWidth().height(42.dp)) {
                    Text(if (canGo) "回书架，导入一本书" else "切回「描述造音色」",
                        fontSize = 13.sp)
                }
            } else {
                Text("还差 " + missing.joinToString("、"), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Text("缺件就出不了声，先补齐上面高亮的那一份。", fontSize = 11.sp, color = Sub)
                Button(onClick = onPick, enabled = importing == null,
                    modifier = Modifier.fillMaxWidth().height(42.dp)) {
                    Text("导入模型文件", fontSize = 13.sp)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPick, enabled = importing == null) {
                Text("一次导入多个模型文件 ›", fontSize = 12.sp, color = Primary)
            }
            Spacer(Modifier.weight(1f))
            if (files.isNotEmpty())
                Text("已导入 ${files.size} 个文件", fontSize = 11.sp, color = Sub)
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MixerScreen(c: Context, onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(MixerDraft.active(c)) }
    var draft by remember { mutableStateOf(active) }
    val changes = MixerCommit.diff(active, draft)
    val dirty = changes.isNotEmpty()
    val scope = rememberCoroutineScope()

    val mode = remember(tick) { ModelStore.currentMode(c) }
    val installed = remember(tick) { ModelStore.installedModes(c).map { it.id }.toSet() }
    var missing by remember { mutableStateOf(emptyList<String>()) }
    var rows by remember { mutableStateOf(emptyList<MixerRow>()) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            val m = ModelStore.missing(c)
            val r = Mixer.snapshot(c)
            withContext(Dispatchers.Main) { missing = m; rows = r }
        }
    }

    var showLeave by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    var trialText by remember { mutableStateOf(Prefs.trialText(c)) }
    var savedTrialText by remember { mutableStateOf(Prefs.trialText(c)) }
    var busyTrial by remember { mutableStateOf(false) }
    var trialMsg by remember { mutableStateOf("") }

    var refState by remember(tick) { mutableStateOf(VoiceRef.status(c)) }
    var speakerList by remember(tick) { mutableStateOf(Prefs.speakerNames(c)) }
    var speakerBusy by remember { mutableStateOf(false) }
    val pickRef = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val r = runCatching {
                val b = c.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("打不开这个文件")
                VoiceRef.save(c, b)
            }
            withContext(Dispatchers.Main) {
                refState = VoiceRef.status(c)
                tick++
                Toast.makeText(c, r.exceptionOrNull()?.message ?: "参考音频已导入",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    fun commitAll() {
        val n = changes.size
        MixerCommit.commit(c, draft, n)
        active = MixerDraft.active(c)
        draft = active
        tick++
    }
    fun discardAll() { draft = active }

    fun switchMode(m: ModelStore.Mode) {
        if (Producer.isBusy()) {
            Toast.makeText(c, "正在制作，做完再切模式", Toast.LENGTH_SHORT).show()
            return
        }
        if (m == mode) return
        Prefs.setModelMode(c, m.id)
        tick++
    }

    fun play(path: String) {
        val f = File(path)
        if (!f.isFile) { Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show(); return }
        PlayQueue.setTracks(listOf(Track(f, "调音台试听", id = "trial:${f.absolutePath}")), "trial")
        PlayQueue.playFrom(0, id = "trial")
    }

    var trialJob by remember { mutableStateOf<Job?>(null) }
    var trialToken by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) { onDispose {
        trialJob?.cancel()
        if (busyTrial) { try { NativeEngine.nativeCancel() } catch (_: Throwable) { } }
    } }

    fun stopTrial() {
        trialJob?.cancel()
        trialJob = null
        trialToken++
        try { NativeEngine.nativeCancel() } catch (_: Throwable) { }
        busyTrial = false
        trialMsg = "已停止"
    }

    fun trial(label: String, src: MixerDraft, text: String, out: String) {
        if (Producer.isBusy()) {
            Toast.makeText(c, "正在制作，做完再试听", Toast.LENGTH_SHORT).show()
            return
        }
        if (busyTrial) return
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        trialJob?.cancel()
        val myToken = trialToken + 1
        trialToken = myToken
        busyTrial = true
        trialMsg = "合成中…"
        val job = scope.launch(Dispatchers.IO) {
            val res = runCatching {
                ensureActive()
                if (!NativeEngine.isAvailable()) throw IllegalStateException("本地引擎没装载，请重启 App")
                ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }
                val tk = ModelStore.modeFile(c, mode) ?: throw IllegalStateException("缺这一份模式主文件")
                val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺共用底座")
                val h = NativeEngine.ttsLoadGated(tk.absolutePath, tok.absolutePath, null)
                if (h == 0L) throw IllegalStateException("声音模型正在装载，稍后再试")
                try {
                    ensureActive()
                    when (mode) {
                        ModelStore.Mode.BASE -> {
                            val txt = src.refText.takeIf { it.isNotBlank() }
                            if (!NativeEngine.ttsSetVoiceRef(h, VoiceRef.pcm(c), txt))
                                throw IllegalStateException("参考音频没能交给引擎")
                        }
                        else -> NativeEngine.ttsClearVoiceRef(h)
                    }
                    val v = when (mode) {
                        ModelStore.Mode.VOICE_DESIGN -> {
                            val identity = src.voice.trim().ifBlank { DEFAULT_IDENTITY }
                            SynthInput.Params(
                                VoiceProfile.build(identity, src.perf.styleInstruct(),
                                    "平稳、清晰地叙述，句间自然停顿。"), null)
                        }
                        ModelStore.Mode.CUSTOM_VOICE -> {
                            val tone = listOf(src.cvStyle.trim(), src.perf.styleInstruct().trim())
                                .filter { it.isNotEmpty() }.joinToString("；")
                            SynthInput.Params(tone.ifBlank { null },
                                src.speaker.takeIf { it.isNotBlank() })
                        }
                        ModelStore.Mode.BASE -> SynthInput.Params(null, null)
                    }
                    ensureActive()
                    val f = File(c.filesDir, out)
                    val code = NativeEngine.ttsSynthesize(h, text, v.instruct, v.speaker,
                        "chinese", f.absolutePath, src.knobs)
                    ensureActive()
                    if (code != 0 || !f.exists() || f.length() <= 44)
                        throw IllegalStateException("没生成声音，code=$code")
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL",
                        "来源=$label · mode=${mode.id} · chars=${text.length} · " +
                            "seed=${src.knobs.seed} · bytes=${f.length()}")
                    f.absolutePath
                } finally { NativeEngine.ttsFreeGated(h) }
            }
            withContext(Dispatchers.Main) {
                if (myToken != trialToken || !isActive) return@withContext
                busyTrial = false
                val p = res.getOrNull()
                if (!p.isNullOrBlank()) play(p)
                trialMsg = if (p != null) "已播放"
                           else "试听失败：" + (res.exceptionOrNull()?.message ?: "未知原因")
                res.exceptionOrNull()?.let {
                    EventLog.log(EventLog.Kind.CRASH, "MIXER_TRIAL_FAIL", it.message ?: "")
                }
            }
        }
        trialJob = job
    }

    BackHandler(enabled = dirty) { showLeave = true }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 20.sp, color = Primary,
                modifier = Modifier.clickable { if (dirty) showLeave = true else onBack() }
                    .padding(end = 12.dp))
            Text("调音台", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.weight(1f))
            if (dirty) {
                Button(onClick = { commitAll() },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text("保存", fontSize = 12.sp)
                }
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))

            ModeTabs(mode, installed) { switchMode(it) }
            Spacer(Modifier.height(10.dp))
            if (mode.id !in installed) {
                Text("「${mode.label}」的主文件还没导入，到「模型」页导入后才能出声。",
                    fontSize = 12.sp, color = Warn)
                Spacer(Modifier.height(8.dp))
            }

            when (mode) {
                ModelStore.Mode.VOICE_DESIGN -> VoiceDesignPane(draft) { draft = it }
                ModelStore.Mode.CUSTOM_VOICE -> CustomVoicePane(
                    draft = draft, onDraft = { draft = it },
                    speakerList = speakerList, speakerBusy = speakerBusy,
                    onBusy = { speakerBusy = it },
                    onSpeakers = { names ->
                        Prefs.setSpeakerNames(c, names)
                        speakerList = names
                        tick++
                    }
                )
                ModelStore.Mode.BASE -> BasePane(
                    draft = draft, onDraft = { draft = it },
                    refState = refState,
                    onPick = { pickRef.launch(arrayOf("audio/*", "*/*")) },
                    onClear = {
                        VoiceRef.clear(c); refState = VoiceRef.status(c); tick++
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            if (mode == ModelStore.Mode.VOICE_DESIGN) {
                Text("采样参数 · 9 项", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = palette.text)
                Spacer(Modifier.height(6.dp))
                ParamCatalog.SAMPLING.forEach { spec ->
                    ParamLine(spec = spec, cur = spec.read(draft.knobs)) { s ->
                        spec.withValue(draft.knobs, s)?.let { draft = draft.copy(knobs = it) }
                    }
                }
                TextButton(onClick = { draft = draft.copy(knobs = TtsKnobs()) }) {
                    Text("全部恢复默认", fontSize = 12.sp, color = Sub)
                }
            } else {
                Text("采样参数沿用全局设置，在「描述造音色」里调",
                    fontSize = 12.sp, color = Sub)
            }
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = trialText, onValueChange = { trialText = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("试听文本", fontSize = 12.sp) },
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (trialText.isBlank()) {
                        Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                    } else {
                        Prefs.setTrialText(c, trialText)
                        savedTrialText = trialText
                        Toast.makeText(c, "试听文本已保存", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存试听文本", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                if (busyTrial) {
                    TextButton(onClick = { stopTrial() }) {
                        Text("停止", fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = missing.isEmpty() && !busyTrial,
                    onClick = { trial("已保存", active, savedTrialText, "mixer_trial_saved.wav") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary, contentColor = palette.onPrimary)
                ) { Text("试听 · 已保存", fontSize = 12.sp) }
                Button(
                    enabled = missing.isEmpty() && !busyTrial,
                    onClick = { trial("未保存", draft, trialText, "mixer_trial_draft.wav") },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimarySoft, contentColor = Primary)
                ) { Text("试听 · 当前", fontSize = 12.sp) }
            }
            if (trialMsg.isNotBlank()) {
                Text(trialMsg, fontSize = 12.sp,
                    color = if (trialMsg.startsWith("试听失败")) Warn else Sub)
            }
            if (missing.isNotEmpty()) {
                Text("还缺模型文件，去「模型」页导入。", fontSize = 12.sp, color = Warn)
            }
            Spacer(Modifier.height(14.dp))

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text("改动还没保存", fontSize = 14.sp) },
            text = { Text("离开会丢掉这次改动。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = { showLeave = false; discardAll(); onBack() }) {
                    Text("丢弃并离开", fontSize = 12.sp, color = Warn)
                }
            },
            dismissButton = { TextButton(onClick = { showLeave = false }) { Text("继续编辑") } }
        )
    }
}

@Composable
private fun ModeTabs(
    current: ModelStore.Mode,
    installed: Set<String>,
    onPick: (ModelStore.Mode) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelStore.Mode.values().forEach { m ->
            val on = m == current
            Column(
                Modifier.weight(1f).height(92.dp)
                    .background(if (on) PrimarySoft else Card, RoundedCornerShape(10.dp))
                    .border(1.dp, if (on) Primary else Line, RoundedCornerShape(10.dp))
                    .clickable { onPick(m) }
                    .padding(horizontal = 9.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(m.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = if (on) Primary else palette.text)
                Text(
                    when (m) {
                        ModelStore.Mode.VOICE_DESIGN -> "写一段话描述嗓子"
                        ModelStore.Mode.CUSTOM_VOICE -> "模型自带音色里挑"
                        ModelStore.Mode.BASE -> "一段人声当样本"
                    },
                    fontSize = 12.sp, color = Sub, maxLines = 2
                )
                if (m.id !in installed) Text("未导入主文件", fontSize = 12.sp, color = Warn)
            }
        }
    }
}

@Composable
private fun VoiceDesignPane(draft: MixerDraft, onDraft: (MixerDraft) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("声音描述", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Spacer(Modifier.weight(1f))
        Text("清空", fontSize = 12.sp, color = Sub,
            modifier = Modifier.clickable { onDraft(draft.copy(voice = "")) })
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.voice, onValueChange = { onDraft(draft.copy(voice = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
        placeholder = { Text("例如：温和的中年女声，吐字清晰，语速偏慢", fontSize = 12.sp) },
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomVoicePane(
    draft: MixerDraft,
    onDraft: (MixerDraft) -> Unit,
    speakerList: List<String>,
    speakerBusy: Boolean,
    onBusy: (Boolean) -> Unit,
    onSpeakers: (List<String>) -> Unit
) {
    Text("选一个音色", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    if (speakerList.isEmpty()) {
        val scope = rememberCoroutineScope()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Button(onClick = {
            onBusy(true)
            scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    val tk = ModelStore.modeFile(ctx, ModelStore.Mode.CUSTOM_VOICE)
                        ?: throw IllegalStateException("还没导入「预设音色」那份主文件")
                    val tok = ModelStore.tokenizer(ctx)
                        ?: throw IllegalStateException("还没导入共用底座")
                    val h = NativeEngine.ttsLoadGated(tk.absolutePath, tok.absolutePath, null)
                    if (h == 0L) throw IllegalStateException("模型打不开")
                    try { NativeEngine.ttsSpeakers(h) } finally { NativeEngine.ttsFreeGated(h) }
                }
                withContext(Dispatchers.Main) {
                    onBusy(false)
                    val names = r.getOrNull()?.split(',')?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()
                    if (names.isNotEmpty()) onSpeakers(names)
                    Toast.makeText(ctx, r.exceptionOrNull()?.message
                        ?: if (names.isEmpty()) "这份模型没有读到音色表" else "读到 ${names.size} 个音色",
                        Toast.LENGTH_LONG).show()
                }
            }
        }, enabled = !speakerBusy) {
            Text(if (speakerBusy) "正在读模型…" else "读取模型自带的音色表 ›", fontSize = 12.sp)
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            speakerList.forEach { name ->
                FilterChip(
                    selected = draft.speaker == name,
                    onClick = { onDraft(draft.copy(speaker = name)) },
                    label = { Text(name, fontSize = 12.sp) }
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("自定义", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.cvStyle, onValueChange = { onDraft(draft.copy(cvStyle = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("例如：语速放慢，句尾别上扬（叠加在所选音色上）", fontSize = 12.sp) },
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    )
}

@Composable
private fun BasePane(
    draft: MixerDraft,
    onDraft: (MixerDraft) -> Unit,
    refState: String,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text("参考音频", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(refState, fontSize = 12.sp,
            color = if (VoiceRef.exists(ctx)) Good else Warn,
            modifier = Modifier.weight(1f))
        if (VoiceRef.exists(ctx)) {
            Text("删掉", fontSize = 12.sp, color = Warn,
                modifier = Modifier.clickable { onClear() }.padding(start = 8.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onPick) { Text("导入一小段人声 ›", fontSize = 12.sp) }
    Text("一段 3~30 秒的人声录音（WAV 即可，App 自动转成 24kHz 单声道）", fontSize = 12.sp, color = Sub)
    Spacer(Modifier.height(12.dp))
    Text("这段录音念的是什么（可留空）", fontSize = 12.sp,
        fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.refText, onValueChange = { onDraft(draft.copy(refText = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("填上原文克隆更准；留空就只按声音克隆", fontSize = 12.sp) },
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    )
}

@Composable
private fun ParamLine(spec: ParamSpec, cur: String, onValue: (String) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var buf by remember(cur) { mutableStateOf(cur) }
    val lo = (spec.min ?: 0.0).toFloat()
    val hi = (spec.max ?: if (spec.key == "seed") 2000.0 else 1000.0).toFloat()
    val sv = (cur.toFloatOrNull() ?: lo).coerceIn(lo, hi)
    val intLike = spec.key == "seed" || spec.key == "maxNewTokens" ||
        spec.key == "topK" || spec.key == "subTopK"
    val step = if (spec.key == "maxNewTokens") 64f else 1f

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, fontSize = 12.sp, color = palette.text,
                modifier = Modifier.weight(1f))
            Text(cur, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary,
                modifier = Modifier
                    .background(PrimarySoft, RoundedCornerShape(6.dp))
                    .clickable { buf = cur; edit = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.width(8.dp))
            Text("↺", fontSize = 12.sp, color = Sub,
                modifier = Modifier.clickable { onValue(spec.def) }.padding(4.dp))
        }
        Slider(
            value = sv,
            onValueChange = { v ->
                val q = if (intLike) kotlin.math.round(v / step) * step else v
                val s = if (intLike) q.toLong().toString()
                        else String.format(java.util.Locale.US, "%.2f", q)
                            .trimEnd('0').trimEnd('.')
                onValue(s)
            },
            valueRange = lo..hi,
            modifier = Modifier.padding(start = 20.dp, end = 10.dp)
                .fillMaxWidth().height(18.dp)
        )
    }

    if (edit) {
        val err = spec.validate(buf)
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text(spec.label, fontSize = 14.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = buf, onValueChange = { buf = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 14.sp),
                        isError = err != null
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("范围 ${spec.rangeLabel} · 默认 ${spec.def}", fontSize = 12.sp, color = Sub)
                    if (err != null) Text(err, fontSize = 12.sp, color = Warn)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (spec.validate(buf) == null && spec.withValue(TtsKnobs(), buf) != null) {
                        onValue(buf.trim()); edit = false
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { edit = false }) { Text("取消") } }
        )
    }
}
