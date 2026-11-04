package com.tingben.app.core

/**
 * instruct 分层（R14 · 抄 QwenLM/Qwen3-TTS 官方 voice_design 示例 + qwentts.cpp/examples/tts.sh）。
 *
 * 官方 voice_design 的 instruct 是「造声」属性串（性别/年龄/音区/音色/节奏），
 * 用来定义一个稳定的声音身份；逐块的情绪则是另一层。
 * 之前 4B 每段都自己写「年轻男声、低沉」这类身份，同一角色跨块会飘 —— 这是听感单薄的根因之一。
 *
 * 现在拆三层：
 *   ① 身份层 = 全局 / 角色的固定描述（用户可编辑，全书复用）
 *   ② 角色风格层 = 该角色稳定的表演习惯（可选）
 *   ③ 本段情绪层 = 4B 逐块产出的 instruct（只描述「此刻怎么说」）
 *
 * 只拼字符串，不交给 4B；`Ann.text` 一个字都不碰，逐字 gate 完全不受影响。
 */
const val INSTRUCT_TEMPLATE_VERSION = 1

object VoiceProfile {

    /**
     * @param global    全局固定音色描述（留空 = 沿用原来的单一默认声音）
     * @param roleStyle 角色稳定风格（可空）
     * @param block     4B 产出的本段情绪指令
     */
    fun build(global: String, roleStyle: String?, block: String): String {
        val g = global.trim()
        val r = roleStyle?.trim().orEmpty()
        val b = block.trim().removeSuffix("。").removeSuffix("，")
        if (g.isBlank() && r.isBlank()) return block.trim()
        return buildString {
            if (g.isNotBlank()) append(g.removeSuffix("。").removeSuffix("，")).append("。")
            if (r.isNotBlank()) append("\n[角色风格] ").append(r.removeSuffix("。"))
            if (b.isNotBlank()) append("\n[本段情绪] ").append(b)
        }.trim()
    }

    /** 角色页预览用：只拿身份层 + 角色风格，不带本段情绪。 */
    fun identity(global: String, roleStyle: String?): String =
        build(global, roleStyle, "").ifBlank { "（留空，沿用默认声音）" }
}
