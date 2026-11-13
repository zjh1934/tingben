# -*- coding: utf-8 -*-
"""R96 · 书架长列表与首帧磁盘读：正向 / 旧形态零残留 / 守恒 / 反向注入。

沙盒无 Android SDK，静态绿 ≠ 编译过；真编译由 CI 负责。
--selftest 先跑一次正向，再做反向注入（历史脚本只跑注入，这里补严）。
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    "main": ui_sources.source(ROOT),
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
        'contentPadding = PaddingValues(16.dp),',
        'item(key = "head") {',
        'if (importError != null) {',
        'item(key = "import-error") {',
        'if (books.isNotEmpty()) {',
        'private fun InputReadScreen(',
        'items(books, key = { it.id }) { b ->',
        # R291 · 空态不再画第二枚导入入口，empty item 与那行字一起退役
        'val lastRead = lastReads[b.id]',
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
        'RefreshStrip(', 'AlertDialog(', 'onOpen(b.id)', 'refresh++',
        'BookStore.add(c, autoBookTitle(snapshot,', # R176 自动标题；守恒的是「粘贴会建书」
        'private fun InputReadScreen(', '再选一次文件 ›',
    ],
    "reading": [
        'reading_state.json',
        'fun save(c: Context, bookId: String, chapter: Int, para: Int? = null, offset: Int? = null)',
        'val same = prev?.chapter == chapter',
    ],
    "chrome": ['internal fun AppDrawerSheet(', 'private fun GearIcon('],
    "hub": ['ReadingState.last(c, b.id)', 'LazyColumn(Modifier.fillMaxSize(),'],
    "ci": ['scripts/r95_verify.py', 'scripts/r94_verify.py'],
}

OLD_WORDS = ['系统提示词', '数据标注', '语言模型', '标注进度']
# R141 · '导出日志' 原本指的是书架页上暴露内部工程术语的旧入口，不是正式功能。
# 批次 E 在设置页新增的「导出诊断日志」是明确排期的任务（带隐私提示、
# 走系统文件选择器、IO 线程），不算旧说法回潮。这里只继续守书架页正文。
OLD_WORDS_LIBRARY_ONLY = ['导出日志']

def library_body(t):
    start = t.find('private fun LibraryScreen(')
    end = t.find('private fun InputReadScreen(', start)
    return t[start:end] if start >= 0 and end > start else ''

def check(f):
    e = []
    # H1 · 书架批量数据（阅读位置 + 合成进度）合并成一次 IO 读盘，
    # 原来绑死的单条 `lastReads = withContext(...) { allLast }` 不再适用。
    # 守行为：LaunchedEffect 里必须有 IO + 批量读，且不许回到组合期同步读。
    lb = library_body(f["main"])
    eff = re.search(r"LaunchedEffect\(refresh, books\) \{(.*?)\n    \}", lb, re.S)
    if not eff:
        e.append("书架缺后台加载 LaunchedEffect")
    else:
        blk = eff.group(1)
        if "withContext(Dispatchers.IO)" not in blk:
            e.append("书架批量数据没走 IO 线程")
        if "ReadingState.allLast(c)" not in blk:
            e.append("阅读位置没走批量读 allLast")
    if "lastReads = books.associate" in lb:
        e.append("阅读位置回到组合期同步读")
    # R230 · 「上次读到 第 N 章」是废话，撤掉后不许回潮；进度条保留。
    if '上次读到 第 ${lastRead + 1} 章' in library_body(f["main"]):
        e.append("R230 回潮：书架又写「上次读到 第 N 章」")
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
    for s in OLD_WORDS_LIBRARY_ONLY:
        if s in body:
            e.append('[main] 书架页旧说法残留: %s' % s)
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
        if not (body.find('item(key = "head")') < body.find('item(key = "import-error")')
                < body.find('items(books, key = { it.id })')):
            e.append('item 顺序错乱')
        # R328 · 独立书架页撤掉后「共 N 本」总数行也删了：抽屉书单只剩
        # 刷新条 + 导入失败提示两个固定 item，空态骨架走 items(3)。
        if body.count('item(key = ') != 2:
            e.append('固定 item 数不是 2')
        if 'Text("共 ${books.size} 本"' in body:
            e.append('R328 回潮：抽屉书单又写总数废话')
        if 'item(key = "empty")' in body or 'Text("导入第一本 ›"' in body:
            e.append('R291 回潮：书架空态又冒出第二枚导入入口')
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
         lambda t: t.replace('contentPadding = PaddingValues(16.dp),',
                             'Modifier.verticalScroll(rememberScrollState()),')),
        ('删 items 导入', 'main',
         lambda t: t.replace('import androidx.compose.foundation.lazy.items\n', '')),
        ('删后台加载', 'main',
         lambda t: t.replace('LaunchedEffect(refresh, books) {', 'if (false) {')),
        ('回到组合期同步读', 'main',
         lambda t: t.replace('lastReads = reads\n        progress = prog',
                             'lastReads = books.associate { it.id to ReadingState.last(c, it.id) }')),
        ('批量数据退回主线程', 'main',
         lambda t: t.replace('val (reads, prog) = withContext(Dispatchers.IO) {',
                             'val (reads, prog) = withContext(Dispatchers.Main) {')),
        ('删批量读', 'reading',
         lambda t: t.replace('fun allLast(c: Context): Map<String, Int>', 'fun nope(c: Context): Map<String, Int>')),
        ('拆成两套解析', 'reading',
         lambda t: t.replace('is AtomicJson.Read.Ok -> decode(JSONObject(r.text).opt(bookId))',
                             'is AtomicJson.Read.Ok -> JSONObject(r.text).opt(bookId) as? Pos')),
        ('删批量遍历', 'reading',
         lambda t: t.replace('for (k in o.keys())', 'for (k in listOf<String>())')),
        ('Dialog 塞回列表', 'main',
         lambda t: t.replace('    confirmDelete?.let { id ->', '    if (true) { } //', 1)
                     .replace('        item(key = "head") {',
                              '        confirmDelete?.let { id -> AlertDialog(onDismissRequest = {}) }\n'
                              '        item(key = "head") {', 1)),
        ('头部顺序破坏', 'main',
         lambda t: t.replace('item(key = "head")', 'item(key = "headx")', 1)),
        ('总数废话回潮', 'main',
         lambda t: t.replace('        if (books.isNotEmpty()) {',
                             '        Text("共 ${books.size} 本", fontSize = 12.sp, color = Sub)\n'
                             '        if (books.isNotEmpty()) {', 1)),
        ('第二导入入口回潮', 'main',
         lambda t: t.replace('items(books, key = { it.id }) { b ->',
                             'item(key = "empty") { Text("导入第一本 ›", fontSize = 14.sp, color = Primary) }\n'
                             '        items(books, key = { it.id }) { b ->', 1)),
        ('CI 摘验证', 'ci', lambda t: t.replace('scripts/r96_verify.py', 'x.py')),
        ('CI 摘对拍', 'ci', lambda t: t.replace('scripts/r96_read_parity.py', 'x.py')),
        ('对拍脚本被掏空', 'parity', lambda t: t.replace('CASES = [', 'DATA = [', 1)),
        ('守恒破坏·刷新条', 'main', lambda t: t.replace('RefreshStrip(', 'NoStrip(')),
        ('守恒破坏·壳层', 'chrome',
         lambda t: t.replace('private fun GearIcon(', 'private fun BrokenGear(')),
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
