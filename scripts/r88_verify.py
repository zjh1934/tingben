# -*- coding: utf-8 -*-
"""R88 · 任务中心页收敛验证：空态下一步 / 跳转按钮降级 / 去重 / 四项操作守恒 / 反向注入。

守四件事：
1. 空态不只有一句「还没有任务」+ 整宽按钮，补了下一步说明；
2. 进行中 / 已完成两处跨页跳转从 46dp 整宽按钮降成行内小文字；
3. 删掉「最近完成」第二张卡，试听入口全页只留一个；
4. 续跑 / 从头重做 / 放弃 / 清失效任务四项操作一个不丢，R79 轮询门控不动。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
GRADLE = ROOT / "app/build.gradle.kts"
CI = ROOT / ".github/workflows/ci.yml"

POS = [
    '在书架选一本书，勾选章节后点「合成音频」，这里就会出现进度。',
    'Text("去书架，选一本书")',
    'Text("看实时进度 ›", fontSize = 12.sp)',
    'Text("去「我的音频」试听 ›", fontSize = 12.sp)',
    'Text("继续制作（接着已做好的块跑）", fontSize = 15.sp)',
    'Text("从头重做", fontSize = 12.sp)',
    'Text("放弃任务", fontSize = 12.sp, color = Warn)',
    'Text("清除这条失效任务", fontSize = 12.sp)',
    'if (!st.running) { loadTick++; return@LaunchedEffect }',
    'delay(2000); loadTick++',
    'TaskResume.realProgress(c, it)',
]

BAN = [
    'Text("最近完成"',
    'Text("去试听 ›")',
    'Text("查看实时进度")',
    'Text("去「我的音频」试听")',
    'Text("去书架")',
    'Button(onClick = onProgress, Modifier.fillMaxWidth().height(46.dp))',
    'Button(onClick = onAudio, Modifier.fillMaxWidth().height(46.dp))',
    'Modifier.fillMaxWidth()) { Text("清除这条失效任务") }',
]

KEEP = [
    'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, TASK_DETAIL, AUDIO, SETTINGS, MODELS, MIXER, CHAPTERS }',
    'Screen.TASKS -> TaskCenterScreen(ctx, refresh,',
    'fun commitAll()',
    'Producer.start(ctx, b, t.only, true, t.think, t.config)',
    'BookStore.list(c).find { x -> x.id == t.bookId }',
]

def section(text):
    i = text.find('private data class TaskDetailData')
    j = text.find('private fun fmtTime(', i)
    return text[i:j] if i >= 0 and j > i else ''

def check(files):
    errs = []
    m, ch, gr, ci = (files[k] for k in ("main", "chrome", "gradle", "ci"))
    sec = section(m)
    if not sec:
        return ['找不到 TaskCenterScreen 函数体']
    for s in POS:
        if s not in sec:
            errs.append('缺正向: ' + s[:56])
    for s in BAN:
        if s in sec:
            errs.append('旧形态残留: ' + s[:56])
    for s in KEEP:
        if s not in m + ch:
            errs.append('守恒丢失: ' + s[:56])
    if sec.count('Producer.clearProgress(c, t.bookId, t.only)') < 3:
        errs.append('四项操作里的清除进度调用被删')
    if 't.direct, t.think' in sec:
        errs.append('分桶旧三态参数回潮')
    if 'r88_verify.py' not in ci:
        errs.append('CI 未挂 r88_verify')
    if '1.1.0+' not in gr:
        errs.append('版本口径被改')
    return errs

def txt(p):
    return p.read_text(encoding='utf-8', errors='replace')

def main():
    files = {"main": txt(MAIN), "chrome": txt(CHROME),
             "gradle": txt(GRADLE), "ci": txt(CI)}
    errs = check(files)
    if '--selftest' in sys.argv:
        injections = [
            ('最近完成卡回潮',
             lambda t: dict(t, main=t['main'].replace('private fun fmtTime(',
                 'Text("最近完成")\n\nprivate fun fmtTime('))),
            ('整宽跳转按钮回潮',
             lambda t: dict(t, main=t['main'].replace('TextButton(onClick = onProgress)',
                 'Button(onClick = onProgress, Modifier.fillMaxWidth().height(46.dp))'))),
            ('旧空态按钮回潮',
             lambda t: dict(t, main=t['main'].replace('Text("去书架，选一本书")', 'Text("去书架")'))),
            ('看进度文案回潮',
             lambda t: dict(t, main=t['main'].replace('Text("看实时进度 ›", fontSize = 12.sp)',
                 'Text("查看实时进度")'))),
            ('试听整宽按钮文案回潮',
             lambda t: dict(t, main=t['main'].replace('Text("去「我的音频」试听 ›", fontSize = 12.sp)',
                 'Text("去「我的音频」试听")'))),
            ('从头重做被删',
             lambda t: dict(t, main=t['main'].replace('Text("从头重做", fontSize = 12.sp)', 'Text("重做")'))),
            ('放弃任务被删',
             lambda t: dict(t, main=t['main'].replace('Text("放弃任务", fontSize = 12.sp, color = Warn)',
                 'Text("算了")'))),
            ('清失效任务被删',
             lambda t: dict(t, main=t['main'].replace('Text("清除这条失效任务", fontSize = 12.sp)',
                 'Text("清除")'))),
            ('R79 轮询门控被删',
             lambda t: dict(t, main=t['main'].replace(
                 'if (!st.running) { loadTick++; return@LaunchedEffect }', ''))),
            ('继续制作被删',
             lambda t: dict(t, main=t['main'].replace('Text("继续制作（接着已做好的块跑）", fontSize = 15.sp)',
                 'Text("继续")'))),
            ('CI 漏挂验证',
             lambda t: dict(t, ci=t['ci'].replace('r88_verify.py', 'r88_missing.py'))),
            ('版本口径被改',
             lambda t: dict(t, gradle=t['gradle'].replace('"1.1.0+"', '"1.0.0+"'))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(files)):
                bad += 1
                errs.append('反向注入漏放: ' + name)
        print('反向注入 %d/%d 全拦下' % (len(injections) - bad, len(injections)))
    if errs:
        print('R88 FAIL', len(errs))
        [print(' -', e) for e in errs]
        return 1
    print('R88 任务中心页收敛 / 去重 / 四项操作守恒：PASS')
    return 0

if __name__ == '__main__':
    sys.exit(main())
