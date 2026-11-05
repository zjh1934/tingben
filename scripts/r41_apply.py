#!/usr/bin/env python3
"""R41 · 调音台 P1 六条：MainActivity.kt 精确替换。

原则：只切「// ── ⑥ 调音台」之后的区块；前面 1885 行（导航/书架/4B/native/前台服务）
一个字节不动。每处替换都断言 old 恰好出现 1 次，漏一处就整体停止、不写盘。
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
src = MAIN.read_text(encoding="utf-8")
orig_len = len(src)

# ── 工具 ────────────────────────────────────────────────────────────────
def rep(old: str, new: str, label: str):
    global src
    n = src.count(old)
    assert n == 1, f"[{label}] old 出现 {n} 次，应为 1 次，停止"
    src = src.replace(old, new, 1)
    print("  ok", label)

def cut(a: str, b: str, new: str, label: str):
    """替换 [a, b) 之间的内容，b 及其后原样保留。"""
    global src
    assert src.count(a) == 1, f"[{label}] 起点标记出现 {src.count(a)} 次，停止"
    i = src.index(a)
    j = src.index(b, i)
    assert j > i, f"[{label}] 终点标记在起点之前，停止"
    src = src[:i] + new + src[j:]
    print("  ok", label, f"({j - i} -> {len(new)} 字符)")

# ── R1 imports ──────────────────────────────────────────────────────────
rep("import androidx.compose.foundation.verticalScroll\n",
    "import androidx.compose.foundation.verticalScroll\n"
    "import androidx.compose.foundation.horizontalScroll\n"
    "import androidx.compose.foundation.text.KeyboardActions\n"
    "import androidx.compose.foundation.text.KeyboardOptions\n"
    "import androidx.compose.ui.layout.onGloballyPositioned\n"
    "import androidx.compose.ui.layout.positionInParent\n"
    "import androidx.compose.ui.text.input.ImeAction\n"
    "import androidx.compose.ui.text.input.KeyboardType\n",
    "R1 imports")

# ── R2 MixerSection 改受控 + 锚点 ───────────────────────────────────────
cut("@Composable\nprivate fun MixerSection(", "/** R40 · 时机提示统一组件",
'''@Composable
private fun MixerSection(
    title: String,
    key: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    badge: String = "",
    anchor: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val mod = Modifier.padding(bottom = 10.dp).then(
        if (anchor != null) Modifier.onGloballyPositioned { anchor(it.positionInParent().y.toInt()) }
        else Modifier
    )
    CardBox(mod) {
        Row(Modifier.fillMaxWidth().clickable { onToggle(!open) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = Primary,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (open) "收起" else "展开", fontSize = 11.5.sp, color = Primary)
        }
        if (open) {
            Spacer(Modifier.height(7.dp))
            content()
        }
    }
}

''', "R2 MixerSection")

# ── R3 三个共用组件（插在 TimingHint 之后 / MixerScreen 之前）────────────
rep('''@Composable
private fun MixerScreen(c: Context, onBack: () -> Unit) {''',
'''/** R41 · 锚点导航：抄 OfflineLLM ChatScreen 的列表滚动思路，本页用 ScrollState，不引 LazyColumn。 */
@Composable
private fun AnchorBar(items: List<Pair<String, String>>, onJump: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        items.forEach { (key, label) ->
            TextButton(onClick = { onJump(key) }, modifier = Modifier.padding(end = 2.dp)) {
                Text(label, fontSize = 11.sp, color = Primary)
            }
        }
    }
}

/** R41 · 九项紧凑行：抄 PocketPal CompletionSettings 的 TextInput + 官方默认回填形状。 */
@Composable
private fun ParamRow(
    spec: ParamSpec,
    cur: String,
    input: String,
    err: String,
    onInput: (String) -> Unit,
    onDone: () -> Unit,
    onPick: (String) -> Unit,
    onReset: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text("范围 ${spec.rangeLabel}${spec.unit}", fontSize = 9.5.sp, color = Sub)
        }
        Text("${spec.hint} · 官方默认 ${spec.def}", fontSize = 9.5.sp, color = Sub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            spec.options.forEach { o ->
                val target = if (o == "随机") "-1" else o
                val on = cur == target || (o == "随机" && (cur.toLongOrNull() ?: 0L) < 0L)
                TextButton(onClick = { onPick(target) }) {
                    Text(o, fontSize = 10.5.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Primary else Sub)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("默认", fontSize = 10.sp, color = Primary) }
        }
        OutlinedTextField(
            value = input, onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("精确值 ${spec.rangeLabel}${spec.unit}", fontSize = 9.5.sp) },
            isError = err.isNotBlank(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
        )
        if (err.isNotBlank()) Text(err, fontSize = 10.sp, color = Warn)
        HorizontalDivider(color = Line)
    }
}

/** R41 · 共用音色选择器：搜索 + 最近使用 + 网格，② 与 ⑤ 不再各写一套控件。 */
@Composable
private fun VoicePicker(
    query: String,
    onQuery: (String) -> Unit,
    recent: List<String>,
    selectedId: String,
    onPick: (VoicePreset) -> Unit
) {
    OutlinedTextField(
        value = query, onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("搜索音色（编号 / 名称 / 特征）", fontSize = 10.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.5.sp)
    )
    if (recent.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("最近使用", fontSize = 10.sp, color = Sub)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            recent.forEach { id ->
                val p = VoicePresets.ALL.firstOrNull { it.id == id } ?: return@forEach
                TextButton(onClick = { onPick(p) }) {
                    Text(p.name, fontSize = 10.5.sp,
                        color = if (p.id == selectedId) Primary else Sub)
                }
            }
        }
    }
    val list = VoicePresets.ALL.filter {
        query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true) ||
            it.chips().contains(query, true)
    }
    list.forEach { p ->
        Row(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${p.id} ${p.name}", fontSize = 11.5.sp,
                    fontWeight = if (p.id == selectedId) FontWeight.Bold else FontWeight.Normal)
                Text(p.chips(), fontSize = 10.sp, color = Sub)
            }
            Text(if (p.id == selectedId) "已选" else "选用", fontSize = 10.5.sp, color = Primary)
        }
    }
    if (list.isEmpty()) Text("没有匹配的音色", fontSize = 10.5.sp, color = Warn)
}

@Composable
private fun MixerScreen(c: Context, onBack: () -> Unit) {''', "R3 三个共用组件")

# ── R4 状态与两个小函数 ─────────────────────────────────────────────────
rep('''    var confirmDeleteId by remember { mutableStateOf("") }

    // R41a''',
'''    var confirmDeleteId by remember { mutableStateOf("") }
    // R41 · 锚点 / 展开态 / 音色选择器 / 预设三弹层（全部是界面态，不写生效态）
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<String, Int>() }
    var openMap by remember { mutableStateOf(Prefs.mixerOpenSections(c)) }
    var voiceQuery by remember { mutableStateOf("") }
    var recentVoices by remember { mutableStateOf(Prefs.recentVoices(c)) }
    var showPresetDiff by remember { mutableStateOf<MixerPreset?>(null) }
    var renamePresetId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var confirmDeletePresetId by remember { mutableStateOf("") }
    fun toggleSection(k: String, v: Boolean) {
        val next = if (v) openMap + k else openMap - k
        openMap = next
        Prefs.setMixerOpenSections(c, next)
    }
    fun jumpTo(k: String) { scope.launch { scroll.animateScrollTo(anchors[k] ?: 0) } }

    // R41a''', "R4 新状态")

# ── R5 预设构造抽成一份（diff 预览与实际载入共用）────────────────────────
cut('''    /** 载入预设只写 draft，绝不越过顶部状态条直接生效。 */
    fun loadPreset(p: MixerPreset) {''',
'''    /** 复用 R18 的全局播放内核''',
'''    /** R41 · 预设 → draft 只此一份构造，diff 预览与实际载入共用，不复制第二份拼串。 */
    fun buildPresetDraft(p: MixerPreset): MixerDraft = draft.copy(
        knobs = p.knobs,
        perf = p.perf,
        voice = if (p.voice.isBlank()) draft.voice else p.voice,
        dims = p.fields,
        dimText = p.fields.let { f ->
            VoicePreset("edit", "自定义", f.filter { it.value != "不指定" }, "").render()
        }
    )
    /** 载入预设只写 draft，绝不越过顶部状态条直接生效。 */
    fun loadPreset(p: MixerPreset) {
        draft = buildPresetDraft(p)
        dimEdited = true
        paramDrafts = emptyMap(); paramErr = emptyMap()
        Mixer.logPreset("载入预设为待提交", p)
        msg = "已把「${p.name}」载入待提交，按顶部「提交变更」生效"
    }
''', "R5 buildPresetDraft")

# ── R6 锚点条 + 滚动容器 ───────────────────────────────────────────────
rep('''        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)) {''',
'''        AnchorBar(listOf(
            "identity" to "身份", "sampling" to "采样", "perf" to "表演",
            "switches" to "开关", "casts" to "角色", "presets" to "预设", "trace" to "资料"
        )) { jumpTo(it) }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {''',
    "R6 锚点条")

# ── R7 首屏身份卡挂锚点 ────────────────────────────────────────────────
rep('''            // ── 固定首屏：已生效全文（只读） vs 草稿（可编辑）+ diff 高亮 ────
            CardBox(Modifier.padding(bottom = 10.dp)) {''',
'''            // ── 固定首屏：已生效全文（只读） vs 草稿（可编辑）+ diff 高亮 ────
            CardBox(Modifier.padding(bottom = 10.dp)
                .onGloballyPositioned { anchors["identity"] = it.positionInParent().y.toInt() }) {''',
    "R7 身份锚点")

# ── R8 九项区：三组紧凑表 ───────────────────────────────────────────────
cut('''            // ── ① 采样参数：9 项，行内改值只进 draft，删掉 9 个逐项提交按钮 ──''',
'''            // ── ② 身份生成器''',
'''            // ── 采样参数：三组紧凑表，改动只进 draft，生效权仍归顶部状态条 ──
            MixerSection("采样参数 · 9 项到引擎", "sampling",
                openMap.contains("sampling"), { toggleSection("sampling", it) },
                anchor = { anchors["sampling"] = it }) {
                Text(Mixer.CHAIN, fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(4.dp))
                val groups = listOf(
                    "主采样（5 项）" to listOf("temperature", "topK", "topP", "repetitionPenalty", "maxNewTokens"),
                    "副采样（3 项）" to listOf("subTemperature", "subTopK", "subTopP"),
                    "确定性（1 项）" to listOf("seed")
                )
                groups.forEach { (gname, keys) ->
                    Text(gname, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Primary,
                        modifier = Modifier.padding(top = 6.dp))
                    keys.mapNotNull { ParamCatalog.find(it) }.forEach { spec ->
                        val cur = spec.read(draft.knobs)
                        val shown = paramDrafts[spec.key] ?: cur
                        ParamRow(
                            spec = spec, cur = cur, input = shown,
                            err = paramErr[spec.key] ?: "",
                            onInput = { s ->
                                paramDrafts = paramDrafts + (spec.key to s)
                                val k = spec.withValue(draft.knobs, s)
                                paramErr = paramErr + (spec.key to (spec.validate(s) ?: ""))
                                if (k != null) draft = draft.copy(knobs = k)
                            },
                            onDone = {
                                paramDrafts = paramDrafts + (spec.key to spec.read(draft.knobs))
                                paramErr = paramErr - spec.key
                            },
                            onPick = { target ->
                                spec.withValue(draft.knobs, target)?.let { draft = draft.copy(knobs = it) }
                                paramDrafts = paramDrafts - spec.key
                                paramErr = paramErr - spec.key
                            },
                            onReset = {
                                spec.withValue(draft.knobs, spec.def)?.let { draft = draft.copy(knobs = it) }
                                paramDrafts = paramDrafts - spec.key
                                paramErr = paramErr - spec.key
                            }
                        )
                    }
                    TextButton(onClick = {
                        var k = draft.knobs
                        keys.forEach { key ->
                            ParamCatalog.find(key)?.let { s -> s.withValue(k, s.def)?.let { k = it } }
                        }
                        draft = draft.copy(knobs = k)
                        keys.forEach { key ->
                            paramDrafts = paramDrafts - key
                            paramErr = paramErr - key
                        }
                    }) { Text("恢复本组官方默认（待提交）", fontSize = 10.5.sp) }
                }
                TextButton(onClick = {
                    draft = draft.copy(knobs = TtsKnobs())
                    paramDrafts = emptyMap(); paramErr = emptyMap()
                }) { Text("全部恢复上游默认（待提交）", fontSize = 11.5.sp) }
                TimingHint(TIMING_NOW)
            }

''', "R8 九项紧凑表")

# ── R9 ② 网格 → 共用 VoicePicker ────────────────────────────────────────
cut('''                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                Text("现成音色网格（点一条填入草稿，不会自己生效）",''',
'''                TimingHint(TIMING_NOW)
            }

            // ── ③ 表演层''',
'''                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                Text("现成音色（选用后填入草稿，不会自己生效）",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                VoicePicker(voiceQuery, { voiceQuery = it }, recentVoices, "") { p ->
                    draft = draft.copy(voice = p.render(), dims = emptyMap(), dimText = p.render())
                    dimEdited = true
                    Prefs.pushRecentVoice(c, p.id)
                    recentVoices = Prefs.recentVoices(c)
                    EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM_DRAFT",
                        "网格 ${p.id} 填入草稿 · 未生效")
                }
''', "R9 VoicePicker")

# ── R10 分区调用签名 / 去编号 / 元信息收进资料区 ─────────────────────────
CALLS = [
    ('MixerSection("② 音色身份生成器（12 维 / 现成网格）", "identity", true, "填入草稿") {',
     'MixerSection("音色身份生成器", "identity",\n                openMap.contains("identity"), { toggleSection("identity", it) }) {'),
    ('MixerSection("③ 表演层 · 上层（下个任务起）", "perf", false, "不进模型 API") {',
     'MixerSection("表演层", "perf", openMap.contains("perf"), { toggleSection("perf", it) },\n                anchor = { anchors["perf"] = it }) {'),
    ('MixerSection("④ 音频开关 · 上层", "switches", false, "对所有新任务生效") {',
     'MixerSection("音频开关", "switches", openMap.contains("switches"),\n                { toggleSection("switches", it) }, anchor = { anchors["switches"] = it }) {'),
    ('MixerSection("⑤ 角色预设", "casts", false, "可增删改") {',
     'MixerSection("角色预设", "casts", openMap.contains("casts"), { toggleSection("casts", it) },\n                anchor = { anchors["casts"] = it }) {'),
    ('MixerSection("⑥ 预设库", "presets", false, "可自定义") {',
     'MixerSection("预设库", "presets", openMap.contains("presets"), { toggleSection("presets", it) },\n                anchor = { anchors["presets"] = it }) {'),
    ('MixerSection("⑧ 参数触达矩阵与官方资料", "trace", false, "只读 · O1~O7") {',
     'MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),\n                { toggleSection("trace", it) }, anchor = { anchors["trace"] = it }) {'),
]
for i, (old, new) in enumerate(CALLS):
    rep(old, new, f"R10.{i+1} 分区签名")

# ── R11 ⑤ 初始音色 → 共用 VoicePicker ───────────────────────────────────
rep('''                SettingOption("初始音色", VoicePresets.ALL.map { it.name },
                    VoicePresets.ALL.indexOfFirst { it.id == newCastGrid }.coerceAtLeast(0)) { i ->
                    newCastGrid = VoicePresets.ALL[i].id
                }''',
'''                Text("新角色的初始音色", fontSize = 11.sp, color = Sub)
                VoicePicker(voiceQuery, { voiceQuery = it }, recentVoices, newCastGrid) { p ->
                    newCastGrid = p.id
                    Prefs.pushRecentVoice(c, p.id)
                    recentVoices = Prefs.recentVoices(c)
                }''', "R11 初始音色合并")

# ── R12 预设区：对比并载入 / 重命名 / 删除确认 / 禁存 untitled ───────────
cut('''                presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(p.chips(), fontSize = 10.sp, color = Sub)
                        }
                        TextButton(onClick = { loadPreset(p) }) {
                            Text("载入为待提交", fontSize = 11.sp)
                        }
                        if (!p.builtin) {
                            TextButton(onClick = {
                                if (MixerPresetStore.delete(c, p.id)) {
                                    Mixer.logPreset("删除预设", p); tick++
                                    msg = "已删除「${p.name}」"
                                }
                            }) { Text("删除", fontSize = 11.sp, color = Warn) }
                        }
                    }
                }''',
'''                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                OutlinedTextField(
                    value = presetName''',
'''                presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(p.chips(), fontSize = 10.sp, color = Sub)
                        }
                        TextButton(onClick = { showPresetDiff = p }) {
                            Text("对比并载入", fontSize = 10.5.sp)
                        }
                        if (!p.builtin) {
                            if (p.name == "untitled") {
                                TextButton(onClick = { renamePresetId = p.id; renameText = "" }) {
                                    Text("重命名", fontSize = 10.sp, color = Warn)
                                }
                            }
                            TextButton(onClick = { confirmDeletePresetId = p.id }) {
                                Text("删除", fontSize = 10.5.sp, color = Warn)
                            }
                        }
                    }
                }
''', "R12 预设列表")

rep('''                        if (presetName.trim().isEmpty()) {
                            msg = "先填预设名再保存"
                        } else {''',
'''                        if (presetName.trim().isEmpty() ||
                            MixerPresetStore.safeName(presetName) == "untitled") {
                            msg = "先填个名字（不要用 untitled）"
                        } else {''', "R12 禁存 untitled")

# ── R13 ⑦ 导出诊断并入 ⑧，删掉独立一区 ──────────────────────────────────
cut('''            // ── ⑦ 降级为导出 / 诊断入口；全貌搬进「查看差异」抽屉 ──────────''',
'''            // ── ⑧ 触达矩阵''',
'''''', "R13 删 ⑦ 区")

rep('''                rows.filter { it.group == "边界" }.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(r.label, fontSize = 10.5.sp, color = Sub, modifier = Modifier.weight(1f))
                        Text(r.value, fontSize = 10.5.sp, modifier = Modifier.weight(1.2f))
                    }
                }
            }''',
'''                rows.filter { it.group == "边界" }.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(r.label, fontSize = 10.5.sp, color = Sub, modifier = Modifier.weight(1f))
                        Text(r.value, fontSize = 10.5.sp, modifier = Modifier.weight(1.2f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("分区元信息：采样 9 项可编辑 / 表演层与开关属上层 / 触达矩阵与官方索引只读",
                    fontSize = 10.sp, color = Sub)
                Spacer(Modifier.height(6.dp))
                Text("导出与诊断", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Text("已生效 v$INSTRUCT_TEMPLATE_VERSION · 指纹 $stamp · 合成并发 1 · 语言 chinese · 24kHz 单声道 WAV",
                    fontSize = 10.sp, color = Sub)
                TextButton(onClick = {
                    Mixer.logProfile(c, "手动导出当前配置到日志")
                    Toast.makeText(c, "已写进诊断日志，复制诊断信息即可贴出", Toast.LENGTH_SHORT).show()
                }) { Text("复制进诊断日志", fontSize = 12.sp) }
            }''', "R13 导出并入资料区")

# ── R14 三个新弹层（追加在 gridTargetId 弹层之后，函数收尾之前）──────────
rep('''            confirmButton = { TextButton(onClick = { gridTargetId = "" }) { Text("关闭") } }
        )
    }
}''',
'''            confirmButton = { TextButton(onClick = { gridTargetId = "" }) { Text("关闭") } }
        )
    }

    // ── R41 · 预设对比并载入：逐项「当前生效 → 该预设」再确认 ──────────────
    showPresetDiff?.let { p ->
        val next = buildPresetDraft(p)
        val pdiff = MixerCommit.diff(active, next)
        AlertDialog(
            onDismissRequest = { showPresetDiff = null },
            title = { Text("对比「${p.name}」", fontSize = 14.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(p.chips(), fontSize = 10.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    if (dirty) Text("会覆盖当前 ${changes.size} 项待提交改动；仍由顶部「提交变更」生效。",
                        fontSize = 10.5.sp, color = Warn)
                    Text(if (pdiff.isEmpty()) "与当前生效一致，没有差异" else "共 ${pdiff.size} 项差异",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    pdiff.forEach { ch ->
                        CardBox(Modifier.padding(vertical = 3.dp)) {
                            Text(ch.label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${ch.old} → ${ch.new}", fontSize = 11.sp, color = Primary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { loadPreset(p); showPresetDiff = null }) {
                    Text("载入为待提交", fontSize = 12.sp)
                }
            },
            dismissButton = { TextButton(onClick = { showPresetDiff = null }) { Text("取消") } }
        )
    }

    // ── R41 · 重命名历史 untitled 预设 ───────────────────────────────────
    if (renamePresetId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { renamePresetId = "" },
            title = { Text("重命名预设", fontSize = 14.sp) },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("新名字", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (MixerPresetStore.rename(c, renamePresetId, renameText)) {
                        msg = "已重命名预设"; tick++; renamePresetId = ""; renameText = ""
                    } else Toast.makeText(c, "名字为空、叫 untitled 或重名", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renamePresetId = "" }) { Text("取消") } }
        )
    }

    // ── R41 · 删除预设二次确认 ───────────────────────────────────────────
    if (confirmDeletePresetId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmDeletePresetId = "" },
            title = { Text("删除预设", fontSize = 14.sp) },
            text = { Text("确定删掉这个预设？只删库里这一条，不影响已生效配置。", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    if (MixerPresetStore.delete(c, confirmDeletePresetId)) { msg = "已删除预设"; tick++ }
                    confirmDeletePresetId = ""
                }) { Text("删除", fontSize = 12.sp, color = Warn) }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePresetId = "" }) { Text("取消") } }
        )
    }
}''', "R14 三个弹层")

# ── 全局守恒断言 ───────────────────────────────────────────────────────
assert src.startswith("package com.tingben.app"), "包声明被破坏"
assert src.count("fun MixerCommit") == 0, "误动 MixerCommit"
for must in ["fun commitAll()", "fun trial(", "MIXER_TRIAL", "试听未提交参数", "试听已生效参数",
             "MixerCommit.diff(active, draft)", "提交变更", "BackHandler(enabled = dirty)"]:
    assert must in src, "守恒丢失: " + must
for gone in ["① 采样参数", "② 音色身份生成器", "⑦ 降级为导出", "现成音色网格",
             'MixerSection("⑧', "SettingOption(\"初始音色\""]:
    assert gone not in src, "旧文案残留: " + gone
# 括号粗检（KDoc 收尾符与字符串里的括号会干扰，只做行数级粗查）
assert src.count("{") > src.count("}") - 2, "花括号明显失衡"

MAIN.write_text(src, encoding="utf-8")
print("完成：", orig_len, "->", len(src), "字符；总行数", src.count("\n") + 1)
