# -*- coding: utf-8 -*-
"""R77 · P1d 底层残留清理：精确替换，每处断言命中一次，漏一个就报错不静默。"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/tingben/app/core"

def rw(rel, pairs):
    p = ROOT / rel
    t = p.read_text(encoding="utf-8")
    for old, new in pairs:
        n = t.count(old)
        if n != 1:
            sys.exit(f"[{rel}] 期望 1 处命中，实际 {n}: {old[:70]!r}")
        t = t.replace(old, new, 1)
    p.write_text(t, encoding="utf-8")
    print("改", rel, len(pairs), "处")

# ── 1. Prefs.kt ────────────────────────────────────────────────────────────
rw("app/src/main/java/com/tingben/app/core/Prefs.kt", [
    ("import org.json.JSONObject\nimport java.security.MessageDigest",
     "import org.json.JSONObject\nimport java.io.File\nimport java.security.MessageDigest"),
    ('    private const val KEY_VOICE_DESC = "voiceDesc"\n    private const val KEY_CAST_ENABLED = "castEnabled"\n',
     '    private const val KEY_VOICE_DESC = "voiceDesc"\n'),
    ('    private const val KEY_DEFAULT_PERF = "defaultPerf"\n    private const val KEY_CAST_SEEDED = "castSeeded"\n',
     '    private const val KEY_DEFAULT_PERF = "defaultPerf"\n'),
    ("    /** 角色→音色映射是否参与合成。默认开，但表里没内容时等价于只用全局描述。 */\n"
     "    fun castEnabled(c: Context) = sp(c).getBoolean(KEY_CAST_ENABLED, true)\n"
     "    fun setCastEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_CAST_ENABLED, v).apply()\n\n",
     ""),
    ("    /** 内置卡只 seed 一次，避免覆盖用户后来改过的配置。 */\n"
     "    fun castSeeded(c: Context) = sp(c).getBoolean(KEY_CAST_SEEDED, false)\n"
     "    fun setCastSeeded(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_CAST_SEEDED, v).apply()\n\n",
     ""),
    ("    // R41 · 纯 UI 偏好：不进 voiceStamp、不进 diff、不碰 4B/任务书签",
     "    // 纯 UI 偏好：不进 voiceStamp、不进 diff、不碰任务书签"),
    ("    // R67 · P1a：旧思考档位设置键随语言模型整套退场，不再读写。",
     "    // 旧思考档位设置键已退场，不再读写。"),
    ("    /**\n"
     "     * 声音指纹：音色描述 / 角色表 / 采样 / instruct 模板 任一变化，旧 WAV 必须作废。\n"
     "     * 抄 ReaderVoice 的 profileHash 思路；这是「改一处联动很多处」里最容易漏的一处。\n"
     "     */\n"
     "    fun voiceStamp(c: Context): String = sha(listOf(\n"
     "        INSTRUCT_TEMPLATE_VERSION.toString(),\n"
     "        voiceDescription(c),\n"
     "        if (castEnabled(c)) VoiceCast.hash(c) else \"off\",\n"
     "        ttsKnobs(c).hash()\n"
     "    ).joinToString(\"||\"))",
     "    /**\n"
     "     * 声音指纹：音色描述 / 采样 / instruct 模板 任一变化，旧 WAV 必须作废。\n"
     "     * 抄 ReaderVoice 的 profileHash 思路。角色表整套退场后不再进指纹。\n"
     "     */\n"
     "    fun voiceStamp(c: Context): String = sha(listOf(\n"
     "        INSTRUCT_TEMPLATE_VERSION.toString(),\n"
     "        voiceDescription(c),\n"
     "        ttsKnobs(c).hash()\n"
     "    ).joinToString(\"||\"))\n\n"
     "    /**\n"
     "     * 迁移专用：旧版四项指纹（含角色表）。只用于识别手机上 voice_stamp.txt 里\n"
     "     * 存的旧值 —— 命中就不作废旧 WAV，并把文件改写成新值。算法固定，\n"
     "     * 角色表读法内联，不依赖已删除的 VoiceCast。\n"
     "     */\n"
     "    fun legacyVoiceStamp(c: Context): String = sha(listOf(\n"
     "        INSTRUCT_TEMPLATE_VERSION.toString(),\n"
     "        voiceDescription(c),\n"
     "        if (sp(c).getBoolean(\"castEnabled\", true)) legacyCastHash(c) else \"off\",\n"
     "        ttsKnobs(c).hash()\n"
     "    ).joinToString(\"||\"))\n\n"
     "    private fun legacyCastHash(c: Context): String = try {\n"
     "        val f = File(c.filesDir, \"cast.json\")\n"
     "        if (f.exists()) sha(f.readText()) else \"empty\"\n"
     "    } catch (_: Throwable) { \"err\" }"),
    ('        append(" · 角色映射 ").append(if (castEnabled(c)) "开" else "关")\n', ""),
    ("        // R73 · 只报软件自身状态；语言模型时代的「上下文 / 线程 / 温度 / 仅非思考」整套退场。",
     "        // 只报软件自身状态与声音配置。"),
])

# ── 2. MixerDraft.kt ───────────────────────────────────────────────────────
rw("app/src/main/java/com/tingben/app/core/MixerDraft.kt", [
    ("    val castEnabled: Boolean = true,\n", ""),
    ("            nonVerbal = Prefs.nonVerbal(c),\n            castEnabled = Prefs.castEnabled(c)\n",
     "            nonVerbal = Prefs.nonVerbal(c)\n"),
    ("        Prefs.setCastEnabled(c, d.castEnabled)\n", ""),
    (" * 边界（R39 §3 守恒）：不碰 4B 设置、prompt v2、两条制作路径、bucket() 分桶、\n"
     " * TaskBookmark 续跑、ProduceService、native/JNI、qt_tts_params 字段、Gradle 依赖。",
     " * 边界：不碰任务书签续跑、ProduceService、native/JNI、qt_tts_params 字段、Gradle 依赖。"),
    ("     * voiceStamp（旧 WAV 作废由 R14 既有机制负责），再各写一条可复盘日志。",
     "     * voiceStamp（旧 WAV 作废由指纹比对负责），再各写一条可复盘日志。"),
])

# ── 3. Pipeline.kt ─────────────────────────────────────────────────────────
rw("app/src/main/java/com/tingben/app/core/Pipeline.kt", [
    ("        // R14 · 声音指纹：音色描述 / 角色表 / 采样 / instruct 模板 任一变化，\n"
     "        // 旧 WAV 必须作废，否则「续跑」会跳过旧块、听起来还是旧声音，界面还假装续上了。\n"
     "        // 只删 WAV，文本切分结果留着不重算。\n"
     "        // 第一次写指纹时不删（默认配置下 instruct 与旧版完全一致，旧 WAV 仍有效）。\n"
     "        val knobs = Prefs.ttsKnobs(c)\n"
     "        val globalVoice = Prefs.voiceDescription(c)\n"
     "        val castOn = Prefs.castEnabled(c)\n"
     "        val cast = if (castOn) VoiceCast.reconcile(c) else emptyList()\n"
     "        val voiceStamp = Prefs.voiceStamp(c)\n"
     "        val stampFile = File(work, \"voice_stamp.txt\")\n"
     "        try {\n"
     "            if (stampFile.exists() && stampFile.readText().trim() != voiceStamp) {\n"
     "                val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(\".wav\") } ?: emptyList()\n"
     "                killed.forEach { it.delete() }\n"
     "                EventLog.log(EventLog.Kind.PIPELINE, \"VOICE_INVALIDATE\",\n"
     "                    \"声音配置变了，作废 ${killed.size} 段旧音频，分段保留\")\n"
     "            }\n"
     "            stampFile.writeText(voiceStamp)\n"
     "        } catch (_: Throwable) { }\n"
     "        EventLog.log(EventLog.Kind.ENGINE, \"VOICE_MODE\",\n"
     "            \"voice_design · 指纹=$voiceStamp · 角色=${cast.size} · seed=${knobs.seed}\" +\n"
     "                \" · temp=${knobs.temperature} · lang=chinese（官方 README：目标语言已知时显式传，别用 auto）\")",
     "        // 声音指纹：音色描述 / 采样 / instruct 模板 任一变化，旧 WAV 必须作废。\n"
     "        // 只删 WAV，文本切分结果留着不重算。\n"
     "        // 双指纹兼容：旧版指纹含角色表项，命中 legacy 就不作废，只改写成新值，\n"
     "        // 避免升级后把用户已经做好的音频一次性删光。\n"
     "        val knobs = Prefs.ttsKnobs(c)\n"
     "        val globalVoice = Prefs.voiceDescription(c)\n"
     "        val voiceStamp = Prefs.voiceStamp(c)\n"
     "        val legacyStamp = Prefs.legacyVoiceStamp(c)\n"
     "        val stampFile = File(work, \"voice_stamp.txt\")\n"
     "        try {\n"
     "            val old = if (stampFile.exists()) stampFile.readText().trim() else \"\"\n"
     "            val changed = stampFile.exists() && old != voiceStamp && old != legacyStamp\n"
     "            if (changed) {\n"
     "                val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(\".wav\") } ?: emptyList()\n"
     "                killed.forEach { it.delete() }\n"
     "                EventLog.log(EventLog.Kind.PIPELINE, \"VOICE_INVALIDATE\",\n"
     "                    \"声音配置变了，作废 ${killed.size} 段旧音频，分段保留\")\n"
     "            }\n"
     "            if (old != voiceStamp && !changed) {\n"
     "                EventLog.log(EventLog.Kind.PIPELINE, \"VOICE_STAMP_MIGRATE\",\n"
     "                    \"旧指纹迁移到新版，已合成音频保持不变\")\n"
     "            }\n"
     "            stampFile.writeText(voiceStamp)\n"
     "        } catch (_: Throwable) { }\n"
     "        EventLog.log(EventLog.Kind.ENGINE, \"VOICE_MODE\",\n"
     "            \"voice_design · 指纹=$voiceStamp · seed=${knobs.seed}\" +\n"
     "                \" · temp=${knobs.temperature} · lang=chinese（官方 README：目标语言已知时显式传，别用 auto）\")"),
    ("        val nonVerbalOn = Prefs.nonVerbal(c)\n"
     "        var unmapped = 0\n"
     "        var strippedTotal = 0\n"
     "        val toSpeak = annotated.map { ann ->\n"
     "            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann\n"
     "            // R14 · 身份层（角色专属 > 全局）由用户/角色表固定，全局音色决定声音。\n"
     "            // 不暂停长任务：未映射的角色沿用全局音色，并如实记一条，角色页里能看见待确认。\n"
     "            val role = if (castOn) VoiceCast.resolve(c, ann.speaker) else null\n"
     "            if (castOn && !VoiceCast.isNarrator(ann.speaker) && role == null) unmapped++\n"
     "            val identity = role?.description?.takeIf { it.isNotBlank() } ?: globalVoice\n"
     "            // R19 · 程序层兜底：指令里混进的音色身份词在这里剥掉，避免逐段换声。\n"
     "            // R20 · 本次风格层（朗读档位/语速/情绪强度）挂到现成的第二层，零签名改动。\n"
     "            // 只改 instruct，text 原样，gate 不受影响。\n"
     "            val built = VoiceProfile.buildWithReport(identity, config.styleInstruct(), base.instruct)",
     "        val nonVerbalOn = Prefs.nonVerbal(c)\n"
     "        var strippedTotal = 0\n"
     "        val toSpeak = annotated.map { ann ->\n"
     "            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann\n"
     "            // 声音只由调音台的全局音色决定；拟声表达与本次风格层只改 instruct，正文一字不动。\n"
     "            val built = VoiceProfile.buildWithReport(globalVoice, config.styleInstruct(), base.instruct)"),
    ("        EventLog.log(EventLog.Kind.ENGINE, \"VOICE_IDENTITY\",\n"
     "            \"身份层=${if (globalVoice.isBlank()) \"默认锚点\" else \"自定义\"} · \" +\n"
     "                    \"版本=$INSTRUCT_TEMPLATE_VERSION · 角色表=${cast.size} · \" +\n"
     "                    \"seed=${knobs.seed} · ${annotated.size} 段\")",
     "        EventLog.log(EventLog.Kind.ENGINE, \"VOICE_IDENTITY\",\n"
     "            \"身份层=${if (globalVoice.isBlank()) \"默认锚点\" else \"自定义\"} · \" +\n"
     "                    \"版本=$INSTRUCT_TEMPLATE_VERSION · \" +\n"
     "                    \"seed=${knobs.seed} · ${annotated.size} 段\")"),
    ("        if (unmapped > 0) EventLog.log(EventLog.Kind.PIPELINE, \"VOICE_UNMAPPED\",\n"
     "            \"$unmapped 段角色还没指定音色，暂用全局音色；去设置页「角色音色」确认\")\n", ""),
    ("                // R22 · 让「这一段到底用什么指令读的」可见：这里是最终 instruct（含身份层/风格层），\n"
     "                // 这里显示的是最终送进声音模型的指令。voice_design 的 speaker 必须传 null。",
     "                // 让「这一段到底用什么指令读的」可见：最终 instruct（含身份层/风格层）。\n"
     "                // voice_design 的 speaker 必须传 null。"),
    ("                        // 官方 Qwen3-TTS README 第 167 行：目标语言已知时显式传，别用 auto。\n"
     "                        // 全中文书固定 chinese；lang 参与合成结果，故 INSTRUCT_TEMPLATE_VERSION 升 4 作废旧 WAV。",
     "                        // 官方 Qwen3-TTS README：目标语言已知时显式传，别用 auto；全中文书固定 chinese。"),
])

# ── 4. 物理删两个文件 ──────────────────────────────────────────────────────
for name in ("CastPresets.kt", "VoiceCast.kt"):
    p = CORE / name
    if not p.exists():
        sys.exit(f"待删文件不存在: {name}")
    p.unlink()
    print("删", p.relative_to(ROOT))

# ── 5. 仍活文件：注释旧词清理（逻辑零改动）────────────────────────────────
COMMENT = {
 "app/src/main/java/com/tingben/app/core/AudioLibrary.kt": [
   (" * R61 · 目录页四列要按章判「已合成 / 已标注且可整本顺序播」，",
    " * R61 · 目录页按章判「已合成 / 可整本顺序播」，")],
 "app/src/main/java/com/tingben/app/core/Mixer.kt": [
   (" *    标注「调低 sub_temperature 可减少语速抖动和电音感」）。",
    " *    注明「调低 sub_temperature 可减少语速抖动和电音感」）。"),
   (" * 不动 native、不改 prompt、不新增权限。所有「当前生效值」都从 Prefs/ProduceConfig 读，",
    " * 不动 native、不新增权限。所有「当前生效值」都从 Prefs/ProduceConfig 读，")],
 "app/src/main/java/com/tingben/app/core/ProduceConfig.kt": [
   (" * gapMs 只在合并时插静音、分段 WAV 内容不变；gateRetries 只影响标注重试 —— 都不进指纹。",
    " * gapMs 只在合并时插静音、分段 WAV 内容不变；gateRetries 只影响失败重做 —— 都不进指纹。")],
 "app/src/main/java/com/tingben/app/core/SegPlan.kt": [
   (" *    `TaskResume` 书签 / native / prompt v2 —— 这是用户点名过的「改一处联动很多处」",
    " *    `TaskResume` 书签 / native —— 这是用户点名过的「改一处联动很多处」"),
   ("    /** null = 旧缓存没记来源，不猜、不冒充已标注。 */",
    "    /** null = 旧缓存没记来源，不猜、不冒充已知。 */"),
   ("     * 重合成这一段：只删该段 wav，其余段与标注一律不动。",
    "     * 重合成这一段：只删该段 wav，其余段一律不动。"),
   ("     * 合成循环见 wav 不存在就重合成；标注阶段 ann 全在 → 不会重跑 4B。",
    "     * 合成循环见 wav 不存在就重合成，其余段直接复用。"),
   ("     * 重新标注本块：删该块 ann/flag/src + 该块所有段的 wav，其余块不动。",
    "     * 重做本块：删该块缓存 + 该块所有段的 wav，其余块不动。"),
   ("     * 续跑时该块 ann 不存在 → 重跑 4B；对应段 wav 不存在 → 重合成。",
    "     * 续跑时该块缓存不存在 → 重切；对应段 wav 不存在 → 重合成。"),
   ("    /** 一眼判定：模型真标注 / 直读 / 旧缓存未知 各几段。 */",
    "    /** 一眼判定：直读 / 旧缓存未知 各几段。 */")],
 "app/src/main/java/com/tingben/app/core/Workbench.kt": [
   (" * 来源；没有 segmap 的旧缓存才降级扫目录，并一律标 UNKNOWN，不冒充已标注。",
    " * 来源；没有 segmap 的旧缓存才降级扫目录，并一律标 UNKNOWN。"),
   ("    /** 判定条用：模型真标注 / 直读 / 未知。没有 segmap 时返回 null，UI 如实说「还没数据」。 */",
    "    /** 判定条用：直读 / 未知。没有 segmap 时返回 null，UI 如实说「还没数据」。 */")],
 "app/src/main/java/com/tingben/app/core/TaskResume.kt": [
   (" * 真正的进度以磁盘上的 ann_ / chunk_ 文件为准（realProgress 重算），",
    " * 真正的进度以磁盘上的分段 WAV 为准（realProgress 重算），"),
   (" * 标注阶段数 ann_，合成阶段数 wav；返回「已完成多少朗读段」的近似值供 UI 显示，",
    " * 合成阶段数 wav；返回「已完成多少朗读段」的近似值供 UI 显示，")],
 "app/src/main/java/com/tingben/app/core/VoiceProfile.kt": [
   (" *   ① 身份层 = 全局 / 角色的固定描述（用户可编辑，全书复用）",
    " *   ① 身份层 = 全局固定描述（用户可编辑，全书复用）"),
   (" *   ② 角色风格层 = 该角色稳定的表演习惯（可选）",
    " *   ② 风格层 = 本次朗读的表演习惯（可选）"),
   (" *   ③ 本段情绪层 = 4B 逐块产出的 instruct（只描述「此刻怎么说」）",
    " *   ③ 本段情绪层 = 断句时给的 instruct（只描述「此刻怎么说」）"),
   (" *   B. v2 提示词虽明禁音色身份词，abliterated 权重遵循不稳，4B 偶尔仍漏写\n"
    " *      「年轻男声 / 低沉沙哑」，叠进本段情绪后等于每段换一个人。",
    " *   B. 指令里偶尔仍会混进音色身份词（「年轻男声 / 低沉沙哑」），\n"
    " *      叠进本段情绪后等于每段换一个人。"),
   ("// 合成结果会变，必须让旧分段 WAV 作废；标注结果（跑了几分钟的 4B 产物）保留复用。",
    "// 合成结果会变，必须让旧分段 WAV 作废；切分结果保留复用。"),
   (" * 把 4B 漏写的身份词从「本段情绪」里剥掉。",
    " * 把混进来的身份词从「本段情绪」里剥掉。"),
   (" * @return 清理后的文本 + 命中次数（写进事件日志，便于判断 4B 到底漏了多少）",
    " * @return 清理后的文本 + 命中次数（写进事件日志）"),
   ("    /** 给标注审计用：本段指令里是否混进了音色身份词（只查 4B 原始产出，不做剥离）。 */",
    "    /** 审计用：本段指令里是否混进了音色身份词（不剥离）。 */"),
   ("     * @param block     4B 产出的本段情绪指令（先 sanitize）",
    "     * @param block     本段情绪指令（先 sanitize）"),
   ("    /** 角色页预览用：只拿身份层 + 角色风格，不带本段情绪。 */",
    "    /** 试听预览用：只拿身份层 + 风格层，不带本段情绪。 */")],
}
for rel, pairs in COMMENT.items():
    rw(rel, pairs)

print("R77 apply 完成")
