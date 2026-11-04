package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R18 · 成品登记。
 *
 * 旧版把每次产出都写成 audio/<bookId>.wav：
 *  - 做第 1 章和做第 3 章互相覆盖同一个文件；
 *  - 音频页把文件名当 bookId 查书名，一章的成品显示成整本书，
 *    看起来就像「一章杠到书架上变成了另一本书」。
 *
 * 现在成品用独立 id + index.json 记「书名 / 范围 / 制作方式 / 时间」，
 * 书架只放源文本、任务页只放制作记录、音频页只放成品，三者互不增长。
 */
data class AudioItem(
    val id: String,
    val file: File,
    val bookId: String?,
    val title: String,
    val scope: String,
    val mode: String,
    val createdAt: Long,
    val bytes: Long
)

object AudioLibrary {
    fun dir(c: Context) = File(c.filesDir, "audio").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")

    fun scopeLabel(only: Set<Int>?): String = when {
        only == null || only.isEmpty() -> "整本"
        only.size == 1 -> "第 ${only.first() + 1} 章"
        else -> {
            val lo = only.min() + 1
            val hi = only.max() + 1
            if (hi - lo + 1 == only.size) "第 $lo–$hi 章" else "选中 ${only.size} 章"
        }
    }

    private fun readIndex(c: Context): MutableList<AudioItem> {
        val f = index(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val file = File(o.getString("file"))
                if (!file.exists()) null else AudioItem(
                    id = o.optString("id", file.nameWithoutExtension),
                    file = file,
                    bookId = o.optString("bookId", "").takeIf { it.isNotBlank() },
                    title = o.optString("title", "有声书"),
                    scope = o.optString("scope", "整本"),
                    mode = o.optString("mode", "—"),
                    createdAt = o.optLong("createdAt", file.lastModified()),
                    bytes = o.optLong("bytes", file.length())
                )
            }.toMutableList()
        } catch (_: Throwable) { mutableListOf() }
    }

    private fun writeIndex(c: Context, items: List<AudioItem>) {
        try {
            val a = JSONArray()
            items.forEach { it ->
                a.put(JSONObject()
                    .put("id", it.id)
                    .put("file", it.file.absolutePath)
                    .put("bookId", it.bookId ?: "")
                    .put("title", it.title)
                    .put("scope", it.scope)
                    .put("mode", it.mode)
                    .put("createdAt", it.createdAt)
                    .put("bytes", it.bytes))
            }
            val f = index(c)
            val tmp = File(f.parentFile, "index.json.part")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.writeText(a.toString()); tmp.delete() }
        } catch (_: Throwable) { }
    }

    fun register(c: Context, bookId: String, bookTitle: String, only: Set<Int>?,
                 direct: Boolean, file: File): AudioItem {
        val item = AudioItem(
            id = file.nameWithoutExtension,
            file = file,
            bookId = bookId,
            title = bookTitle,
            scope = scopeLabel(only),
            mode = if (direct) "直接朗读" else "标注后合成",
            createdAt = System.currentTimeMillis(),
            bytes = file.length()
        )
        val all = readIndex(c)
        all.removeAll { it.id == item.id }
        all.add(0, item)
        writeIndex(c, all)
        return item
    }

    /** 兼容旧版 audio/<bookId>.wav：没在 index 里的按 bookId 查书名，标成「整本（旧版）」。 */
    fun list(c: Context): List<AudioItem> {
        val items = readIndex(c).toMutableList()
        val known = items.map { it.file.name }.toSet()
        try {
            dir(c).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".wav") && it.name !in known }
                ?.forEach { f ->
                    val b = BookStore.list(c).find { it.id == f.nameWithoutExtension }
                    items.add(AudioItem(f.nameWithoutExtension, f, b?.id,
                        b?.title ?: "有声书", "整本（旧版）", "—",
                        f.lastModified(), f.length()))
                }
        } catch (_: Throwable) { }
        return items.sortedByDescending { it.createdAt }
    }

    fun remove(c: Context, id: String) {
        val all = readIndex(c)
        all.find { it.id == id }?.file?.delete()
        writeIndex(c, all.filter { it.id != id })
    }

    /** WAV 24kHz / 16bit / mono：48000 字节 = 1 秒。 */
    fun durationMs(f: File): Long =
        ((f.length() - 44).coerceAtLeast(0) * 1000L / 48000L)
}
