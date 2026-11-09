#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R170 · 源码完整性守门（挂在所有 selftest 之前）。

事故背景：老版 r136/r135 的 --selftest 靠「原地覆写 MainActivity.kt + finally 还原」
做反向注入。一次沙盒 502 把进程砍在循环中途，finally 没跑，源码里留了一行
`Unit`（`Prefs.setModelMode(c, mode.id)` 被替换），下一轮全套直接假红。

现在 selftest 已全部改成纯内存注入，但留这道网：每轮 CI 第一项就核一批
「历史注入的替换目标」是否齐全，任何残留当场报红、给出具体文件名与锚点。
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ui_sources.source(ROOT)

ANCHORS = [
    "Prefs.setModelMode(c, mode.id)",
    "ModelStore.delete(c, f)",
    "ModelStore.delete(c, baseFile)",
    "copyUrl(cnUrl(mode.file))",
    "copyUrl(hfUrl(mode.file))",
    "copyUrl(cnUrl(ModelStore.TOKENIZER))",
    "var expanded by remember { mutableStateOf<String?>(null) }",
    "Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp))",
    "fun openBook(id: String?)",
    "BOOK_IMPORT_CANCEL",
    "clipboard.setText(AnnotatedString(mode.file))",
]

# 注入残留的典型形状：整行只有一个 Unit（r136「切模式被摘」注入）
def check(text):
    e = []
    for a in ANCHORS:
        if a not in text:
            e.append("关键锚点缺失（疑似自检注入残留）: " + a)
    for i, line in enumerate(text.splitlines(), 1):
        if line.strip() == "Unit" and len(line) - len(line.lstrip()) > 20:
            e.append("第 %d 行有孤立 Unit（注入没还原）" % i)
    return e

def selftest():
    base = MAIN.read_text(encoding="utf-8")
    assert not check(base), "基线自己就红了"
    got = 0
    cases = [
        ("切模式被摘", lambda s: s.replace("Prefs.setModelMode(c, mode.id)", "Unit", 1)),
        ("删除重试被摘", lambda s: s.replace("ModelStore.delete(c, f)", "Unit", 1)),
        ("复制地址被摘", lambda s: s.replace("copyUrl(cnUrl(mode.file))", "Unit", 1)),
        ("孤立 Unit 残留", lambda s: s.replace(
            "                                            if (!st.running) {",
            "                                                Unit\n"
            "                                            if (!st.running) {", 1)),
    ]
    for name, mut in cases:
        v = mut(base)
        assert v != base, "注入没生效: " + name
        if check(v): got += 1
        else: print("  未拦下:", name)
    print("反向注入 %d/%d %s" % (got, len(cases), "PASS" if got == len(cases) else "FAIL"))
    return got == len(cases)

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(0 if selftest() else 1)
    e = check(MAIN.read_text(encoding="utf-8"))
    if e:
        print("FAIL · 源码完整性")
        for x in e: print("  ✗", x)
        sys.exit(1)
    print("源码完整性：%d 个关键锚点齐全 / 无注入残留：PASS" % len(ANCHORS))
