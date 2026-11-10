#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R217 · 用户真机反馈两件的守门。

口径：
  · 「悬浮窗的开关好像没有做」——开关本来在设置页「外观与显示」末尾、默认关，
    打开后没系统 overlay 权限会静默不显示。落地为：首页右上角第三枚「悬浮」
    一键开关，打开时无权限直接拉授权页 + 中文提示；设置页同一行显示权限状态
    与「去允许」出口；开关状态由 App 层 state 统一持有。
  · 球上「不应该是 1 和 2」，应写百分之几（0% 写 0、90% 写 90）。数字主体
    保持 0~100 纯数值，右侧补小号 % 标明单位，绝不露段号 / 书名 / 解释。
  · 连带修真 bug：下球原取所有章进度 value 最大值，做到第 2 章还停在第 1 章
    100%；改为按章号 key 取最靠后一章。

用法：python3 scripts/r217_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
SCRIPTS = ROOT / "scripts"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "overlay": rd(APP / "OverlayProgress.kt"),
        "producer": rd(APP / "core/Producer.kt"),
        "settings": rd(APP / "SettingsUi.kt"),
        "input": rd(APP / "InputReadUi.kt"),
        "merged": ui_sources.read(ROOT),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(SCRIPTS / "r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    ov, pr, st, it, mg = f["overlay"], f["producer"], f["settings"], f["input"], f["merged"]
    # ① 数字：0~100 纯数值 + 小 % 号，不许露出段号或旧写法
    for k in ["private fun pctLabel(pct: Float): String",
              "(pct * 100).toInt().coerceIn(0, 100).toString()",
              "private val percentSign = Paint",
              'percentSign.measureText("%")',
              'canvas.drawText(num, cx - signW / 2f - sizePx * 0.01f, baseY, label)',
              'canvas.drawText("%", cx + label.measureText(num) / 2f,',
              "val num = pctLabel(pct)"]:
        if k not in ov: e.append("悬浮窗缺: " + k[:56])
    if "(pct * 100).toInt().toString()" in ov:
        e.append("球上又退回无单位的裸数字（看着像 1、2 段号）")
    if "done.toFloat() / total" not in ov or "chapterPct >= 0f" not in ov:
        e.append("两球进度来源被改坏")
    if "Prefs.overlayProgress(c) && KeepAlive.overlayGranted(c)" not in ov:
        e.append("浮窗权限 / 开关双闸门被摘")
    # ② 下球按章号取当前章，不按 value 最大值
    if "for ((k, v) in chapterProgress) if (k > chIdx) { chIdx = k; chPct = v }" not in pr:
        e.append("下球没按章号取最靠后一章（会停在第 1 章 100%）")
    if "if (v > chPct) chPct = v" in pr:
        e.append("下球又退回取所有章最大值")
    # ③ 设置页：权限状态可见、有去授权出口、关时收起
    for k in ["val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current",
              "var permTick by remember { mutableStateOf(0) }",
              "val permOk = remember(permTick) { KeepAlive.overlayGranted(c) }",
              "fun askPermission()", "KeepAlive.requestOverlay(c)",
              '"还需允许「显示在其他应用上层」"', '"去允许 ›"',
              "else if (!permOk) askPermission()",
              "if (!it) OverlayProgress.hide(c)",
              '"OVERLAY_SETTING", "on=$it · perm=$permOk"']:
        if k not in st: e.append("设置页开关缺: " + k[:56])
    # ④ 首页第三枚开关
    for k in ["overlayOn: Boolean = false", "onOverlayToggle: () -> Unit = {}",
              'Text(if (overlayOn) "悬浮·开" else "悬浮·关"',
              "TextButton(onClick = onOverlayToggle)",
              "color = if (overlayOn) Primary else Sub"]:
        if k not in it: e.append("首页开关缺: " + k[:56])
    # ⑤ App 层接线：state、切换、无权限拉起授权
    for k in ["var overlayOn by remember { mutableStateOf(Prefs.overlayProgress(ctx)) }",
              "overlayOn = overlayOn,", "onOverlayToggle = {",
              "Prefs.setOverlayProgress(ctx, next)", "overlayOn = next",
              "if (!next) OverlayProgress.hide(ctx)",
              "else if (!KeepAlive.overlayGranted(ctx))",
              "KeepAlive.requestOverlay(ctx)",
              '"允许后回来，制作时就能看到悬浮进度"',
              '"OVERLAY_SETTING", "on=$next · from=home"']:
        if k not in mg: e.append("主界面接线缺: " + k[:56])
    # ⑥ 登记
    if "python3 scripts/r217_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r217")
    if '"r217_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r217")
    if "r217_verify.py" not in f["config"]:
        e.append("新守门没登记进受管根")
    # ⑦ 数字对拍：0% 写 0、90% 写 90、越界夹住
    def label(p): return str(max(0, min(100, int(p * 100))))
    for pct, want in [(0.0, "0"), (0.9, "90"), (1.0, "100"),
                      (0.012, "1"), (-0.2, "0"), (1.4, "100")]:
        if label(pct) != want: e.append("百分比数值对拍失败: %s→%s" % (pct, want)); break
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("百分比函数被摘", "overlay", "private fun pctLabel(pct: Float): String", ""),
        ("数字越界不夹", "overlay", ".coerceIn(0, 100)", ""),
        ("% 单位被摘", "overlay", 'canvas.drawText("%", cx + label.measureText(num) / 2f,', ""),
        ("退回裸数字", "overlay", "val num = pctLabel(pct)",
         'val num = (pct * 100).toInt().toString()'),
        ("双球来源被摘", "overlay", "chapterPct >= 0f", "false"),
        ("权限闸门被摘", "overlay", "Prefs.overlayProgress(c) && KeepAlive.overlayGranted(c)", "true"),
        ("下球退回最大值", "producer",
         "for ((k, v) in chapterProgress) if (k > chIdx) { chIdx = k; chPct = v }",
         "for ((_, v) in chapterProgress) if (v > chPct) chPct = v"),
        ("设置页权限状态被摘", "settings",
         "val permOk = remember(permTick) { KeepAlive.overlayGranted(c) }", "val permOk = true"),
        ("去允许出口被摘", "settings", '"去允许 ›"', ""),
        ("打开无权限又静默", "settings", "else if (!permOk) askPermission()", ""),
        ("关时不收浮窗", "settings", "if (!it) OverlayProgress.hide(c)", ""),
        ("首页开关参数被摘", "input", "overlayOn: Boolean = false", ""),
        ("首页开关按钮被摘", "input", "TextButton(onClick = onOverlayToggle)", ""),
        ("首页开关不显状态", "input", 'if (overlayOn) "悬浮·开" else "悬浮·关"', '"悬浮"'),
        ("App 层 state 被摘", "merged",
         "var overlayOn by remember { mutableStateOf(Prefs.overlayProgress(ctx)) }", ""),
        ("切换不落盘", "merged", "Prefs.setOverlayProgress(ctx, next)", ""),
        ("无权限不引导", "merged", "KeepAlive.requestOverlay(ctx)", ""),
        ("首页开关不接线", "merged", "overlayOn = overlayOn,", ""),
        ("CI 漏挂", "ci", "python3 scripts/r217_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r217_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        got = check(g)
        if got: print("  反向注入 %-24s 拦下 %d 条" % (name, len(got)))
        else: print("  反向注入漏放: " + name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(files())
    if e:
        print("R217 FAIL", len(e))
        for x in e: print(" -", x)
        return 1
    print("R217 悬浮窗开关够得到 / 百分比数字 / 下球取当前章：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
