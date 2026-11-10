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
    "onLong = { enterMulti(r.index) }",
    "IconButton(onClick = onListen",
    "IconButton(onClick = onRead",
    # 三点菜单撤掉，换成勾选态的三条退出路径
    "BackHandler(enabled = multiMode) { exitMulti() }",
    "BackHandler(enabled = segMulti) { exitSegMulti() }",
    "BackHandler(enabled = showTasks) { showTasks = false }",
    "if (multiMode) {",
    # 任务看板就地：与独立任务页同源，不另造状态口径
    'EventLog.ui("HUB_TASKS"',
    "TaskBoardDialog(",
    "TaskResume.list(c)",
    "TaskResume.realProgress(c, it)",
    "TaskResume.invalidationReason(c, it)",
    "TaskResume.remove(c, t.bookId)",
    "TaskResume.dequeue(c, t.bookId)",
    "Producer.resumeQueue(c)",
    "Producer.start(c, b, t.only, true, t.think, t.config)",
]

HUB_BAN = [
    "MoreDots(HubPrimary)",
    "private fun MoreDots(",
    "onMore = { enterMulti(r.index) })",
    "IconButton(onClick = onMore",
    # 选空就藏条 = 用户找不到取消入口（R171 修掉的老毛病）
    "if (multiMode && selected.isNotEmpty()) {",
]

MAIN_MUST = [
    'placeholder = { Text("粘贴或输入要念的正文", fontSize = 12.sp) }',
    'SettingsSection("开发者工具", "排查", open = devOpen,',
    "var devOpen by remember { mutableStateOf(false) }",
    'SettingsSection("运行与通知", "系统", collapsible = false)',
    "private fun PermEntryRow(",
    'PermEntryRow("应用详情", "系统设置")',
    'Text("导入第一本 ›", fontSize = 14.sp, color = Primary)',
]

MAIN_BAN = [
    'SettingsSection("后台运行与权限"',   # R174 系统类只留一张卡
    'Text("建好的书在左上角 ☰ 里"',
    'label = { Text("要念的正文"',
    'Text("粘贴或输入要念的正文", fontSize = 14.sp',
    'SettingsSection("启动行为"',
    'SettingsSection("通知与权限"',
]

MULTI_MUST = ['Text("取消"', 'EventLog.ui("MULTI_CANCEL_BAR"',
              "IconClose(palette.sub"]


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
            "        if (multiMode) {", "        if (multiMode && selected.isNotEmpty()) {")),
        ("任务看板被摘", "hub", lambda t: t.replace("TaskBoardDialog(", "NoBoard(")),
        ("看板不走同源数据", "hub", lambda t: t.replace("TaskResume.list(c)", "emptyList()")),
        ("取消文字被摘", "multi", lambda t: t.replace('Text("取消"', 'Text("×"')),
        ("首页废话回潮", "main", lambda t: t.replace(
            "private enum class Screen {", 'Text("建好的书在左上角 ☰ 里", fontSize = 11.sp)\nprivate enum class Screen {')),
        ("开发者工具默认展开", "main", lambda t: t.replace(
            "var devOpen by remember { mutableStateOf(false) }",
            "var devOpen by remember { mutableStateOf(true) }")),
        ("设置页又拆回两卡", "main", lambda t: t.replace(
            'SettingsSection("运行与通知", "系统", collapsible = false)',
            'SettingsSection("启动行为", "系统", collapsible = false)\n        SettingsSection("通知与权限", "系统", collapsible = false)')),
        ("应用详情又冒充权限", "main", lambda t: t.replace(
            'PermEntryRow("应用详情", "系统设置")', 'PermRow("应用详情", true, "系统设置")')),
        ("CI 漏挂", "ci", lambda t: t.replace(
            "python3 scripts/r171_verify.py --selftest", "")),
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
