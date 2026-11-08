# -*- coding: utf-8 -*-
"""R89（R162 重定稿）· 导航壳重排验证。

历史口径：调音台进一级抽屉 / 抽屉 6 项守恒。
R162 用户把目标定死：主页输入框 / 抽屉放书 / 右上角 ＋ 与齿轮 /
模型收进设置 / 调音台合成前弹层 / 任务与音频管理页撤掉常驻入口。

现在守的是「能力搬干净了没有」，不再守菜单长什么样：
  · 功能菜单零回潮，抽屉里是书列表
  · 7 个 NAV_ 兼容常量仍在（旧状态恢复用）
  · Screen 8 值、分发与 chromeOf 对齐
  · 调音台是合成前弹层，模型在设置页，主页是输入框
  · 反向注入覆盖「旧菜单回潮 / 主页变回书架 / 齿轮消失 / 弹层退回独立页」
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nav_semantics as nav
import shell_semantics as shell

ROOT = pathlib.Path(__file__).resolve().parents[1]
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"

EXPECTED = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS",
            "SETTINGS", "MODELS", "MIXER", "CHAPTERS"}


def check(files):
    errs = []
    ch, main, ci = files["chrome"], files["main"], files["ci"]

    for msg in shell.checks(main, ch):
        errs.append("新壳层: " + msg)
    for msg in nav.semantics(main):
        errs.append("导航语义: " + msg)

    if nav.screen_enum(main) != EXPECTED:
        errs.append("Screen 枚举集合与 8 值不符")
    for s in ('fun commitAll()', 'if (!st.running) return@LaunchedEffect'):
        if s not in main + ch:
            errs.append("守恒丢失: " + s[:52])
    if 'r89_verify.py' not in ci:
        errs.append("CI 未挂 r89_verify")
    return errs


def txt(p):
    return p.read_text(encoding="utf-8", errors="replace")


def main():
    files = {"chrome": txt(CHROME), "main": txt(MAIN), "ci": txt(CI)}
    errs = check(files)
    if "--selftest" in sys.argv:
        injections = list(shell.injections())
        injections += [
            ("Screen 枚举被改", "main",
             lambda t: t.replace('SETTINGS, MODELS, MIXER, CHAPTERS',
                                 'SETTINGS, MODELS, CHAPTERS')),
            ("CI 漏挂验证", "ci",
             lambda t: t.replace('r89_verify.py', 'r89_missing.py')),
            ("分发丢一支", "main",
             lambda t: t.replace('Screen.MIXER -> MixerScreen(ctx, onBack = { nav.pop() })', '')),
        ]
        bad = 0
        for name, key, mutate in injections:
            g = dict(files)
            g[key] = mutate(files[key])
            if g == files:
                bad += 1
                errs.append("注入锚点失效: " + name)
            elif not check(g):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(injections) - bad, len(injections)))
    if errs:
        print("R89 FAIL", len(errs))
        [print(" -", e) for e in errs]
        return 1
    print("R89 导航壳重排 / 功能菜单零回潮 / 能力守恒：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
