#!/usr/bin/env python3
"""R49 · 调音台 v5 重设计落地（只改界面层）。

硬约束（见 docs/R49_调音台v5_联动表.md）：
  - 只动 MainActivity.kt 的 AnchorBar / ParamRow / MixerScreen 三块
  - 不动 ParamSpec / Prefs.ttsKnobs / MixerCommit / trial 拼装 / native / 4B / 主链 / Gradle
  - 每条替换 old 唯一或按唯一 marker 切片；任一不符整体不写盘
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
COMMON = ROOT / "scripts/r45_common.py"
V41A = ROOT / "scripts/r41a_verify.py"
V47 = ROOT / "scripts/r47_verify.py"
CI = ROOT / ".github/workflows/ci.yml"


def replace_between(s, start, end, new, label):
    a = s.find(start)
    b = s.find(end, a + 1) if a >= 0 else -1
    if a < 0 or b <= a:
        sys.exit(f"{label}: 找不到唯一切片边界 ({a},{b})")
    if s.count(start) != 1:
        sys.exit(f"{label}: start marker 不唯一")
    return s[:a] + new + s[b:]


def sub(path, pairs):
    s = path.read_text(encoding="utf-8")
    orig = s
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path.name} 第 {i} 条替换命中 {n} 次（应为 1），整体不写盘")
        s = s.replace(old, new, 1)
    if s == orig:
        sys.exit(f"{path.name} 没有任何变化")
    path.write_text(s, encoding="utf-8")
    print(f"{path.name}: {len(pairs)} 条替换完成，{len(orig)} → {len(s)} 字符")


# ════════════════════════ ① AnchorBar：FlowRow 换行 + 当前区高亮 ════════════════════════
ANCHOR_NEW = '''/**
 * R49 · v5 第 2 条：锚点自动换行 + 当前区高亮。
 * R48 真机截图里 7 个标签被横向裁成「固…」；改用官方 FlowRow + FilterChip，
 * 不再用 horizontalScroll 藏选项。当前区由 MixerScreen 传入，不从滚动位置猜。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnchorBar(
    items: List<Pair<String, String>>,
    current: String,
    onJump: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { (key, label) ->
            FilterChip(
                selected = key == current,
                onClick = { onJump(key) },
                label = { Text(label, fontSize = 11.sp) }
            )
        }
    }
}

'''

# ════════════════════════ ② ParamRow：一行一项 ════════════════════════
PARAMROW_NEW = '''/**
 * R49 · v5 第 4 条：一行一项（中文名 · 滑块 · 小数字框 · ⟳）。
 * 常用值、人话说明、官方字段收进该行展开的 ⓘ；数字框就是精确值输入，
 * 不再画第二个框。R47 的滑块双模逐行保留：滑块与数字框共用 onInput，
 * 范围只认 ParamSpec 唯一来源。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamRow(
    spec: ParamSpec,
    cur: String,
    input: String,
    err: String,
    onInput: (String) -> Unit,
    onDone: () -> Unit,
    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onRoll: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, fontSize = 10.5.sp, modifier = Modifier.width(84.dp))
            // R47 · v4 预览 ⑥ 落地：滑块与精确值框是同一个值，改动只进草稿。
            // 滑块只覆盖常用区间（seed 无上界时取 2000）；任意合法值仍可直接在框里填。
            val sliderLo = (spec.min ?: 0.0).toFloat()
            val sliderHi = (spec.max ?: if (spec.key == "seed") 2000.0 else 1000.0).toFloat()
            val intLike = spec.key == "seed" || spec.key == "maxNewTokens" ||
                spec.key == "topK" || spec.key == "subTopK"
            val sliderVal = (input.toFloatOrNull() ?: cur.toFloatOrNull() ?: sliderLo)
                .coerceIn(sliderLo, sliderHi)
            Slider(
                value = sliderVal,
                onValueChange = { v ->
                    val s = if (intLike) v.toLong().toString()
                    else "%.2f".format(v).trimEnd('0').trimEnd('.')
                    onInput(s)
                },
                valueRange = sliderLo..sliderHi,
                modifier = Modifier.weight(1f).height(30.dp)
            )
            OutlinedTextField(
                value = input, onValueChange = onInput,
                modifier = Modifier.width(74.dp), singleLine = true,
                label = { Text("精确值", fontSize = 8.sp) },
                isError = err.isNotBlank(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
            TextButton(onClick = onReset, modifier = Modifier.width(28.dp)) {
                Text("⟳", fontSize = 12.sp, color = Sub)
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.width(26.dp)) {
                Text(if (expanded) "▴" else "▾", fontSize = 11.sp, color = Sub)
            }
        }
        if (err.isNotBlank()) Text(err, fontSize = 10.sp, color = Warn)
        if (expanded) {
            HelpNote("参数说明",
                "${spec.hint} · 官方默认 ${spec.def} · 字段 ${spec.official} · 范围 ${spec.rangeLabel}${spec.unit}")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                spec.options.forEach { o ->
                    val target = if (o == "随机") "-1" else o
                    val on = cur == target || (o == "随机" && (cur.toLongOrNull() ?: 0L) < 0L)
                    FilterChip(
                        selected = on,
                        onClick = { onPick(target) },
                        label = { Text(o, fontSize = 10.sp) }
                    )
                }
            }
            onRoll?.let { roll ->
                TextButton(onClick = roll) {
                    Text("⟳ 换一个固定种子（仅草稿）", fontSize = 10.5.sp, color = Primary)
                }
            }
            TextButton(onClick = onReset) { Text("⟳ 单项恢复默认", fontSize = 10.5.sp, color = Sub) }
        }
        HorizontalDivider(color = Line)
    }
}

'''

# ════════════════════════ ③ 顶部：细状态条 ════════════════════════
TOP_NEW = '''        // ── 吸顶状态条（R49 · v5 第 1 条：只留返回 / 状态 / 查看差异 / 提交）──
        // 试听与试听文本已移进默认折叠的「试听」区，不再占首屏 40%。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { if (dirty) showLeave = true else onBack() }) {
                Text("‹ 返回", fontSize = 11.5.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("已生效 v$INSTRUCT_TEMPLATE_VERSION",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(if (dirty) "未提交 ${changes.size} 项" else "无待提交变更",
                    fontSize = 10.5.sp, color = if (dirty) Warn else Good)
            }
            TextButton(onClick = { showDiff = true }) { Text("查看差异", fontSize = 11.sp) }
            Button(onClick = { commitAll() }, enabled = dirty) {
                Text("提交变更", fontSize = 11.sp)
            }
        }
        if (msg.isNotBlank() || dirty) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (msg.isNotBlank()) Text(msg, fontSize = 10.sp, color = Sub)
                if (dirty) {
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { discardAll() }) {
                        Text("丢弃全部待提交", fontSize = 10.sp, color = Warn)
                    }
                }
            }
        }
        HorizontalDivider(color = Line)

'''

# ════════════════════════ ④ 锚点调用：7 项 ════════════════════════
ANCHOR_CALL_NEW = '''        AnchorBar(listOf(
            "identity" to "身份", "sampling" to "采样", "perf" to "表演",
            "casts" to "角色", "presets" to "预设",
            "fixedVoice" to "固定音色", "mapping" to "高级"
        ), activeAnchor) { jumpTo(it) }
'''

# ════════════════════════ ⑤ 身份卡：摘要 / 展开 / 逐维 diff ════════════════════════
IDENTITY_NEW = '''            // ── R49 · v5 第 3 条：默认摘要，全文与编辑按需展开，只显改动 ──
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .onGloballyPositioned { anchors["identity"] = it.positionInParent().y.toInt() }) {
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音色身份", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("可调 · 全书固定", fontSize = 10.sp, color = Sub)
                    Spacer(Modifier.weight(1f))
                    if (dirty) Text("未提交 ${changes.size} 项", fontSize = 10.sp, color = Warn)
                }
                Spacer(Modifier.height(5.dp))
                Text("当前生效 · 全书固定（只读）", fontSize = 10.5.sp, color = Sub)
                val activeVoice = active.voice.trim().ifBlank { DEFAULT_IDENTITY }
                Text(
                    if (showVoiceFull) activeVoice
                    else activeVoice.take(38) + "…（共 ${activeVoice.length} 字）",
                    fontSize = 11.5.sp, modifier = Modifier.background(PrimarySoft)
                )
                TextButton(onClick = { showVoiceFull = !showVoiceFull }) {
                    Text(if (showVoiceFull) "收起全文" else "展开全文 ›", fontSize = 10.5.sp)
                }
                val vdiff = voiceFieldDiff(activeVoice, draft.voice.trim().ifBlank { DEFAULT_IDENTITY })
                if (vdiff.isNotEmpty()) {
                    Text("改动 ${vdiff.size} 处", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    vdiff.take(4).forEach { (x, y) ->
                        Text("$x → $y", fontSize = 10.5.sp, color = Warn)
                    }
                } else if (voiceChange != null) {
                    Text("已改：${voiceChange.old.take(32)} → ${voiceChange.new.take(32)}",
                        fontSize = 10.5.sp, color = Warn)
                }
                TextButton(onClick = { showVoiceEdit = !showVoiceEdit }) {
                    Text(if (showVoiceEdit) "收起编辑" else "编辑描述 ›",
                        fontSize = 10.5.sp, color = Primary)
                }
                if (showVoiceEdit) {
                    OutlinedTextField(
                        value = draft.voice, onValueChange = { draft = draft.copy(voice = it) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                        label = { Text("编辑音色描述（草稿）", fontSize = 11.sp) },
                        placeholder = { Text("例如：温和的中年女声，音区偏低，吐字清晰，语速偏慢，无明显口音", fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp)
                    )
                    Spacer(Modifier.height(5.dp))
                    TextButton(onClick = { draft = draft.copy(voice = "") }) {
                        Text("清空回默认（待提交）", fontSize = 11.sp)
                    }
                } else {
                    Text(draft.voice.trim().ifBlank { DEFAULT_IDENTITY }.take(38) + "…（草稿）",
                        fontSize = 10.5.sp, color = Sub)
                }
                Text("固定音色 = 这段描述 + 种子 + 9 项采样全书不变。", fontSize = 10.sp, color = Sub)
                TextButton(onClick = {
                    toggleSection("fixedVoice", true)
                    activeAnchor = "fixedVoice"
                    scope.launch { scroll.animateScrollTo(anchors["fixedVoice"] ?: 0) }
                }) { Text("固定音色是什么？ ›", fontSize = 10.5.sp, color = Primary) }
                TimingHint(TIMING_NOW)
            }

'''

# ════════════════════════ ⑥ 试听折叠区 + 辅助设置分组标题 ════════════════════════
TRIAL_SECTION_NEW = '''            // ── R49 · 试听：默认折叠的两态卡，提交不依赖试听 ──────────────
            MixerSection("试听", "trial",
                openMap.contains("trial"), { toggleSection("trial", it) },
                badge = "两态", anchor = { anchors["trial"] = it }) {
                Text("改完直接提交，不必先试听。", fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                val canTrial = missing.isEmpty() && !busyDraftTrial && !busyActiveTrial
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        trial("未提交参数", draft, "mixer_trial_draft.wav") { busyDraftTrial = it }
                    }, enabled = canTrial, modifier = Modifier.weight(1f)) {
                        Text(if (busyDraftTrial) "合成中…" else "试听未提交参数", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        trial("已生效参数", active, "mixer_trial_active.wav") { busyActiveTrial = it }
                    }, enabled = canTrial, modifier = Modifier.weight(1f)) {
                        Text(if (busyActiveTrial) "合成中…" else "试听已生效参数", fontSize = 11.sp)
                    }
                }
                OutlinedTextField(
                    value = trialText, onValueChange = { trialText = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("试听文本", fontSize = 10.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
                )
                if (trialMsg.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(trialMsg, fontSize = 10.5.sp, color = Sub)
                }
                if (missing.isNotEmpty()) {
                    Text("还缺模型文件，先去设置页导入。", fontSize = 10.5.sp, color = Warn)
                }
            }

            // ── R49 · 三态分区：上层项统一挂徽标，默认折叠 ────────────────
            Text("辅助设置 · 上层（能改，但不是模型字段）", fontSize = 10.5.sp, color = Sub,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))

'''

# ════════════════════════ 应用 ════════════════════════
s = MAIN.read_text(encoding="utf-8")
orig = s

s = replace_between(s, "/** R41 · 锚点导航", "/** R41 · 九项紧凑行", ANCHOR_NEW, "AnchorBar")
s = replace_between(s, "/** R41 · 九项紧凑行", "/** R41 · 共用音色选择器", PARAMROW_NEW, "ParamRow")
s = replace_between(s, "        // ── 吸顶状态条", "        AnchorBar(listOf(", TOP_NEW, "顶部细状态条")
s = replace_between(s, "        AnchorBar(listOf(", "        Spacer(Modifier.height(4.dp))",
                    ANCHOR_CALL_NEW, "锚点调用")
s = replace_between(s, "            // ── 固定首屏：", "            // ── 采样参数：",
                    IDENTITY_NEW, "身份卡")

# 试听区插在采样组之后、12 维生成器之前
marker = "            // ── 音色身份生成器"
if s.count(marker) != 1:
    sys.exit("试听区插入点不唯一")
s = s.replace(marker, TRIAL_SECTION_NEW + marker, 1)

# 新增界面态 + 逐维 diff 局部函数
old_state = "    var showDiff by remember { mutableStateOf(false) }"
new_state = '''    var showDiff by remember { mutableStateOf(false) }
    // R49 · v5：锚点当前区、身份全文/编辑两个展开态（纯界面态，不写生效态）
    var activeAnchor by remember { mutableStateOf("identity") }
    var showVoiceFull by remember { mutableStateOf(false) }
    var showVoiceEdit by remember { mutableStateOf(true) }'''
if s.count(old_state) != 1:
    sys.exit("界面态插入点不唯一")
s = s.replace(old_state, new_state, 1)

old_commit = "    /** 全页唯一生效动作。写完重读真值源，保留生成器工作区（dims/dimText）。 */"
new_commit = '''    /** R49 · 身份描述逐维差异：按「；」拆段比不同项，只给界面高亮，不参与提交判定。 */
    fun voiceFieldDiff(old: String, new: String): List<Pair<String, String>> {
        fun parts(x: String) = x.split("；", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val a = parts(old)
        val b = parts(new)
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: "（空）"
            val y = b.getOrNull(i) ?: "（空）"
            if (x != y) out.add(x to y)
        }
        return out
    }

''' + old_commit
if s.count(old_commit) != 1:
    sys.exit("diff 函数插入点不唯一")
s = s.replace(old_commit, new_commit, 1)

# jumpTo：先展开目标折叠区再滚
old_jump = "    fun jumpTo(k: String) { scope.launch { scroll.animateScrollTo(anchors[k] ?: 0) } }"
new_jump = '''    fun jumpTo(k: String) {
        activeAnchor = k
        // R49 · v5 第 6 条：锚点点击先展开目标折叠区，再滚过去
        if (k !in openMap) toggleSection(k, true)
        scope.launch { scroll.animateScrollTo(anchors[k] ?: 0) }
    }'''
if s.count(old_jump) != 1:
    sys.exit("jumpTo 替换点不唯一")
s = s.replace(old_jump, new_jump, 1)

# 三态徽标（只加在既有调用的参数尾部，前三个参数原样保留）
s = sub_badges = s
BADGES = [
    ('                openMap.contains("identity"), { toggleSection("identity", it) }) {',
     '                openMap.contains("identity"), { toggleSection("identity", it) },\n                badge = "上层") {'),
    ('MixerSection("表演层", "perf", openMap.contains("perf"), { toggleSection("perf", it) },\n                anchor =',
     'MixerSection("表演层", "perf", openMap.contains("perf"), { toggleSection("perf", it) },\n                badge = "上层", anchor ='),
    ('MixerSection("音频开关", "switches", openMap.contains("switches"),\n                { toggleSection("switches", it) }, anchor =',
     'MixerSection("音频开关", "switches", openMap.contains("switches"),\n                { toggleSection("switches", it) }, badge = "上层", anchor ='),
    ('MixerSection("角色预设", "casts", openMap.contains("casts"), { toggleSection("casts", it) },\n                anchor =',
     'MixerSection("角色预设", "casts", openMap.contains("casts"), { toggleSection("casts", it) },\n                badge = "配置", anchor ='),
    ('MixerSection("预设库", "presets", openMap.contains("presets"), { toggleSection("presets", it) },\n                anchor =',
     'MixerSection("预设库", "presets", openMap.contains("presets"), { toggleSection("presets", it) },\n                badge = "配置", anchor ='),
    ('MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),\n                { toggleSection("trace", it) }, anchor =',
     'MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),\n                { toggleSection("trace", it) }, badge = "只读", anchor ='),
]
for i, (old, new) in enumerate(BADGES, 1):
    n = s.count(old)
    if n != 1:
        sys.exit(f"徽标第 {i} 条命中 {n} 次（应为 1），整体不写盘")
    s = s.replace(old, new, 1)

# 底部留白：40dp 保留（既有断言）+ 8dp = 48dp
old_spacer = "            Spacer(Modifier.height(40.dp))"
new_spacer = "            Spacer(Modifier.height(40.dp))\n            // R49 · v5 第 7 条：补 8dp 覆盖三键 48dp / 手势白条 24dp\n            Spacer(Modifier.height(8.dp))"
if s.count(old_spacer) != 1:
    sys.exit("底部 spacer 替换点不唯一")
s = s.replace(old_spacer, new_spacer, 1)

if s == orig:
    sys.exit("MainActivity 没有任何变化")
MAIN.write_text(s, encoding="utf-8")
print(f"MainActivity.kt: {len(orig)} → {len(s)} 字符（v5 界面层全部落地）")

# ════════════════════════ ⑦ 同步既有验证脚本 ════════════════════════
sub(COMMON, [
    ("""    # R47 · ParamRow 允许改（滑块双模），由 r47_verify.py 做逐行对拍；
    # 其余 App() 之外的业务代码仍与 HEAD 逐行守恒。
    a = t.find("private fun ParamRow(")
    b = t.find("/** R41 · 共用音色选择器", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    return [x.rstrip() for x in t.splitlines()
            if x.strip() and not x.startswith("import ")]""",
     """    # R47 · ParamRow 允许改（滑块双模），由 r47_verify.py 做逐行对拍；
    # 其余 App() 之外的业务代码仍与 HEAD 逐行守恒。
    a = t.find("private fun ParamRow(")
    b = t.find("/** R41 · 共用音色选择器", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    # R49 · v5 重排 AnchorBar 与整个 MixerScreen（细状态条 / 身份卡 / 试听折叠区）。
    # 形态由 r49_verify.py 逐项断言；这里剔除调音台整段，其余业务代码仍逐行守恒。
    a = t.find("private fun AnchorBar(")
    b = t.find("/** R41 · 九项紧凑行", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    a = t.find("private fun MixerScreen(")
    if a >= 0:
        t = t[:a]
    return [x.rstrip() for x in t.splitlines()
            if x.strip() and not x.startswith("import ")]"""),
])

sub(V41A, [
    ("""    # 两个按钮必须都在吸顶状态条那一张 CardBox 内（返回也在其中）
    i = m.find("吸顶状态条")
    j = m.find("Column(Modifier.weight(1f).verticalScroll", i)
    bar = m[i:j] if i >= 0 and j > i else ""
    for s in ["‹ 返回", "提交变更", "试听未提交参数", "试听已生效参数"]:
        if s not in bar: e.append("不在同一吸顶模块: " + s)""",
     """    # R49 · v5：细状态条只留返回 / 状态 / 查看差异 / 提交；
    # 两态试听移进默认折叠的「试听」区，提交不依赖试听。
    i = m.find("吸顶状态条")
    j = m.find("Column(Modifier.weight(1f).verticalScroll", i)
    bar = m[i:j] if i >= 0 and j > i else ""
    for s in ["‹ 返回", "提交变更", "查看差异"]:
        if s not in bar: e.append("不在细状态条: " + s)
    t = m.find('MixerSection("试听", "trial"')
    u = m.find("// ── 音色身份生成器", t)
    trial_card = m[t:u] if t >= 0 and u > t else ""
    for s in ["试听未提交参数", "试听已生效参数", "试听文本", "改完直接提交，不必先试听。"]:
        if s not in trial_card: e.append("两态试听折叠区缺: " + s)"""),
])

sub(V47, [
    ("""        chk(canon(stripped) == old_pr, "ParamRow 除滑块块/HelpNote 外被顺手改动")""",
     """        # R49 · v5 第 4 条重排了 ParamRow（一行一项），逐行对拍移交 r49_verify.py；
        # 这里只核 R47 滑块双模的 6 个关键表达式仍在 ParamRow 内。
        for expr in ["val sliderLo = (spec.min ?: 0.0).toFloat()",
                     "val sliderHi = (spec.max ?: if (spec.key == \\"seed\\") 2000.0 else 1000.0).toFloat()",
                     "valueRange = sliderLo..sliderHi", "onInput(s)", "Slider("]:
            chk(expr in new_pr, "R47 滑块双模被 R49 改坏: " + expr)"""),
])

sub(CI, [
    ("      - name: JDK 17\n",
     "      - name: R49 调音台 v5 重设计验证（细状态条 / 锚点换行 / 紧凑行 / 反向注入 / 守恒）\n"
     "        run: python3 scripts/r49_verify.py\n\n"
     "      - name: JDK 17\n"),
])
print("R49 应用完成")
