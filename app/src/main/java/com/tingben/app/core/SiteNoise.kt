package com.tingben.app.core

/**
 * R148 · 清掉站外 TXT 常带的下载站页眉；R157 补第二份真机日志里的新模板。
 *
 * 1.1.3 真机日志实证：段 1~4 念的是
 * 「怨气撞铃/作者:尾鱼」「状态:已完结」「内容简介…」「爱下电子书Txt」
 * 「下载和分享更多电子书请访问…https://ixdzs8.com」
 * 「//ixdzs8.tw,E-mail:support@ixdzs.com」「------章节内容开始」「请稍后重1前言」——
 * 用户等了好几分钟，先听了一串广告和网址。
 *
 * **R157 纠正**：1.1.4 第二份日志（怨气撞铃）里 TTS_INPUT 段 1 仍念了
 * 「...』 / 免费章节 / 　　内容还在处理中,请稍后重1前言」。
 * 上一轮汇报写「站点广告不再被念」是**过度承诺**：R148 只覆盖了第一份日志
 * 那一种站点的模板，换个下载站就漏了。查到两个具体原因：
 *   ① 噪声行用**全角空格**缩进，Kotlin `\s` 默认只认 ASCII 空白，
 *      `^\s*` 完全没吃掉 `　　`；
 *   ② 「免费章节」「内容还在处理中」是另一套防盗版占位页，规则里没有。
 * 现在前缀统一认全角空格 / BOM / 不换行空格，并补齐这套模板。
 *
 * 原则不变：**只在整行上保守过滤**，正文里正常出现的网址、邮箱、对话不删
 * （整行命中模板才算噪声）。其余一个字不改，行结构保留。
 * 不落盘、不回写原书，只在合成前作用于待切分的文本；章节表与阅读正文不受影响。
 *
 * **已知限制**：网页把章标题粘进正文行尾（日志里「…仅此而2第①章」），
 * 前半是正文，整行删会改正文，所以不在这里处理 —— 正文一个字不改优先。
 */
object SiteNoise {

    // R157 · 全角空格 U+3000 / BOM / 不换行空格是站外 TXT 的常见缩进，
    // Java 正则的 \s 默认不含它们，必须显式列出来。
    private const val WS = "[\\s\\u3000\\uFEFF\\u00A0]*"

    private val LINE_RULES = listOf(
        Regex("""^${WS}[-=＝—]{1,}.*章节内容开始.*$"""),
        Regex("""^${WS}下载和分享更多电子书.*$"""),
        Regex("""^${WS}(请稍后重|请稍候重|稍后重|请刷新|刷新后).*$"""),
        Regex("""^${WS}[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}${WS}"""),
        Regex("""^${WS}(https?://|www\.)\S+${WS}"""),
        Regex("""^${WS}//\s*\S+\.(com|cn|tw|net|org)\s*[,，].*$"""),
        Regex("""^${WS}爱下电子书.*$"""),
        Regex("""^${WS}『?${WS}(状态|内容简介|作者|来源|封面|标签)\s*[:：].*』?${WS}"""),
        Regex("""^${WS}『?[^』]{0,24}/作者\s*[:：].*』?${WS}"""),
        Regex("""^${WS}(更多电子书|电子书下载|最新网址|本站地址|Txt下载|txt下载).*$"""),
        // ── R157 · 防盗版占位页（整行命中才丢，且限短行，避免误删正文）──
        Regex("""^${WS}(免费章节|VIP章节|试读章节|本章未完|内容加载中)${WS}"""),
        Regex("""^${WS}[.。…]{2,}${WS}』?${WS}"""),          // 「　　...』」
        Regex("""^${WS}[^\n]{0,24}(内容还在处理中|内容正在处理|正在加载中|请稍后重试)[^\n]{0,24}$"""),
        Regex("""^${WS}[^\n]{0,24}请稍后重[^\n]{0,12}$"""),
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
