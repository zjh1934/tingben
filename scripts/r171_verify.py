#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R171 · 用户实证五题：目录页三点菜单 / 勾选态退不出 / 任务看板没融进 /
设置页不合理 / 首页文本框上面有废话。

守的是「交互出口还在不在」，不是绑死某一版 UI 字面量：
  · 章行常态只有听 / 展开两枚图标，选中一律长按；三点菜单零回潮
  · 勾选态：返回键退出、选空了底部条还在、「取消」是文字不是裸 ×
  · 任务看板就地展开，数据源与独立任务页同源（TaskResume / Producer）
  · 首页输入框不许再有标题、说明书、label 三处重复
  · 设置页：开发者工具默认收起、运行与通知合并、应用详情不冒充已开启

R170 事故收口：selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r171_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
HUB = SRC / "ChapterHub.kt"
MAIN = ui_sources.source(SRC)
MULTI = SRC / "MultiSelect.kt"
CI = ROOT / ".github/workflows/ci.yml"

HUB_MUST = [
    "onLong = { if (!multiMode) menuChapter = r.index }",
    "IconButton(onClick = onListen",
    "IconButton(onClick = onRead",
    # 三点菜单撤掉，换成勾选态的三条退出路径
    "BackHandler(enabled = multiMode) { exitMulti() }",
    "BackHandler(enabled = segMulti) { exitSegMulti() }",
    "R298 · 目录页就地任务看板撤掉",
    "if (multiMode && menuChapter == null) {",
    # 任务看板就地：与独立任务页同源，不另造状态口径
    "制作选中章节",
    "R315 · 目录页就地任务浮窗整段撤掉",
    "队列 ${queued.size} · 去任务页看",
    "val queued = remember(refresh, localTick, st.running) { TaskResume.queue(c) }",
    # R334 · 收尾条 / 长按菜单两处拼章统一坏块闸 + 连点锁 + IO，
    # 不再绑死旧版主线程直调的那串字面量。
    "AudioOps.mergeChapter(c, book.id,",
    "val pendingHere = hubByChapter[i].orEmpty()",
    "mergingChapter = i",
    "if (queued.isNotEmpty()) {",
    # R243 · 「继续队列」按钮三处全砍，底层 resumeQueue 仍在 Producer。
    # 这里改判「队列数据同源」，按钮回潮由 HUB_BAN 拦，不放宽能力判据。
    "TaskResume.queue(c)",
    "LinearProgressIndicator(progress = { bookPct }",
]

HUB_BAN = [
    # R243 · 费解的「继续队列」按钮不许回潮
    'Text("继续队列"',
    "Producer.resumeQueue(c)",
    "MoreDots(HubPrimary)",
    "private fun MoreDots(",
    "onMore = { enterMulti(r.index) })",
    # R298 · 目录页就地任务看板撤掉，旧形态零回潮
    "TaskBoardDialog(", "TaskResume.list(c)",
    "TaskResume.realProgress(c, it)", "TaskResume.invalidationReason(c, it)",
    "TaskResume.remove(c, t.bookId)",
    "Producer.start(c, b, t.only, true, t.think, t.config)",
    "BackHandler(enabled = showTasks)",
    "IconButton(onClick = onMore",
    # 选空就藏条 = 用户找不到取消入口（R171 修掉的老毛病）
    "if (multiMode && selected.isNotEmpty()) {",
]

MAIN_MUST = [
    # R226 · 顶栏「任务」入口上移 App 层
    'EventLog.ui("HOME_TASKS", "open:global")',
    # R307 · 首页重绘后输入框改用 decorationBox 的空态提示，
    # 不是 TextField 的 placeholder；按现状迁移，能力判据不放宽。
    'if (text.isEmpty()) Text("粘贴或输入要念的正文",',
    'SettingsSection("开发者工具", "排查", open = devOpen,',
    "var devOpen by remember { mutableStateOf(false) }",
    'SettingsSection("运行与通知", "系统", collapsible = false)',
    "private fun PermEntryRow(",
    'PermEntryRow(UiIcon.SETTINGS, "应用详情", "系统设置")',
    # R291 · 抽屉只留一枚导入入口；这里守「导入能力仍接到调用方」
    'onImportBook = { openBookPicker() }',
]

MAIN_BAN = [
    # R291 · 空态第二枚导入入口回潮
    'Text("导入第一本 ›"',
    'SettingsSection("后台运行与权限"',   # R174 系统类只留一张卡
    'Text("建好的书在左上角 ☰ 里"',
    'label = { Text("要念的正文"',
    'Text("粘贴或输入要念的正文", fontSize = 14.sp',
    'SettingsSection("启动行为"',
    'EventLog.ui("HUB_TASKS"',
    'SettingsSection("通知与权限"',
]

# R224 · 取消从「文字 + 小图标」换成有底色的 ActionChip
MULTI_MUST = ['ActionChip("取消"', 'EventLog.ui("MULTI_CANCEL_BAR"',
              "internal fun ActionChip("]


def sources():
    return {"hub": HUB.read_text(encoding="utf-8"),
            "main": MAIN.read_text(encoding="utf-8"),
            "multi": MULTI.read_text(encoding="utf-8"),
            "ci": CI.read_text(encoding="utf-8")}


def checks(f):
    e = []
    for s in HUB_MUST:
        if s not in f["hub"]:
            e.append("目录页能力缺失: " + s[:60])
    for s in HUB_BAN:
        if s in f["hub"]:
            e.append("目录页旧形态回潮: " + s[:60])
    for s in MAIN_MUST:
        if s not in f["main"]:
            e.append("首页/设置页缺失: " + s[:60])
    for s in MAIN_BAN:
        if s in f["main"]:
            e.append("首页/设置页旧形态回潮: " + s[:60])
    for s in MULTI_MUST:
        if s not in f["multi"]:
            e.append("多选条缺失: " + s[:60])
    if "python3 scripts/r171_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r171_verify --selftest")
    return e


def selftest():
    base = sources()
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("三点菜单回潮", "hub", lambda t: t.replace(
            "private fun ChapterRow(", "private fun MoreDots(tint: Color) { }\nprivate fun ChapterRow(")),
        ("返回退出被摘", "hub", lambda t: t.replace(
            "BackHandler(enabled = multiMode) { exitMulti() }", "")),
        ("选空又藏条", "hub", lambda t: t.replace(
            "        if (multiMode && menuChapter == null) {",
            "        if (multiMode && menuChapter == null && selected.isNotEmpty()) {")),
        ("目录页看板说明被摘", "hub",
         lambda t: t.replace("R298 · 目录页就地任务看板撤掉", "")),
        ("制作按钮被摘", "hub",
         lambda t: t.replace("制作选中章节", "")),
        ("看板回潮", "hub",
         lambda t: t.replace("R315 · 目录页就地任务浮窗整段撤掉", "TaskBoardDialog(")),
        ("取消按钮被摘", "multi", lambda t: t.replace('ActionChip("取消"', 'Text("×"')),
        ("首页空态提示被摘", "main", lambda t: t.replace(
            'if (text.isEmpty()) Text("粘贴或输入要念的正文",', "")),
        ("首页废话回潮", "main", lambda t: t.replace(
            "private enum class Screen {", 'Text("建好的书在左上角 ☰ 里", fontSize = 11.sp)\nprivate enum class Screen {')),
        ("开发者工具默认展开", "main", lambda t: t.replace(
            "var devOpen by remember { mutableStateOf(false) }",
            "var devOpen by remember { mutableStateOf(true) }")),
        ("设置页又拆回两卡", "main", lambda t: t.replace(
            'SettingsSection("运行与通知", "系统", collapsible = false)',
            'SettingsSection("启动行为", "系统", collapsible = false)\n        SettingsSection("通知与权限", "系统", collapsible = false)')),
        ("应用详情又冒充权限", "main", lambda t: t.replace(
            'PermEntryRow(UiIcon.SETTINGS, "应用详情", "系统设置")', 'PermRow("应用详情", true, "系统设置")')),
        ("CI 漏挂", "ci", lambda t: t.replace(
            "python3 scripts/r171_verify.py --selftest", "")),
        ("R334 拼章坏块闸被摘", "hub", lambda t: t.replace(
            "val pendingHere = hubByChapter[i].orEmpty()", "val pendingHere = 0")),
        ("R334 拼章连点锁被摘", "hub", lambda t: t.replace(
            "mergingChapter = i", "// no lock")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not checks(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    e = checks(sources())
    if e:
        print("FAIL · R171")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R171 目录页三点/勾选态/任务看板 · 首页无废话 · 设置页重排：PASS")
