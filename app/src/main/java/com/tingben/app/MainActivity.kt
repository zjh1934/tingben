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
import java.io.File

private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }

/** R17 · 制作方式是正式选择，不是藏在按钮底下的调试捷径。第三档「改编剧本」= 换逐字契约，单列二期。 */
private enum class ProduceMode { DIRECT }

// R73 · 颜色跟随主题：普通 getter 读顶层 snapshot state，组合期自动跟踪，切换即重组。
// 22 个调用点一行不动；亮/暗两套色板见 Theme.kt。
private val Bg: Color get() = palette.bg
private val Card: Color get() = palette.card
private val Primary: Color get() = palette.primary
private val PrimarySoft: Color get() = palette.primarySoft
private val Sub: Color get() = palette.sub
private val Good: Color get() = palette.good
private val Line: Color get() = palette.line
private val Warn: Color get() = palette.warn

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
        applyAppTheme(this, Prefs.themeMode(this))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(incoming: String? = null, onConsumed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.LIBRARY) }
    var bookId by remember { mutableStateOf<String?>(null) }
    // R61 · 悬浮条记住「最近播放的是哪本书」，目录弹层与去书页都用它。
    var miniBook by remember { mutableStateOf<String?>(null) }
    var lastSelection by remember { mutableStateOf<Set<Int>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var modelsFrom by remember { mutableStateOf(Screen.LIBRARY) }
    var importing by remember { mutableStateOf<String?>(null) }
    val importBytesFlow = remember { MutableStateFlow(0L) }
    val importBytes by importBytesFlow.collectAsState()
    var importError by remember { mutableStateOf<String?>(null) }
    // R94 · 导入书失败留一张能重试的细卡，Toast 一闪就没了不算可恢复。
    var bookImportError by remember { mutableStateOf<String?>(null) }
    // R51 · 高频引擎状态不再在 App() 顶层订阅。旧版为了给书架页传 5 个根本没被使用的
    // 参数，把 Producer.state / Producer.resume 挂在整棵页面树的根上：制作时每次块更新
    // 都会重组 when(screen) 下全部 9 个页面。现在收进 KeepScreenOn() / 进度页两个
    // 叶子 composable（官方「状态读在树的最低处」），回调里只读 .value，不订阅。
    // R65 · 贴底任务卡已整条删除，不再有第三个高频状态订阅点。

    // V1 · 抽屉状态：☰ 与从左边缘右滑都开它；drawerReturn 记「从哪一页进的二级页」。
    // 形状抄 reference/OfflineLLM/ChatScreen.kt:99,151-160（Apache-2.0），不自写横向手势。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var drawerReturn by remember { mutableStateOf(Screen.LIBRARY) }

    // 系统返回键/手势统一回书架，而不是直接退出 App。
    BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }
    // V1 · 后注册的 callback 优先：抽屉开着时先收抽屉，不误退回书架。
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // R19 · 长任务在跑时保持屏幕常亮：零权限、零 WakeLock；不等于保活。
    // R51 · 下沉到叶子 composable，避免 App() 为它订阅高频引擎状态。
    KeepScreenOn()

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

    // U1 · 顶层目的地归属集中到 chromeOf()，顶栏/抽屉只消费规格，不再各自写 screen == ...
    val chrome = chromeOf(screen)
    // V1 · 抽屉选中态：顶层沿用 chrome.activeTab 的下标，二级页一一对应。
    val drawerSelected = when (screen) {
        Screen.LIBRARY, Screen.PRODUCE, Screen.CHAPTERS -> NAV_LIBRARY
        Screen.PROGRESS, Screen.TASKS -> NAV_TASKS
        Screen.AUDIO -> NAV_AUDIO
        Screen.SETTINGS -> NAV_SETTINGS
        Screen.MIXER -> NAV_MIXER
        Screen.MODELS -> NAV_MODELS
    }
    // R51 · 联动抽屉：官方 DismissibleNavigationDrawer 展开时把右侧工作页一起推走，
    // 不再是 ModalNavigationDrawer 那种「抽屉盖在内容上 + 全屏遮罩」的覆盖式。
    // ☰ / 从左边缘右滑 / 返回键优先收抽屉三个行为一行未改。
    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            AppDrawerSheet(
                selected = drawerSelected,
                onNavigate = { idx ->
                    // 从顶层页进二级页时记住来源；已在二级页则沿用上一次来源
                    val from = when (screen) {
                        Screen.LIBRARY, Screen.TASKS, Screen.AUDIO,
                        Screen.PRODUCE, Screen.PROGRESS -> screen
                        else -> drawerReturn
                    }
                    drawerReturn = from
                    screen = when (idx) {
                        NAV_LIBRARY -> Screen.LIBRARY
                        NAV_TASKS -> Screen.TASKS
                        NAV_AUDIO -> Screen.AUDIO
                        NAV_SETTINGS -> Screen.SETTINGS
                        NAV_MIXER -> Screen.MIXER
                        NAV_MODELS -> Screen.MODELS
                        else -> Screen.LIBRARY
                    }
                    // 原菜单 / 底栏的刷新副作用迁到这里，语义不变
                    if (idx == NAV_AUDIO || idx == NAV_SETTINGS) refresh++
                    if (idx == NAV_MODELS) modelsFrom = from
                    scope.launch { drawerState.close() }
                },
                surface = Card,
                primary = Primary,
                sub = Sub
            )
        }
    ) {
        // 底栏移除后，底部系统手势区由这一层统一让开（原 AppBottomBar 的 navigationBarsPadding）
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        AppTopBar(
            spec = chrome,
            onMenu = { scope.launch { drawerState.open() } },
            onImport = { openBookPicker() },
            surface = Card,
            primary = Primary
        )
        Box(Modifier.weight(1f)) {
            when (screen) {
                // R51 · producing / progressText / onProgress / pendingTask / onTasks
                // 五个参数在 LibraryScreen 函数体里从未被读取，白挂了一路高频订阅。
                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; screen = Screen.CHAPTERS },
                    onModels = { modelsFrom = Screen.LIBRARY; screen = Screen.MODELS },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ },
                    importError = bookImportError,
                    onDismissImportError = { bookImportError = null })
                // R61 · 一本书点进去 = 四列目录；制作准备页仍在任务/进度链路可达。
                Screen.CHAPTERS -> ChapterHubScreen(ctx, bookId, refresh,
                    onBack = { screen = Screen.LIBRARY },
                    onProgress = { screen = Screen.PROGRESS },
                    onPlayBook = { miniBook = it },
                    onMixer = { drawerReturn = Screen.CHAPTERS; screen = Screen.MIXER })
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { screen = Screen.LIBRARY },
                    onProgress = { screen = Screen.PROGRESS },
                    onDone = { screen = Screen.AUDIO; refresh++ },
                    onStarted = { lastSelection = it })
                Screen.PROGRESS -> ProgressScreen(ctx,
                    onBack = { screen = Screen.LIBRARY },
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
                    onProgress = { screen = Screen.PROGRESS },
                    onAudio = { screen = Screen.AUDIO; refresh++ },
                    onLibrary = { screen = Screen.LIBRARY },
                    onResume = { t ->
                        val b = BookStore.list(ctx).find { x -> x.id == t.bookId }
                        if (b != null) {
                            bookId = b.id; lastSelection = t.only
                            Producer.start(ctx, b, t.only, true, t.think, t.config)
                            screen = Screen.PROGRESS
                        } else {
                            Toast.makeText(ctx, "这本书已经从书架删掉了，任务没法续跑", Toast.LENGTH_LONG).show()
                        }
                    })
                Screen.AUDIO -> AudioScreen(ctx, refresh,
                    onLibrary = { screen = Screen.LIBRARY },
                    onModels = { modelsFrom = Screen.AUDIO; screen = Screen.MODELS })
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; screen = Screen.MODELS },
                    onLibrary = { screen = Screen.LIBRARY },
                    onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER })
                Screen.MIXER -> MixerScreen(ctx, onBack = { screen = drawerReturn })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { screen = modelsFrom },
                    onLibrary = { screen = Screen.LIBRARY },
                    onChanged = { refresh++ })
            }
        }

        // R51 · 任务条抽成叶子 composable：自己订阅引擎状态，
        // App() 不再为它重组整棵 when(screen) 页面树。
        // R61 · 软件内悬浮条：跨页常驻，目录弹层只列第四列亮章。
        MiniPlayBar(ctx, miniBook, onGoBook = { id ->
            bookId = id
            screen = Screen.CHAPTERS
        })
        // R65 · 贴底任务卡整条删除（自带上扒/下扒折叠那张）；
        // 「去任务页 / 去试听」由抽屉既有的「任务 / 我的音频」承接，不新建入口。
        // V1 · 3 个顶层 Tab 已收进左侧抽屉；底部安全区由外层 Column 的 navigationBarsPadding() 负责。
    }
    }
}


/**
 * R51 · 屏幕常亮下沉到叶子：以前 DisposableEffect(st.running) 写在 App() 里，
 * 等于让整棵页面树订阅引擎高频状态。这里单独订阅，本函数不产生任何 UI。
 */
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

/**
 * U1 · 全 App 唯一的「目的地 → 壳规格」映射（Now in Android 的 TopLevelDestination 思路）。
 * 原先 4 处 screen == ... 判断散在顶栏和底栏里；这里一次定义。
 * ⚠️ 只描述显示，不改导航：when(screen) 分发、refresh 计数、modelsFrom、BackHandler 全不动。
 */
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
                           onChanged: () -> Unit,
                           importError: String? = null,
                           onDismissImportError: () -> Unit = {}) {
    val books = remember(refresh) { BookStore.list(c) }
    val ready = remember(refresh) { ModelStore.ready(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    // R96 · 一次批量读、放后台：N 本书从 N 次组合期磁盘读降为 1 次异步读。
    var lastReads by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(refresh, books) {
        lastReads = withContext(Dispatchers.IO) { ReadingState.allLast(c) }
    }
    val clipboard = LocalClipboardManager.current
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
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

    // R96 · 只组合看得见的书卡；各区块各占一个 item，key 稳定，滚动不重排。
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
        if (!ready) {
            item(key = "missing") {
                Row(
                    Modifier.fillMaxWidth().clickable { onModels() }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("还差：" + missing.joinToString("、"), fontSize = 11.5.sp, color = Warn,
                        maxLines = 2, modifier = Modifier.weight(1f))
                    Text("去准备 ›", fontSize = 11.5.sp, color = Primary)
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        // R94 · 失败可恢复：细卡不抢主操作，能直接重选文件，也能关掉。
        if (importError != null) {
            item(key = "import-error") {
                CardBox(Modifier.padding(bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("上次没导进来", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = Warn, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissImportError) {
                            Text("关闭", fontSize = 11.5.sp, color = Sub)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(importError, fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onImportBook) {
                        Text("再选一次文件 ›", fontSize = 12.sp, color = Primary)
                    }
                }
            }
        }

        if (books.isEmpty()) {
            item(key = "empty") {
                // R78 · 空态只留一张主卡：主操作最大，粘贴是次级入口
                CardBox(Modifier.padding(bottom = 12.dp)) {
                    Text("还没有书", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("导入本地 TXT / EPUB，或先粘一段文字试试", fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onImportBook, Modifier.fillMaxWidth().height(48.dp)) {
                        Text("导入一本书", fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { pasteOpen = !pasteOpen }) {
                        Text(if (pasteOpen) "收起粘贴" else "粘贴文字建书 ›", fontSize = 12.sp, color = Primary)
                    }
                }
            }
        }
        // R78 · 粘贴框只在点开时出现，不占首屏
        if (pasteOpen) {
            item(key = "paste") {
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
        }
        if (books.isNotEmpty()) {
            item(key = "count") {
                Text("共 ${books.size} 本", fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
            }
            items(books, key = { it.id }) { b ->
                val lastRead = lastReads[b.id]
                CardBox(Modifier.padding(bottom = 10.dp).clickable { onOpen(b.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                            Spacer(Modifier.height(3.dp))
                            Text("${b.chars} 字", fontSize = 11.5.sp, color = Sub)
                            if (lastRead != null) {
                                Spacer(Modifier.height(3.dp))
                                Text("上次读到 第 ${lastRead + 1} 章", fontSize = 11.sp, color = Primary)
                            }
                        }
                        Text("›", fontSize = 18.sp, color = Sub)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = b.id }) {
                            Text("删除", fontSize = 11.sp, color = Sub)
                        }
                    }
                }
            }
            item(key = "paste-entry") {
                TextButton(onClick = { pasteOpen = !pasteOpen }) {
                    Text(if (pasteOpen) "收起粘贴" else "粘贴文字建书 ›", fontSize = 12.sp, color = Primary)
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
    // R20 · 一次 split 同时拿章节与全部块，用来显示「预计几块」，不再只取 .first。
    val split = remember(book) { book?.let { ChapterSplitter.split(BookStore.read(c, it)) } }
    val chapters = split?.first ?: emptyList()
    val allChunks = split?.second ?: emptyList()
    var selected by remember(book) { mutableStateOf<Set<Int>>(emptySet()) }
    var cacheTick by remember { mutableStateOf(0) }
    val thinkOn = false  // R67 · P1a：不再读写思考设置键
    // R17 · 进页面时对齐已有任务：书正以「直接朗读」跑着，别把高亮停在旧档位，
    // 否则用户看到的模式和任务页/断点续跑用的不是同一套。
    // R20 · 本次预设也对齐已有任务书签，续跑时界面显示的档位与实际跑的一致。
    var mode by remember(book, refresh) {
        val t = TaskResume.load(c)
        // R25 · 历史教训：零产出不许在日志里写成「通过」。
        // 新书默认走直接朗读；已有任务仍按书签原样恢复，不擅自改正在跑的档。
        mutableStateOf(if (t != null && t.bookId == id)
            ProduceMode.DIRECT
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
    // 不然切到直接朗读还会显示「继续制作」，点的却是旧分桶的缓存。
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
                        Text("直接朗读",
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
        }

        ExpandGroup("③ 怎么跑 · 仅本次", "") {
            OptionRow("段间静音", "",
                ProduceConfig.GAP_OPTIONS.map { it to "${it}ms" }, cfg.gapMs) { cfg = cfg.copy(gapMs = it) }
            OptionRow("分段失败重做", "",
                ProduceConfig.RETRY_OPTIONS.map { it to "${it}次" }, cfg.gateRetries) { cfg = cfg.copy(gateRetries = it) }
        }

        ExpandGroup("④ 本次制作 · 只读", "") {
            listOf(
                "声音身份" to if (Prefs.voiceDescription(c).isBlank()) "默认锚点" else "自定义",
                "采样种子" to "${Prefs.ttsKnobs(c).seed}",
                "软件版本" to com.tingben.app.BuildConfig.VERSION_NAME
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
                // 清的是当前模式的那一桶，不能切到直接朗读却还去清旧缓存。
                Producer.clearProgress(c, book.id, directMode, thinkOn,
                    if (selected.isEmpty() || selected.size == chapters.size) null else selected)
                cacheTick++
            }, Modifier.fillMaxWidth()) { Text("从头重做（清掉已做的块）", fontSize = 12.sp) }
        }

        if (st.message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(st.message, fontSize = 12.5.sp, color = if (st.output != null) Good else Sub)
        }
    }
}

// ── ③ 进度 · 空间重分配 + 看台「点格子即播 / 再点停」 ─────────────────────
@Composable
private fun ProgressScreen(c: Context, onBack: () -> Unit, onResume: () -> Unit) {
    val st by Producer.state.collectAsState()
    val pct = if (st.total > 0) st.done.toFloat() / st.total else 0f
    var tick by remember { mutableStateOf(0) }
    // R51 · 没在制作时不需要心跳，停掉无谓的每秒整页重组。
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
    // R79 · 心跳 remember(tick) 是纯内存；这四项都要读磁盘（segmap / 最多 60 次 stat /
    // 整本书重切章），原来跟 tick 走＝每秒一整轮。改跟「完成的段数」走＝每段一整轮。
    val seenTask = remember(st.done, redoNonce) { TaskResume.load(c) }
    val segs = remember(st.done, seenTask, redoNonce) { Workbench.segments(c, seenTask) }
    val rest = remember(st.done, seenTask, redoNonce) { Workbench.remainingCount(c, seenTask) }
    val verdict = remember(st.done, seenTask, redoNonce) { Workbench.verdict(c, seenTask) }
    var showCompare by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val pqSeg by PlayQueue.state.collectAsState()
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(st.running) { if (!st.running) stopping = false }

    // 底部系统手势区 24dp / 三键 48dp 由外层 navigationBarsPadding() 负责，本页不再自画。
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {

        // ① 细状态条：一个纯图标 + 进度条 + 11sp 百分比 + 一行状态，无大字、无返回、无收起
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
                    LinearProgressIndicator(progress = { pct },
                        modifier = Modifier.fillMaxWidth().height(6.dp))
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.stage, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        Text(if (st.total > 0) "${(pct * 100).toInt()}%" else "—",
                            fontSize = 11.sp, color = Primary)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (st.total > 0) "已完成 ${st.done}/${st.total} 块" +
                        if (st.failed > 0) " · ${st.failed} 块改走直读" else ""
                else st.message.ifBlank { "还没开始" },
                fontSize = 11.5.sp, color = Sub)
            if (st.running) {
                Spacer(Modifier.height(6.dp))
                Text(heartbeat, fontSize = 11.sp, color = Primary)
            }
        }

        // ② 四步横排：做过的下面 3dp 色块，正在做只加粗，没做的没有色块
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { i, stepName ->
                val doneStep = curStep > i
                val nowStep = curStep == i
                Column(Modifier.weight(1f)) {
                    Text(stepName, fontSize = 10.sp, maxLines = 1,
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

        // ④ 分段看台：一行 8 格，格子本身就是播放开关；点一下读这一段，再点一下停
        Spacer(Modifier.height(10.dp))
        CardBox {
            Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (segs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("还没有分段", fontSize = 10.sp, color = Sub)
            } else {
                Spacer(Modifier.height(9.dp))
                segs.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { sg ->
                            val label = "第 ${sg.index + 1} 段"
                            // R103 · F-01：身份按 WAV 绝对路径（跨书唯一），不再拿显示文字当主键；
                            // F-10：颜色 / 可点 / 角标只认同一个权威判定，不再 hasAudio 与 wav 两套。
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
                                            PlayQueue.setTracks(listOf(Track(wav, label, id = segId)))
                                            PlayQueue.playFrom(0)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${sg.index + 1}", fontSize = 10.sp,
                                    color = if (playing) Primary else base)
                                if (playable)
                                    Text(if (playing) "⏸" else "▶", fontSize = 6.sp, color = Good,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ── ③b 任务中心：长任务单独一个区域管，不藏在书架或设置里 ──────────
@Composable
private fun TaskCenterScreen(c: Context, refresh: Int, onProgress: () -> Unit,
                             onAudio: () -> Unit, onLibrary: () -> Unit,
                             onResume: (TaskInfo) -> Unit) {
    val st by Producer.state.collectAsState()
    // R101 · 磁盘书签最快 2 秒后才刷，而「继续制作 / 从头重做」点下去的那一瞬间
    // 界面还挂着上一条「已完成」。Producer.resume 是即时的，优先用它。
    val live by Producer.resume.collectAsState()
    // R79 · 旧版 LaunchedEffect(Unit) 无条件每 2 秒整页重组 + 三次磁盘读，
    // 哪怕「还没有任务」也照跑。改成只在制作中轮询，静止页零定时器。
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) { loadTick++; return@LaunchedEffect }
        while (true) { delay(2000); loadTick++ }
    }
    val saved = remember(refresh, loadTick) { TaskResume.load(c) }
    val t = live ?: saved
    val real = remember(t, refresh, st.running) {
        if (st.running) 0 else t?.let { TaskResume.realProgress(c, it) } ?: 0
    }
    val bookExists = remember(t, refresh) {
        t?.let { BookStore.list(c).any { b -> b.id == it.bookId } } ?: false
    }
    // R101 · 三模式接通后还写死「直接朗读」是旧档位话术；按书签里记的模式照实显示，
    // 旧书签没记模式则回退到当时唯一的 voice_design。
    val modeText = when (t?.modeId) {
        "custom_voice" -> "预设音色"
        "base" -> "参考音频"
        else -> "描述造音色"
    }
    val scopeText = t?.let {
        if (it.only == null) "整本" else "选中 ${it.only!!.size} 章"
    } ?: ""
    // R101 · 切过模式 / 改过调音台时，续跑会作废旧 WAV，先在任务页如实说，
    // 不再等点下去才发现「已做好 X 块」是假的。
    val invalidate = remember(t, refresh) { t?.let { TaskResume.invalidationReason(c, it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (t == null) {
            CardBox {
                Text("还没有任务", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text("在书架选一本书，勾选章节后点「合成音频」，这里就会出现进度。",
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLibrary, Modifier.fillMaxWidth().height(48.dp)) {
                    Text("去书架，选一本书")
                }
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
                if (invalidate != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(invalidate!!, fontSize = 11.sp, color = Warn)
                }
                // R101 · st.total 每块更新、书签 t.total 可能落后一拍，取三者最大；
                // 以前不 coerce，done/total > 1 时 LinearProgressIndicator 会抛异常。
                val total = maxOf(t.total, real, if (st.running) st.total else 0)
                val doneNow = (if (st.running) st.done else real).coerceIn(0, total)
                if (total > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) doneNow.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().height(7.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("已做好 $doneNow/$total 块", fontSize = 12.sp, color = Sub)
                }
                Spacer(Modifier.height(5.dp))
                Text("最后更新 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(java.util.Date(t.updatedAt)), fontSize = 11.sp, color = Sub)
                Spacer(Modifier.height(8.dp))

                when {
                    st.running -> {
                        TextButton(onClick = onProgress) { Text("看实时进度 ›", fontSize = 12.sp) }
                    }
                    t.phase == "done" -> {
                        TextButton(onClick = onAudio) { Text("去「我的音频」试听 ›", fontSize = 12.sp) }
                        // R101 · 以前做完只能去试听，想重做没入口；两个小文字按钮补齐，
                        // 与「已中断」分支共用同一套清理 / 启动语义。
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                                val b = BookStore.list(c).find { x -> x.id == t.bookId }
                                if (b != null) {
                                    Producer.start(c, b, t.only, true, t.think, t.config)
                                    onProgress()
                                }
                            }, Modifier.weight(1f)) { Text("从头重做", fontSize = 12.sp) }
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                                loadTick++
                            }, Modifier.weight(1f)) {
                                Text("放弃这条记录", fontSize = 12.sp, color = Warn)
                            }
                        }
                    }
                    !bookExists -> {
                        Text("这本书已经从书架删掉了，任务没法续跑。", fontSize = 12.sp, color = Warn)
                        TextButton(onClick = {
                            Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                        }) { Text("清除这条失效任务", fontSize = 12.sp) }
                    }
                    else -> {
                        Button(onClick = { onResume(t) }, Modifier.fillMaxWidth().height(46.dp)) {
                            Text("继续制作（接着已做好的块跑）", fontSize = 15.sp)
                        }
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                                val b = BookStore.list(c).find { x -> x.id == t.bookId }
                                if (b != null) { Producer.start(c, b, t.only, true, t.think, t.config); onProgress() }
                            }, Modifier.weight(1f)) { Text("从头重做", fontSize = 12.sp) }
                            TextButton(onClick = {
                                Producer.clearProgress(c, t.bookId, t.direct, t.think, t.only)
                            }, Modifier.weight(1f)) { Text("放弃任务", fontSize = 12.sp, color = Warn) }
                        }
                    }
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
private fun AudioScreen(
    c: Context, refresh: Int,
    onLibrary: () -> Unit, onModels: () -> Unit
) {
    var localTick by remember { mutableStateOf(0) }
    val items = remember(refresh, localTick) { AudioLibrary.list(c) }
    var exportId by remember { mutableStateOf<String?>(null) }
    // R80 · 详细播放控制默认收起：拖动 seek / ±15s / 上下条 / 倍速 / 关闭播放器
    // 这 5 项跨页悬浮条里没有，收起来但不删；曲名与状态归悬浮条，本页不再重复一张播放卡。
    var controlsOpen by remember { mutableStateOf(false) }
    // R18 · 播放器不再手搓：PlayQueue 由三份现成源码改写（ReaderVoice CachedAudioPlayer
    // / AlexExoPlayerMP3 PlayerController / ensemble-reads PlaybackController）。
    // 音频页与「分段看台」共用一个实例，切页不停播、不会两套 MediaPlayer 抢焦点。
    val pq by PlayQueue.state.collectAsState()
    LaunchedEffect(items) {
        PlayQueue.setTracks(items.map {
            Track(it.file, "${it.title} · ${it.scope}",
                id = "audio:${it.id}:${it.file.absolutePath}")
        })
    }
    // R79 · tick 统一由跨页常驻的 MiniPlayBar 驱动；本页只订阅 PlayQueue.state，
    // 不再和悬浮条同时驱动同一单例（播放时原本 500ms + 1000ms 双份 tick）。
    val isPlaying = pq.isPlaying
    val posMs = pq.positionMs
    val durMs = pq.durationMs
    // R103 · 当前曲目按稳定 id 认；pq.index 是「队列里第几条」，切页换队列后会串。
    val current = items.firstOrNull {
        val tid = "audio:${it.id}:${it.file.absolutePath}"
        pq.trackId.isNotEmpty() && pq.trackId == tid
    }

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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {

        // R80 · 汇总行兼播放控制入口：曲名与播放状态归跨页悬浮条，本页不再重复一张播放卡。
        if (items.isNotEmpty()) {
            item(key = "audio_summary") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val totalMs = items.sumOf { AudioLibrary.durationMsOfBytes(it.bytes) }
                    Text("共 ${items.size} 个音频 · 合计 ${fmtTime(totalMs)}",
                        fontSize = 11.sp, color = Sub, modifier = Modifier.weight(1f))
                    if (current != null) {
                        TextButton(onClick = { controlsOpen = !controlsOpen }) {
                            Text(if (controlsOpen) "收起 ⌃" else "播放控制 ›",
                                fontSize = 11.5.sp, color = Primary)
                        }
                    }
                }
            }

            // R80 · 详细控制默认收起；展开后拖动 seek / ±15s / 上下条 / 倍速 / 关闭一个不丢。
            if (current != null && controlsOpen) {
                item(key = "now_playing") {
                    CardBox(Modifier.padding(bottom = 10.dp)) {
                        Text(if (pq.ended) "已播完" else if (isPlaying) "播放中" else "已暂停",
                            fontSize = 11.5.sp, color = Primary)
                        Spacer(Modifier.height(4.dp))
                        Text("${current.title} · ${current.scope}", fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2)
                        Text("${current.mode} · ${fmtTime(AudioLibrary.durationMsOfBytes(current.bytes))}",
                            fontSize = 11.5.sp, color = Sub)
                        Spacer(Modifier.height(8.dp))
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
            }
        }

        if (items.isEmpty()) {
            item(key = "audio_empty") {
                CardBox {
                    Text("还没有音频", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("在书架导入一本书，选中章节合成后，成品会出现在这里。",
                        fontSize = 11.sp, color = Sub)
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextButton(onClick = onLibrary) {
                            Text("去书架 ›", fontSize = 11.5.sp, color = Primary)
                        }
                        TextButton(onClick = onModels) {
                            Text("去看模型 ›", fontSize = 11.5.sp, color = Primary)
                        }
                    }
                }
            }
        } else {
            itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                val on = pq.trackId.isNotEmpty() &&
                    pq.trackId == "audio:${item.id}:${item.file.absolutePath}"
                CardBox(Modifier.padding(bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${item.title} · ${item.scope}", fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp, maxLines = 2)
                            Text("${item.mode} · ${fmtTime(AudioLibrary.durationMsOfBytes(item.bytes))} · %.1f MB"
                                .format(item.bytes / 1024f / 1024f), fontSize = 11.5.sp, color = Sub)
                        }
                        if (on) Text("●", color = Primary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(9.dp))
                    // R80 · 主操作只留一个实心块；保存 / 删除降为小文字，导出逻辑一行不动。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            if (!on || pq.ended) PlayQueue.playFrom(idx) else PlayQueue.toggle()
                        }) {
                            Text(if (on && isPlaying) "暂停" else if (on) "继续" else "试听", fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            exportId = item.id
                            export.launch("${item.title}_${item.scope}.wav")
                        }) { Text("保存到手机", fontSize = 11.sp, color = Sub) }
                        TextButton(onClick = {
                            if (on) release()
                            AudioLibrary.remove(c, item.id); localTick++
                        }) { Text("删除", fontSize = 11.sp, color = Warn) }
                    }
                }
            }
        }
    }
}


// ── ⑤ 设置（只管软件） ──
/** R25 · 历史分组小标题组件，R54 起不再调用，保留定义避免动别处引用。 */
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

/** R54 · 细分割线分区：展开态 rememberSaveable 跨旋转保留，不加 Prefs 键、不引新依赖。 */
@Composable
private fun SettingsSection(
    title: String, badge: String, open: Boolean, onToggle: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { onToggle(!open) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Text(badge, fontSize = 10.sp, color = Sub,
                modifier = Modifier.padding(end = 8.dp))
            Text(if (open) "收起 ⌃" else "展开 ›", fontSize = 11.5.sp, color = Sub)
        }
        if (open) {
            Spacer(Modifier.height(7.dp))
            content()
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** R54 · 开关右侧补「开 / 关」文字，不只靠颜色表状态（v7 评审 15）。 */
@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (checked) "开" else "关", fontSize = 11.5.sp,
            color = if (checked) Primary else Sub)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** P0 · 只读信息行：徽标区分「现有 / 待新增 / 只读 / 需确认」，不画假开关。 */
@Composable
private fun SettingInfoRow(label: String, badge: String, value: String, note: String = "",
                           onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (note.isNotBlank()) Text(note, fontSize = 10.5.sp, color = Sub)
        }
        Text(badge, fontSize = 10.sp, color = Sub,
            modifier = Modifier.padding(end = 8.dp))
        Text(value, fontSize = 11.5.sp, color = Primary,
            modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    }
}

/**
 * R66 · 设置页只管软件：外观、本机数据、后台与关于；模型参数不再有独立页。
 * 三套作用域守恒：设置页拨动即写 Prefs（长期默认），无草稿、无差异、无提交按钮；
 * 调音台是草稿 → 唯一 commitAll()，制作准备页是「仅本次」。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun SettingsScreen(c: Context, refresh: Int, onModels: () -> Unit,
                           onLibrary: () -> Unit,
                           onMixer: () -> Unit) {
    // R54 · onLibrary / onMixer 保留签名但零调用：抽屉已有独立目的地，
    // 设置页不再画第二遍跳转卡。
    val missing = remember(refresh) { ModelStore.missing(c) }
    val modeCount = remember(refresh) { ModelStore.installedModes(c).size }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    var dataOpen by rememberSaveable { mutableStateOf(true) }
    var backOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(true) }
    // R73 · 主题 / 字号拨动即写 Prefs；诊断二级折叠默认收起。
    var themeMode by remember { mutableStateOf(Prefs.themeMode(c)) }
    var fontScale by remember { mutableStateOf(Prefs.readerFontScale(c)) }
    var diagOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (missing.isEmpty()) "模型已就绪 · $modeCount 个模式"
                 else "还差：" + missing.joinToString("、"),
                fontSize = 12.5.sp, color = if (missing.isEmpty()) Good else Warn,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onModels) { Text("查看 ›", fontSize = 12.sp) }
        }
        Text("软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME +
             " · 本机 Android " + android.os.Build.VERSION.RELEASE +
             " / " + (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
            fontSize = 10.5.sp, color = Sub)

        SettingsSection("外观与显示", "软件", lookOpen, { lookOpen = it }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("主题", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("切换立即生效", fontSize = 10.5.sp, color = Sub)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, m) ->
                    TextButton(onClick = {
                        themeMode = m
                        Prefs.setThemeMode(c, m)
                        applyAppTheme(c, m)
                    }, modifier = Modifier.weight(1f).padding(horizontal = 2.dp)) {
                        Text(label, fontSize = 11.5.sp,
                            color = if (themeMode == m) Primary else Sub)
                    }
                }
            }
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("正文字号", fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("看书页长期保留", fontSize = 10.5.sp, color = Sub)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val v = (fontScale - 1f).coerceIn(12f, 24f)
                    fontScale = v
                    Prefs.setReaderFontScale(c, v)
                }) { Text("A-", fontSize = 12.sp) }
                Text(fontScale.toInt().toString(), fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 10.dp))
                TextButton(onClick = {
                    val v = (fontScale + 1f).coerceIn(12f, 24f)
                    fontScale = v
                    Prefs.setReaderFontScale(c, v)
                }) { Text("A+", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f))
                Text("预览：巷口那盏灯还亮着", fontSize = 11.sp, color = Sub)
            }
            SettingInfoRow("状态栏安全区", "现有", "已处理", "顶栏已与系统通知栏错开")
        }

        SettingsSection("本机数据", "本机", dataOpen, { dataOpen = it }) {
            ClearSegmentsRow(c)
            SettingsSection("诊断与试音", "按需", diagOpen, { diagOpen = it }) {
                DiagnosticsBlock(c, refresh)
                SoundProbeBlock(c, refresh)
            }
        }

        SettingsSection("后台与关于", "系统", backOpen, { backOpen = it }) {
            SettingInfoRow("电池优化", "现有", "去系统设置", "让制作时不被锁屏杀掉") {
                try {
                    c.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    EventLog.log(EventLog.Kind.PIPELINE, "BATTERY_SETTINGS_OPEN",
                        "用户主动打开系统电池优化设置")
                } catch (_: Throwable) {
                    Toast.makeText(c, "这台手机没提供该设置入口", Toast.LENGTH_SHORT).show()
                }
            }
            SettingInfoRow("前台服务", "只读",
                if (ProduceService.status().contains("运行")) "运行中" else "未运行", "只显示状态")
            SettingInfoRow("第三方许可", "只读", "MIT / Apache-2.0", "")
            SettingInfoRow("设备与引擎", "只读",
                android.os.Build.MODEL,
                if (NativeEngine.isAvailable()) NativeEngine.version() else "引擎未装载")
        }

    }
}

/** 只展示用户能核对的当前配置；日志导出与复制整块退场。 */
@Composable
private fun DiagnosticsBlock(c: Context, _refresh: Int) {
    Spacer(Modifier.height(10.dp))
    Text("当前配置", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    Text(Prefs.summary(c), fontSize = 11.5.sp, color = Sub)
}

/** 本机数据里的真清理入口：明确说清删什么、二次确认、结果可见。 */
@Composable
private fun ClearSegmentsRow(c: Context) {
    var confirmClear by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("清理分段音频", fontSize = 13.sp)
            Text("只删制作过程的分段 WAV，不碰原书、成品与模型",
                fontSize = 10.5.sp, color = Sub)
        }
        Text("清理 ›", fontSize = 11.5.sp, color = Primary,
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

/** P0 · 两段真合成试音搬进「本机数据」：能力不丢，不新增第五个分区。 */
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
                val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                if (h == 0L) throw IllegalStateException("声音模型打不开")
                try {
                    SynthInput.prepare(c, h)
                    val v = SynthInput.params(c, instruct, null)
                    val f = File(c.filesDir, outName)
                    // R38 · 目标语言已知时显式传 chinese，主链 / 两态试听 / 试音口径统一。
                    val code = NativeEngine.ttsSynthesize(h, text, v.instruct, v.speaker, "chinese",
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

    Spacer(Modifier.height(8.dp))
    Text("声音自检 · 真合成两句", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
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

// ── ⑤b 模型页（R84 · 1 共用底座 + 3 模式，各自单独导入） ──────────
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
                fontSize = 11.5.sp, color = if (allReady) Good else Warn)
        }
        Spacer(Modifier.height(6.dp))

        // 导入中 / 导入失败：两态沿用，不静默
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
                Text("重新下载再导入一次，或先重启 App。", fontSize = 11.5.sp, color = Sub)
            }
        }

        // ── 老大：共用底座，三模式共用，导入一次就够 ──
        CardBox(Modifier.padding(bottom = 10.dp)) {
            Text("共用底座 · 三个模式都要用", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("12Hz 码 ↔ 24kHz 波形，导入一次就够。", fontSize = 11.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            Text(ModelStore.TOKENIZER, fontSize = 10.sp, color = Sub,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            if (baseFile != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已导入 ✓ · %.2f GB".format(
                        baseFile.length() / 1024f / 1024f / 1024f),
                        fontSize = 11.5.sp, color = Good, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ModelStore.delete(c, baseFile); onChanged() }) {
                        Text("删除重试", fontSize = 10.5.sp, color = Sub)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("还缺这一份", fontSize = 11.5.sp, color = Warn,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { copyUrl(cnUrl(ModelStore.TOKENIZER)) }) {
                        Text("复制国内地址 ›", fontSize = 11.5.sp, color = Primary)
                    }
                    TextButton(onClick = { copyUrl(hfUrl(ModelStore.TOKENIZER)) }) {
                        Text("备用 ›", fontSize = 10.5.sp, color = Sub)
                    }
                }
            }
        }

        Text("模式主文件 · 装一份就能用", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        Text("App 不联网：复制地址 → 浏览器下 → 回来点导入。",
            fontSize = 10.5.sp, color = Sub)
        Spacer(Modifier.height(8.dp))

        ModelStore.Mode.values().forEach { mode ->
            val f = modeFiles[mode]
            val isCurrent = mode == current
            CardBox(Modifier.padding(bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.label + (if (isCurrent) " · 当前启用" else ""),
                            fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                            color = if (isCurrent) Primary else Color.Unspecified)
                        Text(mode.id, fontSize = 10.sp, color = Sub)
                    }
                    if (isCurrent) Text("✓", fontSize = 14.sp, color = Good)
                }
                Spacer(Modifier.height(4.dp))
                Text(mode.file, fontSize = 10.sp, color = Sub,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(3.dp))
                Text(mode.hint, fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(7.dp))
                if (f != null) {
                    Text("已导入 · %.2f GB".format(f.length() / 1024f / 1024f / 1024f),
                        fontSize = 11.sp, color = Good)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isCurrent) {
                            TextButton(onClick = {
                                if (!st.running) { Prefs.setModelMode(c, mode.id); onChanged() }
                            }, enabled = !st.running) {
                                Text("启用这个模式", fontSize = 11.5.sp, color = Primary)
                            }
                        } else {
                            Text("这是当前模式", fontSize = 11.sp, color = Sub)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { ModelStore.delete(c, f); onChanged() }) {
                            Text("删除重试", fontSize = 10.5.sp, color = Sub)
                        }
                    }
                    if (!isCurrent && st.running) {
                        Text("正在制作，这一本跑完之前不能切", fontSize = 10.5.sp, color = Warn)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("待导入", fontSize = 11.sp, color = Warn,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onPick, enabled = importing == null) {
                            Text("导入这份 ›", fontSize = 11.5.sp, color = Primary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { copyUrl(cnUrl(mode.file)) }) {
                            Text("复制国内地址 ›", fontSize = 11.sp, color = Primary)
                        }
                        TextButton(onClick = { copyUrl(hfUrl(mode.file)) }) {
                            Text("备用 ›", fontSize = 10.5.sp, color = Sub)
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
                    fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                if (current == ModelStore.Mode.VOICE_DESIGN) {
                    Button(onClick = onLibrary, Modifier.fillMaxWidth().height(48.dp)) {
                        Text("回书架，导入一本书", fontSize = 15.sp)
                    }
                } else {
                    // R84 自审：不能让用户带着「还不能合成」的模式回书架，给一键切回。
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
                Text("缺件就出不了声，先补齐上面高亮的那一份。", fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick, enabled = importing == null,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("导入模型文件", fontSize = 15.sp)
                }
            }
        }

        TextButton(onClick = onPick, enabled = importing == null) {
            Text("重新导入某个文件 ›", fontSize = 11.5.sp, color = Primary)
        }
        if (files.isNotEmpty()) {
            Text("已导入 ${files.size} 个文件", fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── ⑥ 调音台（R40 · P0 统一提交）：音频模型控制台 ─────────────────────
// 评审实查：旧版 5 个写入口（身份保存 / 九项各自提交 / 12 维直接覆盖 / 表演层长期默认 /
// 预设一键覆盖）各自握着生效权，界面没有「未保存」这一层。R40 收成：
//   active（Prefs 唯一真值源） ←→ draft（唯一编辑态） → 唯一出口 MixerCommit.commit()
// 不手搓依据见 docs/R39_调音台统一提交_联动表与分轮.md §6 与 core/MixerDraft.kt 头注。
// R98 · 调音台整页重写：旧 MixerScreen 与配套控件整段删除。
// R99 · 折叠分组整套退场，采样与试听常驻；三种模式并列、数值完整可见。

/**
 * R98 · 调音台整页重写（不是在旧形态上增删）。
 *
 * 事实边界（随包 app/src/main/cpp/qwen.h:285-301 + 官方 Qwen3-TTS 模型卡）：
 *   voice_design  instruct 必填 / speaker 拒 / ref_audio 拒
 *   custom_voice  speaker 必填 / instruct 可选（念法情绪）/ ref_audio 拒
 *   base          ref_audio + ref_text / instruct 与 speaker 都拒
 *   9 项采样三模式共用。
 *
 * 设计原则：
 *  1. 三种模式并列放在同一屏顶部，点即切换，不用去「模型」页绕一圈；
 *     每种模式的输入独立保存，切换互不覆盖。
 *  2. 数值不再塞进 40dp 的 OutlinedTextField（真机上只露半截字）。
 *     当前值用普通 Text 完整显示，点一下才弹正常高度的输入框；滑块只做粗调。
 *  3. 不摆设计注记、字段号、状态徽标、重复话术、两个整宽大按钮。
 *     R99 · 采样参数与试听常驻不折叠；顶栏只留返回 + 有改动时一枚「保存」。
 *  4. 全页仍只有一个出口 commit()，两态试听仍共用 PlayQueue 单实例。
 */


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

    var trialText by remember {
        mutableStateOf("雨下了一整夜，她终于忍不住笑出声，又轻轻叹了口气。")
    }
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

    /** 全页唯一生效动作。写完重读真值源，三种模式的输入一起落盘。 */
    fun commitAll() {
        val n = changes.size
        MixerCommit.commit(c, draft, n)
        active = MixerDraft.active(c)
        draft = active
        tick++
    }
    fun discardAll() { draft = active }

    /** 模式点即切；制作中禁切，避免一份任务跑出两种权重。 */
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
        PlayQueue.setTracks(listOf(Track(f, "调音台试听", id = "trial:${f.absolutePath}")))
        PlayQueue.playFrom(0)
    }

    /** 两态试听按传入的 src 完整组装，草稿与已生效各自独立，不写 Prefs。 */
    fun trial(label: String, src: MixerDraft, out: String) {
        busyTrial = true
        trialMsg = "合成中…"
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                if (!NativeEngine.isAvailable()) throw IllegalStateException("本地引擎没装载，请重启 App")
                ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }
                val tk = ModelStore.modeFile(c, mode) ?: throw IllegalStateException("缺这一份模式主文件")
                val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺共用底座")
                val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                if (h == 0L) throw IllegalStateException("声音模型打不开")
                try {
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
                    val f = File(c.filesDir, out)
                    val code = NativeEngine.ttsSynthesize(h, trialText, v.instruct, v.speaker,
                        "chinese", f.absolutePath, src.knobs)
                    if (code != 0 || !f.exists() || f.length() <= 44)
                        throw IllegalStateException("没生成声音，code=$code")
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL",
                        "来源=$label · mode=${mode.id} · chars=${trialText.length} · " +
                            "seed=${src.knobs.seed} · bytes=${f.length()}")
                    f.absolutePath
                } finally { NativeEngine.ttsFree(h) }
            }
            withContext(Dispatchers.Main) {
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
    }

    BackHandler(enabled = dirty) { showLeave = true }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // 顶栏：返回与标题同一行；有改动才出现差异 / 提交。
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
                    Text("保存", fontSize = 11.sp)
                }
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))

            // ── 三种模式并列 ──
            ModeTabs(mode, installed) { switchMode(it) }
            Spacer(Modifier.height(10.dp))
            if (mode.id !in installed) {
                Text("「${mode.label}」的主文件还没导入，到「模型」页导入后才能出声。",
                    fontSize = 10.5.sp, color = Warn)
                Spacer(Modifier.height(8.dp))
            }

            // ── 当前模式自己的输入区（三种各自独立，切换不丢） ──
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

            // R101 · 采样链对三种 model_type 都生效（qwen.h 303-320 无模式条件），
            // 所以功能不删、全局仍只存一份；但另两种模式的界面不铺 9 项数字，
            // 只留一行说明，把这一页让给音色编号 / 参考音频。要调切回描述造音色即可。
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
                    Text("全部恢复默认", fontSize = 10.5.sp, color = Sub)
                }
            } else {
                Text("采样参数沿用全局设置，在「描述造音色」里调",
                    fontSize = 10.5.sp, color = Sub)
            }
            Spacer(Modifier.height(14.dp))

            // ── 试听：常驻，不必先保存；两态收成一行，结果只留「已播放」 ──
            Text("试听", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.height(6.dp))
            val canTrial = missing.isEmpty() && !busyTrial
            OutlinedTextField(
                value = trialText, onValueChange = { trialText = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("试听文本", fontSize = 10.sp) },
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { trial("草稿", draft, "mixer_trial_draft.wav") },
                    enabled = canTrial) { Text("试听草稿", fontSize = 11.sp) }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { trial("已生效", active, "mixer_trial_active.wav") },
                    enabled = canTrial) { Text("试听已生效", fontSize = 11.sp) }
                Spacer(Modifier.weight(1f))
                if (busyTrial) Text("合成中…", fontSize = 10.5.sp, color = Sub)
            }
            if (trialMsg.isNotBlank()) {
                Text(trialMsg, fontSize = 10.5.sp,
                    color = if (trialMsg.startsWith("试听失败")) Warn else Sub)
            }
            if (missing.isNotEmpty()) {
                Text("还缺模型文件，去「模型」页导入。", fontSize = 10.5.sp, color = Warn)
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

/** 三个模式并排，点即切；没导入主文件的那张明确标出来，不假装能用。 */
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
                Modifier.weight(1f).height(76.dp)
                    .background(if (on) PrimarySoft else Card, RoundedCornerShape(10.dp))
                    .border(1.dp, if (on) Primary else Line, RoundedCornerShape(10.dp))
                    .clickable { onPick(m) }
                    .padding(horizontal = 9.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(m.label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    color = if (on) Primary else palette.text)
                Text(
                    when (m) {
                        ModelStore.Mode.VOICE_DESIGN -> "写一段话描述嗓子"
                        ModelStore.Mode.CUSTOM_VOICE -> "模型自带音色里挑"
                        ModelStore.Mode.BASE -> "一段人声当样本"
                    },
                    fontSize = 9.5.sp, color = Sub, maxLines = 2
                )
                if (m.id !in installed) Text("未导入主文件", fontSize = 9.sp, color = Warn)
            }
        }
    }
}

/** 描述造音色：只一个自由输入框，不再堆 12 维词条和身份说明。 */
@Composable
private fun VoiceDesignPane(draft: MixerDraft, onDraft: (MixerDraft) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("声音描述", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Spacer(Modifier.weight(1f))
        Text("清空", fontSize = 10.5.sp, color = Sub,
            modifier = Modifier.clickable { onDraft(draft.copy(voice = "")) })
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.voice, onValueChange = { onDraft(draft.copy(voice = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
        placeholder = { Text("例如：温和的中年女声，吐字清晰，语速偏慢", fontSize = 11.sp) },
        textStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    )
}

/** 预设音色：音色表 + 一句念法要求，两者独立保存。 */
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
                    val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                    if (h == 0L) throw IllegalStateException("模型打不开")
                    try { NativeEngine.ttsSpeakers(h) } finally { NativeEngine.ttsFree(h) }
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
            Text(if (speakerBusy) "正在读模型…" else "读取模型自带的音色表 ›", fontSize = 11.sp)
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            speakerList.forEach { name ->
                FilterChip(
                    selected = draft.speaker == name,
                    onClick = { onDraft(draft.copy(speaker = name)) },
                    label = { Text(name, fontSize = 10.5.sp) }
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // R99 · 单独一行「自定义」：音色编号来自模型元数据（固定，不能新增第 10 个），
    // 但 custom_voice 的 instruct 可选（qwen.h:285-291）。这行写自定义念法/情绪，
    // 与上面选中的音色一起送进引擎，不另建第二份生效态。
    Text("自定义", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.cvStyle, onValueChange = { onDraft(draft.copy(cvStyle = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("例如：语速放慢，句尾别上扬（叠加在所选音色上）", fontSize = 11.sp) },
        textStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    )
}

/** 参考音频克隆：一段样本 + 它的原文，拒一切文字描述。 */
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
        Text(refState, fontSize = 11.sp,
            color = if (VoiceRef.exists(ctx)) Good else Warn,
            modifier = Modifier.weight(1f))
        if (VoiceRef.exists(ctx)) {
            Text("删掉", fontSize = 10.5.sp, color = Warn,
                modifier = Modifier.clickable { onClear() }.padding(start = 8.dp))
        }
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onPick) { Text("导入一小段人声 ›", fontSize = 11.sp) }
    Text("一段 3~30 秒的人声录音（WAV 即可，App 自动转成 24kHz 单声道）", fontSize = 10.sp, color = Sub)
    Spacer(Modifier.height(12.dp))
    Text("这段录音念的是什么（可留空）", fontSize = 12.sp,
        fontWeight = FontWeight.Bold, color = palette.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft.refText, onValueChange = { onDraft(draft.copy(refText = it)) },
        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
        placeholder = { Text("填上原文克隆更准；留空就只按声音克隆", fontSize = 11.sp) },
        textStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    )
}

/**
 * R98 · 数值控件重写。
 * 旧版把值塞进 72×40dp 的 OutlinedTextField，真机上「0.8 / 50 / 1.05 / 42」
 * 只露上半截；滑块却占了大半行，比例失调。这里当前值用普通 Text 完整显示，
 * 点一下才弹正常高度的输入框；细滑块只做粗调，不再和数值框抢宽度。
 */
@Composable
private fun ParamLine(spec: ParamSpec, cur: String, onValue: (String) -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var buf by remember(cur) { mutableStateOf(cur) }
    val lo = (spec.min ?: 0.0).toFloat()
    val hi = (spec.max ?: if (spec.key == "seed") 2000.0 else 1000.0).toFloat()
    val sv = (cur.toFloatOrNull() ?: lo).coerceIn(lo, hi)
    val intLike = spec.key == "seed" || spec.key == "maxNewTokens" ||
        spec.key == "topK" || spec.key == "subTopK"

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, fontSize = 11.sp, color = palette.text,
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
                val s = if (intLike) v.toLong().toString()
                        else "%.2f".format(v).trimEnd('0').trimEnd('.')
                onValue(s)
            },
            valueRange = lo..hi,
            modifier = Modifier.fillMaxWidth().height(18.dp)
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
                    Text("范围 ${spec.rangeLabel} · 默认 ${spec.def}", fontSize = 10.5.sp, color = Sub)
                    if (err != null) Text(err, fontSize = 10.5.sp, color = Warn)
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
