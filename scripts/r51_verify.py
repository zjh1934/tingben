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
need(re.search(r'^        TaskBar\(onNavigate', app, re.M) is not None,
     "任务条没下沉成 TaskBar()")
need("Producer.resume.value" in app, "续跑回调没改成读 .value")

# 叶子各自订阅
for name in ["private fun KeepScreenOn()", "private fun TaskBar(onNavigate"]:
    need(name in s, "缺叶子 composable " + name)
k = s.index("private fun KeepScreenOn()")
t = s.index("private fun TaskBar(onNavigate")
e = s.index("\nprivate fun queryName", t)
need("Producer.state.collectAsState()" in s[k:t], "KeepScreenOn 没自己订阅 state")
need("Producer.state.collectAsState()" in s[t:e], "TaskBar 没自己订阅 state")
need("Producer.resume.collectAsState()" in s[t:e], "TaskBar 没自己订阅 resume")
need("detectVerticalDragGestures(" in s[t:e], "任务条手势丢了")

# 书架页 5 个未使用参数清零
a2 = s.index("private fun LibraryScreen")
b2 = s.index("// ── ② 制作", a2)
lib = s[a2:b2]
for name in ["producing", "progressText", "onProgress", "pendingTask", "onTasks"]:
    need(len(re.findall(r'\b' + name + r'\b', lib)) == 0,
         "LibraryScreen 仍引用已删参数 " + name)

# 三个 tick 收敛
need("delay(300)" not in s, "音频页 300ms 无条件 tick 还在")
need("LaunchedEffect(pq.isPlaying)" in s, "音频页没按播放态门控")
need("delay(500); PlayQueue.tick()" in s, "音频页进度刷新不是 500ms")
need("delay(2000); loadTick++" in s, "任务页没合成 2 秒信号")
need("tick.value / 2" not in s, "任务页旧的 tick/2 磁盘读还在")
need("LaunchedEffect(st.running) {" in s, "进度页心跳没按 running 门控")
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
blocked(lambda x: x.replace("        TaskBar(onNavigate", "        // TaskBar(onNavigate"),
        "任务条不再下沉",
        lambda m: re.search(r'^        TaskBar\(onNavigate', app_body(m), re.M) is not None)
def inject_root(x):
    a = x.index("private fun App(incoming")
    k = x.index("/**\n * R51 · 屏幕常亮", a)
    body = x[a:k].replace("// R51 · 高频引擎状态",
                          "        val st by Producer.state.collectAsState()\n    // R51 · 高频引擎状态", 1)
    return x[:a] + body + x[k:]
blocked(inject_root, "根上恢复订阅",
        lambda m: "Producer.state.collectAsState()" not in app_body(m))
blocked(lambda x: x.replace("LaunchedEffect(pq.isPlaying)", "LaunchedEffect(Unit)"),
        "音频页取消播放门控", lambda m: "LaunchedEffect(pq.isPlaying)" in m)
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
print("R51 PASS · 正向 %d 项 / 反向注入 6 例 / 守恒与括号全过" % 14)
