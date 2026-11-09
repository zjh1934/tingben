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
    # 34 个历史守门必须接线
    stragglers = []
    for p in sorted(SCRIPTS.glob("*.py")):
        t = p.read_text(encoding="utf-8", errors="replace")
        if "Pipeline.kt" in t and p.name not in ("core_sources.py", "r200_verify.py"):
            if "core_sources" not in t and "CONSERVE" not in t and "KEEP" not in t:
                stragglers.append(p.name)
    if stragglers:
        e.append("仍有脚本直接读单文件: " + ", ".join(stragglers))
    if "python3 scripts/r200_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r200")
    if '"r200_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r200")
    return e


def writeback_case():
    """临时目录里真跑一次「注入 → 分发写回 → 还原」，确认不污染主文件。"""
    errs = []
    with tempfile.TemporaryDirectory() as d:
        r = pathlib.Path(d)
        dst = r / cs.CORE
        dst.mkdir(parents=True, exist_ok=True)
        for p in (ROOT / cs.CORE).glob("*.kt"):
            shutil.copy2(p, dst / p.name)
        merged = cs.read(r)
        assert merged == cs.read(ROOT), "临时树合并视图与真源码不一致"
        inj = merged.replace("val safe = if (marks.isEmpty())",
                             "val safe = if (false)", 1)
        assert inj != merged
        cs.source(r).write_text(inj)
        main_t = (r / cs.CORE / "Pipeline.kt").read_text(encoding="utf-8")
        if "object ChapterSplitter {" in main_t:
            errs.append("写回把片段正文灌进了主文件")
        if "if (false)" not in cs.read(r):
            errs.append("写回后合并视图没反映注入")
        if "if (false)" not in (r / cs.CORE / "ChapterSplitter.kt").read_text(encoding="utf-8"):
            errs.append("注入没落到片段文件")
        cs.source(r).write_text(merged)
        if cs.read(r) != merged:
            errs.append("还原后合并视图不一致")
    return errs


def selftest():
    f = files()
    parts = parts_text()
    assert not check(f, parts), "基线自己就红了"
    assert not writeback_case(), "写回分发自检没过"
    cuts = [
        ("占位行被摘", "main", cs.INSERT + "ChapterSplitter", ""),
        ("片段哨兵被摘", "part", cs.PART, ""),
        ("片段符号被删", "part", "object ChapterSplitter {", "object ChapterSplitterX {"),
        ("package 重复", "part", "package com.tingben.app.core",
         "package com.tingben.app.core\npackage com.tingben.app.core"),
        ("CI 漏挂", "ci", "python3 scripts/r200_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r200_verify.py"', ""),
        ("core_sources 退化成直接读单文件", "core_sources",
         "t.replace(_marker(name), _body(", "pass  # "),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        gp = dict(parts)
        if key == "part":
            gp["ChapterSplitter.kt"] = parts["ChapterSplitter.kt"].replace(needle, repl, 1)
        else:
            g[key] = f[key].replace(needle, repl, 1)
        got = check(g, gp)
        if got:
            print("  反向注入 %-26s 拦下 %d 条" % (name, len(got)))
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
