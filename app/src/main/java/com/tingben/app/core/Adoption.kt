package com.tingben.app.core

/**
 * 移植自 Antioch `src/script/annotator.rs` 的 annotate / generate_instructs 采纳管线。
 * 原作者 Xydane，MIT License，版权声明见 docs/THIRD_PARTY_NOTICES.md。
 *
 * 上一版 reconcile 是手搓的：只做「顺序前缀匹配 + 尾部补齐」，下面 5 条一条都没有。
 *
 * 拿过来的 5 条：
 *  ① echo 检测：响应复述了输入 ≥30 字就判为模型在背原文（原 145-149 行）
 *  ② 标签前缀剥离：[角色] / 旁白： / Pacing: 这类前缀（原 131-155 行）
 *  ③ 首句截断 + 长度 <4 丢弃（原 157-164 行）
 *  ④ 只保留对象条目、按 (speaker,text) 去重（原 93-105 行）
 *  ⑤ 单条失败用 sensible defaults 补齐，绝不整块丢弃（原 84-88 行注释与实现）
 *
 * 改写点（不是照抄）：
 *  - 原文是「结构由确定性代码切、LLM 只填 instruct」；我们是「模型出 text 区间、
 *    程序补没标完的尾部」，保留逐字 gate 契约，不换成剧本改编
 *  - 字符集扩到中文：标签认中文冒号，首句截断认 句号叹号问号
 *  - 去重命中不静默吞字，而是停下改由程序补齐，避免破坏逐字相等
 *  - 每条丢弃都记原因，写进 ANN_ADOPT_DETAIL 事件，用户能自证「真标还是假标」
 */
object Adoption {

    /** Antioch 的 "Neutral, even narration." 中文对应，也是直接朗读档用的默认指令。 */
    const val DEFAULT_INSTRUCT = "平稳、清晰地叙述，句间自然停顿"

    data class Drop(val index: Int, val speaker: String, val preview: String, val reason: String)

    data class Result(
        val anns: List<Ann>, val kept: Int, val filled: Int, val disposition: String,
        val drops: List<Drop>
    )

    private val LABEL = Regex("^(?:\\[[^\\]]*\\]|[\\u4e00-\\u9fa5A-Za-z ]{1,20})[:：]\\s*")
    private val LEADING_JUNK = Regex("^[\\[\\]()\"'`\\s]+")

    private fun firstSentence(s: String): String {
        val cut = s.indexOfFirst { it in ".!?。！？" }
        return (if (cut >= 0) s.substring(0, cut + 1) else s).trim()
    }

    /** 清洗一条 instruct，返回 指令 或 丢弃原因。 */
    fun cleanInstruct(raw: String, sourceText: String): Pair<String, String?> {
        val trimmed = raw.trim()
        // ① echo 检测：短于 30 字不做，避免短句被自己误杀
        if (sourceText.length >= 30 && trimmed.contains(sourceText.take(30)))
            return "" to "ECHO"
        var s = LABEL.replace(trimmed, "")
        s = LEADING_JUNK.replace(s, "").trim()
        s = firstSentence(s).trimStart('[', '(', '"', '\'', '`').trim()
        if (s.length < 4) return "" to "TOO_SHORT"
        return s to null
    }

    /**
     * @param tailSegments 尾部没标完时由程序补齐的确定性切分（直接复用 directSegments）
     */
    fun adopt(parsed: List<Ann>?, original: String,
              tailSegments: (String) -> List<Ann>): Result {
        val drops = mutableListOf<Drop>()
        val out = mutableListOf<Ann>()
        val seen = HashSet<Pair<String, String>>()
        var cursor = 0
        var kept = 0

        if (parsed != null) for ((idx, a) in parsed.withIndex()) {
            if (a.text.isEmpty()) {
                drops += Drop(idx, a.speaker, "", "EMPTY_TEXT"); break
            }
            if (cursor + a.text.length > original.length ||
                !original.regionMatches(cursor, a.text, 0, a.text.length)) {
                drops += Drop(idx, a.speaker, a.text.take(24), "NOT_PREFIX_AT_$cursor")
                break
            }
            if (!seen.add(a.speaker to a.text)) {
                drops += Drop(idx, a.speaker, a.text.take(24), "DUPLICATE")
                break
            }
            // ⑤ 单条 instruct 不合格：用默认值补齐这条，不丢整块、不丢 text
            val (inst, reason) = cleanInstruct(a.instruct.ifBlank { DEFAULT_INSTRUCT }, a.text)
            if (reason != null) {
                out.add(Ann(a.text, a.speaker, DEFAULT_INSTRUCT))
                drops += Drop(idx, a.speaker, a.text.take(24), reason)
            } else {
                out.add(Ann(a.text, a.speaker, inst))
            }
            kept++
            cursor += a.text.length
        }

        val rest = if (cursor < original.length) original.substring(cursor) else ""
        val fill = if (rest.isNotEmpty()) tailSegments(rest) else emptyList()
        out.addAll(fill)
        val disp = when {
            kept > 0 && fill.isEmpty() -> "FULL"
            kept > 0 -> "PARTIAL"
            else -> "FALLBACK"
        }
        return Result(out, kept, fill.size, disp, drops)
    }
}
