#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R209 · 1.1.22 真机日志实证三条收口的守门。

日志 tingben_diag_20261110_050933.md：
  A 05:06:52~57 点停止后连点 4 次「继续」全静默（Producer.start 的 if(busy) return）
  B 05:07:41 环形缓冲里 CRASH|UI_ERROR，但「最近一次崩溃（空）」
  C 05:07:41 章节表没写进磁盘，却没留下可定位的线索

界面建议「把这个框做小点」不在本守门范围，等后续批次一起收。
用法：python3 scripts/r209_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
SCRIPTS = ROOT / "scripts"
SELF = SCRIPTS / "r209_verify.py"

def files():
    import sys
    sys.path.insert(0, str(SCRIPTS))
    import ui_sources
    return {
        "merged": ui_sources.read(ROOT),
        "hub": (APP / "ChapterHub.kt").read_text(encoding="utf-8", errors="replace"),
        "ui": (APP / "core/UiErrors.kt").read_text(encoding="utf-8", errors="replace"),
        "plan": (APP / "core/ChapterPlan.kt").read_text(encoding="utf-8", errors="replace"),
        "log": (APP / "diag/EventLog.kt").read_text(encoding="utf-8", errors="replace"),
        "producer": (APP / "core/Producer.kt").read_text(encoding="utf-8", errors="replace"),
        "self": SELF.read_text(encoding="utf-8", errors="replace"),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
    }

def check(f):
    e = []
    # A · 收尾窗口内必须给用户反馈，不许静默调 onResumeTask
    # R226 · 收尾判断随三按钮上移 App 层，统一在合并视图里按符号定位
    if "Producer.isBusyForBook(" not in f["merged"]:
        e.append("续跑按钮没收尾窗口判断")
    if "HUB_RESUME_BLOCKED" not in f["merged"]:
        e.append("被拦时没记账")
    if re.search(r'if \(busy\) return', f["producer"]) is None:
        e.append("Producer.start 的静默早退没了（前置判断才成立）")
    # A 的顺序：判断必须在 onResumeTask() 之前
    k = f["merged"].find("fun hubResume()")
    block = f["merged"][k:k + 900] if k >= 0 else ""
    i, j = block.find("Producer.isBusyForBook("), block.find("onResumeTask()")
    if i < 0 or j < 0 or i > j:
        e.append("收尾判断没排在续跑之前")
    if "稍等再继续" not in block:
        e.append("被拦时没给用户中文反馈")
    # B · UI 提示不是崩溃，但全量错误汇总仍捞得到
    if "EventLog.Kind.AUDIT" not in f["ui"]:
        e.append("UiErrors 没改成 AUDIT 类")
    if "EventLog.Level.ERROR" not in f["ui"]:
        e.append("UiErrors 没标 ERROR 级，allErrors 会漏")
    if "EventLog.Kind.CRASH" in f["ui"]:
        e.append("UiErrors 又退回 CRASH 类，污染崩溃缓冲")
    if '" | ERROR | "' not in f["log"]:
        e.append("allErrors 不再按 ERROR 捞，错误汇总会丢")
    # C · 写盘失败带现场状态，但不改 AtomicJson 写入语义
    for k in ["目标存在=", "残留=", "章数="]:
        if k not in f["plan"]:
            e.append("章节表失败消息缺线索: " + k)
    if "AtomicJson.write" not in f["plan"]:
        e.append("ChapterPlan 不再走 AtomicJson")
    # 登记
    if "python3 scripts/r209_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r209")
    if '"r209_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r209")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("收尾判断被摘", "merged", "bookId?.let { Producer.isBusyForBook(it) }", "true"),
        ("被拦不记账", "merged", "HUB_RESUME_BLOCKED", "HUB_RESUME"),
        ("被拦不给反馈", "merged",
         '                Toast.makeText(ctx, "已点停止，正在收尾这一段，稍等再继续"',
         '                Toast.makeText(ctx, "已点停止，正在收尾这一段"'),
        ("UI 错误又记成崩溃", "ui", "EventLog.Kind.AUDIT", "EventLog.Kind.CRASH"),
        ("UI 错误丢了 ERROR 级", "ui", "level = EventLog.Level.ERROR", ""),
        ("错误汇总不按 ERROR 捞", "log", '" | ERROR | "', '" | NOPE | "'),
        ("写盘失败线索被摘", "plan", "目标存在=", ""),
        ("CI 漏挂", "ci", "python3 scripts/r209_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r209_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = f[key].replace(needle, repl, 1)
        got = check(g)
        if got:
            print("  反向注入 %-24s 拦下 %d 条" % (name, len(got)))
        else:
            print("  反向注入漏放: " + name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(files())
    if e:
        print("R209 FAIL", len(e))
        for x in e: print(" -", x)
        return 1
    print("R209 真机日志三条收口（续跑收尾反馈 / 崩溃缓冲干净 / 写盘失败留线索）：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
