package com.tingben.app.core

import org.json.JSONObject

/**
 * 「本次制作」预设（R20 / P1①）。
 *
 * 不手搓依据（逐条见 docs/P1_制作准备页_联动表.md §1）：
 * - 单一不可变配置 data class：OfflineLLM `SettingsUiState.kt`
 * - KEY 常量与 DEFAULT 常量、缺字段回默认：OfflineLLM `SettingsRepository.kt` / PocketPal 版本迁移
 * - 「新建 Session 时选预设」：Sansui233/index-tts-webui
 * - 参数分组 / break_duration 段间静音 / 质量预设：epub_to_audiobook Provider Configuration Tabs
 * - 枚举档位 → 自然语言导演指令：qwentts.cpp/examples/tts.sh 官方 voice_design
 *
 * 边界：**只影响这一次制作，不写回 Prefs**（跨书默认值在设置页）。
 * 随 TaskInfo 原子落盘，进程被杀后回来点「继续制作」仍沿用同一套，不会风格断层。
 */

enum class ReadingStyle(val label: String, val hint: String, val instruct: String) {
    NARRATION("叙事", "平稳讲故事，适合大部分小说", "整体保持平稳叙述的节奏，不刻意演戏"),
    DIALOGUE("对白", "对白更分明，旁白退后", "对白部分语气更分明，旁白保持克制退后"),
    DRAMA("广播剧", "情绪拉满，停顿更明显", "按广播剧方式演绎，情绪层次拉开，关键处留出停顿")
}

enum class Pace(val label: String, val instruct: String) {
    SLOW("偏慢", "整体语速偏慢，句间多留一点停顿"),
    NORMAL("正常", "语速自然适中"),
    FAST("偏快", "整体语速略快，少拖尾音")
}

enum class Intensity(val label: String, val instruct: String) {
    SUBTLE("克制", "情绪表达克制，不要嘶吼或夸张哭腔"),
    STANDARD("标准", "情绪按文本自然表达"),
    FULL("饱满", "情绪可以更饱满，允许明显的强弱对比")
}

data class ProduceConfig(
    val style: ReadingStyle = ReadingStyle.NARRATION,
    val pace: Pace = Pace.NORMAL,
    val intensity: Intensity = Intensity.STANDARD,
    val gapMs: Int = DEFAULT_GAP_MS,
    val gateRetries: Int = 1,
    // R50 · 表演层三枚自定义小框合并后的附加语气短语（上层：只拼 instruct，不是模型字段）。
    // 格式「朗读:…｜语速:…｜情绪:…」，读回时按前缀拆回三个框；旧数据缺字段一律空串。
    val extraInstruct: String = ""
) {
    /**
     * 身份层之外的「本次风格层」，挂到 VoiceProfile 现成的第二层（roleStyle），
     * 不改 build/buildWithReport 签名。只拼 instruct，Ann.text 一个字不碰。
     * R50：末尾按需拼自定义语气，空串时输出与旧版逐字相同。
     */
    fun styleInstruct(): String = listOf(
        style.instruct, pace.instruct, intensity.instruct, extraInstruct.trim()
    ).filter { it.isNotBlank() }.joinToString("；")

    /**
     * 只含会影响**已合成 WAV** 的项。
     * gapMs 只在合并时插静音、分段 WAV 内容不变；gateRetries 只影响标注重试 —— 都不进指纹。
     * R50：附加语气会改变 instruct，必须进指纹，否则改了自定义词却沿用旧 WAV。
     */
    fun audioStamp(): String = sha(listOf(
        style.name, pace.name, intensity.name, extraInstruct
    ).joinToString("|"))

    fun summary(): String = "${style.label} · ${pace.label} · ${intensity.label} · " +
            "段间静音 ${gapMs}ms · 失败重做 $gateRetries 次"

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("style", style.name)
        .put("pace", pace.name)
        .put("intensity", intensity.name)
        .put("gapMs", gapMs)
        .put("gateRetries", gateRetries)
        .put("extraInstruct", extraInstruct)

    companion object {
        const val SCHEMA = 2
        const val DEFAULT_GAP_MS = 300

        val GAP_OPTIONS = listOf(0, 300, 600)
        val RETRY_OPTIONS = listOf(0, 1, 2)
        // R50 · 自定义框允许预设之外的合法值；fromJson 改范围校验，不再静默回默认。
        val GAP_RANGE = 0..5000
        val RETRY_RANGE = 0..10
        const val EXTRA_SEP = "｜"
        val EXTRA_KEYS = listOf("朗读", "语速", "情绪")

        /** 三个小框 → 一条附加语气；空值不写，保证空串时与旧版一致。 */
        fun packExtra(m: Map<String, String>): String = EXTRA_KEYS.mapNotNull { k ->
            m[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$k:$it" }
        }.joinToString(EXTRA_SEP)

        /** 一条附加语气 → 三个小框；旧数据无字段时返回空表。 */
        fun unpackExtra(s: String): Map<String, String> = s.split(EXTRA_SEP)
            .mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0 || part.substring(0, i) !in EXTRA_KEYS) null
                else part.substring(0, i) to part.substring(i + 1)
            }.toMap()

        /**
         * 宽容迁移：旧书签没有 config、字段缺失、枚举值不认识，一律回默认，绝不崩。
         * 抄 PocketPal completionSettingsVersions.ts 的思路。
         */
        fun fromJson(o: JSONObject?): ProduceConfig {
            if (o == null) return ProduceConfig()
            fun <T : Enum<T>> pick(key: String, values: Array<T>, default: T): T {
                val n = o.optString(key, default.name)
                return values.firstOrNull { it.name == n } ?: default
            }
            return ProduceConfig(
                style = pick("style", ReadingStyle.values(), ReadingStyle.NARRATION),
                pace = pick("pace", Pace.values(), Pace.NORMAL),
                intensity = pick("intensity", Intensity.values(), Intensity.STANDARD),
                // R50：预设 chip 之外允许自定义合法值，范围外才回默认，绝不静默丢弃。
                gapMs = o.optInt("gapMs", DEFAULT_GAP_MS).let { g ->
                    if (g in GAP_RANGE) g else DEFAULT_GAP_MS
                },
                gateRetries = o.optInt("gateRetries", 1).let { r ->
                    if (r in RETRY_RANGE) r else 1
                },
                extraInstruct = o.optString("extraInstruct", "")
            )
        }
    }
}
