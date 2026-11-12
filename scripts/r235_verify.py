#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R235 · 段级重做 / 调音台运行中生效 / 制作设置落盘 / r95 恒红守门修准。

口径：
  · 长按小块多选后多了「重做选中」：标记 redo 并按当前调音台重新合成这些段
  · 制作过程中改调音台，下一段就按新配置念；音色分套锁在开跑时，不换套不归零
  · 制作设置页 cfg 落盘、目录页启动读全局默认（修真 bug：原先设置只在本次生效）
  · r95 修准不等于放宽：守恒判据一条没删，ttsSynthesize 迁到 core 视图
用法：python3 scripts/r235_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import core_sources
ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "hub": rd(APP / "ChapterHub.kt"),
        "producer": core_sources.source(ROOT).read_text(encoding="utf-8"),
        "segplan": rd(APP / "core/SegPlan.kt"),
        "produce": rd(APP / "ProduceUi.kt"),
        "r95": rd(ROOT / "scripts/r95_verify.py"),
        "parity": rd(ROOT / "scripts/r95_space_parity.py"),
        "agents": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    h, m, u, r, sg = f["hub"], f["producer"], f["produce"], f["r95"], f["segplan"]
    # ① 段级重做 UI（R266 · 制作中只标记，绝不排整章任务进队列）
    for k in ['onRedo = {', 'SegPlan.markSegs(w, idxs)',
              'val redoScope = rememberCoroutineScope()',
              'withContext(Dispatchers.IO) {',
              'EventLog.ui("SEG_REDO",',
              'Producer.enqueueOrStart(c, book, setOf(ch),',
              '"已标记 $n 段，按当前调音台重新合成"',
              '"已标记 $n 段（格子转黄）：当前这段做完马上重做，做完变回绿色"',
              '"重做选中" to onRedo',
              'onAll: () -> Unit, onTrial: () -> Unit, onRedo: () -> Unit, onDelete: () -> Unit']:
        if k not in h: e.append("段级重做 UI 缺: " + k[:56])
    # R297 · 勾 N 段只写一次盘、放 IO；逐段 fsync 会卡死主线程
    for k in ['fun markSegs(work: File, segs: Collection<Int>)',
              'private val redoLock = Any()', 'synchronized(redoLock)']:
        if k not in sg: e.append("R297 批量标记/并发锁缺: " + k[:56])
    for banned in ['rows.forEach { SegPlan.markSeg(work, it.index) }']:
        if banned in h: e.append("R297 逐段 fsync 旧形态回潮: " + banned)
    for k in ['val busyHere = Producer.isBusy()',
              'if (!busyHere && ch != null)',
              'if (!busyHere) Producer.enqueueOrStart(c, book, setOf(ch), null, Prefs.defaultPerf(c))',
              '"第 ${ch + 1} 章已标记重做：当前这段做完就逐段接上"']:
        if k not in h: e.append("R266 制作中不排队列缺: " + k[:56])
    # 重做标记轻量观察：只秒读 redo.json，格子转黄/转绿不靠 stage 合并
    for k in ['var redoKeys by remember(book.id) { mutableStateOf(emptySet<String>()) }',
              'sg.copy(pendingRedo = "seg:${sg.index}" in redoKeys ||',
              'redoKeys = if (w == null) emptySet()',
              'SegPlan.pending(w)']:
        if k not in h: e.append("R266 重做标记观察缺: " + k[:56])
    # R266 / R297 · 主循环就地回头重做：每段收尾只插队一个，不整批堵住
    for k in ['val handledRedo = mutableSetOf<Int>()',
              'val nextRedo = SegPlan.pending(work)',
              '.minOrNull()',
              'ProduceService.touch(c.applicationContext, "重做段',
              'emit("正在重做小块",',
              'NativeEngine.ttsSynthesize(ttsHandle, lj.text,',
              'SegPlan.clearRedo(work, "seg:$nextRedo")',
              'SegPlan.clearRedo(work, "seg:$i")',
              '"SEG_REDO_DONE"', '"SEG_REDO_FAIL"',
              '"已重做 1 个选中的小块，继续往前做"']:
        if k not in m: e.append("R266/R297 回头重做缺: " + k[:56])
    # R297 · 整批同步回潮会重新把进度钉死
    for banned in ['val backlog = SegPlan.pending(work)', 'for (j in backlog)',
                   '"已重做 ${backlog.size} 个选中的小块"']:
        if banned in m: e.append("R297 整批同步重做回潮: " + banned)
    # 旧形态回潮：等整本跑完 / 队列永久暂停
    for banned in ['当前这段读完自动接重做',
                   'if (st.running) Producer.enqueueOrStart(c, book, setOf(ch)']:
        if banned in h: e.append("R266 旧排队形态回潮: " + banned)
    # ② 启动读全局默认
    if 'Producer.enqueueOrStart(c, book, selected, null, Prefs.defaultPerf(c))' not in h:
        e.append("目录页启动没读全局默认制作设置")
    # ③ 制作设置落盘
    if 'Prefs.setDefaultPerf(c, cfg)' not in u:
        e.append("制作设置页 cfg 又没落盘（设置只在本次生效的真 bug 回潮）")
    # ④ 逐段实时参数
    for k in ['data class LiveSeg(val text: String, val speaker: String,',
              'fun liveSeg(i: Int): LiveSeg {',
              'val perf = Prefs.defaultPerf(c)',
              'val voice = Prefs.voiceDescription(c)',
              'Prefs.ttsKnobs(c)',
              'val live = liveSeg(i)',
              'val v = live.params',
              'lastLive = live',
              'val cur = lastLive ?: live',
              'NativeEngine.ttsSynthesize(ttsHandle, cur.text, v.instruct, v.speaker, "chinese",',
              'wav.absolutePath, cur.knobs)',
              'val redoAtStart = SegPlan.pending(work)',
              'if ("seg:$i" in redoAtStart) return@count true',
              'val redoSeg = "seg:$i" in SegPlan.pending(work)',
              'val skip = (covered || already) && !redoSeg',
              'val toSpeak = annotated']:
        if k not in m: e.append("逐段实时参数缺: " + k[:56])
    # 不许回退到开跑前预拼 instruct
    if 'Ann(base.text, base.speaker, built.first)' in m or 'val toSpeak = annotated.map' in m:
        e.append("又把整本 instruct 预先拼进 toSpeak，运行中改设置不会生效")
    # 分套必须只在开跑时做一次
    if m.count('VoiceCache.switchTo(work, cacheKey)') != 1:
        e.append("音色分套被挪进逐段循环，会换套归零（违反 R220）")
    # ⑤ r95 修准：旧锚点零残留、守恒判据迁移
    for k in ['24_000L * 2L * 12L', 'if (estimateBytes > budgetBytes)',
              'SEG_FALLBACK = 24_000 * 2 * 12',
              "def budget(", 'val same = prev?.chapter == chapter',
              "'NativeEngine.ttsSynthesize('"]:
        if k not in r: e.append("r95 没修准: " + k[:50])
    for banned in ['24_000L * 2L * 60L', 'if (needBytes > freeBytes)',
                   'def estimate(', 'SEG_FALLBACK = 24_000 * 2 * 60']:
        if banned in r: e.append("r95 旧锚点回潮: " + banned)
    # ⑥ AGENTS 口径 + 登记
    for k in ['段级重做（R235，R266 修正）', '运行中改设置', 'r95 修准']:
        if k not in f["agents"]: e.append("AGENTS 漏写口径: " + k)
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r235_verify.py --selftest"),
                              ("清单", f["r135"], '"r235_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "app/src/main/java"):
                e.append(name + " 漏登记 R235")
        elif needle not in src: e.append(name + " 漏登记 R235")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("重做按钮被摘", "hub", '"重做选中" to onRedo', ""),
        ("不标记 redo", "hub", 'SegPlan.markSegs(w, idxs)', ""),
        ("重做不启动", "hub", 'Producer.enqueueOrStart(c, book, setOf(ch),', ""),
        ("排队反馈被摘", "hub",
         '"已标记 $n 段（格子转黄）：当前这段做完马上重做，做完变回绿色"', ""),
        ("批量写改回逐段 fsync", "hub", 'SegPlan.markSegs(w, idxs)',
         'idxs.forEach { SegPlan.markSeg(w, it) }'),
        ("批量写回主线程", "hub", 'withContext(Dispatchers.IO) {', ""),
        ("制作中又排整章队列", "hub", "if (!busyHere && ch != null)", "if (ch != null)"),
        ("整章重做制作中又排队列", "hub",
         "if (!busyHere) Producer.enqueueOrStart(c, book, setOf(ch), null, Prefs.defaultPerf(c))",
         "Producer.enqueueOrStart(c, book, setOf(ch), null, Prefs.defaultPerf(c))"),
        ("重做标记观察被摘", "hub",
         'sg.copy(pendingRedo = "seg:${sg.index}" in redoKeys ||', ""),
        ("心跳不读 redo", "hub", "SegPlan.pending(w)", "emptySet()"),
        ("回头重做被摘", "producer", "val nextRedo = SegPlan.pending(work)", ""),
        ("回头重做不清标记", "producer", 'SegPlan.clearRedo(work, "seg:$nextRedo")', ""),
        ("回头重做不算失败", "producer", '"SEG_REDO_FAIL"', ""),
        ("回头重做不切回阶段", "producer", 'emit("正在重做小块",', ""),
        ("整批同步回潮", "producer", ".minOrNull()", ".sorted()"),
        ("重做不喂唤醒锁", "producer", 'ProduceService.touch(c.applicationContext, "重做段', ""),
        ("删段不清标记", "producer", 'SegPlan.clearRedo(work, "seg:$i")', ""),
        ("批量标记被摘", "segplan", 'fun markSegs(work: File, segs: Collection<Int>)', ""),
        ("并发锁被摘", "segplan", 'private val redoLock = Any()', ""),
        ("启动不读默认", "hub", "Producer.enqueueOrStart(c, book, selected, null, Prefs.defaultPerf(c))", "Producer.enqueueOrStart(c, book, selected, null, ProduceConfig())"),
        ("设置不落盘", "produce", "Prefs.setDefaultPerf(c, cfg)", ""),
        ("实时参数函数被摘", "producer", "fun liveSeg(i: Int): LiveSeg {", ""),
        ("又预拼 instruct", "producer", "val toSpeak = annotated", "val toSpeak = annotated.map { ann -> Ann(ann.text, ann.speaker, ann.instruct) }"),
        ("不读实时音色", "producer", "val voice = Prefs.voiceDescription(c)", "val voice = globalVoice"),
        ("不读实时 perf", "producer", "val perf = Prefs.defaultPerf(c)", "val perf = config"),
        ("不读实时 knobs", "producer", "Prefs.ttsKnobs(c)", "knobs"),
        ("合成用旧文本", "producer", "NativeEngine.ttsSynthesize(ttsHandle, cur.text, v.instruct, v.speaker, \"chinese\",", "NativeEngine.ttsSynthesize(ttsHandle, a.text, v.instruct, v.speaker, \"chinese\","),
        ("合成用旧 knobs", "producer", "wav.absolutePath, cur.knobs)", "wav.absolutePath, knobs)"),
        ("redo 不纳入 pending", "producer", 'if ("seg:$i" in redoAtStart) return@count true', ""),
        ("redo 不强制重做", "producer", "val skip = (covered || already) && !redoSeg", "val skip = covered || already"),
        ("分套挪进循环", "producer", "VoiceCache.switchTo(work, cacheKey)", "VoiceCache.switchTo(work, cacheKey)\n            VoiceCache.switchTo(work, cacheKey)"),
        ("r95 旧公式回潮", "r95", "24_000L * 2L * 12L", "24_000L * 2L * 60L"),
        ("r95 守恒被删", "r95", "'NativeEngine.ttsSynthesize('", ""),
        ("CI 漏挂", "ci", "python3 scripts/r235_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r235_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        # 同一锚点可能在文件里出现多次（如 Prefs.ttsKnobs(c) 开跑快照与
        # liveSeg 各一处），注入必须全量替换，留一处就判据恒真、反向注入漏放
        g = dict(f); g[key] = f[key].replace(needle, repl)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R235 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R235 段级重做 / 运行中热更新 / 设置落盘 / r95 修准：PASS")
