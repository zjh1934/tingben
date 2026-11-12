#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R218 · 目录页一批真机反馈的守门。

落地：顶栏写书名、重新分章收进制作设置、制作设置页收敛、
章名点开展开 + 小眼睛/小耳机、两条进度条常驻、展开带动效、撤段数文案。
用法：python3 scripts/r218_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "shell": rd(APP / "Shell.kt"),
        "hub": rd(APP / "ChapterHub.kt"),
        "produce": rd(APP / "ProduceUi.kt"),
        "merged": ui_sources.read(ROOT),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    sh, hb, pr, mg = f["shell"], f["hub"], f["produce"], f["merged"]
    # ① 顶栏写书名，不写「目录」
    for k in ['Screen.CHAPTERS -> ChromeSpec("", TAB_LIBRARY, canBack = true)',
              'Screen.PRODUCE  -> ChromeSpec("制作设置"']:
        if k not in sh: e.append("顶栏规格缺: " + k[:60])
    for k in ["val chapterBookTitle = remember(bookId, refresh)",
              "chrome.copy(title = chapterBookTitle)",
              "val shownChrome = if (screen == Screen.CHAPTERS)",
              "spec = shownChrome,"]:
        if k not in mg: e.append("App 层书名覆盖缺: " + k[:60])
    # ② 重新分章不占顶栏、能力不丢
    for k in ["var resplitTick by rememberSaveable", "resplitRequest = resplitTick",
              "onResplit = { nav.pop(); resplitTick++ })",
              "resplitRequest: Int = 0", "resplitRequest > 0)",
              'EventLog.ui("RESPLIT_OPEN"']:
        if k not in mg + hb: e.append("重分章链路缺: " + k[:60])
    if 'Text("重新分章 ›", fontSize = 12.sp, color = HubPrimary)' in hb:
        e.append("重新分章又回到目录页顶栏")
    if 'Text("重新分章 ›"' not in pr:
        e.append("制作设置页的重新分章次要入口被摘")
    # ③ 制作设置页收敛
    for k in ["onResplit: () -> Unit = {}", 'Text("← 目录")',
              'ExpandGroup("① 做什么", "")', 'ExpandGroup("② 怎么跑", "")',
              '"每段约 1–3 分钟，块越多等得越久"',
              '"从头重做（清掉已做的块）"']:
        if k not in pr: e.append("制作设置页缺: " + k[:60])
    for k in ['"制作范围"', "Checkbox(checked = ch.index in selected",
              '"合成前调音"', "showAllChapters", 'Text("← 书架")',
              'ExpandGroup("② 怎么念"', 'OptionRow("朗读档位"',
              'OptionRow("语速倾向"', 'OptionRow("情绪强度"']:
        if k in pr: e.append("制作设置页画蛇添足的块回潮: " + k[:40])
    # ④ 章行：小眼睛 + 小耳机；R264 制作章恒定展开、无展开收起控件
    for k in ["onRead: () -> Unit, onListen: () -> Unit",
              "IconButton(onClick = onRead", "EyeIcon(HubPrimary)",
              "IconButton(onClick = onListen", "onRead = { openReader(r.index) }",
              "val liveChapter: Int? = if (bookRunning) makingChapter else null"]:
        if k not in hb: e.append("章行缺: " + k[:60])
    # R264 · 展开/收起整套旧形态不许回潮
    for k in ["var expandedChapter", "val willOpen =", "HUB_CH_EXPAND",
              "liveChapters", "AnimatedVisibility(", "private fun ChevronIcon("]:
        if k in hb: e.append("展开/收起旧形态回潮: " + k[:40])
    # ⑤ 两条进度条常驻 + 制作章小段区常驻 + 段数文案撤掉
    for k in ["val chapterPctNow =", "LinearProgressIndicator(progress = { bookPct }",
              "LinearProgressIndicator(progress = { chapterPctNow }",
              "modifier = Modifier.fillMaxWidth().height(22.dp),",
              'item(key = "seg_head_${r.index}")',
              "if (r.index == liveChapter) {",
              "items(chapterSegs.chunked(SEG_GRID_COLUMNS),"]:
        if k not in hb: e.append("进度/展开缺: " + k[:60])
    for k in ['"本章 ${segs.size} 小段"', "小段已生成", "还没有小段",
              '"已完成 ${(bookPct * 100).toInt()}%"', '"整体 ${(bookPct * 100).toInt()}%"']:
        if k in hb: e.append("段数文案回潮: " + k[:40])
    # ⑥ 顶栏只剩一枚齿轮 + 勾选态文字入口
    # R304 · 底部菜单不再摆制作设置；出现即回潮
    if hb.count('"制作设置 ›"') != 0:
        e.append("底部菜单不该再有制作设置入口")
    # R226 · 常态齿轮上移系统顶栏（合并视图）
    if 'Text("⚙", fontSize = 16.sp' not in mg:
        e.append("常态顶栏齿轮入口被摘")
    # ⑦ 登记
    if "python3 scripts/r218_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r218")
    if '"r218_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r218")
    from r45_common import scope_indexed
    if not scope_indexed(f["config"], "scripts/r218_verify.py"):
        e.append("新守门没登记进受管根")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("书名覆盖被摘", "merged", "chrome.copy(title = chapterBookTitle)", ""),
        ("顶栏又写目录", "shell",
         'Screen.CHAPTERS -> ChromeSpec("", TAB_LIBRARY, canBack = true)',
         'Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY, canBack = true)'),
        ("重新分章回顶栏", "merged", 'Text("⚙", fontSize = 16.sp',
         'Text("重新分章 ›", fontSize = 12.sp, color = HubPrimary)'),
        ("重分章链路断", "merged", "onResplit = { nav.pop(); resplitTick++ })", ""),
        ("制作范围勾选回潮", "produce", 'ExpandGroup("① 做什么", "")',
         'ExpandGroup("① 做什么", "") { Text("制作范围")'),
        ("调音卡片回潮", "produce", 'Text("← 目录")', 'Text("合成前调音")'),
        ("眼睛被摘", "hub", "IconButton(onClick = onRead", ""),
        ("点章名又进阅读", "hub", "onRead = { openReader(r.index) }",
         "onTap = { openReader(r.index) }"),
        ("本章进度条被摘", "hub", "LinearProgressIndicator(progress = { chapterPctNow }", ""),
        ("制作章恒定展开被摘", "hub", "if (r.index == liveChapter) {", ""),
        ("展开收起态回潮", "hub",
         "val liveChapter: Int? = if (bookRunning) makingChapter else null",
         "var expandedChapter by remember(book.id) { mutableStateOf<Int?>(null) }"),
        ("段数文案回潮", "hub", 'item(key = "seg_head_${r.index}")', '"本章 12 小段"'),
        ("齿轮被摘", "merged", 'Text("⚙", fontSize = 16.sp', ""),
        ("CI 漏挂", "ci", "python3 scripts/r218_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r218_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R218 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R218 目录页顶栏书名 / 制作设置收敛 / 双眼双耳 / 双进度常驻 / 展开动效：PASS")
