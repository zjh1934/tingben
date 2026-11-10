#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R213 · 队列暂停标记的静默失效，及修复后不许引入的回归。

1.1.22 真机日志：05:07:38 QUEUE_PAUSE「队列停在原地」，05:09:35 快照「暂停=false」。
根因：TaskResume.setQueuePaused 写纯标量 "1"，AtomicJson.legal 只放行 { / [，
被静默拒写 → 标记从未落盘 → 进程重启后停掉的队列会自己跑起来。

修复若只改写入会引入新回归：用户停止过一次后，每次主动点合成都会被排进
暂停队列、点了不动。故 enqueueOrStart 队列已空时先清残留再直接开跑。

用法：python3 scripts/r213_verify.py [--selftest]
"""
from __future__ import annotations
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
SCRIPTS = ROOT / "scripts"

def split_args(call: str) -> list:
    depth, cur, out, instr, esc = 0, "", [], False, False
    for ch in call:
        if instr:
            cur += ch
            if esc: esc = False
            elif ch == "\\": esc = True
            elif ch == '"': instr = False
            continue
        if ch == '"': instr = True; cur += ch; continue
        if ch in "([{": depth += 1
        elif ch in ")]}": depth -= 1
        if ch == "," and depth == 0:
            out.append(cur.strip()); cur = ""; continue
        cur += ch
    if cur.strip(): out.append(cur.strip())
    return out

def write_calls(text: str) -> list:
    """按括号深度取真实参数，避免 File(dir(c), "x.json") 里的逗号造成误报。"""
    res = []
    for m in re.finditer(r"AtomicJson\.write\(", text):
        i, depth, j = m.end(), 1, m.end()
        while j < len(text) and depth > 0:
            if text[j] == "(": depth += 1
            elif text[j] == ")": depth -= 1
            j += 1
        res.append(split_args(text[i:j-1]))
    return res

def legal(s: str) -> bool:
    s = s.lstrip()
    if not s or s[0] not in "[{": return False
    try: json.loads(s); return True
    except Exception: return False

def scalar_writes() -> list:
    bad = []
    for f in sorted(APP.rglob("*.kt")):
        for args in write_calls(f.read_text(encoding="utf-8", errors="replace")):
            if len(args) < 2: continue
            m = re.match(r'^"([^"\\]*(?:\\.[^"\\]*)*)"$', args[1])
            if m and not legal(m.group(1)):
                bad.append(f.name + ":" + args[1][:36])
    return bad

def files():
    return {
        "resume": (APP / "core/TaskResume.kt").read_text(encoding="utf-8", errors="replace"),
        "producer": (APP / "core/Producer.kt").read_text(encoding="utf-8", errors="replace"),
        "atomic": (APP / "core/AtomicJson.kt").read_text(encoding="utf-8", errors="replace"),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
    }

def check(f, scalar=None):
    e = []
    r, p, a = f["resume"], f["producer"], f["atomic"]
    # ① 写入必须是合法 JSON，不再有纯标量
    if 'AtomicJson.write(pauseFile(c), "1")' in r:
        e.append("setQueuePaused 又退回写纯标量 1")
    if 'JSONObject().put("paused", 1).toString()' not in r:
        e.append("setQueuePaused 没写 JSON 对象")
    # ② 读回兼容老裸写 + 解析新格式
    if 'f.readText().trim() == "1"' not in r:
        e.append("queuePaused 丢了老裸写兼容")
    if 'optInt("paused", 0)' not in r:
        e.append("queuePaused 没解析 paused 字段")
    if "AtomicJson.read(pauseFile(c))" not in r:
        e.append("queuePaused 不走 AtomicJson 读")
    # ③ 地基不许放宽
    if "'[' ->" not in a or "'{' ->" not in a:
        e.append("AtomicJson.legal 被放宽，纯标量又能混进去")
    # ④ 主动开始不被暂停残留卡住
    if "val queueEmpty = TaskResume.queue(c).isEmpty()" not in p:
        e.append("enqueueOrStart 没区分队列空")
    if "if (queueEmpty && TaskResume.queuePaused(c))" not in p:
        e.append("队列空时没清残留暂停标记")
    if "val paused = TaskResume.queuePaused(c) && !queueEmpty" not in p:
        e.append("paused 没限定只在队列非空时生效")
    if "if (!busy && queueEmpty && !paused)" not in p:
        e.append("直接开跑分支没跟着改")
    # ⑤ 全仓防回潮
    sw = scalar if scalar is not None else scalar_writes()
    if sw:
        e.append("全仓又有纯标量 AtomicJson.write: " + ", ".join(sw[:3]))
    # ⑥ 逻辑对拍
    if legal("1") or not legal('{"paused":1}'):
        e.append("legal 对拍不成立（旧写法应拒、新写法应放行）")
    def read_pause(raw):
        if raw is None: return False
        if raw.strip() == "1": return True
        if not legal(raw): return False
        try: return json.loads(raw).get("paused", 0) == 1
        except Exception: return raw.strip() == "1"
    for raw, exp in [('{"paused":1}', True), ("1", True),
                     ("{broken", False), (None, False), ('{"paused":0}', False)]:
        if read_pause(raw) != exp:
            e.append("queuePaused 读回对拍失败: " + str(raw)); break
    def enqueue(qe, dp):
        if qe and dp: dp = False
        paused = dp and not qe
        return "直接开跑" if qe and not paused else (
            "排进队列（队列暂停）" if paused else "排进队列，自动接着跑")
    for qe, dp, exp in [(True, True, "直接开跑"), (True, False, "直接开跑"),
                        (False, True, "排进队列（队列暂停）"),
                        (False, False, "排进队列，自动接着跑")]:
        if enqueue(qe, dp) != exp:
            e.append("enqueueOrStart 交互对拍失败: %s/%s" % (qe, dp)); break
    # ⑦ 登记
    if "python3 scripts/r213_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r213")
    if '"r213_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r213")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("写回纯标量", "resume", 'JSONObject().put("paused", 1).toString()', '"1"'),
        ("丢老裸写兼容", "resume", 'f.readText().trim() == "1"', "false"),
        ("丢字段解析", "resume", 'optInt("paused", 0)', "optInt(\"x\", 0)"),
        ("放宽 legal", "atomic", "'[' -> { JSONArray(s); true }", "'1' -> true"),
        ("不区分队列空", "producer", "val queueEmpty = TaskResume.queue(c).isEmpty()",
         "val queueEmpty = false"),
        ("不清残留暂停", "producer", "if (queueEmpty && TaskResume.queuePaused(c))", "if (false)"),
        ("paused 不限队列", "producer", "val paused = TaskResume.queuePaused(c) && !queueEmpty",
         "val paused = TaskResume.queuePaused(c)"),
        ("CI 漏挂", "ci", "python3 scripts/r213_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r213_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        got = check(g)
        if got:
            print("  反向注入 %-22s 拦下 %d 条" % (name, len(got)))
        else:
            print("  反向注入漏放: " + name); bad += 1
    # 全仓纯标量回潮：单独用一份临时源码，不写真文件
    g = dict(f); g["resume"] = f["resume"].replace(
        'JSONObject().put("paused", 1).toString()', '"1"')
    if check(g, scalar=["TaskResume.kt:纯标量回潮"]):
        print("  反向注入 全仓纯标量回潮       拦下 1 条")
    else:
        print("  反向注入漏放: 全仓纯标量回潮"); bad += 1
    total = len(cuts) + 1
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad

def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(files())
    if e:
        print("R213 FAIL", len(e))
        for x in e: print(" -", x)
        return 1
    print("R213 队列暂停真落盘 / 主动开始不卡 / 全仓无纯标量写入 / 逻辑对拍：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
