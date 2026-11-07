# -*- coding: utf-8 -*-
"""R80 ·「我的音频」页收敛验证：正向 / 旧形态零残留 / 守恒 / 函数体结构 / 反向注入。

守的是四件事：空态有下一步、播放控制默认收起但功能不丢、
首屏不再出现第二张播放卡、列表容器与播放实例一行不动。
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nav_semantics as nav

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/com/tingben/app"
MAIN = BASE / "MainActivity.kt"
HUB = BASE / "ChapterHub.kt"
CI = ROOT / ".github/workflows/ci.yml"
CONTRACT = ROOT / "docs/00_现行契约.md"

M, H = "main", "hub"

POS = [
    (M, 'onLibrary: () -> Unit, onModels: () -> Unit'),
    (M, 'var openBooks by remember { mutableStateOf(setOf<String>()) }'),
    (M, 'item(key = "audio_tree_summary")'),
    (M, '共 ${tree.size} 本 · $chCount 章'),
    (M, 'val segCount = tree.sumOf'),
    (M, 'if (pq.trackId.isNotEmpty()) {'),
    (M, '"−15s"'),
    (M, 'item(key = "audio_tree_playing")'),
    (M, 'item(key = "audio_tree_summary")'),
    (M, 'Text("重命名"'),
    (M, 'Text("转格式（WAV → M4A）"'),
    (M, 'Text("复制 / 另存为 WAV"'),
    (M, 'Text("删除", fontSize = 14.sp, color = Warn)'),
    (M, '"−15s"'),
    (M, '"+15s"'),
    (M, '"−15s"'),
    (M, '"+15s"'),
    (M, '"关闭"'),
    (M, 'PlayQueue.release()'),
    (M, 'LazyColumn('),
]

BAN = [
    (M, 'FilledTonalButton(onClick = {\n                            exportId = item.id'),
    (M, 'if (current != null) {\n            item(key = "audio_tree_playing")'),
    (M, 'CardBox(Modifier.padding(bottom = 12.dp)) {\n                    Text(if (pq.ended)'),
    (M, 'fontSize = 16.sp,\n                        fontWeight = FontWeight.Bold, maxLines = 2'),
    (M, 'Spacer(Modifier.width(8.dp))\n                        FilledTonalButton'),
    (M, '} ) { Text("保存到手机", fontSize = 12.sp) }'),
]

KEEP = [
    (M, 'LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {'),
    (M, 'item(key = "audio_book_${b.bookId}")'),
    (M, 'PlayQueue.setTracks(listOf(Track(s.file, s.name, id = tid)), "seg")'),
    (M, 'ActivityResultContracts.CreateDocument("audio/wav")'),
    (M, 'AudioOps.deleteAll(t.files)'),
    (M, 'localTick++'),
    (M, 'val pq by PlayQueue.state.collectAsState()'),
    (M, 'MiniPlayBar(ctx, miniBook'),
    (H, 'internal fun MiniPlayBar'),
    (H, 'if (!st.isPlaying) return@LaunchedEffect'),
    (H, 'delay(500L)'),
]

FILES = {M: MAIN, H: HUB}

def audio_body(text):
    s = text.index("private fun AudioScreen")
    e = text.index("private fun GroupLabel(", s)
    return text[s:e]

def check(files):
    errs = []
    EXPECTED = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
                "SETTINGS", "MODELS", "MIXER", "CHAPTERS", "TASK_DETAIL"}
    if nav.screen_enum(files[M]) != EXPECTED:
        errs.append("Screen 枚举集合与 10 值不符")
    for msg in nav.semantics(files[M]):
        errs.append("导航语义: " + msg)
    try:
        body = audio_body(files[M])
        if "onModels" not in body or not nav.model_entries(files[M]):
            errs.append("我的音频页没有去模型页的入口")
    except ValueError:
        errs.append("音频页函数体边界定位失败")
    for f, s in POS:
        if s not in files[f]: errs.append("缺正向[%s]: %s" % (f, s[:50]))
    for f, s in BAN:
        if s in files[f]: errs.append("旧形态残留[%s]: %s" % (f, s[:50]))
    for f, s in KEEP:
        if s not in files[f]: errs.append("守恒丢失[%s]: %s" % (f, s[:50]))
    try:
        body = audio_body(files[M])
        checks = [
            ("FilledTonalButton" not in body, "列表行仍有第二个实心块"),
            (body.count('item(key = "audio_tree_playing")') == 1, "now_playing item 数量不是 1"),
            (body.count('item(key = "audio_tree_summary")') == 1, "audio_summary item 数量不是 1"),
            (body.count('item(key = "audio_seg_') == 1, "小块层数量不是 1"),
            ("if (pq.trackId.isNotEmpty())" in body, "播放控制行没有常驻"),
            ("var openBooks by remember { mutableStateOf(setOf<String>()) }" in body, "书展开态默认值丢失"),
            ("verticalScroll" not in body, "音频页退回 Column+verticalScroll"),
            ("PlayQueue.tick()" not in body, "音频页又自行驱动 tick"),
            ("LazyColumn(" in body, "丢了列表容器"),
            ("−15s" in body and "+15s" in body, "丢了 ±15s"),
            ("关闭" in body, "丢了关闭播放器"),
            ("PlayQueue.release()" in body, "丢了关闭播放器"),
            ("val segCount = tree.sumOf" in body, "汇总行不读音频树"),
        ]
        for ok, msg in checks:
            if not ok: errs.append("AudioScreen: " + msg)
    except ValueError:
        errs.append("AudioScreen 函数体边界定位失败")
    return errs

def load():
    return {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}

def run():
    errs = check(load())
    if "r80_verify.py" not in CI.read_text(encoding="utf-8"):
        errs.append("CI 未挂 r80_verify")
    if not CONTRACT.exists():
        errs.append("现行契约缺失")
    if errs:
        print("FAIL")
        for e in errs: print(" -", e)
        return 1
    print("R80 PASS · 正向 %d / 旧形态零残留 %d / 守恒 %d / 函数体结构 / CI 已挂 / 现行契约在"
          % (len(POS), len(BAN), len(KEEP)))
    return 0

def selftest():
    files = load()
    n = fails = 0
    for f, s in POS:
        n += 1
        b = dict(files); b[f] = b[f].replace(s, "___INJECTED___")
        if not check(b): fails += 1; print("未拦下正向缺失:", s[:46])
    for f, s in BAN:
        n += 1
        b = dict(files); b[f] = b[f] + "\n/*\n" + s + "\n*/\n"
        if not check(b): fails += 1; print("未拦下旧形态:", s[:46])
    for f, s in KEEP:
        n += 1
        b = dict(files); b[f] = b[f].replace(s, "___GONE___")
        if not check(b): fails += 1; print("未拦下守恒丢失:", s[:46])
    injections = [
        ("var openBooks by remember { mutableStateOf(setOf<String>()) }",
         "var controlsOpen by remember { mutableStateOf(true) }", "默认展开"),
        ("if (pq.trackId.isNotEmpty()) {",
         "if (current != null) {", "详细控制重新常驻"),
        ('item(key = "audio_tree_summary")', 'item(key = "audio_summary_old")', "汇总行消失"),
        ('val segCount = tree.sumOf',
         'items.sumOf { AudioLibrary.durationMs(it.file) }', "汇总行改读磁盘"),
        ('Text("复制 / 另存为 WAV"',
         'FilledTonalButton(onClick = {}) { Text("保存到手机", fontSize = 12.sp) }', "第二个实心块回来"),
    ]
    for old, new, label in injections:
        n += 1
        b = dict(files)
        if old not in b[M]:
            fails += 1; print("注入锚点不存在:", label); continue
        b[M] = b[M].replace(old, new, 1)
        if not check(b): fails += 1; print("未拦下结构注入:", label)
    print("反向注入 %d 组，拦下 %d，漏 %d" % (n, n - fails, fails))
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv else run())
