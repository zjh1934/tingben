#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R224 · 自主推进模式批次 1 守门。

B 选中章节后的底部菜单：一排平铺文字 → 有底色圆角的 ActionChip；
C 左滑开书架抽屉：只在软件主页（LIBRARY）生效，其它页不被边缘手势误触。

用法：python3 scripts/r224_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "multi": rd(APP / "MultiSelect.kt"),
        "hub": rd(APP / "ChapterHub.kt"),
        "main": ui_sources.source(APP).read_text(encoding="utf-8"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

CHIPS = ["ActionChip(\"调音 ›\"", "ActionChip(\"制作设置 ›\"",
         "ActionChip(\"合成音频\"", "ActionChip(\"拼成整章\"",
         "ActionChip(\"另存 WAV\"", "ActionChip(\"转 M4A\"",
         "ActionChip(\"删除选中音频\", danger = true"]
BAN = ["TextButton(onClick = onMixer)", "TextButton(onClick = onCancel)",
       'Text("合成音频", fontSize = 12.sp, color = HubPrimary)',
       'Text("调音 ›", fontSize = 12.sp, color = HubPrimary)']

def check(f):
    e = []
    m, hb, mn = f["multi"], f["hub"], f["main"]
    # ① 组件存在且带底色 / 圆角 / 可点
    if "internal fun ActionChip(" not in m: e.append("缺 ActionChip 组件")
    for k in [".background(", ".clip(RoundedCornerShape(12.dp))", ".clickable(onClick = onClick)"]:
        if k not in m: e.append("ActionChip 缺按钮形态: " + k)
    if "danger: Boolean = false" not in m: e.append("ActionChip 缺危险色区分")
    # ② 勾选条内动作全换成 chip
    for k in CHIPS:
        if k not in hb: e.append("勾选动作没按钮化: " + k[:40])
    for k in BAN:
        if k in hb or k in m: e.append("R224 回潮：动作又变回平铺文字 " + k[:40])
    if 'ActionChip(allLabel' not in m: e.append("全选没按钮化")
    if 'ActionChip("取消"' not in m: e.append("取消没按钮化")
    # ③ 抽屉手势只在主页
    if "gesturesEnabled = screen == Screen.LIBRARY" not in mn:
        e.append("抽屉左滑没限定主页")
    if "gesturesEnabled = true" in mn:
        e.append("抽屉左滑又对全局页面开放")
    # ④ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r224_verify.py --selftest"),
                              ("清单", f["r135"], '"r224_verify.py"'),
                              ("受管根", f["config"], "r224_verify.py")]:
        if needle not in src: e.append(name + " 漏登记 r224")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("组件被删", "multi", "internal fun ActionChip(", ""),
        ("底色被摘", "multi", ".background(", ""),
        ("圆角被摘", "multi", ".clip(RoundedCornerShape(12.dp))", ""),
        ("点击被摘", "multi", ".clickable(onClick = onClick)", ""),
        ("危险色被摘", "multi", "danger: Boolean = false", ""),
        ("合成音频退回文字", "hub", 'ActionChip("合成音频"',
         'TextButton(onClick = { startAction(HubAction.SYNTH) })'),
        ("调音退回文字", "hub", 'ActionChip("调音 ›"', 'Text("调音 ›"'),
        ("删除危险色被摘", "hub", 'ActionChip("删除选中音频", danger = true',
         'ActionChip("删除选中音频"'),
        ("全选退回文字", "multi", "ActionChip(allLabel", "TextButton(onClick"),
        ("取消退回文字", "multi", 'ActionChip("取消"', "TextButton(onClick"),
        ("手势回全局", "main", "gesturesEnabled = screen == Screen.LIBRARY",
         "gesturesEnabled = true"),
        ("手势被关", "main", "gesturesEnabled = screen == Screen.LIBRARY",
         "gesturesEnabled = false"),
        ("CI 漏挂", "ci", "python3 scripts/r224_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r224_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R224 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R224 批次1 多选动作按钮化 / 抽屉手势仅主页：PASS")
