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
    (M, 'var controlsOpen by remember { mutableStateOf(false) }'),
    (M, 'item(key = "audio_summary")'),
    (M, '共 ${items.size} 个音频 · 合计 ${fmtTime(totalMs)}'),
    (M, 'items.sumOf { AudioLibrary.durationMsOfBytes(it.bytes) }'),
    (M, 'if (current != null && controlsOpen) {'),
    (M, 'if (controlsOpen) "收起 ⌃" else "播放控制 ›"'),
    (M, 'item(key = "now_playing")'),
    (M, 'item(key = "audio_empty")'),
    (M, 'Text("去书架 ›"'),
    (M, 'Text("去看模型 ›"'),
    (M, 'Text("保存到手机", fontSize = 12.sp, color = Sub)'),
    (M, 'Text("删除", fontSize = 12.sp, color = Warn)'),
    (M, '"−15s"'),
    (M, '"+15s"'),
    (M, '"⏮ 上一条"'),
    (M, '"下一条 ⏭"'),
    (M, '"关闭播放器"'),
    (M, 'PlayQueue.setSpeed(nextSpeed)'),
    (M, 'Slider('),
]

BAN = [
    (M, 'FilledTonalButton(onClick = {\n                            exportId = item.id'),
    (M, 'if (current != null) {\n            item(key = "now_playing")'),
    (M, 'CardBox(Modifier.padding(bottom = 12.dp)) {\n                    Text(if (pq.ended)'),
    (M, 'fontSize = 16.sp,\n                        fontWeight = FontWeight.Bold, maxLines = 2'),
    (M, 'Spacer(Modifier.width(8.dp))\n                        FilledTonalButton'),
    (M, '} ) { Text("保存到手机", fontSize = 12.sp) }'),
]

KEEP = [
    (M, 'LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {'),
    (M, 'itemsIndexed(items, key = { _, it -> it.id })'),
    (M, 'PlayQueue.setTracks(items.map'),
    (M, 'ActivityResultContracts.CreateDocument("audio/wav")'),
    (M, 'AudioLibrary.remove(c, item.id)'),
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
                "SETTINGS", "MODELS", "MIXER", "CHAPTERS"}
    if nav.screen_enum(files[M]) != EXPECTED:
        errs.append("Screen 枚举集合与 9 值不符")
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
            (body.count('item(key = "now_playing")') == 1, "now_playing item 数量不是 1"),
            (body.count('item(key = "audio_summary")') == 1, "audio_summary item 数量不是 1"),
            (body.count("itemsIndexed(") == 1, "itemsIndexed 数量不是 1"),
            ("if (current != null && controlsOpen)" in body, "详细控制没有默认收起"),
            ("var controlsOpen by remember { mutableStateOf(false) }" in body, "controlsOpen 默认值不是 false"),
            ("verticalScroll" not in body, "音频页退回 Column+verticalScroll"),
            ("PlayQueue.tick()" not in body, "音频页又自行驱动 tick"),
            ("Slider(" in body, "丢了拖动进度"),
            ("−15s" in body and "+15s" in body, "丢了 ±15s"),
            ("关闭播放器" in body, "丢了关闭播放器"),
            ("PlayQueue.setSpeed(nextSpeed)" in body, "丢了倍速"),
            ("AudioLibrary.durationMsOfBytes(it.bytes)" in body, "汇总行变成读磁盘"),
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
        ("var controlsOpen by remember { mutableStateOf(false) }",
         "var controlsOpen by remember { mutableStateOf(true) }", "默认展开"),
        ("if (current != null && controlsOpen) {",
         "if (current != null) {", "详细控制重新常驻"),
        ('item(key = "audio_summary")', 'item(key = "audio_summary_old")', "汇总行消失"),
        ('items.sumOf { AudioLibrary.durationMsOfBytes(it.bytes) }',
         'items.sumOf { AudioLibrary.durationMs(it.file) }', "汇总行改读磁盘"),
        ('Text("保存到手机", fontSize = 12.sp, color = Sub)',
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
