package com.tingben.app.core

// @core-part: Annotator
object Annotator {
    fun directWithStats(text: String): Pair<List<Ann>, TextSplitter.Stats> {
        // R148 · 日志里段 1~4 把下载站页眉（书名/作者、URL、E-mail、
        // 「------章节内容开始」「请稍后重」）当正文念了出来。进切分前先整行清洗，
        // 统计基于清洗后的文本，逐字无损仍然成立；阅读正文与章节表不受影响。
        val (segs, st) = TextSplitter.split(SiteNoise.clean(text))
        return segs.map { Ann(it, "旁白", "平稳、清晰地叙述，句间自然停顿") } to st
    }

    fun directSegments(text: String): List<Ann> = directWithStats(text).first
}

