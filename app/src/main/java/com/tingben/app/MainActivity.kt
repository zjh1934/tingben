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
import androidx.compose.foundation.lazy.LazyColumn
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
                    onChanged = { refresh++ })
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
                           onChanged: () -> Unit) {
    val books = remember(refresh) { BookStore.list(c) }
    val ready = remember(refresh) { ModelStore.ready(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    val lastReads = remember(refresh, books) { books.associate { it.id to ReadingState.last(c, it.id) } }
    val clipboard = LocalClipboardManager.current
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // U1 · 下拉刷新条抽到 AppChrome 壳层；手势阈值与旧实现一致
        RefreshStrip(
            onRefresh = onChanged,
            primary = Primary,
            primarySoft = PrimarySoft,
            sub = Sub
        )
        Spacer(Modifier.height(6.dp))
        // R78 · 缺件收成一行细条，不跟主操作抢位置
        if (!ready) {
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

        if (books.isEmpty()) {
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
        // R78 · 粘贴框只在点开时出现，不占首屏
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
        if (books.isNotEmpty()) {
            // R78 · 有书态：整卡可点，补上次读到的章
            Text("共 ${books.size} 本", fontSize = 11.5.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            books.forEach { b ->
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
            TextButton(onClick = { pasteOpen = !pasteOpen }) {
                Text(if (pasteOpen) "收起粘贴" else "粘贴文字建书 ›", fontSize = 12.sp, color = Primary)
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
                else if (hasCached) "继续制作"
                else if (directMode) "开始直接朗读"
                else "开始制作",
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
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onProgress) { Text("查看进度") }
    }
}

// ── ③ 进度 · R65 v12：空间重分配 + 看台「小块 → 模型原貌」 ──────────────
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
    var segSel by remember { mutableStateOf<Int?>(null) }
    var showCompare by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var lmOpen by remember { mutableStateOf(false) }
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

        // ② 五步横排：做过的下面 3dp 色块，正在做只加粗，没做的没有色块
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

        // ④ 分段看台：板块常驻不折叠，8 个一小块；点格子展开送进声音模型的正文
        Spacer(Modifier.height(10.dp))
        CardBox {
            Text("分段看台 · ${segs.size} 段", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("绿=已出声 · 蓝=正在合成 · 灰=未开始 · 点格子看正文",
                fontSize = 10.sp, color = Sub)
            if (segs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("这次还没落盘分段记录；跑一次制作后这里才有点。", fontSize = 11.sp, color = Sub)
            } else {
                Spacer(Modifier.height(9.dp))
                segs.chunked(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { sg ->
                            val base = when {
                                sg.hasAudio -> Good
                                sg.wav != null -> Primary
                                else -> Line
                            }
                            Box(
                                Modifier.size(30.dp)
                                    .background(
                                        if (segSel == sg.index) PrimarySoft else base.copy(alpha = 0.18f),
                                        RoundedCornerShape(8.dp))
                                    .clickable { segSel = if (segSel == sg.index) null else sg.index },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${sg.index + 1}", fontSize = 10.sp,
                                    color = if (segSel == sg.index) Primary else base)
                                if (sg.hasAudio)
                                    Text("▶", fontSize = 6.sp, color = Good,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
                segSel?.let { sel ->
                    val sg = segs.find { it.index == sel }
                    if (sg != null) {
                        val label = "第 ${sg.index + 1} 段"
                        val onThis = pqSeg.label == label
                        val playing = onThis && pqSeg.isPlaying
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${label} · 第 ${sg.block + 1} 块 · 送进声音模型的正文",
                                fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            if (sg.wav != null) {
                                Box(Modifier.size(32.dp)
                                    .background(if (playing) Primary else PrimarySoft,
                                        RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (playing) PlayQueue.pause()
                                        else if (onThis) PlayQueue.resume()
                                        else {
                                            PlayQueue.setTracks(listOf(Track(sg.wav!!, label)))
                                            PlayQueue.playFrom(0)
                                        }
                                    }, contentAlignment = Alignment.Center) {
                                    Text(if (playing) "⏸" else "▶", fontSize = 13.sp,
                                        color = if (playing) Card else Primary)
                                }
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(if (sg.text.isBlank()) "（这一段没有落盘文字）" else sg.text,
                            fontSize = 11.sp, color = palette.text, lineHeight = 17.sp)
                    }
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
    // R79 · 旧版 LaunchedEffect(Unit) 无条件每 2 秒整页重组 + 三次磁盘读，
    // 哪怕「还没有任务」也照跑。改成只在制作中轮询，静止页零定时器。
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(st.running) {
        if (!st.running) return@LaunchedEffect
        while (true) { delay(2000); loadTick++ }
    }
    val saved = remember(refresh, loadTick) { TaskResume.load(c) }
    val t = saved
    val real = remember(t, refresh) { t?.let { TaskResume.realProgress(c, it) } ?: 0 }
    val bookExists = remember(t, refresh) {
        t?.let { BookStore.list(c).any { b -> b.id == it.bookId } } ?: false
    }
    val modeText = if (t == null) "" else "直接朗读"
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
                                if (b != null) { Producer.start(c, b, t.only, true, t.think, t.config); onProgress() }
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
        PlayQueue.setTracks(items.map { Track(it.file, "${it.title} · ${it.scope}") })
    }
    // R79 · tick 统一由跨页常驻的 MiniPlayBar 驱动；本页只订阅 PlayQueue.state，
    // 不再和悬浮条同时驱动同一单例（播放时原本 500ms + 1000ms 双份 tick）。
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
                val on = idx == pq.index
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
private fun SettingInfoRow(label: String, badge: String, value: String, note: String = "") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (note.isNotBlank()) Text(note, fontSize = 10.5.sp, color = Sub)
        }
        Text(badge, fontSize = 10.sp, color = Sub,
            modifier = Modifier.padding(end = 8.dp))
        Text(value, fontSize = 11.5.sp, color = Primary)
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
            SettingInfoRow("清理音频缓存", "需确认", "二次确认后清理",
                "只删制作过程的分段 WAV，不碰原书与模型")
            SettingsSection("诊断与试音", "按需", diagOpen, { diagOpen = it }) {
                DiagnosticsBlock(c, refresh)
                SoundProbeBlock(c, refresh)
            }
        }

        SettingsSection("后台与关于", "系统", backOpen, { backOpen = it }) {
            SettingInfoRow("电池优化", "现有", "去系统设置", "让制作时不被锁屏杀掉")
            SettingInfoRow("前台服务", "只读",
                if (ProduceService.status().contains("运行")) "运行中" else "未运行", "只显示状态")
            SettingInfoRow("第三方许可", "现有", "查看 ›", "MIT / Apache-2.0 清单")
            SettingInfoRow("设备与引擎", "只读",
                android.os.Build.MODEL,
                if (NativeEngine.isAvailable()) NativeEngine.version() else "引擎未装载")
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

    }
}

/** R68 · P1b：旧审计与原始产出退场，诊断区只留软件自身状态与事件日志。 */
@Composable
private fun DiagnosticsBlock(c: Context, _refresh: Int) {
    val clipboard = LocalClipboardManager.current
    var confirmClear by remember { mutableStateOf(false) }

    fun buildDiag(): String = buildString {
        append("听本 ").append(com.tingben.app.BuildConfig.VERSION_NAME).append('\n')
        append("设备 ").append(android.os.Build.MODEL).append(" / Android ")
            .append(android.os.Build.VERSION.RELEASE).append(" / ")
            .append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "").append('\n')
        append("引擎 ").append(if (NativeEngine.isAvailable()) NativeEngine.version() else "未装载").append('\n')
        append("前台服务 ").append(ProduceService.status()).append('\n')
        append("设置 ").append(Prefs.summary(c)).append('\n')
        append("模型 ").append(ModelStore.files(c).joinToString("、") { it.name }).append('\n')
        append("引擎 ").append(if (NativeEngine.isAvailable()) "已装载" else "未装载").append('\n')
        val crash = EventLog.lastCrash()
        if (crash.isNotBlank()) append("\n=== 最近一次崩溃 ===\n").append(crash).append('\n')
        append("\n=== 最近事件（不含正文）===\n")
        append(EventLog.read().lines().takeLast(60).joinToString("\n"))
    }

    Spacer(Modifier.height(10.dp))
    Text("静态快照", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
    Text(Prefs.summary(c), fontSize = 11.5.sp, color = Sub)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = {
        clipboard.setText(AnnotatedString(buildDiag()))
        Toast.makeText(c, "已复制诊断摘要，直接贴给我即可", Toast.LENGTH_SHORT).show()
    }, Modifier.fillMaxWidth()) { Text("复制诊断信息（摘要）") }
    TextButton(onClick = { confirmClear = true }, Modifier.fillMaxWidth()) {
        Text("清理制作过程的分段音频", fontSize = 11.5.sp, color = Warn)
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
                val tk = ModelStore.talker(c) ?: throw IllegalStateException("缺声音主文件")
                val tok = ModelStore.tokenizer(c) ?: throw IllegalStateException("缺声音配套文件")
                val h = NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
                if (h == 0L) throw IllegalStateException("声音模型打不开")
                try {
                    val f = File(c.filesDir, outName)
                    // R38 · 目标语言已知时显式传 chinese，主链 / 两态试听 / 试音口径统一。
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
                else "这个模式的合成界面还没做完，先切回「描述造音色」再制作。"),
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
// 预设一键覆盖）各自握着生效权，界面没有「待提交」这一层。R40 收成：
//   active（Prefs 唯一真值源） ←→ draft（唯一编辑态） → 唯一出口 MixerCommit.commit()
// 不手搓依据见 docs/R39_调音台统一提交_联动表与分轮.md §6 与 core/MixerDraft.kt 头注。
// 折叠分组沿用 R36 的 MixerSection（rememberSaveable 跨旋转保留展开态）。
@Composable
private fun MixerSection(
    title: String,
    key: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    badge: String = "",
    anchor: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // R42 · 细分割线 + 留白替代嵌套阴影；分区标题与展开动作改中性色。
    val mod = Modifier.padding(bottom = 4.dp).then(
        if (anchor != null) Modifier.onGloballyPositioned { anchor(it.positionInParent().y.toInt()) }
        else Modifier
    )
    Column(mod.fillMaxWidth()) {
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { onToggle(!open) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = Sub,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (open) "收起" else "展开", fontSize = 11.5.sp, color = Sub)
        }
        if (open) {
            Spacer(Modifier.height(7.dp))
            content()
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** R42 · help 说明：默认只留一个图标 + 标题，点开才看正文（评审 16）。 */
@Composable
private fun HelpNote(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically) {
        Text("ⓘ", fontSize = 12.sp, color = Sub)
        Spacer(Modifier.width(5.dp))
        Text(title, fontSize = 10.sp, color = Sub, modifier = Modifier.weight(1f))
        Text(if (open) "收起" else "查看", fontSize = 10.sp, color = Sub)
    }
    if (open) {
        Text(body, fontSize = 10.sp, color = Sub,
            modifier = Modifier.padding(start = 17.dp, bottom = 3.dp))
    }
}

/**
 * R42 · 标签在上、选项 chip 自动换行：官方 FlowRow + FilterChip，不是手搓的横向 Row。
 * 375px 下长选项（如「高（明显气声）」「略带江浙口音」）整块换到下一行，不挤成竖排碎字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    hint: String,
    options: List<String>,
    current: String,
    allowCustom: Boolean = false,
    customValue: String = "",
    onCustom: (String) -> Unit = {},
    onPick: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        HelpNote("可选值", hint)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { o ->
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    label = { Text(o, fontSize = 10.5.sp) }
                )
            }
        }
        if (allowCustom) {
            // R50 · 真机评审：自定义框「小一点就可以」。去 label、压到 140×40dp，
            // 不再占满整行；输入仍直接进 onCustom，由调用方写 draft（不摆假开关）。
            OutlinedTextField(
                value = customValue, onValueChange = onCustom,
                // BOM 2024.12.01 的 M3 OutlinedTextField 没有 contentPadding 参数（run 59 真编译实测）；
                // 压高度靠 Modifier.height 强约束，不引 BasicTextField、不加依赖。
                modifier = Modifier.width(140.dp).height(40.dp), singleLine = true,
                placeholder = { Text("自己填", fontSize = 10.sp, color = Sub) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
        }
        HorizontalDivider(color = Line)
    }
}

/** R42 · 375px 窄屏证据：只画两条最长选项的 chip 行，不引新依赖。 */
@Preview(widthDp = 375, showBackground = true)
@Composable
private fun ChipRowNarrowPreview() {
    Column(Modifier.padding(8.dp)) {
        ChipRow("气息", "低 / 中 / 高（气声）",
            VoiceDimension.BREATH.options, "不指定") {}
        ChipRow("口音", "普通话 / 方言 / 无",
            VoiceDimension.ACCENT.options, "不指定") {}
    }
}

/** R42 · 开关统一带「开 / 关」文字与 info（评审 15）。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, timing: String,
                      onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp)
            HelpNote("什么时候生效", timing)
        }
        Text(if (checked) "开" else "关", fontSize = 11.sp,
            color = if (checked) Good else Sub)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * R42 · 开发者资料：默认收起。字段名 / 文档坐标 / 指纹只在展开态出现（评审 13）。
 * 内容全是只读展示，不写任何生效态，不影响 MixerDraft / commit。
 */
@Composable
private fun AdvancedDrawer(rows: List<MixerRow>, stamp: String) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
    Row(Modifier.fillMaxWidth().clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically) {
        Text("高级 · 开发者资料", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text(if (open) "收起" else "展开", fontSize = 11.sp, color = Sub)
    }
    if (open) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            var last = ""
            Mixer.coverage().forEach { (g, item, kind) ->
                if (g != last) {
                    last = g
                    Text(g, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = Sub, modifier = Modifier.padding(top = 5.dp))
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
            Text("日志核对：TTS_PARAMS（界面传出） / TTS_PARAMS_APPLIED（引擎实收）",
                fontSize = 10.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            Text("官方资料（按名称自行查；App 无联网权限，不摆假链接）",
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            ParamCatalog.DOCS.forEach { (name, where) ->
                Spacer(Modifier.height(4.dp))
                Text(name, fontSize = 10.5.sp, color = Sub)
                Text(where, fontSize = 10.sp, color = Sub)
            }
            Spacer(Modifier.height(8.dp))
            rows.filter { it.group == "边界" }.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(r.label, fontSize = 10.5.sp, color = Sub, modifier = Modifier.weight(1f))
                    Text(r.value, fontSize = 10.5.sp, modifier = Modifier.weight(1.2f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("分区元信息：采样 9 项可编辑 / 表演层与开关属上层 / 触达矩阵与官方索引只读",
                fontSize = 10.sp, color = Sub)
            Spacer(Modifier.height(6.dp))
        }
    }
}
// R42_ADVANCED_END

/** R40 · 时机提示统一组件（评审第 17 条：别每处各写一种说法）。 */
@Composable
private fun TimingHint(timing: String) {
    Text("改动先进入待提交，按顶部「提交变更」写入；$timing",
        fontSize = 10.sp, color = Sub)
}

/**
 * R49 · v5 第 2 条：锚点自动换行 + 当前区高亮。
 * R48 真机截图里 7 个标签被横向裁成「固…」；改用官方 FlowRow + FilterChip，
 * 不再用 horizontalScroll 藏选项。当前区由 MixerScreen 传入，不从滚动位置猜。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnchorBar(
    items: List<Pair<String, String>>,
    current: String,
    onJump: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { (key, label) ->
            FilterChip(
                selected = key == current,
                onClick = { onJump(key) },
                label = { Text(label, fontSize = 11.sp) }
            )
        }
    }
}

/**
 * R49 · v5 第 4 条：一行一项（中文名 · 滑块 · 小数字框 · ⟳）。
 * 常用值、人话说明、官方字段收进该行展开的 ⓘ；数字框就是精确值输入，
 * 不再画第二个框。R47 的滑块双模逐行保留：滑块与数字框共用 onInput，
 * 范围只认 ParamSpec 唯一来源。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamRow(
    spec: ParamSpec,
    cur: String,
    input: String,
    err: String,
    onInput: (String) -> Unit,
    onDone: () -> Unit,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onRoll: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        // R50 · 真机评审第 1 条：旧版带 label 的 OutlinedTextField 最小高 56dp 把整行
        // 撑到约 180px、滑块只剩一小段。改成「名字 · 滑块 · 小数字框 · ⟳ · ▾」同一中线，
        // 数字框无 label、72×38dp；「任意合法值 / 范围」移到展开的 ⓘ，不占行高。
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()) {
            Text(spec.label, fontSize = 10.5.sp, modifier = Modifier.width(78.dp))
            // R47 · v4 预览 ⑥ 落地：滑块与数字框是同一个值，改动只进草稿。
            // 滑块只覆盖常用区间（seed 无上界时取 2000）；任意合法值仍可直接在框里填。
            val sliderLo = (spec.min ?: 0.0).toFloat()
            val sliderHi = (spec.max ?: if (spec.key == "seed") 2000.0 else 1000.0).toFloat()
            val intLike = spec.key == "seed" || spec.key == "maxNewTokens" ||
                spec.key == "topK" || spec.key == "subTopK"
            val sliderVal = (input.toFloatOrNull() ?: cur.toFloatOrNull() ?: sliderLo)
                .coerceIn(sliderLo, sliderHi)
            Slider(
                value = sliderVal,
                onValueChange = { v ->
                    val s = if (intLike) v.toLong().toString()
                    else "%.2f".format(v).trimEnd('0').trimEnd('.')
                    onInput(s)
                },
                valueRange = sliderLo..sliderHi,
                modifier = Modifier.weight(1f).height(28.dp)
            )
            Spacer(Modifier.width(2.dp))
            OutlinedTextField(
                value = input, onValueChange = onInput,
                modifier = Modifier.width(72.dp).height(38.dp), singleLine = true,
                isError = err.isNotBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
            TextButton(onClick = onReset, modifier = Modifier.width(24.dp)) {
                Text("⟳", fontSize = 12.sp, color = Sub)
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.width(22.dp)) {
                Text(if (expanded) "▴" else "▾", fontSize = 11.sp, color = Sub)
            }
        }
        if (err.isNotBlank()) Text(err, fontSize = 10.sp, color = Warn)
        if (expanded) {
            HelpNote("参数说明",
                "框里可填任意合法值 · ${spec.hint} · 官方默认 ${spec.def} · 字段 ${spec.official} · 范围 ${spec.rangeLabel}${spec.unit}")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                spec.options.forEach { o ->
                    val target = if (o == "随机") "-1" else o
                    val on = cur == target || (o == "随机" && (cur.toLongOrNull() ?: 0L) < 0L)
                    FilterChip(
                        selected = on,
                        onClick = { onPick(target) },
                        label = { Text(o, fontSize = 10.sp) }
                    )
                }
            }
            onRoll?.let { roll ->
                TextButton(onClick = roll) {
                    Text("⟳ 换一个", fontSize = 10.5.sp, color = Primary)
                }
            }
            TextButton(onClick = onReset) { Text("⟳ 单项恢复默认", fontSize = 10.5.sp, color = Sub) }
        }
        HorizontalDivider(color = Line)
    }
}

/** R41 · 共用音色选择器：搜索 + 最近使用 + 网格，身份生成器不再另写一套控件。 */
@Composable
private fun VoicePicker(
    query: String,
    onQuery: (String) -> Unit,
    recent: List<String>,
    selectedId: String,
    onPick: (VoicePreset) -> Unit
) {
    OutlinedTextField(
        value = query, onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("搜索音色（编号 / 名称 / 特征）", fontSize = 10.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
    )
    if (recent.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("最近使用", fontSize = 10.sp, color = Sub)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            recent.forEach { id ->
                val p = VoicePresets.ALL.firstOrNull { it.id == id } ?: return@forEach
                TextButton(onClick = { onPick(p) }) {
                    Text(p.name, fontSize = 10.5.sp,
                        color = if (p.id == selectedId) Primary else Sub)
                }
            }
        }
    }
    val list = VoicePresets.ALL.filter {
        query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) ||
            it.chips().contains(query, true)
    }
    list.forEach { p ->
        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${p.id} ${p.name}", fontSize = 11.5.sp,
                    fontWeight = if (p.id == selectedId) FontWeight.Bold else FontWeight.Normal)
                Text(p.chips(), fontSize = 10.sp, color = Sub)
            }
            Text(if (p.id == selectedId) "已选" else "选用", fontSize = 10.5.sp)
        }
    }
    if (list.isEmpty()) Text("没有匹配的音色", fontSize = 10.5.sp, color = Warn)
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
    // R49 · v5：锚点当前区、身份全文/编辑两个展开态（纯界面态，不写生效态）
    var activeAnchor by remember { mutableStateOf("identity") }
    var showVoiceFull by remember { mutableStateOf(false) }
    var showVoiceEdit by remember { mutableStateOf(true) }
    var showLeave by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    // R50 · 表演层 5 枚自定义小框的文本态（不是生效态）；数值两项共用这张表，
    // 但 packExtra 只认「朗读/语速/情绪」，数值项各写 gapMs / gateRetries。
    fun seedPerfCustom(p: ProduceConfig): Map<String, String> =
        ProduceConfig.unpackExtra(p.extraInstruct) +
            ("静音" to if (p.gapMs in ProduceConfig.GAP_OPTIONS) "" else p.gapMs.toString()) +
            ("重做" to if (p.gateRetries in ProduceConfig.RETRY_OPTIONS) "" else p.gateRetries.toString())
    var perfCustom by remember { mutableStateOf(seedPerfCustom(active.perf)) }
    fun syncPerfCustom(p: ProduceConfig) { perfCustom = seedPerfCustom(p) }
    fun perfText(k: String): String = perfCustom[k] ?: ""
    /** 自定义框 → draft 的唯一写入口；非法数值只留文本、不改生效态，避免输入抖动。 */
    fun setPerfCustom(k: String, v: String) {
        val next = if (v.isBlank()) perfCustom - k else perfCustom + (k to v.trim())
        perfCustom = next
        when (k) {
            "静音" -> v.filter { it.isDigit() }.take(4).toIntOrNull()
                ?.takeIf { it in ProduceConfig.GAP_RANGE }
                ?.let { draft = draft.copy(perf = draft.perf.copy(gapMs = it)) }
            "重做" -> v.filter { it.isDigit() }.take(2).toIntOrNull()
                ?.takeIf { it in ProduceConfig.RETRY_RANGE }
                ?.let { draft = draft.copy(perf = draft.perf.copy(gateRetries = it)) }
            else -> draft = draft.copy(
                perf = draft.perf.copy(extraInstruct = ProduceConfig.packExtra(next)))
        }
    }
    /** 选了预设 chip 就撤掉该项自定义；短语项要同步重算 extraInstruct，数值项只清文本。 */
    fun clearPerfCustom(k: String) {
        val next = perfCustom - k
        perfCustom = next
        if (k != "静音" && k != "重做") {
            draft = draft.copy(
                perf = draft.perf.copy(extraInstruct = ProduceConfig.packExtra(next)))
        }
    }
    var paramDrafts by remember { mutableStateOf(emptyMap<String, String>()) }
    var paramErr by remember { mutableStateOf(emptyMap<String, String>()) }
    var dimEdited by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    // R41 · 锚点 / 展开态 / 音色选择器 / 预设三弹层（全部是界面态，不写生效态）
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<String, Int>() }
    var openMap by remember { mutableStateOf(Prefs.mixerOpenSections(c)) }
    var voiceQuery by remember { mutableStateOf("") }
    var recentVoices by remember { mutableStateOf(Prefs.recentVoices(c)) }
    var showPresetDiff by remember { mutableStateOf<MixerPreset?>(null) }
    var renamePresetId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var confirmDeletePresetId by remember { mutableStateOf("") }
    fun toggleSection(k: String, v: Boolean) {
        val next = if (v) openMap + k else openMap - k
        openMap = next
        Prefs.setMixerOpenSections(c, next)
    }
    // R50 · 真机第 3 问：旧版 activeAnchor 只在点 chip 时赋值，滚动没有任何回写，
    // 所以「往下扒标签页不跟着变」。这里补反向联动；点击跳转期间锁住，避免动画抖动。
    var jumpLock by remember { mutableStateOf(false) }
    val anchorShown = listOf("identity", "sampling", "perf", "presets", "fixedVoice", "mapping")
    fun jumpTo(k: String) {
        activeAnchor = k
        // R49 · v5 第 6 条：锚点点击先展开目标折叠区，再滚过去
        if (k !in openMap) toggleSection(k, true)
        scope.launch {
            jumpLock = true
            try { scroll.animateScrollTo(anchors[k] ?: 0) }
            finally { jumpLock = false }
        }
    }
    LaunchedEffect(Unit) {
        // R51 · 只把 scroll.value 放进流；旧版每帧 anchors.toMap() 复制整张表，
        // 滚动一帧分配一次 map。锚点表改在 collect 里按当前值读取，语义不变。
        snapshotFlow { scroll.value }.collect { v ->
            if (jumpLock) return@collect
            val top = v + 24
            var best = "identity"
            val pos = anchors.toMap()
            for ((k, y) in pos.entries.sortedBy { it.value }) {
                if (y > top) break
                if (k in anchorShown) best = k
                // 试听 / 开关 / 诊断不在锚点栏里：归并到前一个可见锚点，不出现全不高亮。
            }
            if (best != activeAnchor) activeAnchor = best
        }
    }

    // R41a · 试听只看两态：草稿（未提交）/ 已生效。不再有 A/B、预设对比、合成/播放两步。
    var trialText by remember {
        mutableStateOf("雨下了一整夜，她终于忍不住笑出声，又轻轻叹了口气。")
    }
    var busyDraftTrial by remember { mutableStateOf(false) }
    var busyActiveTrial by remember { mutableStateOf(false) }
    var trialMsg by remember { mutableStateOf("") }

    /** R49 · 身份描述逐维差异：按「；」拆段比不同项，只给界面高亮，不参与提交判定。 */
    fun voiceFieldDiff(old: String, new: String): List<Pair<String, String>> {
        fun parts(x: String) = x.split("；", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val a = parts(old)
        val b = parts(new)
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: "（空）"
            val y = b.getOrNull(i) ?: "（空）"
            if (x != y) out.add(x to y)
        }
        return out
    }

    /** 全页唯一生效动作。写完重读真值源，保留生成器工作区（dims/dimText）。 */
    fun commitAll() {
        val n = changes.size
        val after = MixerCommit.commit(c, draft, n)
        active = MixerDraft.active(c)
        draft = active.copy(dims = draft.dims, dimText = draft.dimText)
        syncPerfCustom(active.perf)   // R50 · 提交后小框回到已生效值，不留编辑态
        paramDrafts = emptyMap(); paramErr = emptyMap()
        tick++
        msg = "已提交 $n 项变更"
    }
    fun discardAll() {
        draft = active.copy(dims = draft.dims, dimText = draft.dimText)
        syncPerfCustom(active.perf)   // R50 · 丢弃待提交，小框一起复位
        paramDrafts = emptyMap(); paramErr = emptyMap()
        msg = "已丢弃待提交变更"
    }
    /** R41 · 预设 → draft 只此一份构造，diff 预览与实际载入共用，不复制第二份拼串。 */
    fun buildPresetDraft(p: MixerPreset): MixerDraft = draft.copy(
        knobs = p.knobs,
        perf = p.perf,
        voice = if (p.voice.isBlank()) draft.voice else p.voice,
        dims = p.fields,
        dimText = p.fields.let { f ->
            VoicePreset("edit", "自定义", f.filter { it.value != "不指定" }, "").render()
        }
    )
    /** 载入预设只写 draft，绝不越过顶部状态条直接生效。 */
    fun loadPreset(p: MixerPreset) {
        draft = buildPresetDraft(p)
        syncPerfCustom(draft.perf)   // R50 · 预设里的自定义语气也要回填到三个小框
        dimEdited = true
        paramDrafts = emptyMap(); paramErr = emptyMap()
        Mixer.logPreset("载入预设为待提交", p)
        msg = "已把「${p.name}」载入待提交，按顶部「提交变更」生效"
    }
    /** 复用 R18 的全局播放内核，不在这里手搓第二套 MediaPlayer。
     *  R41a：必须定义在 trial 之前 —— Kotlin 局部函数不允许前向引用（CI run 50 实测）。 */
    fun play(path: String) {
        val f = File(path)
        if (!f.isFile) { Toast.makeText(c, "播放失败", Toast.LENGTH_SHORT).show(); return }
        PlayQueue.setTracks(listOf(Track(f, "调音台试听")))
        PlayQueue.playFrom(0)
    }
    /**
     * R41a · 一步试听：合成完自动播放。整套配置来自传入的「已生效」或「草稿」，
     * 身份层 / 风格层 / 采样 9 项全跟着走，不写 Prefs、不进 draft、不改 voiceStamp。
     * instruct 复用主链同一个 VoiceProfile.build()，不复制第二份拼串逻辑。
     */
    fun trial(label: String, src: MixerDraft, out: String, busy: (Boolean) -> Unit) {
        busy(true)
        val tag = if (label == "未提交参数" && !dirty) "未提交参数（与已生效一致）" else label
        // 字符串模板的变量名后若紧跟中文，必须用花括号包住，否则 Kotlin 会连成一个标识符（CI run 50 实测）。
        trialMsg = "正在用${label}合成…（手机 CPU 慢，约 1~3 分钟）"
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                if (!NativeEngine.isAvailable()) throw IllegalStateException("本地引擎没装载，请重启 App")
                ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }
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
                trialMsg = if (p != null) "${label} 出声了 ✓ ${File(p).length()} 字节，已自动播放"
                           else "${label} 失败：" + (res.exceptionOrNull()?.message ?: "未知原因")
                res.exceptionOrNull()?.let {
                    EventLog.log(EventLog.Kind.CRASH, "MIXER_TRIAL_FAIL", it.message ?: "")
                }
            }
        }
    }

    val autoText = VoicePreset("edit", "自定义",
        draft.dims.filter { it.value != "不指定" }, "").render()
    val preview = if (dimEdited) draft.dimText else autoText
    val voiceChange = changes.firstOrNull { it.label.startsWith("音色身份") }

    // 有未提交变更时，系统返回键先问一次，别让草稿静默丢失。
    BackHandler(enabled = dirty) { showLeave = true }

    Column(Modifier.fillMaxSize()) {
        // ── 吸顶状态条（R49 · v5 第 1 条：只留返回 / 状态 / 查看差异 / 提交）──
        // 试听与试听文本已移进默认折叠的「试听」区，不再占首屏 40%。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (dirty) showLeave = true else onBack() }) {
                Text("‹ 返回", fontSize = 11.5.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("已生效",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(if (dirty) "未提交 ${changes.size} 项" else "无待提交变更",
                    fontSize = 10.5.sp, color = if (dirty) Warn else Good)
            }
            TextButton(onClick = { showDiff = true }) { Text("查看差异", fontSize = 11.sp) }
            Button(onClick = { commitAll() }, enabled = dirty) {
                Text("提交变更", fontSize = 11.sp)
            }
        }
        if (msg.isNotBlank() || dirty) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (msg.isNotBlank()) Text(msg, fontSize = 10.sp, color = Sub)
                if (dirty) {
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { discardAll() }) {
                        Text("丢弃全部待提交", fontSize = 10.sp, color = Warn)
                    }
                }
            }
        }
        HorizontalDivider(color = Line)

        AnchorBar(listOf(
            "identity" to "身份", "sampling" to "采样", "perf" to "表演",
            "presets" to "预设",
            "fixedVoice" to "固定音色", "mapping" to "高级"
        ), activeAnchor) { jumpTo(it) }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))

            // ── R49 · v5 第 3 条：默认摘要，全文与编辑按需展开，只显改动 ──
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .onGloballyPositioned { anchors["identity"] = it.positionInParent().y.toInt() }) {
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音色身份", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("可调 · 全书固定", fontSize = 10.sp, color = Sub)
                    Spacer(Modifier.weight(1f))
                    if (dirty) Text("未提交 ${changes.size} 项", fontSize = 10.sp, color = Warn)
                }
                Spacer(Modifier.height(5.dp))
                Text("当前生效 · 全书固定（只读）", fontSize = 10.5.sp, color = Sub)
                val activeVoice = active.voice.trim().ifBlank { DEFAULT_IDENTITY }
                Text(
                    if (showVoiceFull) activeVoice
                    else activeVoice.take(38) + "…（共 ${activeVoice.length} 字）",
                    fontSize = 11.5.sp, modifier = Modifier.background(PrimarySoft)
                )
                TextButton(onClick = { showVoiceFull = !showVoiceFull }) {
                    Text(if (showVoiceFull) "收起全文" else "展开全文 ›", fontSize = 10.5.sp)
                }
                val vdiff = voiceFieldDiff(activeVoice, draft.voice.trim().ifBlank { DEFAULT_IDENTITY })
                if (vdiff.isNotEmpty()) {
                    Text("改动 ${vdiff.size} 处", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    vdiff.take(4).forEach { (x, y) ->
                        Text("$x → $y", fontSize = 10.5.sp, color = Warn)
                    }
                } else if (voiceChange != null) {
                    Text("已改：${voiceChange.old.take(32)} → ${voiceChange.new.take(32)}",
                        fontSize = 10.5.sp, color = Warn)
                }
                TextButton(onClick = { showVoiceEdit = !showVoiceEdit }) {
                    Text(if (showVoiceEdit) "收起编辑" else "编辑描述 ›",
                        fontSize = 10.5.sp, color = Primary)
                }
                if (showVoiceEdit) {
                    OutlinedTextField(
                        value = draft.voice, onValueChange = { draft = draft.copy(voice = it) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                        label = { Text("编辑音色描述（草稿）", fontSize = 11.sp) },
                        placeholder = { Text("例如：温和的中年女声，音区偏低，吐字清晰，语速偏慢，无明显口音", fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp)
                    )
                    Spacer(Modifier.height(5.dp))
                    TextButton(onClick = { draft = draft.copy(voice = "") }) {
                        Text("清空回默认（待提交）", fontSize = 11.sp)
                    }
                } else {
                    Text(draft.voice.trim().ifBlank { DEFAULT_IDENTITY }.take(38) + "…（草稿）",
                        fontSize = 10.5.sp, color = Sub)
                }
                Text("固定音色 = 这段描述 + 种子 + 9 项采样全书不变。", fontSize = 10.sp, color = Sub)
                TextButton(onClick = { jumpTo("fixedVoice") }) {
                    Text("固定音色是什么？ ›", fontSize = 10.5.sp, color = Primary)
                }
                TimingHint(TIMING_NOW)
            }

            // ── 采样参数：三组紧凑表，改动只进 draft，生效权仍归顶部状态条 ──
            MixerSection("采样参数 · 9 项真到模型", "sampling",
                openMap.contains("sampling"), { toggleSection("sampling", it) },
                badge = "✅ 可调", anchor = { anchors["sampling"] = it }) {
                HelpNote("采样顺序", Mixer.CHAIN)
                Spacer(Modifier.height(4.dp))
                val groups = listOf(
                    "主采样（5 项）" to listOf("temperature", "topK", "topP", "repetitionPenalty", "maxNewTokens"),
                    "副采样（3 项）" to listOf("subTemperature", "subTopK", "subTopP"),
                    "确定性（1 项）" to listOf("seed")
                )
                groups.forEach { (gname, keys) ->
                    Text(gname, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp))
                    keys.mapNotNull { ParamCatalog.find(it) }.forEach { spec ->
                        val cur = spec.read(draft.knobs)
                        val shown = paramDrafts[spec.key] ?: cur
                        ParamRow(
                            spec = spec, cur = cur, input = shown,
                            err = paramErr[spec.key] ?: "",
                            onInput = { s ->
                                paramDrafts = paramDrafts + (spec.key to s)
                                val k = spec.withValue(draft.knobs, s)
                                paramErr = paramErr + (spec.key to (spec.validate(s) ?: ""))
                                if (k != null) draft = draft.copy(knobs = k)
                            },
                            onDone = {
                                paramDrafts = paramDrafts + (spec.key to spec.read(draft.knobs))
                                paramErr = paramErr - spec.key
                            },
                            onPick = { target ->
                                spec.withValue(draft.knobs, target)?.let { draft = draft.copy(knobs = it) }
                                paramDrafts = paramDrafts - spec.key
                                paramErr = paramErr - spec.key
                            },
                            onReset = {
                                spec.withValue(draft.knobs, spec.def)?.let { draft = draft.copy(knobs = it) }
                                paramDrafts = paramDrafts - spec.key
                                paramErr = paramErr - spec.key
                            },
                            onRoll = if (spec.key == "seed") ({
                                val n = java.util.concurrent.ThreadLocalRandom.current()
                                    .nextLong(1L, 2_000_000_000L)
                                draft = draft.copy(knobs = draft.knobs.copy(seed = n))
                                paramDrafts = paramDrafts - "seed"
                                paramErr = paramErr - "seed"
                                EventLog.log(EventLog.Kind.ENGINE, "MIXER_SEED",
                                    "换一个固定种子 $n · 仅草稿，不直接生效")
                            }) else null
                        )
                    }
                    TextButton(onClick = {
                        var k = draft.knobs
                        keys.forEach { key ->
                            ParamCatalog.find(key)?.let { s -> s.withValue(k, s.def)?.let { k = it } }
                        }
                        draft = draft.copy(knobs = k)
                        keys.forEach { key ->
                            paramDrafts = paramDrafts - key
                            paramErr = paramErr - key
                        }
                    }) { Text("恢复本组官方默认（待提交）", fontSize = 10.5.sp) }
                }
                TextButton(onClick = {
                    draft = draft.copy(knobs = TtsKnobs())
                    paramDrafts = emptyMap(); paramErr = emptyMap()
                }) { Text("全部恢复上游默认（待提交）", fontSize = 11.5.sp) }
                TimingHint(TIMING_NOW)
            }

            // ── R49 · 试听：默认折叠的两态卡，提交不依赖试听 ──────────────
            MixerSection("试听", "trial",
                openMap.contains("trial"), { toggleSection("trial", it) },
                badge = "两态", anchor = { anchors["trial"] = it }) {
                Text("改完直接提交，不必先试听。", fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                val canTrial = missing.isEmpty() && !busyDraftTrial && !busyActiveTrial
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        trial("未提交参数", draft, "mixer_trial_draft.wav") { busyDraftTrial = it }
                    }, enabled = canTrial, modifier = Modifier.weight(1f)) {
                        Text(if (busyDraftTrial) "合成中…" else "试听未提交参数", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        trial("已生效参数", active, "mixer_trial_active.wav") { busyActiveTrial = it }
                    }, enabled = canTrial, modifier = Modifier.weight(1f)) {
                        Text(if (busyActiveTrial) "合成中…" else "试听已生效参数", fontSize = 11.sp)
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

            // ── R49 · 三态分区：上层项统一挂徽标，默认折叠 ────────────────
            Text("辅助设置 · 上层（能改，但不是模型字段）", fontSize = 10.5.sp, color = Sub,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))

            // ── 音色身份生成器：只有一种语义（生成 → 可编辑预览 → 填入草稿）──
            MixerSection("音色身份生成器", "identity",
                openMap.contains("identity"), { toggleSection("identity", it) },
                badge = "上层") {
                HelpNote("12 维是什么", "按维度挑一挑，自动拼成音色描述；只是帮你写字，不是模型字段。")
                Spacer(Modifier.height(5.dp))
                VoiceDimension.entries.forEach { d ->
                    val curDim = draft.dims[d.name] ?: "不指定"
                    ChipRow(d.label, d.hint, d.options, curDim,
                        allowCustom = true,
                        customValue = if (curDim in d.options) "" else curDim,
                        onCustom = { v ->
                            draft = draft.copy(dims = if (v.isBlank()) draft.dims - d.name
                            else draft.dims + (d.name to v.trim()))
                            if (!dimEdited) draft = draft.copy(dimText = "")
                        }) { v ->
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
                Text("现成音色",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                VoicePicker(voiceQuery, { voiceQuery = it }, recentVoices, "") { p ->
                    draft = draft.copy(voice = p.render(), dims = emptyMap(), dimText = p.render())
                    dimEdited = true
                    Prefs.pushRecentVoice(c, p.id)
                    recentVoices = Prefs.recentVoices(c)
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM_DRAFT",
                        "网格 ${p.id} 填入草稿 · 未生效")
                }
                TimingHint(TIMING_NOW)
            }

            // ── 表演层：只写 draft.perf，提交后对所有新任务生效 ─────────────
            MixerSection("表演层", "perf", openMap.contains("perf"), { toggleSection("perf", it) },
                badge = "上层", anchor = { anchors["perf"] = it }) {
                // R50 · 真机评审第 2 条：整块都要有自定义输入块，且必须真进 draft。
                // 三枚短语框合并进 extraInstruct → styleInstruct() → instruct；
                // 两枚数值框直接写 gapMs / gateRetries，预设之外的合法值不再被 fromJson 重置。
                ChipRow("朗读档位", "叙事 / 对白 / 广播剧，也可自己填一句",
                    ReadingStyle.entries.map { it.label }, draft.perf.style.label,
                    allowCustom = true, customValue = perfText("朗读"),
                    onCustom = { setPerfCustom("朗读", it) }) { v ->
                    ReadingStyle.entries.firstOrNull { it.label == v }?.let {
                        draft = draft.copy(perf = draft.perf.copy(style = it))
                    }
                    clearPerfCustom("朗读")
                }
                ChipRow("语速倾向", "慢 / 正常 / 快，只写稳定倾向；可自己填",
                    Pace.entries.map { it.label }, draft.perf.pace.label,
                    allowCustom = true, customValue = perfText("语速"),
                    onCustom = { setPerfCustom("语速", it) }) { v ->
                    Pace.entries.firstOrNull { it.label == v }?.let {
                        draft = draft.copy(perf = draft.perf.copy(pace = it))
                    }
                    clearPerfCustom("语速")
                }
                ChipRow("情绪强度", "克制 / 标准 / 饱满；可自己填",
                    Intensity.entries.map { it.label }, draft.perf.intensity.label,
                    allowCustom = true, customValue = perfText("情绪"),
                    onCustom = { setPerfCustom("情绪", it) }) { v ->
                    Intensity.entries.firstOrNull { it.label == v }?.let {
                        draft = draft.copy(perf = draft.perf.copy(intensity = it))
                    }
                    clearPerfCustom("情绪")
                }
                ChipRow("段间静音", "合并时插入空白，不改正文；可填 0~5000 之间任意毫秒",
                    ProduceConfig.GAP_OPTIONS.map { "${it}ms" }, "${draft.perf.gapMs}ms",
                    allowCustom = true, customValue = perfText("静音"),
                    onCustom = { setPerfCustom("静音", it) }) { v ->
                    v.removeSuffix("ms").toIntOrNull()?.let {
                        draft = draft.copy(perf = draft.perf.copy(gapMs = it))
                    }
                    clearPerfCustom("静音")
                }
                ChipRow("失败重做", "分段不通过时重试次数；可填 0~10 之间任意次数",
                    ProduceConfig.RETRY_OPTIONS.map { "${it} 次" }, "${draft.perf.gateRetries} 次",
                    allowCustom = true, customValue = perfText("重做"),
                    onCustom = { setPerfCustom("重做", it) }) { v ->
                    v.removeSuffix(" 次").toIntOrNull()?.let {
                        draft = draft.copy(perf = draft.perf.copy(gateRetries = it))
                    }
                    clearPerfCustom("重做")
                }
                TimingHint(TIMING_NEXT)
            }

            // ── 音频开关：只写 draft ───────────────────
            MixerSection("音频开关", "switches", openMap.contains("switches"),
                { toggleSection("switches", it) }, badge = "上层", anchor = { anchors["switches"] = it }) {
                SwitchRow("拟声表达", draft.nonVerbal, TIMING_NOW) {
                    draft = draft.copy(nonVerbal = it)
                }
                TimingHint(TIMING_NOW)
            }

            // ── 预设库：载入只写 draft；保存的是已生效配置，空名禁存 ─────────
            MixerSection("预设库", "presets", openMap.contains("presets"), { toggleSection("presets", it) },
                badge = "配置", anchor = { anchors["presets"] = it }) {
                presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(p.chips(), fontSize = 10.sp, color = Sub)
                        }
                        TextButton(onClick = { showPresetDiff = p }) {
                            Text("对比并载入", fontSize = 10.5.sp)
                        }
                        if (!p.builtin) {
                            if (p.name == "untitled") {
                                TextButton(onClick = { renamePresetId = p.id; renameText = "" }) {
                                    Text("重命名", fontSize = 10.sp, color = Warn)
                                }
                            }
                            TextButton(onClick = { confirmDeletePresetId = p.id }) {
                                Text("删除", fontSize = 10.5.sp, color = Warn)
                            }
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
                        if (presetName.trim().isEmpty() ||
                            MixerPresetStore.safeName(presetName) == "untitled") {
                            msg = "先填个名字（不要用 untitled）"
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
                TimingHint(TIMING_NOW)
            }

            // ── R45 · 固定音色：官方三模式只读说明，不画开关（后程折叠）──────
            MixerSection("固定音色 · 官方模式说明", "fixedVoice",
                openMap.contains("fixedVoice"), { toggleSection("fixedVoice", it) },
                badge = "只读", anchor = { anchors["fixedVoice"] = it }) {
                Text("一句话：官方说的「固定音色」是另一份模型权重；我们现在这份没有音色编号，也没有音色锁。",
                    fontSize = 11.5.sp)
                Spacer(Modifier.height(7.dp))
                Text("官方三种模式（三份权重，不是三个开关）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("声音设计（当前）：写一段描述生成声音；不需要编号，也不吃参考音频。",
                    fontSize = 11.sp)
                Text("预置音色：用音色编号选固定说话人，官方列了 9 个；换权重才能用。",
                    fontSize = 11.sp)
                Text("声音克隆：给 3 秒参考音频提取说话人身份；换权重才能用。",
                    fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                HelpNote("官方 9 个编号",
                    "Vivian / Serena / Uncle_Fu / Dylan / Eric / Ryan / Aiden / Ono_Anna / Sohee。"
                        + "只属预置音色模式，本软件不提供选择，也不画开关。")
                Spacer(Modifier.height(6.dp))
                Text("当前能固定的三样", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Text("身份描述全文 + 随机种子 + 9 项采样，全书不变。上面首屏就能改。",
                    fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                Text("这是收敛每段音色漂移，不等于官方承诺的跨段完全一致；种子固定只保证同参数可复现。",
                    fontSize = 10.5.sp, color = Warn)
                Spacer(Modifier.height(4.dp))
                Text("已决定不换模型，所以编号、参考音频、音色锁在这里都不是可选项，界面不画开关。",
                    fontSize = 10.5.sp, color = Sub)
                TimingHint(TIMING_NOW)
            }

            // ── R45 · 参数与模型一一对应：可调 / 上层 / 不可调，只读不画开关 ──
            MixerSection("哪些能改 · 和模型一一对齐", "mapping",
                openMap.contains("mapping"), { toggleSection("mapping", it) },
                badge = "只读", anchor = { anchors["mapping"] = it }) {
                Text("✅ 真到声音模型（能调）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold, color = Good)
                Text("音色描述全文、9 项采样、语言 chinese、音色编号为空。9 项逐字段送进引擎，改完提交即可。",
                    fontSize = 11.sp)
                HelpNote("9 项逐项核对",
                    ParamCatalog.SAMPLING.joinToString("\n") {
                        "${it.label}：官方默认 ${it.def}，可填 ${it.rangeLabel}${it.unit}"
                    })
                Spacer(Modifier.height(7.dp))
                Text("⚠️ 上层（能改，但模型只看到文字或 App 流程）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold, color = Warn)
                Text("12 维生成器只是帮你写描述；朗读档位、语速倾向、情绪强度拼进描述；"
                    + "段间静音是 WAV 合并。",
                    fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                Text("🔒 结构上没有（不画开关）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold, color = Sub)
                ParamCatalog.LOCKED.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(r.label, fontSize = 10.5.sp, color = Sub,
                            modifier = Modifier.weight(1f))
                        Text("无", fontSize = 10.5.sp, color = Sub)
                    }
                }
                Text("字段名、文档坐标、触达链路收在下面「高级 · 开发者资料」，普通视图不显示。",
                    fontSize = 10.sp, color = Sub)
            }

            // ── 资料与诊断：普通视图只留人话；字段名 / 文档坐标 / 指纹全收进「高级」 ──
            MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),
                { toggleSection("trace", it) }, badge = "只读", anchor = { anchors["trace"] = it }) {
                HelpNote("怎么用这份控制台",
                    "改完点顶部「提交变更」；正在跑的任务不受影响。")
                AdvancedDrawer(rows, stamp)
                TimingHint(TIMING_NOW)
            }

            // R41a · 试听已移进吸顶状态条那一块（草稿 / 已生效各一个按钮，一步合成并播放）。
            // R45 · 末尾留白：滚动到底时最后一块不压住系统手势白条 / 三键区。
            Spacer(Modifier.height(40.dp))
            // R49 · v5 第 7 条：补 8dp 覆盖三键 48dp / 手势白条 24dp
            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 查看差异：当前生效全貌（instruct 不截断）+ 逐项 旧→新 ──────────────
    if (showDiff) {
        AlertDialog(
            onDismissRequest = { showDiff = false },
            title = { Text("当前生效 / 待提交差异", fontSize = 14.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("提交不依赖试听：改完直接点「提交变更」即可生效。", fontSize = 10.5.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
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
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            HorizontalDivider(color = Line)
                            Text(ch.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${ch.old} → ${ch.new}", fontSize = 11.sp)
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

    // ── R41 · 预设对比并载入：逐项「当前生效 → 该预设」再确认 ──────────────
    showPresetDiff?.let { p ->
        val next = buildPresetDraft(p)
        val pdiff = MixerCommit.diff(active, next)
        AlertDialog(
            onDismissRequest = { showPresetDiff = null },
            title = { Text("对比「${p.name}」", fontSize = 14.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(p.chips(), fontSize = 10.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    if (dirty) Text("会覆盖当前 ${changes.size} 项待提交改动；仍由顶部「提交变更」生效。",
                        fontSize = 10.5.sp, color = Warn)
                    Text(if (pdiff.isEmpty()) "与当前生效一致，没有差异" else "共 ${pdiff.size} 项差异",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    pdiff.forEach { ch ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            HorizontalDivider(color = Line)
                            Text(ch.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${ch.old} → ${ch.new}", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { loadPreset(p); showPresetDiff = null }) {
                    Text("载入为待提交", fontSize = 12.sp)
                }
            },
            dismissButton = { TextButton(onClick = { showPresetDiff = null }) { Text("取消") } }
        )
    }

    // ── R41 · 重命名历史 untitled 预设 ───────────────────────────────────
    if (renamePresetId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { renamePresetId = "" },
            title = { Text("重命名预设", fontSize = 14.sp) },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("新名字", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (MixerPresetStore.rename(c, renamePresetId, renameText)) {
                        msg = "已重命名预设"; tick++; renamePresetId = ""; renameText = ""
                    } else Toast.makeText(c, "名字为空、叫 untitled 或重名", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renamePresetId = "" }) { Text("取消") } }
        )
    }

    // ── R41 · 删除预设二次确认 ───────────────────────────────────────────
    if (confirmDeletePresetId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmDeletePresetId = "" },
            title = { Text("删除预设", fontSize = 14.sp) },
            text = { Text("确定删掉这个预设？只删库里这一条，不影响已生效配置。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    if (MixerPresetStore.delete(c, confirmDeletePresetId)) { msg = "已删除预设"; tick++ }
                    confirmDeletePresetId = ""
                }) { Text("删除", fontSize = 12.sp, color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePresetId = "" }) { Text("取消") } }
        )
    }
}
