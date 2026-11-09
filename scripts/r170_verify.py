#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R170 · 模型页布局：三卡固定等高 / 详情移出卡片 / 默认态一屏 / 功能守恒。

真机截图 26 实证：展开一张卡，详情全塞在卡里把那张撑到两倍高，
同一行另一列空一大块，第三张被挤下去。改成三卡固定等高 + 统一详情弹层。
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ui_sources.source(ROOT)
CI = ROOT / ".github/workflows/ci.yml"

def body():
    s = MAIN.read_text(encoding="utf-8")
    a = s.index("private fun ModelScreen(")
    b = s.find("\n@OptIn(ExperimentalLayoutApi::class)", a)
    return s[a:b if b > 0 else len(s)]

def checks(mb):
    e = []
    # ① 三卡固定等高：卡片 modifier 上必须有显式 height，且只有一处
    if not re.search(r"Modifier\.weight\(1f\)\.height\(96\.dp\)", mb):
        e.append("三卡没固定等高（详情又会撑歪某一张）")
    # ② 详情在统一弹层，不在卡内
    if "expanded?.let { key ->" not in mb or "AlertDialog(" not in mb:
        e.append("统一详情弹层不在了")
    # M3 AlertDialog 的 confirmButton 是必填参数，漏了真编译直接炸（静态查不出）
    if "confirmButton = {" not in mb:
        e.append("详情弹层缺 confirmButton（Kotlin 编译不过）")
    # 卡内不允许再出现「导入这份 / 删除重试 / 复制地址」这类详情操作
    card = mb[mb.find("modes.toList().chunked(2)"):mb.find("// R170 · 统一详情弹层")]
    for forbidden in ("ModelStore.delete(c, f)", "copyUrl(cnUrl(mode.file))",
                      "导入这份 ›", "删除重试", "启用 ›"):
        if forbidden in card:
            e.append("详情操作又塞回卡内: " + forbidden)
    # ③ 默认态一屏
    if "verticalScroll" in mb:
        e.append("模型页又变成长滚动页")
    if mb.count("Text(mode.file") < 1 or "TextOverflow.Ellipsis" not in mb:
        e.append("模型文件名会竖排折行")
    if "clipboard.setText(AnnotatedString(mode.file))" not in mb:
        e.append("完整文件名不可复制")
    # ④ 功能守恒（R136 的 26 项由 r136 守，这里守「移走后没丢」的 6 个）
    for k in ("copyUrl(cnUrl(mode.file))", "copyUrl(hfUrl(mode.file))",
              "copyUrl(cnUrl(ModelStore.TOKENIZER))", "copyUrl(hfUrl(ModelStore.TOKENIZER))",
              "ModelStore.delete(c, f)", "ModelStore.delete(c, baseFile)",
              "Prefs.setModelMode(c, mode.id)", "onPick"):
        if k not in mb:
            e.append("详情移出后功能丢了: " + k)
    # ⑤ 底座详情也走同一弹层
    if 'if (expanded == "base")' not in mb or 'toggle("base")' not in mb:
        e.append("底座详情没进统一弹层")
    return e

def selftest():
    mb = body()
    assert not checks(mb), "基线自己红了"
    cases = [
        ("三卡又不等高", lambda s: s.replace("Modifier.weight(1f).height(96.dp)",
                                             "Modifier.weight(1f)")),
        ("详情塞回卡内", lambda s: s.replace(
            "                        Text(mode.hint, fontSize = 10.sp, color = Sub, maxLines = 1,\n                            overflow = TextOverflow.Ellipsis)",
            "                        Text(mode.hint, fontSize = 10.sp, color = Sub, maxLines = 1,\n                            overflow = TextOverflow.Ellipsis)\n                        TextButton(onClick = { ModelStore.delete(c, f!!) }) { Text(\"删除重试\") }")),
        ("统一弹层被摘", lambda s: s.replace("expanded?.let { key ->", "null?.let { key ->")),
        ("弹层缺必填按钮", lambda s: s.replace("confirmButton = {", "dismissButton = {")),
        ("复制文件名被摘", lambda s: s.replace(
            "clipboard.setText(AnnotatedString(mode.file))", "Unit")),
        ("整页滚动回来", lambda s: s.replace(
            "Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp))",
            "Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp))")),
        ("国内地址被摘", lambda s: s.replace("copyUrl(cnUrl(mode.file))", "Unit")),
        ("底座详情回潮到卡内", lambda s: s.replace('if (expanded == "base")', 'if (false)')),
    ]
    got = 0
    for name, mut in cases:
        s = mut(mb)
        assert s != mb, "注入没生效: " + name
        if checks(s): got += 1
        else: print("  未拦下:", name)
    print("反向注入 %d/%d %s" % (got, len(cases), "PASS" if got == len(cases) else "FAIL"))
    return got == len(cases)

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(0 if selftest() else 1)
    ci = CI.read_text(encoding="utf-8")
    e = checks(body())
    if "python3 scripts/r170_verify.py" not in ci:
        e.append("CI 未挂 r170_verify")
    if e:
        print("FAIL · R170")
        for x in e: print("  ✗", x)
        sys.exit(1)
    print("R170 模型页三卡等高 / 详情统一弹层 / 一屏 / 功能守恒：PASS")
