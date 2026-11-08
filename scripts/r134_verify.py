# -*- coding: utf-8 -*-
"""R134 · 统一多选态验证。

用户要的不是「长按弹一个菜单」，而是：长按 → 进入多选态 → 小方块勾选 →
底部弹出的操作条里批量操作。守三处可达界面共用同一套组件：
章节列 / 可播列 / 分段看台。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r134_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "multi": SRC / "MultiSelect.kt",
    "hub": SRC / "ChapterHub.kt",
    "main": SRC / "MainActivity.kt",
    "queue": SRC / "core/PlayQueue.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "multi": [
        "internal fun SelectBox(",
        "internal fun MultiSelectBar(",
        "drawRoundRect(",
        "drawPath(p, boxColor",
        "onSelectAll: () -> Unit",
        "onCancel: () -> Unit",
        "horizontalScroll(rememberScrollState())",
        "IconClose(palette.sub",
    ],
    "hub": [
        # 第一列：长按也能勾选，底部换成统一条
        ".pointerInput(r.index, multi, checked)",
        "onLongPress = { onLong() }",
        "allLabel = \"全选章节\"",
        # 第三列：多选态 + 方块 + 底部条 + 批量删除
        "var multiMode by remember(book.id) { mutableStateOf(false) }",
        "var segChecked by remember(book.id) { mutableStateOf(setOf<Int>()) }",
        "fun enterMulti(i: Int)",
        "fun exitMulti()",
        ".pointerInput(sg.index, multi, isChecked, playable, playing, onThis)",
        "onLongPress = { if (playable) onLong(sg.index) }",
        "SelectBox(",
        "MultiSelectBar(",
        "confirmChDel = selected.associateWith { filesOf(it) }",
        "\"删除选中的 ${all.size} 章音频？\"",
        "MoreDots(HubPrimary)",
        # 陈旧状态捕获防护
        "勾选框只在多选态渲染",
    ],
    "main": [
        # 看台：小段多选
        "var segMulti by remember { mutableStateOf(false) }",
        "var segChecked by remember { mutableStateOf(setOf<Int>()) }",
        "fun exitSegMulti()",
        ".pointerInput(sg.index, segMulti, segChecked,",
        "onLongPress = {",
        "SelectBox(checked = sg.index in segChecked",
        "MultiSelectBar(",
        "allLabel = \"全选已生成\"",
        "\"试听选中\"",
        "\"删除选中\"",
        "PlayQueue.setTracks(picked, \"seg\")",
        "treeWav = treeChapter?.segments?.associate",
        "filterValues { it.isFile && it.length() > 44 }",
        "AudioOps.deleteAll(files)",
        "AudioTree.clearDone(c, bookId, chapter)",
    ],
    "queue": [
        'private fun isPreview(id: String): Boolean = id == "seg" || id == "trial"',
    ],
    "ci": ["python3 scripts/r134_verify.py --selftest"],
}

BAN = {
    # 第三列的「点 ⋯ 弹一个界面选操作」整套撤掉，操作集中到底部条
    "hub": [
        "var menu by remember { mutableStateOf<Int?>(null) }",
        "Text(\"第 ${i + 1} 章 · 音频操作\"",
        'Text("已选 ${selected.size} 章"',
    ],
    # 看台格子不许退回单一 clickable（那样就没有长按进多选了）
    "main": [
        "..pointerInput(sg.index, multi, isChecked, playable, playing, onThis) {",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:66]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:66]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "勾选方块被摘": ("multi", "internal fun SelectBox("),
        "方块不画勾": ("multi", "drawPath(p, boxColor"),
        "底部条被摘": ("multi", "internal fun MultiSelectBar("),
        "全选入口被摘": ("multi", "onSelectAll: () -> Unit"),
        "取消入口被摘": ("multi", "onCancel: () -> Unit"),
        "第一列长按被摘": ("hub", "onLongPress = { onLong() }"),
        "第一列底部条被摘": ("hub", 'allLabel = "全选章节"'),
        "第三列多选态被摘": ("hub", "var multiMode by remember(book.id) { mutableStateOf(false) }"),
        "第三列不进多选": ("hub", "onLongPress = { if (playable) onLong(sg.index) }"),
        "第三列方块被摘": ("hub", "SelectBox("),
        "第三列底部条被摘": ("hub", "MultiSelectBar("),
        "批量清单被摘": ("hub", "confirmChDel = selected.associateWith { filesOf(it) }"),
        "陈旧闭包防护被摘": ("hub", "勾选框只在多选态渲染"),
        "看台多选态被摘": ("main", "var segMulti by remember { mutableStateOf(false) }"),
        "看台手势被摘": ("main", ".pointerInput(sg.index, segMulti, segChecked,"),
        "看台方块被摘": ("main", "SelectBox(checked = sg.index in segChecked"),
        "看台底部条被摘": ("main", 'allLabel = "全选已生成"'),
        "批量试听被摘": ("main", '"试听选中"'),
        "批量删除被摘": ("main", '"删除选中"'),
        "批量试听不隔离": ("main", 'PlayQueue.setTracks(picked, "seg")'),
        "删段后索引不刷新": ("main", "filterValues { it.isFile && it.length() > 44 }"),
        "两态判定被摘": ("queue", 'private fun isPreview(id: String): Boolean = id == "seg" || id == "trial"'),
    }
    bad = 0
    for name, (k, needle) in cuts.items():
        x = dict(f)
        x[k] = f[k].replace(needle, "/* removed */")
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for k, olds in BAN.items():
        for old in olds:
            x = dict(f)
            x[k] = f[k] + chr(10) + "// " + old + chr(10)
            if not check(x):
                bad += 1
                print("  旧形态注入漏放:", old[:44])
    total = len(cuts) + sum(len(v) for v in BAN.values())
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R134 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R134 统一多选态 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
