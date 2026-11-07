package com.tingben.app.core

enum class ParamKind(val label: String) {
    ENGINE("到引擎"),
    UPPER("上层"),
    LOCKED("结构"),
}

data class ParamSpec(
    val key: String,
    val official: String,
    val label: String,
    val def: String,
    val hint: String,
    val source: String,
    val options: List<String>,
    val kind: ParamKind = ParamKind.ENGINE,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String = ""
) {
    val isDefault: (TtsKnobs) -> Boolean = { k ->
        val a = read(k).toFloatOrNull()
        val b = def.toFloatOrNull()
        if (a != null && b != null) a == b else read(k) == def
    }
    fun read(k: TtsKnobs): String = when (key) {
        "seed" -> k.seed.toString()
        "maxNewTokens" -> k.maxNewTokens.toString()
        "temperature" -> trimNum(k.temperature)
        "topK" -> k.topK.toString()
        "topP" -> trimNum(k.topP)
        "repetitionPenalty" -> trimNum(k.repetitionPenalty)
        "subTemperature" -> trimNum(k.subTemperature)
        "subTopK" -> k.subTopK.toString()
        "subTopP" -> trimNum(k.subTopP)
        else -> ""
    }

    val rangeLabel: String
        get() {
            val lo = min
            val hi = max
            return when {
                lo != null && hi != null -> "${fmt(lo)}~${fmt(hi)}"
                lo != null -> "≥${fmt(lo)}"
                hi != null -> "≤${fmt(hi)}"
                else -> "不限"
            }
        }

    fun validate(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return "请填数值"
        val n = s.toDoubleOrNull() ?: return "只能填数字"
        min?.let { if (n < it) return "不能小于 ${fmt(it)}" }
        max?.let { if (n > it) return "不能大于 ${fmt(it)}" }
        if (withValue(TtsKnobs(), s) == null) return when (key) {
            "seed", "maxNewTokens", "topK", "subTopK" -> "请填整数，范围 $rangeLabel"
            else -> "不在允许范围 $rangeLabel"
        }
        return null
    }

    fun withValue(k: TtsKnobs, raw: String): TtsKnobs? {
        val s = raw.trim()
        val n = s.toDoubleOrNull() ?: return null
        min?.let { if (n < it) return null }
        max?.let { if (n > it) return null }
        return when (key) {
            "seed" -> s.toLongOrNull()?.let { k.copy(seed = it) }
            "maxNewTokens" -> s.toIntOrNull()?.let { k.copy(maxNewTokens = it) }
            "temperature" -> s.toFloatOrNull()?.let { k.copy(temperature = it) }
            "topK" -> s.toIntOrNull()?.let { k.copy(topK = it) }
            "topP" -> s.toFloatOrNull()?.let { k.copy(topP = it) }
            "repetitionPenalty" -> s.toFloatOrNull()?.let { k.copy(repetitionPenalty = it) }
            "subTemperature" -> s.toFloatOrNull()?.let { k.copy(subTemperature = it) }
            "subTopK" -> s.toIntOrNull()?.let { k.copy(subTopK = it) }
            "subTopP" -> s.toFloatOrNull()?.let { k.copy(subTopP = it) }
            else -> null
        }
    }

    private fun trimNum(f: Float): String =
        if (f == f.toLong().toFloat()) f.toLong().toString() else "%.3f".format(f).trimEnd('0').trimEnd('.')

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

object ParamCatalog {
    val SAMPLING: List<ParamSpec> = listOf(
        ParamSpec("seed", "qt_tts_params.seed", "采样种子", "42",
            "固定可复现；-1 = 引擎硬件随机（O5:308-310）", "O4/O5",
            listOf("42", "1234", "随机"), min = -1.0),
        ParamSpec("maxNewTokens", "qt_tts_params.max_new_tokens", "单次音频长度上限", "2048",
            "12.5Hz → 单次约 164 秒音频；官方评测也用 2048（O1:465）", "O1/O4/O6",
            listOf("1024", "2048", "4096", "8192"), min = 64.0, max = 8192.0, unit = " token"),
        ParamSpec("temperature", "qt_tts_params.temperature", "主·随机度", "0.9",
            "talker 抽语义码 c0；0 = argmax（O5:307）", "O4/O5/O6",
            listOf("0", "0.4", "0.6", "0.9", "1.1"), min = 0.0, max = 2.0),
        ParamSpec("topK", "qt_tts_params.top_k", "主·候选范围", "50",
            "候选集；<=0 关闭截断（O5:308）", "O4/O5/O6",
            listOf("0", "20", "50", "100"), min = 0.0, max = 100.0, unit = " 个"),
        ParamSpec("topP", "qt_tts_params.top_p", "主·核采样", "1.0",
            "核采样；>=1 关闭截断（O5:308）", "O1/O4/O5/O6",
            listOf("0.8", "0.95", "1.0"), min = 0.0, max = 1.0),
        ParamSpec("repetitionPenalty", "qt_tts_params.repetition_penalty", "重复惩罚", "1.05",
            "采样链第一环：rep → temp → topK → topP（O4:22-23）", "O4/O5/O6",
            listOf("1.0", "1.05", "1.1", "1.2"), min = 1.0, max = 2.0),
        ParamSpec("subTemperature", "qt_tts_params.subtalker_temperature", "副·随机度", "0.9",
            "副解码器抽声学码，独立一套采样（O5:303-307）", "O4/O5/O6",
            listOf("0", "0.4", "0.6", "0.9", "1.1"), min = 0.0, max = 2.0),
        ParamSpec("subTopK", "qt_tts_params.subtalker_top_k", "副·候选范围", "50",
            "声学层候选集（O5:319）", "O5/O6",
            listOf("0", "20", "50", "100"), min = 0.0, max = 100.0, unit = " 个"),
        ParamSpec("subTopP", "qt_tts_params.subtalker_top_p", "副·核采样", "1.0",
            "声学层核采样（O5:320）", "O5/O6",
            listOf("0.8", "0.95", "1.0"), min = 0.0, max = 1.0)
    )

    val UPPER: List<ParamSpec> = listOf(
        ParamSpec("voice", "qt_tts_params.instruct", "音色身份描述", "（必填）",
            "自由文本属性指令，VoiceDesign 的必需输入（O1:201-223 / O4:121-129）", "O1/O3/O4",
            emptyList(), ParamKind.ENGINE),
        ParamSpec("lang", "qt_tts_params.lang", "lang（语言）", "chinese",
            "目标语言已知时显式传，别用 auto（O1:167）", "O1/O5",
            emptyList(), ParamKind.ENGINE),
        ParamSpec("speaker", "qt_tts_params.speaker", "speaker", "null",
            "voicedesign 必须 null；speaker 只属 custom_voice（O5:285-288）", "O1/O5",
            emptyList(), ParamKind.ENGINE),
        ParamSpec("dims", "无（instruct 文本拼装器）", "12 维音色网格", "无",
            "帮你写 instruct 的模板，不是模型字段（ReaderVoice 096b_personas.py）", "O1/O4",
            emptyList(), ParamKind.UPPER),
        ParamSpec("perf", "无（styleInstruct）", "朗读档位 / 语速 / 情绪强度", "叙事 / 正常 / 标准",
            "拼进 instruct 第二层，只影响下一次新建任务", "本项目",
            emptyList(), ParamKind.UPPER),
        ParamSpec("gap", "无（WAV 合并）", "段间静音", "300ms",
            "合并时插 48B/ms，不进模型", "本项目",
            emptyList(), ParamKind.UPPER),
        ParamSpec("nonVerbal", "无（合成前改 instruct）", "拟声表达", "开",
            "命中哭笑叹气只加导演指令，正文不改", "本项目",
            emptyList(), ParamKind.UPPER)
    )

    val LOCKED: List<ParamSpec> = listOf(
        ParamSpec("max_batch", "context 级", "合成并发", "1（CPU 单 worker）",
            "不在每次请求参数里", "O5", emptyList(), ParamKind.LOCKED),
        ParamSpec("codec", "context 级", "codec 24s 分块 / 24kHz mono", "固定",
            "引擎内部固定", "O5", emptyList(), ParamKind.LOCKED),
        ParamSpec("speed", "不存在", "speed / pitch 数值字段", "无",
            "qt_tts_params 里没有；播放端倍速 ≠ 合成端", "O5", emptyList(), ParamKind.LOCKED),
        ParamSpec("speakers", "qt_n_speakers", "speaker 表", "voicedesign 返回 0",
            "换 CustomVoice 权重才有 9 个音色", "O1/O5", emptyList(), ParamKind.LOCKED)
    )

    val DOCS: List<Pair<String, String>> = listOf(
        "O1 官方仓库 README" to "QwenLM/Qwen3-TTS · §Voice Design(201-239)、§146 generate kwargs、§167 language、§465 评测 2048",
        "O2 官方模型卡" to "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign · 模型能力与 10 语言",
        "O3 Qwen 官方博客" to "qwen.ai/blog?id=qwen3tts-0115 · 模型列表与指令控制",
        "O4 qwentts.cpp README" to "ServeurpersoCom/qwentts.cpp · §14/§22-23 采样链、§121-129 voice design、§153-156 可覆盖字段",
        "O5 随包 qwen.h" to "app/src/main/cpp/qwen.h:277-356 · qt_tts_params 全字段与模式规则",
        "O6 采样默认值" to "src/sampling-defaults.h · 9 个默认值逐字",
        "O7 CLI 采样表" to "Qwen3 TTS Sampling：--temperature 0.9 / --top-k 50 / --top-p 1.0 / --repetition-penalty 1.05",
        "O8 官方生成参数与范围" to "temperature 0~2 / top_k 1~100 / top_p 0~1 / repetition_penalty 1~2 / max_new_tokens 64~8192；subtalker 同套默认 0.9/50/1.0"
    )

    fun find(key: String): ParamSpec? = SAMPLING.find { it.key == key }
}
