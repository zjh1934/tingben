# -*- coding: utf-8 -*-
"""R117 · 任务/音频链路重做：一本一任务 / 章节层 / 章内进度 / 音频按书分组。

正向：结构真值每一层都有落点；反向注入把关键片段删掉必须被抓到。
静态绿只证明代码路径成立，不代替真机体验。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "resume": APP / "core" / "TaskResume.kt",
    "pipe": APP / "core" / "Pipeline.kt",
    "bench": APP / "core" / "Workbench.kt",
    "audio": APP / "core" / "AudioLibrary.kt",
    "svc": APP / "ProduceService.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = [
    ("resume", 'fun list(c: Context): List<TaskInfo>'),
    ("resume", 'fun byBook(c: Context, bookId: String): TaskInfo?'),
    ("resume", 'fun remove(c: Context, bookId: String)'),
    ("resume", '"tasks.json"'),
    ("resume", 'AtomicJson.write(listFile(c), a.toString())'),
    ("resume", 'AtomicJson.read(file(c))'),
    ("resume", 'all.removeAll { it.bookId == t.bookId }'),
    ("pipe", '"seg_chapters.json"'),
    ("pipe", 'TaskResume.remove(c, bookId)'),
    ("bench", 'fun segChapters(c: Context, t: TaskInfo?): Map<Int, Int>?'),
    ("audio", 'TaskResume.byBook(c, bookId)?.modeId'),
    ("main", 'Screen.TASK_DETAIL'),
    ("main", 'Screen.TASK_DETAIL -> ChromeSpec("任务详情", TAB_TASKS)'),
    ("main", 'TASKS, TASK_DETAIL, AUDIO'),
    ("main", 'private data class TaskDetailData('),
    ("main", 'private fun buildTaskDetail(c: Context, bookId: String?): TaskDetailData'),
    ("main", 'private fun TaskDetailScreen('),
    ("main", 'Screen.TASK_DETAIL -> TaskDetailScreen('),
    ("main", 'onChapter: (Int) -> Unit'),
    ("main", 'onOpenTask = { id -> taskBookId = id; nav.push(Screen.TASK_DETAIL) }'),
    ("main", 'onStarted = { id, sel ->'),
    ("main", 'nav.push(Screen.TASK_DETAIL)'),
    ("main", 'taskChapter = ch'),
    ("main", 'ProgressScreen(ctx, taskBookId, taskChapter,'),
    ("main", 'bookId?.let { TaskResume.byBook(c, it) } ?: TaskResume.load(c)'),
    ("main", 'Workbench.segChapters(c, seenTask)'),
    ("main", 'allSegs.filter { segChapters[it.index] == chapter }'),
    ("main", '第 ${chapter + 1} 章 · 本章 ${segs.count { it.hasAudio }}/${segs.size} 段'),
    ("main", '旧记录没记章，这里显示整次分段'),
    ("main", '共 ${tasks.size} 本 · 一本一个任务'),
    ("main", 'items.groupBy { it.bookId ?: "" }'),
    ("main", '"audio_group_$bid"'),
    ("main", '删除整本 ›'),
    ("main", '按顺序播放 ›'),
    ("main", 'AudioLibrary.playableItems(c, bid)'),
    ("main", '"open_tasks"'),
    ("main", '|tasks:'),
    ("hub", 'onStarted: (String, Set<Int>) -> Unit'),
    ("hub", 'onStarted(book.id, selected)'),
    ("svc", 'putExtra("open_tasks", true)'),
    ("ci", 'r117_verify.py'),
]

KEEP = [
    ("main", 'NAV_MIXER'),
    ("main", 'fun commitAll()'),
    ("hub", 'HubTitles = listOf("章节", "合成", "可播")'),
    ("main", 'val steps = listOf("拆章", "生成声音", "整理音频", "完成")'),
    ("main", 'navigationBarsPadding()'),
    ("hub", 'Producer.start(c, book, selected, directRead = true'),
    ("pipe", '直接朗读'),
]


def check(f):
    e = []
    for k, s in POS:
        if s not in f[k]:
            e.append('缺正向 %s: %s' % (k, s[:66]))
    for k, s in KEEP:
        if s not in f[k]:
            e.append('守恒丢失 %s: %s' % (k, s[:50]))
    m = f["main"]
    if 'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }' not in m:
        e.append('Screen 不是 10 值')
    if m.count('Screen.TASK_DETAIL') < 4:
        e.append('任务详情分发不完整')
    if 'TaskResume.list(c)' not in m:
        e.append('任务页没读多任务')
    if 'AtomicJson.write(file(c), o.toString())' not in f["resume"]:
        e.append('旧单条兼容写丢失')
    return e


def txt(p):
    return p.read_text(encoding='utf-8', errors='replace')


def main():
    f = {k: txt(p) for k, p in FILES.items()}
    errs = check(f)
    if '--selftest' in sys.argv:
        injections = [
            ('任务详情页被删', lambda t: dict(t, main=t['main'].replace(
                'Screen.TASK_DETAIL -> TaskDetailScreen', 'Screen.TASK_DETAIL -> Unit'))),
            ('多任务读被删', lambda t: dict(t, main=t['main'].replace(
                'TaskResume.list(c)', 'TaskResume.load(c)?.let { listOf(it) } ?: emptyList()'))),
            ('按书取任务被删', lambda t: dict(t, resume=t['resume'].replace(
                'fun byBook(c: Context, bookId: String): TaskInfo?', 'fun byBookX(c: Context): TaskInfo?'))),
            ('段章映射落盘被删', lambda t: dict(t, pipe=t['pipe'].replace(
                '"seg_chapters.json"', '"seg_chapters.off"'))),
            ('章内看台过滤被删', lambda t: dict(t, main=t['main'].replace(
                'allSegs.filter { segChapters[it.index] == chapter }', 'allSegs'))),
            ('音频按书分组被删', lambda t: dict(t, main=t['main'].replace(
                'items.groupBy { it.bookId ?: "" }', 'listOf("" to items)'))),
            ('合成落任务详情被删', lambda t: dict(t, main=t['main'].replace(
                'onStarted = { id, sel ->', 'onStartedX = { id, sel ->'))),
            ('通知落点被删', lambda t: dict(t, svc=t['svc'].replace(
                'putExtra("open_tasks", true)', ''))),
            ('CI 漏挂验证', lambda t: dict(t, ci=t['ci'].replace(
                'r117_verify.py', 'r117_missing.py'))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(f)):
                bad += 1
                errs.append('反向注入漏放: ' + name)
        print('反向注入 %d/%d 全拦下' % (len(injections) - bad, len(injections)))
    if errs:
        print('R117 FAIL', len(errs))
        for x in errs:
            print(' -', x)
        return 1
    print('R117 任务/音频链路重做 正向 %d 条 / 守恒 %d 条：PASS' % (len(POS), len(KEEP)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
