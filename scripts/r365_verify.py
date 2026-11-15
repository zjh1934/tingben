#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R365 · 底部多选条「一排纯图标」形态守门。

用户口径：目录页本来就挤，底部条不要两三排、不要字，用图标表示、一排就行。
按「打架只留最新」：R363 三行带字、R291 横滑兜底两套旧规则作废，不并存。

守的是三件事：
  · 单行：条子里只有一个 Row，纯图标按钮，文字只进 contentDescription
  · 能力守恒：全选 / 取消 / 制作 / 导出与另存二级菜单 / 删除二次确认 /
    首页片段的删除 / 重做 / 导出，一条不许摘
  · 禁用态：没勾选主按钮置灰而不是消失，条子不跳排

用法：python3 scripts/r365_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

FILES = {
    "multi": APP / "MultiSelect.kt",
    "hub": APP / "ChapterHub.kt",
    "input": APP / "InputReadUi.kt",
    "icons": APP / "UiIcons.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
    "docs": ROOT / "docs/00_现行契约.md",
}

POS = {
    "multi": [
        "internal fun IconChip(",
        "internal fun MultiSelectBar(",
        "IconChip(allDesc",
        'IconChip("取消多选"',
        "primaryEnabled: Boolean = true",
        "semantics { contentDescription = label }",
        "clickable(enabled = enabled, onClick = onClick)",
        "glyphSize = 20.dp",
        "horizontalArrangement = Arrangement.spacedBy(6.dp)",
        "Row(\n",
    ],
    "hub": [
        'primaryLabel = "制作选中章节"',
        "primaryEnabled = selected.isNotEmpty()",
        'IconChip("导出与另存", UiIcon.M4A',
        "DropdownMenu(expanded = exportMenu",
        '"拼成整章"', '"另存 M4A"', '"转 M4A"',
        "这一章的小块还在重做中 · 展开去处理",
        'IconChip("删除选中音频", UiIcon.DELETE, danger = true',
        "confirmChDel = selected.associateWith { filesOf(it) }",
    ],
    "input": [
        'IconChip("删除"', 'IconChip("重做"', 'IconChip("导出"',
        "ClipLibrary.exportTo(c, AudioExport.treeUri(c), ready)",
    ],
    "icons": ["R365 · 底部多选条按新口径改成一排纯图标"],
    "ci": ["python3 scripts/r365_verify.py --selftest"],
    "r135": ['"r365_verify.py"'],
    "docs": ["一排纯图标"],
}

# 旧口径回潮：出现即红，不与新规则并存
BAN = {
    "multi": [
        'Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp))',
        'Text("已选 $count"',
        'Text("已勾选章节"',
        "ActionChip(allLabel",
        'ActionChip("取消"',
        "ActionChip(primaryLabel, onClick = onPrimary, primary = true",
        "horizontalScroll(rememberScrollState())",
        "R363 · 等宽两列",
    ],
    "hub": [
        'ActionChip("删除选中音频"',
        'ActionChip("导出与另存"',
        "primaryLabel = if (selected.isEmpty()) null else",
        "R363 · 等宽两列",
    ],
    "input": ['ActionChip("删除"', 'ActionChip("重做"', 'ActionChip("导出"'],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace")
            for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:60]))
    for key, olds in BAN.items():
        for s in olds:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:60]))
    # 单行硬约束：MultiSelectBar 体内不许再出现 Column
    bar = f["multi"].split("internal fun MultiSelectBar(", 1)[-1]
    if "Column(" in bar:
        e.append("底部条又长出多行 Column")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    bad = 0
    total = 0
    for key, needles in POS.items():
        for s in needles:
            total += 1
            g = dict(f)
            g[key] = f[key].replace(s, "/* removed */", 1)
            if not check(g):
                print("  正向注入漏放[%s]: %s" % (key, s[:44]))
                bad += 1
    for key, olds in BAN.items():
        for s in olds:
            total += 1
            g = dict(f)
            g[key] = f[key] + "\n// " + s + "\n"
            if not check(g):
                print("  旧形态注入漏放[%s]: %s" % (key, s[:44]))
                bad += 1
    # 额外：把单行 Row 换成 Column，必须红
    total += 1
    g = dict(f)
    g["multi"] = f["multi"].replace(
        "        Row(\n            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),",
        "        Column(\n            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),", 1)
    if not check(g):
        print("  多行回潮注入漏放")
        bad += 1
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    e = check(load())
    if e:
        print("R365 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R365 底部条一排纯图标 / 能力守恒 / 旧口径零回潮：PASS")
