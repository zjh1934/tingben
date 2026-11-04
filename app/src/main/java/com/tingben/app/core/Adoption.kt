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
 *
 * R27 · 真机日志定案后的第二处改写（向 Antioch 的「结构归程序」靠拢）：
 *  旧版要求第一条 text 必须从原文第 0 个字符开始严格前缀匹配。模型把
 *  「第一章 试音」这类标题/空行当元信息跳过时，第一条就 NOT_PREFIX_AT_0，
 *  整块 kept=0 → FALLBACK → 全部换成直读段，模型 896 字合规 JSON 一条都没进 TTS。
 *  现在允许「有序子串 + 缺口补齐」：命中前的缺口按标点补成旁白段。
 *  顺序、不重叠、拼回逐字相等三条都由 tailSegments + gate 保证，没有放宽契约。
 */
object Adoption {

    /** Antioch 的 "Neutral, even narration." 中文对应，也是直接朗读档用的默认指令。 */
    const val DEFAULT_INSTRUCT = "平稳、清晰地叙述，句间自然停顿"

    data class Drop(val index: Int, val speaker: String, val preview: String, val reason: String)

    data class Result(
        val anns: List<Ann>, val kept: Int, val filled: Int, val disposition: String,
        val drops: List<Drop>,
        // R27 · 允许前导缺口后，「前 kept 条来自模型」不再成立（缺口段插在前面）。
        // 逐条记 true/false，ANN_CONTENT / 诊断页的〈模型标注〉标记才不会错位。
        val srcModel: List<Boolean> = emptyList()
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
        val flags = mutableListOf<Boolean>()
        val seen = HashSet<Pair<String, String>>()
        var cursor = 0
        var kept = 0
        var gapFilled = 0

        if (parsed != null) for ((idx, a) in parsed.withIndex()) {
            if (a.text.isEmpty()) {
                drops += Drop(idx, a.speaker, "", "EMPTY_TEXT"); break
            }
            // R27 · 不再要求从原文第 0 字严格前缀。模型跳过标题/空行时，
            // 允许从 cursor 往后找这一条的有序出现位置，前面的缺口由程序补齐。
            val pos = original.indexOf(a.text, cursor)
            if (pos < 0) {
                drops += Drop(idx, a.speaker, a.text.take(24), "NOT_FOUND_AFTER_$cursor")
                break
            }
            if (pos > cursor) {
                val gap = tailSegments(original.substring(cursor, pos))
                out.addAll(gap)
                repeat(gap.size) { flags.add(false) }
                gapFilled += gap.size
                cursor = pos
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
            flags.add(true)
            kept++
            cursor += a.text.length
        }

        val rest = if (cursor < original.length) original.substring(cursor) else ""
        val fill = if (rest.isNotEmpty()) tailSegments(rest) else emptyList()
        out.addAll(fill)
        repeat(fill.size) { flags.add(false) }
        val disp = when {
            kept > 0 && fill.isEmpty() && gapFilled == 0 -> "FULL"
            kept > 0 -> "PARTIAL"
            else -> "FALLBACK"
        }
        return Result(out, kept, fill.size + gapFilled, disp, drops, flags)
    }
}
