#!/usr/bin/env python3
"""R46 · V1 抽屉壳验证：正向形态 / 旧壳零残留 / 反向注入 / 业务守恒。

只依赖标准库。CI 挂独立 step；本地 `python3 scripts/r46_verify.py`。
口径：抽屉是一整页不是浮层；返回键优先关抽屉；底栏 Tab 收进抽屉；
App() 之外的业务代码、Screen 9 分支、外部导入落地、通知回跳全部与 HEAD 一致。
"""
import os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from r45_common import canon
import nav_semantics as nav

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN_P = "app/src/main/java/com/tingben/app/MainActivity.kt"
CHROME_P = "app/src/main/java/com/tingben/app/AppChrome.kt"
MAIN = (ROOT / MAIN_P).read_text(encoding="utf-8")
CHROME = (ROOT / CHROME_P).read_text(encoding="utf-8")

CHROME_POS = [
    "internal fun AppDrawerSheet(",
    "requiredWidth(240.dp)",
    "fontSize = 13.sp",
    "NavigationDrawerItem(",
    "HorizontalDivider(",
    "onMenu: () -> Unit",
    "IconButton(onClick = onMenu)",
    'NAV_MIXER to "调音台"',
    "private val DRAWER_TOP = listOf(",
    "private val DRAWER_MORE = listOf(",
    "if (acc > 55f) onRefresh()",
]
MAIN_POS = [
    "DismissibleNavigationDrawer(",
    "drawerState = drawerState",
    "gesturesEnabled = true",
    "drawerContent = {",
    "AppDrawerSheet(",
    "rememberDrawerState(DrawerValue.Closed)",
    "BackHandler(enabled = drawerState.isOpen)",
    "scope.launch { drawerState.close() }",
    "onMenu = { scope.launch { drawerState.open() } }",
    ".navigationBarsPadding()",
]
OLD_SHELL = ["AppBottomBar(", "DropdownMenu(", "MENU_ITEMS", "TOP_TABS", "AppTab(",
             "onMenu = { idx ->", "ModalNavigationDrawer("]

def enum_count(s):
    m = re.search(r"private enum class Screen \{([^}]*)\}", s)
    return len(set(re.findall(r"\b[A-Z_]+\b", m.group(1)))) if m else -1

def dispatch_count(s):
    head = "Box(Modifier.weight(1f)) {\n            when (screen) {"
    i = s.find(head)
    if i < 0: return -1
    start = i + len(head) - 1
    depth = 0
    for k in range(start, len(s)):
        if s[k] == "{": depth += 1
        elif s[k] == "}":
            depth -= 1
            if depth == 0:
                return len(re.findall(r"Screen\.[A-Z_]+\s*->", s[start:k + 1]))
    return -1

def chrome_set(s):
    m = re.search(r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}", s, re.S)
    return set(re.findall(r"Screen\.([A-Z_]+)\s*->", m.group(1))) if m else set()

def chrome_count(s):
    return len(chrome_set(s))

def check(main, chrome, tag):
    e = []
    def ck(c, m):
        if not c: e.append("[%s] %s" % (tag, m))
    for s in MAIN_POS:
        ck(s in main, "MainActivity 缺符号: " + s[:60])
    for s in CHROME_POS:
        ck(s in chrome, "AppChrome 缺符号: " + s[:60])
    for s in OLD_SHELL:
        ck(s not in main and s not in chrome, "旧壳残留: " + s)
    for msg in nav.semantics(main):
        ck(False, "导航语义: " + msg)
    ck(nav.back_order_ok(main), "抽屉 BackHandler 没排在全局回退之后")
    EXPECTED_SCREENS = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
                        "SETTINGS", "MODELS", "MIXER", "CHAPTERS", "TASK_DETAIL"}
    n = len(EXPECTED_SCREENS)
    ck(enum_count(main) == n, "Screen 枚举不是 %d 值" % n)
    ck(dispatch_count(main) == n, "when(screen) 分发不是 %d 分支" % n)
    ck(chrome_count(main) == n, "chromeOf 没覆盖 %d 个目的地" % n)
    ck(chrome_set(main) == EXPECTED_SCREENS, "chromeOf 目的地集合与枚举不一致")
    for s in ["fun importBook(uri: Uri?", "incoming: String?", "EXTERNAL_INTENT",
              "ProduceService"]:
        ck(s in main, "外部导入/通知回跳落地丢失: " + s)
    ck(nav.library_reachable(main), "书架不再是可达目的地")
    ck("statusBarsPadding().navigationBarsPadding()" in main,
       "根 Column 没同时让开状态栏与系统手势区")
    return e

errs = check(MAIN, CHROME, "基线")

_mixer_back_lit = "Screen.MIXER -> MixerScreen(ctx, onBack = { %s })" % nav._on_back(nav._branch(MAIN, "MIXER"))
_mixer_line = re.search(r"^\s*Screen\.MIXER -> MixerScreen.*$", MAIN, re.M).group(0) + "\n"
_global_frag = nav.global_back(MAIN)[2]
INJECT = [
    ("删抽屉返回键", MAIN, "    BackHandler(enabled = drawerState.isOpen) {\n        scope.launch { drawerState.close() }\n    }\n", ""),
    ("关掉官方手势", MAIN, "gesturesEnabled = true", "gesturesEnabled = false"),
    ("恢复底栏", MAIN, "gesturesEnabled = true", "AppBottomBar(active = 0, onTab = {}, surface = Card, primary = Primary, primarySoft = PrimarySoft, sub = Sub)\n        gesturesEnabled = true"),
    ("☰ 回退成浮层菜单", CHROME, "IconButton(onClick = onMenu)", "DropdownMenu(expanded = false, onDismissRequest = {}) {}; IconButton(onClick = onMenu)"),
    ("去掉底部安全区", MAIN, ".statusBarsPadding().navigationBarsPadding()", ".statusBarsPadding()"),
    ("调音台返回写死设置页", MAIN, _mixer_back_lit,
     "Screen.MIXER -> MixerScreen(ctx, onBack = { screen = Screen.SETTINGS })"),
    ("调音台不再是独立项", CHROME, 'NAV_MIXER to "调音台",\n', ""),
    ("删一个分发分支", MAIN, _mixer_line, ""),
    ("全局回退失效", MAIN, _global_frag, "(enabled = false)"),
]
for name, which, old, new in INJECT:
    if old not in which:
        errs.append("反向注入锚点失效: " + name)
        continue
    m2, c2 = (which.replace(old, new, 1), CHROME) if which == MAIN else (MAIN, which.replace(old, new, 1))
    got = check(m2, c2, name)
    if not got:
        errs.append("反向注入没拦下: " + name)
    else:
        print("  反向注入 %-18s 拦下 %d 条" % (name, len(got)))

KEEP = [
    "app/src/main/assets/prompt_zh.txt",
    "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    "app/src/main/java/com/tingben/app/core/AudioLibrary.kt",
    "app/src/main/java/com/tingben/app/core/PlayQueue.kt",
    "app/src/main/java/com/tingben/app/core/Prefs.kt",
    "app/src/main/java/com/tingben/app/core/ProduceConfig.kt",
    "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
    "app/src/main/java/com/tingben/app/ProduceService.kt",
    "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
    "app/src/main/cpp/tingben_native.cpp",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts",
]
KEEP = [p for p in KEEP if (ROOT / p).exists()]

if not os.environ.get("CI"):
    def head(p):
        try:
            return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                           stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            return None
    for p in KEEP:
        h = head(p)
        if h is not None and h != (ROOT / p).read_bytes():
            errs.append("守恒文件被改动: " + p)
    old = head(MAIN_P)
    if old is None:
        errs.append("拿不到 HEAD 的 MainActivity")
    elif canon(MAIN) != canon(old.decode("utf-8")):
        errs.append("App() 之外的业务代码被改动")
else:
    print("  CI 环境：HEAD 即本次提交，守恒已在提交前本地核对")

if errs:
    print("\n".join("ERROR " + e for e in errs))
    sys.exit(1)
print("R46 验证 PASS：正向 %d 符号 / 旧壳 %d 项零残留 / 反向注入 %d 例 / 守恒 %d 文件 + App() 之外业务代码"
      % (len(MAIN_POS) + len(CHROME_POS), len(OLD_SHELL), len(INJECT), len(KEEP)))
