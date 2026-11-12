#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R215 / R276 · 首页片段批次守门。

用户口径：首页不是「输入建一本书」，也不是「粘上就响」；是贴一段 →
点合成 → 等几分钟 → 产物进下方留白列表，能播、能删、能导出、能重做。
落地：
  · core/ClipSynth.kt = 「一段文字 → 一个 wav」唯一真源，
    调音台试听改为调它，不再两份 mode 分支。
  · R276 起 core/ClipLibrary.kt 管片段：filesDir/clips 独立一层、
    展示名取正文前 10 字、文件名缀短 id 防重名、登记 + 文件存在双判。
  · asHome=true 点「合成这段」→ 串行合成 → 落 clips → 主播放条播；
    不建书、不落书架、不跳目录页、不清空文字、不进 trial 试听通道。
  · asHome=false（外部分享进来的输入朗读页）仍可建书，能力不删。
  · 老守门经 clip_view.merged() 读到新真源，判据一个字没放宽。

用法：python3 scripts/r215_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
SCRIPTS = ROOT / "scripts"

def files():
    return {
        "clip": (APP / "core/ClipSynth.kt").read_text(encoding="utf-8", errors="replace"),
        "cliplib": (APP / "core/ClipLibrary.kt").read_text(encoding="utf-8", errors="replace"),
        "input": (APP / "InputReadUi.kt").read_text(encoding="utf-8", errors="replace"),
        "mixer": (APP / "MixerUi.kt").read_text(encoding="utf-8", errors="replace"),
        "merged": ui_sources.read(ROOT),
        "clipview": (SCRIPTS / "clip_view.py").read_text(encoding="utf-8", errors="replace"),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
        "config": (ROOT / ".aoci/config.json").read_text(encoding="utf-8", errors="replace"),
    }

def check(f):
    e = []
    cl, it, mx, mg = f["clip"], f["input"], f["mixer"], f["merged"]
    # ① 合成真源：一件事、一个上限、四种取消点、三模式、失败判定
    for k in ["object ClipSynth", "const val MAX_CHARS = 1500",
              "cancelled: () -> Boolean = { false }",
              "ModelStore.Mode.VOICE_DESIGN ->", "ModelStore.Mode.CUSTOM_VOICE ->",
              "ModelStore.Mode.BASE -> SynthInput.Params(null, null)",
              "NativeEngine.ttsSynthesize(h, text, v.instruct, v.speaker",
              "f.length() <= 44", "finally { NativeEngine.ttsFreeGated(h) }",
              '"MIXER_TRIAL"', '"MIXER_TRIAL_EMPTY"']:
        if k not in cl: e.append("ClipSynth 缺: " + k[:54])
    if cl.count("if (cancelled())") != 4:
        e.append("合成取消检查点不是 4 处")
    if "EventLog.Kind.CRASH" in cl:
        e.append("合成失败又记成 CRASH（日志会被污染）")
    for banned in ["BookStore.add(", "ChapterPlan.", "TaskResume.", "ProduceService"]:
        if banned in cl: e.append("合成真源越界去管了: " + banned)
    # ② 试听复用真源，不许再写一份合成主体
    if "ClipSynth.synthesize(c, text, out, label," not in mx:
        e.append("调音台试听没复用真源")
    if "cancelled = { myToken != trialToken || !isActive }" not in mx:
        e.append("试听没把 cancelled 具名传入（尾随 lambda 会误传给 outFile）")
    # MixerUi 里还有一处 ttsLoadGated 是「读模型音色表」(ttsSpeakers)，
    # 不是合成主体；认合成特有的符号才不误判。
    for k in ["NativeEngine.ttsSynthesize(h, text", "SynthInput.Params("]:
        if k in mx: e.append("调音台里又长出一份合成主体: " + k)
    if 'EventLog.Kind.ENGINE, "MIXER_TRIAL_FAIL"' not in mx:
        e.append("试听失败日志丢了（R165 口径）")
    # ③ 首页：点合成 → 落片段 → 主播放条，不建书、不跳页、不进试听通道
    for k in ['Text("▶  " + if (busy > 0) "再合成一段" else "合成这段"',
              'text.length > ClipSynth.MAX_CHARS',
              'ClipSynth.synthesize(c, snapshot,',
              '"clip_listen.wav", "首页片段"',
              # R301 · 旧锚「0, 1 / 1, 1」随整批进度迁移，判据一条没放宽；
              # 另补批次计数、全批才收、连点去重几组真判据。
              'OverlayProgress.update(c, "", "合成中", clipBatchDone, clipBatchTotal,',
              'if (last) "完成" else "合成中"',
              'var clipBatchDone by remember', 'var clipBatchTotal by remember',
              'clipBatchTotal++', 'clipBatchDone++', 'val last = busy == 1',
              'if (busy == 0) {', 'val recentSame = clips.any { old ->',
              'ClipLibrary.text(c, old.id) == text',
              '这段刚合成过，已在列表里',
              'OverlaySource.CLIP',
              'OverlayProgress.hide(c, OverlaySource.CLIP)',
              'ClipLibrary.create(c, snapshot)',
              'ClipLibrary.finish(c, item.id, ok)',
              'PlayQueue.setTracks(all.map {',
              'PlayQueue.playFrom(i)',
              '"CLIP_LISTEN"', '"CLIP_LISTEN_FAIL"',
              'DisposableEffect(Unit)']:
        if k not in it: e.append("首页缺: " + k[:54])
    # 成功（延迟收起）与失败两条路径都得收浮窗，少一条就留在屏幕上
    if it.count("OverlayProgress.hide(c, OverlaySource.CLIP)") < 2:
        e.append("悬浮窗收起出口不全（两条路径都要条件 hide，不能误删制作球）")
    # R301 · 退回「每条各自 0/1」会让连点浮窗互相盖：真机日志 top=100 紧接 top=0
    if 'OverlayProgress.update(c, "", "合成中", 0, 1,' in it:
        e.append("片段浮窗又退回逐条 0/1 硬编码")
    # R301 · 整批没收完就 hide，会把还在排队的片段进度抹掉
    if 'if (busy == 0) {' not in it:
        e.append("片段浮窗没等整批跑完就收起")
    # 片段绝不能退回「播完即丢」的试听通道
    if 'id = "trial"' in it or 'PlayQueue.playFrom(0, id = "trial")' in it:
        e.append("片段又退回 trial 试听通道（会被 sweep 删掉）")
    # ③b 片段层：独立目录、前 10 字命名、登记+文件存在双判、不与书交叉
    lb = f["cliplib"]
    for k in ['object ClipLibrary', 'const val NAME_CHARS = 10',
              'take(NAME_CHARS)', 'File(c.filesDir, "clips")',
              'isRunning && !it.audio.isFile', 'fun playable(c: Context)',
              'fun redoFile(c: Context', 'fun exportTo(c: Context']:
        if k not in lb: e.append("片段层缺: " + k[:54])
    for banned in ["BookStore.", "ChapterPlan.", "AudioExport.collect",
                   "TrialFiles.sweep"]:
        if banned in lb: e.append("片段层越界去管了: " + banned)
    # 首页分支里绝不能再有建书 / 清空 / 跳转
    s = it.find("            if (asHome) {")
    end = it.find("            } else {", s + 1) if s >= 0 else -1
    home = it[s:end] if s >= 0 and end > s else ""
    if not home: e.append("首页 asHome 分支定位不到")
    else:
        for banned in ["BookStore.add", "onAdded(", "text = \"\"", "nav.push"]:
            if banned in home: e.append("首页分支又干了: " + banned)
    # ④ 分享页仍可建书（能力不删，守门不假红）
    if 'autoBookTitle(snapshot, "粘贴的文字")' not in it:
        e.append("外部分享页的建书能力被误删")
    if 'Text(if (adding) "正在加…" else "建书"' not in it:
        e.append("分享页建书按钮被误删")
    # ⑤ 主界面不再把首页建书结果跳目录页
    if 'onAdded = { },   // R215' not in mg:
        e.append("首页仍把建书结果跳目录页")
    # ⑥ 老守门经 clip_view 读新真源，不许退回只读 UI
    for name in ["check_structure.py", "r46_verify.py", "r89_verify.py",
                 "r98_verify.py", "r105_verify.py"]:
        t = (SCRIPTS / name).read_text(encoding="utf-8", errors="replace")
        if "clip_view" not in t:
            e.append("老守门没跟着换视图: " + name)
    if "def merged(root)" not in f["clipview"] or "ui_sources.read(root)" not in f["clipview"]:
        e.append("clip_view 不是合并 UI + 真源")
    # ⑦ 登记
    if "python3 scripts/r215_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r215")
    if '"r215_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r215")
    # R280 · 官方 0.1.1 起受管范围由 managed_scope.rules 表达（index 兜底 +
    # exclude 负空间），不再逐条列 managed_roots；判据换真值源、不放宽。
    from r45_common import scope_indexed
    if not scope_indexed(f["config"], "app/src/main/java"):
        e.append("受管根丢了 app/src/main/java")
    for n in ["clip_view.py", "r215_verify.py"]:
        if not scope_indexed(f["config"], "scripts/" + n):
            e.append("新脚本没登记进受管根: " + n)
    # ⑧ 逻辑对拍
    def gate(text):
        if not text.strip(): return "先放点文字进来"
        if len(text) > 1500: return "太长"
        return "合成"
    for t, exp in [("", "先放点文字进来"), ("一段短文", "合成"),
                   ("字" * 1500, "合成"), ("字" * 1501, "太长")]:
        if gate(t) != exp: e.append("字数闸门对拍失败: %d" % len(t)); break
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("真源上限被摘", "clip", "const val MAX_CHARS = 1500", "const val MAX_CHARS = 0"),
        ("真源取消点被摘", "clip", "if (cancelled())", "if (false)"),
        ("真源失败判定被摘", "clip", "f.length() <= 44", "false"),
        ("真源不释放句柄", "clip", "finally { NativeEngine.ttsFreeGated(h) }", ""),
        ("真源越界建书", "clip", "object ClipSynth", "BookStore.add(\nobject ClipSynth"),
        ("试听不复用真源", "mixer", "ClipSynth.synthesize(c, text, out, label,", "TODO()"),
        ("试听又长一份合成", "mixer", "fun trial(label: String, src: MixerDraft",
         "NativeEngine.ttsSynthesize(h, text\n    fun trial(label: String, src: MixerDraft"),
        ("首页按钮退回建书", "input", 'else "合成这段"', 'else "建书"'),
        ("片段退回试听通道", "input", 'PlayQueue.setTracks(all.map {',
         'PlayQueue.setTracks(listOf(Track(File(""), "")), "trial" //'),
        ("片段目录被摘", "cliplib", 'File(c.filesDir, "clips")', 'File(c.cacheDir, "clips")'),
        ("前十字命名被摘", "cliplib", 'take(NAME_CHARS)', 'take(999)'),
        ("双判被摘", "cliplib", 'isRunning && !it.audio.isFile', 'false'),
        ("首页又清空正文", "input", 'val snapshot = text\n                    val item = ClipLibrary.create',
         'text = ""\n                    val snapshot = text\n                    val item = ClipLibrary.create'),
        ("首页绕过字数上限", "input", "text.length > ClipSynth.MAX_CHARS", "false"),
        ("单球链路被摘", "input",
         'OverlayProgress.update(c, "", "合成中", clipBatchDone, clipBatchTotal,', ""),
        ("CLIP 来源被摘", "input", "OverlaySource.CLIP", "OverlaySource.PREVIEW"),
        ("播完不收浮窗", "input", "OverlayProgress.hide(c, OverlaySource.CLIP)", ""),
        ("分享页建书被删", "input", 'autoBookTitle(snapshot, "粘贴的文字")', ""),
        ("主界面又跳目录页", "merged", "onAdded = { },   // R215",
         "onAdded = { id -> nav.push(Screen.CHAPTERS) },"),
        ("clip_view 被摘", "clipview", "def merged(root)", "def gone(root)"),
        ("CI 漏挂", "ci", "python3 scripts/r215_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r215_verify.py"', ""),
        # R301 · 整批进度与连点去重的反向注入，防止退回逐条 0/1 的老形态
        ("批次总数被摘", "input", "clipBatchTotal++", ""),
        ("批次完成被摘", "input", "clipBatchDone++", ""),
        ("全批才收被摘", "input", "if (busy == 0) {", "if (true) {"),
        ("连点去重被摘", "input", "ClipLibrary.text(c, old.id) == text", "true"),
        ("完成态被摘", "input", 'if (last) "完成" else "合成中"', '"合成中"'),
    ]
    bad = 0
    # 全量替换用例：锚点在源码里出现多次，只摘第一处判据仍恒真、注入会漏放
    FULL = {"播完不收浮窗", "CLIP 来源被摘"}
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = (f[key].replace(needle, repl) if name in FULL
                  else f[key].replace(needle, repl, 1))
        got = check(g)
        if got: print("  反向注入 %-22s 拦下 %d 条" % (name, len(got)))
        else: print("  反向注入漏放: " + name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(files())
    if e:
        print("R215 FAIL", len(e))
        for x in e: print(" -", x)
        return 1
    print("R215/R276 首页片段 / 合成真源唯一 / 独立目录 / 老守门视图迁移 / 字数闸门：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
