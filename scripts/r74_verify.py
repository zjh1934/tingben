# -*- coding: utf-8 -*-
"""R74 · P3 逐章进度 / 阅读位置 —— 静态自检 + 反向注入。
沙盒无 Android SDK，静态绿 ≠ 编译过；真编译由 CI 负责。
"""
import core_sources
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
PIPE = core_sources.source(SRC)
HUB = SRC / "ChapterHub.kt"
READING = SRC / "core/ReadingState.kt"

POS = [
    ("Progress 带逐章字段", PIPE, "val chapterProgress: Map<Int, Float> = emptyMap()"),
    ("emit 传递逐章快照", PIPE, "_state.value = Progress(true, stage, done, total, failed, msg, out, chapterProgress)"),
    ("段章归属表", PIPE, "val segChapter = mutableListOf<Int>()"),
    ("每块补齐归属", PIPE, "repeat(segs.size) { segChapter.add(ch.chapter) }"),
    ("每章总段数", PIPE, "val perChapterTotal = segChapter.groupingBy { it }.eachCount()"),
    ("每章已完成", PIPE, "val perChapterDone = mutableMapOf<Int, Int>()"),
    ("按段起点判成功", PIPE, "val segStart = wavs.size"),
    ("只统计落盘成功的段", PIPE, "val chapterSnapshot = if (wavs.size > segStart)"),
    ("失败段不推进", PIPE, "} else _state.value.chapterProgress"),
    ("逐章快照进 emit", PIPE, "chapterProgress = chapterSnapshot"),
    ("目录页订阅逐章", HUB, "st.chapterProgress"),
    ("逐章真值来源", HUB, "chapterProgress[ch.index] else null"),
    ("固定列网格", HUB, "chunked(SEG_GRID_COLUMNS)"),
    ("阅读位置落盘", HUB, "ReadingState.save(c, book.id, i)"),
    ("翻章也写位置", HUB, "{ next -> openReader(next) }"),
    ("整本条纯色", HUB, "modifier = Modifier.fillMaxWidth().height(22.dp),"),
    ("重新分章入口", HUB, 'resplitRequest > 0'),
    ("三列分发", HUB, "LazyColumn(Modifier.fillMaxSize(),"),
    ("停滞态集合", HUB, 'listOf("没完成", "已停止", "出错了")'),
    ("阅读位置文件", READING, "reading_state.json"),
    ("纯 UI 状态声明", READING, "fun allLast(c: Context)"),
    ("写失败静默", READING, "catch (_: Throwable)"),
]

NEG = [
    ("旧四列上界", HUB, "coerceIn(0, 3)"),
    ("整本进度假拆分", HUB, "整本进度 ${(p * 100).toInt()}%"),
    ("标注列死函数", HUB, "private fun AnnColumn("),
    ("旧界面词 已分段", HUB, "\"已分段\""),
    ("旧字段 annotated", HUB, "val annotated: Float?"),
    ("旧字段 unannotatedAudio", HUB, "val unannotatedAudio: Boolean"),
    ("直接 pane 赋值", HUB, "{ i -> pane = Pane.Reader(i) }"),
    ("旧四列注释", HUB, "平级排成四列左右滑"),
    # R253 · 目录页「上次读到 第 N 章 · 继续」细条撤掉（R230 书架已撤），不许回潮
    ("「上次读到」细条回潮", HUB, "上次读到 第 ${lastRead + 1} 章"),
    # R255 · 条内「已完成 X%」与「整体 X%」都撤了，不许回潮
    ("条内文字回潮", HUB, "已完成 ${(bookPct * 100).toInt()}%"),
]

def brackets(text, name):
    for a, b in [("(", ")"), ("{", "}"), ("[", "]")]:
        assert text.count(a) == text.count(b), f"[{name}] {a}{b} 不平衡"
    return True

def check(texts=None):
    t = texts or {p: p.read_text(encoding="utf-8") for p in (PIPE, HUB, READING)}
    for name, path, needle in POS:
        assert needle in t[path], f"[正向] 缺：{name} -> {needle}"
    for name, path, needle in NEG:
        assert needle not in t[path], f"[反向] 旧形态复现：{name} -> {needle}"
    for p in (PIPE, HUB, READING):
        brackets(t[p], p.name)
    return len(POS), len(NEG)

def selftest():
    """把每条正向断言逐个破坏，检查脚本自己拦得住。"""
    base = {p: p.read_text(encoding="utf-8") for p in (PIPE, HUB, READING)}
    caught = 0
    for name, path, needle in POS:
        broken = dict(base)
        broken[path] = base[path].replace(needle, "@@BROKEN@@")
        try:
            check(broken)
        except AssertionError:
            caught += 1
        else:
            raise AssertionError(f"[自检] 破坏后没拦下：{name}")
    for name, path, needle in NEG:
        broken = dict(base)
        broken[path] = base[path] + "\n// " + needle + "\n"
        try:
            check(broken)
        except AssertionError:
            caught += 1
        else:
            raise AssertionError(f"[自检] 注入旧形态后没拦下：{name}")
    print(f"selftest ok · 反向注入 {caught}/{len(POS) + len(NEG)} 全拦下")

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        a, b = check()
        print(f"r74_verify ok · 正向 {a} 条全在 · 反向 {b} 类旧形态零残留")
