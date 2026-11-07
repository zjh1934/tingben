# -*- coding: utf-8 -*-
"""R96 · 书架长列表与首帧磁盘读：正向 / 旧形态零残留 / 守恒 / 反向注入。

沙盒无 Android SDK，静态绿 ≠ 编译过；真编译由 CI 负责。
--selftest 先跑一次正向，再做反向注入（历史脚本只跑注入，这里补严）。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "main": ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt",
    "reading": ROOT / "app/src/main/java/com/tingben/app/core/ReadingState.kt",
    "chrome": ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt",
    "hub": ROOT / "app/src/main/java/com/tingben/app/ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "parity": ROOT / "scripts/r96_read_parity.py",
}

POS = {
    "main": [
        'import androidx.compose.foundation.lazy.items\n',
        'var lastReads by remember { mutableStateOf(emptyMap<String, Int>()) }',
        'LaunchedEffect(refresh, books) {',
        'lastReads = withContext(Dispatchers.IO) { ReadingState.allLast(c) }',
        'contentPadding = PaddingValues(16.dp)',
        'if (importError != null) {',
        'item(key = "import-error") {',
        'private fun InputReadScreen(',
        'items(books, key = { it.id }) { b ->',
        'val lastRead = lastReads[b.id]',
        '上次读到 第 ${lastRead + 1} 章',
    ],
    "reading": [
        'private fun decode(v: Any?): Pos?',
        'is Number -> v.toInt().takeIf { it >= 0 }?.let { Pos(it) }',
        'is JSONObject -> v.optInt("chapter", -1)',
        'is AtomicJson.Read.Ok -> decode(JSONObject(r.text).opt(bookId))',
        'fun last(c: Context, bookId: String): Int? = pos(c, bookId)?.chapter',
        'fun allLast(c: Context): Map<String, Int>',
        'for (k in o.keys())',
        'decode(o.opt(k))?.let { out[k] = it.chapter }',
    ],
    "ci": ['scripts/r96_verify.py', 'scripts/r96_read_parity.py'],
    "parity": ['def decode(', 'def all_last(', 'CASES = ['],
}

KEEP = {
    "main": [
        'BookStore.list(c)', 'ModelStore.installedModes(c)', 'ModelStore.missing(c)',
        'AlertDialog(', 'onOpen(b.id)', 'onChanged()',
        'BookStore.add(c, "粘贴的文字", text)', 'BookStore.delete(c, id)',
        'private fun InputReadScreen(', '再选一次文件 ›',
    ],
    "reading": [
        'reading_state.json',
        'fun save(c: Context, bookId: String, chapter: Int, para: Int? = null, offset: Int? = null)',
        'val same = prev?.chapter == chapter',
    ],
    "chrome": ['NAV_MIXER to "调音台"', 'NAV_MODELS to "模型"'],
    "hub": ['ReadingState.last(c, b.id)', 'LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp)) {'],
    "ci": ['scripts/r95_verify.py', 'scripts/r94_verify.py'],
}

OLD_WORDS = ['系统提示词', '数据标注', '语言模型', '导出日志', '标注进度']

def library_body(t):
    start = t.find('private fun LibraryScreen(')
    end = t.find('private fun InputReadScreen(', start)
    return t[start:end] if start >= 0 and end > start else ''

def check(f):
    e = []
    for k, needles in POS.items():
        for s in needles:
            if s not in f[k]:
                e.append('[%s] 正向缺: %s' % (k, s))
    for k, needles in KEEP.items():
        for s in needles:
            if s not in f[k]:
                e.append('[%s] 守恒失效: %s' % (k, s))
    for s in OLD_WORDS:
        for k in ('main', 'reading'):
            if s in f[k]:
                e.append('[%s] 旧说法残留: %s' % (k, s))

    body = library_body(f['main'])
    if not body:
        e.append('找不到 LibraryScreen 函数体')
    else:
        if 'LazyColumn(' not in body:
            e.append('书架不是 LazyColumn')
        if 'verticalScroll' in body:
            e.append('书架仍有 verticalScroll（全量 Column 复现）')
        if 'books.forEach' in body:
            e.append('书架仍在 forEach 全量铺书卡')
        if 'associate { it.id to ReadingState.last' in body:
            e.append('阅读位置又回到逐本同步读')
        if 'ReadingState.last(c, it.id)' in body:
            e.append('逐本 last() 复现')
        if body.find('confirmDelete?.let') > body.find('LazyColumn('):
            e.append('AlertDialog 又塞回列表 DSL 内')
        if body.find('LaunchedEffect(refresh, books)') > body.find('LazyColumn('):
            e.append('后台加载塞回列表 DSL 内')
        if 'item(key = "import-error") {' not in body:
            e.append('导入失败卡被删')
        if body.find('item(key = "import-error")') >= body.find('items(books, key = { it.id })'):
            e.append('导入失败卡没排在书卡之前')
        if body.count('item(key = ') != 1:
            e.append('固定 item 不是只剩导入失败卡一张')
    for s in ('RefreshStrip(', 'Text("共 ${books.size} 本"', 'item(key = "head")', 'item(key = "count")'):
        if s in f['main']:
            e.append('书架旧形态回潮: ' + s[:40])
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
    print('OK · R96 正向 %d / 守恒 %d / 结构 9 项 / 旧说法零残留全过' %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in KEEP.values())))
    return 0

def selftest():
    f = load()
    base = check(f)
    if base:
        print('正向本身就红，先修正向:')
        for x in base:
            print(' -', x)
        return 1
    cases = [
        ('书卡退回全量 forEach', 'main',
         lambda t: t.replace('items(books, key = { it.id }) { b ->', 'books.forEach { b ->')),
        ('书架退回全量 Column', 'main',
         lambda t: t.replace('contentPadding = PaddingValues(16.dp)',
                             'Modifier.verticalScroll(rememberScrollState()),')),
        ('删 items 导入', 'main',
         lambda t: t.replace('import androidx.compose.foundation.lazy.items\n', '')),
        ('删后台加载', 'main',
         lambda t: t.replace('LaunchedEffect(refresh, books) {', 'if (false) {')),
        ('回到组合期同步读', 'main',
         lambda t: t.replace('lastReads = withContext(Dispatchers.IO) { ReadingState.allLast(c) }',
                             'lastReads = books.associate { it.id to ReadingState.last(c, it.id) }')),
        ('删批量读', 'reading',
         lambda t: t.replace('fun allLast(c: Context): Map<String, Int>', 'fun nope(c: Context): Map<String, Int>')),
        ('拆成两套解析', 'reading',
         lambda t: t.replace('is AtomicJson.Read.Ok -> decode(JSONObject(r.text).opt(bookId))',
                             'is AtomicJson.Read.Ok -> JSONObject(r.text).opt(bookId) as? Pos')),
        ('删批量遍历', 'reading',
         lambda t: t.replace('for (k in o.keys())', 'for (k in listOf<String>())')),
         ('Dialog 塞回列表', 'main', lambda t: t.replace('    confirmDelete?.let { id ->', '    if (true) { } //', 1)
                     .replace('        if (importError != null) {',
                              '        confirmDelete?.let { id -> AlertDialog(onDismissRequest = {}) }\n'
                              '        if (importError != null) {', 1)),
         ('导入失败卡被改名', 'main', lambda t: t.replace('item(key = "import-error")', 'item(key = "late")', 1)),
        ('CI 摘验证', 'ci', lambda t: t.replace('scripts/r96_verify.py', 'x.py')),
        ('CI 摘对拍', 'ci', lambda t: t.replace('scripts/r96_read_parity.py', 'x.py')),
        ('对拍脚本被掏空', 'parity', lambda t: t.replace('CASES = [', 'DATA = [', 1)),
         ('旧刷新条回潮', 'main', lambda t: t + '\nRefreshStrip(\n'),
        ('守恒破坏·抽屉', 'chrome',
         lambda t: t.replace('NAV_MIXER to "调音台"', 'NAV_MIXER to "调音台2"')),
        ('旧说法回潮', 'reading', lambda t: t + '\n// 语言模型\n'),
    ]
    bad = 0
    for name, key, mut in cases:
        g = dict(f)
        g[key] = mut(f[key])
        if not check(g):
            print('  反向注入未被拦下:', name)
            bad += 1
    print('SELFTEST 正向 1 + 反向注入 %d 组，拦下 %d，漏 %d' %
          (len(cases), len(cases) - bad, bad))
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(selftest() if '--selftest' in sys.argv else run())
