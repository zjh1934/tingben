# -*- coding: utf-8 -*-
"""R130 · 统一页面层级与返回表验证。

守：一张返回表定义「哪一页回哪一页」/ 根级页无返回键 / 二级页统一由顶栏给 /
全局回退唯一出口 / 栈空按表兜底 / 返回键是代码绘制真图标而非字符 /
页内重复返回按钮零残留。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r130_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "chrome": APP / "AppChrome.kt",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "chrome": [
        "val canBack: Boolean = false",
        "onBack: (() -> Unit)? = null,",
        "if (spec.canBack && onBack != null)",
        "IconButton(onClick = onBack) { BackChevron(primary) }",
        "private fun BackChevron(tint: Color)",
        "drawPath(",
        "StrokeCap.Round",
    ],
    "main": [
        "private val ROOT_SCREENS = setOf(",
        "private fun canGoBack(s: Screen): Boolean = s !in ROOT_SCREENS",
        "private fun backTargetOf(s: Screen, modelsFrom: Screen): Screen =",
        "Screen.MODELS -> modelsFrom",
        "fun goBack() {",
        "if (nav.canPop()) nav.pop()",
        "screen = backTargetOf(screen, modelsFrom)",
        "val backAction: () -> Unit = { goBack() }",
        "onBack = if (chrome.canBack) backAction else null",
        "canBack = true",
    ],
    "ci": ["python3 scripts/r130_verify.py --selftest"],
}

BAN = {
    "main": [
        'Text("‹ 任务"',
        'Text("‹ 返回"',
        "BackHandler(enabled = screen != Screen.LIBRARY || nav.canPop()) { nav.pop() }",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:66]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:66]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "壳层丢 canBack": ("chrome", "val canBack: Boolean = false"),
        "顶栏不给返回键": ("chrome", "IconButton(onClick = onBack) { BackChevron(primary) }"),
        "图标退回字符": ("chrome", "drawPath("),
        "返回表被摘": ("main", "private fun backTargetOf(s: Screen, modelsFrom: Screen): Screen ="),
        "模型页来源丢失": ("main", "Screen.MODELS -> modelsFrom"),
        "栈空不按表兜底": ("main", "screen = backTargetOf(screen, modelsFrom)"),
        "根级页判定被摘": ("main", "private fun canGoBack(s: Screen): Boolean = s !in ROOT_SCREENS"),
        "顶栏不接统一出口": ("main", "onBack = if (chrome.canBack) backAction else null"),
        "二级页没标 canBack": ("main", "canBack = true"),
    }
    bad = 0
    for name, (k, needle) in cuts.items():
        x = dict(f)
        x[k] = f[k].replace(needle, "/* removed */")
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for k, olds in BAN.items():
        for old in olds:
            x = dict(f)
            x[k] = f[k] + chr(10) + "// " + old + chr(10)
            if not check(x):
                bad += 1
                print("  旧形态注入漏放:", old[:44])
    total = len(cuts) + sum(len(v) for v in BAN.values())
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R130 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R130 统一返回表 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
