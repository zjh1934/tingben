# -*- coding: utf-8 -*-
"""R84 · 模型选择页（1 底座 + 3 模式）落地：按锚点精确切片，不整文件重写。

逐处与 docs/R84_模型选择页_联动表.md 对应。可重复执行：若已是新形态会报「已落地」并跳过。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"
PREFS = ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt"
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"


def replace_once(text, old, new, tag):
    if new.split("\n")[0][:40] in text and old not in text:
        print("  跳过（已落地）:", tag)
        return text
    n = text.count(old)
    if n != 1:
        sys.exit("锚点不唯一或缺失: %s (count=%d)" % (tag, n))
    return text.replace(old, new, 1)


def slice_replace(text, start_marker, end_marker, new, tag):
    s = text.index(start_marker)
    e = text.index(end_marker, s)
    return text[:s] + new + text[e:]


# ───────────────────────────── 1 · ModelStore ─────────────────────────────
MODELSTORE = '''object ModelStore {
    /**
     * R68 · P1b：旧文本权重整套退场后，下面两个名称常量只作「旧数据白名单」保留——
     * 老用户私有目录里若还躺着旧 GGUF，不主动删、也不再加载。
     */
    const val LLM = "huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf"
    const val LLM_LEGACY = "mlabonne_Qwen3-旧权重-abliterated-Q4_K_M.gguf"
    const val TALKER = "qwen-talker-1.7b-voicedesign-Q8_0.gguf"
    const val TOKENIZER = "qwen-tokenizer-12hz-Q8_0.gguf"

    /**
     * R84 · 结构不是「两份配套文件」，而是 1 个共用底座 + 3 份模式主文件。
     * 底座三模式共用、导入一次；三份主文件各自单独点导入，装哪份用哪份。
     * 模式语义以随包 qwen.h 为准：instruct 只归 voice_design（必填），
     * speaker 编号只归 custom_voice，ref_audio_24k 只归 base 且与 instruct 互斥。
     */
    enum class Mode(
        val id: String, val label: String, val file: String,
        val key: String, val hint: String
    ) {
        VOICE_DESIGN("voice_design", "描述造音色",
            "qwen-talker-1.7b-voicedesign-Q8_0.gguf", "voicedesign",
            "一段文字描述造音色，instruct 必填；当前唯一接通合成的模式。"),
        CUSTOM_VOICE("custom_voice", "预设音色",
            "qwen-talker-1.7b-customvoice-Q8_0.gguf", "customvoice",
            "预设音色编号 + 语气指令；合成界面下一轮做。"),
        BASE("base", "参考音频",
            "qwen-talker-1.7b-base-Q8_0.gguf", "base",
            "一小段参考音频克隆，拒 instruct；合成界面下一轮做。")
    }

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun files(c: Context): List<File> = try {
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /**
     * 把 SAF 选中的文件复制进私有目录；同名覆盖。
     * 必须流式、必须在后台线程调用、必须写 .part 再原子改名：
     * 2.5GB 一次性读进内存会 OOM，主线程同步复制会 ANR 被系统杀掉。
     */
    fun import(c: Context, name: String, source: java.io.InputStream,
               onProgress: (Long) -> Unit = {}): File {
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val tmp = File(dir(c), "$safe.part")
        val out = File(dir(c), safe)
        try {
            tmp.outputStream().buffered(64 * 1024).use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    total += n
                    if (total - lastReport >= 8L * 1024 * 1024) {
                        lastReport = total
                        onProgress(total)
                    }
                }
                o.flush()
                onProgress(total)
            }
            if (tmp.length() == 0L) throw IllegalStateException("选到的文件是空的")
            if (out.exists() && !out.delete()) throw IllegalStateException("旧文件删不掉")
            if (!tmp.renameTo(out)) throw IllegalStateException("文件没能保存")
            EventLog.log(EventLog.Kind.MODEL, "MODEL_IMPORT", "$safe ${out.length()}")
            return out
        } catch (t: Throwable) {
            tmp.delete()   // 半截文件绝不留下来被当成就绪
            val reason = t.message ?: ""
            EventLog.log(EventLog.Kind.CRASH, "MODEL_IMPORT_FAIL",
                "$safe ${t.javaClass.simpleName}: $reason")
            throw t
        }
    }

    fun delete(c: Context, f: File) { f.delete(); EventLog.log(EventLog.Kind.MODEL, "MODEL_DELETE", f.name) }

    /** 上次导入被系统中断留下的半截文件，清掉，绝不被当成就绪。 */
    fun cleanup(c: Context) {
        try {
            dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach {
                EventLog.log(EventLog.Kind.MODEL, "PART_DISCARD", "${it.name} ${it.length()}")
                it.delete()
            }
        } catch (_: Throwable) { }
    }

    private fun pick(c: Context, exact: String, vararg hints: String): File? {
        val all = files(c)
        all.find { it.name.equals(exact, true) }?.let { return it }
        return all.firstOrNull { f -> hints.any { f.name.contains(it, true) } }
    }

    /**
     * R84 · 按「模式独有关键词」识别，绝不用泛化的 "talker"：
     * 旧写法 pick(c, TALKER, "talker") 会把 customvoice / base 主文件误当成
     * voicedesign 加载，而 speaker 恒 null、instruct 必填，引擎会直接判非法。
     * 下载器在文件名后加 (1) / _1 时，仍靠 -voicedesign- / -customvoice- / -base- 认出来。
     */
    private fun pickFor(c: Context, m: Mode): File? {
        val all = files(c)
        all.find { it.name.equals(m.file, true) }?.let { return it }
        return all.firstOrNull { it.name.contains("-" + m.key + "-", true) }
    }

    fun modeFile(c: Context, m: Mode) = pickFor(c, m)
    fun modeFiles(c: Context): Map<Mode, File?> = Mode.values().associateWith { pickFor(c, it) }
    fun installedModes(c: Context): List<Mode> = Mode.values().filter { pickFor(c, it) != null }
    fun currentMode(c: Context): Mode =
        Mode.values().firstOrNull { it.id == Prefs.modelMode(c) } ?: Mode.VOICE_DESIGN

    /** 当前启用模式没装时回退到已装的 voicedesign，保证老用户升级后仍能出声。 */
    fun talker(c: Context): File? = pickFor(c, currentMode(c)) ?: pickFor(c, Mode.VOICE_DESIGN)
    fun tokenizer(c: Context) = pick(c, TOKENIZER, "tokenizer", "12hz")

    /**
     * R84 · customvoice 要音色编号、base 要参考音频，输入界面属于下一轮调音台。
     * 明确拦住比把错误参数丢给引擎好：不画假开关、不静默产出坏音频。
     * ttsLoad 每次合成都重新加载并 ttsFree，切模式后下一次合成自然生效，无需额外卸载。
     */
    fun synthesisBlockReason(c: Context): String? {
        val m = currentMode(c)
        if (m == Mode.VOICE_DESIGN) return null
        return "当前是「${m.label}」模式，这个模式的合成界面还没做完；先去模型页切回「描述造音色」。"
    }

    fun missing(c: Context): List<String> {
        val m = mutableListOf<String>()
        if (tokenizer(c) == null) m.add("共用底座（12Hz）")
        if (installedModes(c).isEmpty()) m.add("模式主文件")
        return m
    }
    fun ready(c: Context) = missing(c).isEmpty()
}

'''

# ───────────────────────────── 2 · ModelScreen ─────────────────────────────
MODELSCREEN = '''// ── ⑤b 模型页（R84 · 1 共用底座 + 3 模式，各自单独导入） ──────────
@Composable
private fun ModelScreen(c: Context, refresh: Int,
                        importing: String?, importBytes: Long, importError: String?,
                        onPick: () -> Unit, onBack: () -> Unit, onLibrary: () -> Unit,
                        onChanged: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val st by Producer.state.collectAsState()
    val files = remember(refresh) { ModelStore.files(c) }
    val missing = remember(refresh) { ModelStore.missing(c) }
    val installed = remember(refresh) { ModelStore.installedModes(c) }
    val current = remember(refresh) { ModelStore.currentMode(c) }
    val modeFiles = remember(refresh) { ModelStore.modeFiles(c) }
    val baseFile = remember(refresh) { ModelStore.tokenizer(c) }
    val allReady = missing.isEmpty()

    fun copyUrl(url: String) {
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(c, "地址已复制，用浏览器下载", Toast.LENGTH_SHORT).show()
    }
    fun cnUrl(name: String) =
        "https://hf-mirror.com/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$name"
    fun hfUrl(name: String) =
        "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/$name"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回", fontSize = 12.sp, color = Primary) }
            Spacer(Modifier.weight(1f))
            Text(if (allReady) "已就绪 · ${installed.size} 个模式"
                 else if (baseFile == null) "还缺共用底座" else "还缺模式主文件",
                fontSize = 11.5.sp, color = if (allReady) Good else Warn)
        }
        Spacer(Modifier.height(6.dp))

        // 导入中 / 导入失败：两态沿用，不静默
        if (importing != null) {
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Text("正在复制，别关掉 App", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Primary)
                Spacer(Modifier.height(5.dp))
                Text("已复制 %.2f GB".format(importBytes / 1024f / 1024f / 1024f),
                    fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp))
                Spacer(Modifier.height(5.dp))
                Text("文件很大，可能要一两分钟。", fontSize = 11.5.sp, color = Sub)
            }
        }
        if (importError != null) {
            CardBox(Modifier.padding(bottom = 11.dp)) {
                Text("上一个文件没导入成功", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(importError, fontSize = 12.sp, color = Sub)
                Spacer(Modifier.height(5.dp))
                Text("重新下载再导入一次，或先重启 App。", fontSize = 11.5.sp, color = Sub)
            }
        }

        // ── 老大：共用底座，三模式共用，导入一次就够 ──
        CardBox(Modifier.padding(bottom = 10.dp)) {
            Text("共用底座 · 三个模式都要用", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("12Hz 码 ↔ 24kHz 波形，导入一次就够。", fontSize = 11.sp, color = Sub)
            Spacer(Modifier.height(8.dp))
            Text(ModelStore.TOKENIZER, fontSize = 10.sp, color = Sub,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            if (baseFile != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已导入 ✓ · %.2f GB".format(
                        baseFile.length() / 1024f / 1024f / 1024f),
                        fontSize = 11.5.sp, color = Good, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ModelStore.delete(c, baseFile); onChanged() }) {
                        Text("删除重试", fontSize = 10.5.sp, color = Sub)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("还缺这一份", fontSize = 11.5.sp, color = Warn,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { copyUrl(cnUrl(ModelStore.TOKENIZER)) }) {
                        Text("复制国内地址 ›", fontSize = 11.5.sp, color = Primary)
                    }
                    TextButton(onClick = { copyUrl(hfUrl(ModelStore.TOKENIZER)) }) {
                        Text("备用 ›", fontSize = 10.5.sp, color = Sub)
                    }
                }
            }
        }

        Text("模式主文件 · 装一份就能用", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        Text("App 不联网：复制地址 → 浏览器下 → 回来点导入。",
            fontSize = 10.5.sp, color = Sub)
        Spacer(Modifier.height(8.dp))

        ModelStore.Mode.values().forEach { mode ->
            val f = modeFiles[mode]
            val isCurrent = mode == current
            CardBox(Modifier.padding(bottom = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.label + (if (isCurrent) " · 当前启用" else ""),
                            fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                            color = if (isCurrent) Primary else Color.Unspecified)
                        Text(mode.id, fontSize = 10.sp, color = Sub)
                    }
                    if (isCurrent) Text("✓", fontSize = 14.sp, color = Good)
                }
                Spacer(Modifier.height(4.dp))
                Text(mode.file, fontSize = 10.sp, color = Sub,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(3.dp))
                Text(mode.hint, fontSize = 10.5.sp, color = Sub)
                Spacer(Modifier.height(7.dp))
                if (f != null) {
                    Text("已导入 · %.2f GB".format(f.length() / 1024f / 1024f / 1024f),
                        fontSize = 11.sp, color = Good)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isCurrent) {
                            TextButton(onClick = {
                                if (!st.running) { Prefs.setModelMode(c, mode.id); onChanged() }
                            }, enabled = !st.running) {
                                Text("启用这个模式", fontSize = 11.5.sp, color = Primary)
                            }
                        } else {
                            Text("这是当前模式", fontSize = 11.sp, color = Sub)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { ModelStore.delete(c, f); onChanged() }) {
                            Text("删除重试", fontSize = 10.5.sp, color = Sub)
                        }
                    }
                    if (!isCurrent && st.running) {
                        Text("正在制作，这一本跑完之前不能切", fontSize = 10.5.sp, color = Warn)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("待导入", fontSize = 11.sp, color = Warn,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onPick, enabled = importing == null) {
                            Text("导入这份 ›", fontSize = 11.5.sp, color = Primary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { copyUrl(cnUrl(mode.file)) }) {
                            Text("复制国内地址 ›", fontSize = 11.sp, color = Primary)
                        }
                        TextButton(onClick = { copyUrl(hfUrl(mode.file)) }) {
                            Text("备用 ›", fontSize = 10.5.sp, color = Sub)
                        }
                    }
                }
            }
        }

        if (allReady) {
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text("底座 + 至少 1 个模式都在", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(current.label + " 是当前模式。" + (if (current == ModelStore.Mode.VOICE_DESIGN)
                    "可以合成音频了。"
                else "这个模式的合成界面还没做完，先切回「描述造音色」再制作。"),
                    fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onLibrary, Modifier.fillMaxWidth().height(48.dp)) {
                    Text("回书架，导入一本书", fontSize = 15.sp)
                }
            }
        } else {
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Text("还差 " + missing.joinToString("、"), fontWeight = FontWeight.Bold,
                    fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("缺件就出不了声，先补齐上面高亮的那一份。", fontSize = 11.5.sp, color = Sub)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick, enabled = importing == null,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("导入模型文件", fontSize = 15.sp)
                }
            }
        }

        TextButton(onClick = onPick, enabled = importing == null) {
            Text("重新导入某个文件 ›", fontSize = 11.5.sp, color = Primary)
        }
        if (files.isNotEmpty()) {
            Text("已导入 ${files.size} 个文件", fontSize = 11.sp, color = Sub)
        }
        Spacer(Modifier.height(24.dp))
    }
}

'''

def main():
    # 1 · Pipeline.kt —— ModelStore 整段替换
    p = PIPE.read_text(encoding="utf-8")
    if "enum class Mode(" in p:
        print("ModelStore 已落地，跳过")
    else:
        p = slice_replace(p, "object ModelStore {",
                          "// ─────────────────────────── 分块", MODELSTORE, "ModelStore")
        print("ModelStore 已替换")

    # 2 · Pipeline.kt —— 合成前模式校验 + 缺件口径
    old_run = '''        val tk = ModelStore.talker(c); val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("声音主文件")
        if (tok == null) miss.add("声音配套文件")
        // R67 · P1a：只剩声音模型，不再检查第二份权重。
        if (miss.isNotEmpty()) {
            _state.value = Progress(false, "没开始", 0, 0, 0,
                "还差：" + miss.joinToString("、") + "。去设置页导入。")
            return
        }
'''
    new_run = '''        // R84 · 非 voicedesign 模式的输入界面还没做，明确拦住，不静默产出坏音频。
        ModelStore.synthesisBlockReason(c)?.let { reason ->
            _state.value = Progress(false, "没开始", 0, 0, 0, reason)
            return
        }
        val tk = ModelStore.talker(c); val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("模式主文件")
        if (tok == null) miss.add("共用底座（12Hz）")
        if (miss.isNotEmpty()) {
            _state.value = Progress(false, "没开始", 0, 0, 0,
                "还差：" + miss.joinToString("、") + "。去模型页导入。")
            return
        }
'''
    if new_run in p:
        print("run 校验已落地，跳过")
    else:
        p = replace_once(p, old_run, new_run, "Producer.run 模式校验")
    PIPE.write_text(p, encoding="utf-8")

    # 3 · Prefs.kt —— 当前模式
    pf = PREFS.read_text(encoding="utf-8")
    if "KEY_MODEL_MODE" in pf:
        print("Prefs.modelMode 已落地，跳过")
    else:
        pf = replace_once(pf,
            '    private const val KEY_FONT_SCALE = "readerFontScale"\n',
            '    private const val KEY_FONT_SCALE = "readerFontScale"\n'
            '    // R84 · 当前启用的音频模式 id；不进 voiceStamp，切模式不误删旧 WAV。\n'
            '    private const val KEY_MODEL_MODE = "modelMode"\n',
            "Prefs KEY_MODEL_MODE")
        pf = replace_once(pf,
            '    fun themeMode(c: Context) = sp(c).getInt(KEY_THEME_MODE, 0)\n',
            '    // R84 · 1 底座 + 3 模式里的当前模式，默认描述造音色（兼容旧数据）。\n'
            '    fun modelMode(c: Context): String =\n'
            '        sp(c).getString(KEY_MODEL_MODE, "voice_design") ?: "voice_design"\n'
            '    fun setModelMode(c: Context, v: String) =\n'
            '        sp(c).edit().putString(KEY_MODEL_MODE, v).apply()\n\n'
            '    fun themeMode(c: Context) = sp(c).getInt(KEY_THEME_MODE, 0)\n',
            "Prefs.modelMode 函数")
    PREFS.write_text(pf, encoding="utf-8")

    # 4 · MainActivity.kt —— ModelScreen 整段替换
    m = MAIN.read_text(encoding="utf-8")
    if "R84 · 1 共用底座" in m:
        print("ModelScreen 已落地，跳过")
    else:
        m = slice_replace(m, "// ── ⑤b 模型页", "// ── ⑥ 调音台", MODELSCREEN, "ModelScreen")
        print("ModelScreen 已替换")

    # 5 · 导出日志相关全部砍掉
    cuts = [
        ('            SettingInfoRow("诊断全文", "现有", "复制全文", "事件、审计、TTS 参数、设备信息")\n'
         '            SettingInfoRow("分段记录", "现有", "按 run 分目录", "每次制作落一份段映射")\n', ""),
        ('        SettingsSection("数据与日志", "本机", dataOpen, { dataOpen = it }) {',
         '        SettingsSection("本机数据", "本机", dataOpen, { dataOpen = it }) {'),
        ('    fun buildDiag(full: Boolean): String = buildString {',
         '    fun buildDiag(): String = buildString {'),
        ('        if (full) {\n'
         '            append("\\n\\n=== 诊断态完整日志（含 TRACE，最近 300 行）===\\n")\n'
         '                .append(EventLog.readDetail().lines().takeLast(300).joinToString("\\n"))\n'
         '        }\n', ""),
        ('        clipboard.setText(AnnotatedString(buildDiag(false)))',
         '        clipboard.setText(AnnotatedString(buildDiag()))'),
        ('    TextButton(onClick = {\n'
         '        clipboard.setText(AnnotatedString(buildDiag(true)))\n'
         '        Toast.makeText(c, "已复制完整日志，仅本机保存", Toast.LENGTH_SHORT).show()\n'
         '    }, Modifier.fillMaxWidth()) { Text("复制完整日志") }\n', ""),
        ('            Text("导出与诊断", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)\n'
         '            Text("已生效 v$INSTRUCT_TEMPLATE_VERSION · 指纹 $stamp · 合成并发 1 · 语言 chinese · 24kHz 单声道 WAV",\n'
         '                fontSize = 10.sp, color = Sub)\n', ""),
        ('                HelpNote("怎么用这份控制台",\n'
         '                    "改完点顶部「提交变更」；正在跑的任务不受影响。想复盘就点下面「复制进诊断日志」。")\n'
         '                TextButton(onClick = {\n'
         '                    Mixer.logProfile(c, "手动导出当前配置到日志")\n'
         '                    Toast.makeText(c, "已写进诊断日志，复制诊断信息即可贴出", Toast.LENGTH_SHORT).show()\n'
         '                }) { Text("复制进诊断日志", fontSize = 12.sp) }\n',
         '                HelpNote("怎么用这份控制台",\n'
         '                    "改完点顶部「提交变更」；正在跑的任务不受影响。")\n'),
    ]
    for old, new in cuts:
        if old in m:
            m = m.replace(old, new, 1)
            print("  删/改:", old.strip().split("\n")[0][:48])
        elif new and new in m:
            print("  跳过（已落地）:", old.strip().split("\n")[0][:48])
        else:
            print("  ⚠ 未命中:", old.strip().split("\n")[0][:48])

    # 6 · 设置页缺件提示换词（局部，避免误伤其它同名行）
    ss = m.index("private fun SettingsScreen(")
    se = m.index("/** R68 · P1b", ss)
    seg = m[ss:se]
    if "模型已就绪" in seg:
        print("设置页提示已落地，跳过")
    else:
        seg = replace_once(seg,
            '    val missing = remember(refresh) { ModelStore.missing(c) }\n',
            '    val missing = remember(refresh) { ModelStore.missing(c) }\n'
            '    val modeCount = remember(refresh) { ModelStore.installedModes(c).size }\n',
            "设置页 modeCount")
        seg = replace_once(seg,
            '            Text(if (missing.isEmpty()) "2 个声音模型都准备好了"\n'
            '                 else "还差：" + missing.joinToString("、"),\n',
            '            Text(if (missing.isEmpty()) "模型已就绪 · $modeCount 个模式"\n'
            '                 else "还差：" + missing.joinToString("、"),\n',
            "设置页状态词")
        m = m[:ss] + seg + m[se:]

    # 7 · 两处试音：非 voicedesign 模式明确拦住（不静默）
    probe_anchor = '                val tk = ModelStore.talker(c) ?: throw IllegalStateException("缺声音主文件")'
    probe_new = ('                ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }\n'
                 + probe_anchor)
    cnt = m.count(probe_anchor)
    if "synthesisBlockReason(c)?.let { throw" in m:
        print("试音拦截已落地，跳过")
    elif cnt == 2:
        m = m.replace(probe_anchor, probe_new)
        print("两处试音已加模式拦截")
    else:
        sys.exit("试音锚点数量异常: %d" % cnt)
    MAIN.write_text(m, encoding="utf-8")

    # 8 · ci.yml：归档 r81 旧形态验证，挂 r84
    c = CI.read_text(encoding="utf-8")
    old_step = ('      - name: R81 模型页重排验证（缺件单主卡 / 就绪不同构 / 复制降级 / 守恒 / 反向注入）\n'
                '        run: python3 scripts/r81_verify.py --selftest\n\n')
    new_step = ('      - name: R84 模型选择页验证（1 底座 + 3 模式 / 精确识别 / 导出日志零残留 / 守恒 / 反向注入）\n'
                '        run: python3 scripts/r84_verify.py --selftest\n\n')
    if new_step in c:
        print("CI 已挂 r84，跳过")
    else:
        c = replace_once(c, old_step, new_step, "CI 步骤换挂")
        CI.write_text(c, encoding="utf-8")

    print("R84 代码落地完成")


if __name__ == "__main__":
    main()
