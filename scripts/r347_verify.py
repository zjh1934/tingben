# -*- coding: utf-8 -*-
"""R347 · 设置页「本机音频」浏览弹窗重设计：两个页签都能多选 / 全选 / 一键导出。

用户实证（真机截图）：弹窗里「中间小块 / 整章成品」只有一行行点开另存，
长文件名被 maxLines = 1 截掉，滚过组标题后认不出是哪本书、哪一章哪一段；
没有多选、全选，更没有一键导出。

R347 口径：
  · 两个页签共用同一套勾选：整行点选、全选 / 取消全选、选本组 / 取消本组，
    切页签清空勾选，不跨页签把别处的文件导出去；
  · 选中项一键导出到「导出全部」已经设好的目录树（沿用 AudioExport.Item），
    单文件「另存」原样保留在行尾，能力不丢；
  · 行内标题自带「书名 · 第几章 / 第几段」，文件名固定留两行（maxLines = 2
    + 最小高度），长名字折行完整可见，不再一行截断；
  · 导出主体抽成 writeItems，导出全部与导出选中共用，幂等 / 建目录 / 写文件
    行为完全一致。

守恒：browseGroups 按书分组、Dialog 两个页签、单文件 SAF 另存、导出位置、
清理中间小块的二次确认、设置页三行结构，一条没动。

用法：python3 scripts/r347_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "settings": APP / "SettingsUi.kt",
    "export": APP / "core" / "AudioExport.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "settings": [
        # 行内定位标题：书名 + 章 / 段，组标题滚走也认得出
        "internal fun audioFileLabel(f: File, group: String, segChapter: Int? = null): String",
        "stem == \"整本\" -> \"$group · 整本音频\"",
        "segChapter?.let { \"第 ${it + 1} 章 · \" }",
        "Text(audioFileLabel(f, g, segCh), fontSize = 12.sp",
        # 文件名固定留两行
        "maxLines = 2,",
        "Modifier.heightIn(min = 30.dp)",
        "lineHeight = 13.sp",
        # 勾选：按页签隔离、全选、选本组、整行点选
        "var picked by remember(tab) { mutableStateOf(emptySet<String>()) }",
        "TriStateCheckbox(",
        "if (allOn) \"取消全选\" else \"全选\"",
        "\"取消本组\" else \"选本组\"",
        "Checkbox(checked = on, onCheckedChange = null,",
        ".clickable {\n                                            picked = if (on)",
        # 单文件另存保留 + 一键导出
        "Text(\"另存\", fontSize = 11.sp,",
        "onSaveSelected: (List<Pair<String, File>>) -> Unit,",
        "onSaveSelected(picked.mapNotNull { byPath[it] })",
        # R348 起导出按钮多一态「正在导出…」，锚按现行三分支迁移，能力不减
        "Text(if (exporting) \"正在导出…\"",
        "else if (picked.isEmpty()) \"导出选中\"",
        "else \"导出选中 · ${picked.size} 个\", fontSize = 12.sp",
        "AudioExport.exportPicked(c, tree,",
        "AudioLibrary.safeName(g), f.name, f)",
        # 副行按新形态说清「可批量导出」
        "\"按书分组 · $total 个 · 勾选后可批量导出\"",
        "\"章成品 / 中间小块 · 按书分组 · 可勾选批量导出\"",
        "\"M4A · 章音频\"",
        "\"WAV · 中间小块\"",
    ],
    "export": [
        "fun exportAll(c: Context, tree: Uri?): Result",
        "fun exportPicked(c: Context, tree: Uri?, items: List<Item>): Result",
        "private fun writeItems(c: Context, t: Uri, items: List<Item>): Result",
        "return writeItems(c, t, items)",
        "if (items.isEmpty()) return Result(reason = \"还没勾选音频\")",
        # 守恒：幂等与写文件主体一字没动
        "DocumentsContract.getTreeDocumentId(t)",
        "AudioOps.copyTo(c, it.file, child)",
    ],
    "ci": [
        "python3 scripts/r347_verify.py",
        "python3 scripts/r347_verify.py --selftest",
    ],
    "r135": ['"r347_verify.py"'],
}

BAN = {
    "settings": [
        # 旧形态回潮：一行截断文件名、整行点另存、旧副标题
        "val seg = segRe.matchEntire(f.name)",
        "\"按书分组 · $total 个 · 可另存\"",
        "\"章成品 / 中间小块 · 按书分组 · 点一个另存\"",
        ".clickable { onSave(f) }",
        "maxLines = 1, overflow = TextOverflow.Ellipsis)\n                                    }",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for k, needles in POS.items():
        for n in needles:
            if n not in f[k]:
                e.append("R347 %s 缺: %s" % (k, n[:72]))
    for k, needles in BAN.items():
        for n in needles:
            if n in f[k]:
                e.append("R347 %s 旧形态回潮: %s" % (k, n[:60]))
    # 两个入口都走同一套写文件逻辑，不能只接一个
    if f["export"].count("return writeItems(c, t, items)") < 2:
        e.append("R347 导出全部 / 导出选中没共用 writeItems")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("行内标题被摘", "settings", "internal fun audioFileLabel(f: File, group: String, segChapter: Int? = null): String", ""),
        ("标题没接上列表", "settings", "Text(audioFileLabel(f, g, segCh), fontSize = 12.sp", ""),
        ("文件名压回一行", "settings", "maxLines = 2,", "maxLines = 1,"),
        ("两行最小高度被摘", "settings", "Modifier.heightIn(min = 30.dp)", "Modifier.heightIn(min = 0.dp)"),
        ("勾选没按页签隔离", "settings", "var picked by remember(tab) { mutableStateOf(emptySet<String>()) }",
         "var picked by remember { mutableStateOf(emptySet<String>()) }"),
        ("全选框被摘", "settings", "TriStateCheckbox(", ""),
        ("取消全选被摘", "settings", "if (allOn) \"取消全选\" else \"全选\"", "\"全选\""),
        ("选本组被摘", "settings", "\"取消本组\" else \"选本组\"", "\"全选\""),
        ("行内勾选被摘", "settings", "Checkbox(checked = on, onCheckedChange = null,", ""),
        ("整行点选被摘", "settings", ".clickable {\n                                            picked = if (on)", ""),
        ("单文件另存被摘", "settings", "Text(\"另存\", fontSize = 11.sp,", ""),
        ("回调签名被摘", "settings", "onSaveSelected: (List<Pair<String, File>>) -> Unit,", ""),
        ("一键导出按钮被摘", "settings", "onSaveSelected(picked.mapNotNull { byPath[it] })", ""),
        ("导出计数被摘", "settings", "else \"导出选中 · ${picked.size} 个\"", "else \"导出\""),
        ("导出调用被摘", "settings", "AudioExport.exportPicked(c, tree,", ""),
        ("书名目录没安全化", "settings", "AudioLibrary.safeName(g), f.name, f)", "g, f.name, f)"),
        ("副标题回潮", "settings", "\"按书分组 · $total 个 · 勾选后可批量导出\"",
         "\"按书分组 · $total 个 · 可另存\""),
        ("设置行副标题回潮", "settings", "\"章成品 / 中间小块 · 按书分组 · 可勾选批量导出\"",
         "\"章成品 / 中间小块 · 按书分组 · 点一个另存\""),
        ("格式标签被摘", "settings", "\"M4A · 章音频\"", ""),
        ("旧解析回潮", "settings", "Text(audioFileLabel(f, g, segCh), fontSize = 12.sp",
         "val seg = segRe.matchEntire(f.name)\n                                        Text(audioFileLabel(f, g), fontSize = 12.sp"),
        ("旧整行另存回潮", "settings", "Text(\"另存\", fontSize = 11.sp,",
         ".clickable { onSave(f) }\n                                        Text(\"另存\", fontSize = 11.sp,"),
        ("exportPicked 被摘", "export", "fun exportPicked(c: Context, tree: Uri?, items: List<Item>): Result", ""),
        ("writeItems 被摘", "export", "private fun writeItems(c: Context, t: Uri, items: List<Item>): Result", ""),
        ("共用被拆", "export", "return writeItems(c, t, items)", "return Result()"),
        ("空勾选没拦", "export", "if (items.isEmpty()) return Result(reason = \"还没勾选音频\")", ""),
        ("CI 漏挂", "ci", "python3 scripts/r347_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r347_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = f[key].replace(needle, repl)
        if check(g):
            print("  反向注入 %-22s 拦下" % name)
        else:
            print("  反向注入漏放:", name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    e = check(load())
    if e:
        print("R347 FAIL", len(e))
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R347 本机音频弹窗 多选/全选/一键导出/文件名两行：PASS")
