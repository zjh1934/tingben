#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R214 · 主动推进批次：悬浮窗双球可拖 + 首页三处 + 弹层收小 + 进度链路。

用户口径（自己拍板，不再等补话）：
  整本书合成 = 两球上下叠成 8 字（上=整本圆环、下=章节进度）；
  首页即贴即听 = 单球；圆环中间写数字、外圈按百分比转；浮窗啥也不写、可拖动。
  首页框 1/3 屏、粘贴钮进框内右下、右上角调音 + 任务双入口（无任务置灰）。
  调音弹层上下收小，仍避让状态栏与手势区。

用法：python3 scripts/r214_verify.py [--selftest]
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
        "overlay": (APP / "OverlayProgress.kt").read_text(encoding="utf-8", errors="replace"),
        "service": (APP / "ProduceService.kt").read_text(encoding="utf-8", errors="replace"),
        "producer": (APP / "core/Producer.kt").read_text(encoding="utf-8", errors="replace"),
        "input": (APP / "InputReadUi.kt").read_text(encoding="utf-8", errors="replace"),
        "mixer": (APP / "MixerUi.kt").read_text(encoding="utf-8", errors="replace"),
        "merged": ui_sources.read(ROOT),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
    }

def check(f):
    e = []
    ov, sv, pr = f["overlay"], f["service"], f["producer"]
    it, mx, mg = f["input"], f["mixer"], f["merged"]
    # ① 悬浮窗：双球圆环 + 可拖动 + 不写字 + 两态
    for k in ["private class RingView", "canvas.drawArc(oval, -90f, 360f * pct, false, arc)",
              "Gravity.TOP or Gravity.START", "MotionEvent.ACTION_MOVE",
              "updateViewLayout(v, params)", "chapterPct >= 0f",
              "bottomRing?.visibility = View.GONE"]:
        if k not in ov: e.append("悬浮窗缺: " + k[:52])
    if "import android.widget.TextView" in ov or "TextView(c)" in ov:
        e.append("悬浮窗又写起文字")
    if "cancelIntent" in ov:
        e.append("悬浮窗又挂停止按钮")
    if "wm?.removeViewImmediate(v)" not in ov:
        e.append("hide 没 removeViewImmediate（WindowManager 泄漏）")
    # ② 链路：章节进度必须真的传下去
    if "chapterPct: Float = -1f" not in sv:
        e.append("ProduceService.update 没接章节进度")
    if "OverlayProgress.update(c, lastBook, stage, done, total, chapterPct)" not in sv:
        e.append("章节进度没透传到浮窗")
    if "for ((k, v) in chapterProgress) if (k > chIdx) { chIdx = k; chPct = v }" not in pr:
        e.append("Producer.emit 没取章节进度")
    # ③ R230 · 输入框 1/3 屏 + 粘贴钮进框内（内容区）；
    #    调音/任务入口上移系统顶栏，按符号在合并视图里守。
    for k in ["fillMaxHeight(0.36f)", "Alignment.BottomEnd"]:
        if k not in it: e.append("首页缺: " + k[:52])
    # R244 · 按图标符号定位，不绑字按钮文案
    for k in ['MixerIcon(', 'TasksIcon(',
              "enabled = homeHasTask",
              "TasksIcon(if (homeHasTask) Primary else Sub)",  # R244 · 置灰改走图标 tint
              "val homeHasTask = remember(refresh, bookImporting)"]:
        if k not in mg: e.append("顶栏入口缺: " + k[:52])
    if "Modifier.fillMaxWidth().weight(1f)" in it:
        e.append("输入框又退回占满整屏")
    # ④ 弹层收小但仍避让导航
    if "fillMaxHeight(0.86f)" not in mx:
        e.append("调音弹层没收小")
    for k in ["statusBarsPadding()", "navigationBarsPadding()"]:
        if k not in mx: e.append("弹层丢了避让: " + k)
    if "Modifier.fillMaxSize()" in mx.split("MixerDialog")[1][:400]:
        e.append("弹层又全屏撑满")
    # ⑤ MainActivity 接线：onTasks / hasTask 不能是空实现
    for k in ["chapterMixerOpen = true", "hubOpenTasks = true",
              "TaskResume.queue(ctx).isNotEmpty()", "Screen.LIBRARY -> {"]:
        if k not in mg: e.append("主界面接线缺: " + k[:52])
    # ⑥ 登记
    if "python3 scripts/r214_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r214")
    if '"r214_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r214")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("圆环描边被摘", "overlay", "canvas.drawArc(oval, -90f, 360f * pct, false, arc)", ""),
        ("拖动被摘", "overlay", "MotionEvent.ACTION_MOVE", "MotionEvent.ACTION_UP"),
        ("updateViewLayout 被摘", "overlay", "updateViewLayout(v, params)", ""),
        ("两态开关被摘", "overlay", "chapterPct >= 0f", "false"),
        ("浮窗又写字", "overlay", "import android.graphics.Canvas",
         "import android.widget.TextView\nimport android.graphics.Canvas"),
        ("removeView 被摘", "overlay", "wm?.removeViewImmediate(v)", ""),
        ("链路没接章节", "service", "chapterPct: Float = -1f", ""),
        ("链路没透传", "service", "total, chapterPct)", "total)"),
        ("emit 没算章节", "producer",
         "for ((k, v) in chapterProgress) if (k > chIdx) { chIdx = k; chPct = v }", ""),
        ("框又占满屏", "input", "fillMaxHeight(0.36f)", "weight(1f)"),
        ("粘贴钮出框", "input", "Alignment.BottomEnd", "Alignment.TopEnd"),
        ("任务入口不置灰", "merged", "enabled = homeHasTask", "enabled = true"),
        ("弹层没收小", "mixer", "fillMaxHeight(0.86f)", "fillMaxSize()"),
        ("弹层丢导航避让", "mixer", "navigationBarsPadding()", ""),
        ("主界面没接任务", "merged",
         "val homeHasTask = remember(refresh, bookImporting)", "val homeHasTask = false"),
        ("CI 漏挂", "ci", "python3 scripts/r214_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r214_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
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
        print("R214 FAIL", len(e))
        for x in e: print(" -", x)
        return 1
    print("R214 悬浮窗双球可拖 / 首页三处 / 弹层收小 / 章节进度链路：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
