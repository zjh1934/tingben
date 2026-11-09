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
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nav_semantics as nav
import screen_enum as se
import shell_semantics as shell

ROOT = pathlib.Path(__file__).resolve().parents[1]
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
MAIN = ui_sources.source(ROOT)
CI = ROOT / ".github/workflows/ci.yml"

# R195 · 动态解析
EXPECTED = None  # 在 check() 里按传入源码算


def check(files):
    errs = []
    ch, main, ci = files["chrome"], files["main"], files["ci"]

    for msg in shell.checks(main, ch):
        errs.append("新壳层: " + msg)
    for msg in nav.semantics(main):
        errs.append("导航语义: " + msg)

    expected = se.declared(main)
    if not expected or nav.screen_enum(main) != expected:
        errs.append("Screen 枚举集合解析失败")
    errs += se.consistency(main)
    # R195 · 第二个锚点原来在被删的 ProgressScreen 里，换成活着的枚举声明
    for s in ('fun commitAll()', 'private enum class Screen {'):
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
             lambda t: t.replace('SETTINGS, MODELS, CHAPTERS',
                                 'SETTINGS, MODELS, LLM, CHAPTERS')),
            ("CI 漏挂验证", "ci",
             lambda t: t.replace('r89_verify.py', 'r89_missing.py')),
            ("分发丢一支", "main",
             lambda t: t.replace('Screen.MODELS -> ModelScreen(ctx, refresh,', '')),
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
