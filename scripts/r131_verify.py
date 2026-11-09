# -*- coding: utf-8 -*-
"""R131 · 三列归位 / 独立入口撤除验证。

用户确认：音频管理页与任务章节详情页的其他入口都不保留，能力归到书的目录页三列。

守：抽屉无「我的音频」独立项 / 所有跳转落到 CHAPTERS 并带目标列 /
目录页接收 initialCol / 第二列承接任务层 / 第三列承接音频层 /
枚举与旧页函数体暂留作能力库（下轮搬完再删）。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r131_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "chrome": APP / "AppChrome.kt",
    "main": ui_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "chrome": [
        "internal fun AppDrawerSheet(",
        'title: String',
        'private fun GearIcon(',
    ],
    "main": [
        "fun openBook(id: String?)",
        "fun openBook(id: String?)",
        "nav.push(Screen.CHAPTERS)",
        "nav.push(Screen.CHAPTERS)",
        "onOpenTask = { id -> openBook(id) }",
        "openBook(id)",
        "onDone = { nav.clear(); openBook(bookId); refresh++ }",
        "onBack = { nav.pop() },",
        "onResumeTask = {",
        "openBook(b.id)",
    ],
    "hub": [
        "internal fun ChapterHubScreen(",
        "onResumeTask: () -> Unit = {}",
        "private fun ChapterRow(",
        "LaunchedEffect(showResplit, book.id, planTick)",
        "items(chapterSegs.chunked(SEG_GRID_COLUMNS),",
        "onLong = { enterMulti(r.index) },",
        "item(key = \"ch_${r.index}\")",
        "继续队列",
        "队列 ${queued.size}",
        "可播 $finishedChapters/${ui.size} 章",
        "%.1f MB",
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
        "nav.push(Screen.PROGRESS)",
        "screen = Screen.PROGRESS",
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
    # R149 · 任务页「听成品」改成传该卡自己的 bookId（修多任务跳错书），
    # 原来绑死全局 taskBookId 的字面量就红了。守语义：音频入口必须落第三列，
    # 且用这次卡片的 id，不关心变量名。
    if not re.search(r"onAudio\s*=\s*\{[^\n}]*openBook\(", f["main"]):
        e.append("音频入口没落第三列")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "目标列状态被摘": ("main", "fun openBook(id: String?)"),
        "统一跳转被摘": ("main", "fun openBook(id: String?)"),
        "任务入口不落第二列": ("main", "onOpenTask = { id -> openBook(id) }"),
        "续跑不落第二列": ("main", "openBook(b.id)"),
        "音频入口不落第三列": ("main", "onAudio = { id -> openBook(id)"),
        "目录页不接初始列": ("main", "onBack = { nav.pop() },"),
        "目录页不接初始列参数": ("hub", "internal fun ChapterHubScreen("),
        "初始列不生效": ("hub", "private fun ChapterRow("),
        "第二列不承接任务": ("hub", "item(key = \"ch_${r.index}\")"),
        "续跑入口被摘": ("hub", "继续队列"),
        "任务队列入口被摘": ("hub", "队列 ${queued.size}"),
        "第三列不承接音频": ("hub", "可播 $finishedChapters/${ui.size} 章"),
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
