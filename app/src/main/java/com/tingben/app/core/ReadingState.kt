package com.tingben.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File

object ReadingState {
    private fun file(c: Context) = File(c.filesDir, "reading_state.json")

    data class Pos(val chapter: Int, val para: Int = 0, val offset: Int = 0)

    private fun decode(v: Any?): Pos? = when (v) {
        is Number -> v.toInt().takeIf { it >= 0 }?.let { Pos(it) }
        is JSONObject -> v.optInt("chapter", -1).takeIf { it >= 0 }?.let {
            Pos(it, v.optInt("para", 0).coerceAtLeast(0),
                v.optInt("offset", 0).coerceAtLeast(0))
        }
        else -> null
    }

    fun pos(c: Context, bookId: String): Pos? = try {
        val f = file(c)
        if (!f.exists()) null
        else decode(JSONObject(f.readText()).opt(bookId))
    } catch (_: Throwable) {
        null
    }

    fun last(c: Context, bookId: String): Int? = pos(c, bookId)?.chapter

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
