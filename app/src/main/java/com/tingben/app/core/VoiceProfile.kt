package com.tingben.app.core

/**
 * instruct 分层（R14 建，R19 钉死）。
 *
 * 抄 QwenLM/Qwen3-TTS 官方 voice_design 示例 + qwentts.cpp/examples/tts.sh：
 * 官方 voice_design 的 instruct 是「造声」属性串（性别/年龄/音区/音色/节奏），
 * 用来定义一个稳定的声音身份；逐块的情绪则是另一层。
 *
 * 三层：
 *   ① 身份层 = 全局 / 角色的固定描述（用户可编辑，全书复用）
 *   ② 角色风格层 = 该角色稳定的表演习惯（可选）
 *   ③ 本段情绪层 = 4B 逐块产出的 instruct（只描述「此刻怎么说」）
 *
 * R19 · 用户实测「每一个小段音色都不一样」，两个真因：
 *   A. 全局音色描述默认留空 ⇒ 每段根本没有身份锚点，voice_design 每次重新「造声」；
 *   B. v2 提示词虽明禁音色身份词，abliterated 权重遵循不稳，4B 偶尔仍漏写
 *      「年轻男声 / 低沉沙哑」，叠进本段情绪后等于每段换一个人。
 * 修法：① 给一个 DEFAULT_IDENTITY 兜底，留空也有锚点；② 程序层 sanitize()
 * 把身份词剥掉，不指望模型自觉。只动 instruct，Ann.text 一个字不碰，逐字 gate 不受影响。
 *
 * ⚠️ 边界：voice_design 每次 synthesize 仍会按 instruct 重新采样音色，
 * 固定 seed(42) + 固定身份前缀能显著收敛，但不能保证 100% 比特级一致。
 * 要彻底根治得换 CustomVoice 权重用固定 speaker id —— 那是换模型，不在本轮。
 */
// R20 · 升 3：instruct 多了「本次风格层」（朗读档位/语速/情绪强度）。
// 版本进 Prefs.voiceStamp ⇒ 旧分段 WAV 自动作废、标注保留，复用 R14 既有机制。
const val INSTRUCT_TEMPLATE_VERSION = 3

/** 留空时的默认身份锚点：只描述「谁在念」，不带任何情绪/节奏。 */
const val DEFAULT_IDENTITY = "清晰自然的中文普通话叙述声，音区适中，无明显口音"

object VoiceProfile {

    /**
     * 明确的「音色身份」词。宁可少列也不误杀表演层：
     * 「声音微微发颤」「气声偏重」「尾音下沉」是发声状态，必须保留。
     * 长的排前面，避免先被短词切碎。
     */
    private val IDENTITY_TERMS = listOf(
        "年轻男声", "中年男声", "老年男声", "年轻女声", "中年女声", "老年女声",
        "男声", "女声", "童声", "少女音", "少年音", "老人声", "娃娃音",
        "低沉", "沙哑", "浑厚", "清亮", "甜美", "磁性", "尖细", "洪亮", "粗哑",
        "圆润", "厚重", "音色", "嗓音", "声线", "音区", "音域", "口音",
        "年轻", "年迈", "苍老", "稚嫩", "少年", "少女", "中年", "老年", "孩童", "儿童"
    )

    /**
     * 把 4B 漏写的身份词从「本段情绪」里剥掉。
     * 用替换而不是整段丢弃：情绪词和身份词常写在同一句，丢整段会连「压低质问」一起没了。
     * @return 清理后的文本 + 命中次数（写进事件日志，便于判断 4B 到底漏了多少）
     */
    fun sanitize(block: String): Pair<String, Int> {
        var s = block
        var hits = 0
        IDENTITY_TERMS.forEach { t ->
            if (s.contains(t)) {
                hits += occurrences(s, t)
                s = s.replace(t, "")
            }
        }
        // 删词后留下的空槽 / 连续标点要收干净，否则 TTS 会读到「，，压低质问」
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

    /**
     * @param global    全局固定音色描述（留空 = 用 DEFAULT_IDENTITY，不再「无锚点」）
     * @param roleStyle 角色稳定风格（可空）
     * @param block     4B 产出的本段情绪指令（先 sanitize）
     */
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

    /** 角色页预览用：只拿身份层 + 角色风格，不带本段情绪。 */
    fun identity(global: String, roleStyle: String?): String =
        build(global, roleStyle, "").ifBlank { DEFAULT_IDENTITY }
}
