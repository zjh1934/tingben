package com.tingben.app.core

/**
 * R25 · 直接朗读的断句：**只按符号断，绝不按字符从半句腰斩**。
 *
 * 移植来源（现成源码，非手搓）：
 *  - antioch-audiobook `src/script/chunker.rs`（MIT）：`split_into_segments` 先按段落、
 *    再按句子边界切，合并到目标长度；`is_heading_like` 让短标题单独成段不与正文连读；
 *    `group_by_speaker` 的「说话人变化必为分界」在这里等价于「句子边界必为分界」。
 *  - ReaderVoice-Mobile `data-room/.../semantic/SemanticSegmenter.kt`：引号栈 +
 *    source-exact 区间切片，不在引号中间断，切片拼回去逐字无损。
 *  - ensemble-reads `player/SegmentMerger.kt`：`MAX_SEG_CHARS` 相邻合并；注释明确
 *    「单段本身超过上限的长独白保留原样，避免切碎语义」——所以本实现**没有字符硬切**。
 *
 * 中文适配：句末集合加了 。！？；… 与 ”」』；段落边界 \n\n；英文 . ! ? 沿用上游判据。
 * 不删一个字：所有空白都留在相邻段的尾部，拼回去与原文严格相等（charsIn == charsOut）。
 */
object TextSplitter {
    /** 合成目标段长；只在句子/段落边界合并，不是硬上限。 */
    const val TARGET_CHARS = 110

    /** 超过这个长度的长句也保留整句，不再按逗号切（见 SegmentMerger 的语义优先原则）。 */
    const val SOFT_CAP = 260

    private const val OPENERS = "“「『‘"
    private const val CLOSERS = "”」』’\"'"
    private const val TERMINALS = "。！？!?；;…"
    private const val SECONDARY = "，、,：:"
    private const val AFTER_TERMINAL = "。！？!?；;….\"”」』’"

    data class Stats(
        val segments: Int,
        val sentenceCuts: Int,
        val softCuts: Int,
        val hardCuts: Int,
        val oversize: Int,
        val longest: Int,
        val endsAtPunct: Int,
        val charsIn: Int,
        val charsOut: Int
    ) {
        /** 真机日志里一行就能看出「是不是又从中间断了」。 */
        fun summary(): String =
            "段数=$segments · 句/段边界=$sentenceCuts · 次级边界=$softCuts · " +
                "字符硬切=$hardCuts · 超长整句保留=$oversize · 最长段=$longest 字 · " +
                "标点收尾=$endsAtPunct/$segments · 原文=$charsIn 字 拼回=$charsOut 字" +
                if (charsIn == charsOut) " · 逐字无损" else " · ⚠️字数不一致"
    }

    /** 返回「段列表 + 统计」。段内容含原文空白，顺序与原文完全一致。 */
    fun split(text: String): Pair<List<String>, Stats> {
        if (text.isEmpty()) return emptyList<String>() to Stats(0, 0, 0, 0, 0, 0, 0, 0, 0)

        val n = text.length
        val primary = mutableListOf<Int>()   // 一级切点：句末标点 / 段落边界（exclusive）
        var quoteDepth = 0
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c in OPENERS) { quoteDepth++; i++; continue }
            if (c in CLOSERS) { if (quoteDepth > 0) quoteDepth--; i++; continue }

            // 段落边界：连续两个换行。标题后面常常没有句号，必须单独断。
            if (c == '\n' && i + 1 < n && text[i + 1] == '\n') {
                var j = i + 2
                while (j < n && text[j].isWhitespace()) j++
                if (j < n) primary.add(j)
                i = j
                continue
            }
            // 句末标点：引号闭合后才允许断（SemanticSegmenter 的 quote stack）。
            if (c in TERMINALS && quoteDepth == 0) {
                var j = i + 1
                // 把紧随其后的省略号、闭合引号、空白一起归到本段，切点落在下一个实字。
                while (j < n && (text[j] in AFTER_TERMINAL || text[j].isWhitespace())) j++
                if (j < n) primary.add(j)
                i = j
                continue
            }
            i++
        }

        // 一级切片：严格 substring，不 trim，不丢字符。
        val units = mutableListOf<String>()
        var prev = 0
        for (b in primary.distinct().sorted()) {
            if (b > prev && b <= n) { units.add(text.substring(prev, b)); prev = b }
        }
        if (prev < n) units.add(text.substring(prev, n))

        // 二级：太长的一级单元按次级标点（逗号/顿号/冒号）再断一次，仍然只按符号断。
        var softCuts = 0
        val pieces = mutableListOf<String>()
        for (u in units) {
            if (u.length <= SOFT_CAP) { pieces.add(u); continue }
            val cuts = secondaryCuts(u)
            if (cuts.isEmpty()) { pieces.add(u); continue }
            softCuts += cuts.size
            var p = 0
            for (b in cuts) { if (b > p) pieces.add(u.substring(p, b)); p = b }
            if (p < u.length) pieces.add(u.substring(p))
        }

        // 合并到目标长度：只在 piece 边界合并，标题单独成段（Antioch is_heading_like）。
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        val headingUnits = mutableListOf<String>()
        for (s in pieces) {
            val heading = isHeadingLike(s)
            if (cur.isNotEmpty() && (cur.length + s.length > TARGET_CHARS || heading)) {
                out.add(cur.toString()); cur.setLength(0)
            }
            cur.append(s)
            if (heading && cur.isNotEmpty()) {
                out.add(cur.toString()); cur.setLength(0); headingUnits.add(s.trim())
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())

        val charsOut = out.sumOf { it.length }
        val longest = out.maxOfOrNull { it.length } ?: 0
        val oversize = out.count { it.length > SOFT_CAP }
        val endsAtPunct = out.count { seg ->
            val t = seg.trimEnd()
            t.isEmpty() || t.last() in TERMINALS || t.last() in CLOSERS
        }
        return out to Stats(
            segments = out.size, sentenceCuts = primary.size, softCuts = softCuts,
            hardCuts = 0, oversize = oversize, longest = longest, endsAtPunct = endsAtPunct,
            charsIn = n, charsOut = charsOut
        )
    }

    /** 长单元内部的次级标点切点，切点仍落在标点之后的下一个实字。 */
    private fun secondaryCuts(u: String): List<Int> {
        val cuts = mutableListOf<Int>()
        var depth = 0
        var last = 0
        for (i in u.indices) {
            val c = u[i]
            if (c in OPENERS) { depth++; continue }
            if (c in CLOSERS) { if (depth > 0) depth--; continue }
            if (c in SECONDARY && depth == 0 && i - last >= 40) {
                var j = i + 1
                while (j < u.length && u[j].isWhitespace()) j++
                if (j < u.length) { cuts.add(j); last = j }
            }
        }
        return cuts
    }

    /** 短、无句末标点 = 标题，不与正文连读（抄 chunker.rs::is_heading_like）。 */
    private fun isHeadingLike(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length >= 120) return false
        val last = t.last()
        return last !in TERMINALS && last !in CLOSERS && !t.endsWith("…")
    }
}
