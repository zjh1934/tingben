#!/usr/bin/env python3
"""R33 · U1 返工：状态栏安全区 + 全局清注释式文案 + 首页结构。

原则（用户原话「每个改动后面都连带着一堆东西」）：
- 所有结构改动先按**原始行号**切成编辑区间，互不重叠，从后往前一次性应用；
- 每处替换前断言命中次数，失败即整轮不写任何文件；
- 散落的说明型 Text(...) 调用按短语自动定位（括号配平），逐个删；
- helper 的说明参数统一置空，靠 ExpandGroup/OptionRow 已有的 isNotBlank 自动不渲染；
- 守恒清单：Screen 枚举、when 分发、refresh、BackHandler、chromeOf、ProduceService、
  native / prompt / 缓存分桶 / 4B 相关代码一律不碰。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"

raw = MAIN.read_text(encoding="utf-8")
lines = raw.splitlines(keepends=True)


def block(a: int, b: int) -> str:
    """原始文件 1-indexed 闭区间，返回精确文本。"""
    return "".join(lines[a - 1:b])


# ── ① 结构性替换：(起行, 止行, 新文本, 标签) ──────────────────────────
BLOCKS = [
    (289, 289,
     "    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {\n",
     "状态栏安全区"),

    (290, 296,
     """        AppTopBar(
            spec = chrome,
            onMenu = { idx ->
                screen = when (idx) {
                    0 -> Screen.SETTINGS
                    1 -> Screen.MIXER
                    2 -> Screen.MODELS
                    3 -> Screen.CAST
                    else -> Screen.SETTINGS
                }
                if (idx == 0) refresh++
                if (idx == 2) modelsFrom = Screen.SETTINGS
            },
            onImport = { openBookPicker() },
            surface = Card,
            primary = Primary
        )
""", "顶栏改为一级菜单入口"),

    (359, 392,
     """        // 跨页面常驻任务条：上扒展开 / 下扒收起，点整卡不再跳页
        val pendingTask = resumeTask?.takeIf { it.phase != "done" && !st.running }
        val hasTask = st.running || st.output != null || pendingTask != null
        var taskExpanded by remember { mutableStateOf(false) }
        if (hasTask) {
            val barBg = if (st.running) PrimarySoft
                else if (pendingTask != null) Color(0xFFFFF3E0) else Color(0xFFE7F4EC)
            Column(
                Modifier.fillMaxWidth().background(barBg)
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { acc = 0f },
                            onDragEnd = {
                                if (acc < -24f) taskExpanded = true
                                else if (acc > 24f) taskExpanded = false
                                acc = 0f
                            },
                            onVerticalDrag = { _, dy -> acc += dy }
                        )
                    }
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { taskExpanded = !taskExpanded }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).background(
                        if (st.running) Primary else if (pendingTask != null) Warn else Good,
                        RoundedCornerShape(50)))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        when {
                            st.running -> if (st.total > 0) "正在制作 · ${st.done}/${st.total} 块" else "正在制作…"
                            pendingTask != null -> "《${pendingTask.title}》有没做完的任务"
                            else -> "做好了"
                        },
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        color = if (st.running) Primary else if (pendingTask != null) Warn else Good,
                        modifier = Modifier.weight(1f))
                    Text(if (taskExpanded) "收起 ↑" else "展开 ↓",
                        fontSize = 12.5.sp, color = Primary)
                }
                if (taskExpanded) {
                    Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 10.dp)) {
                        Text(
                            when {
                                st.running -> "${st.stage} · 已完成 ${st.done}/${st.total} 块"
                                pendingTask != null -> "《${pendingTask.title}》· 已中断，回来点继续"
                                else -> "成品已生成"
                            },
                            fontSize = 11.5.sp, color = Sub)
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            screen = when {
                                st.running -> Screen.PROGRESS
                                pendingTask != null -> Screen.TASKS
                                else -> Screen.AUDIO
                            }
                            if (screen == Screen.AUDIO) refresh++
                        }) {
                            Text(if (st.running) "查看进度 ›"
                                else if (pendingTask != null) "去任务页 ›" else "去试听 ›",
                                fontSize = 12.sp)
                        }
                    }
                }
            }
        }
""", "常驻任务条改可展开/收起"),

    (456, 459,
     """    val clipboard = LocalClipboardManager.current
    var pasteOpen by remember { mutableStateOf(true) }
    var pasteText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
""", "书架页状态：默认展开粘贴框 + 删除确认 id"),

    (468, 585,
     """        Spacer(Modifier.height(6.dp))
        if (!ready) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                Text("还差模型文件", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onModels, Modifier.fillMaxWidth()) { Text("去准备") }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("粘贴文字", fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { pasteOpen = !pasteOpen }) {
                Text(if (pasteOpen) "收起" else "展开", fontSize = 12.sp, color = Primary)
            }
        }
        if (pasteOpen) {
            CardBox(Modifier.padding(bottom = 12.dp)) {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text("正文") },
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val got = clipboard.getText()?.text ?: ""
                        if (got.isNotBlank()) pasteText = got
                        else Toast.makeText(c, "剪贴板里没有文字", Toast.LENGTH_SHORT).show()
                    }) { Text("粘贴") }
                    TextButton(onClick = { pasteText = "" }) { Text("清除") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        if (pasteText.isBlank()) {
                            Toast.makeText(c, "先放点文字进来", Toast.LENGTH_SHORT).show()
                        } else {
                            BookStore.add(c, "粘贴的文字", pasteText)
                            pasteText = ""; pasteOpen = false
                            onChanged()
                            Toast.makeText(c, "书加好了", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("建书") }
                }
            }
        }
        if (books.isEmpty()) {
            CardBox {
                Text("书架还是空的", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onImportBook, Modifier.fillMaxWidth().height(48.dp)) {
                    Text("导入一本书", fontSize = 15.sp)
                }
            }
        } else {
            books.forEach { b ->
                CardBox(Modifier.padding(bottom = 10.dp)) {
                    Text(b.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("${b.chars} 字", fontSize = 11.5.sp, color = Sub)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { confirmDelete = b.id }) {
                            Text("✕", fontSize = 16.sp, color = Sub)
                        }
                        IconButton(onClick = { onOpen(b.id) }) {
                            Text("▶", fontSize = 16.sp, color = Primary)
                        }
                    }
                }
            }
        }
        confirmDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("删除这本书？") },
                text = { Text(books.find { it.id == id }?.title ?: "") },
                confirmButton = {
                    TextButton(onClick = {
                        BookStore.delete(c, id); confirmDelete = null; onChanged()
                    }) { Text("删除", color = Warn) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = null }) { Text("取消") }
                }
            )
        }
""", "书架页重写：删标题/重复卡、粘贴框、图标操作、删除确认"),

    (671, 680,
     """                        Text(if (m == ProduceMode.DIRECT) "直接朗读" else "标注后合成（实验性）",
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
""", "制作方式去说明，风险信息并进标题"),

    (837, 864,
     """        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 ↑" else "展开 ↓", fontSize = 12.5.sp, color = Primary)
            }
        }
        // 手势条：上扒展开 / 下扒收起，零文案
        Box(
            Modifier.fillMaxWidth().height(22.dp)
                .background(PrimarySoft, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    var acc = 0f
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragEnd = {
                            if (acc > 45f) expanded = false
                            else if (acc < -45f) expanded = true
                            acc = 0f
                        },
                        onVerticalDrag = { _, dy -> acc += dy }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.width(34.dp).height(3.dp)
                .background(Primary, RoundedCornerShape(50)))
        }
""", "进度页去大标题/手势条文案"),

    (973, 975,
     """                Text("绿=声音 · 蓝=模型标 · 黄=直读 · 灰=未记",
                    fontSize = 10.5.sp, color = Sub)
""", "看台图例压成一行"),

    (1133, 1148,
     """    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (t == null) {
            CardBox {
                Text("还没有任务", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onLibrary, Modifier.fillMaxWidth()) { Text("去书架") }
            }
        } else {
""", "任务页去大标题/说明"),

    (1236, 1247, "", "任务页后台长文整块删"),

    (1291, 1296,
     """    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
""", "音频页去大标题/说明"),

    (1347, 1353,
     """        if (items.isEmpty()) {
            CardBox {
                Text("还没有音频", fontWeight = FontWeight.Bold)
            }
        } else items.forEachIndexed { idx, item ->
""", "音频空状态去说明"),

    (1391, 1396,
     """private fun GroupLabel(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary)
        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = Sub)
    }
}
""", "GroupLabel 空说明不渲染"),

    (1433, 1464,
     """    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // R31 · 调音台是独立一页，不把 9 个参数埋在折叠里
        Card(
            Modifier.fillMaxWidth().padding(bottom = 11.dp)
                .clickable { onMixer() },
            colors = CardDefaults.cardColors(containerColor = PrimarySoft),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🎛 调音台", fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = Primary,
                    modifier = Modifier.weight(1f))
                Text("›", fontSize = 20.sp, color = Primary)
            }
        }

""", "设置页去大标题/作用域说明卡、调音台卡去副标题"),

    (1878, 1886,
     """    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
""", "模型页去大标题/说明"),

    (1975, 1988,
     """    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回设置", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
""", "角色页去大标题/说明"),

    (2132, 2140,
     """    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(10.dp))
""", "调音台去大标题/说明"),
]

# ── ② 散落说明型 Text 调用：按短语定位后整块删 ─────────────────────────
DROP_PHRASES = [
    "每块约 500 字",
    "这本书只识别出 1 章",
    "全局音色在设置页",
    "思考档是全局设置",
    "这三项是跨书默认值",
    "①②③ 仅本次",
    "上次没跑完，直接点",
    "算完一块才会往上走一格",
    "其中 ${verdict.unknown} 段是旧缓存",
    "还没开始落盘",
    "已做好的块会留着",
    "现在正在算。可以切走",
    "续跑沿用原档位",
    "开着：每块先想再标",
    "这台手机给不起模型要的思考长度",
    "8192 在部分手机上会因内存不足崩",
    "开着：原文写到哭",
    "不是插入音效",
    "给整本书定一个固定的嗓子",
    "默认值逐字取自 qwentts.cpp",
    "不跑整本，只让声音模型读一句",
    "听本 · 本地离线把小说变成能听的音频",
    "前台服务 + 可见通知",
    "只存有限片段、按 run 轮转",
    "App 自己不联网下载",
    "先跑一次「开始制作」",
    "同一个人的不同称呼点这里合并",
    "先定物理声学",
    "主 talker 决定",
    "这几项属于「仅本次任务」",
    "同一句文本、同一音色描述",
    "每次合成都会把完整 9 个参数",
    "回书架，随时能再进来看",
]

# ── ③ helper 说明参数置空（这些 helper 已有 isNotBlank 判断） ────────────
BLANK_LITERALS = [
    "方式和范围，只影响这一次，不写回设置",
    "长任务相关",
    "一般不用改",
    "决定整本怎么演",
    "写进导演指令的自然语言引导，不是精确倍速",
    "防止模型用力过猛",
    "段落之间留白",
    "手机上每次重试都要再跑几分钟",
    "只管文字怎么被理解：思考、上下文、线程、随机度",
    "只管声音怎么发出来：音色、采样、拟声、试音",
    "还没有思考记录，跑起来后这里会逐块出现。",
]

# ── ④ 精确串替换 / 删除 ──────────────────────────────────────────────
EXACT = [
    ("Text(p.why, fontSize = 10.5.sp, color = Sub)", "", "采样预设理由去注释"),
    ('Text("现成音色网格（移植 ReaderVoice 中文 persona 表）", fontSize = 12.sp,\n'
     '                    fontWeight = FontWeight.Bold)',
     'Text("音色网格", fontSize = 12.sp, fontWeight = FontWeight.Bold)', "音色网格标题去出处"),
    ('Text("清空角色表（不影响已导入的模型和书）", fontSize = 12.sp, color = Warn)',
     'Text("清空角色表", fontSize = 12.sp, color = Warn)', "清空按钮去括号注释"),
]

# ── 执行 ─────────────────────────────────────────────────────────────
def fail(msg):
    print("FAIL:", msg)
    sys.exit(1)


# 1) 校验 block 区间互不重叠、且每个 old 在原文唯一
edits = []
for a, b, new, label in BLOCKS:
    old = block(a, b)
    if raw.count(old) != 1:
        fail(f"[{label}] 原文命中 {raw.count(old)} 次，应为 1（{a}-{b}）")
    edits.append((a, b, old, new, label))
ends = sorted((a, b) for a, b, *_ in edits)
for (a1, b1), (a2, b2) in zip(ends, ends[1:]):
    if b1 >= a2:
        fail(f"block 区间重叠 {a1}-{b1} / {a2}-{b2}")

s = raw
for a, b, old, new, label in sorted(edits, key=lambda e: -e[0]):
    s = s.replace(old, new, 1)

# 2) 按短语定位并删除说明型 Text(...) 块
def text_blocks(src):
    out = []
    for m in re.finditer(r'\bText\s*\(', src):
        i = src.find('(', m.start())
        d = 0
        while i < len(src):
            if src[i] == '(':
                d += 1
            elif src[i] == ')':
                d -= 1
                if d == 0:
                    break
            i += 1
        out.append((m.start(), i + 1))
    return out


blocks_now = text_blocks(s)
drop_spans = []
seen = set()
for phrase in DROP_PHRASES:
    hits = [(a, b) for a, b in blocks_now if phrase in s[a:b]]
    # 嵌套时取最外层（跨度最大）
    if not hits:
        fail(f"DROP 短语未命中任何 Text 块：{phrase}")
    best = max(hits, key=lambda x: x[1] - x[0])
    if best in seen:
        fail(f"两个短语命中同一 Text 块：{phrase}")
    seen.add(best)
    a, b = best
    ls = s.rfind('\n', 0, a) + 1
    le = s.find('\n', b)
    if le < 0:
        le = len(s)
    before = s[ls:a]
    after = s[b:le]
    if before.strip() == '' and after.strip() == '':
        span = (ls, le + 1 if le + 1 <= len(s) else le)
    else:
        span = (a, b)
    drop_spans.append(span)

for a, b in sorted(drop_spans, reverse=True):
    s = s[:a] + s[b:]

# 3) helper 参数置空
for lit in BLANK_LITERALS:
    q = '"' + lit + '"'
    if s.count(q) != 1:
        fail(f"BLANK 字面量命中 {s.count(q)} 次：{lit}")
    s = s.replace(q, '""', 1)

# 4) 精确串
for old, new, label in EXACT:
    if s.count(old) != 1:
        fail(f"[{label}] 精确串命中 {s.count(old)} 次，应为 1")
    s = s.replace(old, new, 1)

# 5) 守恒断言：这些一律不许动
CONSERVED = [
    'private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, AUDIO, SETTINGS, MODELS, CAST, MIXER }',
    'BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }',
    'private fun chromeOf(s: Screen): ChromeSpec = when (s)',
    'var refresh by remember { mutableStateOf(0) }',
    'ProduceService',
    'Producer.start(',
    'statusBarsPadding()',
    'onMenu = { idx ->',
    'taskExpanded',
    'confirmDelete',
    'AlertDialog(',
]
for token in CONSERVED:
    if token not in s:
        fail(f"守恒项丢失：{token}")

# 结构平衡（去掉字符串与注释后粗查）
def strip_noise(src):
    src = re.sub(r'//[^\n]*', '', src)
    src = re.sub(r'/\*[\s\S]*?\*/', '', src)
    src = re.sub(r'"(?:\\.|[^"\\])*"', '""', src)
    return src


# 与原始文件比较「差值守恒」：Kotlin 模板字符串 / 注释里的括号会让绝对计数天然不等，
# 但只要改写没引入新的不配对，差值必须与原始一致。
raw_clean = strip_noise(raw)
clean = strip_noise(s)
for op, cl, name in [('(', ')', '圆括号'), ('{', '}', '花括号')]:
    d0 = raw_clean.count(op) - raw_clean.count(cl)
    d1 = clean.count(op) - clean.count(cl)
    if d0 != d1:
        fail(f"{name} 差值变化：原始 {d0} → 现在 {d1}")

MAIN.write_text(s, encoding="utf-8")
print("OK: MainActivity.kt 已改写")
print(f"  原始 {len(raw)} 字符 → 现在 {len(s)} 字符")
print(f"  结构块 {len(BLOCKS)} 处 · 删说明 Text {len(drop_spans)} 处 · 置空 helper 参数 {len(BLANK_LITERALS)} 处 · 精确替换 {len(EXACT)} 处")
