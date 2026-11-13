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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
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

// R132：任务详情页 / 我的音频页的独立入口与目的地都撤了，能力已归进目录页三列。
internal enum class Screen { LIBRARY, PRODUCE, TASKS, SETTINGS, MODELS, CHAPTERS }

// R139 · 制作准备页的一次性加载结果（全部在 IO 线程算好，组合期不再碰磁盘）
internal data class ProduceLoad(
    val book: com.tingben.app.core.Book,
    val chapters: List<Chapter>,
    val chunks: List<Chunk>,
    val savedConfig: ProduceConfig?
)

internal class NavHost(
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

internal val Bg: Color get() = palette.bg
internal val Card: Color get() = palette.card
internal val Primary: Color get() = palette.primary
internal val PrimarySoft: Color get() = palette.primarySoft
internal val Sub: Color get() = palette.sub
internal val Good: Color get() = palette.good
internal val Line: Color get() = palette.line
internal val Warn: Color get() = palette.warn

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
            "action=${i.action ?: "无"} uri=${EventLog.safe(raw)}")
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
internal fun App(incoming: String? = null, onConsumed: () -> Unit = {}) {
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
    // R303 · 跟手位移：0 = 展开到位，屏高 = 完全滑出屏幕。上滑展开 / 下滑收起
    // 拖动中每帧 snapTo 实时跟手，松手才补一段 animateTo，不再「点了才跳」。
    val sheetH = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    val sheetY = remember(sheetH) { Animatable(sheetH) }
    fun hidePlayer() {
        scope.launch {
            sheetY.animateTo(sheetH, tween(180))
            playerOpen = false
        }
        EventLog.ui("OVERLAY_PLAYER", "close")
    }
    // R148 · 目录页「调音」不再 push 到独立页：跳页会让 ChapterHub 重建，
    // 用户勾选的章节全丢，还多出一个返回键。改成就地弹层，勾选原样保留。
    var chapterMixerOpen by rememberSaveable { mutableStateOf(false) }
    // R292 · 调音台从哪个页面开的，关的时候就记哪个来源。
    var mixerFrom by rememberSaveable { mutableStateOf("hub") }
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
    // R218 · 制作设置页点「重新分章」后回目录页唤起弹窗，能力不占顶栏也不丢
    var resplitTick by rememberSaveable { mutableStateOf(0) }
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
    // R210 · no_pending 的节流状态，与 resumeSkipLogged 各管一条 reason，互不顶掉
    var lastSkipReason by remember { mutableStateOf("") }

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
    BackHandler(enabled = playerOpen) { hidePlayer(); EventLog.ui("OVERLAY_PLAYER", "close:back") }
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
            // R210 · 日志里 no_pending 刷了 20+ 条，把真信息稀释了。
            // R169 只节流了 decided；这里按「reason 变了才记」收敛，
            // 状态从有任务变回无任务仍会记一条，信息不丢。
            if (lastSkipReason != "no_pending") {
                lastSkipReason = "no_pending"
                EventLog.ui("RESUME_ASK_SKIP", "reason=no_pending")
            }
            return@LaunchedEffect
        }
        lastSkipReason = ""
        if (resumeDecided) {
            if (!resumeSkipLogged) {
                resumeSkipLogged = true
                EventLog.ui("RESUME_ASK_SKIP", "reason=decided")
            }
            return@LaunchedEffect
        }
        EventLog.ui("RESUME_CHECK",
            "book=${EventLog.safe(p.task.title)} action=${p.action} auto=$auto phase=${p.task.phase}")
        if (auto && p.action == Producer.ResumeAction.AUTO) {
            resumeDecided = true
            EventLog.ui("RESUME_AUTO", "id=${p.task.bookId.take(8)} · title=${EventLog.safe(p.task.title)}")
            val hint = Producer.autoResume(ctx)
            if (hint != null) Toast.makeText(ctx, hint, Toast.LENGTH_LONG).show()
        } else {
            resumeAsk = p
            EventLog.ui("RESUME_ASK_SHOW",
                "book=${EventLog.safe(p.task.title)} phase=${p.task.phase} action=${p.action}")
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
        EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_BEGIN", "$tag ${EventLog.safe(uri.toString())}")
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
                            EventLog.ui("BOOK_IMPORT_OPEN", "id=${added.id.take(8)}")
                        }
                        Toast.makeText(ctx,
                            if (added != null) "已加入书架：${r.title}（${r.kind}）"
                            else "这本书没加进来，打开左边抽屉再导入一次",
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
                // R298 · 进 IO 找「有未完成任务的书」，直接落到全局任务页；
                // 一本都没任务时给一句中文反馈。
                val b = withContext(Dispatchers.IO) {
                    val t = TaskResume.active(ctx) ?: TaskResume.queue(ctx).firstOrNull()
                    t?.bookId?.let { bid -> BookStore.list(ctx).find { it.id == bid } }
                }
                if (b != null) {
                    bookId = b.id
                    nav.clear()
                    screen = Screen.TASKS
                    EventLog.ui("NOTIF_TASKS_OPEN", "book=${b.id.take(8)}")
                } else {
                    Toast.makeText(ctx, "暂时没有没做完的任务", Toast.LENGTH_SHORT).show()
                }
                refresh++
            }
            raw == "player:" -> { playerOpen = true; scope.launch { sheetY.snapTo(0f) } }
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
                        // R148 · 用户实证：刚导完模型就被拽回主页，手里的页面和按钮全变了。
                        // 留在模型页，把「齐了」当成本页状态，导入交给用户自己点。
                        Toast.makeText(ctx, "模型都齐了，在左边抽屉导入一本书就能开始", Toast.LENGTH_LONG).show()
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
        // R210 · 收尾窗口判断下沉到唯一的生产启动 lambda。
        // R209 只堵了目录页那枚「继续」按钮；但续跑询问弹层（onResume = resumeTask）
        // 与任务看板「继续制作」都走这里，日志 05:09:35 正显示着弹层，
        // 那两处同样会撞上 Producer.start 的 if (busy) return 而毫无反应。
        if (Producer.isBusyForBook(t.bookId)) {
            EventLog.ui("RESUME_BLOCKED_BUSY", "book=${t.bookId.take(8)}")
            Toast.makeText(ctx, "已点停止，正在收尾这一段，稍等再继续",
                Toast.LENGTH_SHORT).show()
        } else {
            val b = BookStore.list(ctx).find { x -> x.id == t.bookId }
            if (b != null) {
                // R284 · 诊断快照里「队列 1 项 · 暂停=true」的标记，只该防进程重启
                // 自动跑起停掉的队列。用户主动点「继续制作」是明确意图：清掉它，
                // 当前这本收尾后 pumpQueue 自然接上排队项，不会永久卡死。
                if (TaskResume.queuePaused(ctx)) {
                    Producer.unpauseQueue(ctx)
                    EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_UNPAUSE_BY_CONTINUE",
                        "用户主动继续制作，队列恢复调度")
                }
                bookId = b.id
                lastSelection = t.only
                Producer.start(ctx, b, t.only, directRead = true,
                    thinkOverride = t.think, config = t.config)
                // R163 · 续跑一律回这本书的目录页（现在只有一列）。
                openBook(b.id)
            } else {
                Toast.makeText(ctx, "这本书已经从书架删掉了，任务没法续跑",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    // R218 · 目录页顶栏写书名；读一次书架索引，随 refresh/切书更新
    val chapterBookTitle = remember(bookId, refresh) {
        bookId?.let { bid -> BookStore.list(ctx).find { it.id == bid }?.title } ?: ""
    }
    val chrome = chromeOf(screen)
    val shownChrome = if (screen == Screen.CHAPTERS)
        chrome.copy(title = chapterBookTitle) else chrome
    // R226 · 目录页三枚操作上移到系统顶栏，与书名同一排右侧；
    // 状态在 App 层按同一份 Producer/TaskResume 真值算，ChapterHub 不再单独占一排。
    val hubProd by Producer.state.collectAsState()
    val hubTask = remember(refresh, hubProd.running, hubProd.stage) { TaskResume.active(ctx) }
    val hubRunning = hubProd.running && hubTask?.bookId == bookId
    val hubStalled = !hubProd.running && hubTask?.bookId == bookId &&
        hubProd.stage in listOf("没完成", "已停止", "出错了")
    var lastHubTapMs by remember { mutableStateOf(0L) }
    fun hubStop() {
        val now = System.currentTimeMillis()
        if (now - lastHubTapMs >= 800L) {
            lastHubTapMs = now
            EventLog.ui("HUB_STOP", "id=${bookId?.take(8)}")
            Producer.stop()
        }
    }
    fun onResumeTask() {
        bookId?.let { id -> TaskResume.byBook(ctx, id)?.let { resumeTask(it) } }
    }
    fun hubResume() {
        val now = System.currentTimeMillis()
        if (now - lastHubTapMs >= 800L) {
            lastHubTapMs = now
            if (bookId?.let { Producer.isBusyForBook(it) } == true) {
                EventLog.ui("HUB_RESUME_BLOCKED", "id=${bookId?.take(8)} reason=收尾中")
                Toast.makeText(ctx, "已点停止，正在收尾这一段，稍等再继续",
                    Toast.LENGTH_SHORT).show()
            } else {
                EventLog.ui("HUB_RESUME", "id=${bookId?.take(8)}")
                onResumeTask()
            }
        }
    }
    // R224 · 左滑开书架抽屉只在软件主页生效；进到书里/设置页不再被边缘手势误触
    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = screen == Screen.LIBRARY,
        drawerContent = {
            AppDrawerSheet(
                title = "",
                onImport = {
                    openBookPicker()
                    scope.launch { drawerState.close() }
                },
                // R230 · 设置从主页右上角齿轮收进抽屉左下角
                onSettings = {
                    EventLog.ui("DRAWER_SETTINGS", "open")
                    modelsFrom = Screen.LIBRARY
                    nav.push(Screen.SETTINGS)
                    refresh++
                    scope.launch { drawerState.close() }
                },
                surface = Card,
                primary = Primary,
                sub = Sub
            ) {
                // R328 · 抽屉里只挂书单，独立书架页和 compact 开关都撤了：
                // 进度条、置顶、删除一个不少，导入失败提示与空态也真正生效。
                LibraryScreen(ctx, refresh,
                    currentId = bookId,
                    onOpen = { id ->
                        bookId = id
                        nav.push(Screen.CHAPTERS)
                        scope.launch { drawerState.close() }
                    },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ },
                    importError = bookImportError,
                    onDismissImportError = { bookImportError = null })
            }
        }
    ) {
        Box(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            // R230 · 首页两枚入口（调音常驻、任务有活才亮）与目录页三枚
            // 共用系统顶栏书名右侧同一排；主页右上角不再有 ＋/齿轮/悬浮开关。
        AppTopBar(
            spec = shownChrome,
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
            onBack = if (chrome.canBack) backAction else null,
            trailing = when (screen) {
                Screen.LIBRARY -> {
                    {
                        IconButton(onClick = { chapterMixerOpen = true; mixerFrom = "home"
                            EventLog.ui("OVERLAY_MIXER", "open:home") }) {
                            MixerIcon(Primary)
                        }
                        IconButton(onClick = {
                            // R243 · 主页任务通向全局任务页，不是目录页就地看板
                            // R303 · 没任务也能点进去看空态，不再置灰把入口拦住
                            EventLog.ui("HOME_TASKS", "open:global")
                            nav.push(Screen.TASKS)
                        }) {
                            TasksIcon(Primary)
                        }
                    }
                }
                Screen.CHAPTERS -> {
                {
                    if (hubRunning) {
                        IconButton(onClick = { hubStop() }) { StopIcon(Warn) }
                    } else if (hubStalled) {
                        IconButton(onClick = { hubResume() }) { PlayIcon(Primary) }
                    }
                    // R272 · 调音台入口不只首页有，章节目录页顶栏也常驻一枚；
                    // 与底部多选胶囊里的「调音 ›」不是一回事（那个跟选中章绑定）。
                    IconButton(onClick = {
                        chapterMixerOpen = true; mixerFrom = "hubbar"
                        EventLog.ui("OVERLAY_MIXER", "open:hubbar")
                    }) { MixerIcon(Primary) }
                    // R320 · ⚙ 字符换成自绘齿轮，和设置页前导图标同一套语言
                    IconButton(onClick = { nav.push(Screen.PRODUCE) },
                        modifier = Modifier.size(34.dp)) {
                        UiIconGlyph(UiIcon.SETTINGS, Primary, 20.dp)
                    }
                }
                }
                else -> { {} }
            }
        )
        Box(Modifier.weight(1f)) {
            when (screen) {
                // R162 · 主页就是输入框：粘贴文字直接建书，书列表搬到左边抽屉。
                Screen.LIBRARY -> InputReadScreen(ctx,
                    onBack = { },
                    onAdded = { },   // R215/R276 · 首页贴一段点合成，不再建书、不再跳目录页
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
                        onMixer = { chapterMixerOpen = true; mixerFrom = "hub"; EventLog.ui("OVERLAY_MIXER", "open:hub") },
                        onProduceSetup = { id -> bookId = id; nav.push(Screen.PRODUCE) },
                        onStarted = { id, sel ->
                            lastSelection = sel
                            openBook(id)
                        },
                        resplitRequest = resplitTick,
                        onOpenModels = {
                            modelsFrom = Screen.CHAPTERS
                            nav.push(Screen.MODELS)
                        })
                }
                Screen.PRODUCE -> ProduceScreen(ctx, bookId, refresh,
                    onBack = { nav.pop() },
                    onProgress = { openBook(bookId) },
                    onDone = { nav.clear(); openBook(bookId); refresh++ },
                    onStarted = { lastSelection = it },
                    onResplit = { nav.pop(); resplitTick++ },
                    onSettings = { nav.push(Screen.SETTINGS) })
                Screen.TASKS -> TaskCenterScreen(ctx, refresh,
                    onOpenTask = { id -> openBook(id) },
                    onProgress = { id -> openBook(id) },
                    onAudio = { id -> openBook(id); refresh++ },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onResume = resumeTask)
                Screen.SETTINGS -> SettingsScreen(ctx, refresh,
                    onModels = { modelsFrom = Screen.SETTINGS; nav.push(Screen.MODELS) },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onMixer = { chapterMixerOpen = true; mixerFrom = "settings"; EventLog.ui("OVERLAY_MIXER", "open:settings") })
                Screen.MODELS -> ModelScreen(ctx, refresh,
                    importing = importing, importBytes = importBytes, importError = importError,
                    onPick = { pickModel.launch(arrayOf("*/*")) },
                    onBack = { nav.pop() },
                    onLibrary = { screen = Screen.LIBRARY; nav.clear() },
                    onChanged = { refresh++ })
            }
        }

        MiniPlayBar(ctx, miniBook,
            onExpand = { playerOpen = true; EventLog.ui("OVERLAY_PLAYER", "open")
                scope.launch { sheetY.snapTo(sheetH); sheetY.animateTo(0f, tween(220)) } },
            onGoBook = { id ->
                bookId = id
                nav.push(Screen.CHAPTERS)
            },
            onPullUp = { px ->
                if (!playerOpen) playerOpen = true
                scope.launch { sheetY.snapTo((sheetH - px).coerceIn(0f, sheetH)) }
            },
            onPullEnd = {
                scope.launch {
                    if (sheetY.value < sheetH * 0.5f) sheetY.animateTo(0f, tween(180))
                    else { sheetY.animateTo(sheetH, tween(180)); playerOpen = false }
                }
            })
        }
        // 播放器是覆盖全屏的浮层，不进 Screen 枚举 / 导航栈：
        // 它不是「另一页」，是同一份 PlayQueue 状态的展开视图。
        if (playerOpen) {
            // R303 · 位移只改这一层 Box 的放置，播放器内容不重组，动画每帧只重摆位置。
            Box(Modifier.fillMaxSize()
                .offset { IntOffset(0, sheetY.value.roundToInt()) }) {
                PlayerSheet(ctx, miniBook ?: bookId,
                    // R319 · 读 Animatable.value 会随每帧订阅重组，
                    // 下滑时页面控件按这个比例淡出，不再整层平移叠成一团。
                    pullFraction = (sheetY.value / sheetH).coerceIn(0f, 1f),
                    onClose = { hidePlayer() },
                    onGoBook = { id ->
                        bookId = id
                        nav.push(Screen.CHAPTERS)
                    },
                    onPullDown = { px ->
                        scope.launch { sheetY.snapTo(px.coerceIn(0f, sheetH)) }
                    },
                    onPullSettle = {
                        scope.launch {
                            if (sheetY.value > sheetH * 0.35f) {
                                sheetY.animateTo(sheetH, tween(180))
                                playerOpen = false
                                EventLog.ui("OVERLAY_PLAYER", "close:drag")
                            } else sheetY.animateTo(0f, spring())
                        }
                    })
            }
        }
        if (chapterMixerOpen) {
            MixerDialog(ctx, onClose = {
                chapterMixerOpen = false; EventLog.ui("OVERLAY_MIXER", "close:$mixerFrom")
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
                        "book=${EventLog.safe(p.task.title)} action=${p.action}")
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
                    EventLog.ui("RESUME_ASK_VIEW", "id=${p.task.bookId.take(8)}")
                    resumeAsk = null
                    resumeDecided = true
                    screen = Screen.TASKS
                    nav.clear()
                },
                onLater = {
                    EventLog.ui("RESUME_ASK_LATER", "id=${p.task.bookId.take(8)}")
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

// @ui-insert: Shell
// @ui-insert: LibraryUi
// @ui-insert: InputReadUi
// @ui-insert: ProduceUi
// @ui-insert: TaskUi
// @ui-insert: SettingsUi
// @ui-insert: ModelUi
// @ui-insert: MixerUi
// @ui-insert: ResumeUi
