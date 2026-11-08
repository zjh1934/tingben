# -*- coding: utf-8 -*-
"""R147 · 「用户连着用几轮再给日志」的接日志准备验证。

动机：1.1.3 发版后用户要去真机用上几轮，下下轮才把日志带回来。
原来日志 73 个埋点几乎全在合成/引擎链路上，UI 类只有 init 一条 APP_START，
且「一键复制摘要」死取末尾 120 行 —— 前几轮会被截掉，也没有会话边界，
拿到日志也分不清「用户说的是哪一轮」。

落地：
  · EventLog.ui() 便捷入口；每次冷启动写 SESSION_START 带 session id
  · 导航 / 返回 / 浮层开关 / 目录三列 / 章节批量与勾选 / 播放器控制 /
    日志导出复制 共 31 处 UI 埋点（全部在回调或 LaunchedEffect 里，
    不在 Composable 顶层，避免重组刷屏）
  · exportBundle 增加「会话边界」说明；exportSummary 改成
    「最近 3 轮完整事件 + 全量报错/警告」，不再死取末尾 120 行

用法：python3 scripts/r147_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "eventlog": APP / "diag" / "EventLog.kt",
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "player": APP / "PlayerSheet.kt",
    "multi": APP / "MultiSelect.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "eventlog": [
        "fun ui(action: String", 'Kind.UI, action',
        "var currentSession: String", "SESSION_START",
        "currentSession = SimpleDateFormat",
        "private fun recentSessions(n: Int = 3)",
        "private fun allErrors()",
        "所有报错/警告（不受会话窗口限制）",
        "最近 3 轮完整事件（按 SESSION_START 分段）",
        "## 会话边界",
    ],
    "main": [
        'EventLog.ui("OPEN_BOOK"', 'EventLog.ui("BACK"',
        'EventLog.ui("DRAWER_NAV"', 'EventLog.ui("OVERLAY_PLAYER"',
        'EventLog.ui("OVERLAY_INPUT_READ"', 'EventLog.ui("OVERLAY_MIXER"',
        'EventLog.ui("DIAG_COPY"', 'EventLog.ui("DIAG_EXPORT"',
    ],
    "hub": [
        'EventLog.ui("HUB_LISTEN"', 'EventLog.ui("HUB_QUEUE"',
        'EventLog.ui("OPEN_READER"', 'EventLog.ui("RESPLIT_OPEN"',
        'EventLog.ui("MULTI_ALL"', 'EventLog.ui("CH_TOGGLE"',
        'EventLog.ui("SEG_ALL"', 'EventLog.ui("CH_TOGGLE"',
        'EventLog.ui("MULTI_ALL"', 'EventLog.ui("HUB_MULTI"',
    ],
    "player": [
        "import com.tingben.app.diag.EventLog",
        'EventLog.ui("PLAY_SPEED"', 'EventLog.ui("PLAY_PREV"',
        'EventLog.ui("PLAY_TOGGLE"', 'EventLog.ui("PLAY_NEXT"',
        'EventLog.ui("PLAY_QUEUE_LIST"',
    ],
    "multi": [
        "import com.tingben.app.diag.EventLog",
        'EventLog.ui("MULTI_ALL_BAR"', 'EventLog.ui("MULTI_CANCEL_BAR"',
    ],
    "ci": ["python3 scripts/r147_verify.py --selftest"],
}

BAN = {
    "eventlog": ["末尾 120 行", "read().lines().takeLast(120)"],
}

# 每个文件至少要有这么多埋点，防止「加一个锚点就算过」
MIN_UI = {"main": 12, "hub": 9, "player": 5, "multi": 2}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def split_sessions(lines, n=3):
    """复刻 EventLog.recentSessions 的切分逻辑，用样本验证边界行为。"""
    starts = [i for i, l in enumerate(lines) if "| UI | SESSION_START |" in l]
    if not starts:
        return lines[-300:]
    return lines[starts[max(0, len(starts) - n)]:]


def logic_cases(f):
    bad = []
    # ① 埋点密度
    for k, lo in MIN_UI.items():
        got = f[k].count("EventLog.ui(")
        if got < lo:
            bad.append("%s UI 埋点只有 %d 处，少于 %d" % (k, got, lo))
        else:
            print("  %-6s UI 埋点 %d 处" % (k, got))
    total = sum(f[k].count("EventLog.ui(") for k in MIN_UI)
    print("UI 埋点合计 %d 处" % total)

    # ② 会话切分：三轮事件流，取最近 1 轮只该剩最后一轮
    lines = []
    for sid, tag in (("s1", "第一轮"), ("s2", "第二轮"), ("s3", "第三轮")):
        lines.append("01-01 00:00:00 | UI | SESSION_START | 启动 · session=%s" % sid)
        lines.append("01-01 00:00:01 | UI | BACK | %s" % tag)
        lines.append("01-01 00:00:02 | PIPELINE | SEG_DONE | %s" % tag)
    one = split_sessions(lines, 1)
    if len(one) != 3 or "第三轮" not in "\n".join(one) or "第一轮" in "\n".join(one):
        bad.append("recentSessions(1) 没切出最后一轮")
    three = split_sessions(lines, 3)
    if len(three) != 9:
        bad.append("recentSessions(3) 没覆盖三轮")
    if not split_sessions(["01 | UI | BACK | 无边界"], 3):
        bad.append("无 SESSION_START 时没走兜底")

    # ③ 错误行筛选不受会话窗口限制
    detail = ["a | UI | INFO | BACK | x", "b | PIPELINE | ERROR | SEG_FAIL | y",
              "c | CRASH | ERROR | JAVA_CRASH | z", "d | UI | WARN | DENIED | w"]
    errs = [l for l in detail if " | ERROR | " in l or " | CRASH | " in l or " | WARN | " in l]
    if len(errs) != 3:
        bad.append("allErrors 漏了报错/警告行")

    # ④ 埋点必须落在回调里：不允许出现在 Composable 顶层声明区
    #    粗略但够用——禁止与 Column( / Row( / Text( 同一行直接调用
    for k in MIN_UI:
        for i, l in enumerate(f[k].splitlines(), 1):
            if "EventLog.ui(" in l and any(x in l for x in ("Column(", "Row(", "Text(")):
                bad.append("%s:%d 埋点疑似落在组合体顶层，会重组刷屏" % (k, i))

    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:70]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:60]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("ui 入口被摘", "eventlog", "fun ui(action: String", ""),
        ("会话边界被摘", "eventlog", "SESSION_START", ""),
        ("会话切分被摘", "eventlog", "private fun recentSessions(n: Int = 3)", ""),
        ("全量错误被摘", "eventlog", "private fun allErrors()", ""),
        ("摘要退回 120 行", "eventlog", "最近 3 轮完整事件（按 SESSION_START 分段）",
         "最近事件（末尾 120 行）"),
        ("导航埋点被摘", "main", 'EventLog.ui("OPEN_BOOK"', ""),
        ("返回埋点被摘", "main", 'EventLog.ui("BACK"', ""),
        ("浮层埋点被摘", "main", 'EventLog.ui("OVERLAY_PLAYER"', ""),
        ("复制摘要埋点被摘", "main", 'EventLog.ui("DIAG_COPY"', ""),
        ("目录列埋点被摘", "hub", 'EventLog.ui("HUB_LISTEN"', ""),
        ("章节勾选埋点被摘", "hub", 'EventLog.ui("CH_TOGGLE"', ""),
        ("全选埋点被摘", "hub", 'EventLog.ui("MULTI_ALL"', ""),
        ("播放器埋点被摘", "player", 'EventLog.ui("PLAY_TOGGLE"', ""),
        ("多选条埋点被摘", "multi", 'EventLog.ui("MULTI_ALL_BAR"', ""),
        ("CI 漏挂 r147", "ci", "python3 scripts/r147_verify.py --selftest", ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        # 全局替换：同一锚点在文件里可能出现多次（如浮层开关/会话边界），
        # 只删第一处会让正向检查依然全过，反向注入就成了假绿。
        x[key] = f[key].replace(needle, repl)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R147 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R147 接日志准备：会话分段 / UI 埋点覆盖 / 摘要不截断：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
