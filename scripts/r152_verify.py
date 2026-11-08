# -*- coding: utf-8 -*-
"""R152 · H 批「三页重设计」守门。

用户实证（第一份真机日志后的使用感受）：
  · 模型页「导入模型的按钮变成了回书架导入一本书」→ 建议变成红色卸载模型；
    底座有「详情」，三个模式「好像就没有」；设置页「排版很难看，不像人用的」
  · 书架页「统计字数和本地离线对用户没有任何价值」「右边向右箭头没价值」
    「右边应该就一个删除按钮和一个置顶按钮」「书名后面应该是颜色条，
    直观看出这本书的合成进度」

纠正后按纠正做的：
  · 「卸载模型」不是把导入按钮染红，而是让主按钮语义固定（永远「导入模型文件」），
    另外给一个红色危险操作 + 二次确认；只删模型文件，书与音频不动
  · 颜色条放在书名下方整行（书名长短不定，塞在书名后面会被挤没），
    旁边给「已做好 N/M 章」，一眼能读
  · 置顶单独存 pins.json，不动 Book 数据类与既有 save()（老 books.json 零迁移）

用法：python3 scripts/r152_verify.py --selftest
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "pipe": APP / "core" / "Pipeline.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "pipe": [
        "private fun pinFile(c: Context)",
        "fun pinned(c: Context): Set<String>",
        "fun setPinned(c: Context, id: String, on: Boolean)",
        "books.sortedByDescending { it.id in pins }",
        "try { setPinned(c, id, false) }",
    ],
    "main": [
        # H1 书架
        "var progress by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }",
        "val (reads, prog) = withContext(Dispatchers.IO) {",
        "ChapterPlan.resolve(c, b).first.size",
        "AudioLibrary.playableChapters(c, b.id).size",
        "val pinned = remember(refresh) { BookStore.pinned(c) }",
        "val (done, total) = progress[b.id] ?: (0 to 0)",
        "val pct = if (total > 0) done.toFloat() / total.toFloat() else 0f",
        "Box(Modifier.fillMaxWidth(pct).fillMaxHeight()",
        '"已做好 $done/$total 章"',
        "val libScope = rememberCoroutineScope()",
        "libScope.launch(Dispatchers.IO) {",
        "BookStore.setPinned(c, b.id, next)",
        "withContext(Dispatchers.Main) { onChanged() }",
        'EventLog.ui("BOOK_PIN_TAP"',
        '"取消置顶"', '"置顶"',
        # H2 模型页
        "var confirmUninstall by remember { mutableStateOf(false) }",
        "Text(if (open) \"收起\" else \"详情 ›\",",
        "if (!open) {\n                            Text(mode.hint",
        'EventLog.ui("MODEL_UNINSTALL_ASK"',
        'EventLog.ui("MODEL_UNINSTALL"',
        '"卸载模型"', '"卸载已导入的模型？"',
        "ModelStore.files(c).forEach { ModelStore.delete(c, it) }",
        # R165 · 模型页不再放「去书架」，返回交给顶栏统一给（二级页 canBack）。
        'ChromeSpec("模型", canBack = true)',
        'Text("导入模型文件", fontSize = 13.sp)',
        "colors = ButtonDefaults.buttonColors(containerColor = Warn)",
        # H3 设置页
        "Card(\n        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),",
        "colors = CardDefaults.cardColors(containerColor = palette.card)",
        "Box(Modifier.background(PrimarySoft, RoundedCornerShape(6.dp))",
        '"模型已就绪"', '"模型还没齐"',
        "RoundedCornerShape(10.dp))\n                .padding(3.dp)",
        "if (on) palette.card else Color.Transparent",
        '"清理", fontSize = 12.sp',
        '"去开启", fontSize = 12.sp',
    ],
    "ci": ["python3 scripts/r152_verify.py --selftest"],
    "r135": ['"r152_verify.py"'],
}

BAN = {
    "main": [
        '"${b.chars} 字 · 本地离线"',
        'Text("›", fontSize = 20.sp, color = Sub)',
        '"回书架，导入一本书"',
        'Text("去书架 ›"',
        '"切换立即生效"',
        'Text("清理 ›", fontSize = 12.sp, color = Primary,',
        'Text("去开启 ›", fontSize = 12.sp, color = Primary,',
        'lastReads = withContext(Dispatchers.IO) { ReadingState.allLast(c) }',
    ],
    "pipe": [],
}


def settings_section_body(main):
    a = main.find("private fun SettingsSection(")
    if a < 0:
        return ""
    b = main.find("\n}\n\n@Composable", a)
    return main[a:b] if b > a else ""


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def pin_sort(books, pins):
    """复刻 list() 的稳定置顶排序。"""
    order = {bid: i for i, bid in enumerate(books)}
    return sorted(books, key=lambda b: (0 if b in pins else 1, order[b]))


def book_progress(done, total):
    if total <= 0:
        return 0.0, "还没分章"
    safe = max(0, min(done, total))
    pct = max(0.0, min(safe / total, 1.0))
    return pct, "已做好 %d/%d 章" % (safe, total)


def uninstall_allowed(files, running):
    return bool(files) and not running


def logic_cases(f):
    bad = []
    # ① 置顶排序：稳定、非置顶保持原顺序
    books = ["A", "B", "C", "D"]
    cases = [
        ({"C"}, ["C", "A", "B", "D"]),
        (set(), ["A", "B", "C", "D"]),
        ({"B", "D"}, ["B", "D", "A", "C"]),
        ({"A", "B", "C", "D"}, ["A", "B", "C", "D"]),
    ]
    for pins, want in cases:
        got = pin_sort(books, pins)
        if got != want:
            bad.append("置顶排序 %s → %s 期望 %s" % (sorted(pins), got, want))
        else:
            print("  置顶 %-12s → %s" % (str(sorted(pins)), got))

    # ② 进度口径
    for done, total, want_pct in ((0, 0, 0.0), (0, 10, 0.0), (3, 10, 0.3),
                                 (10, 10, 1.0), (15, 10, 1.0), (-1, 10, 0.0)):
        pct, label = book_progress(done, total)
        if abs(pct - want_pct) > 1e-6:
            bad.append("进度 %d/%d → %.2f 期望 %.2f" % (done, total, pct, want_pct))
        else:
            print("  进度 %2d/%-2d → %3.0f%% · %s" % (done, total, pct * 100, label))

    # ③ 卸载守卫
    for files, running, want in (
        ([], False, False), (["a.gguf"], False, True),
        (["a.gguf"], True, False), ([], True, False),
    ):
        got = uninstall_allowed(files, running)
        if got != want:
            bad.append("卸载守卫 files=%s running=%s → %s 期望 %s" %
                       (len(files), running, got, want))
        else:
            print("  卸载 files=%d running=%-5s → %s" %
                  (len(files), running, "可点" if got else "禁用"))

    # ④ 模型页不能有整页滚动（R136 也守，这里再守一道语义）
    a = f["main"].find("private fun ModelScreen(")
    b = f["main"].find("\n@OptIn(ExperimentalLayoutApi::class)", a)
    mb = f["main"][a:b if b > 0 else len(f["main"])]
    if "verticalScroll" in mb:
        bad.append("模型页又变成长滚动页")
    if mb.count('Text("导入模型文件"') != 1:
        bad.append("导入模型文件主按钮不是唯一稳定入口")
    if "卸载模型" not in mb or "confirmUninstall" not in mb:
        bad.append("红色卸载模型缺二次确认")
    # 三张模式卡默认态都要有 hint 与详情入口
    if "if (!open) {" not in mb or "mode.hint" not in mb:
        bad.append("三模式默认态没有各自的信息")
    if 'else "详情 ›"' not in mb:
        bad.append("三模式卡缺可见「详情」入口")

    # ⑤ 设置页必须是卡片分区，不能退回裸分隔线
    body = settings_section_body(f["main"])
    if not body:
        bad.append("没圈出 SettingsSection 函数体")
    else:
        if "Card(" not in body or "RoundedCornerShape(16.dp)" not in body:
            bad.append("设置分区没卡片化")
        if "HorizontalDivider(color = Line)" in body:
            bad.append("设置分区又退回裸分隔线")
        else:
            print("  SettingsSection 卡片分区 ✓")

    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:75]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    # 裸分隔线只禁在设置分区内；工程别处（调音台等）用它合法。
    sb = settings_section_body(f["main"])
    if sb and "HorizontalDivider(color = Line)" in sb:
        e.append("设置分区又退回裸分隔线")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("置顶存储被摘", "pipe", "private fun pinFile(c: Context)", ""),
        ("置顶读被摘", "pipe", "fun pinned(c: Context): Set<String>", ""),
        ("置顶写被摘", "pipe", "fun setPinned(c: Context, id: String, on: Boolean)", ""),
        ("置顶排序被摘", "pipe", "books.sortedByDescending { it.id in pins }", ""),
        ("删书清 pin 被摘", "pipe", "try { setPinned(c, id, false) }", ""),
        ("进度数据被摘", "main",
         "var progress by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }", ""),
        ("章数来源被摘", "main", "ChapterPlan.resolve(c, b).first.size", ""),
        ("成品数来源被摘", "main", "AudioLibrary.playableChapters(c, b.id).size", ""),
        ("一次 IO 读盘被摘", "main", "val (reads, prog) = withContext(Dispatchers.IO) {",
         "val (reads, prog) = withContext(Dispatchers.Main) {"),
        ("进度色条被摘", "main", "Box(Modifier.fillMaxWidth(pct).fillMaxHeight()", ""),
        ("置顶按钮被摘", "main", "BookStore.setPinned(c, b.id, next)", ""),
        ("置顶写盘退回主线程", "main", "libScope.launch(Dispatchers.IO) {", ""),
        ("字数回潮", "main", '"已做好 $done/$total 章"', '"${b.chars} 字 · 本地离线"'),
        ("右箭头回潮", "main", '"取消置顶"', 'Text("›", fontSize = 20.sp, color = Sub)'),
        ("卸载确认态被摘", "main", "var confirmUninstall by remember { mutableStateOf(false) }", ""),
        ("红色卸载被摘", "main", "colors = ButtonDefaults.buttonColors(containerColor = Warn)", ""),
        ("卸载执行被摘", "main", "ModelStore.files(c).forEach { ModelStore.delete(c, it) }", ""),
        ("变形按钮回潮", "main", 'Text("导入模型文件", fontSize = 13.sp)',
         '"回书架，导入一本书"'),
        ("模型页返回键丢失", "main", 'ChromeSpec("模型", canBack = true)',
         'ChromeSpec("模型")'),
        ("稳定主按钮被摘", "main", 'Text("导入模型文件", fontSize = 13.sp)', ""),
        ("三模式默认信息被摘", "main", 'if (!open) {\n                            Text(mode.hint', ""),
        ("三模式详情入口被摘", "main", 'Text(if (open) "收起" else "详情 ›",', ""),
        ("设置卡片被摘", "main",
         "Card(\n        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),", ""),
        ("徽标被摘", "main", "Box(Modifier.background(PrimarySoft, RoundedCornerShape(6.dp))", ""),
        ("主题分段被摘", "main", "if (on) palette.card else Color.Transparent", ""),
        ("状态卡被摘", "main", '"模型已就绪"', ""),
        ("裸分隔线回潮", "main", "Box(Modifier.background(PrimarySoft, RoundedCornerShape(6.dp))",
         "HorizontalDivider(color = Line)"),
        ("CI 漏挂 r152", "ci", "python3 scripts/r152_verify.py --selftest", ""),
        ("现行清单漏 r152", "r135", '"r152_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R152 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R152 三页重设计（书架精简+进度+置顶 / 模型页卸载 / 设置页卡片化）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
