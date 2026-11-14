# -*- coding: utf-8 -*-
"""R348 · 设置页「本机音频」弹窗第二轮：长名两行首尾可见 / 反选 / 章号 / 导出中态。

用户实证（真机截图）：中间小块与整章成品两个页签只有一行行点开另存，
长文件名被一行吃掉，滚过组标题认不出是哪本书哪一章哪一段，也没有
多选 / 全选 / 一键导出。R347 已把多选、全选、选本组、一键导出和两行
文件名接上；R348 对着改完的样子再收四处：

  · 文件名固定两行：两行放得下就整名折行（R349 看图后第三稿，旧版 30 字
    就省会把稍长的名字白白掩盖），超两行才从中间省，开头 + 「段号 · 扩展名」都留着；
  · 行内标签补所属章号，复用 AudioTree 的 seg_chapters.json，不另造解析；
    书名 / 章名长也给两行，不再一行 Ellipsis；
  · 全选旁补「反选」，勾了大半本想导剩下几段不用从头点；
  · 导出中把按钮锁住并显示「正在导出…」，防连点重复写。

守恒：两个页签、按书分组、整行点选、选本组、单文件另存、切页签清勾选、
AudioExport.exportPicked 与 exportAll 共用 writeItems、导出位置与幂等口径，
一条没动。

用法：python3 scripts/r348_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "settings": APP / "SettingsUi.kt",
    "audiotree": APP / "core" / "AudioTree.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "settings": [
        # 长文件名两行：两行放得下整名折行，超两行才中间省、首尾都在
        "internal fun audioFileNameLines(name: String): String",
        "val perLine = 19",
        "if (name.length <= perLine * 2) {",
        "(name.length - i) >= 5",
        "return name.take(perLine) + \"\\n…\" + name.takeLast(perLine)",
        "Text(audioFileNameLines(f.name),",
        # R349 · 导出中转圈，图与代码同形
        "CircularProgressIndicator(",
        # 行内标签：章号复用现成映射，标签也给两行
        "internal fun browseSegChapter(f: File, seg: Int): Int?",
        "internal fun browseSegIndex(f: File): Int?",
        "AudioTree.readSegChapters(d)",
        "segChapter?.let { \"第 ${it + 1} 章 · \" }",
        "maxLines = 2,\n                                            lineHeight = 15.sp",
        "val segCh = remember(f.absolutePath) {",
        # 看图后调整：部分选中给半选态、格式标签挪到体积列
        "import androidx.compose.ui.state.ToggleableState",
        "TriStateCheckbox(",
        "ToggleableState.Indeterminate",
        "// R348 · 看图后调整：格式标签挪到体积下面，",
        # 反选
        "Text(\"反选\", fontSize = 11.sp, color = Sub,",
        "picked = allPaths - picked",
        # 导出中态进弹窗，父层把状态传进来
        "exporting: Boolean = false,",
        "enabled = picked.isNotEmpty() && !exporting,",
        "if (exporting) \"正在导出…\"",
        "exporting = exporting,",
        # 往上找章表不无限爬
        "hop < 6",
    ],
    "audiotree": [
        "internal fun readSegChapters(dir: File): Map<Int, Int>? {",
    ],
    "ci": [
        "python3 scripts/r348_verify.py",
        "python3 scripts/r348_verify.py --selftest",
    ],
    "r135": ['"r348_verify.py"'],
}

BAN = {
    "settings": [
        # 旧形态：一行尾部省略、标题一行截断、导出按钮无 busy 守卫
        "Text(f.name, fontSize = 10.sp, color = Sub,",
        "maxLines = 1,\n                                            overflow = TextOverflow.Ellipsis)",
        "enabled = picked.isNotEmpty(),",
        "\"按书分组 · $total 个 · 可另存\"",
        "Checkbox(checked = allOn,",
        "name.take(18) + \"\\n…\" + name.takeLast(16)",
        # R349 · 30 字就省＝把两行放得下的名字又掩盖回去
        "if (name.length <= 30) return name",
    ],
    "audiotree": [
        "private fun readSegChapters(dir: File)",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for k, needles in POS.items():
        for n in needles:
            if n not in f[k]:
                e.append("R348 %s 缺: %s" % (k, n[:72]))
    for k, needles in BAN.items():
        for n in needles:
            if n in f[k]:
                e.append("R348 %s 旧形态回潮: %s" % (k, n[:60]))
    # 两个页签必须共用同一份勾选与同一枚导出按钮，不许各做一套
    if f["settings"].count("onSaveSelected(picked.mapNotNull { byPath[it] })") != 1:
        e.append("R348 一键导出口径被拆成多份")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("两行文件名被摘", "settings", "internal fun audioFileNameLines(name: String): String", ""),
        ("中间省略被摘", "settings",
         "return name.take(perLine) + \"\\n…\" + name.takeLast(perLine)", "return name"),
        ("两行整名折行被摘", "settings", "if (name.length <= perLine * 2) {", "if (false) {"),
        ("折行孤行禁令被摘", "settings", "(name.length - i) >= 5", "true"),
        ("旧 30 字阈值回潮", "settings", "val perLine = 19",
         "if (name.length <= 30) return name"),
        ("导出中转圈被摘", "settings", "CircularProgressIndicator(", ""),
        ("列表没接两行名", "settings", "Text(audioFileNameLines(f.name),", "Text(f.name,"),
        ("标签压回一行", "settings", "maxLines = 2,\n                                            lineHeight = 15.sp",
         "maxLines = 1,"),
        ("章号读取被摘", "settings", "internal fun browseSegChapter(f: File, seg: Int): Int?", ""),
        ("段号解析被摘", "settings", "internal fun browseSegIndex(f: File): Int?", ""),
        ("章表复用被摘", "settings", "AudioTree.readSegChapters(d)", ""),
        ("章号没进标签", "settings", "segChapter?.let { \"第 ${it + 1} 章 · \" }", "null"),
        ("IO 缓存被摘", "settings", "val segCh = remember(f.absolutePath) {", ""),
        ("反选入口被摘", "settings", "Text(\"反选\", fontSize = 11.sp, color = Sub,", ""),
        ("反选算法被摘", "settings", "picked = allPaths - picked", "picked = emptySet()"),
        ("弹窗没收导出态", "settings", "exporting: Boolean = false,", ""),
        ("父层没传导出态", "settings", "exporting = exporting,", ""),
        ("连点没拦", "settings", "enabled = picked.isNotEmpty() && !exporting,",
         "enabled = picked.isNotEmpty(),"),
        ("进行中没文案", "settings", "if (exporting) \"正在导出…\"", "if (picked.isEmpty())"),
        ("向上找章表无界", "settings", "hop < 6", "true"),
        ("章表没给复用", "audiotree", "internal fun readSegChapters(dir: File): Map<Int, Int>? {",
         "private fun readSegChapters(dir: File): Map<Int, Int>? {"),
        ("一行文件名回潮", "settings", "Text(audioFileNameLines(f.name),",
         "Text(f.name, fontSize = 10.sp, color = Sub,"),
        ("旧副标题回潮", "settings", "exporting: Boolean = false,",
         "\"按书分组 · $total 个 · 可另存\""),
        ("三态框被摘", "settings", "TriStateCheckbox(", "Checkbox(checked = allOn,"),
        ("半选态被摘", "settings", "ToggleableState.Indeterminate", "ToggleableState.Off"),
        ("状态枚举没导入", "settings", "import androidx.compose.ui.state.ToggleableState", ""),
        ("格式标签回左侧", "settings", "// R348 · 看图后调整：格式标签挪到体积下面，", ""),
        ("旧尾部截断回潮", "settings",
         "return name.take(perLine) + \"\\n…\" + name.takeLast(perLine)",
         "name.take(18) + \"\\n…\" + name.takeLast(16)"),
        ("CI 漏挂", "ci", "python3 scripts/r348_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r348_verify.py"', ""),
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
        print("R348 FAIL", len(e))
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R348 本机音频弹窗 长名两行/反选/章号/导出中态：PASS")
