# -*- coding: utf-8 -*-
"""R78 · 书架首屏重排验证：正向 / 零残留 / 守恒 / 反向注入。"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys, re
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ui_sources.source(ROOT)
CONTRACT = ROOT / "docs/00_现行契约.md"
CI = ROOT / ".github/workflows/ci.yml"

# R246 · 按现状迁移：R215 首页不建书、R230 撤「上次读到」/改长按菜单、
# R245 设置页统一行之后，11.sp/10.sp 是合法字号，不再当「旧形态」禁。
POS = [
    'Text("导入第一本 ›", fontSize = 14.sp, color = Primary)',
    'var menuBook by remember { mutableStateOf<String?>(null) }',
    'Text("置顶或删除", fontSize = 12.sp, color = Sub)',
    'else "听这段"',                       # R215 首页粘一段即听，不建书
    'Text("再选一次文件 ›", fontSize = 12.sp, color = Primary)',
    'onLongPress = { menuBook = b.id }',   # R230 长按书名弹置顶/删除
    'var lastReads by remember { mutableStateOf(emptyMap<String, Int>()) }',
    'ReadingState.allLast(c)',
    'items(books, key = { it.id })',
    'Text("共 ${books.size} 本"',
    'onTap = { onOpen(b.id) }',            # R230 点书名开书、长按弹菜单
    'BookStore.pinned(c)',
    'Text("删除"',
]
BAN = [
    'FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("去准备") }',
    'Text("粘贴文字", fontSize = 12.5.sp, fontWeight = FontWeight.Bold',
    'Text("书架还是空的"',
    'IconButton(onClick = { onOpen(b.id) })',
    'IconButton(onClick = { confirmDelete = b.id })',
    'Text("✕", fontSize = 16.sp',
    # R215 · 首页退回「粘贴建书」是功能回潮，不许出现
    'BookStore.add(c, "粘贴的文字", pasteText)',
    # R230 · 「上次读到 第 N 章」是撤掉的废话
    '上次读到 第 ${lastRead + 1} 章',
]
KEEP = [
    'RefreshStrip(', 'BookStore.list(c)', 'ModelStore.missing(c)',
    'ClipSynth.synthesize',                # R215 首页合成真源
    'InputReadScreen(',                    # R215 首页真源（不再建书）
    'BookStore.delete(c, id)',
    'AlertDialog(', 'onImportBook', 'onOpen(b.id)', 'onChanged()',
]

def check(text, ok_msg):
    errs = []
    for s in POS:
        if s not in text: errs.append("缺正向: " + s)
    for s in BAN:
        if s in text: errs.append("旧形态残留: " + s)
    for s in KEEP:
        if s not in text: errs.append("守恒丢失: " + s)
    return errs

def run():
    t = SRC.read_text()
    errs = check(t, "")
    if not CONTRACT.exists(): errs.append("现行契约缺失")
    if "r78_verify.py" not in CI.read_text(): errs.append("CI 未挂 r78_verify")
    if errs:
        print("FAIL")
        for e in errs: print(" -", e)
        return 1
    print("PASS · 正向 %d / 零残留 %d / 守恒 %d / 现行契约在 / CI 已挂" % (len(POS), len(BAN), len(KEEP)))
    return 0

def selftest():
    """反向注入：把每条正向符号抹掉、把每条旧形态塞回去，必须被拦下。"""
    t = SRC.read_text()
    fails = 0
    for s in POS:
        broken = t.replace(s, "___INJECTED___")
        if not check(broken, ""): fails += 1; print("未拦下正向缺失:", s[:40])
    for s in BAN:
        broken = t + "\n// " + s + "\n"
        if not check(broken, ""): fails += 1; print("未拦下旧形态:", s[:40])
    for s in KEEP:
        broken = t.replace(s, "___GONE___")
        if not check(broken, ""): fails += 1; print("未拦下守恒丢失:", s[:40])
    print("反向注入 %d 组，拦下 %d，漏 %d" % (len(POS)+len(BAN)+len(KEEP), len(POS)+len(BAN)+len(KEEP)-fails, fails))
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv else run())
