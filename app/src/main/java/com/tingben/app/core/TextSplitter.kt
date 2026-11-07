package com.tingben.app.core

object TextSplitter {
    const val TARGET_CHARS = 110

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
        fun summary(): String =
            "段数=$segments · 句/段边界=$sentenceCuts · 次级边界=$softCuts · " +
                "字符硬切=$hardCuts · 超长整句保留=$oversize · 最长段=$longest 字 · " +
                "标点收尾=$endsAtPunct/$segments · 原文=$charsIn 字 拼回=$charsOut 字" +
                if (charsIn == charsOut) " · 逐字无损" else " · ⚠️字数不一致"
    }

    fun split(text: String): Pair<List<String>, Stats> {
        if (text.isEmpty()) return emptyList<String>() to Stats(0, 0, 0, 0, 0, 0, 0, 0, 0)

        val n = text.length
        val primary = mutableListOf<Int>()
        var quoteDepth = 0
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c in OPENERS) { quoteDepth++; i++; continue }
            if (c in CLOSERS) { if (quoteDepth > 0) quoteDepth--; i++; continue }

            if (c == '\n' && i + 1 < n && text[i + 1] == '\n') {
                var j = i + 2
                while (j < n && text[j].isWhitespace()) j++
                if (j < n) primary.add(j)
                i = j
                continue
            }
            if (c in TERMINALS && quoteDepth == 0) {
                var j = i + 1
                while (j < n && (text[j] in AFTER_TERMINAL || text[j].isWhitespace())) j++
                if (j < n) primary.add(j)
                i = j
                continue
            }
            i++
        }

        val units = mutableListOf<String>()
        var prev = 0
        for (b in primary.distinct().sorted()) {
            if (b > prev && b <= n) { units.add(text.substring(prev, b)); prev = b }
        }
        if (prev < n) units.add(text.substring(prev, n))

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

    private fun isHeadingLike(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length >= 120) return false
        val last = t.last()
        return last !in TERMINALS && last !in CLOSERS && !t.endsWith("…")
    }
}
