# -*- coding: utf-8 -*-
"""R346 · 第二章已拼好转码、第三章合成完又回头把第二章小块跑一遍的推进漏洞。

用户实证：某一章已经拼接转码完成，下一章合成结束后，主循环不接着往更后面
走，反而回去把上一章的小块重新合成一遍。根因是三处「回头」逻辑只认
redo.json 里的 seg: 标记，不认「这一章已经固化成章音频」：

  · doneChapterFiles 是开跑时的一份快照，运行中目录页手动「拼接并转码」
    刚写进 chapter_done.json 的章不在快照里；
  · 手动拼章路径（AudioOps.mergeChapter）从来不清段级重做标记，标记成了
    孤儿，而小块早已 deleteAll 删掉；
  · nextRedo 每段收尾只排除 mergedThisRun（本次自动拼的章），孤儿标记于是
    每段都被挑出来重做；重做失败再 handledRedo.remove，同一次运行里每段
    反复重试同一个失败段，进度钉在旧章不往前走。

R346 口径：chapter_done.json 是「已固化」的唯一事实，主循环逐段刷新；
已固化章的孤儿标记只清不重做，重做插队 / redoSeg / 重做闸 / 收尾补拼
一律排除已固化章；失败段本次只试一次，章边界靠「标记还在」照样不许拼
（R329 不破）；两条拼章成功路径统一批量清该章标记。

守恒：章边界即时拼、自动开关 force=false、坏块没消化不许拼、拼成才清标记、
分章规则与段号、断点续跑、手动另存兜底，一条没动。

用法：python3 scripts/r346_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "producer": APP / "core" / "Producer.kt",
    "audioops": APP / "core" / "AudioOps.kt",
    "segplan": APP / "core" / "SegPlan.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "producer": [
        # 章成品状态实时化：var + 逐段刷新，读失败保留旧快照
        "var doneChapterFiles: Map<Int, File> =",
        "doneChapterFiles = try { AudioTree.readDone(work) }",
        "catch (_: Throwable) { doneChapterFiles }",
        # 已固化判定 / 孤儿标记批量清 / 重做闸认标记不认 handledRedo
        "fun chapterSealed(ci: Int): Boolean = ci >= 0 &&",
        "fun clearSealedRedo(ci: Int) {",
        "SegPlan.clearSegs(work, chapterSegIndices[ci].orEmpty())",
        "!chapterSealed(ci) && SegPlan.pending(work).any { key ->",
        "if (chapterSealed(ci)) {",
        "clearSealedRedo(ci)",
        # 段级：已固化章不强制重做、不回头重做、孤儿标记清掉
        "val sealedCi = chapterSealed(ci)",
        'val redoSeg = !sealedCi && "seg:$i" in SegPlan.pending(work)',
        "val redoNow = SegPlan.pending(work)",
        "if (chapterSealed(segChapter.getOrElse(j) { -1 }))",
        "SegPlan.clearRedo(work, key)",
        "!chapterSealed(segChapter.getOrElse(j) { -1 }) &&",
        # 收尾补拼同样排除
        "if (chapterSealed(ci)) continue",
        # 守恒锚：R266 / R300 / R329 / R330 一条不许少
        "val nextRedo = SegPlan.pending(work)",
        "segChapter.getOrElse(j) { -1 } !in mergedThisRun &&",
        "if (ci in mergedThisRun) continue",
        "if (chapterRedoLeft(ci)) { deferredChapters.add(ci); return }",
        'chapterSegIndices[ci].orEmpty().forEach { j -> SegPlan.clearRedo(work, "seg:$j") }',
        "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = false)",
    ],
    "audioops": [
        "private fun clearRedoOfParts(parts: List<File>) {",
        "SegPlan.clearSegs(work, idxs)",
        "AudioTree.markDone(it, chapter, enc.file)",
        "deleteAll(parts)",
    ],
    "segplan": [
        "fun clearSegs(work: File, segs: Collection<Int>) = synchronized(redoLock) {",
        "if (changed) writeRedo(work, r)",
    ],
    "ci": [
        "python3 scripts/r346_verify.py",
        "python3 scripts/r346_verify.py --selftest",
    ],
    "r135": ['"r346_verify.py"'],
}

BAN = {
    "producer": [
        # R329 的失败回路回潮：每段反复重做同一失败段，进度钉住
        "handledRedo.remove(nextRedo)",
        # 旧快照 / 旧 redoSeg / 旧重做闸形态
        "val doneChapterFiles = try { AudioTree.readDone(work) }",
        'val redoSeg = "seg:$i" in SegPlan.pending(work)',
        "j !in handledRedo &&\n                chapterSegIndices[ci]",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    p, a, s = f["producer"], f["audioops"], f["segplan"]
    for k, needles in POS.items():
        for n in needles:
            if n not in f[k]:
                e.append("R346 %s 缺: %s" % (k, n[:68]))
    for k, needles in BAN.items():
        for n in needles:
            if n in f[k]:
                e.append("R346 %s 旧形态回潮: %s" % (k, n[:68]))
    # 两条拼章成功路径都必须清标记（调用锚带缩进，定义行不算）
    if a.count("        clearRedoOfParts(parts)") != 2:
        e.append("R346 拼章成功清标记不是 2 处（手动/自动各一）")
    # 章边界与段收尾两处触发点仍在，别为了堵漏洞把即时拼章摘了
    if p.count("chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }") < 2:
        e.append("R346 章边界触发点不足 2 处")
    if p.count("deferredChapters.toList().forEach { tryMergeChapter(it, i) }") < 2:
        e.append("R346 推迟章补拼触发点不足 2 处")
    # 清整章标记仍必须在拼章成功之后（R329 顺序口径）
    mi = p.find("val r = AudioOps.mergeChapterAuto(")
    ci = p.find('chapterSegIndices[ci].orEmpty().forEach { j -> SegPlan.clearRedo')
    if mi < 0 or ci < 0 or ci < mi:
        e.append("R329 清重做标记退回拼章之前")
    return e


def selftest():
    f = load()
    base = check(f)
    if base:
        print("正向不绿，先修实现：")
        for x in base:
            print(" -", x)
        return 1
    print("正向 0 失败")
    cuts = [
        ("章成品快照不刷新", "producer", "doneChapterFiles = try { AudioTree.readDone(work) }", ""),
        ("快照退回 val 旧形态", "producer", "var doneChapterFiles: Map<Int, File> =",
         "val doneChapterFiles ="),
        ("没有已固化判定", "producer", "fun chapterSealed(ci: Int): Boolean = ci >= 0 &&", ""),
        ("重做闸又认 handledRedo", "producer",
         "!chapterSealed(ci) && SegPlan.pending(work).any { key ->",
         "SegPlan.pending(work).any { key ->"),
        ("重做闸不排除已固化", "producer",
         "!chapterSealed(ci) && SegPlan.pending(work).any { key ->",
         "SegPlan.pending(work).any { key ->\n                true &&"),
        ("段仍被孤儿标记强制重做", "producer",
         'val redoSeg = !sealedCi && "seg:$i" in SegPlan.pending(work)',
         'val redoSeg = "seg:$i" in SegPlan.pending(work)'),
        ("回头重做不排除已固化", "producer",
         "!chapterSealed(segChapter.getOrElse(j) { -1 }) &&", ""),
        ("孤儿标记不清", "producer", "SegPlan.clearRedo(work, key)", ""),
        ("孤儿清理被摘", "producer", "val redoNow = SegPlan.pending(work)", ""),
        ("收尾又补拼已固化章", "producer", "if (chapterSealed(ci)) continue", ""),
        ("失败段反复重做回潮", "producer", '"SEG_REDO_FAIL"',
         'handledRedo.remove(nextRedo)\n                        EventLog.log(EventLog.Kind.PIPELINE, "SEG_REDO_FAIL"'),
        ("拼章入口不认已固化", "producer", "if (chapterSealed(ci)) {", ""),
        ("手动拼章不清标记", "audioops", "        clearRedoOfParts(parts)\n        return Result(true, enc.file, kept = kept)",
         "        return Result(true, enc.file, kept = kept)"),
        ("自动拼章不清标记", "audioops", "        clearRedoOfParts(parts)\n        return Result(true, enc.file, kept = parts.size)",
         "        return Result(true, enc.file, kept = parts.size)"),
        ("批量清接口被摘", "segplan", "fun clearSegs(work: File, segs: Collection<Int>)", ""),
        ("章边界即时拼被摘", "producer",
         "chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }", ""),
        ("守恒：重做未清不许拼", "producer",
         "if (chapterRedoLeft(ci)) { deferredChapters.add(ci); return }", ""),
        ("守恒：收尾 force=false", "producer",
         "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = false)",
         "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = true)"),
        ("CI 漏挂", "ci", "python3 scripts/r346_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r346_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = f[key].replace(needle, repl)
        if check(g):
            print("  反向注入 %-22s 拦下" % name)
        else:
            print("  反向注入漏放:", name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    e = check(load())
    if e:
        print("R346 FAIL", len(e))
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R346 已固化章不回头重做 / 推进不卡旧章：PASS")
