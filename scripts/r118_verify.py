# -*- coding: utf-8 -*-
"""R118 · 我的音频三层结构：书 → 章 → 小块。

正向：结构真值每层有落点；反向注入把关键片段删掉必须被抓到。
静态绿只证明代码路径成立，不代替真机体验。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "pipe": APP / "core" / "Pipeline.kt",
    "tree": APP / "core" / "AudioTree.kt",
    "ops": APP / "core" / "AudioOps.kt",
    "audio": APP / "core" / "AudioLibrary.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "report": ROOT / "R118_落地报告.md",
}

POS = [
    ("tree", 'data class SegAudio('),
    ("tree", 'data class ChapterAudio('),
    ("tree", 'data class BookAudio('),
    ("tree", 'fun load(c: Context): List<BookAudio>'),
    ("tree", '"seg_chapters.json"'),
    ("tree", '"chapters.json"'),
    ("tree", 'fun mergedFile(c: Context, bookId: String, chapter: Int): File?'),
    ("tree", 'fun moveSegment(c: Context, path: String, bookId: String, chapter: Int): Boolean'),
    ("tree", 'fun chapterTracks(c: Context, ch: ChapterAudio): List<Track>'),
    ("tree", 'AudioLibrary.list(c)'),
    ("ops", 'fun pcmOf(f: File): Pair<ByteArray, Int>?'),
    ("ops", 'fun mergedTemp(c: Context, parts: List<File>): Result'),
    ("ops", 'fun mergeChapter(c: Context, bookId: String, bookTitle: String, chapter: Int,'),
    ("ops", 'fun toM4a(c: Context, src: File, out: File): Result'),
    ("ops", 'fun deleteAll(files: List<File>): Int'),
    ("ops", 'MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)'),
    ("ops", 'WavMerger.merge(parts, dst, gapMs, declickMs)'),
    ("pipe", '"chapters.json"'),
    ("pipe", 'declickMs: Int = 30'),
    ("pipe", 'private fun declick(pcm: ByteArray, ms: Int): ByteArray'),
    ("pipe", 'chunks = parts.mapNotNull { p -> pcmOf(p)?.let { declick(it, declickMs) } }'),
    ("pipe", 'AudioLibrary.register(c, book.id, book.title, only, direct, outFile, chapters.size)'),
    ("main", 'import androidx.compose.foundation.gestures.detectTapGestures'),
    ("main", 'private data class AudioMenu('),
    ("main", 'AudioTree.load(c)'),
    ("main", '"audio_book_${b.bookId}"'),
    ("main", '"audio_chapter_$ck"'),
    ("main", '"audio_seg_grid_'),
    ("main", 'fun playSeg(s: SegAudio)'),
    ("main", 'fun playChapter(ch: ChapterAudio)'),
    ("main", 'detectTapGestures('),
    ("main", '"▶ 整章"'),
    ("main", '共 ${tree.size} 本'),
    ("main", '"重命名"'),
    ("main", '"复制 / 另存为 WAV"'),
    ("main", '"转格式（WAV → M4A）"'),
    ("main", '"移动到别的章"'),
    ("main", '"把这一章拼成一个音频"'),
    ("main", 'AudioOps.mergeChapter(c, t.bookId'),
    ("main", 'AudioOps.deleteAll(t.files)'),
    ("main", 'AudioOps.toM4a(c, src, tmp)'),
    ("main", 'AudioTree.setName(c, key, renameText)'),
    ("main", 'AudioTree.moveSegment(c, s.key, s.bookId, n - 1)'),
    ("ci", 'r118_verify.py'),
    ("report", '30ms'),
    ("report", '不做真 crossfade'),
    ("report", 'gapless'),
]

ZERO = [
    ("main", 'audio_empty'),
    ("main", '去书架 ›'),
    ("main", '去看模型 ›'),
    ("main", 'controlsOpen'),
    ("main", 'items.groupBy { it.bookId'),
    ("main", '删除整本'),
    ("main", '按顺序播放'),
]

KEEP = [
    ("main", 'NAV_MIXER'),
    ("main", 'fun commitAll()'),
    ("main", 'Screen.TASK_DETAIL'),
    ("main", 'navigationBarsPadding()'),
    ("audio", 'fun register(c: Context, bookId: String'),
    ("audio", 'fun remove(c: Context, id: String): Boolean'),
    ("audio", 'AtomicJson.write(index(c), a.toString())'),
    ("pipe", '直接朗读'),
]


def check(f):
    e = []
    for k, s in POS:
        if s not in f[k]:
            e.append('缺正向 %s: %s' % (k, s[:70]))
    m = f["main"]
    a = m.index('private fun AudioScreen')
    b = m.index('private fun GroupLabel(', a)
    body = m[a:b]
    for k, s in ZERO:
        if s in (body if k == "main" else f[k]):
            e.append('旧形态残留 %s: %s' % (k, s[:50]))
    for k, s in KEEP:
        if s not in f[k]:
            e.append('守恒丢失 %s: %s' % (k, s[:50]))
    m = f["main"]
    if 'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }' not in m:
        e.append('Screen 不是 10 值')
    if m.count('detectTapGestures(') < 3:
        e.append('三层长按入口不全')
    if 'private fun declick' not in f["pipe"]:
        e.append('接缝防爆音丢失')
    return e


def txt(p):
    return p.read_text(encoding='utf-8', errors='replace')


def main():
    f = {k: txt(p) for k, p in FILES.items()}
    errs = check(f)
    if '--selftest' in sys.argv:
        injections = [
            ('音频树被删', lambda t: dict(t, tree=t['tree'].replace(
                'fun load(c: Context): List<BookAudio>', 'fun loadX(c: Context): List<BookAudio>'))),
            ('章标题落盘被删', lambda t: dict(t, pipe=t['pipe'].replace(
                '"chapters.json"', '"chapters.off"'))),
            ('接缝防爆音被删', lambda t: dict(t, pipe=t['pipe'].replace(
                'declick(it, declickMs)', 'it'))),
            ('三层小块行被删', lambda t: dict(t, main=t['main'].replace(
                '"audio_seg_grid_', '"audio_seg_off"'))),
            ('章播放键被删', lambda t: dict(t, main=t['main'].replace(
                '"▶ 整章"', '"整章"'))),
            ('拼接成章被删', lambda t: dict(t, main=t['main'].replace(
                'AudioOps.mergeChapter(c, t.bookId', 'AudioOps.mergedTemp(c'))),
            ('删除按磁盘结果被删', lambda t: dict(t, main=t['main'].replace(
                'AudioOps.deleteAll(t.files)', '0'))),
            ('空态旧卡被放回', lambda t: dict(t, main=t['main'].replace(
                'item(key = "audio_tree_summary")', 'item(key = "audio_empty")'))),
            ('CI 漏挂验证', lambda t: dict(t, ci=t['ci'].replace(
                'r118_verify.py', 'r118_missing.py'))),
            ('设计依据被删', lambda t: dict(t, report=t['report'].replace(
                '不做真 crossfade', ''))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(f)):
                bad += 1
                errs.append('反向注入漏放: ' + name)
        print('反向注入 %d/%d 全拦下' % (len(injections) - bad, len(injections)))
    if errs:
        print('R118 FAIL', len(errs))
        for x in errs:
            print(' -', x)
        return 1
    print('R118 我的音频三层结构 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条：PASS'
          % (len(POS), len(ZERO), len(KEEP)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
