# -*- coding: utf-8 -*-
"""R131 · 三列归位 / 独立入口撤除验证。

用户确认：音频管理页与任务章节详情页的其他入口都不保留，能力归到书的目录页三列。

守：抽屉无「我的音频」独立项 / 所有跳转落到 CHAPTERS 并带目标列 /
目录页接收 initialCol / 第二列承接任务层 / 第三列承接音频层 /
枚举与旧页函数体暂留作能力库（下轮搬完再删）。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r131_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "chrome": APP / "AppChrome.kt",
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "chrome": [
        "「我的音频」独立入口已撤（R131）",
    ],
    "main": [
        "var hubCol by rememberSaveable { mutableStateOf(0) }",
        "fun openBookCol(id: String?, col: Int) {",
        "hubCol = col.coerceIn(0, 2)",
        "nav.push(Screen.CHAPTERS)",
        "onOpenTask = { id -> openBookCol(id, 1) }",
        "openBookCol(id, 1)",
        "onAudio = { openBookCol(taskBookId, 2); refresh++ }",
        "onDone = { nav.clear(); openBookCol(bookId, 2); refresh++ }",
        "initialCol = hubCol,",
        "onResumeTask = {",
        "if (taskChapter != null) nav.push(Screen.PROGRESS)",
    ],
    "hub": [
        "initialCol: Int = 0",
        "onResumeTask: () -> Unit = {}",
        "if (initialCol > 0) initialCol.coerceIn(0, 2)",
        "LaunchedEffect(initialCol)",
        "1 -> SynColumn(ui, { i -> playChapter(i, single = true) },",
        "onResumeTask = onResumeTask, onTasks = onProgress,",
        "item(key = \"syn_task_head\")",
        "继续制作 ›",
        "任务队列 ›",
        "可播音频 · ${finished.size}/${ui.size} 章",
        "共 %.1f MB · 本地文件",
    ],
    "ci": ["python3 scripts/r131_verify.py --selftest"],
}

BAN = {
    "chrome": ['NAV_AUDIO to "我的音频"'],
    "main": [
        "nav.push(Screen.AUDIO)",
        "nav.push(Screen.TASK_DETAIL)",
        "NAV_AUDIO -> Screen.AUDIO",
        "screen = Screen.AUDIO; refresh++",
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
                e.append("旧入口残留[%s]: %s" % (key, s[:66]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "目标列状态被摘": ("main", "var hubCol by rememberSaveable { mutableStateOf(0) }"),
        "统一跳转被摘": ("main", "fun openBookCol(id: String?, col: Int) {"),
        "任务入口不落第二列": ("main", "onOpenTask = { id -> openBookCol(id, 1) }"),
        "音频入口不落第三列": ("main", "onAudio = { openBookCol(taskBookId, 2); refresh++ }"),
        "目录页不接初始列": ("main", "initialCol = hubCol,"),
        "目录页不接初始列参数": ("hub", "initialCol: Int = 0"),
        "初始列不生效": ("hub", "if (initialCol > 0) initialCol.coerceIn(0, 2)"),
        "第二列不承接任务": ("hub", "item(key = \"syn_task_head\")"),
        "续跑入口被摘": ("hub", "继续制作 ›"),
        "任务队列入口被摘": ("hub", "任务队列 ›"),
        "第三列不承接音频": ("hub", "可播音频 · ${finished.size}/${ui.size} 章"),
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
                print("  旧入口注入漏放:", old[:44])
    total = len(cuts) + sum(len(v) for v in BAN.values())
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R131 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R131 三列归位 正向 %d 条 / 旧入口 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
