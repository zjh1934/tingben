#!/usr/bin/env python3
"""U1 反向验证：注入违规必须被 CI 自检拦下，还原后基线必须通过。

用户原话「你改完东西不做验证，有些问题是改一处联动很多处的」。
这里验证的不是「代码看起来对」，而是「断言真的能抓到回归」。
只依赖标准库，临时改动文件后逐条还原，异常时也保证还原。
"""
from pathlib import Path
import subprocess, sys

ROOT = Path(__file__).resolve().parent.parent
CHECK = ROOT / "scripts/check_structure.py"
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"

CASES = [
    ("删掉 chromeOf 一个目的地", MAIN,
     '    Screen.CAST     -> ChromeSpec("角色音色")',
     '    // 故意删掉 CAST 分支', "chromeOf"),
    ("破坏系统返回键骨架", MAIN,
     "BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }",
     "BackHandler(enabled = false) { }", "导航骨架"),
    ("恢复删书不刷新 bug", MAIN,
     "BookStore.delete(c, b.id); onChanged()",
     "BookStore.delete(c, b.id)", "onChanged"),
    ("底栏偷偷加回设置 Tab", CHROME,
     '    TAB_AUDIO to "音频",\n)',
     '    TAB_AUDIO to "音频",\n    TAB_AUDIO to "设置",\n)', "顶层 Tab"),
    ("改掉下拉刷新阈值", CHROME,
     "if (acc > 55f) {", "if (acc > 9999f) {", "下拉刷新"),
    ("恢复旧文字刷新按钮", MAIN,
     '        AppTopBar(',
     '        Text("⟳ 刷新")\n        AppTopBar(', "旧 UI 残留"),
    ("恢复旧 4-Tab 组件", MAIN,
     "private fun chromeOf(s: Screen): ChromeSpec = when (s) {",
     "private fun TabButton() { }\nprivate fun chromeOf(s: Screen): ChromeSpec = when (s) {", "旧 UI 残留"),
    ("删掉 AppBottomBar 调用", MAIN, "        AppBottomBar(", "        // AppBottomBar(", "AppBottomBar"),
    ("改 Screen 枚举", MAIN,
     "private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, CAST, MIXER }",
     "private enum class Screen { LIBRARY, PRODUCE, TASKS, AUDIO, SETTINGS, MIXER }", "枚举"),
    ("破坏括号平衡", CHROME,
     "internal data class ChromeSpec(", "internal data class ChromeSpec(\n{", "括号"),
    ("KDoc 注入注释收尾符", CHROME,
     " * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器、不碰任何数据层。",
     " * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器 KEY_*/DEFAULT_ 不碰任何数据层。", "KDoc"),
]


def run_check():
    r = subprocess.run([sys.executable, str(CHECK)], cwd=ROOT,
                       capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip()


def main():
    rc, out = run_check()
    if rc != 0:
        print("基线就失败，先修基线：\n" + out)
        return 1
    print("基线通过 ✓")

    saved = {p: p.read_text(encoding="utf-8") for p in {MAIN, CHROME}}
    failed = []
    try:
        for name, path, old, new, expect in CASES:
            cur = path.read_text(encoding="utf-8")
            if old not in cur:
                failed.append((name, "注入锚点找不到（断言可能已失效）"))
                continue
            path.write_text(cur.replace(old, new, 1), encoding="utf-8")
            rc, out = run_check()
            hit = rc != 0 and expect in out
            print(("  ✓ 拦下 " if hit else "  ✗ 漏过 ") + name)
            if not hit:
                failed.append((name, out[:300]))
            # 立刻还原，保证下一个用例在干净基线上
            path.write_text(saved[path], encoding="utf-8")
    finally:
        for p, txt in saved.items():
            p.write_text(txt, encoding="utf-8")

    rc, out = run_check()
    print(("还原后基线通过 ✓" if rc == 0 else "还原后基线失败 ✗\n" + out))
    if failed or rc != 0:
        for n, d in failed:
            print("FAIL", n, d)
        return 1
    print("U1 反向验证 %d/%d 全部拦下" % (len(CASES), len(CASES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
