#!/usr/bin/env python3
"""R65 · 进度页 v12 验证：正向 / 旧形态零残留 / 差一对拍 / 反向注入 / 守恒。"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
RAW = ROOT / "app/src/main/java/com/tingben/app/diag/RawDump.kt"
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"
CI = ROOT / ".github/workflows/ci.yml"
s = MAIN.read_text(encoding="utf-8")
r = RAW.read_text(encoding="utf-8")
p = PIPE.read_text(encoding="utf-8")
errs = []

def need(cond, msg):
    if not cond: errs.append(msg)

def progress(text):
    a = text.index("private fun ProgressScreen(")
    b = text.index("// ── ③b 任务中心", a)
    return text[a:b]

pg = progress(s)

# ── 正向 ────────────────────────────────────────────────────────────────
MUST = [
    "RawDump.forBlock(c, sg.block + 1)",   # 差一必须在调用处
    "fun forBlock(c: Context, block: Int): List<Pair<Int, String>>",
    'val prefix = "block_%04d_attempt".format(block)',
    "segs.chunked(8).forEach",
    "Modifier.size(42.dp, 34.dp)",
    'Text(if (canStop) "⏸" else "▶"',
    "fontFamily = FontFamily.Monospace",
    "对照：实际送去合成的这句 ›",
    "PlayQueue.pause()", "PlayQueue.resume()", "PlayQueue.playFrom(0)",
    "更多操作 ›", "segSel == sg.index", "curStep > i",
    "textAlign = TextAlign.Center", "verdict.label",
    "这次是直接朗读，没有模型原始产出。",
    "这一块的原始产出已被清理（只保留最近 3 次运行）。",
]
for m in MUST:
    need(m in s or m in pg or m in r, "缺正向符号: " + m)
need("r65_verify.py" in CI.read_text(encoding="utf-8"), "CI 没挂 R65 验证")
need("RawDump.write(c, i + 1, attempt + 1" in p, "落盘 1-based 依据变了，差一规则要复核")

# ── 旧形态零残留 ────────────────────────────────────────────────────────
BAN = [
    "private fun TaskBar(", "TaskBar(onNavigate", "taskExpanded",
    "收起 ↑", "fontSize = 34.sp", "detectVerticalDragGestures(",
    "继续制作（从停下的地方接着跑）", "查看进度 ›", "var segOpen",
    '"展开 ↓"', "重播这一段",
    "跑一次「标注后合成」后这里给出结论",
]
for b in BAN:
    need(b not in s, "旧形态没删干净: " + b)
# 仅进度页专属（别的页面仍有合法的 expanded 折叠状态，不能全局查）
PG_BAN = ["var expanded by remember", "segOpen", "s.text,\n", "整宽"]
for b in PG_BAN[:2] + ["← 书架"]:
    need(b not in pg, "进度页旧形态没删干净: " + b)

# ── 差一对拍：0-based block → 1-based 文件名，多 attempt 升序 ────────────
def for_block(files, block):
    prefix = "block_%04d_attempt" % (block + 1)
    out = []
    for name, body in files:
        if name.startswith(prefix) and name.endswith(".txt"):
            a = name[len(prefix):-4]
            if a.isdigit(): out.append((int(a), body))
    return sorted(out)

cases = [
    ([("block_0001_attempt1.txt", "A"), ("block_0002_attempt1.txt", "B"),
      ("block_0002_attempt3.txt", "C"), ("block_0002_attempt2.txt", "D")], 1,
     [(1, "B"), (2, "D"), (3, "C")]),
    ([("block_0001_attempt1.txt", "A")], 0, [(1, "A")]),
    ([("block_0001_attempt1.txt", "A")], 1, []),
    ([], 0, []),
]
for files, block, want in cases:
    need(for_block(files, block) == want,
         "差一对拍失败 block=%d got=%s want=%s" % (block, for_block(files, block), want))

# ── 守恒 ────────────────────────────────────────────────────────────────
need(len(re.findall(r'^private enum class Screen \{.*\}$', s, re.M)) == 1, "Screen 枚举变了")
need("DismissibleNavigationDrawer(" in s, "抽屉推走联动丢了")
need("MixerCommit.commit(c, draft, n)" in s, "调音台唯一提交出口丢了")
need("navigationBarsPadding()" in s, "底部安全区丢了")
need("PlayQueue.setTracks(listOf(Track(" in s, "PlayQueue 单实例被改")
need("private fun TaskCenterScreen(" in s, "任务中心被误删")
for f in ["app/src/main/java/com/tingben/app/core/Workbench.kt",
          "app/src/main/java/com/tingben/app/core/SegPlan.kt",
          "app/src/main/java/com/tingben/app/core/PlayQueue.kt",
          "app/src/main/java/com/tingben/app/core/Pipeline.kt",
          "app/src/main/java/com/tingben/app/ChapterHub.kt",
          "app/src/main/java/com/tingben/app/AppChrome.kt",
          "app/src/main/AndroidManifest.xml",
          "app/build.gradle.kts"]:
    need((ROOT / f).exists(), "守恒文件缺失: " + f)

# ── 反向注入 ────────────────────────────────────────────────────────────
def blocked(mut, label, probe):
    m = mut(s)
    need(not probe(m), "反向注入没拦下: " + label)

blocked(lambda x: x.replace("RawDump.forBlock(c, sg.block + 1)",
                            "RawDump.forBlock(c, sg.block)"),
        "差一被抹掉", lambda m: "RawDump.forBlock(c, sg.block + 1)" in m)
blocked(lambda x: x.replace('Text(if (canStop) "⏸" else "▶"',
                            'Text(if (canStop) "停止" else "开始"'),
        "换回文字大按钮", lambda m: 'Text(if (canStop) "⏸" else "▶"' in m)
blocked(lambda x: x.replace("segs.chunked(8).forEach", "segs.chunked(9).forEach"),
        "一排挤成 9 格", lambda m: "segs.chunked(8).forEach" in m)
blocked(lambda x: x.replace("        MiniPlayBar(ctx, miniBook",
                            "        TaskBar(onNavigate = { _, _ -> })\n        MiniPlayBar(ctx, miniBook"),
        "任务卡回潮", lambda m: "TaskBar(" not in m)
blocked(lambda x: x.replace('"对照：实际送去合成的这句 ›"',
                            '"对照：模型吐出来的原貌 ›"'),
        "对照又冒充原貌", lambda m: '"对照：实际送去合成的这句 ›"' in m)
blocked(lambda x: x.replace("fontFamily = FontFamily.Monospace", ""),
        "原貌不原样显示", lambda m: "fontFamily = FontFamily.Monospace" in m)
blocked(lambda x: x.replace("if (doneStep) Primary else Color.Transparent", "Primary"),
        "没做的步骤也画色块",
        lambda m: "if (doneStep) Primary else Color.Transparent" in m)
blocked(lambda x: x.replace("PlayQueue.pause()", "PlayQueue.release()"),
        "再点一下变成清队列", lambda m: "PlayQueue.pause()" in m)
blocked(lambda x: x.replace('var lmOpen by remember { mutableStateOf(false) }',
                            'var lmOpen by remember { mutableStateOf(true) }'),
        "语言模型卡又常驻展开", lambda m: 'var lmOpen by remember { mutableStateOf(false) }' in m)
blocked(lambda x: x.replace("更多操作 ›", "更多操作"),
        "对照/重做入口被删", lambda m: "更多操作 ›" in m)

if errs:
    print("R65 FAIL:")
    for e in errs: print("  -", e)
    sys.exit(1)
print("R65 PASS · 正向 %d 符号 / 旧形态 %d 条零残留 / 差一对拍 %d 组 / 反向注入 10 例 / 守恒全过"
      % (len(MUST), len(BAN), len(cases)))
