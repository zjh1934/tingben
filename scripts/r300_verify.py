#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R300 · 制作是「勾好章 → 一条线顺着做 → 这一章小块做完就立刻拼成这一章的
音频」，不是整本跑完再统一拼。守实现串 + 反向注入，防止退回旧形态。

用法：python3 scripts/r300_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/tingben/app/core"

def files():
    return {
        "producer": (CORE / "Producer.kt").read_text(encoding="utf-8", errors="replace"),
    }

NEED = [
    "val chapterSegIndices = LinkedHashMap<Int, MutableList<Int>>()",
    "val chapterLastIndex = chapterSegIndices.mapValues { (_, idxs) -> idxs.last() }",
    "val chapterAtBoundary = chapterLastIndex.entries.associate { (ch, last) -> last to ch }",
    "fun chapterRedoLeft(ci: Int): Boolean = SegPlan.pending(work).any { key ->",
    "fun tryMergeChapter(ci: Int, at: Int) {",
    "if (chapterRedoLeft(ci)) { deferredChapters.add(ci); return }",
    "chapterSegIndices[ci].orEmpty().forEach { j -> SegPlan.clearRedo(work, \"seg:$j\") }",
    "AudioOps.mergeChapterAuto(app, book.id, book.title, ci, parts,",
    "segChapter.getOrElse(j) { -1 } !in mergedThisRun &&",
    "if (ci in mergedThisRun) continue",
    "val totalMade = chaptersMade + merged.first",
    "immediate=$chaptersMade",
    # 收尾补漏网仍在：边界没拼成的章不会没成品
    "AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,",
]

def check(f):
    e = []
    p = f["producer"]
    for k in NEED:
        if k not in p:
            e.append("R300 逐章即时拼缺: " + k[:64])
    # 两处触发点：删段分支 + 正常段收尾，缺一处都会漏拼一整章
    a = "chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }"
    b = "deferredChapters.toList().forEach { tryMergeChapter(it, i) }"
    if p.count(a) < 2:
        e.append("R300 章边界触发点不足 2 处（删段分支/段收尾）")
    if p.count(b) < 2:
        e.append("R300 被推迟章的补拼触发点不足 2 处")
    # R270 已拔的 holdChapters 不许借新形态回潮
    if "holdChapters" in p:
        e.append("R300 holdChapters 回潮")
    return e

def selftest():
    f = files()
    base = check(f)
    if base:
        print("正向不绿，先修实现：")
        for x in base: print(" -", x)
        return 1
    print("正向 0 失败")
    cuts = [
        ("正常段收尾不拼章", "                // R300 · 本段收尾后看章边界：这一章小块齐了就立刻拼成章音频、\n"
         "                // 清掉小块，主循环接着做下一章，不等整本跑完。\n"
         "                chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }\n"
         "                deferredChapters.toList().forEach { tryMergeChapter(it, i) }", ""),
        ("只留一处边界触发", "chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }",
         "// 边界触发被摘"),
        ("拼章调用被摘", "val r = AudioOps.mergeChapterAuto(app, book.id, book.title, ci, parts,",
         "val r = AudioOps.Result(false)"),
        ("重做未清就拼章", "if (chapterRedoLeft(ci)) { deferredChapters.add(ci); return }",
         "if (false) { deferredChapters.add(ci); return }"),
        ("拼完不清重做标记",
         "chapterSegIndices[ci].orEmpty().forEach { j -> SegPlan.clearRedo(work, \"seg:$j\") }", ""),
        ("已拼章仍进重做插队", "segChapter.getOrElse(j) { -1 } !in mergedThisRun &&", ""),
        ("收尾又全量扫", "if (ci in mergedThisRun) continue", ""),
        ("完成数漏算即时拼章", "val totalMade = chaptersMade + merged.first",
         "val totalMade = merged.first"),
        ("日志丢即时拼章数", "immediate=$chaptersMade", ""),
        ("推迟章不补拼", "deferredChapters.toList().forEach { tryMergeChapter(it, i) }", ""),
        ("章边界索引被删",
         "val chapterAtBoundary = chapterLastIndex.entries.associate { (ch, last) -> last to ch }", ""),
        ("收尾补漏网被删", "AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,", ""),
    ]
    bad = 0
    for name, needle, repl in cuts:
        g = dict(f)
        g["producer"] = f["producer"].replace(needle, repl)
        if check(g):
            print("  反向注入 %-20s 拦下" % name)
        else:
            print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R300 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R300 这一章做完立刻拼成这一章音频：PASS")
