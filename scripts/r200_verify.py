#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R200 · 大文件分家读取层的防回潮守门。

守四件事：
  1. 合并视图 = 拆分前的 Pipeline.kt（所有 object 都还在，判据不会因此假红）
  2. 被搬出的符号只在新文件里出现一次，主文件只留占位行
  3. source().write_text() 把合并视图分发回各文件，绝不整体覆进主文件
  4. 34 个历史守门全部走 core_sources，不许有人改回直接读单文件

反向注入全程纯内存；写回分发用临时目录验证，不写真源码。
用法：python3 scripts/r200_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, shutil, sys, tempfile, re

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import core_sources as cs

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / cs.CORE
SCRIPTS = ROOT / "scripts"
MAIN = CORE / "Pipeline.kt"

# 合并视图必须同时在的符号（各占一段，证明没漏拼）
ANCHORS = [
    "data class Book(", "object BookStore {", "object ModelStore {",
    "object ChapterSplitter {", "object Annotator {", "object AnnCache {",
    "object WavMerger {", "data class Progress(", "object Producer {",
    "fun split(text: String): Pair<List<Chapter>, List<Chunk>>",
]


def files() -> dict:
    return {
        "core_sources": (SCRIPTS / "core_sources.py").read_text(encoding="utf-8", errors="replace"),
        "main": MAIN.read_text(encoding="utf-8", errors="replace"),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
    }


def parts_text():
    return {name: (CORE / name).read_text(encoding="utf-8", errors="replace")
            for name, _, _ in cs.PARTS}


def check(f, parts):
    e = []
    merged = cs.read(ROOT)
    for a in ANCHORS:
        if a not in merged:
            e.append("合并视图缺符号: " + a[:44])
    if "def read(root)" not in f["core_sources"]:
        e.append("core_sources.read 不存在")
    if "t.replace(_marker(name), _body(" not in f["core_sources"]:
        e.append("core_sources.read 退化成直接读单文件")
    for name, _, _ in cs.PARTS:
        marker = cs.INSERT + name[:-3]
        if f["main"].count(marker) != 1:
            e.append("主文件占位行异常: " + name)
        body = parts.get(name, "")
        if body.count(cs.PART) != 1:
            e.append("片段缺 @core-part: " + name)
        if body.count("package com.tingben.app.core") != 1:
            e.append("片段 package 行异常: " + name)
        # 被搬出的符号只能出现在片段里
        sym = name[:-3]
        if ("object " + sym + " {") in f["main"]:
            e.append("主文件仍留着搬走的符号: " + sym)
        if ("object " + sym + " {") not in body:
            e.append("片段里找不到符号: " + sym)
    # R202b · Kotlin 按文件独立编译：Pipeline.kt 顶部的 import 不会传给片段。
    # 片段正文里以简单名用到某个类型，就必须在自己的头部（哨兵行之前）import；
    # 反过来，import 行若混进正文会被拼进合并视图，破坏逐字节一致。
    req = {}
    for line in f["main"].splitlines():
        m = re.match(r"\s*import\s+([\w.]+)\s*$", line)
        if m and not m.group(1).endswith(".*"):
            req[m.group(1).split(".")[-1]] = line.strip()
    for name, _, _ in cs.PARTS:
        t = parts.get(name, "")
        i = t.find(cs.PART)
        head, body = (t[:i], t[i:]) if i >= 0 else (t, "")
        if re.search(r"^import ", body, re.M):
            e.append("片段 import 混进正文（会污染合并视图）: " + name)
        for sym, imp in req.items():
            if re.search(r"(?<![\w.])" + re.escape(sym) + r"\b", body) and imp not in head:
                e.append("片段 %s 用了 %s，头部缺 %s" % (name, sym, imp))
    # 34 个历史守门必须接线
    stragglers = []
    for p in sorted(SCRIPTS.glob("*.py")):
        t = p.read_text(encoding="utf-8", errors="replace")
        if "Pipeline.kt" in t and p.name not in ("core_sources.py", "r200_verify.py"):
            if "core_sources" not in t and "CONSERVE" not in t and "KEEP" not in t:
                stragglers.append(p.name)
    if stragglers:
        e.append("仍有脚本直接读单文件: " + ", ".join(stragglers))
    # 物理存在的片段文件必须都在 PARTS 里登记，漏一个就会静默不拼
    for q in sorted(CORE.glob("*.kt")):
        t2 = q.read_text(encoding="utf-8", errors="replace")
        if cs.PART in t2 and q.name not in {n for n, _, _ in cs.PARTS}:
            e.append("片段文件没登记进 PARTS: " + q.name)
    if not cs.PARTS:
        e.append("PARTS 为空，分家读取层没登记任何片段")
    # 读的是传入的 core_sources 文本，反向注入删掉 PARTS 行也能当场红
    for name, _, _ in cs.PARTS:
        if '"' + name + '"' not in f["core_sources"]:
            e.append("PARTS 漏登记: " + name)
    if "python3 scripts/r200_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r200")
    if '"r200_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r200")
    return e


def _anchor(name, body):
    """从片段正文里自动挑一个稳定的函数声明作注入锚点，不随片段数变化。"""
    import re
    m = re.search(r'^    (?:private )?fun (\w+)\(', body, re.M)
    return ("fun " + m.group(1) + "(", "fun " + m.group(1) + "_X(") if m else None


def writeback_case():
    """临时目录里对每个片段各跑一次「注入 → 分发写回 → 还原」，确认不污染主文件。"""
    errs = []
    with tempfile.TemporaryDirectory() as d:
        r = pathlib.Path(d)
        dst = r / cs.CORE
        dst.mkdir(parents=True, exist_ok=True)
        for q in (ROOT / cs.CORE).glob("*.kt"):
            shutil.copy2(q, dst / q.name)
        merged = cs.read(r)
        assert merged == cs.read(ROOT), "临时树合并视图与真源码不一致"
        for name, _, _ in cs.PARTS:
            base = cs.read(r)
            body = (r / cs.CORE / name).read_text(encoding="utf-8")
            pair = _anchor(name, body)
            if not pair:
                errs.append("片段找不到函数锚点: " + name)
                continue
            needle, repl = pair
            inj = base.replace(needle, repl, 1)
            assert inj != base
            cs.source(r).write_text(inj)
            main_t = (r / cs.CORE / "Pipeline.kt").read_text(encoding="utf-8")
            if "object " + name[:-3] + " {" in main_t:
                errs.append("写回把片段正文灌进了主文件: " + name)
            if repl not in cs.read(r):
                errs.append("写回后合并视图没反映注入: " + name)
            if repl not in (r / cs.CORE / name).read_text(encoding="utf-8"):
                errs.append("注入没落到片段文件: " + name)
            cs.source(r).write_text(base)
            if cs.read(r) != merged:
                errs.append("还原后合并视图不一致: " + name)
    return errs

def selftest():
    f = files()
    parts = parts_text()
    assert not check(f, parts), "基线自己就红了"
    assert not writeback_case(), "写回分发自检没过"

    cuts = []   # (名称, 目标, 锚点, 替换)
    # 每块：占位行被摘
    for name, _, _ in cs.PARTS:
        sym = name[:-3]
        cuts.append(("占位行被摘·" + sym, "main", cs.INSERT + sym, ""))
    # 每个片段：哨兵 / 符号 / package
    for name, _, _ in cs.PARTS:
        sym = name[:-3]
        cuts.append(("哨兵被摘·" + sym, "part:" + name, cs.PART, ""))
        cuts.append(("符号被删·" + sym, "part:" + name,
                     "object " + sym + " {", "object " + sym + "X {"))
    first = cs.PARTS[0][0]
    cuts.append(("package 重复", "part:" + first, "package com.tingben.app.core",
                 "package com.tingben.app.core\npackage com.tingben.app.core"))
    # 每块：PARTS 漏登记
    for name, start, nxt in cs.PARTS:
        line = '    ("%s", "%s", "%s"),' % (name, start, nxt)
        cuts.append(("PARTS 漏登记·" + name[:-3], "core_sources", line, ""))
    # 每个有 import 的片段：删掉一条 import、或把 import 塞进正文
    for name, _, _ in cs.PARTS:
        t = parts[name]
        m = re.search(r"^import .+$", t, re.M)
        if m:
            cuts.append(("片段缺 import·" + name[:-3], "part:" + name,
                         m.group(0) + "\n", ""))
            cuts.append(("import 混进正文·" + name[:-3], "part:" + name,
                         cs.PART + name[:-3] + "\n",
                         cs.PART + name[:-3] + "\n" + m.group(0) + "\n"))
    # 固定三项
    cuts += [
        ("CI 漏挂", "ci", "python3 scripts/r200_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r200_verify.py"', ""),
        ("core_sources 退化成直接读单文件", "core_sources",
         "t.replace(_marker(name), _body(", "pass  # "),
    ]

    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        gp = dict(parts)
        if key.startswith("part:"):
            nm = key.split(":", 1)[1]
            gp[nm] = parts[nm].replace(needle, repl, 1)
        else:
            g[key] = f[key].replace(needle, repl, 1)
        got = check(g, gp)
        if got:
            print("  反向注入 %-28s 拦下 %d 条" % (name, len(got)))
        else:
            print("  反向注入漏放: " + name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

def main():
    f = files()
    parts = parts_text()
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(f, parts) + writeback_case()
    if e:
        print("R200 FAIL %d" % len(e))
        for x in e:
            print(" - " + x)
        return 1
    print("R200 合并视图完整 / 片段唯一 / 写回不污染 / 34 守门全接线：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
