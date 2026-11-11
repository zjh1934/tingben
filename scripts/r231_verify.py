#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R231 · 拼的是「这一章」不是整本；制作选项卡补拼接设置；
拼接借鉴同行无损路线并加格式一致性前置校验。

用法：python3 scripts/r231_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources, core_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
CORE = APP / "core"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "prefs": rd(CORE / "Prefs.kt"),
        "wav": rd(CORE / "WavMerger.kt"),
        "ops": rd(CORE / "AudioOps.kt"),
        "producer": rd(CORE / "Producer.kt"),
        "ui": rd(APP / "ProduceUi.kt"),
        "agents": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def auto_body(ops: str) -> str:
    i = ops.find("fun mergeChapterAuto(")
    j = ops.find("    fun mergedTemp(", i)
    return ops[i:j] if i >= 0 and j > i else ""

def check(f):
    e = []
    pf, wv, op, pr, ui, ag = (f["prefs"], f["wav"], f["ops"], f["producer"],
                              f["ui"], f["agents"])
    # ① 拼接偏好在 Prefs，不进 ProduceConfig（音色指纹不动）
    for k in ['KEY_MERGE_AUTO = "mergeAutoChapter"',
              'KEY_MERGE_DECLICK = "mergeDeclickMs"',
              "fun autoMergeChapter(c: Context) = sp(c).getBoolean(KEY_MERGE_AUTO, true)",
              "fun setAutoMergeChapter(c: Context, v: Boolean)",
              "fun mergeDeclickMs(c: Context) = sp(c).getInt(KEY_MERGE_DECLICK, 30).coerceIn(0, 60)",
              "fun setMergeDeclickMs(c: Context, v: Int)"]:
        if k not in pf: e.append("拼接偏好缺: " + k[:56])
    # ② 格式一致性前置校验（同行无损拼接前提）
    for k in ["private fun fmtOf(f: File): Triple<Int, Int, Int>?",
              "val fmts = ArrayList<Triple<Int, Int, Int>>(parts.size)",
              "MERGE_FMT_UNREADABLE", "MERGE_FMT_MISMATCH",
              "val want = Triple(24000, 1, 16)",
              "if (fmts.any { it != want })"]:
        if k not in wv: e.append("格式校验缺: " + k[:56])
    # ③ 自动按章拼：只增不删
    body = auto_body(op)
    if not body: e.append("mergeChapterAuto 定位不到")
    else:
        for k in ["WavMerger.merge(parts = parts, out = dst, gapMs = gapMs, declickMs = declickMs)",
                  "only = setOf(chapter),",
                  "CHAPTER_MERGE_ONE"]:
            if k not in body: e.append("按章拼缺: " + k[:56])
        for banned in ["deleteAll(", "AudioTree.forget", "AudioTree.markDone"]:
            if banned in body: e.append("自动拼章动了小块/续跑: " + banned)
    # ④ Producer 收尾按章拼，整本成品仍在
    # R242 · rc #204 真编译实证：c=appCtx 是 Context?，守卫被摘会报
    # Argument type mismatch（静态守门抓不到），单列一条硬判据。
    if 'if (c != null && Prefs.autoMergeChapter(c)) {' not in f["producer"]:
        e.append("R231 块丢了 c 非空守卫（真编译会报 Context? 不匹配）")
    for k in ['if (c != null && Prefs.autoMergeChapter(c)) {',
              'val declick = Prefs.mergeDeclickMs(c)',
              'val byCh = LinkedHashMap<Int, MutableList<File>>()',
              "AudioOps.mergeChapterAuto(c, book.id, book.title, ci, parts,",
              "gapMs = config.gapMs, declickMs = declick",
              "CHAPTER_MERGE_AUTO",
              "WavMerger.merge(wavs, outFile, config.gapMs)"]:
        if k not in pr: e.append("制作收尾缺: " + k[:56])
    # R225 脱敏：日志行里不许出现书名；传参给 register 的 book.title 不算日志
    log_line = next((l for l in pr.splitlines() if '"CHAPTER_MERGE_AUTO"' in l), "")
    if "拼好 $made 章" not in pr or not log_line or "book.title" in log_line or "bookTitle" in log_line:
        e.append("自动拼章日志缺或带书名原文")
    # ⑤ 制作选项卡补 ③ 拼接设置
    for k in ['var autoMerge by remember { mutableStateOf(Prefs.autoMergeChapter(c)) }',
              'var declickMs by remember { mutableStateOf(Prefs.mergeDeclickMs(c)) }',
              'ExpandGroup("③ 拼成这一章"',
              'OptionRow("自动拼成整章"',
              'OptionRow("接缝处理"',
              "Prefs.setAutoMergeChapter(c, it)", "Prefs.setMergeDeclickMs(c, it)"]:
        if k not in ui: e.append("拼接设置缺: " + k[:56])
    # ⑥ 借鉴来源是同行开源，不是自造
    for k in ["Ensemble Reads", "Merge Audio", "android_audio_mixer",
              "MediaExtractor", "不引 FFmpeg"]:
        if k not in ag: e.append("AGENTS 借鉴清单缺: " + k[:48])
    # ⑦ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r231_verify.py --selftest"),
                              ("清单", f["r135"], '"r231_verify.py"'),
                              ("受管根", f["config"], "r231_verify.py")]:
        if needle not in src: e.append(name + " 漏登记 r231")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("自动拼章偏好被摘", "prefs",
         "fun autoMergeChapter(c: Context) = sp(c).getBoolean(KEY_MERGE_AUTO, true)",
         "fun autoMergeChapter(c: Context) = false"),
        ("接缝偏好被摘", "prefs",
         "fun mergeDeclickMs(c: Context) = sp(c).getInt(KEY_MERGE_DECLICK, 30).coerceIn(0, 60)", ""),
        ("格式校验函数被摘", "wav",
         "private fun fmtOf(f: File): Triple<Int, Int, Int>?", ""),
        ("格式不一致仍硬拼", "wav", "if (fmts.any { it != want })", "if (false)"),
        ("读不出头仍硬拼", "wav", "MERGE_FMT_UNREADABLE", "MERGE_FMT_OK"),
        ("按章拼函数调用被摘", "ops",
         "WavMerger.merge(parts = parts, out = dst, gapMs = gapMs, declickMs = declickMs)", ""),
        ("按章拼没登记", "ops", "only = setOf(chapter),", ""),
        ("自动拼章删小块", "ops", "EventLog.log(EventLog.Kind.PIPELINE, \"CHAPTER_MERGE_ONE\"",
         "AudioTree.forget(c, parts.map { it.absolutePath })\n        EventLog.log(EventLog.Kind.PIPELINE, \"CHAPTER_MERGE_ONE\""),
        ("开关被摘", "producer", "if (c != null && Prefs.autoMergeChapter(c)) {", "if (false) {"),
        ("按章拼调用被摘", "producer",
         "AudioOps.mergeChapterAuto(c, book.id, book.title, ci, parts,", ""),
        ("整本成品被摘", "producer",
         "WavMerger.merge(wavs, outFile, config.gapMs)", ""),
        ("接缝配置没透传", "producer",
         "gapMs = config.gapMs, declickMs = declick", "gapMs = 0, declickMs = 0"),
        ("③ 分组被摘", "ui", 'ExpandGroup("③ 拼成这一章"', ""),
        ("自动拼选项被摘", "ui", 'OptionRow("自动拼成整章"', ""),
        ("接缝选项被摘", "ui", 'OptionRow("接缝处理"', ""),
        ("选项不落盘", "ui", "Prefs.setAutoMergeChapter(c, it)", ""),
        ("借鉴来源被抹", "agents", "Ensemble Reads", "自造方案"),
        ("CI 漏挂", "ci", "python3 scripts/r231_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r231_verify.py"', ""),
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
        print("R231 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R231 按章拼成品 / 拼接设置 / 格式前置校验：PASS")
