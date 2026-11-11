#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R163 · 目录页语义判据（唯一真源）。

历史背景：20 个验证脚本各自把「三列目录」的 UI 字面量写死进 POS/KEEP
（`HubTitles`、`HorizontalPager`、`SynColumn`、`openBookCol(id, 2)`、
`HUB_COL`、`"全选" to {`……）。R163 把三列并成一列后它们集体假红，
逐个哄脚本等于把旧债再抄一遍。

这里把判据收敛成「一本书一个主页，能力还在不在」：
  · 单列：三列/分页/列跳转零回潮
  · 章行：点进阅读、长按进多选、勾选框只在多选态、听/展开两枚图标（R171 撤三点）
  · 章级：拼整章 / 另存 WAV / 转 M4A / 删音频（删列不丢能力）
  · 小片：固定 10 列（整块居中 / 行内左对齐）、惰性行、点播再点停、长按多选、批量试听与删除
  · 顶部：双进度、停止/继续、可播汇总、顺序播放、队列就地管理
  · 其它：文本可选中复制、重新分章预览、segmap 同源、关键埋点

各脚本 `import hub_semantics as hub`，在 check() 里
`for m in hub.checks(f["hub"], f.get("main","")): e.append(...)`，
不再自己维护 ChapterHub 的字面量清单。
"""
from __future__ import annotations
import re

# ── 单列正向锚点（新代码里真实存在） ──────────────────────────────────
HUB_POS = [
    "internal fun ChapterHubScreen(",
    "private fun ChapterRow(",
    "private fun HeadphoneIcon(",
    "private fun ChevronIcon(",
    "private fun SegGridHead(",
    "private fun ChapterSegGrid(",
    # 章行：默认点进阅读、长按进多选；R171 起常态只有听 / 展开两枚图标
    "onRead = { openReader(r.index) }",
    "onLong = { menuChapter = r.index }",
    "IconButton(onClick = onListen",
    "IconButton(onClick = onRead",
    # R171 · 三点「更多」撤掉，选中一律长按；勾选态必须能退出
    "BackHandler(enabled = multiMode) { exitMulti() }",
    "BackHandler(enabled = segMulti) { exitSegMulti() }",
    "if (multiMode && menuChapter == null) {",
    # R171 · 任务看板就地看，不切独立任务页
    "TaskBoardDialog(",
    "Box(Modifier.size(34.dp).clickable { onToggle(r.index) }",
    # 勾选框只在多选态：ChapterRow 里 if (multi) 分支
    "SelectBox(checked = checked, tint = HubPrimary",
    # 章级操作（原第三列能力，删列不丢）
    "AudioOps.mergeChapter(c, book.id, book.title, i, segs)",
    "saveWav.launch(\"chapter.wav\")",
    "saveM4a.launch(\"chapter.m4a\")",
    "confirmChDel = selected.associateWith { filesOf(it) }",
    "AudioTree.clearDone(c, book.id, i)",
    "filesOf(i: Int): Pair<List<File>, File?>",
    "srcOf(i: Int): File?",
    # 分段小片：固定 10 列（整块居中 / 行内左对齐）+ 惰性行 + 点播暂停 + 长按多选
    "internal const val SEG_GRID_COLUMNS = 10",
    "chapterSegs.chunked(SEG_GRID_COLUMNS).forEach { row ->",
    "BoxWithConstraints(",
    "contentAlignment = Alignment.Center",
    "repeat(SEG_GRID_COLUMNS)",
    "Spacer(Modifier.size(cell))",
    "Row(Modifier.width(gridWidth)",
    # 深色序号对比度：2.93 → 5.29:1
    "private fun segNumberColor(",
    "color = segNumberColor(playing, playable, multi, isChecked,",
    "segCellColor(playable, playing, multi, isChecked,",
    "onLong = { i -> segMulti = true; segChecked = setOf(i) }",
    "onToggle = { i ->",
    "PlayQueue.setTracks(picked, \"seg\")",
    "PlayQueue.pausePreview()",
    "PlayQueue.resumePreview()",
    # 删段：二次确认 → IO → 索引记账
    "confirmSegDel?.let",
    "AudioTree.forget(c,",
    "AudioOps.deleteAll(files)",
    # 顶部：双进度 + 心跳（R226 · 停止/继续已上移系统顶栏，由 MAIN_POS 守）
    "LinearProgressIndicator(progress = { bookPct }",
    "LinearProgressIndicator(progress = { chapterPctNow }",
    # 可播汇总 / 顺序播放 / 队列就地管理
    "\"按顺序播放\"",
    "AudioLibrary.playableItems(c, book.id)",
    "TaskResume.queue(c)",
    "TaskResume.dequeue(c, t.bookId)",
    # R243 · 「继续队列」按钮砍掉，底层能力仍在 Producer；这里判同源数据，
    # 按钮回潮由 HUB_BAN 拦，不放宽能力判据。
    "TaskResume.realProgress(c, it)",
    # R169 · 章名深浅两色统一 palette.text：浅色下 HubGrey 只有 1.74:1，
    # 真机截图里 103 章整列看不见；「有没有做好」交给右侧耳机图标颜色。
    "color = palette.text,",
    # 其它守恒
    "ChapterPlan.preview(c, book, null)",
    "Workbench.segments(c, t) to Workbench.segChapters(c, t)",
    "SelectionContainer",
    'EventLog.ui("OPEN_READER"',
    'EventLog.ui("HUB_CH_EXPAND"',
    'EventLog.ui("SEG_TAP"',
    'EventLog.ui("RESPLIT_OPEN"',
]

# ── 三列 / 分页 / 列跳转零回潮 ───────────────────────────────────────
HUB_BAN = [
    # R243 · 费解的「继续队列」按钮不许回潮
    'Text("继续队列 ›"',
    "Producer.resumeQueue(c)",
    "HorizontalPager(",
    "HubTitles",
    "private fun SynColumn(",
    "private fun PlayColumn(",
    "private fun RawColumn(",
    "initialCol",
    "coerceIn(0, 2)",
    'EventLog.ui("HUB_COL"',
    'EventLog.ui("HUB_JUMP"',
    "分段看台 ·",
    "syn_seg_bench_head",
    # R164 · 逐行居中（wrapContentWidth）与旧 8 列切分都不许回潮
    "Row(Modifier.wrapContentWidth()",
    "chunked(8)",
    # R167 · 无条件灰字回潮（深色下章节名必须是白）
    'color = if (r.audio != null || (p ?: 0f) > 0f) Color.Unspecified else HubGrey,',
]

MAIN_POS = [
    "fun openBook(id: String?)",
    "nav.push(Screen.CHAPTERS)",
    'EventLog.ui("OPEN_BOOK"',
    # R226 · 三枚操作上移系统顶栏，与书名同一排：判据按符号、不绑颜色常量
    "trailing = when (screen)",
    'StopIcon(',
    'PlayIcon(',
    'TasksIcon(',
    'Text("⚙", fontSize = 16.sp',
    'EventLog.ui("HUB_STOP"',
    'EventLog.ui("HUB_RESUME"',
    'EventLog.ui("HUB_TASKS"',
    "fun hubStop()", "fun hubResume()",
    "tasksRequest = hubTasksTick",
]
MAIN_BAN = ["openBookCol", "hubCol", "initialCol ="]


def _between(src: str, start: str, end: str) -> str:
    i = src.find(start)
    if i < 0:
        return ""
    j = src.find(end, i + len(start))
    return src[i:j if j >= 0 else len(src)]


def chapter_row_ok(hub: str) -> bool:
    """R219 · 章名左边常驻一枚勾（点勾即选中，不再靠长按），右边眼睛+耳机两枚。"""
    body = _between(hub, "private fun ChapterRow(", "/** 代码绘制的耳机")
    if not body:
        return False
    return ("Box(Modifier.size(34.dp).clickable { onToggle(r.index) }" in body
            and "SelectBox(" in body
            and body.count("IconButton(") >= 2)


def grid_fixed_columns(hub: str) -> bool:
    """R164 · 一排固定 10 列：网格整块居中、行内从第 1 列往右排、末行靠左。

    R158 的「每行各自居中」会让不满的行像文字一样飘在中间，已按真机反馈纠正。
    """
    body = _between(hub, "private fun ChapterSegGrid(", "// 代码绘制的三点操作图标")
    if not body:
        body = _between(hub, "private fun ChapterSegGrid(", "private fun MoreDots")
    # 只看容器头部：每个小格内部也有 Center，拿整段判会被误满足。
    head = body.split("val gap = 4.dp")[0] if "val gap = 4.dp" in body else body
    return ("BoxWithConstraints(" in head
            and "contentAlignment = Alignment.Center" in head
            and "repeat(SEG_GRID_COLUMNS)" in body
            and "Spacer(Modifier.size(cell))" in body
            and "wrapContentWidth" not in body)


def checks(hub: str, main: str = "") -> list:
    """返回错误列表（空即通过）。main 为空时跳过 MainActivity 侧判据。"""
    e = []
    for s in HUB_POS:
        if s not in hub:
            e.append("目录页能力缺失: " + s[:64])
    for s in HUB_BAN:
        if s in hub:
            e.append("三列形态回潮: " + s[:52])
    if not chapter_row_ok(hub):
        e.append("章行勾选框不是「长按才显示」/ 常态三枚图标不齐")
    if not grid_fixed_columns(hub):
        e.append("小片不是「固定 10 列 · 整块居中 · 行内左对齐」网格")
    if main:
        for s in MAIN_POS:
            if s not in main:
                e.append("MainActivity 入口缺失: " + s[:56])
        for s in MAIN_BAN:
            if s in main:
                e.append("MainActivity 列分流回潮: " + s[:48])
    return e


def injections():
    """供 r163 与各历史脚本复用的反向注入：[(名字, 改哪个文件, mutate)]。"""
    return [
        ("三列分页回潮", "hub",
         lambda t: t.replace("internal fun ChapterHubScreen(",
                             "private val HubTitles = listOf(\"章节\", \"合成\", \"可播\")\n"
                             "internal fun ChapterHubScreen(")),
        ("章行左侧勾被摘", "hub",
         lambda t: t.replace("Box(Modifier.size(34.dp).clickable { onToggle(r.index) }",
                             "")),
        ("点章不再进阅读", "hub",
         lambda t: t.replace("onRead = { openReader(r.index) }",
                             "onTap = { }")),
        ("长按菜单锚被摘", "hub",
         lambda t: t.replace("onLong = { menuChapter = r.index }",
                             "onLong = { }")),
        ("听本章入口消失", "hub",
         lambda t: t.replace("IconButton(onClick = onListen", "IconButton(onClick = {} //")),
        ("章级另存 WAV 丢失", "hub",
         lambda t: t.replace('saveWav.launch("chapter.wav")', '// lost')),
        ("章级转 M4A 丢失", "hub",
         lambda t: t.replace('saveM4a.launch("chapter.m4a")', '// lost')),
        ("章级拼整章丢失", "hub",
         lambda t: t.replace("AudioOps.mergeChapter(c, book.id, book.title, i, segs)",
                             "AudioOps.deleteAll(emptyList())")),
        ("格子不再整块居中", "hub",
         lambda t: t.replace("contentAlignment = Alignment.Center",
                             "contentAlignment = Alignment.TopStart")),
        ("格子退回全量组合", "hub",
         lambda t: t.replace("chapterSegs.chunked(SEG_GRID_COLUMNS).forEach { row ->",
                             "items(listOf(chapterSegs),")),
        ("逐行居中回潮", "hub",
         lambda t: t.replace("repeat(SEG_GRID_COLUMNS)", "row.forEachIndexed { _, _ ->")),
        ("列数退回 8", "hub",
         lambda t: t.replace("internal const val SEG_GRID_COLUMNS = 10",
                             "internal const val SEG_GRID_COLUMNS = 8")),
        ("深色序号取色丢失", "hub",
         lambda t: t.replace("color = segNumberColor(playing, playable, multi, isChecked,",
                             "color = HubSub)")),
        ("章名退回浅灰看不清", "hub",
         lambda t: t.replace("color = palette.text,",
                             "color = if (r.audio != null || (p ?: 0f) > 0f) Color.Unspecified else HubGrey,")),
        ("章名又按主题分灰度", "hub",
         lambda t: t.replace("color = palette.text,",
                             "color = if (appThemeDark) palette.text else HubGrey,")),
        ("删段确认丢失", "hub",
         lambda t: t.replace("confirmSegDel?.let", "if (false) null?.let")),
        ("整本进度条丢失", "hub",
         lambda t: t.replace("LinearProgressIndicator(progress = { bookPct }",
                             "Spacer(Modifier.height(6.dp)) //")),
        ("入口又按列分流", "main",
         lambda t: t.replace("fun openBook(id: String?)",
                             "fun openBookCol(id: String?, col: Int)")),
    ]
