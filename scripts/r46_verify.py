#!/usr/bin/env python3
"""R46 · V1 抽屉壳验证：正向形态 / 旧壳零残留 / 反向注入 / 业务守恒。

只依赖标准库。CI 挂独立 step；本地 `python3 scripts/r46_verify.py`。
口径：抽屉是一整页不是浮层；返回键优先关抽屉；底栏 Tab 收进抽屉；
App() 之外的业务代码、Screen 9 分支、外部导入落地、通知回跳全部与 HEAD 一致。
"""
import os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from r45_common import canon

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN_P = "app/src/main/java/com/tingben/app/MainActivity.kt"
CHROME_P = "app/src/main/java/com/tingben/app/AppChrome.kt"
MAIN = (ROOT / MAIN_P).read_text(encoding="utf-8")
CHROME = (ROOT / CHROME_P).read_text(encoding="utf-8")

# ── 1) 正向形态断言（查定义/调用形态，不查裸名字） ─────────────────────
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
    # R51 · 覆盖式 → 联动式；其余抽屉行为（手势 / 返回键 / 7 目的地）守恒不变
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
    "var drawerReturn by remember { mutableStateOf(Screen.LIBRARY) }",
    "Screen.CAST -> CastScreen(ctx, onBack = { screen = drawerReturn })",
    "Screen.MIXER -> MixerScreen(ctx, onBack = { screen = drawerReturn })",
    "BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }",
    "var refresh by remember { mutableStateOf(0) }",
    "var modelsFrom by remember { mutableStateOf(Screen.LIBRARY) }",
]
OLD_SHELL = ["AppBottomBar(", "DropdownMenu(", "MENU_ITEMS", "TOP_TABS", "AppTab(",
             "onMenu = { idx ->", "ModalNavigationDrawer("]

# 守恒：Screen 枚举 9 值 / when 分发 9 分支 / chromeOf 9 映射 / 外部导入落地
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

def chrome_count(s):
    m = re.search(r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}", s, re.S)
    return len(set(re.findall(r"Screen\.([A-Z_]+)\s*->", m.group(1)))) if m else -1

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
    # 抽屉 BackHandler 必须排在原 BackHandler 之后（LIFO 优先）
    a = main.find("BackHandler(enabled = screen != Screen.LIBRARY)")
    b = main.find("BackHandler(enabled = drawerState.isOpen)")
    ck(a >= 0 and b > a, "抽屉 BackHandler 没排在原 BackHandler 之后")
    ck(enum_count(main) == 9, "Screen 枚举不是 9 值")
    ck(dispatch_count(main) == 9, "when(screen) 分发不是 9 分支")
    ck(chrome_count(main) == 9, "chromeOf 没覆盖 9 个目的地")
    for s in ["fun importBook(uri: Uri?", "incoming: String?", "EXTERNAL_INTENT",
              "ProduceService", "screen = Screen.LIBRARY"]:
        ck(s in main, "外部导入/通知回跳落地丢失: " + s)
    # 底部安全区：statusBarsPadding 与 navigationBarsPadding 都在根 Column 上
    ck("statusBarsPadding().navigationBarsPadding()" in main,
       "根 Column 没同时让开状态栏与系统手势区")
    return e

errs = check(MAIN, CHROME, "基线")

# ── 2) 反向注入：任一处改坏，断言必须响 ────────────────────────────────
INJECT = [
    ("删抽屉返回键", MAIN, "    BackHandler(enabled = drawerState.isOpen) {\n        scope.launch { drawerState.close() }\n    }\n", ""),
    ("关掉官方手势", MAIN, "gesturesEnabled = true", "gesturesEnabled = false"),
    ("恢复底栏", MAIN, "        // V1 · 3 个顶层 Tab 已收进左侧抽屉", "        AppBottomBar(active = 0, onTab = {}, surface = Card, primary = Primary, primarySoft = PrimarySoft, sub = Sub)\n        // V1 · 3 个顶层 Tab 已收进左侧抽屉"),
    ("☰ 回退成浮层菜单", CHROME, "IconButton(onClick = onMenu)", "DropdownMenu(expanded = false, onDismissRequest = {}) {}; IconButton(onClick = onMenu)"),
    ("去掉底部安全区", MAIN, ".statusBarsPadding().navigationBarsPadding()", ".statusBarsPadding()"),
    ("调音台返回写死设置页", MAIN, "Screen.MIXER -> MixerScreen(ctx, onBack = { screen = drawerReturn })", "Screen.MIXER -> MixerScreen(ctx, onBack = { screen = Screen.SETTINGS })"),
    ("调音台不再是独立项", CHROME, 'NAV_MIXER to "调音台",\n', ""),
    ("删一个分发分支", MAIN, "                Screen.CAST -> CastScreen(ctx, onBack = { screen = drawerReturn })\n", ""),
    ("抽屉 BackHandler 排到原条之前", MAIN,
     "    BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }\n    // V1 · 后注册的 callback 优先：抽屉开着时先收抽屉，不误退回书架。\n    BackHandler(enabled = drawerState.isOpen) {\n        scope.launch { drawerState.close() }\n    }",
     "    BackHandler(enabled = drawerState.isOpen) {\n        scope.launch { drawerState.close() }\n    }\n    BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }"),
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

# ── 3) 业务守恒：App() 之外与 HEAD 逐行一致 + 关键文件逐字节一致 ───────
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
