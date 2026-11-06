package com.tingben.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * R74 · 阅读位置：每本书记住上次打开到第几章。
 * 纯 UI 状态，不进声音指纹、不进 diff、不进任务书签；写失败静默，不影响看书。
 */
object ReadingState {
    private fun file(c: Context) = File(c.filesDir, "reading_state.json")

    fun last(c: Context, bookId: String): Int? = try {
        val f = file(c)
        if (!f.exists()) null
        else JSONObject(f.readText()).optInt(bookId, -1).takeIf { it >= 0 }
    } catch (_: Throwable) {
        null
    }

    fun save(c: Context, bookId: String, chapter: Int) {
        try {
            val f = file(c)
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            o.put(bookId, chapter)
            f.writeText(o.toString())
        } catch (_: Throwable) {
        }
    }
}
