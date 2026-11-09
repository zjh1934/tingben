package com.tingben.app.core

// @core-part: ChapterSplitter
object ChapterSplitter {
    // R138 · 批次 B：TXT 分章委托给 TocRules 规则集自动选优；
    // EPUB 仍走 Importer.CHAPTER_SEP 结构化分隔，一个字不动。
    // 本对象只负责「编排」：候选 → Chapter 区间 → 切 Chunk。
    // 真正的单一真值源是 ChapterPlan（落盘、按书记住 ruleId），
    // UI 与制作运行都应调 ChapterPlan.resolve，而不是直接调 split。

    fun fromMarks(text: String, marks: List<Pair<Int, String>>): List<Chapter> {
        val safe = if (marks.isEmpty()) listOf(0 to "整本") else marks
        return safe.mapIndexed { i, m ->
            val e = if (i + 1 < safe.size) safe[i + 1].first else text.length
            Chapter(i, m.second, m.first, e)
        }
    }

    fun cutFrom(text: String, chapters: List<Chapter>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var idx = 0
        chapters.forEach { ch -> idx = cut(text, ch.start, ch.end, ch.index, out, idx) }
        return out
    }

    private fun cut(text: String, s: Int, e: Int, chIndex: Int,
                    out: MutableList<Chunk>, startIdx: Int): Int {
        var idx = startIdx
        var buf = StringBuilder()
        var bufStart = s
        var i = s
        val lo = s.coerceIn(0, text.length)
        val hi = e.coerceIn(lo, text.length)
        i = lo; bufStart = lo
        while (i < hi) {
            val ch0 = text[i]
            buf.append(ch0); i++
            val len = buf.length
            val boundary = ch0 == '\n' || ch0 == '。' || ch0 == '！' || ch0 == '？' ||
                    ch0 == '!' || ch0 == '?' || ch0 == '；' || ch0 == ';'
            if (len >= 500 || (len >= 350 && boundary)) {
                if (buf.isNotEmpty()) out.add(Chunk(idx++, chIndex, buf.toString(), bufStart, i))
                bufStart = i
                buf = StringBuilder()
            }
        }
        if (buf.isNotEmpty()) out.add(Chunk(idx++, chIndex, buf.toString(), bufStart, hi))
        return idx
    }

    fun split(text: String): Pair<List<Chapter>, List<Chunk>> {
        if (!text.contains(Importer.CHAPTER_SEP)) {
            val chapters = fromMarks(text, TocRules.choose(text).second)
            return chapters to cutFrom(text, chapters)
        }
        // ---- 以下 EPUB 分支原样保留 ----
        val chapters = mutableListOf<Chapter>()
        val chunks = mutableListOf<Chunk>()
        var idx = 0
        val segs = text.split(Importer.CHAPTER_SEP)
            .map { it.trim() }.filter { it.isNotBlank() }
        var cursor = 0
        segs.forEachIndexed { ci, seg ->
            val title = seg.lineSequence().firstOrNull()?.trim()?.take(40)
                ?.ifBlank { "第 ${ci + 1} 节" } ?: "第 ${ci + 1} 节"
            val start = text.indexOf(seg, cursor).let { if (it < 0) cursor else it }
            val end = start + seg.length
            chapters.add(Chapter(ci, title, start, end))
            idx = cut(text, start, end, ci, chunks, idx)
            cursor = end
        }
        return chapters to chunks
    }
}

