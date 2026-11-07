# -*- coding: utf-8 -*-
"""R120 · 拼成整章后删小块这条链路真正闭环。

正向：章成品进树 / 整本成品不冒充章成品 / 拼接后自动清理 / 续跑不重算 /
      任务详情章状态真值 / 进度页不显示空看台 / 制作页进度入口不再错位。
反向注入：把任一关键片段退回旧形态必须被抓到。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "tree": APP / "core" / "AudioTree.kt",
    "ops": APP / "core" / "AudioOps.kt",
    "pipe": APP / "core" / "Pipeline.kt",
    "resume": APP / "core" / "TaskResume.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = [
    ("tree", 'it.file.name.startsWith("chapter_")'),
    ("tree", 'it.chapters.size == 1'),
    ("tree", 'fun chapterOnlyItems(c: Context)'),
    ("tree", 'chapterOnly.forEach { a ->'),
    ("tree", 'scCache.getOrPut(dir.absolutePath)'),
    ("tree", 'it.isFile && it.name == "chapters.json"'),
    ("tree", 'fun readDone(dir: File): Map<Int, File>'),
    ("tree", 'fun markDone(dir: File, chapter: Int, file: File): Boolean'),
    ("tree", 'fun clearDone(c: Context, bookId: String, chapter: Int)'),
    ("tree", 'chapter_done.json'),
    ("tree", 'chapterOnly.filter { it.bookId == bookId && ch in it.chapters }'),
    ("tree", 'bk.chapters.mapNotNull { it.merged?.lastModified() }'),
    ("ops", 'val kept: Int = 0'),
    ("ops", 'parts.firstOrNull()?.parentFile?.let { AudioTree.markDone(it, chapter, dst) }'),
    ("ops", 'val kept = deleteAll(parts)'),
    ("ops", 'fun deleteMerged(c: Context, file: File): Boolean'),
    ("pipe", 'AudioTree.readDone(work)'),
    ("pipe", 'val usedDoneChapters = mutableSetOf<Int>()'),
    ("pipe", 'val covered = doneWav != null'),
    ("pipe", 'usedDoneChapters.add(ci)'),
    ("resume", 'val doneMap = AudioTree.readDone(w)'),
    ("resume", 'minOf(selected, base + covered)'),
    ("main", 'val mergeds: List<File> = emptyList()'),
    ("main", 'b.chapters.mapNotNull { it.merged?.takeIf { f -> f.isFile } }'),
    ("main", 'fun partsFor(t: AudioMenu): List<File>'),
    ("main", 'if (pq.queueId == q) {'),
    ("main", '已拼成整章，原来的小块已清理'),
    ("main", 'AudioOps.deleteMerged(c, it)'),
    ("main", 'AudioTree.clearDone(c, t.bookId, t.chapter)'),
    ("main", 'if (ch.segments.isEmpty() && ch.merged != null) "已拼成整章 · 1 个音频"'),
    ("main", 'if (chOpen && ch.segments.isNotEmpty())'),
    ("main", 'val chapterReady: Set<Int>'),
    ("main", 'val chapterStarted: Set<Int>'),
    ("main", 'val chapterMerged: Map<Int, File>'),
    ("main", 'Workbench.segChapters(c, it)'),
    ("main", 'val done = ch.index in d.chapterReady'),
    ("main", 'ch.index in d.chapterStarted || livePct != null'),
    ("main", 'playChapterAudio(c, bookId, ch.index, d.chapterMerged[ch.index])'),
    ("main", 'private fun playChapterAudio(c: Context, bookId: String?, ch: Int, merged: File? = null)'),
    ("main", 'val mergedOnly = chapter != null'),
    ("main", '这一章已拼成整章音频，小块已清理'),
    ("main", 'if (mergedOnly) 1f'),
    ("main", '"▶ 播这一章"'),
    ("main", 'onProgress = { taskBookId = bookId; taskChapter = null; nav.push(Screen.PROGRESS) }'),
    ("ci", 'r120_verify.py'),
]

ZERO = [
    ("main", '已拼成整章，原来的小块可以删掉了'),
    ("main", '已拼好的整章音频会留着'),
    ("main", 'val started = done || livePct != null ||'),
    ("tree", '.filter { it.bookId == bookId && it.chaptersKnown && chapter in it.chapters && it.file.isFile }'),
]

KEEP = [
    ("main", 'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }'),
    ("main", 'val steps = listOf("拆章", "生成声音", "整理音频", "完成")'),
    ("main", '"audio_seg_grid_$ck"'),
    ("main", '"▶ 整章"'),
    ("main", 'commitAll()'),
    ("main", 'AudioTree.load(c)'),
    ("main", 'Text("看实时进度 ›", fontSize = 12.sp)'),
    ("pipe", '直接朗读'),
    ("ops", 'fun mergeChapter('),
    ("ops", 'declickMs: Int = 30'),
]


def check(f):
    e = []
    for k, s in POS:
        if s not in f[k]: e.append('缺正向 %s: %s' % (k, s[:70]))
    for k, s in ZERO:
        if s in f[k]: e.append('旧形态残留 %s: %s' % (k, s[:60]))
    for k, s in KEEP:
        if s not in f[k]: e.append('守恒丢失 %s: %s' % (k, s[:60]))
    m = f["main"]
    a = m.index('private fun AudioScreen')
    b = m.index('private fun GroupLabel(', a)
    body = m[a:b]
    if body.count('AudioOps.deleteMerged') < 2:
        e.append('音频页没有真正删得掉整章成品')
    if 'partsFor(t)' not in body:
        e.append('导出没有走整章优先的选源')
    d = m.index('private fun TaskDetailScreen(')
    dend = m.index('private fun TaskCenterScreen(', d)
    detail = m[d:dend]
    if 'chapterReady' not in detail:
        e.append('任务详情章状态没用真实完成度')
    mf = f['tree'][f['tree'].index('fun mergedFile('):f['tree'].index('fun chapterOnlyItems(')]
    if 'it.chapters.size == 1' not in mf or 'startsWith("chapter_")' not in mf:
        e.append('mergedFile 没把整本成品排除在章成品之外')
    p = m.index('private fun ProgressScreen(')
    pend = m.index('private data class TaskDetailData(', p)
    prog = m[p:pend]
    if prog.count('mergedOnly') < 4:
        e.append('进度页没有为只有整章音频的章单独出态')
    return e


def txt(p): return p.read_text(encoding='utf-8', errors='replace')


def main():
    f = {k: txt(p) for k, p in FILES.items()}
    errs = check(f)
    if '--selftest' in sys.argv:
        inj = [
            ('章成品节点被删', lambda t: dict(t, tree=t['tree'].replace(
                'chapterOnly.forEach { a ->', 'listOf<AudioItem>().forEach { a ->'))),
            ('整本成品又被当章成品', lambda t: dict(t, tree=t['tree'].replace(
                'it.chapters.size == 1 &&\n                chapter in it.chapters',
                'chapter in it.chapters'))),
            ('拼接后不清理小块', lambda t: dict(t, ops=t['ops'].replace(
                'val kept = deleteAll(parts)', 'val kept = 0'))),
            ('章完成表不写', lambda t: dict(t, ops=t['ops'].replace(
                'AudioTree.markDone(it, chapter, dst)', 'Unit'))),
            ('续跑补位被删', lambda t: dict(t, pipe=t['pipe'].replace(
                'usedDoneChapters.add(ci)', 'false'))),
            ('进度倒退没修', lambda t: dict(t, resume=t['resume'].replace(
                'minOf(selected, base + covered)', 'base'))),
            ('章状态真值被删', lambda t: dict(t, main=t['main'].replace(
                'val done = ch.index in d.chapterReady', 'val done = false'))),
            ('只有整章音频的章又画空看台', lambda t: dict(t, main=t['main'].replace(
                'if (mergedOnly) 1f', 'if (false) 1f'))),
            ('整章成品删不掉', lambda t: dict(t, main=t['main'].replace(
                't.mergeds.forEach { if (!AudioOps.deleteMerged(c, it)) failed++ }', 'Unit'))),
            ('制作页进度入口又回任务详情', lambda t: dict(t, main=t['main'].replace(
                'onProgress = { taskBookId = bookId; taskChapter = null; nav.push(Screen.PROGRESS) }',
                'onProgress = { taskBookId = bookId; taskChapter = null; nav.push(Screen.TASK_DETAIL) }'))),
            ('CI 漏挂验证', lambda t: dict(t, ci=t['ci'].replace(
                'r120_verify.py', 'r120_missing.py'))),
        ]
        bad = 0
        for name, mutate in inj:
            if not check(mutate(f)):
                bad += 1; errs.append('反向注入漏放: ' + name)
        print('反向注入 %d/%d 全拦下' % (len(inj) - bad, len(inj)))
    if errs:
        print('R120 FAIL', len(errs))
        for x in errs: print(' -', x)
        return 1
    print('R120 拼章闭环 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条：PASS'
          % (len(POS), len(ZERO), len(KEEP)))
    return 0


if __name__ == '__main__': sys.exit(main())
