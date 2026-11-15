#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R362 · 目录页三处真 bug + 章状态单一真值。

1. 正在制作的那一章 = 本次范围内「最靠前的未完成章」。旧写法取最大章号、
   空表兜底 only.maxOrNull()，勾 2/3/4/5 会先指向第 5 章、点进度条也跳第 5 章。
2. 暂停 / 队列暂停后两条进度条不许归零：stop() 曾重建 Progress 抹掉逐章进度，
   目录页又只认 task 里旧的 done=0。现在认「任务档案 + 磁盘章成品 + 实时 state」。
3. 章行四态（已固化 / 制作中 / 本次范围内待做 / 没动过）一处派生，不靠字色区分。
4. 点进度条回跳时补上展开章插入的 item 偏移，章号 ≠ LazyColumn 序号。

档案口径：不新建索引系统。TaskInfo（task_active.json / tasks.json）本来就存了
only / done / total / phase，ChapterPlan 是章表，AudioTree 是章级成品索引，
目录页只读它们 + 一处派生，避免三处各算各的。

用法：python3 scripts/r362_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": APP / "ChapterHub.kt",
    "producer": APP / "core/Producer.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
}

POS = {
    "hub": [
        "private enum class ChapterMark { DONE, MAKING, SCOPED, PLAIN }",
        "val mark: ChapterMark = ChapterMark.PLAIN",
        "a != null || ch.index in playable -> ChapterMark.DONE",
        "inScope(ch.index) -> ChapterMark.SCOPED",
        "r.copy(mark = ChapterMark.MAKING)",
        "val makingChapter = remember(st.chapterProgress, task, chapters.size, book.id) {",
        "if (scope != null && k !in scope) continue",
        "if (v < 1f && k < idx) idx = k",
        "if (idx == Int.MAX_VALUE) idx = scope?.minOrNull() ?: 0",
        "val archive = remember(book.id, refresh, localTick, st.running) {",
        "TaskResume.byBook(c, book.id)",
        "val totalNow = maxOf(st.total, task?.total ?: 0, archive?.total ?: 0)",
        "val doneNow = maxOf(st.done, task?.done ?: 0)",
        "val scopeOnly = task?.takeIf { it.bookId == book.id }?.only ?: archive?.only",
        "val finishedInScope = ui.count { r ->",
        "maxOf(m, finishedInScope.toFloat() / scopeSize.toFloat()).coerceIn(0f, 1f)",
        "2 + chapterSegs.chunked(SEG_GRID_COLUMNS).size else 0",
        "val markColor = when (r.mark)",
        "ChapterMark.SCOPED -> HubPrimary.copy(alpha = 0.42f)",
        "val markLabel = when (r.mark)",
        '"本次待做"',
        "fontWeight = if (r.mark == ChapterMark.MAKING)",
        "R219 起章名左边常驻一排勾",
    ],
    "producer": [
        "R362 · 保留 chapterProgress",
        '_state.value = _state.value.copy(running = false, stage = "已停止"',
        "_resume.value = stopped",
    ],
    "ci": [
        "python3 scripts/r362_verify.py",
        "python3 scripts/r362_verify.py --selftest",
    ],
    "r135": ['"r362_verify.py"'],
}

BAN = {
    "hub": [
        # 旧：取最大未完成章 / 兜底最后一个勾选章
        "for ((k, v) in st.chapterProgress) if (v < 1f && k > idx) idx = k",
        "task?.only?.maxOrNull() ?: 0",
        # 旧：停一次两条进度一起归零，没有档案 / 磁盘兜底
        "val doneNow = (if (bookRunning) st.done else (task?.done ?: 0))",
        "for ((_, v) in st.chapterProgress) if (v > m) m = v\n        m\n    }",
        # 旧：勾选框口径两句话并存
        "勾选框**只在多选态出现**",
        "R163 · 勾选框平时不渲染",
        # 旧：范围内所有 <1f 的章都标制作中
        "running && inScope(ch.index) &&\n                    (chapterProgress[ch.index] ?: 0f) < 1f -> ChapterMark.MAKING",
    ],
    "producer": [
        # 旧：6 参重建 Progress，chapterProgress 被默认空表抹掉
        '_state.value = Progress(false, "已停止", _state.value.done, _state.value.total,',
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for k, needles in POS.items():
        for n in needles:
            if n not in f[k]:
                e.append("R362 %s 缺: %s" % (k, n[:64]))
    for k, needles in BAN.items():
        for n in needles:
            if n in f[k]:
                e.append("R362 %s 旧规则回潮: %s" % (k, n[:56]))
    # 四态必须互斥且一处派生：chapterStates 里不许出现 MAKING
    body = f["hub"]
    i = body.find("private fun chapterStates(")
    j = body.find("\n}\n", i)
    if i >= 0 and j > i and "ChapterMark.MAKING" in body[i:j]:
        e.append("R362 chapterStates 里又判 MAKING（应由 liveChapter 单点覆盖）")
    # 进度兜底必须在 chapterPctNow 里，不能只留一句注释
    if "maxOf(m, finishedInScope.toFloat()" not in body:
        e.append("R362 暂停后章进度没有磁盘兜底")
    return e


def selftest():
    f = load()
    base = check(f)
    if base:
        print("正向不绿，先修实现：")
        for x in base:
            print(" -", x)
        return 1
    print("正向 0 失败")
    cases = [
        ("起始章又取最大章号", "hub",
         "if (v < 1f && k < idx) idx = k",
         "if (v < 1f && k > idx) idx = k"),
        ("兜底又跳最后一个勾选章", "hub",
         "if (idx == Int.MAX_VALUE) idx = scope?.minOrNull() ?: 0",
         "if (idx == Int.MAX_VALUE) idx = task?.only?.maxOrNull() ?: 0"),
        ("范围外章又混进来", "hub",
         "if (scope != null && k !in scope) continue", ""),
        ("整本进度又只认旧 task", "hub",
         "val doneNow = maxOf(st.done, task?.done ?: 0)",
         "val doneNow = (if (bookRunning) st.done else (task?.done ?: 0))"),
        ("档案被摘", "hub",
         "val archive = remember(book.id, refresh, localTick, st.running) {", ""),
        ("暂停兜底被摘", "hub",
         "maxOf(m, finishedInScope.toFloat() / scopeSize.toFloat()).coerceIn(0f, 1f)",
         "m"),
        ("item 偏移补偿被摘", "hub",
         "2 + chapterSegs.chunked(SEG_GRID_COLUMNS).size else 0", "0"),
        ("四态竖条被摘", "hub",
         "val markColor = when (r.mark)", ""),
        ("制作中覆盖被摘", "hub",
         "r.copy(mark = ChapterMark.MAKING)", "r"),
        ("停止又重建 Progress", "producer",
         '_state.value = _state.value.copy(running = false, stage = "已停止"',
         '_state.value = Progress(false, "已停止", _state.value.done, _state.value.total,'),
        ("档案内存镜像又不同步", "producer",
         "_resume.value = stopped", ""),
        ("旧勾选口径回潮", "hub",
         "R219 起章名左边常驻一排勾", "勾选框**只在多选态出现**"),
        ("CI 不挂本判据", "ci",
         "python3 scripts/r362_verify.py --selftest", ""),
        ("清单不登记本判据", "r135",
         '"r362_verify.py"', ""),
    ]
    bad = 0
    for name, key, old, new in cases:
        f2 = dict(f)
        f2[key] = f2[key].replace(old, new, 1)
        if not check(f2):
            print("  没拦下：%s" % name)
            bad += 1
    print("注入 %d 组，拦下 %d 组" % (len(cases), len(cases) - bad))
    return 1 if bad else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    e = check(load())
    if e:
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R362 PASS")
