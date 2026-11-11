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
        "icons": rd(APP / "UiIcons.kt"),
        "chrome": rd(APP / "AppChrome.kt"),
        "mixer": rd(APP / "MixerUi.kt"),
        "task": rd(APP / "TaskUi.kt"),
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
    # ⑤ R247 · 其余字按钮配代码自绘图标（文字保留，老字面量判据零迁移）
    ic = f["icons"]
    for k in ["enum class UiIcon {", "internal fun UiIconGlyph(",
              "Canvas(Modifier.size(glyphSize))", "0.30f * i.toFloat()",
              "strokeWidth = sw, cap = StrokeCap.Round"]:
        if k not in ic: e.append("图标库缺: " + k[:52])
    for v in ["MIXER", "SETTINGS", "SYNTH", "MERGE", "WAV", "M4A", "DELETE",
              "PLAY", "STOP", "LISTEN", "EXPORT", "CANCEL", "ALL", "AUDIO", "TASKS"]:
        if "UiIcon." + v + " ->" not in ic: e.append("枚举值没画: " + v)
    if "icon: UiIcon? = null" not in m or "UiIconGlyph(icon, tint" not in m:
        e.append("ActionChip 没接图标槽")
    if hb.count("icon = UiIcon.") < 7:
        e.append("目录页动作条图标没配齐（实 %d）" % hb.count("icon = UiIcon."))
    # 只禁渲染出来的 ⚙ 字符；KDoc 里解释「不用 ⚙ 字符」的说明不算回潮
    if 'Text("⚙' in f["chrome"]:
        e.append("⚙ 字符回潮（应走 GearIcon 代码自绘）")
    if "GearIcon(primary)" not in f["chrome"]:
        e.append("抽屉设置没用真齿轮")
    for name, src, need in [("裁剪弹层", f["mixer"], "UiIconGlyph(UiIcon.AUDIO"),
                            ("任务页", f["task"], "UiIconGlyph(UiIcon.LISTEN")]:
        if need not in src: e.append(name + "没接图标")
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
        ("图标库被摘", "icons", "enum class UiIcon {", ""),
        ("局部函数 Float×Int 回潮", "icons", "0.30f * i.toFloat()", "0.30f * i"),
        ("ActionChip 图标槽被摘", "multi", "icon: UiIcon? = null", ""),
        ("动作条图标被摘", "hub", 'ActionChip("拼成整章", icon = UiIcon.MERGE',
         'ActionChip("拼成整章"'),
        ("⚙ 字符回潮", "chrome", "GearIcon(primary)", 'Text("⚙ 设置"'),
        ("裁剪弹层图标被摘", "mixer", "UiIconGlyph(UiIcon.AUDIO", ""),
        ("任务页图标被摘", "task", "UiIconGlyph(UiIcon.LISTEN", ""),
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
    print("R224 批次1 多选动作按钮化 / 抽屉手势仅主页 / R247 图标接线：PASS")
