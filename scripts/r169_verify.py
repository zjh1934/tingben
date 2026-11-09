#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R169 · 第四份真机日志 + 6 张截图实证守门（纯静态，不依赖 SDK）。

只守「截图/日志里确凿的毛病」不许回潮，不绑死实现细节：
  ① 浅色章名对比度（HubGrey 1.74:1 → palette.text）
  ② 齿轮图标语义（细射线太阳 → 粗短齿）
  ③ 文件选择器取消 ≠ 导入失败
  ④ 同一前台会话的 SKIP 只记一次
  ⑤ 模型文件名单行、导出按钮空态禁用
"""
import pathlib, re, sys, tempfile, shutil
ROOT = pathlib.Path(__file__).resolve().parents[1]
A = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": A / "ChapterHub.kt", "chrome": A / "AppChrome.kt",
    "main": A / "MainActivity.kt", "theme": A / "Theme.kt",
}
def lum(h):
    c = [int(h[i:i+2], 16)/255 for i in (1,3,5)]
    c = [x/12.92 if x <= 0.04045 else ((x+0.055)/1.055)**2.4 for x in c]
    return .2126*c[0] + .7152*c[1] + .0722*c[2]
def contrast(a, b):
    x, y = sorted([lum(a), lum(b)], reverse=True)
    return (x+.05)/(y+.05)

def checks(src, quiet=False):
    hub, chrome, main, theme = (src[k] for k in ("hub","chrome","main","theme"))
    e = []
    # ① 章名统一 text；旧灰字分支零回潮
    if "color = palette.text," not in hub:
        e.append("R169 章名没用 palette.text")
    if "else if (r.audio != null" in hub or re.search(r"else\s+HubGrey,\s*$", hub, re.M):
        e.append("R169 章名又退回按状态给浅灰")
    m = re.search(r"internal val LightPal = Pal\((.*?)\n\)", theme, re.S)
    if not m: e.append("R169 读不到 LightPal")
    else:
        t = re.search(r"\btext = Color\(0x([0-9A-Fa-f]{8})\)", m.group(1))
        if not t: e.append("R169 LightPal.text 格式不对")
        else:
            fg = "#" + t.group(1)[2:]
            c = contrast(fg, "#FFFFFF")
            if c < 4.5: e.append("R169 浅色章名对比度 %.2f < 4.5" % c)
            elif not quiet: print("  浅色章名/白卡 %.2f ≥ 4.5 ✓" % c)
    # ② 齿轮是粗短方齿，不是细射线太阳
    if "private fun GearIcon(" not in chrome: e.append("R169 齿轮图标消失")
    if "val teeth = 8" not in chrome or "gear.close()" not in chrome:
        e.append("R169 齿轮不是齿形轮廓（又变回放射线太阳）")
    # R168 同款：Float × Double / Float ÷ Int 静态全绿但真编译必炸
    if "2f * kotlin.math.PI" in chrome or "2f * Math.PI" in chrome:
        e.append("R169 齿轮角度有 Float×Double，Kotlin 编译不过")
    # ③ 取消静默
    if "BOOK_IMPORT_CANCEL" not in main: e.append("R169 导入取消没单独记账")
    # 只盯日志调用体，别被解释性注释里的旧事件名误伤（R166 同款坑）
    if re.search(r'EventLog\.log\([^)]*"BOOK_IMPORT_NONE"', main, re.S):
        e.append("R169 取消又被当导入失败")
    if re.search(r'BOOK_IMPORT_CANCEL[\s\S]{0,180}Toast\.makeText', main):
        e.append("R169 取消仍弹错误 Toast")
    # ④ SKIP 收敛
    if "resumeSkipLogged" not in main or "if (!resumeSkipLogged)" not in main:
        e.append("R169 RESUME_ASK_SKIP 又每次都记")
    # ⑤ 文件名与空态按钮
    if "TextOverflow.Ellipsis" not in main: e.append("R169 模型文件名会竖排折行")
    if not re.search(r'Text\(mode\.file[^)]*maxLines = 1', main, re.S):
        e.append("R169 模型文件名没限单行")
    if "Button(enabled = expCount > 0" not in main:
        e.append("R169 0 个成品时「导出全部」仍可点")
    return e

def read_all(base=ROOT):
    return {k: (base / v.relative_to(ROOT)).read_text(encoding="utf-8")
            for k, v in FILES.items()}

def selftest():
    base = read_all()
    cases = [
        ("章名退回浅灰", "hub", lambda t: t.replace("color = palette.text,",
            "color = if (r.audio != null || (p ?: 0f) > 0f) Color.Unspecified else HubGrey,")),
        ("齿轮退回放射线", "chrome", lambda t: t.replace("gear.close()", "// no close")),
        ("角度 Float×Double", "chrome", lambda t: t.replace(
            "val step = (2.0 * kotlin.math.PI / teeth).toFloat()",
            "val step = (2f * kotlin.math.PI / teeth).toFloat()")),
        ("取消又弹错误", "main", lambda t: t.replace(
            'EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_CANCEL", "$tag 用户取消选择",\n                level = EventLog.Level.TRACE)',
            'EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_NONE", "$tag 没拿到文件")\n            Toast.makeText(ctx, "没拿到文件，换个方式再试", Toast.LENGTH_SHORT).show()')),
        ("SKIP 每次都记", "main", lambda t: t.replace("if (!resumeSkipLogged) {", "if (true) {")),
        ("文件名不限行", "main", lambda t: t.replace(
            "fontSize = 11.sp, color = Sub, maxLines = 1,\n                                overflow = TextOverflow.Ellipsis,\n                                fontFamily = FontFamily.Monospace,",
            "fontSize = 11.sp, color = Sub,\n                                fontFamily = FontFamily.Monospace,")),
        ("导出按钮不禁用", "main", lambda t: t.replace("Button(enabled = expCount > 0, ", "Button(")),
    ]
    ok = not checks(base)
    got = 0
    for name, key, mut in cases:
        s = dict(base); s[key] = mut(s[key])
        assert s[key] != base[key], "注入没生效: " + name
        if checks(s, quiet=True): got += 1
        else: print("  未拦下:", name)
    print("反向注入 %d/%d %s" % (got, len(cases), "PASS" if ok and got == len(cases) else "FAIL"))
    return ok and got == len(cases)

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(0 if selftest() else 1)
    e = checks(read_all())
    if e:
        print("FAIL · R169")
        for x in e: print("  ✗", x)
        sys.exit(1)
    print("R169 真机截图与日志实证（浅色章名 / 齿轮 / 导入取消 / 噪声收敛）：PASS")
