#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R225 · 日志脱敏守门：用户内容（书名 / 音色描述 / 正文 / 自填语气）不得落盘。

只留「长度 + 短哈希 + 内部 id + 计数 + 字节数」，诊断信息不减反增。
用法：python3 scripts/r225_verify.py [--selftest]
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "log": rd(APP / "diag/EventLog.kt"),
        "book": rd(APP / "core/BookStore.kt"),
        "prod": rd(APP / "core/Producer.kt"),
        "cfg": rd(APP / "core/ProduceConfig.kt"),
        "mixer": rd(APP / "core/Mixer.kt"),
        "main": ui_sources.source(APP).read_text(encoding="utf-8"),
        "task": rd(APP / "TaskUi.kt"),
        "hub": rd(APP / "ChapterHub.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

# 日志调用里这些形态 = 用户内容原文直接落盘
BAN = [
    (r'EventLog\.(log|ui)\([^\n]*\$\{[a-zA-Z.]*\.title', "书名原文进日志"),
    (r'EventLog\.(log|ui)\([^\n]*voiceDescription\(c\)(?!\))', "音色描述原文进日志"),
    (r'EventLog\.(log|ui)\([^\n]*extraInstruct\.trim', "自填语气原文进日志"),
    (r'EventLog\.(log|ui)\([^\n]*, title\)', "title 作为日志 detail"),
    (r'append\(" · voice="\)\.append\(Prefs\.voiceDescription', "MIXER_PROFILE 又写音色原文"),
    (r'"本次预设=" \+ config\.summary\(\)', "预设摘要没脱敏"),
    (r'"预设=" \+ config\.summary\(\)', "chunks 预设摘要没脱敏"),
    (r'config\.summary\(\) \+ " · 指纹="', "PRESET_APPLIED 没脱敏"),
]

def check(f):
    e = []
    allcode = "\n".join(f[k] for k in ("log","book","prod","cfg","mixer","main","task","hub"))
    # ① 脱敏真源存在且用 sha
    if "fun safe(s: String?): String" not in f["log"]: e.append("缺 EventLog.safe 脱敏真源")
    if "com.tingben.app.core.sha" not in f["log"]: e.append("safe 没用 sha")
    if 'len=${s.length} h=${sha(s).take(8)}' not in f["log"]: e.append("safe 口径被改")
    # ② 全文禁原文（按行扫 EventLog 调用）
    for line in allcode.splitlines():
        for pat, why in BAN:
            if re.search(pat, line):
                e.append(why + ": " + line.strip()[:70])
    # ③ summarySafe 存在且三处日志都用它
    if "fun summarySafe(): String" not in f["cfg"]: e.append("缺 summarySafe")
    if f["prod"].count("config.summarySafe()") != 3:
        e.append("Producer 三处预设日志没全换 summarySafe")
    if "import com.tingben.app.diag.EventLog" not in f["cfg"]:
        e.append("ProduceConfig 漏 EventLog import（R187 会红）")
    # ④ 诊断包：R261 起快照段用 redact 别名（段内有同名局部 safe 会遮蔽），
    #    用户内容一律不许裸插值。按语义判，不绑旧字面量。
    snap = f["log"][f["log"].find("private fun snapshotBlock"):]
    for pat, why in [(r'\$\{t\.title\b', "运行快照仍写任务书名"),
                     (r'\$\{p\.message\b', "运行快照仍写制作状态原文"),
                     (r'\$\{s\.label\b', "运行快照仍写播放标签原文")]:
        if re.search(pat, snap): e.append(why)
    if snap.count("redact(") < 4: e.append("运行快照脱敏别名调用不足")
    if '"${t.title} · ${t.phase}' in f["log"]: e.append("运行快照仍写任务书名")
    if "可能含书名与正文片段" in f["log"]: e.append("诊断摘要抬头仍宣称含原文")
    # ⑤ 书名日志改带内部 id 与字数
    for k, needle in [("book", 'id=${b.id.take(8)}'), ("book", 'chars=${text.length}'),
                      ("prod", 'id=${t.bookId.take(8)}'),
                      ("main", 'id=${p.task.bookId.take(8)}'),
                      ("task", 'id=${t.bookId.take(8)}'),
                      ("hub", 'EventLog.ui("HUB_QUEUE"')]:
        if needle not in f[k]: e.append("诊断字段缺失[%s]: %s" % (k, needle[:40]))
    if 'board id=' in f["hub"]:
        e.append("目录页就地看板日志回潮")
    # ⑥ 登记（受管范围改按官方 managed_scope.rules 判，不再找旧字符串）
    from r45_common import scope_indexed
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r225_verify.py --selftest"),
                              ("清单", f["r135"], '"r225_verify.py"')]:
        if needle not in src: e.append(name + " 漏登记 r225")
    if not scope_indexed(f["config"], "scripts/r225_verify.py"):
        e.append("受管根 漏登记 r225")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("safe 被删", "log", "fun safe(s: String?): String", ""),
        ("safe 口径被改", "log", 'len=${s.length} h=${sha(s).take(8)}', "已脱敏"),
        ("BOOK_ADD 写回书名", "book",
         '"id=${b.id.take(8)} · title=${EventLog.safe(title)} · chars=${text.length} · origin=$origin"',
         'title'),
        ("QUEUE_SKIP 写回书名", "prod",
         '"书没了 id=${t.bookId.take(8)} · title=${EventLog.safe(t.title)}"',
         '"书没了 book=${t.title}"'),
        ("RESUME_AUTO 写回书名", "main",
         'EventLog.ui("RESUME_AUTO", "id=${p.task.bookId.take(8)}',
         'EventLog.ui("RESUME_AUTO", "book=${p.task.title}'),
        ("TASK_RESUME 写回书名", "task",
         'EventLog.ui("TASK_RESUME", "id=${t.bookId.take(8)}")',
         'EventLog.ui("TASK_RESUME", "book=${t.title}")'),
        ("任务删除写回书名", "task",
         'EventLog.ui("TASK_DELETE", "id=${t.bookId.take(8)}")',
         'EventLog.ui("TASK_DELETE", "book=${t.title}")'),
        ("目录页看板日志回潮", "hub",
         'EventLog.ui("HUB_QUEUE"', 'EventLog.ui("HUB_QUEUE", "board id=${t.bookId.take(8)}")'),
        ("音色原文回潮", "mixer",
         'append(" · voice=").append(EventLog.safe(Prefs.voiceDescription(c)))',
         'append(" · voice=").append(Prefs.voiceDescription(c))'),
        ("预设摘要回潮", "prod", '" · 本次预设=" + config.summarySafe()',
         '" · 本次预设=" + config.summary()'),
        ("指纹摘要回潮", "prod", 'config.summarySafe() + " · 指纹=" + presetStamp',
         'config.summary() + " · 指纹=" + presetStamp'),
        ("summarySafe 被删", "cfg", "fun summarySafe(): String", ""),
        ("import 被摘", "cfg", "import com.tingben.app.diag.EventLog\n", ""),
        ("快照写回书名", "log", '"${redact(t.title)} · ${t.phase}', '"${t.title} · ${t.phase}'),
        ("抬头回潮", "log", "不含书名/正文/音色描述原文", "可能含书名与正文片段"),
        ("CI 漏挂", "ci", "python3 scripts/r225_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r225_verify.py"', ""),
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
        print("R225 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R225 日志脱敏：书名/音色/正文/自填语气零落盘、诊断字段保留：PASS")
