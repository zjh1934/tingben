# -*- coding: utf-8 -*-
"""R79 · P4 性能二期验证：正向 / 零残留 / 守恒 / 函数体结构 / 反向注入。"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nav_semantics as nav

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/com/tingben/app"
MAIN = BASE / "MainActivity.kt"
HUB = BASE / "ChapterHub.kt"
LIB = BASE / "core" / "AudioLibrary.kt"
CI = ROOT / ".github/workflows/ci.yml"
CONTRACT = ROOT / "docs/00_现行契约.md"

M, H, A = "main", "hub", "lib"

POS = [
    (M, 'import androidx.compose.foundation.lazy.LazyColumn'),
    (M, 'import androidx.compose.foundation.lazy.itemsIndexed'),
    (M, 'LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {'),
    (M, 'item(key = "audio_book_${b.bookId}")'),
    (M, 'item(key = "audio_tree_playing")'),
    (M, 'item(key = "audio_tree_summary")'),
    (M, 'pq.durationMs'),
    (M, '"audio_seg_grid_$ck"'),
    (M, 'val seenTask = remember(st.done, redoNonce, bookId)'),
    (M, 'val allSegs = remember(st.done, seenTask, redoNonce)'),
    (M, 'val rest = remember(st.done, seenTask, redoNonce)'),
    (M, 'val verdict = remember(st.done, seenTask, redoNonce)'),
    (M, 'val heartbeat = remember(tick) {'),
    (H, 'if (!st.isPlaying) return@LaunchedEffect'),
    (H, 'delay(500L)'),
    (A, 'fun durationMsOfBytes(bytes: Long): Long ='),
    (A, 'fun durationMs(f: File): Long = durationMsOfBytes(f.length())'),
]
BAN = [
    (M, 'LaunchedEffect(Unit) { while (true) { delay(2000); loadTick++ } }'),
    (M, 'LaunchedEffect(pq.isPlaying) {\n        if (!pq.isPlaying) return@LaunchedEffect\n        while (true) { delay(500); PlayQueue.tick() }\n    }'),
    (M, 'AudioLibrary.durationMs(current.file)'),
    (M, 'AudioLibrary.durationMs(item.file)'),
    (M, 'items.forEachIndexed { idx, item ->'),
    (M, 'remember(tick, seenTask, redoNonce)'),
    (M, 'val seenTask = remember(tick)'),
    (H, 'delay(1000L)'),
]
KEEP = [
    (M, 'val scroll = rememberScrollState()'),
    (M, 'Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp))'),
    (M, 'MiniPlayBar(ctx, miniBook'),
    (M, 'val pq by PlayQueue.state.collectAsState()'),
    (M, 'PlayQueue.setTracks(listOf(Track(s.file, s.name, id = tid)), "seg")'),
    (M, 'commitAll()'),
    (H, 'LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 90.dp))'),
    (H, 'PlayQueue.tick()'),
    (H, 'internal fun MiniPlayBar'),
]

FILES = {M: MAIN, H: HUB, A: LIB}

def audio_body(text):
    """AudioScreen 函数体：从函数签名到下一个 '// ── ⑤' 之前。"""
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
    wins = [m.group(1) for m in re.finditer(
        r"LaunchedEffect\(st\.running\)\s*\{(.{0,320})", files[M], re.S)]
    if not any("!st.running" in w and "loadTick++" in w for w in wins):
        errs.append("任务页缺「运行中才轮询 + 停止时即时刷新」")
    for f, s in POS:
        if s not in files[f]: errs.append("缺正向[%s]: %s" % (f, s[:52]))
    for f, s in BAN:
        if s in files[f]: errs.append("旧形态残留[%s]: %s" % (f, s[:52]))
    for f, s in KEEP:
        if s not in files[f]: errs.append("守恒丢失[%s]: %s" % (f, s[:52]))
    try:
        body = audio_body(files[M])
        if "LazyColumn(" not in body: errs.append("AudioScreen 内没有 LazyColumn")
        if "verticalScroll" in body: errs.append("AudioScreen 内仍有 verticalScroll")
        if "forEachIndexed" in body: errs.append("AudioScreen 内仍有 forEachIndexed")
        if body.count('item(key = "audio_seg_') != 1: errs.append("AudioScreen 内小块层不是 1 处")
        if "PlayQueue.tick()" in body: errs.append("AudioScreen 内仍在自行 tick")
    except ValueError:
        errs.append("AudioScreen 函数体边界定位失败")
    try:
        ms = files[M].index("private fun MixerScreen")
        mb = files[M][ms:]
        if "verticalScroll(scroll)" not in mb: errs.append("调音台 verticalScroll(scroll) 被破坏")
    except ValueError:
        errs.append("MixerScreen 定位失败")
    return errs

def load():
    return {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}

def run():
    errs = check(load())
    ci = CI.read_text(encoding="utf-8")
    if "r79_verify.py" not in ci: errs.append("CI 未挂 r79_verify")
    if not CONTRACT.exists(): errs.append("现行契约缺失")
    if errs:
        print("FAIL")
        for e in errs: print(" -", e)
        return 1
    print("PASS · 正向 %d / 零残留 %d / 守恒 %d / 音频页与调音台函数体结构 / CI 已挂 / 现行契约在"
          % (len(POS), len(BAN), len(KEEP)))
    return 0

def selftest():
    files = load()
    n = 0
    fails = 0
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
    n += 1
    b = dict(files)
    b[M] = b[M].replace("LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {",
                        "Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {")
    if not check(b): fails += 1; print("未拦下音频页退回 Column")
    n += 1
    b = dict(files)
    b[M] = b[M].replace("Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {",
                        "LazyColumn(Modifier.weight(1f)) {")
    if not check(b): fails += 1; print("未拦下调音台 verticalScroll 被破坏")
    print("反向注入 %d 组，拦下 %d，漏 %d" % (n, n - fails, fails))
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv else run())
