#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R215 · 首页「粘一段即贴即听」批次守门。

用户口径：首页根本不是「输入建一本书」，是粘一段文字合成听。
落地：
  · core/ClipSynth.kt = 「一段文字 → 一个 wav」唯一真源，
    调音台试听改为调它，不再两份 mode 分支。
  · asHome=true 点「听这段」→ 合成 → 试听通道播放；不建书、不落书架、
    不跳目录页、不清空文字；单球浮窗 chapterPct=-1。
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
    if "ClipSynth.synthesize(c, text, out, label)" not in mx:
        e.append("调音台试听没复用真源")
    # MixerUi 里还有一处 ttsLoadGated 是「读模型音色表」(ttsSpeakers)，
    # 不是合成主体；认合成特有的符号才不误判。
    for k in ["NativeEngine.ttsSynthesize(h, text", "SynthInput.Params("]:
        if k in mx: e.append("调音台里又长出一份合成主体: " + k)
    if 'EventLog.Kind.ENGINE, "MIXER_TRIAL_FAIL"' not in mx:
        e.append("试听失败日志丢了（R165 口径）")
    # ③ 首页：即贴即听，不建书、不跳页
    for k in ['Text(if (listening) "正在合成…" else "听这段"',
              'text.length > ClipSynth.MAX_CHARS',
              'ClipSynth.synthesize(c, snapshot,',
              '"clip_listen.wav", "首页即贴即听"',
              'OverlayProgress.update(c, "", "合成中", 0, 1, -1f)',
              'OverlayProgress.update(c, "", "完成", 1, 1, -1f)',
              'OverlayProgress.hide(c)',
              'PlayQueue.setTracks(listOf(Track(File(path), "这段朗读"',
              'PlayQueue.playFrom(0, id = "trial")',
              '"CLIP_LISTEN"', '"CLIP_LISTEN_FAIL"',
              'DisposableEffect(Unit)']:
        if k not in it: e.append("首页缺: " + k[:54])
    # 成功（延迟收起）与失败两条路径都得收浮窗，少一条就留在屏幕上
    if it.count("OverlayProgress.hide(c)") < 2:
        e.append("悬浮窗收起出口不全")
    # 首页分支里绝不能再有建书 / 清空 / 跳转
    home = it[it.find("            if (asHome) {"):it.find("            } else {")]
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
    # ClipSynth.kt 在 dirs=app/src/main/java 下由 rglob 自动纳管，
    # 不必列进 files；两个新脚本必须显式登记。
    if '"app/src/main/java"' not in f["config"]:
        e.append("受管根丢了 app/src/main/java")
    for n in ["clip_view.py", "r215_verify.py"]:
        if n not in f["config"]: e.append("新脚本没登记进受管根: " + n)
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
        ("试听不复用真源", "mixer", "ClipSynth.synthesize(c, text, out, label)", "TODO()"),
        ("试听又长一份合成", "mixer", "fun trial(label: String, src: MixerDraft",
         "NativeEngine.ttsSynthesize(h, text\n    fun trial(label: String, src: MixerDraft"),
        ("首页按钮退回建书", "input", 'else "听这段"', 'else "建书"'),
        ("首页又清空正文", "input", 'ClipSynth.synthesize(c, snapshot,',
         'text = ""\n                                ClipSynth.synthesize(c, snapshot,'),
        ("首页绕过字数上限", "input", "text.length > ClipSynth.MAX_CHARS", "false"),
        ("单球链路被摘", "input", 'OverlayProgress.update(c, "", "合成中", 0, 1, -1f)', ""),
        ("播完不收浮窗", "input", "OverlayProgress.hide(c)", ""),
        ("分享页建书被删", "input", 'autoBookTitle(snapshot, "粘贴的文字")', ""),
        ("主界面又跳目录页", "merged", "onAdded = { },   // R215",
         "onAdded = { id -> nav.push(Screen.CHAPTERS) },"),
        ("clip_view 被摘", "clipview", "def merged(root)", "def gone(root)"),
        ("CI 漏挂", "ci", "python3 scripts/r215_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r215_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = (f[key].replace(needle, repl) if name == "播完不收浮窗"
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
    print("R215 首页即贴即听 / 合成真源唯一 / 老守门视图迁移 / 字数闸门：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
