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
       'MixerIcon(Primary)',
       "nav.push(Screen.TASKS)",
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
    for k in ["MixerIcon(Primary)", "TasksIcon(Primary)"]:
        if k not in mn: e.append("顶栏没接图标: " + k[:44])
    # R352 · 停止/继续/开始不再占顶栏，改内容区一排等大按钮；注入仍逐枚拦「被摘」
    for k in ['HubActChip(primaryLabel', 'HubActChip(resumeLabel',
              'HubActChip("按顺序播放"', "fun hubPrimaryAction()"]:
        if k not in hb: e.append("目录页等大按钮排缺: " + k[:44])
    for banned in ['Text("调音", fontSize = 12.sp', 'Text("任务", fontSize = 12.sp',
                   'Text("停止", fontSize = 12.sp, color = Warn)']:
        if banned in mn: e.append("字按钮回潮: " + banned[:44])
    # ③ ChapterHub 不再单独占一排（旧右对齐行整块撤掉）
    for k in ['Text("停止", fontSize = 12.sp, color = HubWarn)',
              'Text("任务", fontSize = 12.sp, color = HubPrimary)',
              "horizontalArrangement = Arrangement.End,\n                verticalAlignment = Alignment.CenterVertically) {"]:
        if k in hb: e.append("旧右对齐单行回潮 " + k[:40])
    if "R352 · 停止 / 继续 / 开始" not in hb:
        e.append("ChapterHub 缺一排等大按钮说明")
    # ④ R298 · 目录页就地看板撤掉，任务统一从主页右上角进全局任务页
    for k in ["R298 · 目录页就地任务看板撤掉", "制作选中章节",
              "队列 ${queued.size} · 去任务页 ›"]:
        if k not in hb: e.append("目录页任务能力缺失: " + k[:46])
    for k in ["nav.push(Screen.TASKS)", 'EventLog.ui("HOME_TASKS", "open:global")',
              "screen = Screen.TASKS"]:
        if k not in mn: e.append("全局任务页链路缺失: " + k[:46])
    for banned in ["tasksRequest", "hubTasksTick", "TaskBoardDialog(",
                   'EventLog.ui("HUB_TASKS_OPEN"', "openTasksOnStart"]:
        if banned in hb or banned in mn:
            e.append("目录页就地看板回潮: " + banned[:40])
    # ⑤ 收尾反馈与节流不丢
    # R356 · 停止/继续标签搬进等大按钮排后仍须在（加严，不放宽）
    if 'bookRunning -> "停止制作"' not in hb: e.append("停止按钮标签缺")
    if 'stalledTask -> "继续制作"' not in hb: e.append("继续按钮标签缺")
    for k in ["Producer.isBusyForBook(book.id)",
              'EventLog.ui("HUB_RESUME_BLOCKED"',
              "if (now - lastHubActionMs < 800L) return",
              "onResumeTask()"]:
        if k not in hb: e.append("续跑保护缺失: " + k[:48])
    if hb.count("if (now - lastHubActionMs < 800L) return") != 1:
        e.append("按钮排节流不齐")
    # ⑥ 日志仍脱敏：书名原文不进日志
    if 'EventLog.ui("HUB_STOP", "book=' in hb:
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
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "scripts/r226_verify.py"):
                e.append(name + " 漏登记 r226")
        elif needle not in src: e.append(name + " 漏登记 r226")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("槽被删", "chrome", "trailing: @Composable RowScope.() -> Unit = {}", ""),
        ("槽没调用", "chrome", "        trailing()\n", ""),
        ("图标被摘", "chrome", "internal fun MixerIcon(", ""),
        ("整条 trailing 被摘", "main", "trailing = when (screen)", "trailing = {}"),
        ("停止按钮被摘", "hub", 'bookRunning -> "停止制作"', ""),
        ("继续按钮被摘", "hub", 'stalledTask -> "继续制作"', ""),
        ("全局任务入口被摘", "main", "nav.push(Screen.TASKS)", ""),
        ("齿轮被摘", "main", "IconButton(onClick = { nav.push(Screen.PRODUCE) }", ""),
        ("书名被搬下顶栏", "main", "chrome.copy(title = chapterBookTitle)", 'chrome.copy(title = "")'),
        ("按钮排被摘", "hub", "HubActChip(primaryLabel", ""),
        ("目录页任务能力被摘", "hub", "队列 ${queued.size} · 去任务页 ›", ""),
        ("任务页落地被摘", "main", 'EventLog.ui("HOME_TASKS", "open:global")', ""),
        ("目录页看板回潮", "hub", "R298 · 目录页就地任务看板撤掉", "TaskBoardDialog("),
        ("收尾判断被摘", "hub", "Producer.isBusyForBook(book.id)", "true"),
        ("节流被摘", "hub", "if (now - lastHubActionMs < 800L) return",
         "if (false) return"),
        ("日志脱敏回潮", "hub", 'EventLog.ui("HUB_STOP", "id=${book.id.take(8)}")',
         'EventLog.ui("HUB_STOP", "book=${book.title}")'),
        ("归因又写回用户口径", "agents", "**工程现状**，不是用户下达的指令",
         "用户已授权，按用户约束"),
        ("CI 漏挂", "ci", "python3 scripts/r226_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r226_verify.py"', ""),
    ]
    bad = 0
    # R356 · 全局任务入口 / 收尾判断同串多处，只摘第一处判据仍绿，全量替换（更严）
    FULL = {"全局任务入口被摘", "收尾判断被摘"}
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = (f[key].replace(needle, repl) if name in FULL
                               else f[key].replace(needle, repl, 1))
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
