package com.tingben.app.diag

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R21 · 标注成品审计。
 *
 * 用户的核心怀疑：「模型一顿跑了一堆，结果啥也没标注」「标注后的长度合不合适」「规则有没有生效」。
 * 这里把每一块的真实结果落成一条可查记录，不靠猜：
 *   - 原始产出长度 / 思考长度 / 有效 JSON 长度（看废话占比）
 *   - 标出几条 / text 拼回去多少字 / 与原文差几个字 / 首个差异偏移
 *   - 9 条 prompt 规则逐条 PASS 还是 VIOLATE（看规则有没有生效）
 *   - 产出开头与结尾的截取片段（看模型到底吐了什么）
 *   - 四级状态 PASS / CACHED / FALLBACK / FAIL + 失败码
 *
 * 只存有限片段，不把整本正文写进日志；按 run 分文件，只留最近 3 次，避免原文长期留存。
 */
data class BlockAudit(
    val runId: String,
    val block: Int,
    val total: Int,
    val attempt: Int,
    val origChars: Int,
    val rawChars: Int,
    val thinkChars: Int,
    val bodyChars: Int,
    val annCount: Int,
    val textChars: Int,
    val lenDelta: Int,
    val firstDiff: Int,
    val speakers: String,
    val instructEmpty: Int,
    val status: String,
    val failCode: String,
    val rules: Map<String, Boolean>,
    val previewHead: String,
    val previewTail: String,
    val elapsedMs: Long,
    val fromCache: Boolean
) {
    fun toJson(): String = JSONObject().apply {
        put("runId", runId); put("block", block); put("total", total); put("attempt", attempt)
        put("origChars", origChars); put("rawChars", rawChars); put("thinkChars", thinkChars)
        put("bodyChars", bodyChars); put("annCount", annCount); put("textChars", textChars)
        put("lenDelta", lenDelta); put("firstDiff", firstDiff); put("speakers", speakers)
        put("instructEmpty", instructEmpty); put("status", status); put("failCode", failCode)
        put("rules", JSONObject().apply { rules.forEach { (k, v) -> put(k, v) } })
        put("previewHead", previewHead); put("previewTail", previewTail)
        put("elapsedMs", elapsedMs); put("fromCache", fromCache)
    }.toString()

    companion object {
        fun fromJson(s: String): BlockAudit? = try {
            val o = JSONObject(s)
            val r = o.optJSONObject("rules")
            val rules = linkedMapOf<String, Boolean>()
            if (r != null) r.keys().forEach { rules[it] = r.optBoolean(it, false) }
            BlockAudit(
                o.optString("runId"), o.optInt("block"), o.optInt("total"), o.optInt("attempt"),
                o.optInt("origChars"), o.optInt("rawChars"), o.optInt("thinkChars"), o.optInt("bodyChars"),
                o.optInt("annCount"), o.optInt("textChars"), o.optInt("lenDelta"), o.optInt("firstDiff", -1),
                o.optString("speakers", ""), o.optInt("instructEmpty"), o.optString("status", "?"),
                o.optString("failCode", "-"), rules, o.optString("previewHead", ""),
                o.optString("previewTail", ""), o.optLong("elapsedMs"), o.optBoolean("fromCache"))
        } catch (_: Throwable) { null }
    }
}

object AuditLog {
    const val PASS = "PASS"
    const val CACHED = "CACHED"
    const val FALLBACK = "FALLBACK"
    const val FAIL = "FAIL"

    @Volatile private var runId: String = ""
    @Volatile private var meta: Map<String, String> = emptyMap()
    private val mem = mutableListOf<BlockAudit>()

    private fun dir(c: Context) = File(c.filesDir, "audit").apply { mkdirs() }
    private fun runFile(c: Context, id: String) = File(dir(c), "run-$id.jsonl")
    private fun latestFile(c: Context) = File(dir(c), "latest")

    fun currentRun(): String = runId

    fun beginRun(c: Context, info: Map<String, String>) {
        runId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            "_" + (System.currentTimeMillis() % 10000).toString()
        meta = info
        synchronized(mem) { mem.clear() }
        try {
            latestFile(c).writeText(runId)
            // 只留最近 3 次 run：连同本次共 3 个，避免正文片段长期堆在手机里
            val old = dir(c).listFiles()?.filter { it.name.startsWith("run-") && it.name.endsWith(".jsonl") }
                ?.sortedBy { it.name }.orEmpty()
            old.dropLast(2).forEach { it.delete() }
        } catch (_: Throwable) { }
    }

    fun endRun() { /* 汇总按需从磁盘重算，跨进程重启也不丢 */ }

    @Synchronized
    fun write(c: Context, a: BlockAudit) {
        synchronized(mem) { mem.add(a) }
        try { runFile(c, a.runId).appendText(a.toJson() + "\n") } catch (_: Throwable) { }
    }

    private fun load(c: Context): List<BlockAudit> {
        synchronized(mem) { if (mem.isNotEmpty()) return mem.toList() }
        return try {
            val id = if (runId.isNotEmpty()) runId else latestFile(c).readText().trim()
            runFile(c, id).readLines().mapNotNull { BlockAudit.fromJson(it) }
        } catch (_: Throwable) { emptyList() }
    }

    private fun loadMeta(c: Context): Map<String, String> {
        if (meta.isNotEmpty()) return meta
        return mapOf("run" to (try { latestFile(c).readText().trim() } catch (_: Throwable) { "-" }))
    }

    fun recent(c: Context, n: Int = 12): List<BlockAudit> = load(c).takeLast(n)

    fun summaryText(c: Context): String {
        val b = load(c)
        val m = loadMeta(c)
        if (b.isEmpty()) return "本次还没有标注记录（可能走的是直接朗读，或任务刚开工）。"
        val pass = b.count { it.status == PASS }
        val cached = b.count { it.status == CACHED }
        val fallback = b.count { it.status == FALLBACK }
        val empty = b.count { !it.fromCache && it.annCount == 0 }
        val fails = b.filter { it.failCode != "-" && it.failCode.isNotBlank() }
            .groupingBy { it.failCode }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(" / ") { "${it.key}×${it.value}" }
            .ifBlank { "无" }
        val orig = b.sumOf { it.origChars }
        val text = b.sumOf { it.textChars }
        val raw = b.sumOf { it.rawChars }
        val body = b.sumOf { it.bodyChars }
        val waste = if (raw > 0) ((raw - body) * 100 / raw) else 0
        val sb = StringBuilder()
        sb.append("本次标注审计 · run=").append(m["run"] ?: runId)
            .append(" · ").append(m["mode"] ?: "-").append(" · ").append(m["chunks"] ?: b.size).append(" 块\n")
        sb.append("结果：PASS ").append(pass).append(" / 缓存续上 ").append(cached)
            .append(" / 降级直读 ").append(fallback).append(" / 零标注 ").append(empty).append('\n')
        sb.append("失败码：").append(fails).append('\n')
        sb.append("长度：原文合计 ").append(orig).append(" 字；标注 text 合计 ").append(text)
            .append(" 字（差 ").append(if (text - orig >= 0) "+" else "").append(text - orig)
            .append("）；模型原始产出 ").append(raw).append(" 字，其中有效 JSON 候选 ").append(body)
            .append(" 字（非 JSON 占 ").append(waste).append("%）\n")
        sb.append(ruleText(c))
        if (empty > 0) sb.append("⚠ 有 ").append(empty)
            .append(" 块跑完却没标出任何条目 —— 看「产出预览」里它到底吐了什么。\n")
        return sb.toString().trimEnd()
    }

    fun ruleText(c: Context): String {
        val b = load(c).filter { it.rules.isNotEmpty() }
        if (b.isEmpty()) return "规则生效：本次没有经过语言模型的块。\n"
        val keys = AnnotatorRules.LABELS.keys
        return buildString {
            append("规则生效（按块计）：\n")
            keys.forEach { k ->
                val total = b.count { it.rules.containsKey(k) }
                val ok = b.count { it.rules[k] == true }
                if (total > 0) append("  ").append(AnnotatorRules.LABELS[k]).append("：")
                    .append(ok).append('/').append(total).append('\n')
            }
        }
    }

    fun blocksText(c: Context, n: Int = 12): String {
        val b = recent(c, n)
        if (b.isEmpty()) return "（暂无逐块记录）"
        return b.joinToString("\n") { a ->
            "块 ${a.block}/${a.total} ${a.status} · 原文 ${a.origChars} 字 · 产出 ${a.rawChars} 字" +
                " · 标 ${a.annCount} 条 · text ${a.textChars} 字（差 ${if (a.lenDelta >= 0) "+" else ""}${a.lenDelta}）" +
                " · 首个差异 ${a.firstDiff} · 规则 ${a.rules.values.count { it }}/${a.rules.size}" +
                " · ${a.failCode}" + (if (a.fromCache) " · 从磁盘续上" else "")
        }
    }

    fun previewText(c: Context, n: Int = 3): String {
        val b = recent(c, n).filter { !it.fromCache }
        if (b.isEmpty()) return "（暂无产出片段）"
        return b.joinToString("\n\n") { a ->
            "— 块 ${a.block}/${a.total}（${a.status}）截取，非全文 —\n开头：${a.previewHead}\n结尾：${a.previewTail}"
        }
    }

    /** 崩溃文件里附最后 10 个块的一行摘要，定位「跑到哪一块、那块成没成」。 */
    fun crashTail(): String = synchronized(mem) {
        mem.takeLast(10).joinToString("\n") { a ->
            "块 ${a.block}/${a.total} ${a.status} 标 ${a.annCount} 条 差 ${a.lenDelta} ${a.failCode}"
        }
    }
}

/** 规则中文名集中放这里，避免 UI 与校验逻辑各写一份。 */
object AnnotatorRules {
    val LABELS = linkedMapOf(
        "VERBATIM" to "逐字保真（拼回原文）",
        "ORDERED_SUBSTRING" to "每条 text 是原文有序连续片段",
        "JSON_ONLY" to "只输出一个 JSON 数组",
        "NO_TRAILING_TEXT" to "JSON 后无解释尾文",
        "NO_INLINE_TAG" to "instruct 无方括号行内标签",
        "NO_IDENTITY" to "instruct 不写音色身份",
        "COUNT_BOUND" to "条数不超句末标点+2",
        "SPEAKER_OK" to "speaker 合法且非空",
        "INSTRUCT_OK" to "instruct 独立完整"
    )
}
