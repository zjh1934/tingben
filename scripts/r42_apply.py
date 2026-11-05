#!/usr/bin/env python3
"""R42 P2 · 调音台文案/响应式/视觉：精确切片替换，一处不匹配就整体退出。

只动 MainActivity.kt 的调音台区块与文件头 import；其余文件一行不碰。
用法: python3 scripts/r42_apply.py
"""
import pathlib, sys, shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent
P = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
src = P.read_text(encoding="utf-8")
backup = ROOT.parent / "MainActivity.before_r42.kt"
if not backup.exists():
    shutil.copyfile(P, backup)

# (说明, 原文, 新文, 期望出现次数)
R = []

R.append(("imports", """import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions""",
"""import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.tooling.preview.Preview""", 1))

R.append(("MixerSection 去阴影", """@Composable
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
}""",
"""@Composable
private fun MixerSection(
    title: String,
    key: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    badge: String = "",
    anchor: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // R42 · 细分割线 + 留白替代嵌套阴影；分区标题与展开动作改中性色。
    val mod = Modifier.padding(bottom = 4.dp).then(
        if (anchor != null) Modifier.onGloballyPositioned { anchor(it.positionInParent().y.toInt()) }
        else Modifier
    )
    Column(mod.fillMaxWidth()) {
        HorizontalDivider(color = Line)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { onToggle(!open) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = Sub,
                    modifier = Modifier.padding(end = 8.dp))
            }
            Text(if (open) "收起" else "展开", fontSize = 11.5.sp, color = Sub)
        }
        if (open) {
            Spacer(Modifier.height(7.dp))
            content()
        }
        Spacer(Modifier.height(6.dp))
    }
}""", 1))

NEW_WIDGETS = """/** R42 · help 说明：默认只留一个图标 + 标题，点开才看正文（评审 16）。 */
@Composable
private fun HelpNote(title: String, body: String) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically) {
        Text("ⓘ", fontSize = 12.sp, color = Sub)
        Spacer(Modifier.width(5.dp))
        Text(title, fontSize = 10.sp, color = Sub, modifier = Modifier.weight(1f))
        Text(if (open) "收起" else "查看", fontSize = 10.sp, color = Sub)
    }
    if (open) {
        Text(body, fontSize = 10.sp, color = Sub,
            modifier = Modifier.padding(start = 17.dp, bottom = 3.dp))
    }
}

/**
 * R42 · 标签在上、选项 chip 自动换行：官方 FlowRow + FilterChip，不是手搓的横向 Row。
 * 375px 下长选项（如「高（明显气声）」「略带江浙口音」）整块换到下一行，不挤成竖排碎字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    label: String,
    hint: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        HelpNote("可选值", hint)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { o ->
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    label = { Text(o, fontSize = 10.5.sp) }
                )
            }
        }
        HorizontalDivider(color = Line)
    }
}

/** R42 · 375px 窄屏证据：只画两条最长选项的 chip 行，不引新依赖。 */
@Preview(widthDp = 375, showBackground = true)
@Composable
private fun ChipRowNarrowPreview() {
    Column(Modifier.padding(8.dp)) {
        ChipRow("气息", "低 / 中 / 高（气声）",
            VoiceDimension.BREATH.options, "不指定") {}
        ChipRow("口音", "普通话 / 方言 / 无",
            VoiceDimension.ACCENT.options, "不指定") {}
    }
}

/** R42 · 开关统一带「开 / 关」文字与 info（评审 15）。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, timing: String,
                      onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp)
            HelpNote("什么时候生效", timing)
        }
        Text(if (checked) "开" else "关", fontSize = 11.sp,
            color = if (checked) Good else Sub)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * R42 · 开发者资料：默认收起。字段名 / 文档坐标 / 指纹只在展开态出现（评审 13）。
 * 内容全是只读展示，不写任何生效态，不影响 MixerDraft / commit。
 */
@Composable
private fun AdvancedDrawer(rows: List<MixerRow>, stamp: String) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
    Row(Modifier.fillMaxWidth().clickable { open = !open },
        verticalAlignment = Alignment.CenterVertically) {
        Text("高级 · 开发者资料", fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text(if (open) "收起" else "展开", fontSize = 11.sp, color = Sub)
    }
    if (open) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            var last = ""
            Mixer.coverage().forEach { (g, item, kind) ->
                if (g != last) {
                    last = g
                    Text(g, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = Sub, modifier = Modifier.padding(top = 5.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(item, fontSize = 10.5.sp, color = Sub, modifier = Modifier.weight(1f))
                    Text(kind, fontSize = 10.5.sp,
                        color = when (kind) { "可编辑" -> Good; "上层" -> Warn; else -> Sub })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("触达链路", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            Text("UI → Prefs.ttsKnobs → NativeEngine.ttsSynthesize → JNI → qt_tts_params → qt_synthesize",
                fontSize = 10.sp, color = Sub)
            Text("日志核对：TTS_PARAMS（界面传出） / TTS_PARAMS_APPLIED（引擎实收）",
                fontSize = 10.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            Text("官方资料（按名称自行查；App 无联网权限，不摆假链接）",
                fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            ParamCatalog.DOCS.forEach { (name, where) ->
                Spacer(Modifier.height(4.dp))
                Text(name, fontSize = 10.5.sp, color = Sub)
                Text(where, fontSize = 10.sp, color = Sub)
            }
            Spacer(Modifier.height(8.dp))
            rows.filter { it.group == "边界" }.forEach { r ->
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
        }
    }
}
// R42_ADVANCED_END

"""
R.append(("新增 4 个组件", """/** R40 · 时机提示统一组件（评审第 17 条：别每处各写一种说法）。 */""",
           NEW_WIDGETS + """/** R40 · 时机提示统一组件（评审第 17 条：别每处各写一种说法）。 */""", 1))

R.append(("AnchorBar 中性色", """                Text(label, fontSize = 11.sp, color = Primary)""",
          """                Text(label, fontSize = 11.sp, color = Sub)""", 1))

R.append(("ParamRow 组名去主色", """                    Text(gname, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Primary,
                        modifier = Modifier.padding(top = 6.dp))""",
          """                    Text(gname, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp))""", 1))

R.append(("ParamRow 说明降级 help", """        Text("${spec.hint} · 官方默认 ${spec.def}", fontSize = 9.5.sp, color = Sub)""",
          """        HelpNote("参数说明", "${spec.hint} · 官方默认 ${spec.def}")""", 1))

R.append(("ParamRow 默认按钮中性", """            TextButton(onClick = onReset) { Text("默认", fontSize = 10.sp, color = Primary) }""",
          """            TextButton(onClick = onReset) { Text("默认", fontSize = 10.sp, color = Sub) }""", 1))

R.append(("VoicePicker 选用中性", """            Text(if (p.id == selectedId) "已选" else "选用", fontSize = 10.5.sp, color = Primary)""",
          """            Text(if (p.id == selectedId) "已选" else "选用", fontSize = 10.5.sp)""", 1))

R.append(("吸顶条去指纹", """                    Text("已生效 v$INSTRUCT_TEMPLATE_VERSION · 指纹 ${stamp.take(8)}",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)""",
          """                    Text("已生效 v$INSTRUCT_TEMPLATE_VERSION",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)""", 1))

R.append(("提交提示去指纹", """        msg = "已提交 $n 项变更 · 指纹 ${after.take(8)}\"""",
          """        msg = "已提交 $n 项变更\"""", 1))

R.append(("首屏身份卡去阴影", """            CardBox(Modifier.padding(bottom = 10.dp)
                .onGloballyPositioned { anchors["identity"] = it.positionInParent().y.toInt() }) {
                Text("音色身份", fontSize = 15.sp, fontWeight = FontWeight.Bold)""",
          """            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)
                .onGloballyPositioned { anchors["identity"] = it.positionInParent().y.toInt() }) {
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(8.dp))
                Text("音色身份", fontSize = 15.sp, fontWeight = FontWeight.Bold)""", 1))

R.append(("采样链降级 help", """                Text(Mixer.CHAIN, fontSize = 10.5.sp, color = Sub)""",
          """                HelpNote("采样顺序", Mixer.CHAIN)""", 1))

R.append(("12 维说明人话化", """                Text("12 维是帮你写 instruct 的拼装模板，不是模型字段（ReaderVoice 096b_personas.py，MIT）。",
                    fontSize = 10.5.sp, color = Sub)""",
          """                HelpNote("12 维是什么", "按维度挑一挑，自动拼成音色描述；只是帮你写字，不是模型字段。")""", 1))

R.append(("12 维改 ChipRow", """                VoiceDimension.entries.forEach { d ->
                    SettingOption(d.label, d.options,
                        d.options.indexOf(draft.dims[d.name] ?: "不指定").coerceAtLeast(0)) { i ->
                        val v = d.options[i]
                        draft = draft.copy(dims = if (v == "不指定") draft.dims - d.name
                        else draft.dims + (d.name to v))
                        if (!dimEdited) draft = draft.copy(dimText = "")
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM",
                            "${d.name}=${v} · 已选 ${draft.dims.size} 维 · 仅草稿")
                    }
                }""",
          """                VoiceDimension.entries.forEach { d ->
                    ChipRow(d.label, d.hint, d.options,
                        draft.dims[d.name] ?: "不指定") { v ->
                        draft = draft.copy(dims = if (v == "不指定") draft.dims - d.name
                        else draft.dims + (d.name to v))
                        if (!dimEdited) draft = draft.copy(dimText = "")
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM",
                            "${d.name}=${v} · 已选 ${draft.dims.size} 维 · 仅草稿")
                    }
                }""", 1))

R.append(("现成音色标题去第二套说法", """                Text("现成音色（选用后填入草稿，不会自己生效）",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold)""",
          """                Text("现成音色",
                    fontSize = 11.5.sp, fontWeight = FontWeight.Bold)""", 1))

R.append(("表演层 5 项统一 ChipRow", """                SettingOption("朗读档位", ReadingStyle.entries.map { it.label },
                    ReadingStyle.entries.indexOf(draft.perf.style).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(style = ReadingStyle.entries[i]))
                }
                SettingOption("语速倾向", Pace.entries.map { it.label },
                    Pace.entries.indexOf(draft.perf.pace).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(pace = Pace.entries[i]))
                }
                SettingOption("情绪强度", Intensity.entries.map { it.label },
                    Intensity.entries.indexOf(draft.perf.intensity).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(intensity = Intensity.entries[i]))
                }
                SettingOption("段间静音", ProduceConfig.GAP_OPTIONS.map { "${it}ms" },
                    ProduceConfig.GAP_OPTIONS.indexOf(draft.perf.gapMs).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(gapMs = ProduceConfig.GAP_OPTIONS[i]))
                }
                SettingOption("失败重做", ProduceConfig.RETRY_OPTIONS.map { "${it} 次" },
                    ProduceConfig.RETRY_OPTIONS.indexOf(draft.perf.gateRetries).coerceAtLeast(0)) { i ->
                    draft = draft.copy(perf = draft.perf.copy(gateRetries = ProduceConfig.RETRY_OPTIONS[i]))
                }""",
          """                ChipRow("朗读档位", "叙事 / 对白 / 广播剧",
                    ReadingStyle.entries.map { it.label }, draft.perf.style.label) { v ->
                    ReadingStyle.entries.firstOrNull { it.label == v }?.let {
                        draft = draft.copy(perf = draft.perf.copy(style = it))
                    }
                }
                ChipRow("语速倾向", "慢 / 正常 / 快，只写稳定倾向",
                    Pace.entries.map { it.label }, draft.perf.pace.label) { v ->
                    Pace.entries.firstOrNull { it.label == v }?.let {
                        draft = draft.copy(perf = draft.perf.copy(pace = it))
                    }
                }
                ChipRow("情绪强度", "克制 / 标准 / 饱满",
                    Intensity.entries.map { it.label }, draft.perf.intensity.label) { v ->
                    Intensity.entries.firstOrNull { it.label == v }?.let {
                        draft = draft.copy(perf = draft.perf.copy(intensity = it))
                    }
                }
                ChipRow("段间静音", "合并时插入空白，不改正文",
                    ProduceConfig.GAP_OPTIONS.map { "${it}ms" }, "${draft.perf.gapMs}ms") { v ->
                    v.removeSuffix("ms").toIntOrNull()?.let {
                        draft = draft.copy(perf = draft.perf.copy(gapMs = it))
                    }
                }
                ChipRow("失败重做", "标注不通过时重试次数",
                    ProduceConfig.RETRY_OPTIONS.map { "${it} 次" }, "${draft.perf.gateRetries} 次") { v ->
                    v.removeSuffix(" 次").toIntOrNull()?.let {
                        draft = draft.copy(perf = draft.perf.copy(gateRetries = it))
                    }
                }""", 1))

R.append(("开关区统一 SwitchRow", """                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("拟声表达", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (draft.nonVerbal) "开" else "关", fontSize = 11.sp, color = Sub)
                    Switch(checked = draft.nonVerbal,
                        onCheckedChange = { draft = draft.copy(nonVerbal = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("角色映射", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(if (draft.castEnabled) "开" else "关", fontSize = 11.sp, color = Sub)
                    Switch(checked = draft.castEnabled,
                        onCheckedChange = { draft = draft.copy(castEnabled = it) })
                }""",
          """                SwitchRow("拟声表达", draft.nonVerbal, TIMING_NOW) {
                    draft = draft.copy(nonVerbal = it)
                }
                SwitchRow("角色映射", draft.castEnabled, TIMING_NOW) {
                    draft = draft.copy(castEnabled = it)
                }""", 1))

R.append(("角色区补 TimingHint", """                    }) { Text("扫描标注里的角色", fontSize = 11.sp) }
                }
            }""",
          """                    }) { Text("扫描标注里的角色", fontSize = 11.sp) }
                }
                TimingHint(TIMING_NOW)
            }""", 1))

R.append(("预设区补 TimingHint", """                    }) { Text("恢复上游默认（待提交）", fontSize = 11.sp) }
                }
            }""",
          """                    }) { Text("恢复上游默认（待提交）", fontSize = 11.sp) }
                }
                TimingHint(TIMING_NOW)
            }""", 1))

# 资料区：整块切片替换（起止标记唯一）
START = "            // ── 参数触达与官方资料（一一对应，用户自己去看）──────────────────"
END = "            // R41a · 试听已移进吸顶状态条那一块"
NEW_TRACE = """            // ── 资料与诊断：普通视图只留人话；字段名 / 文档坐标 / 指纹全收进「高级」 ──
            MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),
                { toggleSection("trace", it) }, anchor = { anchors["trace"] = it }) {
                HelpNote("怎么用这份控制台",
                    "改完点顶部「提交变更」；正在跑的任务不受影响。想复盘就点下面「复制进诊断日志」。")
                TextButton(onClick = {
                    Mixer.logProfile(c, "手动导出当前配置到日志")
                    Toast.makeText(c, "已写进诊断日志，复制诊断信息即可贴出", Toast.LENGTH_SHORT).show()
                }) { Text("复制进诊断日志", fontSize = 12.sp) }
                AdvancedDrawer(rows, stamp)
                TimingHint(TIMING_NOW)
            }

"""

fail = []
for name, old, new, want in R:
    got = src.count(old)
    if got != want:
        fail.append("%s: 期望 %d 处，实际 %d 处" % (name, want, got))
    else:
        src = src.replace(old, new, want)

if src.count(START) != 1 or src.count(END) != 1:
    fail.append("资料区起止标记不唯一")
else:
    a = src.index(START); b = src.index(END)
    src = src[:a] + NEW_TRACE + src[b:]

if fail:
    print("\n".join("ERROR " + x for x in fail))
    sys.exit(1)
P.write_text(src, encoding="utf-8")
print("R42 应用完成：%d 处精确替换 + 资料区整块替换；备份 %s" % (len(R), backup.name))
