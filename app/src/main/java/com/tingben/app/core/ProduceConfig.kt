package com.tingben.app.core

import org.json.JSONObject

// R221 · 三个枚举停用：不再拼进 instruct。留着只为读旧任务 JSON /
// 旧调音台预设不炸，字段本身对合成零影响。
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
    val extraInstruct: String = ""
) {
    // R221 · 风格层只留用户自己填的附加语气，不再叠三句写死预设。
    // voice_design 不再被「平稳叙述/语速适中」冲淡身份层；custom_voice
    // 不再和自填 cvStyle 拼成一串；base 本来就忽略 instruct。
    fun styleInstruct(): String = extraInstruct.trim()

    // R219 · 切分代际也进指纹：粒度一变，旧套分段整体归档，不混进新书
    fun audioStamp(): String = sha(listOf(
        style.name, pace.name, intensity.name, extraInstruct,
        TextSplitter.SPLIT_EPOCH.toString()
    ).joinToString("|"))

    // R221 · 日志里也不再显示已停用的三档
    fun summary(): String = "段间静音 ${gapMs}ms · 失败重做 $gateRetries 次" +
            (if (extraInstruct.isBlank()) "" else " · 附加语气：${extraInstruct.trim()}")

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
        val GAP_RANGE = 0..5000
        val RETRY_RANGE = 0..10
        const val EXTRA_SEP = "｜"
        val EXTRA_KEYS = listOf("朗读", "语速", "情绪")

        fun packExtra(m: Map<String, String>): String = EXTRA_KEYS.mapNotNull { k ->
            m[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { "$k:$it" }
        }.joinToString(EXTRA_SEP)

        fun unpackExtra(s: String): Map<String, String> = s.split(EXTRA_SEP)
            .mapNotNull { part ->
                val i = part.indexOf(':')
                if (i <= 0 || part.substring(0, i) !in EXTRA_KEYS) null
                else part.substring(0, i) to part.substring(i + 1)
            }.toMap()

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
