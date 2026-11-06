#!/usr/bin/env python3
"""R51 · 卡顿根因收窄 + 抽屉联动：正向 / 反向注入 / 守恒。

不依赖 Android SDK，只做源码级断言；真编译由 CI 的 assembleDebug 负责。
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
s = MAIN.read_text()
c = CHROME.read_text()
errs = []

def need(cond, msg):
    if not cond:
        errs.append(msg)

def app_body(text):
    a = text.index("private fun App(incoming")
    k = text.index("/**\n * R51 · 屏幕常亮", a)
    return text[a:k]

app = app_body(s)

# ── 正向 ────────────────────────────────────────────────────────────────
need("DismissibleNavigationDrawer(" in s, "缺联动抽屉 DismissibleNavigationDrawer")
need("ModalNavigationDrawer(" not in s, "又出现覆盖式抽屉调用")
need("ExperimentalMaterial3Api::class" in s, "缺 OptIn（M3 抽屉实验性 API）")
need("R51 · 外层 Drawer 改 DismissibleNavigationDrawer" in c,
     "AppChrome 没登记 R51 注释")

# 根上不再订阅高频引擎状态
need("Producer.state.collectAsState()" not in app, "App() 仍订阅 Producer.state")
need("Producer.resume.collectAsState()" not in app, "App() 仍订阅 Producer.resume")
need(len(re.findall(r'\bst\.', app)) == 0, "App() 仍读 st.")
need("resumeTask" not in app, "App() 仍读 resumeTask")
need("KeepScreenOn()" in app, "屏幕常亮没下沉成 KeepScreenOn()")
# R65 · 贴底任务卡整条删除；R51 的「订阅下沉」改由进度页叶子承担。
need("TaskBar(" not in app, "R65 任务条不该再挂在 App() 上")
need("private fun TaskBar(" not in s, "R65 任务条函数应已删除")
need("Producer.resume.collectAsState()" not in s, "任务条删了但 resume 订阅还在")
need("Producer.resume.value" in app, "续跑回调没改成读 .value")

# 叶子各自订阅
for name in ["private fun KeepScreenOn()", "private fun ProgressScreen("]:
    need(name in s, "缺叶子 composable " + name)
k = s.index("private fun KeepScreenOn()")
e = s.index("private fun queryName", k)
need("Producer.state.collectAsState()" in s[k:e], "叶子没自己订阅 state")
pa = s.index("private fun ProgressScreen(")
pb = s.index("// ── ③b 任务中心", pa)
need("Producer.state.collectAsState()" in s[pa:pb], "进度页没自己订阅 state")
need("LaunchedEffect(st.running) {" in s[pa:pb], "进度页心跳没按 running 门控")

# 书架页 5 个未使用参数清零
a2 = s.index("private fun LibraryScreen")
b2 = s.index("// ── ② 制作", a2)
lib = s[a2:b2]
for name in ["producing", "progressText", "onProgress", "pendingTask", "onTasks"]:
    need(len(re.findall(r'\b' + name + r'\b', lib)) == 0,
         "LibraryScreen 仍引用已删参数 " + name)

# 三个 tick 收敛
# R79 订正：R51 把「音频页自己门控 + 自己 500ms tick」写死成正断言；R79 起 tick 统一由
# 跨页常驻的 MiniPlayBar 驱动，音频页只订阅 state。旧两条断言已过时，改成更严的新形态：
# 悬浮条必须门控且是唯一 tick 源，音频页内不许再出现第二处 PlayQueue.tick()。
hub_path = ROOT / "app/src/main/java/com/tingben/app/ChapterHub.kt"
hub = hub_path.read_text()
audio = s[s.index("private fun AudioScreen"):s.index("// ── ⑤ 设置（只管软件） ──")]
need("delay(300)" not in s, "音频页 300ms 无条件 tick 还在")
need("if (!st.isPlaying) return@LaunchedEffect" in hub, "悬浮条 tick 没按播放态门控")
need("delay(500L)" in hub and "delay(1000L)" not in hub, "悬浮条不是唯一 500ms tick 源")
need("PlayQueue.tick()" in hub, "悬浮条没驱动 PlayQueue.tick")
need("PlayQueue.tick()" not in audio, "音频页又出现第二处 tick 驱动")
need("LaunchedEffect(Unit) { while (true) { delay(2000); loadTick++ } }" not in s,
     "任务页 2 秒轮询退回无条件")
need("delay(2000); loadTick++" in s, "任务页 2 秒信号丢了")
need("LaunchedEffect(st.running) {" in s, "任务页/进度页心跳没按 running 门控")
need("tick.value / 2" not in s, "任务页旧的 tick/2 磁盘读还在")
need("snapshotFlow { scroll.value }" in s, "锚点流仍每帧复制 map")
need("scroll.value to anchors.toMap()" not in s, "旧锚点流还在")

# ── 守恒（一行都不能动） ────────────────────────────────────────────────
need(len(re.findall(r'^private enum class Screen \{.*\}$', s, re.M)) == 1, "Screen 枚举变了")
need(s.count("Screen.") >= 9, "Screen 分支数异常")
need("chromeOf(screen)" in s, "chromeOf 调用丢了")
need("BackHandler(enabled = screen != Screen.LIBRARY)" in s, "原返回键丢了")
need("BackHandler(enabled = drawerState.isOpen)" in s, "抽屉返回键优先丢了")
need(s.count("refresh++") >= 4, "refresh 计数被删")
need("navigationBarsPadding()" in s and "statusBarsPadding()" in s, "安全区丢了")
need("AppDrawerSheet(" in s and "requiredWidth(240.dp)" in c, "抽屉收窄丢了")
need("ProduceService" in (ROOT / "app/src/main/AndroidManifest.xml").read_text(),
     "前台服务声明丢了")

# ── 反向注入：把修好的形态改回旧版，断言必须能拦下 ─────────────────────
def blocked(mut, label, probe):
    """把 mut 应用后的源码重新跑关键断言，若断言仍通过说明是假绿。"""
    m = mut(s)
    ok = probe(m)
    need(not ok, "反向注入没拦下：" + label)

blocked(lambda x: x.replace("DismissibleNavigationDrawer(", "ModalNavigationDrawer("),
        "换回覆盖式抽屉", lambda m: "DismissibleNavigationDrawer(" in m and "ModalNavigationDrawer(" not in m)
blocked(lambda x: x.replace("        MiniPlayBar(ctx, miniBook",
                             "        TaskBar(onNavigate = { _, _ -> })\n        MiniPlayBar(ctx, miniBook"),
        "任务条回潮",
        lambda m: "TaskBar(" not in app_body(m))
def inject_root(x):
    a = x.index("private fun App(incoming")
    k = x.index("/**\n * R51 · 屏幕常亮", a)
    body = x[a:k].replace("// R51 · 高频引擎状态",
                          "        val st by Producer.state.collectAsState()\n    // R51 · 高频引擎状态", 1)
    return x[:a] + body + x[k:]
blocked(inject_root, "根上恢复订阅",
        lambda m: "Producer.state.collectAsState()" not in app_body(m))
def blocked_h(mut, label, probe):
    m = mut(hub)
    need(not probe(m), "反向注入没拦下：" + label)
blocked_h(lambda x: x.replace("if (!st.isPlaying) return@LaunchedEffect", ""),
          "悬浮条取消播放门控", lambda m: "if (!st.isPlaying) return@LaunchedEffect" in m)
blocked(lambda x: x.replace(
            "    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {",
            "    LaunchedEffect(Unit) { while (true) { delay(500); PlayQueue.tick() } }\n"
            "    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {"),
        "音频页又出现第二处 tick",
        lambda m: "PlayQueue.tick()" not in m[m.index("private fun AudioScreen"):
                                              m.index("// ── ⑤ 设置（只管软件） ──")])
blocked(lambda x: x.replace("delay(2000); loadTick++", "delay(1000); tick.value++"),
        "任务页退回 1 秒 tick", lambda m: "delay(2000); loadTick++" in m)
blocked(lambda x: x.replace("snapshotFlow { scroll.value }", "snapshotFlow { scroll.value to anchors.toMap() }"),
        "锚点流恢复每帧复制", lambda m: "scroll.value to anchors.toMap()" not in m)

# 括号平衡（先屏蔽字符串/注释）
def strip_code(text):
    out = []; i = 0; n = len(text)
    while i < n:
        ch = text[i]
        if ch == '"' and text[i+1:i+3] == '""':
            j = text.find('"""', i+3); j = n if j < 0 else j+3
            out.append(' '*(j-i)); i = j; continue
        if ch == '"' or ch == "'":
            q = ch; j = i+1
            while j < n:
                if text[j] == '\\': j += 2; continue
                if text[j] == q: j += 1; break
                j += 1
            out.append(' '*(j-i)); i = j; continue
        if ch == '/' and i+1 < n and text[i+1] == '/':
            j = text.find('\n', i); j = n if j < 0 else j
            out.append(' '*(j-i)); i = j; continue
        if ch == '/' and i+1 < n and text[i+1] == '*':
            j = text.find('*/', i+2); j = n if j < 0 else j+2
            out.append(' '*(j-i)); i = j; continue
        out.append(ch); i += 1
    return ''.join(out)
for f in [MAIN, CHROME]:
    z = strip_code(f.read_text())
    for o, cl in [('{','}'), ('(',')'), ('[',']')]:
        need(z.count(o) == z.count(cl), "%s 括号不平衡 %s%s" % (f.name, o, cl))

if errs:
    print("R51 FAIL")
    for e in errs: print("  -", e)
    sys.exit(1)
print("R51 PASS · 正向 %d 项 / 反向注入 7 例 / 守恒与括号全过（R79 tick 源订正）" % 14)
