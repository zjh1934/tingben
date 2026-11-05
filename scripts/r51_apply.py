#!/usr/bin/env python3
"""R51 · 卡顿根因收窄 + 抽屉联动（只改 MainActivity.kt / AppChrome.kt 注释）

依据：
- 官方「把状态读在树的最低处」(developer.android.com Compose 性能指南)
- 官方 DismissibleNavigationDrawer：抽屉展开时把右侧工作页一起推走；
  ModalNavigationDrawer 只把抽屉盖在内容上（覆盖式）。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
P = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
C = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
src = P.read_text()
orig = src

def rep(old, new, tag):
    global src
    n = src.count(old)
    if n != 1:
        sys.exit("R51 %s: 期望命中 1 次，实际 %d 次" % (tag, n))
    src = src.replace(old, new, 1)
    print("  ok", tag)

# ① App() 顶层两个高频订阅删除 ------------------------------------------------
rep(
"""    val st by Producer.state.collectAsState()
    val resumeTask by Producer.resume.collectAsState()
""",
"""    // R51 · 高频引擎状态不再在 App() 顶层订阅。旧版为了给书架页传 5 个根本没被使用的
    // 参数，把 Producer.state / Producer.resume 挂在整棵页面树的根上：制作时每次块更新
    // 都会重组 when(screen) 下全部 9 个页面。现在收进 KeepScreenOn() / TaskBar() 两个
    // 叶子 composable（官方「状态读在树的最低处」），回调里只读 .value，不订阅。
""",
"A 顶层订阅")

# ② 屏幕常亮下沉 --------------------------------------------------------------
rep(
"""    // R19 · 长任务在跑时保持屏幕常亮：只加前台窗口 flag，零权限、零 WakeLock。
    // ⚠️ 这不等于保活：锁屏/切后台后 CPU 仍会睡、进程仍可能被回收。
    // 后台续跑要走前台服务 + PARTIAL_WAKE_LOCK + 电池白名单，单列下一轮，不混在这轮验。
    val act = ctx as? android.app.Activity
    DisposableEffect(st.running) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (st.running) act?.window?.addFlags(flag) else act?.window?.clearFlags(flag)
        onDispose { act?.window?.clearFlags(flag) }
    }
""",
"""    // R19 · 长任务在跑时保持屏幕常亮：零权限、零 WakeLock；不等于保活。
    // R51 · 下沉到叶子 composable，避免 App() 为它订阅高频引擎状态。
    KeepScreenOn()
""",
"B 屏幕常亮")

# ③ 抽屉：覆盖 → 联动 ---------------------------------------------------------
rep(
"""    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
""",
"""    // R51 · 联动抽屉：官方 DismissibleNavigationDrawer 展开时把右侧工作页一起推走，
    // 不再是 ModalNavigationDrawer 那种「抽屉盖在内容上 + 全屏遮罩」的覆盖式。
    // ☰ / 从左边缘右滑 / 返回键优先收抽屉三个行为一行未改。
    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
""",
"C 抽屉联动")

# ④ 书架页去掉 5 个未使用参数 -------------------------------------------------
rep(
"""                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; screen = Screen.PRODUCE },
                    onModels = { modelsFrom = Screen.LIBRARY; screen = Screen.MODELS },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ },
                    producing = st.running, progressText = if (st.total > 0) "${st.done}/${st.total} 块" else "",
                    onProgress = { screen = Screen.PROGRESS },
                    pendingTask = resumeTask?.takeIf { it.phase != "done" },
                    onTasks = { screen = Screen.TASKS })
""",
"""                // R51 · producing / progressText / onProgress / pendingTask / onTasks
                // 五个参数在 LibraryScreen 函数体里从未被读取，白挂了一路高频订阅。
                Screen.LIBRARY -> LibraryScreen(ctx, refresh,
                    onOpen = { bookId = it; screen = Screen.PRODUCE },
                    onModels = { modelsFrom = Screen.LIBRARY; screen = Screen.MODELS },
                    onImportBook = { openBookPicker() },
                    onChanged = { refresh++ })
""",
"D 书架实参")

# ⑤ 续跑回调里读 .value，不订阅 -----------------------------------------------
rep(
"""                    onResume = {
                        val t = resumeTask
""",
"""                    onResume = {
                        val t = Producer.resume.value
""",
"E 续跑回调")

# ⑥ LibraryScreen 签名 --------------------------------------------------------
rep(
"""private fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit,
                           onModels: () -> Unit, onImportBook: () -> Unit,
                           onChanged: () -> Unit, producing: Boolean,
                           progressText: String, onProgress: () -> Unit,
                           pendingTask: TaskInfo?, onTasks: () -> Unit) {
""",
"""private fun LibraryScreen(c: Context, refresh: Int, onOpen: (String) -> Unit,
                           onModels: () -> Unit, onImportBook: () -> Unit,
                           onChanged: () -> Unit) {
""",
"F 书架签名")

# ⑦ 任务条整段 → 叶子 composable 调用 -----------------------------------------
start = src.index("        // 跨页面常驻任务条：上扒展开 / 下扒收起，点整卡不再跳页")
end = src.index("        // V1 · 3 个顶层 Tab 已收进左侧抽屉", start)
src = src[:start] + """        // R51 · 任务条抽成叶子 composable：自己订阅引擎状态，
        // App() 不再为它重组整棵 when(screen) 页面树。
        TaskBar(onNavigate = { target, isAudio ->
            screen = target
            if (isAudio) refresh++
        })
""" + src[end:]
print("  ok G 任务条下沉")

# ⑧ 两个新 composable 插在 App() 之后 -----------------------------------------
marker = "\nprivate fun queryName(c: Context, uri: Uri): String {"
extra = """

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

/**
 * R51 · 跨页面常驻任务条：上扒展开 / 下扒收起，点整卡不跳页。
 * 从 App() 抽出来自己订阅 Producer.state / Producer.resume：制作时每次块更新
 * 只重组这一条，不再把 9 个页面全部重组合一遍。手势与文案一行未改。
 */
@Composable
private fun TaskBar(onNavigate: (Screen, Boolean) -> Unit) {
    val st by Producer.state.collectAsState()
    val resumeTask by Producer.resume.collectAsState()
    val pendingTask = resumeTask?.takeIf { it.phase != "done" && !st.running }
    val hasTask = st.running || st.output != null || pendingTask != null
    var taskExpanded by remember { mutableStateOf(false) }
    if (!hasTask) return
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
                    when {
                        st.running -> onNavigate(Screen.PROGRESS, false)
                        pendingTask != null -> onNavigate(Screen.TASKS, false)
                        else -> onNavigate(Screen.AUDIO, true)
                    }
                }) {
                    Text(if (st.running) "查看进度 ›"
                        else if (pendingTask != null) "去任务页 ›" else "去试听 ›",
                        fontSize = 12.sp)
                }
            }
        }
    }
}
"""
if src.count(marker) != 1:
    sys.exit("R51 H: marker 不唯一")
src = src.replace(marker, extra + marker, 1)
print("  ok H 新 composable")

# ⑨ 三个 tick 循环收敛 --------------------------------------------------------
rep(
"""    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    // 每秒读一次最近一条引擎事件""",
"""    var tick by remember { mutableStateOf(0) }
    // R51 · 没在制作时不需要心跳，停掉无谓的每秒整页重组。
    LaunchedEffect(st.running) {
        if (!st.running) return@LaunchedEffect
        while (true) { delay(1000); tick++ }
    }

    // 每秒读一次最近一条引擎事件""",
"I 进度心跳")

rep(
"""    val tick = remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick.value++ } }
    // 每次进页面 / 每秒都从磁盘读，不依赖进程内存
    val saved = remember(refresh, tick.value / 2) { TaskResume.load(c) }
""",
"""    // R51 · 旧版每秒 tick 一次（整页重组 1 次/秒），tick/2 每 2 秒同步读磁盘。
    // 合成一次 2 秒信号：重组次数减半，磁盘读频率不变。
    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); loadTick++ } }
    val saved = remember(refresh, loadTick) { TaskResume.load(c) }
""",
"J 任务页 tick")

rep(
"""    LaunchedEffect(Unit) { while (true) { delay(300); PlayQueue.tick() } }
""",
"""    // R51 · 旧版无条件每 300ms tick，即使没播放也把整页重组 3.3 次/秒。
    // 只在播放时刷新进度，500ms 足够人眼读进度条。
    LaunchedEffect(pq.isPlaying) {
        if (!pq.isPlaying) return@LaunchedEffect
        while (true) { delay(500); PlayQueue.tick() }
    }
""",
"K 音频页 tick")

# ⑩ 锚点流不再每帧复制 map ---------------------------------------------------
rep(
"""    LaunchedEffect(Unit) {
        snapshotFlow { scroll.value to anchors.toMap() }.collect { (v, pos) ->
            if (jumpLock) return@collect
            val top = v + 24
            var best = "identity"
            for ((k, y) in pos.entries.sortedBy { it.value }) {
                if (y > top) break
                if (k in anchorShown) best = k
                // 试听 / 开关 / 诊断不在锚点栏里：归并到前一个可见锚点，不出现全不高亮。
            }
            if (best != activeAnchor) activeAnchor = best
        }
    }
""",
"""    LaunchedEffect(Unit) {
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
""",
"L 锚点流")

# ⑪ OptIn ----------------------------------------------------------------------
rep(
"""@Composable
private fun App(incoming: String? = null, onConsumed: () -> Unit = {}) {
""",
"""@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(incoming: String? = null, onConsumed: () -> Unit = {}) {
""",
"M OptIn")

if src == orig:
    sys.exit("R51: 没有任何改动")
P.write_text(src)

# AppChrome 注释订正
c = C.read_text()
old = """ * R47 · 抽屉收窄：M3 ModalDrawerSheet 默认 320dp 对 7 个三五字标签过宽，"""
new = """ * R51 · 外层 Drawer 改 DismissibleNavigationDrawer：抽屉展开时右侧工作页一起推走（联动），
 * 不再是 ModalNavigationDrawer 的覆盖式。本文件的 Sheet 与 7 个条目一行未改。
 *
 * R47 · 抽屉收窄：M3 ModalDrawerSheet 默认 320dp 对 7 个三五字标签过宽，"""
if c.count(old) != 1:
    sys.exit("R51 N: AppChrome 注释锚点不唯一")
C.write_text(c.replace(old, new, 1))
print("  ok N AppChrome 注释")
print("R51 apply done")
