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

// R132：任务详情页 / 我的音频页的独立入口与目的地都撤了，能力已归进目录页三列。
private enum class Screen { LIBRARY, PRODUCE, TASKS, SETTINGS, MODELS, CHAPTERS }

// R139 · 制作准备页的一次性加载结果（全部在 IO 线程算好，组合期不再碰磁盘）
private data class ProduceLoad(
    val book: com.tingben.app.core.Book,
    val chapters: List<Chapter>,
    val chunks: List<Chunk>,
    val savedConfig: ProduceConfig?
)

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
    var refresh by rememberSaveable { mutableStateOf(0) }
    // R180 · 新装用户打开软件第一眼就该知道「还差模型」，而不是等点合成才撞墙。
    // 只给状态 + 一个能按的入口，不写教程（用户硬口径）；模型齐了整行自动消失。
    val modelMissing = remember(refresh) { ModelStore.missing(ctx).size }
    var modelsFrom by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
    var inputReadOpen by rememberSaveable { mutableStateOf(false) }
    // 底部常驻条点开 / 通知点进来，展开同一个全屏播放器浮层
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // R148 · 目录页「调音」不再 push 到独立页：跳页会让 ChapterHub 重建，
    // 用户勾选的章节全丢，还多出一个返回键。改成就地弹层，勾选原样保留。
    var chapterMixerOpen by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    val importBytesFlow = remember { MutableStateFlow(0L) }
    val importBytes by importBytesFlow.collectAsState()
    var importError by rememberSaveable { mutableStateOf<String?>(null) }
    var bookImportError by rememberSaveable { mutableStateOf<String?>(null) }
    // R176 · 大文件导入在 IO 线程跑几十秒，原来可以连点 ＋ 起多个解码协程，
    // 内存峰值翻倍甚至 OOM。这里做并发闸门，并让首页显示「正在导入」。
    var bookImporting by rememberSaveable { mutableStateOf(false) }
    // R178 · 通知点「任务」不再跳独立任务页：落到那本书的目录页并自动展开
    // 就地任务看板（R171 已把看板做进目录页）。能力搬干净后再物理删页。
    var hubOpenTasks by rememberSaveable { mutableStateOf(false) }
    // R159 · 续跑确认。用户实证「关闭以后重新打开应用，它不一定弹」：
    // 原来 LaunchedEffect(Unit) 只在首次组合跑一次，加上 resumeSkipped 用
    // rememberSaveable 跨实例残留 —— 按返回退出再从桌面进（进程没被杀）就不弹，
    // 转屏还会让弹窗凭空消失；守卫又把手动停/失败/队列暂停全吞成 null。
    // 现在：每次回到前台都重判，Activity 重建 = 全新状态 = 重新弹；
    // resumeDecided 只表示「这次前台会话里点过弹窗」，不跨实例记忆。
    var resumeAsk by remember { mutableStateOf<Producer.PendingTask?>(null) }
    var resumeDecided by remember { mutableStateOf(false) }
    // R169 · 每次回前台都会重判，原来每判一次就写一条 SKIP reason=decided，
    // 第四份日志里同一会话刷了 6 条。弹窗本身没问题（只 SHOW 了一次），
    // 收敛成「同一前台会话只记一次」，信息不丢、不再制造噪声。
    var resumeSkipLogged by remember { mutableStateOf(false) }

    var navStack by rememberSaveable { mutableStateOf("") }
    val nav = remember { NavHost(navStack, { screen }, { screen = it }, { navStack = it }) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // R163 · 目录页只有一列了：任务 / 音频 / 制作完成等入口全部落到同一页，
    // 不再带「目标列」。必须放在 nav 声明之后（局部函数不能引用未声明变量）。
    fun openBook(id: String?) {
        EventLog.ui("OPEN_BOOK", "", mapOf("book" to (id ?: "-")))
        if (id != null) bookId = id
        nav.push(Screen.CHAPTERS)
    }

    // 全局唯一回退出口：有栈就回来源页，栈被清掉就按统一返回表兜底。
    fun goBack() {
        EventLog.ui("BACK", "from=$screen", mapOf("hadStack" to nav.canPop().toString()))
        if (nav.canPop()) nav.pop()
        else {
            screen = backTargetOf(screen, modelsFrom)
            nav.clear()
        }
    }
    val backAction: () -> Unit = { goBack() }
    BackHandler(enabled = canGoBack(screen) || nav.canPop()) { goBack() }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    // R142 · 「输入朗读」「全屏播放器」是覆盖式浮层，不进 Screen 枚举、不进 nav 栈。
    // 原来系统返回键会被上面的全局 goBack() 吃掉：底层页面已经切走，浮层还盖在
    // 屏幕上，或者干脆关不掉 —— 就是用户说的「有时不能返回 / 不知道回到哪」。
    // 浮层自己消费返回，且注册在全局之后（后注册优先），两层不打架。
    BackHandler(enabled = playerOpen) { playerOpen = false; EventLog.ui("OVERLAY_PLAYER", "close:back") }
    BackHandler(enabled = inputReadOpen) { inputReadOpen = false; EventLog.ui("OVERLAY_INPUT_READ", "close:back") }

    KeepScreenOn()

    // R139 · 自动续跑要起前台服务，Android 12+ 会拦「后台启动」。
    // 组合完成 ≠ 窗口可见，原来 LaunchedEffect(Unit) 一进组合就跑，时序上仍在
    // ON_START 之前。这里显式等 Activity 真正 ON_RESUME 再调 Producer.autoResume。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var windowVisible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> windowVisible = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> windowVisible = false
                else -> { }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        if (lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) windowVisible = true
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // R159 · 每次回到前台都重新判断（不是 LaunchedEffect(Unit) 一次定终身）。
    // 只要存在未完成任务就给出口：能自动跑的按设置直接跑或弹窗问，
    // 不能自动跑的（手动停 / 失败 / 模型没齐 / 队列暂停）也弹窗，
    // 只是主按钮变成「查看任务」「继续队列」，绝不点了没反应。
    LaunchedEffect(windowVisible) {
        if (!windowVisible) return@LaunchedEffect
        // 一次 IO 同时读待处理任务与开关，避免在主线程碰 SharedPreferences。
        val (p, auto) = withContext(Dispatchers.IO) {
            Producer.pending(ctx) to Prefs.autoResumeOnStart(ctx)
        }
        if (p == null) {
            EventLog.ui("RESUME_ASK_SKIP", "reason=no_pending")
            return@LaunchedEffect
        }
        if (resumeDecided) {
            if (!resumeSkipLogged) {
                resumeSkipLogged = true
                EventLog.ui("RESUME_ASK_SKIP", "reason=decided")
            }
            return@LaunchedEffect
        }
        EventLog.ui("RESUME_CHECK",
            "book=${p.task.title} action=${p.action} auto=$auto phase=${p.task.phase}")
        if (auto && p.action == Producer.ResumeAction.AUTO) {
            resumeDecided = true
            EventLog.ui("RESUME_AUTO", "book=${p.task.title}")
            val hint = Producer.autoResume(ctx)
            if (hint != null) Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
        } else {
            resumeAsk = p
            EventLog.ui("RESUME_ASK_SHOW",
                "book=${p.task.title} phase=${p.task.phase} action=${p.action}")
        }
    }

    // R140 · 把原本静默的失败（音频打不开 / 索引写失败 / 通知栏没显示 …）
    // 汇成一条事件流，在这里统一 Toast，不在几十个 catch 里各弹各的。
    LaunchedEffect(Unit) {
        UiErrors.events.collect { msg ->
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun importBook(uri: Uri?, tag: String = "PICKER") {
        if (uri == null) {
            // R169 · OpenDocument 返回 null 就是用户在文件选择器按了返回。第四份日志里
            // 两条「没拿到文件」记录都弹了错误提示，把正常取消当成
            // 软件坏了。取消只留一条 TRACE 给排查用，不弹错误、不占报错清单。
            EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_CANCEL", "$tag 用户取消选择",
                level = EventLog.Level.TRACE)
            return
        }
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_BEGIN", "$tag ${uri.toString().take(90)}")
        bookImporting = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching { Importer.read(ctx, uri) }
            val r = result.getOrNull()
            // R139 · 写整本进书架也是磁盘 IO，原来放在 Main 分支里同步跑
            val addRes = if (r == null) null else runCatching {
                // R180 · BookStore.add 本来就返回 Book，以前只拿来比 size，
                // 书悄悄进了抽屉、屏幕一动不动，用户以为没点上。
                BookStore.add(ctx, r.title, r.text)
            }
            withContext(Dispatchers.Main) {
                bookImporting = false
                if (r == null) {
                    val why = result.exceptionOrNull()?.message ?: "换个文件试试"
                    EventLog.log(EventLog.Kind.CRASH, "BOOK_IMPORT_FAIL", "$tag $why")
                    bookImportError = why
                    Toast.makeText(ctx, "这本书没读进来：$why", Toast.LENGTH_LONG).show()
                } else {
                    bookImportError = null
                    val err = addRes?.exceptionOrNull()
                    if (err != null) {
                        Toast.makeText(ctx, err.message ?: "这本书加不进来",
                            Toast.LENGTH_LONG).show()
                    } else {
                        refresh++
                        val added = addRes?.getOrNull()
                        if (added != null) {
                            bookId = added.id
                            nav.push(Screen.CHAPTERS)
                            EventLog.ui("BOOK_IMPORT_OPEN", "id=${added.id}")
                        }
                        Toast.makeText(ctx,
                            if (added != null) "已加入书架：${r.title}（${r.kind}）"
                            else "这本书没加进来，去书架看看",
                            Toast.LENGTH_SHORT).show()
                    }
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
        if (bookImporting) {
            Toast.makeText(ctx, "上一本还在导入，稍等一下", Toast.LENGTH_SHORT).show()
            return
        }
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
                // R178 · 进 IO 找「有未完成任务的书」，落目录页 + 自动展开就地看板；
                // 一本都没任务时给一句中文反馈，不再把用户丢到独立任务页。
                val b = withContext(Dispatchers.IO) {
                    val t = TaskResume.active(ctx) ?: TaskResume.queue(ctx).firstOrNull()
                    t?.bookId?.let { bid -> BookStore.list(ctx).find { it.id == bid } }
                }
                if (b != null) {
                    bookId = b.id
                    nav.clear()
                    screen = Screen.CHAPTERS
                    hubOpenTasks = true
                    EventLog.ui("NOTIF_TASKS_OPEN", "book=${b.id}")
                } else {
                    Toast.makeText(ctx, "暂时没有没做完的任务", Toast.LENGTH_SHORT).show()
                }
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
                    val err = withContext(Dispatchers.IO) {
                        runCatching {
                            BookStore.add(ctx, autoBookTitle(text, "分享的文字"), text, "input")
                        }
                            .exceptionOrNull()
                    }
                    if (err != null) Toast.makeText(ctx, err.message ?: "文字加不进来",
                        Toast.LENGTH_LONG).show()
                    else {
                        refresh++
                        screen = Screen.LIBRARY
                        Toast.makeText(ctx, "文字已就绪（相同内容不会重复添加）",
                            Toast.LENGTH_SHORT).show()
                    }
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
                        // R148 · 用户实证：刚导完模型就被拽回书架，手里的页面和按钮全变了。
                        // 留在模型页，把「齐了」当成本页状态，去书架交给用户自己点。
                        Toast.makeText(ctx, "模型都齐了，回书架导入一本书就能开始", Toast.LENGTH_LONG).show()
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
            bookId = b.id
            lastSelection = t.only
            Producer.start(ctx, b, t.only, true, t.think, t.config)
            // R163 · 续跑一律回这本书的目录页（现在只有一列）。
            openBook(b.id)
        } else {
            Toast.makeText(ctx, "这本书已经从书架删掉了，任务没法续跑", Toast.LENGTH_LONG).show()
        }
    }
    val chrome = chromeOf(screen)
    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            AppDrawerSheet(
                title = "我的书",
                onImport = {
                    openBookPicker()
                    scope.launch { drawerState.close() }
                },
                surface = Card,
                primary = Primary,
                sub = Sub
            ) {
                // R162 · 书架页没有消失，只是搬进抽屉：进度色条、置顶、删除一个不少。
                LibraryScreen(ctx, refresh,
                    compact = true,
                    onOpen = { id ->
                        bookId = id
                        nav.push(Screen.CHAPTERS)
                        scope.launch { drawerState.close() }
                    },
                    onModels = { modelsFrom = Screen.LIBRARY; nav.push(Screen.MODELS) },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ },
                    importError = bookImportError,
                    onDismissImportError = { bookImportError = null })
            }
        }
    ) {
        Box(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
        AppTopBar(
            spec = chrome,
            onMenu = {
                EventLog.ui("DRAWER_NAV", "open")
                scope.launch { drawerState.open() }
            },
            onImport = { openBookPicker() },
            onSettings = {
                EventLog.ui("TOP_SETTINGS", "open")
                modelsFrom = Screen.LIBRARY
                nav.push(Screen.SETTINGS)
                refresh++
            },
            surface = Card,
            primary = Primary,
            onBack = if (chrome.canBack) backAction else null
        )
        Box(Modifier.weight(1f)) {
            when (screen) {
                // R162 · 主页就是输入框：粘贴文字直接建书，书列表搬到左边抽屉。
                Screen.LIBRARY -> InputReadScreen(ctx,
                    onBack = { },
                    onAdded = { id ->
                        refresh++
                        bookId = id
                        nav.push(Screen.CHAPTERS)
                    },
                    asHome = true,
                    importingBook = bookImporting,
                    modelMissing = modelMissing,
                    onModels = { modelsFrom = Screen.LIBRARY; nav.push(Screen.MODELS) })
                Screen.CHAPTERS -> {
                    ChapterHubScreen(ctx, bookId, refresh,
                        onBack = { nav.pop() },
                        onResumeTask = {
                            // R137：bookId 是可空 String?，byBook 要非空 String；
                            // 沙盒无 SDK 时静态脚本抓不到，CI 真编译 run 127 报出，
                            // 这里显式判空，不改成 !!（没开书时本就不该恢复任务）。
                            bookId?.let { id -> TaskResume.byBook(ctx, id)?.let { resumeTask(it) } }
                        },
                        onPlayBook = { miniBook = it },
                        onMixer = { chapterMixerOpen = true; EventLog.ui("OVERLAY_MIXER", "open:hub") },
                        onStarted = { id, sel ->
                            lastSelection = sel
                            openBook(id)
                        },
                        openTasksOnStart = hubOpenTasks,
                        onOpenModels = {
                            modelsFrom = Screen.CHAPTERS
                            nav.push(Screen.MODELS)
                        })
                    // 只借这一帧把「自动展开」交给目录页，之后复位，
                    // 下次正常从书列表打开书不会无故弹看板。
                    SideEffect { if (hubOpenTasks) hubOpenTasks = false }
                }
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { nav.pop() },
                    onProgress = { openBook(bookId) },
                    onDone = { nav.clear(); openBook(bookId); refresh++ },
                    onStarted = { lastSelection = it })
                Screen.TASKS -> TaskCenterScreen(ctx, refresh,
                    onOpenTask = { id -> openBook(id) },
                    onProgress = { id -> openBook(id) },
                    onAudio = { id -> openBook(id); refresh++ },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onResume = resumeTask)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; nav.push(Screen.MODELS) },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onMixer = { chapterMixerOpen = true; EventLog.ui("OVERLAY_MIXER", "open:settings") })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { nav.pop() },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onChanged = { refresh++ })
            }
        }

        MiniPlayBar(ctx, miniBook,
            onExpand = { playerOpen = true; EventLog.ui("OVERLAY_PLAYER", "open") },
            onGoBook = { id ->
                bookId = id
                nav.push(Screen.CHAPTERS)
            })
        }
        // 播放器是覆盖全屏的浮层，不进 Screen 枚举 / 导航栈：
        // 它不是「另一页」，是同一份 PlayQueue 状态的展开视图。
        if (playerOpen) {
            PlayerSheet(ctx, miniBook ?: bookId,
                onClose = { playerOpen = false; EventLog.ui("OVERLAY_PLAYER", "close") },
                onGoBook = { id ->
                    bookId = id
                    nav.push(Screen.CHAPTERS)
                })
        }
        if (chapterMixerOpen) {
            MixerDialog(ctx, onClose = {
                chapterMixerOpen = false; EventLog.ui("OVERLAY_MIXER", "close:hub")
            })
        }
        resumeAsk?.let { p ->
            ResumeAskDialog(
                title = p.task.title,
                done = p.task.done,
                total = p.task.total,
                label = p.label,
                action = p.action,
                onPrimary = {
                    EventLog.ui("RESUME_ASK_PRIMARY",
                        "book=${p.task.title} action=${p.action}")
                    resumeAsk = null
                    resumeDecided = true
                    when (p.action) {
                        Producer.ResumeAction.AUTO -> {
                            val hint = Producer.autoResume(ctx)
                            if (hint != null)
                                Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
                        }
                        Producer.ResumeAction.QUEUE -> {
                            val hint = Producer.resumeQueue(ctx)
                            if (hint != null)
                                Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
                        }
                        Producer.ResumeAction.VIEW -> {
                            screen = Screen.TASKS
                            nav.clear()
                        }
                    }
                },
                onView = {
                    EventLog.ui("RESUME_ASK_VIEW", "book=${p.task.title}")
                    resumeAsk = null
                    resumeDecided = true
                    screen = Screen.TASKS
                    nav.clear()
                },
                onLater = {
                    EventLog.ui("RESUME_ASK_LATER", "book=${p.task.title}")
                    resumeAsk = null
                    resumeDecided = true
                }
            )
        }
        if (inputReadOpen) {
            InputReadScreen(ctx,
                onBack = { inputReadOpen = false; EventLog.ui("OVERLAY_INPUT_READ", "close") },
                onAdded = { _ ->
                    inputReadOpen = false
                    EventLog.ui("OVERLAY_INPUT_READ", "added")
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
// R162 · 主页只有输入框这一屏。设置从右上角齿轮进、任务从通知/书里进，
// 都是二级页，一律能返回主页。
private val ROOT_SCREENS = setOf(Screen.LIBRARY)

private fun canGoBack(s: Screen): Boolean = s !in ROOT_SCREENS

private fun backTargetOf(s: Screen, modelsFrom: Screen): Screen =
    when (s) {
        // 任务页不再有常驻入口（从通知/书里进），回退统一到主页
        Screen.TASKS -> Screen.LIBRARY
        Screen.MODELS -> modelsFrom
        else -> Screen.LIBRARY
    }

private fun chromeOf(s: Screen): ChromeSpec = when (s) {
    // R164 · 品牌名从顶栏撤掉：已经在用这个软件，左上角不必再写一遍「听本」。
    // 齿轮 / ＋ 只留首页，二级页一律不给，右上角腾出整块空间。
    Screen.LIBRARY  -> ChromeSpec("", TAB_LIBRARY,
        showImport = true, showSettings = true)
    Screen.PRODUCE  -> ChromeSpec("制作", TAB_LIBRARY, canBack = true)
    Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY, canBack = true)
    Screen.TASKS    -> ChromeSpec("任务", TAB_TASKS, canBack = true)
    Screen.SETTINGS -> ChromeSpec("设置", canBack = true)
    Screen.MODELS   -> ChromeSpec("模型", canBack = true)
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
                CardBox(Modifier.padding(bottom = 12.dp).clickable { onOpen(b.id) }) {
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
                            if (lastRead != null) {
                                Spacer(Modifier.height(6.dp))
                                Text("上次读到 第 ${lastRead + 1} 章", fontSize = 11.sp,
                                    color = Primary,
                                    modifier = Modifier
                                        .background(PrimarySoft, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        // 右边只留两个有用的动作：置顶、删除。
                        // 不再放「›」——用户实证「右箭头没有任何价值」。
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = {
                                val next = !isPinned
                                libScope.launch(Dispatchers.IO) {
                                    BookStore.setPinned(c, b.id, next)
                                    EventLog.ui("BOOK_PIN_TAP",
                                        "id=${b.id} on=$next")
                                    withContext(Dispatchers.Main) { onChanged() }
                                }
                            }, modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(if (isPinned) "取消置顶" else "置顶",
                                    fontSize = 12.sp,
                                    color = if (isPinned) Primary else Sub)
                            }
                            TextButton(onClick = { confirmDelete = b.id },
                                modifier = Modifier.height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("删除", fontSize = 12.sp, color = Warn)
                            }
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
private fun autoBookTitle(text: String, fallback: String): String {
    val line = text.lineSequence().map { it.trim() }
        .firstOrNull { it.isNotEmpty() } ?: return fallback
    val clean = line.replace(Regex("[\r\n\t]"), " ").trim()
    return if (clean.length <= 18) clean else clean.take(18) + "…"
}

@Composable
private fun InputReadScreen(c: Context, onBack: () -> Unit, onAdded: (String) -> Unit,
                            asHome: Boolean = false,
                            importingBook: Boolean = false,
                            modelMissing: Int = 0,
                            onModels: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val inputScope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    // R176 · 建书是异步落盘，连点会重复触发；成功后清空输入框，
    // 免得用户看着还在的全文以为没点上又按一次。
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        // 这是「正在发生的事」，不是常驻说明文字：只在导入中显示
        if (importingBook) {
            Text("正在后台导入一本书…", fontSize = 11.sp, color = Sub,
                modifier = Modifier.padding(bottom = 6.dp))
        }
        // R180 · 没有模型就合成不了。模型页藏在设置里，新装用户根本不知道。
        // 这里只给一行状态 + 一个入口，模型齐了整行消失；不写教程、不常驻。
        if (modelMissing > 0) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Text("还差 $modelMissing 份模型，还不能合成", fontSize = 12.sp,
                    color = Warn, modifier = Modifier.weight(1f))
                TextButton(onClick = onModels) {
                    Text("去导入 ›", fontSize = 12.sp, color = Primary)
                }
            }
        }
        if (!asHome) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center) {
                    IconBack(Primary)
                }
                Text("输入朗读", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = palette.text)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("关闭", fontSize = 12.sp, color = Sub) }
            }
            Spacer(Modifier.height(12.dp))
        }
        // R171 · 用户实证「文本框上面有废话文字说明」。标题、说明书、输入框 label
        // 三处讲同一件事，全撤：空框里一句 placeholder，输入后自动消失。
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("粘贴或输入要念的正文", fontSize = 12.sp) },
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
            Button(enabled = !adding, onClick = {
                if (text.isBlank()) {
                    Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                } else if (!adding) {
                    adding = true
                    val snapshot = text
                    inputScope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                BookStore.add(c, autoBookTitle(snapshot, "粘贴的文字"),
                                    snapshot, "input")
                            }
                        }
                        val err = res.exceptionOrNull()
                        if (err != null) {
                            Toast.makeText(c, err.message ?: "书加不进来",
                                Toast.LENGTH_LONG).show()
                        } else {
                            text = ""   // 失败时正文原样留着，不丢用户输入
                            val added = res.getOrNull()
                            if (added != null) {
                                // R180 · 建完就进这本书，不再让用户回抽屉里找
                                onAdded(added.id)
                                Toast.makeText(c, "书加好了", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(c, "这段已经在书架里了",
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                        adding = false
                    }
                }
            }) { Text(if (adding) "正在加…" else "建书", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun ProduceScreen(c: Context, id: String?, refresh: Int, onBack: () -> Unit,
                          onProgress: () -> Unit, onDone: () -> Unit,
                          onStarted: (Set<Int>?) -> Unit) {
    val st by Producer.state.collectAsState()
    var selected by remember(id, refresh) { mutableStateOf<Set<Int>>(emptySet()) }
    var cacheTick by remember { mutableStateOf(0) }
    var cfg by remember(id, refresh) { mutableStateOf(Prefs.defaultPerf(c)) }
    var loadDone by remember(id, refresh) { mutableStateOf(false) }
    var load by remember(id, refresh) { mutableStateOf<ProduceLoad?>(null) }
    // R142 · 调音是「点合成之前」的准备工作，不该是另一张一级页。
    var mixerOpen by remember { mutableStateOf(false) }
    var loadError by remember(id, refresh) { mutableStateOf<String?>(null) }
    var hasCached by remember(id, refresh) { mutableStateOf(false) }
    // R185 · 用户常调音色，旧实现一保存调优就把成果全删。现在分套留着，
    // 制作页说清「这套做了几段 / 还留着几套 / 切回原设置就能接着用」。
    var cacheInfo by remember(id, refresh) {
        mutableStateOf(VoiceCache.Summary(0, 0, 0, 0L))
    }

    // R139 · 组合期不再同步读整本 + 切块（原来 64MB 文本会卡主线程甚至 ANR）。
    // 与目录页同一套口径：IO 线程读落盘章节表，加载中给进度圈，出错给中文原因。
    LaunchedEffect(id, refresh) {
        loadDone = false
        val r: Pair<ProduceLoad?, String?> = withContext(Dispatchers.IO) {
            try {
                val b = id?.let { BookStore.list(c).find { it.id == id } }
                if (b == null) null to null else {
                    val (chs, chunks) = ChapterPlan.resolve(c, b)
                    val saved = TaskResume.load(c)
                    ProduceLoad(b, chs, chunks,
                        saved?.takeIf { it.bookId == id }?.config) to null
                }
            } catch (t: Throwable) {
                null to (t.message ?: "读这本书时出错")
            }
        }
        load = r.first
        loadError = r.second
        r.first?.savedConfig?.let { cfg = it }
        loadDone = true
    }

    val snapshot = load
    val chapters = snapshot?.chapters ?: emptyList()
    val allChunks = snapshot?.chunks ?: emptyList()
    val estimate = remember(selected, snapshot) {
        if (selected.isEmpty() || selected.size == chapters.size) allChunks.size
        else allChunks.count { it.chapter in selected }
    }

    // 断点检查也读盘，同样放 IO；selected / cacheTick 变化时自动刷新
    LaunchedEffect(id, refresh, selected, cacheTick, loadDone, snapshot, cfg) {
        if (!loadDone) return@LaunchedEffect
        val b = snapshot?.book ?: return@LaunchedEffect
        val only = if (selected.isEmpty() || selected.size == chapters.size) null else selected
        val (cached, info) = withContext(Dispatchers.IO) {
            Producer.hasProgress(c, b.id, only) to
                Producer.voiceCacheSummary(c, b.id, only, cfg)
        }
        hasCached = cached
        cacheInfo = info
    }

    if (!loadDone) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (snapshot == null) {
        Column(Modifier.padding(16.dp)) {
            Text(loadError ?: "这本书不在了")
            TextButton(onClick = onBack) { Text("返回书架") }
        }
        return
    }
    val book = snapshot.book
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 书架") }
        Text(book.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("${chapters.size} 章 · ${book.chars} 字", fontSize = 13.sp, color = Sub)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("合成前调音", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { mixerOpen = true; EventLog.ui("OVERLAY_MIXER", "open") }) {
                Text("调音 ›", fontSize = 12.sp, color = Primary)
            }
        }
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
            // R183 · 真机实测每段 50~220 秒。只给一句量级，不写教程、不常驻解释。
            Text("每段约 1–3 分钟，块越多等得越久", fontSize = 11.sp, color = Sub)
            if (cacheInfo.current > 0) {
                Spacer(Modifier.height(4.dp))
                Text("这套音色已做好 ${cacheInfo.current} 段，接着做不用重来",
                    fontSize = 12.sp, color = Good)
            }
            if (cacheInfo.others > 0) {
                Spacer(Modifier.height(4.dp))
                Text("另有 ${cacheInfo.others} 套音色留着（最多 ${cacheInfo.bestOther} 段），" +
                    "切回原来的设置就能接着用", fontSize = 12.sp, color = Sub)
            }
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
        // R142 · 合成前调音弹层：不进导航栈，关闭/返回都回到本页
        if (mixerOpen) MixerDialog(c, onClose = {
            mixerOpen = false; EventLog.ui("OVERLAY_MIXER", "close")
        })
    }
}

@Composable
private fun TaskCenterScreen(c: Context, refresh: Int, onOpenTask: (String) -> Unit,
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
@Composable
private fun OverlayProgressRow(c: Context) {
    var on by remember { mutableStateOf(Prefs.overlayProgress(c)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("悬浮进度", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(if (on) "制作时显示 · 停止即收起" else "关闭",
                fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = {
            on = it
            Prefs.setOverlayProgress(c, it)
            if (!it) OverlayProgress.hide(c)
            EventLog.ui("OVERLAY_SETTING", "on=$it")
        })
    }
}

/** R159 · 顶层组合函数，不能写进 SettingsScreen 里（Compose 不接受局部组合函数）。 */
@Composable
private fun AutoResumeRow(c: Context) {
    var on by remember { mutableStateOf(Prefs.autoResumeOnStart(c)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("打开软件自动续跑", fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = palette.text,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = {
            on = it
            Prefs.setAutoResumeOnStart(c, it)
            EventLog.ui("RESUME_SETTING", "auto=$it")
        })
    }
}

/** R171 · 「应用详情」是入口不是权限，原来复用 PermRow 会显示「已开启 · 查看」。 */
@Composable
private fun PermEntryRow(title: String, hint: String, go: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = go,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("打开 ›", fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsSection(
    title: String, badge: String, open: Boolean = true, onToggle: (Boolean) -> Unit = {},
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    // H3 · 用户实证「设置页排版很难看，乍一看不像人用的」：
    // 原来是「一条分隔线 + 一行裸标题 + 贴在一起的行」，没有边界感。
    // 改成一块一块的卡片，小徽标标归属，标题与内容拉开距离。
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth()
                    .let { if (collapsible) it.clickable { onToggle(!open) } else it },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.background(PrimarySoft, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text(badge, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = Primary)
                }
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                if (collapsible) {
                    Text(if (open) "收起 ⌃" else "展开 ›", fontSize = 12.sp, color = Sub)
                }
            }
            if (!collapsible || open) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

/** R158 · 设置页权限行。必须是顶层组合函数，局部函数 Compose 编译器不接受。
 *  R165 · 用户实证「权限如果已经有了，应该是变绿，不是还跟原来一样去开启」：
 *  已开启换成绿底，未开启维持主色；绿底前景按主题取，保证深浅都读得清。 */
@Composable
private fun PermRow(title: String, granted: Boolean, hint: String, go: () -> Unit) {
    val goodFg = if (appThemeDark) Color(0xFF0E1117) else Color(0xFFFFFFFF)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = go,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (granted) Good else Primary,
                contentColor = if (granted) goodFg else palette.onPrimary),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text(if (granted) "已开启 · 查看" else "去开启", fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit,
                           onLibrary: () -> Unit,
                           onMixer: () -> Unit) {
    val missing = remember(refresh) { ModelStore.missing(c) }
    val modeCount = remember(refresh) { ModelStore.installedModes(c).size }
    var themeMode by remember { mutableStateOf(Prefs.themeMode(c)) }
    // R171 · 开发者工具默认收起，普通用户打开设置不再先看到两枚排查按钮。
    var devOpen by remember { mutableStateOf(false) }

    // R156 · 成品导出：让用户用系统文件管理器自己选一个文件夹（一般是 Download），
    // 记住后把每章成品 WAV 平铺写进去。不开放内部 work/audio 路径配置，
    // 理由写在 core/AudioExport.kt 的类注释里。
    val expScope = rememberCoroutineScope()
    // R158 · 系统特殊权限不给 result code，用户从设置页返回后必须复检。
    var permTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val batOk = remember(permTick) { KeepAlive.batteryIgnoring(c) }
    val ovOk = remember(permTick) { KeepAlive.overlayGranted(c) }
    var expTreeLabel by remember { mutableStateOf(AudioExport.treeLabel(c)) }
    var expCount by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        expCount = withContext(Dispatchers.IO) { AudioExport.collect(c).size }
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        AudioExport.saveTree(c, uri)
        expTreeLabel = AudioExport.treeLabel(c)
        Toast.makeText(c, "导出位置已记住", Toast.LENGTH_SHORT).show()
    }

    // R142 · 日志是给开发者排查用的：导出完整 .md，或一键复制摘要直接贴过来。
    val exportScope = rememberCoroutineScope()
    val diagClipboard = LocalClipboardManager.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val os = c.contentResolver.openOutputStream(uri)
                    if (os == null) {
                        UiErrors.storage("诊断日志没导出来：系统没给写入通道", null)
                        false
                    } else {
                        os.use { it.write(EventLog.exportBundle(c).toByteArray(Charsets.UTF_8)) }
                        true
                    }
                } catch (t: Throwable) {
                    UiErrors.storage("诊断日志没导出来", t)
                    false
                }
            }
            if (ok) Toast.makeText(c, "诊断日志已保存", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (missing.isEmpty()) PrimarySoft else Warn.copy(alpha = 0.12f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (missing.isEmpty()) "模型已就绪" else "模型还没齐",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = if (missing.isEmpty()) Primary else Warn,
                        modifier = Modifier.weight(1f))
                    Button(onClick = onModels,
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text("查看 ›", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(if (missing.isEmpty()) "$modeCount 个模式可用"
                     else "还差：" + missing.joinToString("、"),
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                Text("软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME +
                     " · 本机 Android " + android.os.Build.VERSION.RELEASE +
                     " / " + (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
                    fontSize = 11.sp, color = Sub)
            }
        }

        SettingsSection("外观与显示", "软件", collapsible = false) {
            Text("主题", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth()
                .background(PrimarySoft, RoundedCornerShape(10.dp))
                .padding(3.dp)) {
                listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, m) ->
                    val on = themeMode == m
                    Box(Modifier.weight(1f).height(34.dp)
                        .background(if (on) palette.card else Color.Transparent,
                            RoundedCornerShape(8.dp))
                        .clickable {
                            themeMode = m
                            Prefs.setThemeMode(c, m)
                            applyAppTheme(c, m)
                        },
                        contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) Primary else Sub)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OverlayProgressRow(c)
        }

        SettingsSection("本机数据", "本机", collapsible = false) {
            // R171 · 开发日志不是「本机数据」，移到默认收起的「开发者工具」。
            ClearSegmentsRow(c)
        }
        // R171 · 合并「启动行为」与「通知与权限」：原来一个开关独占一张卡，
        // 另一张卡只有一行，都是把设置页拉得又长又碎的根因。
        SettingsSection("运行与通知", "系统", collapsible = false) {
            AutoResumeRow(c)
            Spacer(Modifier.height(8.dp))
            // R165 · 原来写死「去开启」，权限已经给了还是这句。按真实状态复检，
            // 回前台（permTick）就刷新；已给换绿底，和下面两行权限同一口径。
            val notifOk = remember(permTick) { NotifPerm.granted(c) }
            val goodFg = if (appThemeDark) Color(0xFF0E1117) else Color(0xFFFFFFFF)
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("通知权限", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                        try {
                            val i = android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.packageName)
                            c.startActivity(i)
                        } catch (_: Throwable) {
                            Toast.makeText(c, "这台机器没有通知设置入口", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (notifOk) Good else Primary,
                        contentColor = if (notifOk) goodFg else palette.onPrimary),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(if (notifOk) "已开启 · 查看" else "去开启", fontSize = 12.sp)
                }
            }
            // R174 · 自审发现：设置页原来有两张都标「系统」的卡（运行与通知 /
            // 后台运行与权限），同类拆两处正是「设置页不合理」的残留。
            // 现在系统类归到这一张，少一段空白、少一个重复分组。
            // R158b · 用户明确「教程不要塞进应用里」：长说明、图文步骤、
            // 箭头流程全部撤掉，只留按钮 + 状态。说明放沙盒方案文档，不进 APK。
            Spacer(Modifier.height(10.dp))
            PermRow("忽略电池优化", batOk, "后台限制") {
                if (!KeepAlive.requestIgnoreBattery(c))
                    Toast.makeText(c, "打不开设置页，请手动到应用详情里找",
                        Toast.LENGTH_SHORT).show()
            }
            PermRow("显示在其他应用上层", ovOk, "可选") {
                if (!KeepAlive.requestOverlay(c))
                    Toast.makeText(c, "打不开设置页，请手动到应用详情里找",
                        Toast.LENGTH_SHORT).show()
            }
            PermEntryRow("应用详情", "系统设置") {
                if (!KeepAlive.openAppDetails(c))
                    Toast.makeText(c, "这台机器打不开应用详情", Toast.LENGTH_SHORT).show()
            }
        }
        SettingsSection("开发者工具", "排查", open = devOpen,
                        onToggle = { devOpen = it }) {
            // R142 · 日志定位：给开发者排查用，不是给使用者看的。
            Text("开发日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("日志可能含书名和正文片段", fontSize = 11.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            // R165 · 用户实证「一键复制 / 导出 md 也应该是按钮，要有圆角矩形的底」：
            // 原来只是两枚裸文字按钮，看着不像能按。两枚等宽，次/主靠底色区分。
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    EventLog.ui("DIAG_COPY", "summary")
                    exportScope.launch {
                        val txt = withContext(Dispatchers.IO) { EventLog.exportSummary(c) }
                        diagClipboard.setText(AnnotatedString(txt))
                        Toast.makeText(c, "诊断摘要已复制，直接贴给开发者",
                            Toast.LENGTH_SHORT).show()
                    }
                },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimarySoft, contentColor = Primary),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("一键复制摘要", fontSize = 12.sp)
                }
                Button(onClick = {
                    EventLog.ui("DIAG_EXPORT", "markdown")
                    val name = "tingben_diag_" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date()) + ".md"
                    try { exportLauncher.launch(name) }
                    catch (t: Throwable) {
                        UiErrors.storage("打不开系统保存界面，没法导出日志", t)
                    }
                },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary, contentColor = palette.onPrimary),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("导出完整 .md", fontSize = 12.sp)
                }
            }
        }
        SettingsSection("成品文件", "本机", collapsible = false) {
            // R156 · 用户目标方案：「音频管理没必要了，设置里加个入口指向音频目录，
            // 点一下用系统文件管理器进去」。查证：成品在 filesDir/audio（App 私有），
            // 没有存储权限也没有 DocumentsProvider，文件管理器进不去。
            // 所以给的是「导出到你自己选的目录」，不是「打开私有目录」。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导出位置", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(expTreeLabel, fontSize = 11.sp, color = Sub)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                        try { treeLauncher.launch(AudioExport.treeUri(c)) }
                        catch (_: Throwable) {
                            Toast.makeText(c, "这台机器打不开文件夹选择", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("选择", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("已做好 $expCount 个整章成品", fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Button(enabled = expCount > 0, onClick = {
                        val tree = AudioExport.treeUri(c)
                        if (tree == null) {
                            Toast.makeText(c, "先点「选择」挑一个文件夹",
                                Toast.LENGTH_SHORT).show()
                        } else {
                            expScope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    AudioExport.exportAll(c, tree)
                                }
                                val msg = when {
                                    r.ok == 0 && r.reason.isNotEmpty() -> r.reason
                                    r.ok == 0 -> "还没有可导出的成品"
                                    r.failed > 0 -> "导出 ${r.ok} 个，失败 ${r.failed} 个" +
                                            if (r.reason.isNotEmpty()) "（${r.reason}）" else ""
                                    r.skipped > 0 -> "已导出 ${r.ok} 个，跳过 ${r.skipped} 个失效文件"
                                    else -> "已导出 ${r.ok} 个成品"
                                }
                                Toast.makeText(c, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text("导出全部", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ClearSegmentsRow(c: Context) {
    var confirmClear by remember { mutableStateOf(false) }
    val clearScope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("清理分段音频", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("只删分段 WAV", fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { confirmClear = true },
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("清理", fontSize = 12.sp)
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理分段音频？", fontSize = 14.sp) },
            text = { Text("只删制作过程里生成的分段 WAV（会连留着的其他音色缓存一起清掉），" +
                    "书架原书、成品和模型文件都不动。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    // R139 · 遍历整个 work/ 删文件原来在按钮回调（主线程）同步跑，
                    // 分段多的时候会卡住界面。改 IO 线程，回来再弹 Toast。
                    clearScope.launch {
                        val n = withContext(Dispatchers.IO) {
                            var k = 0
                            try {
                                val work = File(c.filesDir, "work")
                                work.walkTopDown()
                                    .filter { it.isFile && it.name.endsWith(".wav") }
                                    .forEach { if (it.delete()) k++ }
                            } catch (_: Throwable) { }
                            k
                        }
                        Toast.makeText(c, "已清理 $n 个分段音频", Toast.LENGTH_SHORT).show()
                    }
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
    // H2 · 用户实证：「导入模型的按钮变成了回书架导入一本书」——
    // 那个按钮会随状态变形，本身就是错的设计。现在主按钮永远叫「导入模型文件」，
    // 就绪后另外给一个明确的红色「卸载模型」危险操作，走二次确认。
    var confirmUninstall by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (allReady) "已就绪 · ${installed.size} 个模式"
                 else if (baseFile == null) "还缺共用底座" else "还缺模式主文件",
                fontSize = 12.sp, color = if (allReady) Good else Warn)
        }

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

        // 共用底座：一行摘要 + 点「详情」打开统一详情弹层
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
        }

        Text("模式主文件 · 装一份就能用", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))

        // R170 · 真机截图 26：展开一张卡，详情全塞在卡里，那张卡被撑到两倍高，
        // 同一行另一列空出一大块，第三张还被挤到下一行 —— 看着像没排完。
        // 现在三卡固定等高，详情一律移到下方统一弹层，一次只开一项；
        // 页面本身永远一屏（R136），弹层由系统处理返回键与点空白关闭。
        val modes = ModelStore.Mode.values()
        modes.toList().chunked(2).forEach { rowModes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowModes.forEach { mode ->
                    val f = modeFiles[mode]
                    val isCurrent = mode == current
                    val open = expanded == mode.id
                    CardBox(Modifier.weight(1f).height(96.dp).padding(bottom = 6.dp)
                        .clickable { toggle(mode.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                color = if (isCurrent || open) Primary else Color.Unspecified)
                            Text(if (open) "收起" else "详情 ›",
                                fontSize = 10.sp, color = Primary)
                            Spacer(Modifier.width(4.dp))
                            Text(if (f != null) "✓" else "＋", fontSize = 12.sp,
                                color = if (f != null) Good else Warn)
                        }
                        Text(if (f != null)
                                "%.2f GB%s".format(f.length() / 1024f / 1024f / 1024f,
                                    if (isCurrent) " · 当前" else "")
                            else "待导入", fontSize = 11.sp,
                            color = if (f != null) Good else Warn)
                        Text(mode.hint, fontSize = 10.sp, color = Sub, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // R170 · 统一详情弹层：完整文件名（可点复制）、两个下载地址、
        // 启用 / 删除重试 / 导入这份，全都在这里。卡里只留摘要，所以永远等高。
        expanded?.let { key ->
            val isBase = key == "base"
            val mode = ModelStore.Mode.values().find { it.id == key }
            val f = mode?.let { modeFiles[it] }
            val isCurrent = mode == current
            AlertDialog(
                onDismissRequest = { toggle(key) },
                title = {
                    Text(if (isBase) "共用底座" else (mode?.label ?: "模型详情"),
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        if (expanded == "base") {
                            Text(ModelStore.TOKENIZER, fontSize = 11.sp, color = Sub,
                                fontFamily = FontFamily.Monospace)
                            Text("12Hz 码 ↔ 24kHz 波形，导入一次就够。",
                                fontSize = 11.sp, color = Sub)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    copyUrl(cnUrl(ModelStore.TOKENIZER))
                                }) { Text("复制国内地址 ›", fontSize = 12.sp, color = Primary) }
                                TextButton(onClick = {
                                    copyUrl(hfUrl(ModelStore.TOKENIZER))
                                }) { Text("备用 ›", fontSize = 12.sp, color = Sub) }
                            }
                            if (baseFile != null) {
                                TextButton(onClick = {
                                    ModelStore.delete(c, baseFile); onChanged(); toggle(key)
                                }) { Text("删除重试", fontSize = 12.sp, color = Sub) }
                            }
                        } else if (mode != null) {
                            Text(mode.id, fontSize = 11.sp, color = Sub)
                            // R169 · 两列卡太窄会把 40 字符文件名竖着拆成 5 行；
                            // R170 移到全宽弹层，单行省略 + 点一下复制完整名。
                            Text(mode.file, fontSize = 11.sp, color = Sub, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    clipboard.setText(AnnotatedString(mode.file))
                                    Toast.makeText(c, "文件名已复制",
                                        Toast.LENGTH_SHORT).show()
                                })
                            Text(mode.hint, fontSize = 11.sp, color = Sub)
                            if (f != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!isCurrent) {
                                        TextButton(onClick = {
                                            if (!st.running) {
                                                Prefs.setModelMode(c, mode.id)
                                                onChanged(); toggle(key)
                                            }
                                        }, enabled = !st.running) {
                                            Text("启用 ›", fontSize = 11.sp, color = Primary)
                                        }
                                    } else Text("当前模式", fontSize = 11.sp, color = Sub)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        ModelStore.delete(c, f); onChanged(); toggle(key)
                                    }) { Text("删除重试", fontSize = 11.sp, color = Sub) }
                                }
                                if (!isCurrent && st.running)
                                    Text("正在制作，跑完前不能切", fontSize = 11.sp, color = Warn)
                            } else {
                                TextButton(onClick = onPick, enabled = importing == null) {
                                    Text("导入这份 ›", fontSize = 11.sp, color = Primary)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { copyUrl(cnUrl(mode.file)) }) {
                                    Text("国内地址 ›", fontSize = 11.sp, color = Primary)
                                }
                                TextButton(onClick = { copyUrl(hfUrl(mode.file)) }) {
                                    Text("备用地址 ›", fontSize = 11.sp, color = Sub)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { toggle(key) }) { Text("关闭", fontSize = 13.sp) }
                }
            )
        }

        Spacer(Modifier.height(2.dp))

        CardBox(Modifier.padding(bottom = 6.dp)) {
            if (allReady) {
                Text("底座 + 至少 1 个模式都在", fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Text(current.label + " 是当前模式。" + (if (current == ModelStore.Mode.VOICE_DESIGN)
                    "可以合成音频了。" else "切回「描述造音色」后即可继续。"),
                    fontSize = 11.sp, color = Sub)
                if (current != ModelStore.Mode.VOICE_DESIGN) {
                    TextButton(onClick = {
                        if (!st.running) {
                            Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)
                            onChanged()
                        }
                    }, enabled = !st.running) {
                        Text("切回「描述造音色」 ›", fontSize = 11.sp, color = Primary)
                    }
                    if (st.running)
                        Text("正在制作，跑完前不能切", fontSize = 10.sp, color = Warn)
                }
            } else {
                Text("还差 " + missing.joinToString("、"), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            }
            // H2 · 主按钮语义固定，不随就绪状态变形。
            Button(onClick = onPick, enabled = importing == null,
                modifier = Modifier.fillMaxWidth().height(40.dp)) {
                Text("导入模型文件", fontSize = 13.sp)
            }
            // R165 · 用户实证「模型页不需要去书架按钮」：二级页返回由顶栏统一给，
            // 这里再放一枚只会让人不知道该点哪个。onLibrary 保留给调用方兼容。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (files.isNotEmpty()) {
                    Button(onClick = {
                        EventLog.ui("MODEL_UNINSTALL_ASK", "n=${files.size}")
                        confirmUninstall = true
                    }, enabled = !st.running,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Warn)) {
                        Text("卸载模型", fontSize = 12.sp, color = palette.onWarn)
                    }
                }
            }
            if (st.running && files.isNotEmpty())
                Text("正在制作，跑完才能卸载模型", fontSize = 10.sp, color = Sub)
        }

        // H2 · 卸载是破坏性操作：二次确认，只删模型文件，
        // 原书、已做好的音频、分段缓存一律不动；卸载后要重新导入才能再合成。
        if (confirmUninstall) {
            AlertDialog(
                onDismissRequest = { confirmUninstall = false },
                title = { Text("卸载已导入的模型？", fontSize = 15.sp,
                    fontWeight = FontWeight.Bold) },
                text = {
                    Text("会删掉本机 ${ModelStore.files(c).size} 个模型文件。" +
                            "书架里的书、已做好的音频和分段缓存都不动；" +
                            "卸载后要重新导入才能再合成。",
                        fontSize = 13.sp, color = Sub)
                },
                confirmButton = {
                    TextButton(onClick = {
                        val n = ModelStore.files(c).size
                        ModelStore.files(c).forEach { ModelStore.delete(c, it) }
                        EventLog.ui("MODEL_UNINSTALL", "deleted=$n")
                        confirmUninstall = false
                        onChanged()
                        Toast.makeText(c, "已卸载 $n 个模型文件",
                            Toast.LENGTH_SHORT).show()
                    }) { Text("卸载", fontSize = 13.sp, color = Warn) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUninstall = false }) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            )
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


/**
 * R142 · 合成前的调音弹层。调音本来就是「点开始之前」的准备工作，
 * 之前却是一张和书架平级的一级页，来回跳还不知道该回哪。
 * 这里用全屏 Dialog 复用整套 MixerScreen（保存、试听、未保存确认都在里面），
 * 顶栏多给一枚关闭叉，返回键也由窗口自己消费。
 */
@Composable
private fun MixerDialog(c: Context, onClose: () -> Unit) {
    // R155 · 用户实证两处：弹层底部会盖住系统返回/桌面手势区；点空白处返回不了。
    // 原来 onDismissRequest 是空实现，只有顶栏一枚灰叉能退出。
    // 现在让 MixerScreen 把「有改动先确认、没改动直接关」的 leave 注册上来，
    // 空白点按、返回键、关闭叉走同一个出口，不会绕过未保存确认。
    var requestLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    Dialog(
        onDismissRequest = { requestLeave?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // statusBars + navigationBars 都避让：全屏 Dialog 默认会伸到手势条下面，
        // 底部「保存 / 试听」被系统导航区压住，看起来就像「没有出口」。
        Surface(Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
            shape = RoundedCornerShape(16.dp), color = Bg) {
            MixerScreen(c, onBack = onClose, onClose = onClose,
                onRegisterLeave = { requestLeave = it })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MixerScreen(c: Context, onBack: () -> Unit,
                        onClose: (() -> Unit)? = null,
                        onRegisterLeave: ((() -> Unit) -> Unit)? = null) {
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
                            // R165 · 第三份真机日志实证：MIXER_TRIAL_FAIL「没生成声音」，
                            // TTS_PARAMS 里 speaker=null。官方示例 generate_custom_voice
                            // 必须给 speaker（Vivian / Ryan），缺了引擎直接失败。
                            // 正式合成（Pipeline 255 行）本来就有「没选就取音色表第一个」
                            // 的兜底，试听却漏了 —— 两处口径对齐，别再白跑一趟。
                            val tone = listOf(src.cvStyle.trim(), src.perf.styleInstruct().trim())
                                .filter { it.isNotEmpty() }.joinToString("；")
                            val speaker = listOf(src.speaker, Prefs.speakerName(c))
                                .firstOrNull { it.isNotBlank() }
                                ?: Prefs.speakerNames(c).firstOrNull()
                                    ?.also { Prefs.setSpeakerName(c, it) }.orEmpty()
                            if (speaker.isBlank())
                                throw IllegalStateException("先在下面读取模型自带的音色表，挑一个音色再试听")
                            SynthInput.Params(tone.ifBlank { null }, speaker)
                        }
                        ModelStore.Mode.BASE -> SynthInput.Params(null, null)
                    }
                    ensureActive()
                    val f = File(c.filesDir, out)
                    val code = NativeEngine.ttsSynthesize(h, text, v.instruct, v.speaker,
                        "chinese", f.absolutePath, src.knobs)
                    ensureActive()
                    if (code != 0 || !f.exists() || f.length() <= 44) {
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL_EMPTY",
                            "mode=${mode.id} · code=$code · " +
                                "speaker=${v.speaker ?: "空"} · chars=${text.length} · " +
                                "instructChars=${v.instruct?.length ?: 0}")
                        throw IllegalStateException(
                            SynthErrors.explain(code, f.exists() && f.length() > 44))
                    }
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
                // R165 · 原来记成 CRASH 级，日志里就冒出一条「CRASH | MIXER_TRIAL_FAIL」，
                // 把「最近一次崩溃」和崩溃前环形缓冲都污染了，其实软件根本没崩。
                res.exceptionOrNull()?.let {
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL_FAIL",
                        "mode=${mode.id} · ${it.message ?: "未知原因"}")
                }
            }
        }
        trialJob = job
    }

    val leave = { if (dirty) showLeave = true else (onClose ?: onBack)() }
    // R155 · 把同一个 leave 交给外层 Dialog 的 onDismissRequest：
    // 点遮罩空白处也能退出，且「有改动」时照样先弹未保存确认。
    if (onRegisterLeave != null) LaunchedEffect(dirty) { onRegisterLeave(leave) }
    BackHandler(enabled = dirty) { showLeave = true }
    // 弹层形态下，没有改动时返回键直接关窗口；有改动时上面的 BackHandler 先弹确认。
    // 两者互斥，不会和外层全局返回打架（后注册优先）。
    if (onClose != null) BackHandler(enabled = !dirty) { onClose?.invoke() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // R148 · 原来返回箭头永远显示，弹层又叠一枚关闭叉，用户看到「两个返回键」。
            // 弹层只留关闭叉，独立页只留返回箭头，一个界面一个出口。
            if (onClose == null) {
                Box(Modifier.size(40.dp).clickable { leave() },
                    contentAlignment = Alignment.Center) { IconBack(Primary) }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Text("调音台", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.weight(1f))
            if (dirty) {
                Button(onClick = { commitAll() },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Text("保存", fontSize = 12.sp)
                }
            }
            // 弹层才有关闭叉；独立页走顶栏返回键即可，不重复给两个出口。
            // R155 · 原来是一枚灰色 IconClose，用户说「没有明确的返回按键」：
            // 换成带浅底的主色叉，形态和可点性一致，一眼看出这是出口。
            if (onClose != null) {
                Box(Modifier.size(40.dp)
                        .background(PrimarySoft, RoundedCornerShape(12.dp))
                        .clickable { leave() },
                    contentAlignment = Alignment.Center) {
                    IconClose(Primary)
                }
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))

            ModeTabs(mode, installed) { switchMode(it) }
            if (mode.id !in installed) {
                Text("「${mode.label}」未导入", fontSize = 12.sp, color = Warn)
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


            // I1 · 用户实证「试听文本和试听两个按钮在采样参数上面」。
            // 顺序定成：选模式 → 描述想要的声音 → 马上试听 → 再细调九项参数，
            // 不用来回滚一长串滑块找试听按钮。
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
                Text("沿用全局设置", fontSize = 12.sp, color = Sub)
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
                TextButton(onClick = {
                    showLeave = false; discardAll(); (onClose ?: onBack)()
                }) {
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
        placeholder = { Text("描述想要的音色", fontSize = 12.sp) },
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
        placeholder = { Text("风格补充（可留空）", fontSize = 12.sp) },
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
    Spacer(Modifier.height(12.dp))
    Text("这段录音念的是什么（可留空）", fontSize = 12.sp,
        fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.refText, onValueChange = { onDraft(draft.copy(refText = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("（可留空）", fontSize = 12.sp) },
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
            // I1 · 用户实证「两个刷新键太小了根本点不到」。
            // 原来只是 12sp 字符 + 4dp padding，热区约 20dp；扩成 40dp 方块。
            // J1 · 看图又发现：光扩热区仍看不出这里有个按钮（草图里像个空 checkbox）。
            // 补上浅色圆底 + 主色字符，形态和可点性一致。
            Box(Modifier.size(40.dp)
                    .background(PrimarySoft, RoundedCornerShape(10.dp))
                    .clickable { onValue(spec.def) },
                contentAlignment = Alignment.Center) {
                Text("↺", fontSize = 17.sp, color = Primary)
            }
        }
        // R158b · 种子语义不进 UI（那是教程）。结论留在沙盒工作卡，界面只给滑块。
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

/**
 * R159 · 续跑弹窗重设计。
 *
 * 用户实证：「这个弹窗做的很丑，要重新设计」。原来是系统 AlertDialog 默认外形 +
 * 一句拼起来的长解释 + 主按钮和两个文字按钮挤在一行，权重全乱，还没有进度可视。
 * 现在：圆角卡 + 代码绘制图标 + 状态胶囊 + 真实进度条 + 整宽主按钮，
 * 三态（可续跑 / 继续队列 / 需手动）各自给对的出口。
 *
 * R158b 硬口径：不写教程，不写「接着跑不会重算…」这类解释句；
 * 状态用一枚胶囊说清，进度用一根条说清。
 */
@Composable
private fun ResumeAskDialog(
    title: String, done: Int, total: Int, label: String,
    action: Producer.ResumeAction,
    onPrimary: () -> Unit, onView: () -> Unit, onLater: () -> Unit
) {
    Dialog(onDismissRequest = onLater) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = palette.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp)
                            .background(palette.primarySoft, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        IconResume(palette.primary, Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("上次没做完", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = palette.text)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.background(palette.primarySoft, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(label, fontSize = 11.sp, color = palette.primary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = palette.text, maxLines = 2)
                Spacer(Modifier.height(14.dp))

                val frac = if (total > 0)
                    (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(palette.line)) {
                    Box(Modifier.fillMaxWidth(frac).height(6.dp)
                            .clip(RoundedCornerShape(3.dp)).background(palette.primary))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$done / $total 块", fontSize = 12.sp, color = palette.sub)
                    Spacer(Modifier.weight(1f))
                    Text("${(frac * 100).toInt()}%", fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, color = palette.primary)
                }
                Spacer(Modifier.height(20.dp))

                Button(onClick = onPrimary,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(50)) {
                    Text(when (action) {
                        Producer.ResumeAction.AUTO -> "继续制作"
                        Producer.ResumeAction.QUEUE -> "继续队列"
                        Producer.ResumeAction.VIEW -> "查看任务"
                    }, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (action != Producer.ResumeAction.VIEW) {
                        OutlinedButton(onClick = onView,
                            modifier = Modifier.weight(1f).height(40.dp),
                            shape = RoundedCornerShape(50)) {
                            Text("查看任务", fontSize = 12.sp)
                        }
                    }
                    // J 系列看图结论：并排的次按钮要同形，一个描边一个裸文字
                    // 看着像「少画了一个框」；层级靠文字颜色区分，不靠外形。
                    OutlinedButton(onClick = onLater,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(50)) {
                        Text("稍后", fontSize = 12.sp, color = palette.sub)
                    }
                }
            }
        }
    }
}
