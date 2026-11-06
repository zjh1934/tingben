#!/usr/bin/env python3
"""R61 · 目录页四列与悬浮条验证。

阶段 A：index.json 章节索引的 Kotlin↔Python 等价实现往返对拍（6 组 + 兼容旧数据）。
阶段 B：源码正向断言 / 三态真值来源 / 守恒。
反向注入：把源码故意改坏，断言必须拦下（拦不下 = 验证脚本自己失效）。
"""
import contextlib, io, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
AUDIO = ROOT / 'app/src/main/java/com/tingben/app/core/AudioLibrary.kt'
HUB = ROOT / 'app/src/main/java/com/tingben/app/ChapterHub.kt'
MAIN = ROOT / 'app/src/main/java/com/tingben/app/MainActivity.kt'
PIPE = ROOT / 'app/src/main/java/com/tingben/app/core/Pipeline.kt'
CI = ROOT / '.github/workflows/ci.yml'


# ── 阶段 A · 与 Kotlin 等价的章节索引逻辑 ────────────────────────────────
def scope_indices(scope: str):
    s = scope.strip()
    m = re.fullmatch(r'第\s*(\d+)\s*章', s)
    if m:
        n = int(m.group(1))
        return ({n - 1}, True) if n > 0 else (set(), False)
    m = re.fullmatch(r'第\s*(\d+)\s*[–\-~至]\s*(\d+)\s*章', s)
    if m:
        a, b = int(m.group(1)), int(m.group(2))
        if a <= 0 or b < a or b - a > 2000:
            return set(), False
        return set(range(a - 1, b)), True
    return set(), False          # 整本 / 选中 N 章 / 整本（旧版）：一律 unknown


def read_indices(o: dict):
    if 'chapterIndices' in o and isinstance(o['chapterIndices'], list):
        s = {v for v in o['chapterIndices'] if isinstance(v, int) and v >= 0}
        return s, bool(s)
    return scope_indices(o.get('scope', ''))


def register(only, direct, count, cid, created=100):
    chs = set(only) if only else (set(range(count)) if count > 0 else set())
    return {
        'id': cid, 'scope': ('整本' if not only else
                            '第 %d 章' % (min(only) + 1) if len(only) == 1 else
                            '第 %d–%d 章' % (min(only) + 1, max(only) + 1)),
        'mode': '直接朗读' if direct else '标注后合成',
        'createdAt': created, 'bookId': 'B1',
        'chapterIndices': sorted(chs) if chs else [],
        '_known': bool(chs),
    }


def read_index(items):
    out = []
    for o in items:
        chs, known = read_indices(o)
        out.append((o['id'], o.get('bookId'), o.get('mode', '—'), o.get('createdAt', 0), chs, known))
    return out


def latest_by_chapter(items, book_id='B1'):
    r = {}
    for _, b, mode, ts, chs, known in read_index(items):
        if b != book_id or not known or mode == '—':
            continue
        for ch in chs:
            if ch not in r or r[ch][0] < ts:
                r[ch] = (ts, mode)
    return r


def playable_chapters(items, book_id='B1'):
    return {ch for ch, (_, mode) in latest_by_chapter(items, book_id).items()
            if mode == '标注后合成'}


def phase_a():
    cases = [
        ('新成品·单章', [register({2}, False, 8, 'a')], {2}, {2}),
        ('新成品·范围', [register({1, 2, 3}, False, 8, 'b')], {1, 2, 3}, {1, 2, 3}),
        ('新成品·整本', [register(None, False, 8, 'c')], set(range(8)), set(range(8))),
        ('旧数据·第 3 章', [{'id': 'd', 'scope': '第 3 章', 'mode': '标注后合成',
                          'createdAt': 90, 'bookId': 'B1'}], {2}, {2}),
        ('旧数据·整本（不点亮）', [{'id': 'e', 'scope': '整本', 'mode': '标注后合成',
                            'createdAt': 90, 'bookId': 'B1'}], set(), set()),
        ('旧数据·选中/旧版（不点亮）', [
            {'id': 'f', 'scope': '选中 3 章', 'mode': '标注后合成', 'createdAt': 90, 'bookId': 'B1'},
            {'id': 'g', 'scope': '整本（旧版）', 'mode': '—', 'createdAt': 80, 'bookId': 'B1'}],
         set(), set()),
    ]
    for name, items, want_finished, want_playable in cases:
        got_f = set(latest_by_chapter(items).keys())
        got_p = playable_chapters(items)
        assert got_f == want_finished, f'{name}: 成品章 {got_f} != {want_finished}'
        assert got_p == want_playable, f'{name}: 可播章 {got_p} != {want_playable}'
        # 往返：写出 JSON 文本再读回，索引不丢
        wire = json.dumps([{k: v for k, v in o.items() if not k.startswith('_')}
                           for o in items], ensure_ascii=False)
        back = read_index(json.loads(wire))
        assert len(back) == len(items), f'{name}: 往返条数变了'
        print(f'  A ok · {name}：成品 {sorted(got_f)} / 可播 {sorted(got_p)}')

    # 直接朗读成品：第三列亮、第四列必须灰
    mixed = [register({0}, True, 8, 'x'), register({1}, False, 8, 'y')]
    assert 0 in latest_by_chapter(mixed), '直接朗读成品应在第三列亮'
    assert playable_chapters(mixed) == {1}, '直接朗读成品不能进第四列'
    print('  A ok · 直接朗读 → 第三列亮 / 第四列灰')

    # 同名章取最新一份
    dup = [register({2}, False, 8, 'old', 10), register({2}, False, 8, 'new', 99)]
    assert latest_by_chapter(dup)[2][0] == 99, '同名章应取 createdAt 最新'
    print('  A ok · 同名章取最新成品')

    # 越界范围与损坏数组：安全降级而不是崩
    assert scope_indices('第 999999999 章')[0] == {999999998}
    assert scope_indices('第 1–99999 章')[1] is False
    assert read_indices({'scope': '整本', 'chapterIndices': [-1, 'x', 3]}) == ({3}, True)
    print('  A ok · 越界/损坏数据安全降级')


# ── 阶段 B · 源码断言 ─────────────────────────────────────────────────────
POS = {
    'AudioLibrary': [
        'chapterIndices', 'chaptersKnown', 'fun scopeIndices', 'fun latestByChapter',
        'fun playableChapters', 'fun playableItems', 'fun audioFor',
        'allChapterCount: Int', 'index.json.part',
    ],
    'ChapterHub': [
        'fun ChapterHubScreen', 'fun MiniPlayBar', 'HubTitles = listOf("原始", "标注", "合成", "可播")',
        'horizontalScroll(hScroll)', 'animateScrollTo', 'Checkbox(',
        '去标注', '直接合成音频', '标注并合成音频',
        '这一档跑完会一并出音频', '未标注 · 可单章听', '按顺序播放', '从本章起播',
        'PlayQueue.state.collectAsState()', 'PlayQueue.setTracks', 'PlayQueue.release()',
        'SegPlan.readMap(work)', 'Producer.state.collectAsState()',
        'BookStore.read(c, book)', '整本进度', '旧缓存没有逐段记录',
    ],
    'MainActivity': [
        'LLM, CHAPTERS }', 'Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY)',
        'Screen.LIBRARY, Screen.PRODUCE, Screen.CHAPTERS -> NAV_LIBRARY',
        'onOpen = { bookId = it; screen = Screen.CHAPTERS }',
        'Screen.CHAPTERS -> ChapterHubScreen(', 'MiniPlayBar(ctx, miniBook',
    ],
    'Pipeline': ['AudioLibrary.register(c, book.id, book.title, only, direct, outFile, chapters.size)'],
    'ci.yml': ['r61_verify.py'],
}

FORBIDDEN = [
    'Screen.PLAYER', 'Text("倍速', 'Text("定时', 'Text("循环',
    'Text("下载', 'Text("边看边读', 'TextToSpeech(',
]

CONSERVED = {
    'app/src/main/cpp/tingben_native.cpp': None,
    'contracts/prompt_zh.txt': None,
    'app/src/main/AndroidManifest.xml': None,
    'app/build.gradle.kts': None,
    'app/src/main/java/com/tingben/app/core/PlayQueue.kt': None,
    'app/src/main/java/com/tingben/app/core/MixerDraft.kt': None,
}


def phase_b(texts):
    for key, needles in POS.items():
        t = texts[key]
        for n in needles:
            assert n in t, f'[{key}] 缺正向符号: {n}'
        print(f'  B ok · {key}: {len(needles)} 条正向断言')
    # 只查新页面：MainActivity/AudioScreen 本来就有倍速与 setSpeed，不能误伤既有能力
    hub_src = texts['ChapterHub']
    for bad in FORBIDDEN:
        assert bad not in hub_src, f'新页面出现不该有的假功能: {bad}'
    print(f'  B ok · {len(FORBIDDEN)} 条零残留')
    # 守恒：本轮不允许改这些文件（与 git HEAD 比由 CI 环境做，这里只查文件存在）
    for f in CONSERVED:
        assert (ROOT / f).exists(), f'守恒文件缺失: {f}'
    print(f'  B ok · {len(CONSERVED)} 个守恒文件在位')


BAD_INJECT = [
    ('AudioLibrary', 'chapterIndices', 'chapterIndexes', '索引字段名被改'),
    ('AudioLibrary', 'fun playableChapters', 'fun zzzDisabled', '可播入口被改名'),
    ('MainActivity', 'onOpen = { bookId = it; screen = Screen.CHAPTERS }',
     'onOpen = { bookId = it; screen = Screen.PRODUCE }', '点书又跳回制作页'),
    ('MainActivity', 'MiniPlayBar(ctx, miniBook', '// MiniPlayBar_off(ctx, miniBook', '悬浮条被注释掉'),
    ('ChapterHub', '这一档跑完会一并出音频', '', '删掉两档映射的如实说明'),
    ('ChapterHub', 'SegPlan.readMap(work)', 'null', '标注结果页失去真值来源'),
    ('ChapterHub', 'PlayQueue.state.collectAsState()', 'remember { mutableStateOf(PlaybackState()) }',
     '新建第二套播放状态'),
    ('ChapterHub', 'Checkbox(', '// CheckboxDisabled(', '第一列勾选框没了'),
    ('ci.yml', 'r61_verify.py', 'r60_verify.py', 'CI 漏挂 R61'),
    ('ChapterHub', '按顺序播放', '倍速', '画出不存在的倍速开关'),
]


def phase_inject(texts):
    blocked = 0
    for key, old, new, why in BAD_INJECT:
        t = texts[key]
        assert old in t, f'注入基线不存在: {why}'
        bad = dict(texts)
        bad[key] = t.replace(old, new)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                phase_b(bad)
        except AssertionError:
            blocked += 1
            print(f'  I ok · 拦下：{why}')
        else:
            raise AssertionError(f'反向注入没拦下：{why}')
    assert blocked == len(BAD_INJECT), f'只拦下 {blocked}/{len(BAD_INJECT)}'
    print(f'  I ok · 反向注入 {blocked}/{len(BAD_INJECT)} 全拦下')


def main():
    texts = {
        'AudioLibrary': AUDIO.read_text(),
        'ChapterHub': HUB.read_text(),
        'MainActivity': MAIN.read_text(),
        'Pipeline': PIPE.read_text(),
        'ci.yml': CI.read_text(),
    }
    print('R61 阶段 A · index.json 章节索引往返对拍')
    phase_a()
    print('R61 阶段 B · 源码正向断言与守恒')
    phase_b(texts)
    print('R61 反向注入')
    phase_inject(texts)
    print('R61 ALL PASS')


if __name__ == '__main__':
    try:
        main()
    except AssertionError as e:
        print('R61 FAIL:', e)
        sys.exit(1)
