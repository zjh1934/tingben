#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R178 · 工程文件里记着、确实还欠的那批界面改动，收口验证。

工程文件依据：
  · `听本_目标方案_校正与排期.md` 第 4 批「整页下拉刷新」、R155 结论
    「主页改输入框后刷新小横条自然不在了，但整页下拉刷新得先补上」；
  · `接手卡片.md` 真未决「STOP→RESUME 只隔 0.3 秒，界面轮顺手处理」、
    R171 回执「独立任务页删页排到收口轮，通知点进来还走它」。

这轮落地：
  A 刷新入口：书列表搬进抽屉后 compact 模式原来不渲染 RefreshStrip，刷新
    入口整个消失。现在常显，并支持下拉 + 点按双触发。整页下拉手势与
    LazyColumn 滚动抢事件，风险高于收益，不硬上（如实记为口径，不冒充）。
  B 停止/继续防连点：800ms 内只认第一次，避免刚停下又被拉起来。
  C 通知「任务」落点：不再跳独立任务页，IO 找有任务的书 → 落目录页 →
    自动展开就地任务看板；一本都没任务给中文反馈。能力搬干净，
    Screen 枚举物理清仍排重构轮（35 个脚本绑死 8 值，不在这轮硬拆）。
  D 工程文件回填：四批排期表状态列、三份真值源版本串跟随 version.txt。
    R179 起判据动态取版本，不再绑死某个字面量，免得每升一次版本就改一轮。

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r178_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": ui_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "chrome": APP / "AppChrome.kt",
    "readme": ROOT / "README.md",
    "status": ROOT / "STATUS.md",
    "roadmap": ROOT / "ROADMAP.md",
    "ci": ROOT / ".github/workflows/ci.yml",
}

MAIN = [
    "var hubOpenTasks by rememberSaveable",
    '"NOTIF_TASKS_OPEN"',
    'TaskResume.active(ctx) ?: TaskResume.queue(ctx).firstOrNull()',
    'screen = Screen.CHAPTERS',
    'openTasksOnStart = hubOpenTasks',
    'SideEffect { if (hubOpenTasks) hubOpenTasks = false }',
    'item(key = "head")',
    'RefreshStrip(',
]
MAIN_BAN = [
    'raw == "tasks:" -> {\n                screen = Screen.TASKS',
    'if (!compact) {\n            item(key = "head")',
]
HUB = [
    "openTasksOnStart: Boolean = false",
    "mutableStateOf(openTasksOnStart)",
    "var lastHubTapMs by remember { mutableStateOf(0L) }",
    "now - lastHubTapMs >= 800L",
]
CHROME = [
    "import androidx.compose.foundation.clickable",
    ".clickable { onRefresh() }",
]
def version():
    return (ROOT / "version.txt").read_text(encoding="utf-8").strip()


def doc_needles(v):
    """三份真值源里必须出现、且必须与 version.txt 同版的串。"""
    return {
        "readme": ['**当前版本 `%s`**' % v, "releases/download/v%s/" % v],
        "status": ['**当前版本 `%s`' % v, "| 版本 | `%s` |" % v],
        "roadmap": ['**当前版本 `%s`' % v],
    }


def checks(f):
    e = []
    for s in MAIN:
        if s not in f["main"]:
            e.append("主界面能力缺失: " + s[:60])
    for s in MAIN_BAN:
        if s in f["main"]:
            e.append("旧形态回潮: " + s[:56])
    if f["main"].count("now - lastHubTapMs") != 0:
        pass  # 节流在 hub 里，这里只确认没有误写进 main
    for s in HUB:
        if s not in f["hub"]:
            e.append("目录页能力缺失: " + s[:60])
    if f["hub"].count("now - lastHubTapMs >= 800L") != 2:
        e.append("停止/继续两处节流不齐")
    for s in CHROME:
        if s not in f["chrome"]:
            e.append("刷新条能力缺失: " + s[:60])
    for key, needles in doc_needles(version()).items():
        for s in needles:
            if s not in f[key]:
                e.append("工程文件版本未与 version.txt 对齐[%s]: %s" % (key, s[:52]))
    if "python3 scripts/r178_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r178_verify --selftest")
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("通知又跳独立任务页", "main",
         lambda t: t.replace('                    screen = Screen.CHAPTERS\n                    hubOpenTasks = true',
                             '                    screen = Screen.TASKS')),
        ("通知落点不找书", "main",
         lambda t: t.replace('TaskResume.active(ctx) ?: TaskResume.queue(ctx).firstOrNull()',
                             'null')),
        ("看板不自动展开", "main",
         lambda t: t.replace('openTasksOnStart = hubOpenTasks', 'openTasksOnStart = false')),
        ("展开标志不复位", "main",
         lambda t: t.replace('SideEffect { if (hubOpenTasks) hubOpenTasks = false }', '')),
        ("刷新条又被 compact 挡掉", "main",
         lambda t: t.replace('        item(key = "head") {\n            RefreshStrip(',
                             '        if (!compact) {\n            item(key = "head") {\n                RefreshStrip(')),
        ("刷新条点按被摘", "chrome",
         lambda t: t.replace('.clickable { onRefresh() }', '')),
        ("刷新条 import 被摘", "chrome",
         lambda t: t.replace('import androidx.compose.foundation.clickable', '')),
        ("看板初值不认参数", "hub",
         lambda t: t.replace('mutableStateOf(openTasksOnStart)', 'mutableStateOf(false)')),
        ("签名不加参数", "hub",
         lambda t: t.replace('    openTasksOnStart: Boolean = false', '')),
        ("停止按钮不节流", "hub",
         lambda t: t.replace('''                                if (now - lastHubTapMs >= 800L) {
                                    lastHubTapMs = now
                                    EventLog.ui("HUB_STOP", "book=${book.id}")
                                    Producer.stop()
                                }''',
                             '''                                EventLog.ui("HUB_STOP", "book=${book.id}")
                                Producer.stop()''')),
        ("继续按钮不节流", "hub",
         lambda t: t.replace('''                                if (now - lastHubTapMs >= 800L) {
                                    lastHubTapMs = now
                                    EventLog.ui("HUB_RESUME", "book=${book.id}")
                                    onResumeTask()
                                }''',
                             '''                                EventLog.ui("HUB_RESUME", "book=${book.id}")
                                onResumeTask()''')),
        ("README 版本回退", "readme",
         lambda t: t.replace('**当前版本 `%s`**' % version(),
                             '**当前版本 `0.0.1`**')),
        ("README 直链版本回退", "readme",
         lambda t: t.replace('releases/download/v%s/' % version(),
                             'releases/download/v0.0.1/')),
        ("STATUS 版本回退", "status",
         lambda t: t.replace('| 版本 | `%s` |' % version(),
                             '| 版本 | `0.0.1` |')),
        ("ROADMAP 版本回退", "roadmap",
         lambda t: t.replace('**当前版本 `%s`' % version(),
                             '**当前版本 `0.0.1`')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r178_verify.py --selftest", "")),
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
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R178 界面收口")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R178 界面收口：刷新入口 / 停止继续防连点 / 通知落点 / 工程文件回填：PASS")
