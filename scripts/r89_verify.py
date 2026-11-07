# -*- coding: utf-8 -*-
"""R89 · 调音台进一级目录验证：平级入口 / 抽屉不膨胀 / 导航守恒 / 反向注入。

只守结构，不守画法：调音台整页重做仍留待用户引导，本轮不碰界面。
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nav_semantics as nav

ROOT = pathlib.Path(__file__).resolve().parents[1]
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"

def top_and_more(text):
    out = {}
    for name in ("DRAWER_TOP", "DRAWER_MORE"):
        m = re.search(r"private val %s = listOf\((.*?)\n\)" % name, text, re.S)
        out[name] = m.group(1) if m else ""
    return out

def check(files):
    errs = []
    ch, main, ci = files["chrome"], files["main"], files["ci"]
    tm = top_and_more(ch)
    if "NAV_MIXER to \"调音台\"" not in tm["DRAWER_TOP"]:
        errs.append("调音台不在一级组")
    if "NAV_MIXER" in tm["DRAWER_MORE"]:
        errs.append("调音台仍在「更多」组")
    for s in ('NAV_LIBRARY to "书架"', 'NAV_TASKS to "任务"',
              'NAV_AUDIO to "我的音频"', 'NAV_SETTINGS to "设置"',
              'NAV_MODELS to "模型"'):
        if s not in tm["DRAWER_TOP"] + tm["DRAWER_MORE"]:
            errs.append("抽屉项丢失: " + s[:28])
    n = len(re.findall(r"NAV_\w+ to \"", tm["DRAWER_TOP"] + tm["DRAWER_MORE"]))
    if n != 6:
        errs.append("抽屉项数不是 6，实得 %d" % n)
    if len(re.findall(r"NAV_\w+ to \"", tm["DRAWER_TOP"])) != 4:
        errs.append("一级组不是 4 项")

    EXPECTED = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
                "SETTINGS", "MODELS", "MIXER", "CHAPTERS"}
    if nav.screen_enum(main) != EXPECTED:
        errs.append("Screen 枚举集合与 9 值不符")
    for pat in (r"Screen\.MIXER\s*->\s*NAV_MIXER", r"NAV_MIXER\s*->\s*Screen\.MIXER"):
        if not re.search(pat, main + ch):
            errs.append("导航映射丢失: " + pat)
    for s in ('fun commitAll()', 'if (!st.running) return@LaunchedEffect'):
        if s not in main + ch:
            errs.append("守恒丢失: " + s[:52])
    for msg in nav.semantics(main):
        errs.append("导航语义: " + msg)
    if 'r89_verify.py' not in ci:
        errs.append("CI 未挂 r89_verify")
    return errs

def txt(p):
    return p.read_text(encoding="utf-8", errors="replace")

def main():
    files = {"chrome": txt(CHROME), "main": txt(MAIN), "ci": txt(CI)}
    errs = check(files)
    if "--selftest" in sys.argv:
        injections = [
            ("调音台退回更多组",
             lambda t: dict(t, chrome=t["chrome"].replace(
                 '    NAV_MIXER to "调音台",\n)', ')\n\nprivate val DRAWER_MORE_EXTRA = listOf(\n    NAV_MIXER to "调音台",\n)'))),
            ("一级组删掉调音台",
             lambda t: dict(t, chrome=t["chrome"].replace('    NAV_MIXER to "调音台",\n)', ')'))),
            ("抽屉多出一项",
             lambda t: dict(t, chrome=t["chrome"].replace(
                 '    NAV_MODELS to "模型",\n)', '    NAV_MODELS to "模型",\n    NAV_SETTINGS to "重复",\n)'))),
            ("导航映射丢失",
             lambda t: dict(t, main=t["main"].replace('Screen.MIXER -> NAV_MIXER', 'Screen.MIXER -> NAV_SETTINGS'))),
            ("Screen 枚举被改",
             lambda t: dict(t, main=t["main"].replace('SETTINGS, MODELS, MIXER, CHAPTERS', 'SETTINGS, MODELS, CHAPTERS'))),
            ("CI 漏挂验证",
             lambda t: dict(t, ci=t["ci"].replace('r89_verify.py', 'r89_missing.py'))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(files)):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(injections) - bad, len(injections)))
    if errs:
        print("R89 FAIL", len(errs))
        [print(" -", e) for e in errs]
        return 1
    print("R89 调音台一级入口 / 抽屉 6 项守恒 / 导航守恒：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
