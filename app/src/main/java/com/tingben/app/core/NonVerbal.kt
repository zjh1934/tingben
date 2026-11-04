package com.tingben.app.core

/**
 * 非语言表达（哭腔 / 笑 / 叹气 / 抽泣 / 咳嗽 / 喘 / 倒吸气）。
 *
 * R11 调研结论（见 docs/NONVERBAL_RESEARCH.md）：
 * - 手上的 Qwen3-TTS-12Hz-1.7B-VoiceDesign **不解析** [laughter] / [sigh] 这类行内标签，
 *   写进 text 会被当成字念出来（Voicebox 文档明确点名 Qwen3-TTS）。
 * - 但它的 instruct 能吃自然语言导演指令，官方示例就有「以极度悲伤、带着明显哭腔的语气」。
 * - Alexandria 走的是另一条路：让 LLM 写 "Haha!" / "Sniff…" 这种**可发音拟声词**当真实文本。
 *   那条路会往成品里加原文没有的字，和我们「正文一字不改」的契约冲突，暂不采用。
 *
 * 所以本版做法：**text 一个字不动**（逐字 gate 完全不受影响），
 * 只在合成前扫描原文里已经存在的非语言描写，给 instruct 追加一句导演指令。
 * 不换模型、不重下权重、不加权限、不联网。
 */
object NonVerbal {

    /** 原文里出现这些词，说明该处本来就有非语言描写。顺序 = 优先级。 */
    private val cues = listOf(
        // 哭 / 悲
        listOf("呜咽", "哽咽", "抽泣", "啜泣", "哭腔", "哭了", "哭出", "泪", "泣不成声")
                to "带出压抑的哭腔，声音微微发颤，气声偏重，句尾下沉",
        // 笑
        listOf("哈哈", "笑出", "笑了", "轻笑", "冷笑", "苦笑", "笑声", "咯咯", "嗤笑")
                to "在对应处带出一点轻声的笑，用气声，不夸张，不另加字词",
        // 叹 / 呼吸
        listOf("叹了口气", "叹气", "长叹", "叹息", "呼出一口", "深深吸", "倒吸", "吸了口气")
                to "在对应处先有一口听得见的呼气或吸气，语速略放缓，再接着说",
        // 咳嗽 / 喘
        listOf("咳嗽", "咳了", "喘着", "喘息", "气喘", "上气不接下气")
                to "在对应处带一点短促的气声或咳嗽感，别盖住正文",
        // 颤 / 怕
        listOf("颤抖", "发抖", "声音发颤", "倒吸一口凉气", "倒抽")
                to "声音略带发颤，语速放慢，尾音轻一点"
    )

    /** 命中就返回导演短语，没命中返回 null。 */
    fun detect(text: String): String? {
        for ((words, direction) in cues) {
            if (words.any { text.contains(it) }) return direction
        }
        return null
    }

    /**
     * 合成前调用。只改 instruct，text / speaker 原样返回。
     * 追加的指令明确要求「不另加字词」，避免模型自作主张补拟声词破坏成品契约。
     */
    fun apply(ann: Ann): Ann {
        val d = detect(ann.text) ?: return ann
        val base = ann.instruct.trim().removeSuffix("。").removeSuffix("，")
        val extra = "$d；除原文外不要另加任何字词，不要把括号或说明念出来"
        return Ann(ann.text, ann.speaker, if (base.isBlank()) extra else "$base。$extra")
    }

    /** 统计用：一本书里有多少段命中，写进事件日志，方便判断效果有没有被触发。 */
    fun count(anns: List<Ann>): Int = anns.count { detect(it.text) != null }
}
