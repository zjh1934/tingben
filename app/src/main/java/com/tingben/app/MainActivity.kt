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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.input.ImeAction
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

private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }

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
    var refresh by rememberSaveable { mutableStateOf(0) }
    var modelsFrom by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    var inputReadOpen by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    val importBytesFlow = remember { MutableStateFlow(0L) }
    val importBytes by importBytesFlow.collectAsState()
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var bookImportError by rememberSaveable { mutableStateOf<String?>(null) }

    var navStack by rememberSaveable { mutableStateOf("") }
    val nav = remember { NavHost(navStack, { screen }, { screen = it }, { navStack = it }) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }

    BackHandler(enabled = screen != Screen.LIBRARY || nav.canPop()) { nav.pop() }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    KeepScreenOn()

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
            nav.push(if (taskChapter != null) Screen.PROGRESS else Screen.TASK_DETAIL)
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
        Screen.PROGRESS, Screen.TASKS, Screen.TASK_DETAIL -> NAV_TASKS
        Screen.AUDIO -> NAV_AUDIO
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
                            Screen.LIBRARY, Screen.TASKS, Screen.AUDIO,
                            Screen.PRODUCE, Screen.PROGRESS -> screen
                            else -> drawerReturn
                        }
                        drawerReturn = from
                        if (idx == NAV_MODELS) modelsFrom = from
                        val target = when (idx) {
                            NAV_LIBRARY -> Screen.LIBRARY
                            NAV_TASKS -> Screen.TASKS
                            NAV_AUDIO -> Screen.AUDIO
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
                        if (idx == NAV_AUDIO || idx == NAV_SETTINGS) refresh++
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
            primary = Primary
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
                    onBack = { nav.pop() },
                    onProgress = { taskBookId = bookId; nav.push(Screen.TASKS) },
                    onPlayBook = { miniBook = it },
                    onMixer = { drawerReturn = Screen.CHAPTERS; nav.push(Screen.MIXER) },
                    onStarted = { id, sel ->
                        taskBookId = id; lastSelection = sel
                        nav.push(Screen.TASK_DETAIL)
                    })
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { nav.pop() },
                    onProgress = { taskBookId = bookId; taskChapter = null; nav.push(Screen.PROGRESS) },
                    onDone = { nav.clear(); screen = Screen.AUDIO; refresh++ },
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
                    onOpenTask = { id -> taskBookId = id; nav.push(Screen.TASK_DETAIL) },
                    onProgress = {
                        val t = Producer.resume.value
                        taskBookId = t?.bookId ?: taskBookId
                        taskChapter = t?.let { TaskResume.currentChapter(ctx, it) }
                        nav.push(Screen.PROGRESS)
                    },
                    onAudio = { nav.push(Screen.AUDIO); refresh++ },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onResume = resumeTask)
                Screen.TASK_DETAIL -> TaskDetailScreen(ctx, taskBookId, refresh,
                    onBack = { nav.pop() },
                    onChapter = { ch ->
                        taskChapter = ch
                        taskBookId = taskBookId ?: bookId
                        nav.push(Screen.PROGRESS)
                    },
                    onPickChapter = { ch ->
                        bookId = taskBookId ?: bookId
                        lastSelection = setOf(ch)
                        nav.push(Screen.CHAPTERS)
                    },
                    onAudio = { nav.push(Screen.AUDIO); refresh++ },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onResume = resumeTask)
                Screen.AUDIO -> AudioScreen(ctx, refresh,
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onModels = { modelsFrom = Screen.AUDIO; nav.push(Screen.MODELS) })
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

        MiniPlayBar(ctx, miniBook, onGoBook = { id ->
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

private fun chromeOf(s: Screen): ChromeSpec = when (s) {
    Screen.LIBRARY  -> ChromeSpec("书架", TAB_LIBRARY, showImport = true)
    Screen.PRODUCE  -> ChromeSpec("制作", TAB_LIBRARY)
    Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY)
    Screen.PROGRESS -> ChromeSpec("进度", TAB_TASKS)
    Screen.TASKS    -> ChromeSpec("任务", TAB_TASKS)
    Screen.AUDIO    -> ChromeSpec("我的音频", TAB_AUDIO)
    Screen.SETTINGS -> ChromeSpec("设置")
    Screen.MODELS   -> ChromeSpec("模型")
    Screen.MIXER    -> ChromeSpec("调音台")
    Screen.TASK_DETAIL -> ChromeSpec("章节", TAB_TASKS)
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
                CardBox(Modifier.padding(bottom = 10.dp).clickable { onOpen(b.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Text("${b.chars} 字", fontSize = 12.sp, color = Sub)
                            if (lastRead != null) {
                                Spacer(Modifier.height(3.dp))
                                Text("上次读到 第 ${lastRead + 1} 章", fontSize = 12.sp, color = Primary)
                            }
                        }
                        Text("›", fontSize = 18.sp, color = Sub)
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
    val fallbackSegs = if (chapter != null && segChapters != null)
        allSegs.filter { segChapters[it.index] == chapter } else allSegs
    val chapterRows = treeChapter?.segments?.map { s ->
        SegRow(s.seq - 1, s.seq - 1, 0, null, s.file, s.file.name,
            "", "旁白", "", false)
    }
    val segs = if (chapter != null && chapterRows != null) chapterRows else
        if (chapter != null) fallbackSegs.mapIndexed { i, r -> r.copy(index = i) } else allSegs
    val rest = remember(st.done, seenTask, redoNonce) { Workbench.remainingCount(c, seenTask) }
    val verdict = remember(st.done, seenTask, redoNonce) { Workbench.verdict(c, seenTask) }
    var showCompare by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val pqSeg by PlayQueue.state.collectAsState()
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(st.running) { if (!st.running) stopping = false }

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
                                    .clickable(enabled = playable) {
                                        if (playing) PlayQueue.pause()
                                        else if (onThis) PlayQueue.resume()
                                        else sg.wav?.let { wav ->
                                            PlayQueue.setTracks(listOf(Track(wav, label, id = segId)), "seg")
                                            PlayQueue.playFrom(0, id = "seg")
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${sg.index + 1}", fontSize = 12.sp,
                                    color = if (playing) Primary else base)
                                if (playable)
                                    Text(if (playing) "⏸" else "▶", fontSize = 12.sp, color = Good,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
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
}


private data class TaskDetailData(
    val title: String,
    val exists: Boolean,
    val task: TaskInfo?,
    val chapters: List<Chapter>,
    val finished: Map<Int, AudioItem>,
    val chapterReady: Set<Int>,
    val chapterStarted: Set<Int>,
    val chapterMerged: Map<Int, File>,
    val real: Int,
    val invalidate: String?
)

private fun buildTaskDetail(c: Context, bookId: String?): TaskDetailData {
    val book = bookId?.let { BookStore.list(c).find { it.id == bookId } }
    val task = bookId?.let { TaskResume.byBook(c, it) }
    val chapters = if (book != null) try {
        ChapterSplitter.split(BookStore.read(c, book)).first
    } catch (_: Throwable) { emptyList() } else emptyList()
    val finished = bookId?.let { AudioLibrary.latestByChapter(c, it) } ?: emptyMap()
    val real = task?.let { TaskResume.realProgress(c, it) } ?: 0
    val invalidate = task?.let { TaskResume.invalidationReason(c, it) }
    val plan = task?.let { Workbench.segChapters(c, it) }
    val perChapter = plan?.values?.groupingBy { it }?.eachCount() ?: emptyMap()
    val ready = mutableSetOf<Int>()
    val startedCh = mutableSetOf<Int>()
    val mergedCh = mutableMapOf<Int, File>()
    AudioTree.load(c).firstOrNull { it.bookId == bookId }?.chapters?.forEach { ch ->
        ch.merged?.takeIf { it.isFile }?.let { mergedCh[ch.chapter] = it }
        if (ch.segments.isNotEmpty()) startedCh.add(ch.chapter)
        val planned = perChapter[ch.chapter] ?: 0
        if (ch.merged != null || (planned > 0 && ch.segments.size >= planned)) ready.add(ch.chapter)
    }
    finished.keys.forEach { ready.add(it) }
    return TaskDetailData(book?.title ?: task?.title ?: "这本书不在了",
        book != null, task, chapters, finished, ready, startedCh, mergedCh, real, invalidate)
}

private fun playChapterAudio(c: Context, bookId: String?, ch: Int, merged: File? = null) {
    if (bookId == null) return
    if (merged != null && merged.isFile) {
        val q = "chapterMerged:$bookId:$ch"
        PlayQueue.setTracks(listOf(Track(merged, "第 ${ch + 1} 章 · 整章",
            id = "merged:${merged.absolutePath}")), q)
        PlayQueue.playFrom(0, id = q)
        return
    }
    val a = AudioLibrary.audioFor(c, bookId, ch) ?: return
    val q = "chapter:$bookId"
    PlayQueue.setTracks(listOf(Track(a.file, "${a.title} · 第 ${ch + 1} 章",
        AudioLibrary.chapterStartMs(c, a, ch),
        id = "chapter:${a.file.absolutePath}")), q)
    PlayQueue.playFrom(0, id = q)
}

@Composable
private fun TaskDetailScreen(
    c: Context, bookId: String?, refresh: Int,
    onBack: () -> Unit, onChapter: (Int) -> Unit,
    onPickChapter: (Int) -> Unit,
    onAudio: () -> Unit, onLibrary: () -> Unit,
    onResume: (TaskInfo) -> Unit
) {
    val st by Producer.state.collectAsState()
    val live by Producer.resume.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) { tick++; return@LaunchedEffect }
        while (true) { delay(2000); tick++ }
    }
    var detail by remember { mutableStateOf<TaskDetailData?>(null) }
    LaunchedEffect(bookId, refresh, tick, st.done, st.stage) {
        detail = withContext(Dispatchers.IO) { buildTaskDetail(c, bookId) }
    }
    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val t = d.task
    val runningHere = st.running && live?.bookId == bookId
    val otherRunning = st.running && !runningHere
    val badge = when {
        runningHere -> "进行中"
        t?.phase == "done" -> "已完成"
        t?.phase == "stopped" -> "已停止"
        else -> "已中断"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item(key = "detail_head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 任务", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                Text(badge, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (runningHere) Primary else if (t?.phase == "done") Good else Warn)
            }
            if (!d.exists) {
                Text("这本书已从书架删掉，成品仍留在「我的音频」里。",
                    fontSize = 12.sp, color = Warn)
            }
            if (d.invalidate != null) {
                Text(d.invalidate!!, fontSize = 12.sp, color = Warn)
            }
            if (otherRunning) {
                Text("另一本正在制作，等它跑完或先停它，再续这一本。",
                    fontSize = 12.sp, color = Warn)
            }
            Spacer(Modifier.height(6.dp))
        }
        item(key = "detail_chapter_title") {
            Spacer(Modifier.height(10.dp))
            Text("章节 · 做过的章点开看它的分段", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = Primary)
            Spacer(Modifier.height(6.dp))
        }
        if (d.chapters.isEmpty()) {
            item(key = "detail_no_chapter") {
                CardBox {
                    Text("这本书的章节读不出来", fontSize = 12.sp, color = Sub)
                    TextButton(onClick = onLibrary) { Text("去书架 ›", fontSize = 12.sp) }
                }
            }
        } else {
            items(d.chapters, key = { it.index }) { ch ->
                val done = ch.index in d.chapterReady || d.finished.containsKey(ch.index)
                val inScope = t?.only == null || ch.index in t.only!!
                val livePct = if (runningHere) st.chapterProgress[ch.index] else null
                val started = done || ch.index in d.chapterStarted || livePct != null ||
                    (runningHere && inScope)
                val label = when {
                    done -> "已做好"
                    livePct != null -> "正在制作 ${(livePct * 100).toInt()}%"
                    runningHere && inScope -> "排队中"
                    !inScope -> "本次没选"
                    t?.phase == "stopped" || t?.phase == "interrupted" -> "没做完"
                    else -> "还没做"
                }
                val tone = when {
                    done -> Good
                    livePct != null || (runningHere && inScope) -> Primary
                    started -> Warn
                    else -> Sub
                }
                CardBox(Modifier.padding(bottom = 6.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp)
                        .clickable(enabled = started) { onChapter(ch.index) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${ch.index + 1}. ${ch.title}", fontSize = 14.sp, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(tone, RoundedCornerShape(50)))
                                Spacer(Modifier.width(6.dp))
                                Text(label, fontSize = 12.sp, color = tone)
                            }
                        }
                        if (done) {
                            TextButton(onClick = { playChapterAudio(c, bookId, ch.index, d.chapterMerged[ch.index]) }) {
                                Text("▶ 播放", fontSize = 12.sp)
                            }
                        }
                        if (started) {
                            Text("本章 ›", fontSize = 12.sp, color = Primary)
                        } else {
                            TextButton(onClick = { onPickChapter(ch.index) }) {
                                Text("去选这一章 ›", fontSize = 12.sp, color = Sub)
                            }
                        }
                    }
                }
            }
        }
        item(key = "detail_actions") {
            Spacer(Modifier.height(8.dp))
            if (t == null) {
                TextButton(onClick = onLibrary) { Text("去书架，选一本书 ›", fontSize = 12.sp) }
            } else when {
                runningHere -> {
                    Text("这一本正在跑，点做过的章看它的分段。",
                        fontSize = 12.sp, color = Sub)
                }
                t.phase == "done" -> {
                    TextButton(onClick = onAudio) {
                        Text("去「我的音频」试听 ›", fontSize = 12.sp)
                    }
                }
                !d.exists -> {
                    TextButton(onClick = {
                        Producer.clearProgress(c, t.bookId, t.only)
                        tick++
                    }) { Text("清除这条失效任务", fontSize = 12.sp) }
                }
                else -> {
                    Button(onClick = { if (!otherRunning) onResume(t) },
                        enabled = !otherRunning,
                        modifier = Modifier.fillMaxWidth().height(46.dp)) {
                        Text("继续制作（接着已做好的块跑）", fontSize = 15.sp)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            Producer.clearProgress(c, t.bookId, t.only)
                            val b = BookStore.list(c).find { x -> x.id == t.bookId }
                            if (b != null) onResume(t)
                        }, Modifier.weight(1f)) { Text("从头重做", fontSize = 12.sp) }
                        TextButton(onClick = {
                            Producer.clearProgress(c, t.bookId, t.only)
                            tick++
                        }, Modifier.weight(1f)) {
                            Text("放弃任务", fontSize = 12.sp, color = Warn)
                        }
                    }
                }
            }
        }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
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

private fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private data class AudioMenu(
    val kind: Int,
    val bookId: String,
    val chapter: Int,
    val seg: SegAudio?,
    val label: String,
    val files: List<File>,
    val mergeds: List<File> = emptyList()
)

@Composable
private fun AudioScreen(
    c: Context, refresh: Int,
    onLibrary: () -> Unit, onModels: () -> Unit
) {
    var localTick by remember { mutableStateOf(0) }
    var tree by remember { mutableStateOf(emptyList<BookAudio>()) }
    LaunchedEffect(refresh, localTick) {
        tree = withContext(Dispatchers.IO) { AudioTree.load(c) }
    }
    val pq by PlayQueue.state.collectAsState()
    var openBooks by remember { mutableStateOf(setOf<String>()) }
    var openChapters by remember { mutableStateOf(setOf<String>()) }
    var target by remember { mutableStateOf<AudioMenu?>(null) }
    var confirmDel by remember { mutableStateOf<AudioMenu?>(null) }
    var rename by remember { mutableStateOf<AudioMenu?>(null) }
    var renameText by remember { mutableStateOf("") }
    var moveSeg by remember { mutableStateOf<SegAudio?>(null) }
    var moveText by remember { mutableStateOf("") }
    var pendingSrc by remember { mutableStateOf<File?>(null) }
    var pendingName by remember { mutableStateOf("") }

    val saveWav = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val src = pendingSrc
        if (uri != null && src != null) {
            val ok = AudioOps.copyTo(c, src, uri)
            Toast.makeText(c, if (ok) "已复制" else "复制失败", Toast.LENGTH_SHORT).show()
        }
        if (src != null && src.name.startsWith("audio_copy_")) src.delete()
        pendingSrc = null
    }
    val saveM4a = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val src = pendingSrc
        if (uri != null && src != null) {
            val tmp = File(c.cacheDir, "conv_${System.currentTimeMillis()}.m4a")
            val r = AudioOps.toM4a(c, src, tmp)
            if (r.ok) {
                val ok = AudioOps.copyTo(c, tmp, uri)
                Toast.makeText(c, if (ok) "已转存 M4A" else "写出失败", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(c, r.reason, Toast.LENGTH_LONG).show()
            }
            tmp.delete()
        }
        if (src != null && src.name.startsWith("audio_copy_")) src.delete()
        pendingSrc = null
    }

    fun toast(s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

    fun partsFor(t: AudioMenu): List<File> {
        if (t.kind == 2) return t.files
        if (t.kind == 1) return t.mergeds.ifEmpty { t.files }
        val out = mutableListOf<File>()
        tree.firstOrNull { it.bookId == t.bookId }?.chapters?.forEach { ch ->
            val m = ch.merged?.takeIf { it.isFile }
            if (m != null) out.add(m) else out.addAll(ch.segments.map { it.file })
        }
        return out.ifEmpty { t.files + t.mergeds }
    }

    fun exportParts(t: AudioMenu, m4a: Boolean) {
        val parts = partsFor(t)
        val src = if (parts.size == 1 && parts[0].isFile) parts[0]
            else AudioOps.mergedTemp(c, parts).let { r ->
                if (!r.ok || r.file == null) { toast(r.reason); return }
                r.file!!
            }
        pendingSrc = src
        pendingName = t.label
        val base = t.label.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }
            .take(40).ifBlank { "audio" }
        if (m4a) saveM4a.launch("$base.m4a") else saveWav.launch("$base.wav")
    }

    fun playSeg(s: SegAudio) {
        val tid = "seg:${s.file.absolutePath}"
        if (pq.trackId == tid) {
            PlayQueue.toggle()
        } else {
            PlayQueue.setTracks(listOf(Track(s.file, s.name, id = tid)), "seg")
            PlayQueue.playFrom(0, id = "seg")
        }
    }

    fun playChapter(ch: ChapterAudio) {
        val q = "chapter:${ch.bookId}:${ch.chapter}"
        val tracks = AudioTree.chapterTracks(c, ch)
        if (tracks.isEmpty()) { toast("这一章还没有音频小块"); return }
        if (pq.queueId == q) {
            PlayQueue.toggle()
        } else {
            PlayQueue.setTracks(tracks, q)
            PlayQueue.playFrom(0, id = q)
        }
    }

    fun bookMenu(b: BookAudio) = AudioMenu(0, b.bookId, -1, null, b.title,
        AudioTree.allSegments(b).map { it.file },
        b.chapters.mapNotNull { it.merged?.takeIf { f -> f.isFile } })
    fun chapterMenu(ch: ChapterAudio) = AudioMenu(1, ch.bookId, ch.chapter, null, ch.title,
        ch.segments.map { it.file },
        listOfNotNull(ch.merged?.takeIf { it.isFile }))
    fun segMenu(s: SegAudio) = AudioMenu(2, s.bookId, s.chapter, s, s.name, listOf(s.file))

    val segCount = tree.sumOf { b -> b.chapters.sumOf { ch -> ch.segments.size } }
    val chCount = tree.sumOf { it.chapters.size }
    val mergedCount = tree.sumOf { b -> b.chapters.count { it.merged != null } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item(key = "audio_tree_summary") {
            Text("共 ${tree.size} 本 · $chCount 章 · $segCount 块" +
                if (mergedCount > 0) " · $mergedCount 章已拼成整章" else "",
                fontSize = 12.sp, color = Sub, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (pq.trackId.isNotEmpty()) {
            item(key = "audio_tree_playing") {
                CardBox(Modifier.padding(bottom = 10.dp)) {
                    Text(pq.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("%s · %s / %s".format(
                        if (pq.isPlaying) "播放中" else "已暂停",
                        fmtTime(pq.positionMs), fmtTime(pq.durationMs)),
                        fontSize = 12.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { PlayQueue.seekTo((pq.positionMs - 15000).coerceAtLeast(0)) }) {
                            Text("−15s", fontSize = 12.sp)
                        }
                        Button(onClick = { PlayQueue.toggle() }) {
                            Text(if (pq.isPlaying) "暂停" else "继续", fontSize = 13.sp)
                        }
                        TextButton(onClick = { PlayQueue.seekTo((pq.positionMs + 15000).coerceAtMost(pq.durationMs)) }) {
                            Text("+15s", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { PlayQueue.release() }) {
                            Text("关闭", fontSize = 12.sp, color = Sub)
                        }
                    }
                }
            }
        }

        tree.forEach { b ->
            val bookOpen = b.bookId in openBooks
            item(key = "audio_book_${b.bookId}") {
                CardBox(Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                openBooks = if (bookOpen) openBooks - b.bookId else openBooks + b.bookId
                            },
                            onLongPress = { target = bookMenu(b) })
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                            Text("${b.chapters.size} 章 · ${AudioTree.allSegments(b).size} 块" +
                                (if (b.chapters.any { it.merged != null })
                                    " · ${b.chapters.count { it.merged != null }} 章已拼好" else "") +
                                if (b.onShelf) "" else " · 不在书架上",
                                fontSize = 12.sp, color = Sub)
                        }
                        Text(if (bookOpen) "▾" else "›", fontSize = 18.sp, color = Sub)
                    }
                }
            }
            if (bookOpen) {
                b.chapters.forEach { ch ->
                    val ck = "${b.bookId}#${ch.chapter}"
                    val chOpen = ck in openChapters
                    item(key = "audio_chapter_$ck") {
                        CardBox(Modifier.padding(start = 12.dp, bottom = 6.dp)) {
                            Row(Modifier.fillMaxWidth().pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        openChapters = if (chOpen) openChapters - ck else openChapters + ck
                                    },
                                    onLongPress = { target = chapterMenu(ch) })
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ch.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                    Text(
                                        if (ch.segments.isEmpty() && ch.merged != null) "已拼成整章 · 1 个音频"
                                        else "${ch.segments.size} 块" +
                                            if (ch.merged != null) " · 已拼成整章" else "",
                                        fontSize = 12.sp, color = Sub)
                                }
                                TextButton(onClick = { playChapter(ch) }) {
                                    Text("▶ 整章", fontSize = 12.sp, color = Primary)
                                }
                            }
                        }
                    }
                    if (chOpen && ch.segments.isNotEmpty()) {
                        item(key = "audio_seg_grid_$ck") {
                            CardBox(Modifier.padding(start = 24.dp, bottom = 6.dp)) {
                                ch.segments.chunked(8).forEach { row ->
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        row.forEach { s ->
                                            val tid = "seg:${s.file.absolutePath}"
                                            val on = pq.trackId == tid
                                            val playing = on && pq.isPlaying
                                            Box(
                                                Modifier.size(34.dp)
                                                    .background(
                                                        if (playing) PrimarySoft else
                                                            if (on) Good.copy(alpha = 0.35f) else
                                                                Good.copy(alpha = 0.18f),
                                                        RoundedCornerShape(8.dp))
                                                    .pointerInput(Unit) {
                                                        detectTapGestures(
                                                            onTap = { playSeg(s) },
                                                            onLongPress = { target = segMenu(s) })
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("${s.seq}", fontSize = 12.sp,
                                                    color = if (playing) Primary else Good)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (target != null) {
        val t = target!!
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(t.label, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2) },
            text = {
                Column {
                    TextButton(onClick = {
                        rename = t
                        renameText = t.label
                        target = null
                    }) { Text("重命名", fontSize = 14.sp) }
                    TextButton(onClick = { target = null; exportParts(t, false) }) {
                        Text("复制 / 另存为 WAV", fontSize = 14.sp)
                    }
                    TextButton(onClick = { target = null; exportParts(t, true) }) {
                        Text("转格式（WAV → M4A）", fontSize = 14.sp)
                    }
                    if (t.kind == 1 && t.files.isNotEmpty()) {
                        TextButton(onClick = {
                            target = null
                            val r = AudioOps.mergeChapter(c, t.bookId,
                                tree.firstOrNull { it.bookId == t.bookId }?.title ?: t.label,
                                t.chapter, t.files)
                            if (r.ok) {
                                if (r.reason.isBlank()) {
                                    t.mergeds.forEach { AudioOps.deleteMerged(c, it) }
                                    toast(if (r.kept > 0) "已拼成整章，有 ${r.kept} 个小块没删掉"
                                        else "已拼成整章，原来的小块已清理")
                                } else toast(r.reason)
                                localTick++
                            } else toast(r.reason)
                        }) {
                            Text(if (t.mergeds.isNotEmpty()) "重新拼成整章（会替换旧的）"
                                else "把这一章拼成一个音频", fontSize = 14.sp, color = Primary)
                        }
                    }
                    if (t.kind == 2 && t.seg != null) {
                        TextButton(onClick = {
                            moveSeg = t.seg
                            moveText = (t.chapter + 1).toString()
                            target = null
                        }) { Text("移动到别的章", fontSize = 14.sp) }
                    }
                    TextButton(onClick = { confirmDel = t; target = null }) {
                        Text("删除", fontSize = 14.sp, color = Warn)
                    }
                }
            },
            confirmButton = { },
            dismissButton = { TextButton(onClick = { target = null }) { Text("取消") } }
        )
    }

    if (confirmDel != null) {
        val t = confirmDel!!
        AlertDialog(
            onDismissRequest = { confirmDel = null },
            title = { Text("删除「${t.label}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(if (t.mergeds.isNotEmpty())
                    "会删掉拼好的整章音频和 ${t.files.size} 个小块，不能恢复。" +
                        if (t.files.isEmpty()) "" else "没拼过的章，下次续跑会重新生成这些段。"
                else "共 ${t.files.size} 个音频小块，删掉后不能恢复；" +
                    "没拼过的章，下次续跑会重新生成这些段。", fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    var failed = AudioOps.deleteAll(t.files)
                    t.mergeds.forEach { if (!AudioOps.deleteMerged(c, it)) failed++ }
                    AudioTree.forget(c, t.files.filter { !it.isFile }.map { it.absolutePath })
                    if (t.kind == 1 && t.chapter >= 0) AudioTree.clearDone(c, t.bookId, t.chapter)
                    if (failed > 0) toast("有 $failed 个没删干净，文件会重新出现在列表里")
                    else toast("已删除 ${t.files.size + t.mergeds.size} 个音频")
                    PlayQueue.release()
                    localTick++
                    confirmDel = null
                }) { Text("删除", color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } }
        )
    }

    if (rename != null) {
        val t = rename!!
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("重命名", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    label = { Text("显示名称") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val key = when (t.kind) {
                        0 -> AudioTree.bookKey(t.bookId)
                        1 -> AudioTree.chapterKey(t.bookId, t.chapter)
                        else -> t.files.firstOrNull()?.absolutePath ?: ""
                    }
                    if (key.isNotBlank()) {
                        if (!AudioTree.setName(c, key, renameText)) toast("名字没写上，记录已保留")
                        localTick++
                    }
                    rename = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } }
        )
    }

    if (moveSeg != null) {
        val s = moveSeg!!
        AlertDialog(
            onDismissRequest = { moveSeg = null },
            title = { Text("移动到第几章", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = moveText, onValueChange = { moveText = it },
                    label = { Text("章号，从 1 开始") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = moveText.trim().toIntOrNull()
                    if (n == null || n < 1) toast("填一个大于 0 的章号")
                    else {
                        if (!AudioTree.moveSegment(c, s.key, s.bookId, n - 1))
                            toast("移动没写上，记录已保留")
                        localTick++
                        moveSeg = null
                    }
                }) { Text("移动") }
            },
            dismissButton = { TextButton(onClick = { moveSeg = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun GroupLabel(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary)
        if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = Sub)
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
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (checked) "开" else "关", fontSize = 12.sp,
            color = if (checked) Primary else Sub)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingInfoRow(label: String, badge: String, value: String, note: String = "",
                           onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = Sub)
        }
        Text(value, fontSize = 12.sp, color = Primary,
            modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier)
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
    }
}

@Composable
private fun DiagnosticsBlock(c: Context, _refresh: Int) {
    Spacer(Modifier.height(10.dp))
    Text("当前配置", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Text(Prefs.summary(c), fontSize = 12.sp, color = Sub)
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
private fun SoundProbeBlock(c: Context, refresh: Int) {
    val st by Producer.state.collectAsState()
    val missing = remember(refresh) { ModelStore.missing(c) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf("") }
    var probeFile by remember { mutableStateOf("") }
    val probeScope = rememberCoroutineScope()
    val enabled = !probing && !st.running && missing.isEmpty()

    fun doProbe(text: String, instruct: String, outName: String) {
        probing = true; probeResult = "正在合成…"; probeFile = ""
        probeScope.launch(Dispatchers.IO) {
            val res = runCatching {
                if (!NativeEngine.isAvailable()) throw IllegalStateException(
                    NativeEngine.shortReason().ifBlank { "本地引擎没装载，请重启 App" })
                ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }
                val tk = ModelStore.modeFile(c, ModelStore.currentMode(c))
                    ?: throw IllegalStateException("缺这一份模式主文件")
                val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺共用底座")
                val h = NativeEngine.ttsLoadGated(tk.absolutePath, tok.absolutePath, null)
                if (h == 0L) throw IllegalStateException("声音模型打不开")
                try {
                    SynthInput.prepare(c, h)
                    val v = SynthInput.params(c, instruct, null)
                    val f = File(c.filesDir, outName)
                    val code = NativeEngine.ttsSynthesize(h, text, v.instruct, v.speaker, "chinese",
                        f.absolutePath, Prefs.ttsKnobs(c))
                    if (code != 0 || !f.exists() || f.length() <= 44)
                        throw IllegalStateException("没生成声音，code=$code")
                    f.absolutePath
                } finally { NativeEngine.ttsFreeGated(h) }
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

    Spacer(Modifier.height(8.dp))
    Text("声音自检 · 真合成两句", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Row(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { doProbe("你好，这是一句试音。", "平稳、清晰地叙述", "probe.wav") },
            enabled = enabled, modifier = Modifier.weight(1f)
        ) { Text(if (probing) "…" else "试合成一句", fontSize = 12.sp) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
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
            Text(probeResult, fontSize = 12.sp, color = Sub, modifier = Modifier.weight(1f))
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
    if (st.running) Text("正在制作整本，等跑完再试。", fontSize = 12.sp, color = Sub)
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 12.sp, color = Primary) }
            Spacer(Modifier.weight(1f))
            Text(if (allReady) "已就绪 · ${installed.size} 个模式"
                 else if (baseFile == null) "还缺共用底座" else "还缺模式主文件",
                fontSize = 12.sp, color = if (allReady) Good else Warn)
        }
        Spacer(Modifier.height(6.dp))

        if (importing != null) {
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Text("正在复制，别关掉 App", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Primary)
                Spacer(Modifier.height(5.dp))
                Text("已复制 %.2f GB".format(importBytes / 1024f / 1024f / 1024f),
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
                Spacer(Modifier.height(5.dp))
                Text("文件很大，可能要一两分钟。", fontSize = 12.sp, color = Sub)
            }
        }
        if (importError != null) {
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Text("上一个文件没导入成功", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(importError, fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(5.dp))
                Text("重新下载再导入一次，或先重启 App。", fontSize = 12.sp, color = Sub)
            }
        }

        CardBox(Modifier.padding(bottom = 10.dp)) {
            Text("共用底座 · 三个模式都要用", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("12Hz 码 ↔ 24kHz 波形，导入一次就够。", fontSize = 12.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            Text(ModelStore.TOKENIZER, fontSize = 12.sp, color = Sub,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            if (baseFile != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已导入 ✓ · %.2f GB".format(
                        baseFile.length() / 1024f / 1024f / 1024f),
                        fontSize = 12.sp, color = Good, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ModelStore.delete(c, baseFile); onChanged() }) {
                        Text("删除重试", fontSize = 12.sp, color = Sub)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("还缺这一份", fontSize = 12.sp, color = Warn,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { copyUrl(cnUrl(ModelStore.TOKENIZER)) }) {
                        Text("复制国内地址 ›", fontSize = 12.sp, color = Primary)
                    }
                    TextButton(onClick = { copyUrl(hfUrl(ModelStore.TOKENIZER)) }) {
                        Text("备用 ›", fontSize = 12.sp, color = Sub)
                    }
                }
            }
        }

        Text("模式主文件 · 装一份就能用", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("App 不联网：复制地址 → 浏览器下 → 回来点导入。",
            fontSize = 12.sp, color = Sub)
        Text("一次可勾选 4 个文件：1 个共用底座 + 3 个模式主文件。",
            fontSize = 12.sp, color = Sub)
        Spacer(Modifier.height(8.dp))

        ModelStore.Mode.values().forEach { mode ->
            val f = modeFiles[mode]
            val isCurrent = mode == current
            CardBox(Modifier.padding(bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.label + (if (isCurrent) " · 当前启用" else ""),
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = if (isCurrent) Primary else Color.Unspecified)
                        Text(mode.id, fontSize = 12.sp, color = Sub)
                    }
                    if (isCurrent) Text("✓", fontSize = 14.sp, color = Good)
                }
                Spacer(Modifier.height(4.dp))
                Text(mode.file, fontSize = 12.sp, color = Sub,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(3.dp))
                Text(mode.hint, fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(7.dp))
                if (f != null) {
                    Text("已导入 · %.2f GB".format(f.length() / 1024f / 1024f / 1024f),
                        fontSize = 12.sp, color = Good)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isCurrent) {
                            TextButton(onClick = {
                                if (!st.running) { Prefs.setModelMode(c, mode.id); onChanged() }
                            }, enabled = !st.running) {
                                Text("启用这个模式", fontSize = 12.sp, color = Primary)
                            }
                        } else {
                            Text("这是当前模式", fontSize = 12.sp, color = Sub)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { ModelStore.delete(c, f); onChanged() }) {
                            Text("删除重试", fontSize = 12.sp, color = Sub)
                        }
                    }
                    if (!isCurrent && st.running) {
                        Text("正在制作，这一本跑完之前不能切", fontSize = 12.sp, color = Warn)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("待导入", fontSize = 12.sp, color = Warn,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onPick, enabled = importing == null) {
                            Text("导入这份 ›", fontSize = 12.sp, color = Primary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { copyUrl(cnUrl(mode.file)) }) {
                            Text("复制国内地址 ›", fontSize = 12.sp, color = Primary)
                        }
                        TextButton(onClick = { copyUrl(hfUrl(mode.file)) }) {
                            Text("备用 ›", fontSize = 12.sp, color = Sub)
                        }
                    }
                }
            }
        }

        if (allReady) {
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text("底座 + 至少 1 个模式都在", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(current.label + " 是当前模式。" + (if (current == ModelStore.Mode.VOICE_DESIGN)
                    "可以合成音频了。"
                else "还不能用来合成音频，切回「描述造音色」后即可继续。"),
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                if (current == ModelStore.Mode.VOICE_DESIGN) {
                    Button(onClick = onLibrary, Modifier.fillMaxWidth().height(48.dp)) {
                        Text("回书架，导入一本书", fontSize = 15.sp)
                    }
                } else {
                    Button(onClick = {
                        if (!st.running) {
                            Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)
                            onChanged()
                        }
                    }, enabled = !st.running, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("切回「描述造音色」", fontSize = 15.sp)
                    }
                }
            }
        } else {
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text("还差 " + missing.joinToString("、"), fontWeight = FontWeight.Bold,
                    fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("缺件就出不了声，先补齐上面高亮的那一份。", fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick, enabled = importing == null,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("导入模型文件", fontSize = 15.sp)
                }
            }
        }

        TextButton(onClick = onPick, enabled = importing == null) {
            Text("一次导入多个模型文件 ›", fontSize = 12.sp, color = Primary)
        }
        if (files.isNotEmpty()) {
            Text("已导入 ${files.size} 个文件", fontSize = 12.sp, color = Sub)
        }
        Spacer(Modifier.height(24.dp))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = missing.isEmpty() && !busyTrial,
                    onClick = { trial("已保存", active, savedTrialText, "mixer_trial_saved.wav") }
                ) { Text("试听 · 已保存", fontSize = 12.sp) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = missing.isEmpty() && !busyTrial,
                    onClick = { trial("未保存", draft, trialText, "mixer_trial_draft.wav") }
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
