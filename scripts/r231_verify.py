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
        "lib": rd(CORE / "AudioLibrary.kt"),
        "export": rd(CORE / "AudioExport.kt"),
        "producer": rd(CORE / "Producer.kt"),
        "ui": rd(APP / "ProduceUi.kt"),
        "hub": rd(APP / "ChapterHub.kt"),
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
    pf, wv, op, lib, pr, ui, ag = (f["prefs"], f["wav"], f["ops"], f["lib"],
                                   f["producer"], f["ui"], f["agents"])
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
        # R255 · 用户改口径：整章拼好就清中间小块。约束不是放宽而是换严：
        # 必须「先写 chapter_done 再删小块」（否则续跑会把整章重做一遍），
        # 且 markDone 失败要有保留小块的兜底。顺序由下面的下标比较守住。
        for k in ["AudioTree.markDone", "if (!marked)", "deleteAll(",
                  "AudioTree.forget", "CHAPTER_MERGE_CLEAN"]:
            if k not in body: e.append("R255 清小块缺: " + k[:56])
        i_mark = body.find("AudioTree.markDone")
        i_keep = body.find("if (!marked)")
        i_del = body.find("deleteAll(")
        if not (0 <= i_mark < i_keep < i_del):
            e.append("R255 顺序错：没先写章状态/没兜底就删了小块")
    # ④ R272 · 收尾只按章拼，不许再生成整本.wav
    # R242 · rc #204 真编译实证：c=appCtx 是 Context?，守卫被摘会报
    # Argument type mismatch（静态守门抓不到），单列一条硬判据。
    for k in ['val c = appCtx',
              'val byCh = LinkedHashMap<Int, MutableList<File>>()',
              "AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,",
              "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = true)",
              'val merged = if (c != null)',
              "AudioLibrary.bookDir(c, book.id, book.title).absolutePath",
              'chapters=$totalMade immediate=$chaptersMade late=${merged.first}']:
        if k not in pr: e.append("R272 收尾按章拼缺: " + k[:56])
    for k in ["WavMerger.merge(wavs, outFile, config.gapMs)",
              "AudioLibrary.bookWholeFile(", "AudioLibrary.scopeFile(",
              "AudioLibrary.dropWholeBook(", '"AUDIO_REGISTER"',
              '"WHOLE_BOOK_STALE"', "File(outFile.parentFile,"]:
        if k in pr: e.append("R272 整本大文件链路回潮: " + k[:56])
    for k in ["force: Boolean = false",
              "if (!force && !Prefs.autoMergeChapter(c)) return 0 to 0"]:
        if k not in op: e.append("R272 收尾强制拼章缺: " + k[:56])
    for k in ["AudioLibrary.isWholeBook(it) && it.file.isFile",
              'Item(dir, "整本.wav"', "val whole = all.filter"]:
        if k in f["export"]: e.append("R272 导出又收整本: " + k[:56])
    if "if (items.isEmpty()) continue" not in f["export"]:
        e.append("R272 导出空书跳过缺")
    # R243 · 回溯补齐：装新版前已合成的小段也要长出章节成品
    for k in ["AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,",
              "if (made > 0) localTick++"]:
        if k not in f["hub"]: e.append("目录页回溯补齐缺: " + k[:56])
    # R301 · 正常跑完的书别每次进目录页全量读 segmap：1.1.32 真机日志
    # 7 次「补拼 0 章 / 跳过 3 章」全是白烧 IO。中断/停止/待确认仍要补。
    for k in ["AudioLibrary.chapterSnapshot(c, book.id).second.isNotEmpty()",
              "if (hasAudio && !pendingPhase) return@withContext 0",
              't.phase == "interrupted"', 't.phase == "stopped"']:
        if k not in f["hub"]: e.append("目录页回溯短路缺: " + k[:56])
    # R265 · 唯一真源：补拼按宽松口径去重（旧版整本成品多章不算、单章算）。
    # R272 后整本.wav 不再新生成，但兼容读取旧盘数据的口径一条不放宽。
    for k in ["if (AudioLibrary.hasChapterAudio(c, bookId, ci))",
              "parts.any { !it.isFile || it.length() <= 44L }",
              '"CHAPTER_MERGE_ENSURE"']:
        if k not in op: e.append("章节补拼真源缺: " + k[:56])
    for k in ["fun isWholeBook(item: AudioItem): Boolean",
              "fun hasChapterAudio(c: Context, bookId: String, chapterIndex: Int): Boolean",
              "fun chapterOnlyFor(c: Context, bookId: String, chapterIndex: Int): AudioItem?",
              "!isWholeBook(it) && it.file.length() > 44L",
              "!isWholeBook(it) || it.chapters.size == 1",
              'item.file.name == "整本.wav"']:
        if k not in lib: e.append("旧整本兼容读取缺: " + k[:56])
    for k in ["fun bookWholeFile(", "fun scopeFile(", "fun dropWholeBook("]:
        if k in lib: e.append("R272 整本路径函数回潮: " + k[:56])
    if "AudioLibrary.chapterOnlyFor(c, book.id, ch)?.let" not in f["hub"]:
        e.append("整章重做误删旧整本成品")
    for k in ["if (AudioLibrary.audioFor(c, bookId, ci) != null)",
              "if (AudioLibrary.chapterOnlyFor(c, bookId, ci) != null)"]:
        if k in op: e.append("R265 旧去重口径回潮: " + k[:56])
    # R225 脱敏：补拼日志行里不许出现书名
    log_line = next((l for l in op.splitlines() if '"CHAPTER_MERGE_ENSURE"' in l), "")
    if "补拼 $made 章" not in op or not log_line or "book.title" in log_line or "bookTitle" in log_line:
        e.append("补拼日志缺或带书名原文")
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
    # ⑧ R270 · 小块只在制作中存在，拼章后清掉，与章级成品互斥。
    # 整章重做（针对已拼好的章）才需要撤旧成品 + 清 done；小块重做（制作中）
    # 那一章本来就没有章成品，不许给它挂失效、也不许 hold 住补拼（会让小块留着不删）。
    for k in ["fun invalidateChapterAudio(ch: Int) {",
              "AudioTree.clearDone(c, book.id, ch)"]:
        if k not in f["hub"]: e.append("R270 整章重做撤成品函数缺: " + k[:56])
    if "invalidateChapterAudio(ch)" not in f["hub"]:
        e.append("R270 整章重做没撤旧章成品")
    # 按 onRedo 块定位：小块重做路径里不许出现任何章成品失效调用
    h = f["hub"]
    a = h.find("onRedo = {")
    b = h.find("exitSegMulti()", a)
    if a < 0 or b < 0 or "invalidateChapterAudio" in h[a:b]:
        e.append("R270 小块重做路径挂了章成品失效（制作中那章没有章成品）")
    for k in ["holdChapters", "if (ci in holdChapters)"]:
        if k in op: e.append("R270 holdChapters 回潮（会让收尾跳过补拼、小块留着不删）")
    for nm, src in [("制作收尾", pr), ("目录页回溯", f["hub"])]:
        if "holdChapters = hold" in src:
            e.append("R270 " + nm + "仍在传待重做章")
    # ⑨ R272 · 整本.wav 不再新生成、不登记、不导出、不作废（R268/R271 整条伪需求删）
    # 只判代码串，不判注释里也会出现的字面量
    for k in ["AudioLibrary.dropWholeBook(", "AudioLibrary.bookWholeFile(",
              "AudioLibrary.scopeFile(", "val outFile = if (only.isNullOrEmpty()",
              "val item = AudioLibrary.register(c, book.id, book.title, only, direct"]:
        if k in pr: e.append("R272 收尾又碰整本成品: " + k[:56])
    for k in ["AudioLibrary.isWholeBook(it) && it.file.isFile",
              'Item(dir, "整本.wav", whole.file)']:
        if k in f["export"]: e.append("R272 导出又碰整本成品: " + k[:56])
    # 旧盘整本.wav 仍要能被 list() 兼容读到，不许把兼容口径删掉
    if "candidateDirs(c)" not in lib or "整本（旧版）" not in lib:
        e.append("R272 旧版整本兼容读取被删（会丢用户已有音频）")
    # ⑦ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r231_verify.py --selftest"),
                              ("清单", f["r135"], '"r231_verify.py"'),
                              ("受管根", f["config"], "r231_verify.py")]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "scripts/r231_verify.py"):
                e.append(name + " 漏登记 r231")
        elif needle not in src: e.append(name + " 漏登记 r231")
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
        # R255 · 旧注入往函数里塞 forget 看是否变红；现在 forget 本就在，
        # 换成摘掉「先写章状态」与「失败保留」两处真约束。
        ("清小块没写章状态", "ops", "AudioTree.markDone(it, chapter, dst)", ""),
        ("章状态失败没兜底", "ops", "if (!marked) {", "if (false) {"),
        ("收尾不强制拼章", "producer", "force = true)", "force = false)"),
        ("按章拼调用被摘", "producer",
         "AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,", ""),
        ("回溯补齐被摘", "hub",
         "AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,", ""),
        ("回溯短路被摘", "hub",
         "if (hasAudio && !pendingPhase) return@withContext 0", ""),
        ("补拼去重被摘", "ops",
         "if (AudioLibrary.hasChapterAudio(c, bookId, ci))", ""),
        ("退回整本占章去重", "ops",
         "if (AudioLibrary.hasChapterAudio(c, bookId, ci))",
         "if (AudioLibrary.audioFor(c, bookId, ci) != null)"),
        ("单章整本不算章成品", "lib",
         "!isWholeBook(it) || it.chapters.size == 1", "!isWholeBook(it)"),
        ("严格口径被放宽", "lib", "!isWholeBook(it) && it.file.length() > 44L", ""),
        ("整章重做又删整本", "hub",
         "AudioLibrary.chapterOnlyFor(c, book.id, ch)?.let",
         "AudioLibrary.audioFor(c, book.id, ch)?.let"),
        ("接缝配置没透传", "producer",
         "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c)", "gapMs = 0, declickMs = 0"),
        ("③ 分组被摘", "ui", 'ExpandGroup("③ 拼成这一章"', ""),
        ("自动拼选项被摘", "ui", 'OptionRow("自动拼成整章"', ""),
        ("接缝选项被摘", "ui", 'OptionRow("接缝处理"', ""),
        ("选项不落盘", "ui", "Prefs.setAutoMergeChapter(c, it)", ""),
        ("整章重做不撤章成品", "hub", "invalidateChapterAudio(ch)", ""),
        ("失效不连文件删", "hub",
         "AudioLibrary.chapterOnlyFor(c, book.id, ch)?.let { AudioLibrary.removeFile(c, it.file) }", ""),
        ("失效不清 done 记录", "hub", "AudioTree.clearDone(c, book.id, ch)", ""),
        ("小块重做回挂失效", "hub", "val busyHere = Producer.isBusy()",
         "val busyHere = Producer.isBusy(); invalidateChapterAudio(liveChapter ?: 0)"),
        ("holdChapters 回潮", "ops", "if (!force && !Prefs.autoMergeChapter(c)) return 0 to 0",
         "holdChapters: Set<Int> = emptySet()\n        if (!force && !Prefs.autoMergeChapter(c)) return 0 to 0"),
        ("收尾回传待重做章", "producer", "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c)",
         "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), holdChapters = hold"),
        ("收尾又拼整本", "producer",
         "val merged = if (c != null) AudioOps.ensureChapterAudios(",
         "val merged = if (c != null) { WavMerger.merge(wavs, outFile, config.gapMs); AudioOps.ensureChapterAudios("),
        ("导出又收整本", "export", "if (items.isEmpty()) continue",
         'if (items.isEmpty()) { val whole = AudioLibrary.list(c).firstOrNull(); if (whole != null) out += Item(dir, "整本.wav", whole.file); continue }'),
        ("整本路径函数回潮", "lib", "fun isWholeBook(item: AudioItem): Boolean",
         'fun scopeFile(c: Context, bookId: String, only: Set<Int>): File = File("")\n    fun isWholeBook(item: AudioItem): Boolean'),
        ("旧版兼容被删", "lib", "整本（旧版）", "未知"),
        ("借鉴来源被抹", "agents", "Ensemble Reads", "自造方案"),
        ("CI 漏挂", "ci", "python3 scripts/r231_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r231_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        # R243 · 全量替换，避免自指锚点留一份导致注入恒不红
        g = dict(f); g[key] = f[key].replace(needle, repl)
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
