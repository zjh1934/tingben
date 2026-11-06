package com.tingben.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * R74 / R95 · 阅读位置：每本书记住上次读到第几章、章内滚到哪一段。
 * 纯 UI 状态，不进声音指纹、不进 diff、不进任务书签；写失败静默，不影响看书。
 * R95 · 旧数据是 bookId -> Int（只记章），新数据是 bookId -> {chapter,para,offset}；
 * 两种都读得动，升级后已存的位置不丢。
 */
object ReadingState {
    private fun file(c: Context) = File(c.filesDir, "reading_state.json")

    data class Pos(val chapter: Int, val para: Int = 0, val offset: Int = 0)

    /** R96 · 单本与批量共用一份兼容解析，避免两套逻辑慢慢漂移。 */
    private fun decode(v: Any?): Pos? = when (v) {
        is Number -> v.toInt().takeIf { it >= 0 }?.let { Pos(it) }
        is JSONObject -> v.optInt("chapter", -1).takeIf { it >= 0 }?.let {
            Pos(it, v.optInt("para", 0).coerceAtLeast(0),
                v.optInt("offset", 0).coerceAtLeast(0))
        }
        else -> null
    }

    /** 兼容读：旧整数值 = 只记了章，段内位置归零。 */
    fun pos(c: Context, bookId: String): Pos? = try {
        val f = file(c)
        if (!f.exists()) null
        else decode(JSONObject(f.readText()).opt(bookId))
    } catch (_: Throwable) {
        null
    }

    /** 书架只显示章号，沿用旧调用形状。 */
    fun last(c: Context, bookId: String): Int? = pos(c, bookId)?.chapter

    /**
     * R96 · 书架一次读完所有书的章号：不再对每本书各 readText + 解析一次。
     * 与 last() 共用 decode，旧整数 / 新对象两种数据结果逐本一致。
     */
    fun allLast(c: Context): Map<String, Int> = try {
        val f = file(c)
        if (!f.exists()) emptyMap()
        else {
            val o = JSONObject(f.readText())
            val out = HashMap<String, Int>()
            for (k in o.keys()) {
                decode(o.opt(k))?.let { out[k] = it.chapter }
            }
            out
        }
    } catch (_: Throwable) {
        emptyMap()
    }

    /**
     * openReader 只传章：同章重开保留段内位置，换章自动归零。
     * 看书页滚动时显式传 para / offset 覆盖，写入频率由 snapshotFlow 去重收敛。
     */
    fun save(c: Context, bookId: String, chapter: Int, para: Int? = null, offset: Int? = null) {
        try {
            val old = pos(c, bookId)
            val same = old?.chapter == chapter
            val p = para ?: if (same) old!!.para else 0
            val off = offset ?: if (same) old!!.offset else 0
            val f = file(c)
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            o.put(bookId, JSONObject()
                .put("chapter", chapter).put("para", p).put("offset", off))
            f.writeText(o.toString())
        } catch (_: Throwable) {
        }
    }
}
