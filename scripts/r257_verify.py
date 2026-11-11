#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R257 · 1.1.30 真机日志实证守门。

① 用户内容零落盘（R225 补漏）：书名 / 书正文 / 音色描述 / 音色 ID / 用户文件 URI
   一律走 EventLog.safe（长度+短哈希）或 id.take(8)，禁止直接插值进 EventLog。
② 拼章去抖：回溯补齐不得挂在 st.done 上，制作中必须直接跳过。

用法：python3 scripts/r257_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources as u
ROOT = pathlib.Path(__file__).resolve().parents[1]
A = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "hub": rd(A / "ChapterHub.kt"),
        "main": u.read(ROOT),
        "lib": rd(A / "LibraryUi.kt"),
        "svc": rd(A / "ProduceService.kt"),
        "producer": rd(A / "core/Producer.kt"),
        "clip": rd(A / "core/ClipSynth.kt"),
        "native": rd(A / "engine/NativeEngine.kt"),
        "plan": rd(A / "core/ChapterPlan.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    # ① TTS_INPUT：正文 / 指令 / 音色都不许原文
    p = f["producer"]
    for k in ['"text=${EventLog.safe(live.text)} · "',
              '"instruct=${EventLog.safe(v.instruct)} · "',
              '"speaker=${EventLog.safe(live.speaker)}"',
              '"speakerParam" to EventLog.safe(v.speaker)']:
        if k not in p: e.append("TTS_INPUT 脱敏缺: " + k[:52])
    for bad in ["live.text.take(50)", 'v.instruct ?: "（无指令）"',
                '"speaker" to live.speaker', '"speakerParam" to (v.speaker']:
        if bad in p: e.append("TTS_INPUT 原文回潮: " + bad[:52])
    # ② 书名：所有 EventLog 调用里出现 .title 必须包 safe
    for name in ["main", "hub", "producer", "svc"]:
        s = f[name]
        for bad in ["book=${p.task.title}", "book=${fixed.title}", "book=${t.title}",
                    "书=${book.title}", "title=$title type=", "${item.title} · ${item.scope}"]:
            if bad in s: e.append("书名原文回潮[%s]: %s" % (name, bad[:52]))
    for k in ['"book=${EventLog.safe(p.task.title)}',
              '"book=${EventLog.safe(fixed.title)}',
              '"book=${EventLog.safe(t.title)}',
              '"书=${EventLog.safe(book.title)}',
              '"title=${EventLog.safe(title)} type=',
              '"${EventLog.safe(item.title)} · ${item.scope}']:
        if k not in "".join(f[x] for x in ("main", "hub", "producer", "svc")):
            e.append("书名 safe 缺: " + k[:52])
    # ③ URI / UUID / 音色 ID
    for bad, where in [("uri=${raw?.take(90)", "main"),
                       ("uri.toString().take(90)", "main"),
                       ('"id=${added.id}"', "main"),
                       ('"id=${b.id} on=$next"', "lib"),
                       ('"book=${b.id}"', "main"),
                       ('"book=${t.bookId}"', "main"),
                       ('"book=${book.id}"', "hub"),
                       ('"book=${t.bookId}"', "hub"),
                       ("book=$bookId ", "plan"),
                       ('speaker=${speaker ?: "null"}', "native"),
                       ('speaker=${v.speaker ?: "空"}', "clip")]:
        if bad in f[where]: e.append("原文/完整 ID 回潮[%s]: %s" % (where, bad[:52]))
    for k, where in [('uri=${EventLog.safe(raw)}', "main"),
                     ('"id=${added.id.take(8)}"', "main"),
                     ('"id=${b.id.take(8)} on=$next"', "lib"),
                     ('"speaker=${EventLog.safe(speaker)} hash=', "native"),
                     ('"speaker=${EventLog.safe(v.speaker)} · chars=', "clip")]:
        if k not in f[where]: e.append("脱敏接线缺[%s]: %s" % (where, k[:52]))
    # ④ 拼章去抖：段列表 effect 不拼章、补齐 effect 独立且制作中跳过
    h = f["hub"]
    seg = h.split("LaunchedEffect(book.id, st.running, localTick, task?.bookId,")
    if len(seg) != 2: e.append("段列表 LaunchedEffect 定位不到")
    else:
        body = seg[1].split("LaunchedEffect(book.id, st.running)")[0]
        if "AudioOps.ensureChapterAudios(" in body:
            e.append("回溯补齐又挂回 st.done（真机 50 次 0/338 白烧 IO）")
    if "LaunchedEffect(book.id, st.running) {" not in h:
        e.append("补齐独立 effect 丢了")
    if "if (st.running) return@LaunchedEffect" not in h:
        e.append("制作中没跳过补齐")
    # ⑤ 三处挂载
    for k, where in [("python3 scripts/r257_verify.py\n", "ci"),
                     ('"r257_verify.py"', "r135"),
                     ('"r257_verify.py"', "config")]:
        if k not in f[where]: e.append("漏挂[%s]: %s" % (where, k[:40]))
    return e

def selftest():
    f = files()
    base_err = check(f)
    assert not base_err, "基线自己就红了，先修基线：" + " | ".join(base_err[:3])
    cuts = [
        ("正文原文回潮", "producer",
         '"text=${EventLog.safe(live.text)} · "', '"text=${live.text.take(50)} · "'),
        ("指令原文回潮", "producer",
         '"instruct=${EventLog.safe(v.instruct)} · "', '"instruct=${v.instruct} · "'),
        ("音色 ID 原文回潮", "producer",
         '"speakerParam" to EventLog.safe(v.speaker)', '"speakerParam" to (v.speaker ?: "null")'),
        ("书名原文回潮", "main",
         '"book=${EventLog.safe(p.task.title)} action=${p.action} auto=$auto',
         '"book=${p.task.title} action=${p.action} auto=$auto'),
        ("服务书名原文回潮", "svc",
         '"title=${EventLog.safe(title)} type=', '"title=$title type='),
        ("成品书名原文回潮", "producer",
         '"${EventLog.safe(item.title)} · ${item.scope}', '"${item.title} · ${item.scope}'),
        ("URI 原文回潮", "main",
         'uri=${EventLog.safe(raw)}', 'uri=${raw?.take(90)}'),
        ("完整 UUID 回潮", "main",
         '"id=${added.id.take(8)}"', '"id=${added.id}"'),
        ("引擎音色原文回潮", "native",
         '"speaker=${EventLog.safe(speaker)} hash=', '"speaker=${speaker ?: "null"} hash='),
        ("试听音色原文回潮", "clip",
         '"speaker=${EventLog.safe(v.speaker)} · chars=', '"speaker=${v.speaker ?: "空"} · chars='),
        ("补齐又挂 st.done", "hub",
         "LaunchedEffect(book.id, st.running) {", "LaunchedEffect(book.id, st.done) {"),
        ("制作中不跳过", "hub",
         "if (st.running) return@LaunchedEffect", ""),
        ("CI 漏挂", "ci", "python3 scripts/r257_verify.py\n", ""),
        ("清单漏登记", "r135", '"r257_verify.py"', ""),
        ("受管根漏登记", "config", '"r257_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        assert needle in g[key], "注入锚找不到: " + name
        g[key] = g[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R257 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R257 真机日志实证：用户内容零落盘 + 拼章去抖：PASS")
