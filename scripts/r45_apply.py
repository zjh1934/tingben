#!/usr/bin/env python3
"""R45 · 调音台 v4 落地（只改界面层 / UI 偏好 / 一个设置页入口）。

硬约束：不碰 MixerCommit / MixerDraft / Prefs 写入口 / native / 4B / 主链 / Gradle。
每条替换都断言 old 唯一且存在，任一不符即整体不写盘。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
PREFS = ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt"

def apply(path, pairs):
    s = path.read_text()
    orig = s
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path.name} 第 {i} 条替换命中 {n} 次（应为 1），整体不写盘")
        s = s.replace(old, new, 1)
    if s == orig:
        sys.exit(f"{path.name} 没有任何变化")
    path.write_text(s)
    print(f"{path.name}: {len(pairs)} 条替换完成，{len(orig)} → {len(s)} 字符")

prefs_pairs = [
    ("""/** 默认只展开「身份生成器」；离开页面重进仍保留上次展开的分区。 */
    fun mixerOpenSections(c: Context): Set<String> =
        (sp(c).getString(KEY_MIXER_OPEN, null) ?: "identity")""",
     """/** R45 · 默认全部折叠；首屏身份卡常驻不折叠。只有用户主动展开过才落盘，
     *  所以改默认值对老用户也立即生效，不覆盖手动记住的展开态。 */
    fun mixerOpenSections(c: Context): Set<String> =
        (sp(c).getString(KEY_MIXER_OPEN, null) ?: "")"""),
]

fixed_voice_section = """            // ── R45 · 固定音色：官方三模式只读说明，不画开关（后程折叠）──────
            MixerSection("固定音色 · 官方模式说明", "fixedVoice",
                openMap.contains("fixedVoice"), { toggleSection("fixedVoice", it) },
                badge = "只读", anchor = { anchors["fixedVoice"] = it }) {
                Text("一句话：官方说的「固定音色」是另一份模型权重；我们现在这份没有音色编号，也没有音色锁。",
                    fontSize = 11.5.sp)
                Spacer(Modifier.height(7.dp))
                Text("官方三种模式（三份权重，不是三个开关）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("声音设计（当前）：写一段描述生成声音；不需要编号，也不吃参考音频。",
                    fontSize = 11.sp)
                Text("预置音色：用音色编号选固定说话人，官方列了 9 个；换权重才能用。",
                    fontSize = 11.sp)
                Text("声音克隆：给 3 秒参考音频提取说话人身份；换权重才能用。",
                    fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                HelpNote("官方 9 个编号",
                    "Vivian / Serena / Uncle_Fu / Dylan / Eric / Ryan / Aiden / Ono_Anna / Sohee。"
                        + "只属预置音色模式，本软件不提供选择，也不画开关。")
                Spacer(Modifier.height(6.dp))
                Text("当前能固定的三样", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Text("身份描述全文 + 随机种子 + 9 项采样，全书不变。上面首屏就能改。",
                    fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                Text("这是收敛每段音色漂移，不等于官方承诺的跨段完全一致；种子固定只保证同参数可复现。",
                    fontSize = 10.5.sp, color = Warn)
                Spacer(Modifier.height(4.dp))
                Text("已决定不换模型，所以编号、参考音频、音色锁在这里都不是可选项，界面不画开关。",
                    fontSize = 10.5.sp, color = Sub)
                TimingHint(TIMING_NOW)
            }

            // ── R45 · 参数与模型一一对应：可调 / 上层 / 不可调，只读不画开关 ──
            MixerSection("哪些能改 · 和模型一一对齐", "mapping",
                openMap.contains("mapping"), { toggleSection("mapping", it) },
                badge = "只读", anchor = { anchors["mapping"] = it }) {
                Text("✅ 真到声音模型（能调）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold, color = Good)
                Text("音色描述全文、9 项采样、语言 chinese、音色编号为空。9 项逐字段送进引擎，改完提交即可。",
                    fontSize = 11.sp)
                HelpNote("9 项逐项核对",
                    ParamCatalog.SAMPLING.joinToString("\\n") {
                        "${it.label}：官方默认 ${it.def}，可填 ${it.rangeLabel}${it.unit}"
                    })
                Spacer(Modifier.height(7.dp))
                Text("⚠️ 上层（能改，但模型只看到文字或 App 流程）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold, color = Warn)
                Text("12 维生成器只是帮你写描述；朗读档位、语速倾向、情绪强度拼进描述；"
                    + "段间静音是 WAV 合并；角色映射与预设是 App 自己的配置。",
                    fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                Text("🔒 结构上没有（不画开关）", fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold, color = Sub)
                ParamCatalog.LOCKED.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(r.label, fontSize = 10.5.sp, color = Sub,
                            modifier = Modifier.weight(1f))
                        Text("无", fontSize = 10.5.sp, color = Sub)
                    }
                }
                Text("字段名、文档坐标、触达链路收在下面「高级 · 开发者资料」，普通视图不显示。",
                    fontSize = 10.sp, color = Sub)
            }

"""

main_pairs = [
    # A · 12 维每维小自定义框：ChipRow 加可选参数，其他调用点不受影响
    ("""private fun ChipRow(
    label: String,
    hint: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit
) {""",
     """private fun ChipRow(
    label: String,
    hint: String,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
    allowCustom: Boolean = false,
    customValue: String = "",
    onCustom: (String) -> Unit = {}
) {"""),

    ("""            options.forEach { o ->
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    label = { Text(o, fontSize = 10.5.sp) }
                )
            }
        }
        HorizontalDivider(color = Line)
    }
}""",
     """            options.forEach { o ->
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    label = { Text(o, fontSize = 10.5.sp) }
                )
            }
        }
        if (allowCustom) {
            OutlinedTextField(
                value = customValue, onValueChange = onCustom,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("自己填（可选）", fontSize = 9.5.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
        }
        HorizontalDivider(color = Line)
    }
}"""),

    # B · seed 一键换一个新的固定种子（只写草稿，不直接生效）
    ("""    onPick: (String) -> Unit,
    onReset: () -> Unit
) {""",
     """    onPick: (String) -> Unit,
    onReset: () -> Unit,
    onRoll: (() -> Unit)? = null
) {"""),

    ("""            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("默认", fontSize = 10.sp, color = Sub) }
        }""",
     """            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("⟳ 默认", fontSize = 10.sp, color = Sub) }
            onRoll?.let { roll ->
                TextButton(onClick = roll) { Text("⟳ 换一个", fontSize = 10.sp, color = Primary) }
            }
        }"""),

    ("""                            onReset = {
                                spec.withValue(draft.knobs, spec.def)?.let { draft = draft.copy(knobs = it) }
                                paramDrafts = paramDrafts - spec.key
                                paramErr = paramErr - spec.key
                            }
                        )""",
     """                            onReset = {
                                spec.withValue(draft.knobs, spec.def)?.let { draft = draft.copy(knobs = it) }
                                paramDrafts = paramDrafts - spec.key
                                paramErr = paramErr - spec.key
                            },
                            onRoll = if (spec.key == "seed") ({
                                val n = java.util.concurrent.ThreadLocalRandom.current()
                                    .nextLong(1L, 2_000_000_000L)
                                draft = draft.copy(knobs = draft.knobs.copy(seed = n))
                                paramDrafts = paramDrafts - "seed"
                                paramErr = paramErr - "seed"
                                EventLog.log(EventLog.Kind.ENGINE, "MIXER_SEED",
                                    "换一个固定种子 $n · 仅草稿，不直接生效")
                            }) else null
                        )"""),

    # C · 采样区标题明确「真到模型」，与后程只读区区分
    ("""            MixerSection("采样参数 · 9 项到引擎", "sampling",
                openMap.contains("sampling"), { toggleSection("sampling", it) },
                anchor = { anchors["sampling"] = it }) {""",
     """            MixerSection("采样参数 · 9 项真到模型", "sampling",
                openMap.contains("sampling"), { toggleSection("sampling", it) },
                badge = "✅ 可调", anchor = { anchors["sampling"] = it }) {"""),

    # D · 12 维每维挂小自定义框
    ("""                VoiceDimension.entries.forEach { d ->
                    ChipRow(d.label, d.hint, d.options,
                        draft.dims[d.name] ?: "不指定") { v ->
                        draft = draft.copy(dims = if (v == "不指定") draft.dims - d.name
                        else draft.dims + (d.name to v))
                        if (!dimEdited) draft = draft.copy(dimText = "")
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM",
                            "${d.name}=${v} · 已选 ${draft.dims.size} 维 · 仅草稿")
                    }
                }""",
     """                VoiceDimension.entries.forEach { d ->
                    val curDim = draft.dims[d.name] ?: "不指定"
                    ChipRow(d.label, d.hint, d.options, curDim,
                        allowCustom = true,
                        customValue = if (curDim in d.options) "" else curDim,
                        onCustom = { v ->
                            draft = draft.copy(dims = if (v.isBlank()) draft.dims - d.name
                            else draft.dims + (d.name to v.trim()))
                            if (!dimEdited) draft = draft.copy(dimText = "")
                        }) { v ->
                        draft = draft.copy(dims = if (v == "不指定") draft.dims - d.name
                        else draft.dims + (d.name to v))
                        if (!dimEdited) draft = draft.copy(dimText = "")
                        EventLog.log(EventLog.Kind.ENGINE, "MIXER_DIM",
                            "${d.name}=${v} · 已选 ${draft.dims.size} 维 · 仅草稿")
                    }
                }"""),

    # E · 首屏一句人话 + 直达固定音色说明
    ("""                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = draft.copy(voice = "") }) {
                        Text("清空回默认（待提交）", fontSize = 11.sp)
                    }
                }
                TimingHint(TIMING_NOW)
            }""",
     """                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = draft.copy(voice = "") }) {
                        Text("清空回默认（待提交）", fontSize = 11.sp)
                    }
                }
                Text("固定音色 = 这段描述 + 种子 + 9 项采样全书不变。", fontSize = 10.sp, color = Sub)
                TextButton(onClick = {
                    toggleSection("fixedVoice", true)
                    scope.launch { scroll.animateScrollTo(anchors["fixedVoice"] ?: 0) }
                }) { Text("固定音色是什么？ ›", fontSize = 10.5.sp, color = Primary) }
                TimingHint(TIMING_NOW)
            }"""),

    # F · 锚点补两项
    ("""        AnchorBar(listOf(
            "identity" to "身份", "sampling" to "采样", "perf" to "表演",
            "switches" to "开关", "casts" to "角色", "presets" to "预设", "trace" to "资料"
        )) { jumpTo(it) }""",
     """        AnchorBar(listOf(
            "identity" to "身份", "sampling" to "采样", "perf" to "表演",
            "switches" to "开关", "casts" to "角色", "presets" to "预设",
            "fixedVoice" to "固定音色", "mapping" to "对齐", "trace" to "资料"
        )) { jumpTo(it) }"""),

    # G · 两个后程只读折叠区（默认收起）
    ("""            // ── 资料与诊断：普通视图只留人话；字段名 / 文档坐标 / 指纹全收进「高级」 ──
            MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),""",
     fixed_voice_section + """            // ── 资料与诊断：普通视图只留人话；字段名 / 文档坐标 / 指纹全收进「高级」 ──
            MixerSection("参数触达与官方资料", "trace", openMap.contains("trace"),"""),

    # H · 差异弹层明写「不试听也能提交」
    ("""                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("当前生效全貌", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)""",
     """                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("提交不依赖试听：改完直接点「提交变更」即可生效。", fontSize = 10.5.sp, color = Sub)
                    Spacer(Modifier.height(6.dp))
                    Text("当前生效全貌", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)"""),

    # I · 底部留白：最后一块不被系统手势条压住
    ("""            // R41a · 试听已移进吸顶状态条那一块（草稿 / 已生效各一个按钮，一步合成并播放）。
            Spacer(Modifier.height(18.dp))""",
     """            // R41a · 试听已移进吸顶状态条那一块（草稿 / 已生效各一个按钮，一步合成并播放）。
            // R45 · 末尾留白：滚动到底时最后一块不压住系统手势白条 / 三键区。
            Spacer(Modifier.height(40.dp))"""),

    # J · 设置页「固定音色」入口：复用展开态偏好，不改任何函数签名
    ("""                Text("›", fontSize = 20.sp, color = Primary)
            }
        }


        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("模型文件（两个模型共用）", fontWeight = FontWeight.Bold, fontSize = 14.sp)""",
     """                Text("›", fontSize = 20.sp, color = Primary)
            }
        }

        // R45 · 固定音色入口：只把对应折叠区记成展开，再进调音台；不新增页面、不改签名。
        CardBox(Modifier.padding(bottom = 11.dp)) {
            Row(Modifier.fillMaxWidth().clickable {
                val s = Prefs.mixerOpenSections(c) + "fixedVoice"
                Prefs.setMixerOpenSections(c, s)
                onMixer()
            }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("固定音色", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("官方三模式说明 · 当前权重能固定哪三样", fontSize = 11.sp, color = Sub)
                }
                Text("›", fontSize = 20.sp, color = Primary)
            }
        }


        CardBox(Modifier.padding(bottom = 11.dp)) {
            Text("模型文件（两个模型共用）", fontWeight = FontWeight.Bold, fontSize = 14.sp)"""),
]

apply(PREFS, prefs_pairs)
apply(MAIN, main_pairs)
