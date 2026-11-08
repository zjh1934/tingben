package com.tingben.app.core

/**
 * R148 · 清掉站外 TXT 常带的下载站页眉。
 *
 * 1.1.3 真机日志实证：段 1~4 念的是
 * 「怨气撞铃/作者:尾鱼」「状态:已完结」「内容简介…」「爱下电子书Txt」
 * 「下载和分享更多电子书请访问…https://ixdzs8.com」
 * 「//ixdzs8.tw,E-mail:support@ixdzs.com」「------章节内容开始」「请稍后重1前言」——
 * 用户等了好几分钟，先听了一串广告和网址。
 *
 * 原则：**只在整行上保守过滤**。正文里正常出现的网址、邮箱、对话不会被删
 * （必须整行命中模板才算噪声）。其余一个字都不改，行结构保留。
 * 不落盘、不回写原书，只在合成前作用于待切分的文本。
 */
object SiteNoise {

    private val LINE_RULES = listOf(
        Regex("""^\s*[-=＝—]{4,}\s*章节内容开始.*$"""),
        Regex("""^\s*下载和分享更多电子书.*$"""),
        Regex("""^\s*(请稍后重|请稍候重|稍后重|请刷新|刷新后).*$"""),
        Regex("""^\s*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\s*$"""),
        Regex("""^\s*(https?://|www\.)\S+\s*$"""),
        Regex("""^\s*//\s*\S+\.(com|cn|tw|net|org)\s*[,，].*$"""),
        Regex("""^\s*爱下电子书.*$"""),
        Regex("""^\s*『?\s*(状态|内容简介|作者|来源|封面|标签)\s*[:：].*』?\s*$"""),
        Regex("""^\s*『?[^』]{0,24}/作者\s*[:：].*』?\s*$"""),
        Regex("""^\s*(更多电子书|电子书下载|最新网址|本站地址|Txt下载|txt下载).*$"""),
    )

    /** 返回清洗后的文本；没有命中时尽量原样返回，避免无谓的字符串重建。 */
    fun clean(text: String): String {
        if (text.isEmpty()) return text
        val lines = text.lines()
        val kept = lines.filterNot { line -> LINE_RULES.any { it.matches(line) } }
        if (kept.size == lines.size) return text
        // 尽量保留原来的换行风格，但不追求逐字节一致（这是合成输入，不是落盘原文）
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        return kept.joinToString(nl)
    }

    /** 给日志/自检用：这一轮实际丢掉了几行。 */
    fun droppedCount(text: String): Int {
        if (text.isEmpty()) return 0
        return text.lines().count { line -> LINE_RULES.any { it.matches(line) } }
    }
}
