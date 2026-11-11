package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * R233 · 用户手工删掉的小块单独记账：这些段从此不参与合成，也不参与拼接。
 *
 * 旧行为恰好相反：删完提示「下次续跑会按原顺序重新生成」，等于白删。
 * 记账按 bookId 级（不是按 work 分桶），切换选中章后仍认得；删书时
 * BookStore.purge 整棵 work/{bookId} 删掉，记账自然一起清，不用另接。
 *
 * 每条带「段文本短哈希」：只按段号认，在选中章集合变化、段号重排时会
 * 误跳到别的段上；哈希对不上就不跳过，宁可重新合成也不丢内容。
 * segmap 里 text 是原文前 200 字，判定处用同一口径取，保证一致。
 */
object SegDrops {

    private val wavRe = Regex("""^direct_(\d{4})\.wav$""")

    private fun file(c: Context, bookId: String) =
        File(c.filesDir, "work/$bookId/dropped.json")

    private fun hash(text: String): String = try {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        d.take(8).joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { "" }

    private fun read(c: Context, bookId: String): MutableList<JSONObject> {
        val body = when (val r = AtomicJson.read(file(c, bookId))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return mutableListOf()
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { a.getJSONObject(it) }.toMutableList()
        } catch (_: Throwable) { mutableListOf() }
    }

    private fun write(c: Context, bookId: String, rows: List<JSONObject>): Boolean {
        val a = JSONArray(); rows.forEach { a.put(it) }
        return AtomicJson.write(file(c, bookId), a.toString())
    }

    /** 删小段 UI 调：从被删 WAV 所在 work 目录读一次 segmap，补上文本短哈希。 */
    fun mark(c: Context, bookId: String, files: List<File>): Int {
        if (files.isEmpty()) return 0
        val rows = read(c, bookId)
        val known = rows.map { it.optString("wav", "") }.toMutableSet()
        val maps = HashMap<File, Map<String, String>>()
        var added = 0
        for (f in files) {
            val wav = f.name
            if (!wavRe.matches(wav)) continue      // 旧 chunk_ 命名不进新链路
            if (!known.add(wav)) continue
            val dir = f.parentFile ?: File("")
            val textMap = maps.getOrPut(dir) {
                SegPlan.readMap(dir)?.associate { it.wavName to it.text } ?: emptyMap()
            }
            // segmap 缺了就留空哈希：老数据只按文件名认，安全降级
            rows.add(JSONObject().put("wav", wav)
                .put("hash", textMap[wav]?.let { hash(it) } ?: ""))
            added++
        }
        if (added > 0) write(c, bookId, rows)
        return added
    }

    /** 一次读入内存，合成循环逐段判定不再反复读盘。wav -> 短哈希（可能空串）。 */
    fun snapshot(c: Context, bookId: String): Map<String, String> =
        read(c, bookId).associate { it.optString("wav", "") to it.optString("hash", "") }
            .filterKeys { it.isNotBlank() }

    /** 哈希空 = 旧数据只认文件名；对不上 = 段号重排过，不跳过更安全。 */
    fun matches(snap: Map<String, String>, wav: String, text: String): Boolean {
        val h = snap[wav] ?: return false
        return h.isBlank() || h == hash(text)
    }
}
