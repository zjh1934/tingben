#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R174 · 界面自审固化（画给自己看之后找到的两个真问题 + 一个核实结论）。

自审板把六格界面按真实结构画出来自己看，逐条去代码核实，结论：
  · 书架空态是一片白、没有能按的入口 → 加「导入第一本 ›」按钮（不是说明文字）
  · 设置页有两张都标「系统」的卡 → 权限三行并进「运行与通知」
  · 目录页底部胶囊会挡最后一章？→ 核实有 contentPadding(bottom=100.dp)，
    已收口，这里把它固化成判据，防止以后被顺手删掉
  · 首页缺「我的书」入口 / 模型页残件显示 ✓ → 分别是用户既定设计与已知 P3，
    只记不改

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r174_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "main": ui_sources.source(ROOT),
    "hub": ROOT / "app/src/main/java/com/tingben/app/ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

EMPTY_BTN = 'Text("导入第一本 ›", fontSize = 14.sp, color = Primary)'
SYS_CARD = 'SettingsSection("运行与通知", "系统", collapsible = false)'
BOTTOM_PAD = "contentPadding = PaddingValues(bottom = 100.dp)"


def group_of(src: str, start_needle: str) -> str:
    a = src.find(start_needle)
    b = src.find("SettingsSection(", a + 10) if a >= 0 else -1
    return src[a:b] if a >= 0 and b > a else ""


def checks(f):
    e = []
    m, h = f["main"], f["hub"]

    # ① 书架空态：必须是一个能按的按钮，且挂在 books 为空的 else 分支里
    if EMPTY_BTN not in m:
        e.append("书架空态没有可点的导入入口")
    if 'item(key = "empty")' not in m:
        e.append("书架空态没有独立的 empty item")
    else:
        a = m.find("if (books.isNotEmpty())")
        z = m.find("导入第一本", a)
        if a < 0 or z < 0 or "else {" not in m[a:z]:
            e.append("空态入口没落在 books 为空的 else 分支")
    # 空态不许回潮成「废话说明」：用户明确反感文本框上方的说明文字
    empty_zone = m[m.find('item(key = "empty")'):m.find('item(key = "empty")') + 400]
    if "说明" in empty_zone or "教程" in empty_zone or "怎么" in empty_zone:
        e.append("书架空态混进了说明文字（不是入口按钮）")

    # ② 设置页系统类只留一张卡，三行权限都在卡内
    sys_lines = [x for x in m.splitlines()
                 if "SettingsSection(" in x and '"系统"' in x]
    if len(sys_lines) != 1:
        e.append("设置页有 %d 张「系统」卡（应为 1）" % len(sys_lines))
    if 'SettingsSection("后台运行与权限"' in m or 'SettingsSection("启动行为"' in m \
            or 'SettingsSection("通知与权限"' in m:
        e.append("已合并的系统分组又拆回去了")
    grp = group_of(m, SYS_CARD)
    for n in ("AutoResumeRow(c)", 'PermRow("忽略电池优化", batOk,',
              'PermRow("显示在其他应用上层", ovOk,',
              'PermEntryRow("应用详情", "系统设置")'):
        if n not in grp:
            e.append("系统卡里缺: " + n[:48])

    # ③ 自审核实结论固化：底部胶囊不遮最后一章
    if BOTTOM_PAD not in h:
        e.append("目录页底部留白被删，悬浮胶囊会挡最后一章")

    if "python3 scripts/r174_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r174_verify --selftest")
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("空态入口被摘", "main", lambda t: t.replace(EMPTY_BTN, "")),
        ("空态退回废话说明", "main",
         lambda t: t.replace(EMPTY_BTN,
             'Text("说明：点右上角＋导入第一本书，然后就能开始制作")')),
        ("空态入口挪出 else", "main",
         lambda t: t.replace('item(key = "empty")', 'item(key = "head")')),
        ("系统卡又拆成两张", "main",
         lambda t: t.replace(SYS_CARD,
             SYS_CARD + ' { }\n        SettingsSection("后台运行与权限", "系统", collapsible = false)')),
        ("权限行被摘", "main",
         lambda t: t.replace('PermRow("忽略电池优化", batOk,',
                             'PermRow("已移除", batOk,')),
        ("底部留白被删", "hub",
         lambda t: t.replace(BOTTOM_PAD, "contentPadding = PaddingValues(0.dp)")),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r174_verify.py --selftest", "")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not checks(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R174 界面自审固化")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R174 界面自审：书架空态入口 / 系统卡唯一 / 底部留白：PASS")
