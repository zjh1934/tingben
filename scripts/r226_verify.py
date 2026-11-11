#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R226 · 目录页三枚操作并入系统顶栏书名同一排；选型归因更正。

用法：python3 scripts/r226_verify.py [--selftest]
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "chrome": rd(APP / "AppChrome.kt"),
        "main": ui_sources.source(APP).read_text(encoding="utf-8"),
        "hub": rd(APP / "ChapterHub.kt"),
        "agents": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

# 三枚按钮必须出现在系统顶栏（合并视图），且不再单独占内容区一排
TOP = ["trailing = when (screen)",
       'IconButton(onClick = { hubStop() }',
       'IconButton(onClick = { hubResume() }',
       "hubTasksTick++",
       'IconButton(onClick = { nav.push(Screen.PRODUCE) }']
# 书名仍在系统顶栏，不能被搬下来
TITLE = ["chrome.copy(title = chapterBookTitle)", "spec = shownChrome,"]

def check(f):
    e = []
    ch, mn, hb, ag = f["chrome"], f["main"], f["hub"], f["agents"]
    # ① 顶栏槽
    if "trailing: @Composable RowScope.() -> Unit = {}" not in ch:
        e.append("AppTopBar 缺 trailing 槽")
    if "        trailing()\n" not in ch:
        e.append("AppTopBar 没调用 trailing()")
    # ② 三枚按钮在顶栏、与书名同一排
    for k in TOP:
        if k not in mn: e.append("顶栏缺: " + k[:54])
    for k in TITLE:
        if k not in mn: e.append("书名没留在系统顶栏: " + k[:50])
    # R244 · 顶栏改代码自绘图标，界面不再出现字按钮；图标真源在 AppChrome.kt
    for k in ["internal fun MixerIcon(", "internal fun TasksIcon(",
              "internal fun StopIcon(", "internal fun PlayIcon("]:
        if k not in ch: e.append("自绘图标缺: " + k[:44])
    for k in ["MixerIcon(Primary)", "TasksIcon(Primary)", "StopIcon(Warn)", "PlayIcon(Primary)"]:
        if k not in mn: e.append("顶栏没接图标: " + k[:44])
    for banned in ['Text("调音", fontSize = 12.sp', 'Text("任务", fontSize = 12.sp',
                   'Text("停止", fontSize = 12.sp, color = Warn)']:
        if banned in mn: e.append("字按钮回潮: " + banned[:44])
    # ③ ChapterHub 不再单独占一排（旧右对齐行整块撤掉）
    for k in ['Text("停止", fontSize = 12.sp, color = HubWarn)',
              'Text("任务", fontSize = 12.sp, color = HubPrimary)',
              "fun hubStop()", "fun hubResume()",
              "horizontalArrangement = Arrangement.End,\n                verticalAlignment = Alignment.CenterVertically) {"]:
        if k in hb: e.append("R226 回潮：三按钮又回到内容区单独一排 " + k[:40])
    if "R226 · 三枚操作已上移到系统顶栏" not in hb:
        e.append("ChapterHub 缺上移说明")
    # ④ 任务看板仍能就地打开（计数器链路，不用会误弹的布尔复位）
    for k in ["tasksRequest: Int = 0",
              'LaunchedEffect(tasksRequest)',
              'EventLog.ui("HUB_TASKS_OPEN", "from=topbar")']:
        if k not in hb: e.append("ChapterHub 缺任务入口链路: " + k[:46])
    if "tasksRequest = hubTasksTick" not in mn:
        e.append("App 层没把 tasksRequest 传给目录页")
    if "var hubTasksTick by rememberSaveable" not in mn:
        e.append("计数器状态缺失")
    # ⑤ 收尾反馈与节流不丢
    for k in ["bookId?.let { Producer.isBusyForBook(it) }",
              'EventLog.ui("HUB_RESUME_BLOCKED"',
              "if (now - lastHubTapMs >= 800L) {",
              "fun onResumeTask()"]:
        if k not in mn: e.append("续跑保护缺失: " + k[:48])
    if mn.count("if (now - lastHubTapMs >= 800L) {") != 2:
        e.append("两处节流不齐")
    # ⑥ 日志仍脱敏：书名原文不进日志
    if 'EventLog.ui("HUB_STOP", "book=${book.id}")' in mn:
        e.append("R225 脱敏回潮：停止日志又写书名")
    # ⑦ 归因更正：工程现状不是用户指令
    if "**工程现状**，不是用户下达的指令" not in ag:
        e.append("AGENTS 仍把零依赖/无网络当用户口径")
    if "用户已授权" in ag and "不引 Media3" in ag:
        e.append("旧归因残留：拿用户授权当理由拒绝依赖")
    # ⑧ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r226_verify.py --selftest"),
                              ("清单", f["r135"], '"r226_verify.py"'),
                              ("受管根", f["config"], "r226_verify.py")]:
        if needle not in src: e.append(name + " 漏登记 r226")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("槽被删", "chrome", "trailing: @Composable RowScope.() -> Unit = {}", ""),
        ("槽没调用", "chrome", "        trailing()\n", ""),
        ("图标被摘", "chrome", "internal fun MixerIcon(", ""),
        ("整条 trailing 被摘", "main", "trailing = when (screen)", "trailing = {}"),
        ("停止按钮被摘", "main", "IconButton(onClick = { hubStop() }", ""),
        ("继续按钮被摘", "main", "IconButton(onClick = { hubResume() }", ""),
        ("任务计数被摘", "main", "hubTasksTick++", ""),
        ("齿轮被摘", "main", "IconButton(onClick = { nav.push(Screen.PRODUCE) }", ""),
        ("书名被搬下顶栏", "main", "chrome.copy(title = chapterBookTitle)", 'chrome.copy(title = "")'),
        ("三按钮回内容区", "hub", "// R226 · 三枚操作已上移到系统顶栏",
         'Text("停止", fontSize = 12.sp, color = HubWarn)'),
        ("任务链路被摘", "hub", "LaunchedEffect(tasksRequest)", ""),
        ("计数没传给目录页", "main", "tasksRequest = hubTasksTick", ""),
        ("收尾判断被摘", "main", "bookId?.let { Producer.isBusyForBook(it) }", "true"),
        ("节流被摘", "main", "if (now - lastHubTapMs >= 800L) {", "if (true) {"),
        ("日志脱敏回潮", "main", 'EventLog.ui("HUB_STOP", "id=${bookId?.take(8)}")',
         'EventLog.ui("HUB_STOP", "book=${book.id}")'),
        ("归因又写回用户口径", "agents", "**工程现状**，不是用户下达的指令",
         "用户已授权，按用户约束"),
        ("CI 漏挂", "ci", "python3 scripts/r226_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r226_verify.py"', ""),
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
        print("R226 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R226 三按钮并入顶栏书名同排 / 任务链路 / 选型归因更正：PASS")
