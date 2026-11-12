#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R230 · 主页右上角 ＋/齿轮撤掉、导入独占抽屉一排、设置收进抽屉左下角、
长按书名弹置顶/删除、撤「上次读到」、悬浮开关回设置页且打开即预览。

用法：python3 scripts/r230_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "chrome": rd(APP / "AppChrome.kt"),
        "shell": rd(APP / "Shell.kt"),
        "main": ui_sources.read(ROOT),
        "input": rd(APP / "InputReadUi.kt"),
        "lib": rd(APP / "LibraryUi.kt"),
        "settings": rd(APP / "SettingsUi.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    ch, sh, mn, it, lb, st = (f["chrome"], f["shell"], f["main"],
                              f["input"], f["lib"], f["settings"])
    # ① 抽屉：标题单独一行、导入独占一排、设置收进左下角
    for k in ['Text("＋ 导入一本书", fontSize = 13.sp)',
              'Button(\n                onClick = onImport,',
              'GearIcon(primary)\n                        Spacer(Modifier.width(6.dp))',
              "Box(Modifier.weight(1f)) { content() }"]:
        if k not in ch: e.append("抽屉布局缺: " + k[:52])
    # AppTopBar 与 AppDrawerSheet 各有一处；抽屉那处被摘就只剩 1
    if ch.count("onSettings: () -> Unit,") < 2:
        e.append("抽屉没接设置回调")
    if 'TextButton(onClick = onImport)' in ch:
        e.append("导入又跟标题挤回同一行")
    # ② 抽屉调用接线
    for k in ['EventLog.ui("DRAWER_SETTINGS", "open")',
              "onSettings = {", "scope.launch { drawerState.close() }"]:
        if k not in mn: e.append("抽屉设置接线缺: " + k[:52])
    # ③ 主页右上角 ＋/齿轮 撤掉
    if "showImport = true" in sh or "showSettings = true" in sh:
        e.append("主页右上角 ＋/齿轮 回潮")
    if "trailing = when (screen)" not in mn:
        e.append("顶栏 trailing 没按 screen 分发")
    # R244 · 顶栏改自绘图标，判据按符号不再绑字按钮
    for k in ["Screen.LIBRARY -> {", 'MixerIcon(Primary)',
              'TasksIcon(', "enabled = homeHasTask",
              "val homeHasTask = remember(refresh, bookImporting)"]:
        if k not in mn: e.append("顶栏首页两枚缺: " + k[:52])
    # ④ 首页内容区零常驻按钮排、零悬浮开关
    for banned in ["overlayOn", "onOverlayToggle", "悬浮·开", "悬浮·关",
                   'Text("调音", fontSize = 12.sp, color = Primary)',
                   "horizontalArrangement = Arrangement.End,\n                verticalAlignment = Alignment.CenterVertically) {"]:
        if banned in it: e.append("首页内容区回潮: " + banned[:44])
    # ⑤ 书架：长按书名弹菜单、撤废话、撤常驻动作列
    for k in ["var menuBook by remember { mutableStateOf<String?>(null) }",
              "menuBook?.let { id ->",
              "detectTapGestures(", "onLongPress = { menuBook = b.id }",
              "onTap = { onOpen(b.id) }",
              "BookStore.setPinned(c, b.id, next)",
              'EventLog.ui("BOOK_PIN_TAP", "id=${b.id.take(8)} on=$next")',
              "confirmDelete = b.id", 'Text("删除", fontSize = 13.sp, color = Warn)']:
        if k not in lb: e.append("长按菜单缺: " + k[:52])
    if '上次读到 第 ${lastRead + 1} 章' in lb:
        e.append("「上次读到 第 N 章」回潮")
    if 'Column(horizontalAlignment = Alignment.End) {' in lb:
        e.append("置顶/删除又常驻卡片右侧")
    if '"已做好 $done/$total 章"' not in lb:
        e.append("制作进度条文字被误删（要保留）")
    # ⑥ 悬浮开关回设置页 + 打开即预览
    for k in ['OverlayProgress.update(c, "", "预览", 0, 0, -1f,',
              "OverlaySource.PREVIEW",
              "打开即显示预览", "if (!it) OverlayProgress.hide(c)",
              'else if (!permOk) askPermission()']:
        if k not in st: e.append("设置页开关缺: " + k[:52])
    for banned in ["overlayOn", "from=home"]:
        if banned in mn: e.append("App 层又留一份悬浮开关: " + banned)
    # ⑦ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r230_verify.py --selftest"),
                              ("清单", f["r135"], '"r230_verify.py"'),
                              ("受管根", f["config"], "r230_verify.py")]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "scripts/r230_verify.py"):
                e.append(name + " 漏登记 r230")
        elif needle not in src: e.append(name + " 漏登记 r230")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("抽屉设置参数被摘", "chrome", "onSettings: () -> Unit,", ""),
        ("导入又挤回标题行", "chrome", 'Button(\n                onClick = onImport,',
         'TextButton(onClick = onImport)'),
        ("导入整排被摘", "chrome", 'Text("＋ 导入一本书", fontSize = 13.sp)', ""),
        ("抽屉左下设置被摘", "chrome",
         'GearIcon(primary)\n                        Spacer(Modifier.width(6.dp))', ""),
        ("抽屉设置没接线", "main", 'EventLog.ui("DRAWER_SETTINGS", "open")', ""),
        ("主页加号回潮", "shell", "Screen.LIBRARY  -> ChromeSpec(\"\", TAB_LIBRARY, showMenu = true)",
         'Screen.LIBRARY  -> ChromeSpec("", TAB_LIBRARY, showMenu = true,\n        showImport = true)'),
        ("顶栏首页分支被摘", "main", "Screen.LIBRARY -> {", ""),
        ("调音入口被摘", "main", 'MixerIcon(Primary)', ""),
        ("任务不置灰", "main", "enabled = homeHasTask", "enabled = true"),
        ("首页又长悬浮开关", "input", "val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current",
         'val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current\n    Text("悬浮·关")'),
        ("长按菜单状态被摘", "lib",
         "var menuBook by remember { mutableStateOf<String?>(null) }", ""),
        ("长按被摘", "lib", "onLongPress = { menuBook = b.id }", ""),
        ("置顶写盘被摘", "lib", "BookStore.setPinned(c, b.id, next)", ""),
        ("废话回潮", "lib", "// R230 · 「上次读到 第 N 章」撤掉，制作进度条保留",
         'Text("上次读到 第 ${lastRead + 1} 章")'),
        ("进度条被误删", "lib", '"已做好 $done/$total 章"', ""),
        ("打开即预览被摘", "settings",
         'else OverlayProgress.update(c, "", "预览", 0, 0, -1f,', ""),
        ("无权限也硬挂", "settings", "else if (!permOk) askPermission()", "else if (false) { }"),
        ("CI 漏挂", "ci", "python3 scripts/r230_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r230_verify.py"', ""),
    ]
    bad = 0
    # MixerIcon(Primary) 在合并视图里出现 2 次，只摘第一处判据仍绿、注入假生效
    FULL = {"调音入口被摘"}
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = (f[key].replace(needle, repl) if name in FULL
                  else f[key].replace(needle, repl, 1))
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R230 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R230 抽屉/顶栏布局收口 + 长按菜单 + 悬浮开关真修：PASS")
