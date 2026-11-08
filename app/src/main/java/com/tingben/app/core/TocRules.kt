package com.tingben.app.core

// R138 · 批次 B：TXT 目录规则集。
// 参考成熟开源阅读器（Legado txtTocRule.json / TextFile.kt getTocRule、TRNovel toc-rules、
// Reeden 分章规则）的做法，把「一套固定正则硬筛」换成：
//   多条候选规则 → 对这一本书统计有效命中数自动选优 → 目录页簇丢弃 → 无命中按字数兜底。
// 规则只认「独占一行、顶格或仅 ASCII 缩进、长度受限、不以句末标点结尾」的短标题；
// 全角空格缩进视为正文段落（TRNovel 明确），负向断言排除「第三部分 / 第三节课」类歧义。
// EPUB 不走这里，Importer 用 CHAPTER_SEP 分隔，天然有结构化目录。
data class TocRule(val id: String, val name: String, val pattern: Regex,
                   val isVolume: Boolean = false)

object TocRules {
    const val LEGACY = "legacy_v2"   // 1.1.2 及以前那套启发式，专供老书沿用，保章号不漂
    const val BY_SIZE = "by_size"    // 任何规则都不命中时的兜底
    const val SCHEMA = 3             // 章节表 schema；改切章口径时 +1

    val ALL: List<TocRule> = listOf(

        TocRule("cn_chapter", "常规中文 第N章 / 正文 第N章 / 楔子",
            Regex("""^[ \t]{0,4}(?:序章|楔子|正文(?!完|结)|终章|后记|尾声|番外|第[ \t]{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+?[ \t]{0,4}(?:章|节(?!课)|回|卷|集(?![合和])|部(?![分赛游])|篇(?!张)|话)).{0,30}${'$'}"""),
            false),
        TocRule("cn_volume", "卷一 / 卷 1",
            Regex("""^[ \t]{0,4}卷[ \t]{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10}.{0,30}${'$'}"""),
            true),
        TocRule("numeric_sep", "1. 标题 / 1、标题 / 001 标题",
            Regex("""^[ \t]{0,4}\d{1,5}[ \t]{0,2}[、.．,，:：）】\-—][ \t]{0,2}.{1,30}${'$'}"""),
            false),
        TocRule("cn_numeric", "一、标题 / 十二. 标题",
            Regex("""^[ \t]{0,4}[〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}[ \t]{0,2}[、.．][ \t]{0,2}.{1,30}${'$'}"""),
            false),
        TocRule("en_chapter", "Chapter 1 / Part 2 / Section 3",
            Regex("""^[ \t]{0,4}(?:[Cc]hapter|[Pp]art|[Ss]ection|[Bb]ook|[Ee]pisode)[ \t]{0,4}\d{1,5}.{0,30}${'$'}"""),
            false),
        TocRule("special", "楔子 / 序章 / 尾声 / 番外 独占一行",
            Regex("""^[ \t]{0,4}(?:楔子|序章|序言|引子|尾声|后记|番外|外传|附录|前言|开篇|卷首语|完本感言|终章|结局)(?:[ \t　:：·—\-、,，.．].{0,30})?${'$'}"""),
            false),
        TocRule("wrapped", "【第1章】 / ★第一章★ 等包裹形态",
            Regex("""^[ \t]{0,4}[【〔〖《「『★☆◆■●（(\[][ \t]{0,2}(?:楔子|序章|第[ \t]{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10}[ \t]{0,4}[章回节卷篇话]|[零〇一二三四五六七八九十百千两]{1,6}).{0,30}${'$'}"""),
            false)
    )

    private val excludePattern = Regex("""^(?:第[ \t]{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10}[ \t]{0,4}(?:部分|部门|部队|节课|集合|集和|篇张|章回体|章节目)|.*[。！？；;!?]${'$'})""")

    const val MAX_TITLE = 35
    // 目录页是「连续几行都是标题」，行与行中间没有正文；24 字符足以容纳
    // 一行标题。用 300 会把紧接目录页的正文第一章也吞进簇（实测 6 章变 5 章）。
    private const val TOC_GAP = 24
    private const val TOC_CLUSTER_MIN = 3    // 连续 3 行以上才判目录簇
    private const val TOC_HEAD_RATIO = 0.15f // 目录簇只可能在文本前 15%
    private const val BY_SIZE_CHARS = 8000   // 兜底：约每 8000 字一节

    fun byId(id: String): TocRule? = ALL.firstOrNull { it.id == id }

    /** 逐行扫描，返回该规则命中的 (偏移量, 标题)。偏移量是字符下标，不是字节。 */
    fun scan(text: String, rule: TocRule): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != '\n' && text[j] != '\r') j++
            val raw = text.substring(i, j)
            // 只剥行尾空白与 BOM；前导空白交给正则的 ^[ \t]{0,4} 判定，
            // 不像旧实现那样 trim 掉全角空格（那会把缩进正文误升成标题）。
            val line = raw.trimEnd(' ', '\t', '\r').trimStart('\uFEFF')
            if (line.isNotEmpty() && line.length <= MAX_TITLE &&
                !excludePattern.matches(line) && rule.pattern.matches(line)) {
                out.add(i to cleanTitle(line))
            }
            if (j >= text.length) break
            i = if (text[j] == '\r' && j + 1 < text.length && text[j+1] == '\n') j + 2 else j + 1
        }
        return out
    }

    private fun cleanTitle(s: String): String =
        s.trim().trimStart('\uFEFF').trim().take(MAX_TITLE).ifBlank { "未命名章节" }

    /**
     * 目录页簇：连续 >=3 个命中、相邻标题之间只有 1 个换行（或间隔 <TOC_GAP）、
     * 且整簇落在文本前 15%，判为书前目录页，整簇丢弃。
     * 成熟阅读器在「选规则」阶段要求匹配间隔 >1000 字符，本质就是不让目录页参与计分；
     * 这里连切章阶段也一起过滤，且不吞掉紧接目录页的正文第一章（它前面有空行/分隔行）。
     */
    /**
     * 目录项精准去重：目录页里的标题，正文里通常以同名再出现一次，且中间隔着
     * 上千字符。只按「密集簇」丢会把紧接目录页的正文第一章一起吞掉（实测 6 章变 5 章）。
     * 先按同名重复删目录项，密集簇再作为没有重复标题时的兜底。
     */
    fun dropTocDuplicates(marks: List<Pair<Int, String>>): List<Pair<Int, String>> {
        if (marks.size < 2) return marks
        return marks.filterIndexed { i, m ->
            !((i + 1 until marks.size).any { j ->
                marks[j].second == m.second && marks[j].first - m.first > 1000
            })
        }
    }

    fun dropTocCluster(marks: List<Pair<Int, String>>, text: String): List<Pair<Int, String>> {
        if (marks.size < TOC_CLUSTER_MIN) return marks
        val head = (text.length.toFloat() * TOC_HEAD_RATIO).toInt().coerceAtLeast(600)
        var start = -1
        var i = 0
        while (i < marks.size - 1) {
            // 目录页是「一行标题紧接下一行标题」，中间片段只有 1 个换行。
            // 目录页与正文第一章之间会有空行/分隔行/版权页，换行数 >= 2。
            // 用换行数而不是字符间隔：标题长短不影响，长英文标题也不会断簇。
            val between = text.substring(marks[i].first, marks[i+1].first)
            val dense = between.count { it == '\n' } <= 1 ||
                    marks[i+1].first - marks[i].first < TOC_GAP
            if (dense) {
                if (start < 0) start = i
                i++
            } else {
                if (start >= 0 && i - start + 1 >= TOC_CLUSTER_MIN &&
                    marks[start].first < head) return marks.toMutableList().also {
                        it.subList(start, i + 1).clear() }
                start = -1; i++
            }
        }
        if (start >= 0 && marks.size - start >= TOC_CLUSTER_MIN && marks[start].first < head)
            return marks.subList(0, start).toList()
        return marks
    }

    /** 按书自动选优：有效命中数最多的规则胜出；全部不足 2 命中则按字数兜底。 */
    fun choose(text: String): Pair<String, List<Pair<Int, String>>> {
        var bestId: String? = null
        var bestMarks: List<Pair<Int, String>> = emptyList()
        var bestScore = 0
        for (rule in ALL) {
            // 先按「连续标题行」丢目录页，再按同名重复兜底；顺序反了会漏删
            val m = dropTocDuplicates(dropTocCluster(scan(text, rule), text))
            // 相邻命中间隔 >1000 字符才计一次，抑制同屏重复标题刷分
            var effective = if (m.isEmpty()) 0 else 1
            for (k in 1 until m.size) if (m[k].first - m[k-1].first > 1000) effective++
            if (effective > bestScore) { bestScore = effective; bestId = rule.id; bestMarks = m }
        }
        if (bestId != null && bestMarks.size >= 2) return bestId!! to bestMarks
        return BY_SIZE to bySize(text)
    }

    /** 兜底：在最近换行处按字数切，永不退回「整本 1 章」。 */
    fun bySize(text: String): List<Pair<Int, String>> {
        if (text.length <= BY_SIZE_CHARS) return listOf(0 to "整本")
        val out = ArrayList<Pair<Int, String>>()
        var cur = 0
        while (cur < text.length) {
            out.add(cur to "第 ${out.size + 1} 节")
            var target = (cur + BY_SIZE_CHARS).coerceAtMost(text.length)
            if (target < text.length) {
                var k = target
                while (k > cur + BY_SIZE_CHARS / 2 && text[k] != '\n' && text[k] != '\r') k--
                if (k > cur + BY_SIZE_CHARS / 2) target = k + 1
            }
            cur = target
        }
        return out
    }

    fun splitMarks(text: String, ruleId: String): List<Pair<Int, String>> = when (ruleId) {
        BY_SIZE -> bySize(text)
        LEGACY -> legacyCandidates(text)
        else -> {
            val rule = byId(ruleId) ?: return choose(text).second
            dropTocDuplicates(dropTocCluster(scan(text, rule), text))
        }
    }

    // ---- 1.1.2 的旧启发式：老书沿用它，保证章号与已有音频/断点完全一致 ----
    private val legacyStrong = listOf(
        Regex("""^第\s*[零〇一二三四五六七八九十百千两0-9０-９]{1,12}\s*[章节回卷篇集部话](?:[\s　:：·—\-、,，.．]?.*)?$"""),
        Regex("""^(?:[Cc]hapter|[Pp]art|[Ss]ection|[Bb]ook)\s*[0-9０-９一二三四五六七八九十]{1,4}(?:[\s　:：·—\-、,，.．]?.*)?$"""),
        Regex("""^卷\s*[零〇一二三四五六七八九十百千两0-9０-９]{1,12}(?:[\s　:：·—\-、,，.．]?.*)?$"""))
    private val legacySpecial = Regex(
        """^(?:楔子|序章|序言|引子|尾声|后记|番外|楔|序|跋|终章|结局|前言|开篇|卷首语|完本感言)(?:[\s　:：·—\-、,，.．]?.*)?$""")
    private val legacyNumeric = listOf(
        Regex("""^[0-9０-９]{1,4}\s*[、.．,，:：）】\-—]\s*\S.{0,36}$"""),
        Regex("""^[0-9０-９]{1,4}\s+\S.{0,36}$"""),
        Regex("""^[零〇一二三四五六七八九十百千两]{1,6}\s*[、.．]\s*\S.{0,36}$"""))

    private data class Cand(val off: Int, val title: String, val strong: Boolean, val ordinal: Int?)
    private fun legacyClean(raw: String): String {
        var s = raw.trim().trim('\uFEFF').trim()
            .trimStart('—', '-', '=', '*', '#', ' ', '　')
            .trimEnd('—', '-', '=', '*', '#', ' ', '　').trim()
        s = s.replace("【", "").replace("】", "").replace("[", "").replace("]", "")
            .replace("（", "").replace("）", "").replace("(", "").replace(")", "")
        return s.trim()
    }
    private fun legacyOrdinal(line: String): Int? {
        val m = Regex("""[0-9０-９]{1,4}""").find(line) ?: return null
        return m.value.map { ch -> if (ch in '０'..'９') '0' + (ch - '０') else ch }
            .joinToString("").toIntOrNull()
    }
    private fun legacyMonotone(cs: List<Cand>): Boolean {
        val seq = cs.mapNotNull { it.ordinal }
        if (seq.size < 3) return true
        var back = 0
        for (i in 1 until seq.size) if (seq[i] < seq[i-1]) back++
        return back * 3 <= seq.size
    }
    fun legacyCandidates(text: String): List<Pair<Int, String>> {
        val strong = ArrayList<Cand>(); val numeric = ArrayList<Cand>()
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != '\n' && text[j] != '\r') j++
            val line = legacyClean(text.substring(i, j))
            if (line.isNotEmpty() && line.length <= 40 &&
                line.none { it == '。' || it == '！' || it == '？' || it == ';' || it == '；' }) {
                val t = line.take(40)
                when {
                    legacyStrong.any { it.matches(line) } || legacySpecial.matches(line) ->
                        strong.add(Cand(i, t, true, null))
                    legacyNumeric.any { it.matches(line) } ->
                        numeric.add(Cand(i, t, false, legacyOrdinal(line)))
                }
            }
            if (j >= text.length) break
            i = if (text[j] == '\r' && j+1 < text.length && text[j+1] == '\n') j+2 else j+1
        }
        val numericOn = numeric.size >= 3 && legacyMonotone(numeric) &&
            (numeric.mapNotNull { it.ordinal }.maxOrNull() ?: 0) >= 2
        val kept = (strong + if (numericOn) numeric else emptyList())
            .distinctBy { it.off }.sortedBy { it.off }
        val filtered = ArrayList<Cand>()
        kept.forEach { cand ->
            val prev = filtered.lastOrNull()
            if (prev == null || cand.off - prev.off >= 24) filtered.add(cand)
            else if (cand.strong && !prev.strong) filtered[filtered.lastIndex] = cand
        }
        if (filtered.size < 2) return emptyList()
        return filtered.map { it.off to it.title }
    }
}
