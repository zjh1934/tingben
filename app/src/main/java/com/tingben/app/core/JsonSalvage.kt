package com.tingben.app.core

import org.json.JSONArray

/**
 * 移植自 Alexandria (novel-voice-studio) `generate_script.py` 的
 * clean_json_string / repair_json_array / salvage_json_entries。
 * 原作者 Finrandojin，MIT License，版权声明见 docs/THIRD_PARTY_NOTICES.md。
 *
 * 为什么拿过来（2026-11-04 本地对拍实测，不写虚的）：
 *   - 字符串里带裸换行时，旧手搓版解析出 0 条，移植件 1 条 —— 真实可观测差异
 *   - 括号计数让正文里的方括号不会被误当数组闭合，是结构性正确；
 *     但在「输出已被预算截断」的用例下两者都救不回条目，不夸大收益
 *   - 12 组用例（围栏 / think 闭合与未闭合 / 缺逗号 / 尾逗号 / 半条截断 /
 *     裸字符串混入 / 字段乱序 / 尾随解释）与原实现逐条一致
 *
 * 改写点（不是照抄）：
 *  - Python json.loads 换成 Android 自带 org.json，不引新依赖
 *  - 原正则写死字段顺序 speaker,text,instruct；这里用 optString 逐字段取，顺序无关
 *  - 额外返回定位信息给 ParseMeta 用，原实现没有
 *  - 保留六条核心：多种 think 标签 / 未闭合 think 截断 / 括号计数找真闭合 /
 *    字符串内控制字符修复 / 裸字符串条目过滤 / 截断到最后一个完整对象
 */
object JsonSalvage {

    data class Extraction(
        val body: String?,
        val jsonStart: Int,
        val jsonEnd: Int,
        val fenceRemoved: Boolean,
        val trailingChars: Int,
        val thinkTagsStripped: Int,
        val controlCharsFixed: Int
    )

    private val THINK_BLOCK = listOf(
        Regex("<think>[\\s\\S]*?</think>"),
        Regex("<thinking>[\\s\\S]*?</thinking>"),
        Regex("<reflection>[\\s\\S]*?</reflection>"),
        Regex("<reasoning>[\\s\\S]*?</reasoning>")
    )
    private val THINK_UNCLOSED = listOf(
        Regex("<think>[\\s\\S]*$"), Regex("<thinking>[\\s\\S]*$"),
        Regex("<reflection>[\\s\\S]*$"), Regex("<reasoning>[\\s\\S]*$")
    )
    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val FENCE_MARK = Regex("```(?:json)?", RegexOption.IGNORE_CASE)
    private val STRING_LITERAL = Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"")

    fun extract(rawIn: String): Extraction {
        var raw = rawIn
        var tags = 0
        THINK_BLOCK.forEach { r ->
            if (r.containsMatchIn(raw)) { tags += r.findAll(raw).count(); raw = r.replace(raw, "") }
        }
        THINK_UNCLOSED.forEach { r ->
            if (r.containsMatchIn(raw)) { tags++; raw = r.replace(raw, "") }
        }
        val fenceRemoved = raw.contains("```")
        val f = FENCE.find(raw)
        raw = if (f != null) f.groupValues[1].trim() else FENCE_MARK.replace(raw, "").trim()

        val start = raw.indexOf('[')
        if (start < 0) return Extraction(null, -1, -1, fenceRemoved, raw.trim().length, tags, 0)

        // Alexandria 的 bracket_count：字符串内的方括号不计入层级。
        var depth = 0
        var inStr = false
        var esc = false
        var end = -1
        var i = start
        while (i < raw.length) {
            val ch = raw[i]
            if (esc) { esc = false; i++; continue }
            if (inStr && ch == '\\') { esc = true; i++; continue }
            if (ch == '"') { inStr = !inStr; i++; continue }
            if (inStr) { i++; continue }
            if (ch == '[') depth++
            else if (ch == ']') { depth--; if (depth == 0) { end = i + 1; break } }
            i++
        }
        if (end < 0) {
            // 没闭合：截到最后一个完整对象再补 ]，原实现第 63-68 行。
            val last = raw.lastIndexOf("},")
            val salvaged = if (last > start) raw.substring(start, last + 1) + "]" else null
            return Extraction(salvaged, start, -1, fenceRemoved, 0, tags, 0)
        }

        var body = raw.substring(start, end)
        var ctrl = 0
        body = STRING_LITERAL.replace(body) { m ->
            val s = m.value
            val fixed = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            if (fixed != s) ctrl++
            fixed
        }
        return Extraction(body, start, end, fenceRemoved,
            raw.substring(end).trim().length, tags, ctrl)
    }

    /**
     * 对应 Alexandria repair_json_array 的 5 次尝试，返回 修复层级 0..4；
     * -1 = 彻底没解析出对象。层级写进日志，能看出模型输出到底烂到哪一步。
     */
    fun repair(bodyIn: String?): Pair<List<Ann>?, Int> {
        val body = bodyIn ?: return null to -1
        val candidates = mutableListOf(body)
        candidates.add(body.replace(Regex("\\}\\s*\\{"), "},\n{"))
        candidates.add(candidates.last().replace(Regex(",\\s*\\]"), "]"))
        val objs = Regex("\\{[^{}]*\\}").findAll(body).map { it.value }.toList()
        if (objs.isNotEmpty()) candidates.add("[" + objs.joinToString(",") + "]")
        val last = body.lastIndexOf("},")
        if (last > 0) candidates.add(body.substring(0, last + 1) + "]")
        for ((level, t) in candidates.withIndex()) {
            val a = parseArray(t)
            if (a != null) return a to level
        }
        return null to -1
    }

    /** 只保留对象条目（原实现 _filter_entries），字段顺序无关。 */
    private fun parseArray(t: String): List<Ann>? = try {
        val arr = JSONArray(t)
        val out = mutableListOf<Ann>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text", "")
            if (text.isEmpty()) continue
            out.add(Ann(text, o.optString("speaker", "旁白").ifBlank { "旁白" },
                o.optString("instruct", "")))
        }
        if (out.isEmpty()) null else out
    } catch (_: Throwable) { null }
}
