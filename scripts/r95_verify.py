# -*- coding: utf-8 -*-
"""R95 · 落盘预检 + 阅读位置精度：正向 / 旧形态零残留 / 守恒 / 反向注入。

沙盒无 Android SDK，静态绿 ≠ 编译过；真编译由 CI 负责。
"""
import ui_sources
import core_sources
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "reading": ROOT / "app/src/main/java/com/tingben/app/core/ReadingState.kt",
    "hub": ROOT / "app/src/main/java/com/tingben/app/ChapterHub.kt",
    "pipe": core_sources.source(ROOT),
    "main": ui_sources.source(ROOT),
    "chrome": ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "parity": ROOT / "scripts/r95_space_parity.py",
}

POS = {
    "reading": [
        'data class Pos(val chapter: Int, val para: Int = 0, val offset: Int = 0)',
        'is Number -> v.toInt().takeIf { it >= 0 }?.let { Pos(it) }',
        'is JSONObject -> v.optInt("chapter", -1)',
        'fun last(c: Context, bookId: String): Int? = pos(c, bookId)?.chapter',
        'fun save(c: Context, bookId: String, chapter: Int, para: Int? = null, offset: Int? = null)',
        'val same = old?.chapter == chapter',
        'reading_state.json',
    ],
    "hub": [
        'rememberLazyListState(',
        'initialFirstVisibleItemIndex = saved?.para ?: 0',
        'initialFirstVisibleItemScrollOffset = saved?.offset ?: 0',
        'snapshotFlow {',
        'distinctUntilChanged()',
        'ReadingState.save(c, book.id, i, p, off)',
        'LazyColumn(state = listState,',
        'ReadingState.save(c, book.id, i)',
        'ReadingState.last(c, book.id)',
    ],
    "pipe": [
        'val pending = (0 until toSpeak.size).count',
        'val segBytes = if (sample.isNotEmpty()) sample.average().toLong()',
        '24_000L * 2L * 60L',
        'pending * segBytes * 3L / 2L + mergeBytes + 16L * 1024 * 1024',
        'minOf(work.usableSpace, AudioLibrary.dir(c).usableSpace)',
        'if (needBytes > freeBytes)',
        'STORAGE_SHORT',
        'val finalBytes = wavs.sumOf { it.length() } + 16L * 1024 * 1024',
        'if (finalBytes > outFile.parentFile!!.usableSpace)',
        'STORAGE_SHORT_MERGE',
        '存储空间不够',
        '清出空间后在任务里点「继续制作」',
        '音频接不起来',
        'WavMerger.merge(wavs, outFile, config.gapMs)',
        'for ((i, a) in toSpeak.withIndex())',
    ],
    "ci": ['scripts/r95_verify.py', 'scripts/r95_space_parity.py'],
    "parity": ['def estimate(', 'def merge_need(', 'SEG_FALLBACK = 24_000 * 2 * 60'],
}

BAN = {
    "reading": ['optInt(bookId, -1)'],
    "hub": ['LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp)) {'],
    "pipe": [],
}

KEEP = {
    "chrome": ['internal fun AppDrawerSheet(', 'private fun GearIcon('],
    "main": ['SETTINGS, MODELS, CHAPTERS }', 'commitAll()', 'ttsSynthesize('],
    "pipe": ['AudioLibrary.register(c, book.id, book.title', 'TaskResume.save(c, done)',
             'ProduceService.update'],
}

OLD_WORDS = ['系统提示词', '数据标注', '语言模型', '导出日志', '标注进度']

def check(f):
    e = []
    for k, needles in POS.items():
        for s in needles:
            if s not in f[k]:
                e.append('[%s] 正向缺: %s' % (k, s))
    for k, needles in BAN.items():
        for s in needles:
            if s in f[k]:
                e.append('[%s] 旧形态复现: %s' % (k, s))
    for k, needles in KEEP.items():
        for s in needles:
            if s not in f[k]:
                e.append('[%s] 守恒失效: %s' % (k, s))
    for s in OLD_WORDS:
        for k in ('reading', 'hub', 'pipe'):
            if s in f[k]:
                e.append('[%s] 旧说法残留: %s' % (k, s))
    p = f['pipe']
    if p.find('if (needBytes > freeBytes)') > p.find('for ((i, a) in toSpeak.withIndex())'):
        e.append('预检写在合成循环之后，等于没拦住')
    if p.find('STORAGE_SHORT_MERGE') > p.find('val ok = WavMerger.merge'):
        e.append('合并预检写在 merge 之后，等于没拦住')
    return e

def load():
    return {k: p.read_text(encoding='utf-8') for k, p in FILES.items()}

def run():
    e = check(load())
    if e:
        print('FAIL')
        for x in e:
            print(' -', x)
        return 1
    print('OK · R95 正向 %d / 零残留 %d / 守恒 %d / 顺序 %d 全过' %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values()),
           sum(len(v) for v in KEEP.values()), 2))
    return 0

if __name__ == '__main__':
    if '--selftest' in sys.argv:
        f = load()
        cases = [
            ('删旧数据兼容读', 'reading', lambda t: t.replace('is Number ->', '// x')),
            ('只存章不存段', 'reading', lambda t: t.replace('para: Int? = null, offset: Int? = null', 'x: Int = 0')),
            ('换章不清零', 'reading', lambda t: t.replace('val same = old?.chapter == chapter', 'val same = true')),
            ('看书页不恢复', 'hub', lambda t: t.replace('rememberLazyListState(', 'remember { } //')),
            ('看书页不落盘', 'hub', lambda t: t.replace('ReadingState.save(c, book.id, i, p, off)', '// x')),
            ('退回无 state 列表', 'hub', lambda t: t.replace('LazyColumn(state = listState,', 'LazyColumn(')),
            ('删合成前预检', 'pipe', lambda t: t.replace('if (needBytes > freeBytes)', 'if (false)')),
            ('删合并前预检', 'pipe', lambda t: t.replace('if (finalBytes > outFile.parentFile!!.usableSpace)', 'if (false)')),
            ('空间提示塌回笼统', 'pipe', lambda t: t.replace('存储空间不够', '没能生成音频')),
            ('CI 摘验证', 'ci', lambda t: t.replace('scripts/r95_verify.py', 'x.py')),
            ('CI 摘对拍', 'ci', lambda t: t.replace('scripts/r95_space_parity.py', 'x.py')),
            ('守恒破坏·壳层', 'chrome', lambda t: t.replace('private fun GearIcon(', 'private fun BrokenGear(')),
        ]
        bad = 0
        for name, key, mut in cases:
            g = dict(f)
            g[key] = mut(f[key])
            if not check(g):
                print('  反向注入未被拦下:', name)
                bad += 1
        print('SELFTEST', len(cases) - bad, '/', len(cases))
        sys.exit(1 if bad else 0)
    sys.exit(run())
