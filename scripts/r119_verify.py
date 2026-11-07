# -*- coding: utf-8 -*-
"""R119 · 音频页小格子 / 任务三层理顺 / 章内进度 / 停滞任务续跑入口。

正向：结构真值每层有落点；零残留：旧形态不回潮；反向注入把关键片段删掉必须被抓到。
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
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = [
    ("main", '"audio_seg_grid_$ck"'),
    ("main", 'ch.segments.chunked(8).forEach'),
    ("main", 'Text("${s.seq}", fontSize = 12.sp'),
    ("main", 'Modifier.size(34.dp)'),
    ("main", 'onLongPress = { target = segMenu(s) }'),
    ("main", 'Screen.TASK_DETAIL -> ChromeSpec("章节", TAB_TASKS)'),
    ("main", 'base.copy(title = "本章进度")'),
    ("main", 'onProgress = { taskBookId = bookId; nav.push(Screen.TASKS) }'),
    ("main", 'taskChapter = t?.let { TaskResume.currentChapter(ctx, it) }'),
    ("main", 'onPickChapter: (Int) -> Unit'),
    ("main", 'clickable(enabled = started) { onChapter(ch.index) }'),
    ("main", 'val started = done || livePct != null ||'),
    ("main", '"去选这一章 ›"'),
    ("main", '"还没做"'),
    ("main", '"已做好"'),
    ("main", '"正在制作 ${(livePct * 100).toInt()}%"'),
    ("main", 'Box(Modifier.size(7.dp).background(tone, RoundedCornerShape(50)))'),
    ("main", 'item(key = "detail_head")'),
    ("main", '"没做完 ${unfinished.size} 本 · 点「继续制作」接着跑"'),
    ("main", 'Button(onClick = { onResume(t) }, enabled = !otherBusy,'),
    ("main", 'val otherBusy = st.running && live?.bookId != t.bookId'),
    ("main", 'val unfinished = tasks.filter { it.phase != "done" }'),
    ("main", 'taskChapter = TaskResume.currentChapter(ctx, t)'),
    ("main", 'nav.push(if (taskChapter != null) Screen.PROGRESS else Screen.TASK_DETAIL)'),
    ("main", 'AudioTree.load(c).firstOrNull { it.bookId == bookId }'),
    ("main", 'SegRow(s.seq - 1, s.seq - 1, 0, null, s.file,'),
    ("main", 'if (chapter != null && chapterRows != null) chapterRows else'),
    ("main", 'if (chapter == null) {'),
    ("main", 'Text(treeChapter?.title ?: "第 ${chapter + 1} 章"'),
    ("main", 'Text("看章节 ›", fontSize = 12.sp'),
    ("resume", 'fun currentChapter(c: Context, t: TaskInfo): Int?'),
    ("resume", 'if (old != null && out.none { it.bookId == old.bookId }) out.add(old)'),
    ("hub", 'Text("任务 ›", fontSize = 12.sp)'),
    ("ci", 'r119_verify.py'),
]

ZERO = [
    ("main", '"任务详情"'),
    ("main", '"进度 ›"'),
    ("main", 'item(key = "detail_back")'),
    ("main", '"audio_seg_${s.key}"'),
    ("hub", '"进度页 ›"'),
]

KEEP = [
    ("main", 'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }'),
    ("main", 'val steps = listOf("拆章", "生成声音", "整理音频", "完成")'),
    ("main", 'Text("分段看台 · ${segs.size} 段"'),
    ("main", '"▶ 整章"'),
    ("main", 'AudioTree.load(c)'),
    ("main", 'Text("看实时进度 ›", fontSize = 12.sp)'),
    ("main", 'TaskResume.list(c)'),
    ("pipe", '直接朗读'),
]


def check(f):
    e = []
    for k, s in POS:
        if s not in f[k]:
            e.append('缺正向 %s: %s' % (k, s[:70]))
    for k, s in ZERO:
        if s in f[k]:
            e.append('旧形态残留 %s: %s' % (k, s[:50]))
    for k, s in KEEP:
        if s not in f[k]:
            e.append('守恒丢失 %s: %s' % (k, s[:50]))
    m = f["main"]
    a = m.index('private fun AudioScreen')
    b = m.index('private fun GroupLabel(', a)
    body = m[a:b]
    if body.count('item(key = "audio_seg_') != 1:
        e.append('音频页小块层不是单个网格 item')
    if 'chunked(8)' not in body or 'forEachIndexed' in body:
        e.append('音频页小块层不是小格子网格')
    d = m.index('private fun TaskDetailScreen(')
    dend = m.index('private fun TaskCenterScreen(', d)
    detail = m[d:dend]
    if 'Text(d.title' in detail:
        e.append('章节列表顶上又出现了书名大卡')
    if detail.count('clickable(enabled = started)') != 1:
        e.append('未制作的章没有拦住点击')
    return e


def txt(p):
    return p.read_text(encoding='utf-8', errors='replace')


def main():
    f = {k: txt(p) for k, p in FILES.items()}
    errs = check(f)
    if '--selftest' in sys.argv:
        injections = [
            ('小格子网格被删', lambda t: dict(t, main=t['main'].replace(
                '"audio_seg_grid_$ck"', '"audio_seg_off"'))),
            ('章状态色点被删', lambda t: dict(t, main=t['main'].replace(
                'Box(Modifier.size(7.dp).background(tone, RoundedCornerShape(50)))', 'Box(Modifier)'))),
            ('未做不可点被删', lambda t: dict(t, main=t['main'].replace(
                'clickable(enabled = started) { onChapter(ch.index) }',
                'clickable { onChapter(ch.index) }'))),
            ('列表续跑按钮被删', lambda t: dict(t, main=t['main'].replace(
                'Button(onClick = { onResume(t) }, enabled = !otherBusy,',
                'Button(onClick = {}, enabled = false,'))),
            ('当前章推断被删', lambda t: dict(t, resume=t['resume'].replace(
                'fun currentChapter(c: Context, t: TaskInfo): Int?',
                'fun currentChapterX(c: Context, t: TaskInfo): Int?'))),
            ('旧单条合并被删', lambda t: dict(t, resume=t['resume'].replace(
                'if (old != null && out.none { it.bookId == old.bookId }) out.add(old)',
                'if (out.isEmpty()) legacy(c)?.let { out.add(it) }'))),
            ('章内树过滤被删', lambda t: dict(t, main=t['main'].replace(
                'AudioTree.load(c).firstOrNull { it.bookId == bookId }',
                'null'))),
            ('章内四步被放回', lambda t: dict(t, main=t['main'].replace(
                'if (chapter == null) {', 'if (true) {'))),
            ('任务详情标题被放回', lambda t: dict(t, main=t['main'].replace(
                'Screen.TASK_DETAIL -> ChromeSpec("章节", TAB_TASKS)',
                'Screen.TASK_DETAIL -> ChromeSpec("任务详情", TAB_TASKS)'))),
            ('目录页旧进度入口被放回', lambda t: dict(t, hub=t['hub'].replace(
                'Text("任务 ›", fontSize = 12.sp)', 'Text("进度页 ›", fontSize = 12.sp)'))),
            ('CI 漏挂验证', lambda t: dict(t, ci=t['ci'].replace(
                'r119_verify.py', 'r119_missing.py'))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(f)):
                bad += 1
                errs.append('反向注入漏放: ' + name)
        print('反向注入 %d/%d 全拦下' % (len(injections) - bad, len(injections)))
    if errs:
        print('R119 FAIL', len(errs))
        for x in errs:
            print(' -', x)
        return 1
    print('R119 音频小格子 / 任务三层 / 章内进度 / 续跑入口 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条：PASS'
          % (len(POS), len(ZERO), len(KEEP)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
