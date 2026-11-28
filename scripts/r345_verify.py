#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R345 · 悬浮窗圆球下方不再垫方形底。

用户口径：悬浮进度球下面那块方形底难看，去掉。
实现要点（三处一起改才算真去掉）：
  · build() 不再给 LinearLayout 垫 GradientDrawable 圆角卡片 + 描边，也不留 4dp 内边距；
  · lp() 的像素格式 OPAQUE → TRANSLUCENT，否则球外透明区会被涂成黑块，等于换个更丑的方底；
  · 不透明下沉到每枚 RingView：drawCircle(cx, cy, r, disc) 铺圆芯，半径取环中心线 r，
    不越过环外沿，两球叠成 8 字时下球的芯够不到上球的环。

守恒（不许顺手改坏）：双球环形、可拖动、来源仲裁、百分比数字、hide 收起全原样。

用法：python3 scripts/r345_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

# 旧方形底板及其配套写法，一个都不许回来
BANNED = [
    "GradientDrawable",
    "cornerRadius",
    "setStroke(",
    "setPadding(",
    "background =",
    "PixelFormat.OPAQUE",
    "0xFFC9D4E8",
    "val pad =",
    "cancelIntent",
]
# R345 新形态的真实锚
REQUIRED = [
    "private const val RING_DP = 56f",
    "PixelFormat.TRANSLUCENT",
    "(RING_DP * dm.density)",
    "(RING_DP * c.resources.displayMetrics.density)",
    "private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {",
    "style = Paint.Style.FILL",
    "canvas.drawCircle(cx, cy, r, disc)",
]
# R214 / R217 / R267 守了很久的链路，去方底不许把它们一起摘掉
CONSERVED = [
    "private class RingView",
    "canvas.drawArc(oval, -90f, 360f * pct, false, arc)",
    "private fun pctLabel(pct: Float): String",
    "enum class OverlaySource { PREVIEW, CLIP, PRODUCE }",
    "source.ordinal < cur.source.ordinal",
    "target.source == OverlaySource.PRODUCE && rawPct >= 0f",
    "bottomRing?.visibility = View.GONE",
    "MotionEvent.ACTION_MOVE",
    "updateViewLayout(v, p)",
    "wm?.removeViewImmediate(v)",
]


def files(overlay=None, settings=None):
    def rd(p):
        return p.read_text(encoding="utf-8", errors="replace")
    return {
        "overlay": overlay if overlay is not None else rd(APP / "OverlayProgress.kt"),
        "settings": settings if settings is not None else rd(APP / "SettingsUi.kt"),
    }


def check(f):
    e = []
    ov, st = f["overlay"], f["settings"]
    for k in BANNED:
        if k in ov:
            e.append("方形底板旧写法回潮: " + k[:52])
    for k in REQUIRED:
        if k not in ov:
            e.append("R345 新形态缺: " + k[:56])
    for k in CONSERVED:
        if k not in ov:
            e.append("去方底把老链路改坏了，缺: " + k[:56])
    if 'Text("不透明圆球"' not in st:
        e.append("设置页图例没按新形态改准")
    if 'Text("不透明浅底"' in st:
        e.append("设置页图例还在写旧的方形浅底")
    return e


def selftest():
    base = files()
    ov, st = base["overlay"], base["settings"]
    # 全部是「把某一处改回旧样子 / 摘掉新样子」，check 必须报红
    cases = [
        ("像素格式退回 OPAQUE", ov.replace("PixelFormat.TRANSLUCENT", "PixelFormat.OPAQUE"), st),
        ("摘掉圆芯绘制", ov.replace("            canvas.drawCircle(cx, cy, r, disc)\n", ""), st),
        ("摘掉圆芯画笔", ov.replace("        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {\n"
                              "            style = Paint.Style.FILL\n"
                              "            color = 0xFFF4F6FA.toInt()\n"
                              "        }\n", ""), st),
        ("圆芯半径越过环外沿（会削掉 8 字上球）",
         ov.replace("canvas.drawCircle(cx, cy, r, disc)", "canvas.drawCircle(cx, cy, sizePx / 2f, disc)"), st),
        ("塞回 GradientDrawable 方底",
         ov.replace("            orientation = LinearLayout.VERTICAL\n",
                    "            orientation = LinearLayout.VERTICAL\n"
                    "            background = android.graphics.drawable.GradientDrawable().apply {\n"
                    "                cornerRadius = 14f\n"
                    "                setStroke(2, 0xFFC9D4E8.toInt())\n"
                    "            }\n"), st),
        ("塞回 4dp 内边距",
         ov.replace("            orientation = LinearLayout.VERTICAL\n",
                    "            orientation = LinearLayout.VERTICAL\n"
                    "            setPadding(4, 4, 4, 4)\n"), st),
        ("塞回描边", ov.replace("        box.addView(top)", "        box.setStroke(1, 0)\n        box.addView(top)"), st),
        ("RING_DP 常量被摘（两处尺寸引用悬空）",
         ov.replace("    private const val RING_DP = 56f\n", ""), st),
        ("默认位置退回 72dp 卡片估算",
         ov.replace("(RING_DP * dm.density)", "(72 * dm.density)"), st),
        ("球尺寸退回硬编码 56",
         ov.replace("(RING_DP * c.resources.displayMetrics.density)", "(56 * c.resources.displayMetrics.density)"), st),
        ("双球收起被摘", ov.replace("bottomRing?.visibility = View.GONE", ""), st),
        ("拖动被摘", ov.replace("MotionEvent.ACTION_MOVE", "MotionEvent.ACTION_HOVER_MOVE"), st),
        ("hide 收起被摘", ov.replace("wm?.removeViewImmediate(v)", ""), st),
        ("来源仲裁被摘", ov.replace("source.ordinal < cur.source.ordinal", ""), st),
        ("百分比弧被摘", ov.replace("canvas.drawArc(oval, -90f, 360f * pct, false, arc)", ""), st),
        ("设置页图例退回旧文案", ov, st.replace("不透明圆球", "不透明浅底")),
        ("设置页图例被摘", ov, st.replace('Text("不透明圆球", fontSize = 10.sp, color = Sub)', "")),
        ("窗口又挂停止按钮", ov + "\n// cancelIntent\n", st),
    ]
    bad = []
    for name, o, s in cases:
        if not check({"overlay": o, "settings": s}):
            bad.append(name)
    base_err = check(base)
    print("R345 自检：正向 %s，反向注入 %d 组，漏拦 %d 组"
          % ("绿" if not base_err else "红:" + ";".join(base_err), len(cases), len(bad)))
    for b in bad:
        print("  漏拦：", b)
    return 0 if not bad and not base_err else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    e = check(files())
    if e:
        print("R345 悬浮窗方形底守门：FAIL")
        for x in e:
            print("  -", x)
        sys.exit(1)
    print("R345 悬浮窗圆球无方形底 / 圆芯不透明 / 双球拖动与收起守恒：PASS")
