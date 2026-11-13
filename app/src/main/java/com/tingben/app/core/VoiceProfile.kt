package com.tingben.app.core

const val INSTRUCT_TEMPLATE_VERSION = 4

const val DEFAULT_IDENTITY = "清晰自然的中文普通话叙述声，音区适中，无明显口音"

object VoiceProfile {

    private val IDENTITY_TERMS = listOf(
        "年轻男声", "中年男声", "老年男声", "年轻女声", "中年女声", "老年女声",
        "男声", "女声", "童声", "少女音", "少年音", "老人声", "娃娃音",
        "低沉", "沙哑", "浑厚", "清亮", "甜美", "磁性", "尖细", "洪亮", "粗哑",
        "圆润", "厚重", "音色", "嗓音", "声线", "音区", "音域", "口音",
        "年轻", "年迈", "苍老", "稚嫩", "少年", "少女", "中年", "老年", "孩童", "儿童"
    )

    fun sanitize(block: String): Pair<String, Int> {
        var s = block
        var hits = 0
        IDENTITY_TERMS.forEach { t ->
            if (s.contains(t)) {
                hits += occurrences(s, t)
                s = s.replace(t, "")
            }
        }
        s = Regex("[，,、;；]{2,}").replace(s, "，")
        s = Regex("^[，,、;；。\\s]+|[，,、;；。\\s]+$").replace(s, "")
        return s.trim().removeSuffix("。").removeSuffix("，") to hits
    }

    private fun occurrences(s: String, sub: String): Int {
        var n = 0
        var i = s.indexOf(sub)
        while (i >= 0) { n++; i = s.indexOf(sub, i + sub.length) }
        return n
    }

    fun buildWithReport(global: String, roleStyle: String?, block: String): Pair<String, Int> {
        val (b, hits) = sanitize(block)
        val g = (global.trim().ifBlank { DEFAULT_IDENTITY }).removeSuffix("。").removeSuffix("，")
        val r = roleStyle?.trim().orEmpty().removeSuffix("。").removeSuffix("，")
        if (g.isBlank() && r.isBlank()) return b to hits
        return buildString {
            append(g).append("。")
            if (r.isNotBlank()) append("\n[风格层] ").append(r)
            if (b.isNotBlank()) append("\n[本段情绪] ").append(b)
        }.trim() to hits
    }

    fun build(global: String, roleStyle: String?, block: String): String =
        buildWithReport(global, roleStyle, block).first
}
