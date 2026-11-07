package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AudioItem(
    val id: String,
    val file: File,
    val bookId: String?,
    val title: String,
    val scope: String,
    val mode: String,
    val createdAt: Long,
    val bytes: Long,
    val chapters: Set<Int> = emptySet(),
    val chaptersKnown: Boolean = false
)

object AudioLibrary {
    @Volatile var readError: String? = null
        private set
    @Volatile var writeError: String? = null
        private set

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

    private fun scopeIndices(scope: String): Pair<Set<Int>, Boolean> {
        val one = Regex("""^第\s*(\d+)\s*章$""").matchEntire(scope.trim())
        if (one != null) {
            val n = one.groupValues[1].toIntOrNull() ?: return emptySet<Int>() to false
            return setOf(n - 1) to (n > 0)
        }
        val range = Regex("""^第\s*(\d+)\s*[–\-~至]\s*(\d+)\s*章$""").matchEntire(scope.trim())
        if (range != null) {
            val a = range.groupValues[1].toIntOrNull() ?: return emptySet<Int>() to false
            val b = range.groupValues[2].toIntOrNull() ?: return emptySet<Int>() to false
            if (a <= 0 || b < a || b - a > 2000) return emptySet<Int>() to false
            return ((a - 1)..(b - 1)).toSet() to true
        }
        return emptySet<Int>() to false
    }

    private fun readIndices(o: JSONObject): Pair<Set<Int>, Boolean> {
        val arr = o.optJSONArray("chapterIndices") ?: return scopeIndices(o.optString("scope", ""))
        val s = mutableSetOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v >= 0) s.add(v)
        }
        return s.toSet() to s.isNotEmpty()
    }

    private fun readIndex(c: Context): MutableList<AudioItem>? {
        val body = when (val r = AtomicJson.read(index(c))) {
            is AtomicJson.Read.Missing -> { readError = null; return mutableListOf() }
            is AtomicJson.Read.Corrupt -> {
                readError = "音频记录损坏，已按文件恢复"
                return null
            }
            is AtomicJson.Read.Ok -> {
                readError = if (r.from.isEmpty()) null else "音频记录已从上一次保存恢复"
                r.text
            }
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val file = File(o.getString("file"))
                if (!file.exists()) null else {
                    val (chs, known) = readIndices(o)
                    AudioItem(
                        id = o.optString("id", file.nameWithoutExtension),
                        file = file,
                        bookId = o.optString("bookId", "").takeIf { it.isNotBlank() },
                        title = o.optString("title", "有声书"),
                        scope = o.optString("scope", "整本"),
                        mode = o.optString("mode", "—"),
                        createdAt = o.optLong("createdAt", file.lastModified()),
                        bytes = o.optLong("bytes", file.length()),
                        chapters = chs,
                        chaptersKnown = known
                    )
                }
            }.toMutableList()
        } catch (_: Throwable) {
            readError = "音频记录内容不全，已按文件恢复"
            null
        }
    }

    private fun writeIndex(c: Context, items: List<AudioItem>): Boolean {
        val a = JSONArray()
        items.forEach { it ->
            val o = JSONObject()
                .put("id", it.id)
                .put("file", it.file.absolutePath)
                .put("bookId", it.bookId ?: "")
                .put("title", it.title)
                .put("scope", it.scope)
                .put("mode", it.mode)
                .put("createdAt", it.createdAt)
                .put("bytes", it.bytes)
            if (it.chaptersKnown) {
                val arr = JSONArray()
                it.chapters.sorted().forEach { arr.put(it) }
                o.put("chapterIndices", arr)
            }
            a.put(o)
        }
        val ok = AtomicJson.write(index(c), a.toString())
        writeError = if (ok) null else "音频记录写入失败，旧记录已保留"
        return ok
    }

    fun register(c: Context, bookId: String, bookTitle: String, only: Set<Int>?,
                 direct: Boolean, file: File, allChapterCount: Int = -1): AudioItem {
        val chs: Set<Int> = when {
            !only.isNullOrEmpty() -> only
            allChapterCount > 0 -> (0 until allChapterCount).toSet()
            else -> emptySet()
        }
        val item = AudioItem(
            id = file.nameWithoutExtension,
            file = file,
            bookId = bookId,
            title = bookTitle,
            scope = scopeLabel(only),
            mode = "直接朗读",
            createdAt = System.currentTimeMillis(),
            bytes = file.length(),
            chapters = chs,
            chaptersKnown = chs.isNotEmpty()
        )
        val all = readIndex(c)
        if (all == null) {
            writeError = "音频记录损坏，本次成品已保留但未登记"
            return item
        }
        all.removeAll { it.id == item.id }
        all.add(0, item)
        writeIndex(c, all)
        return item
    }

    fun list(c: Context): List<AudioItem> {
        val items = (readIndex(c) ?: mutableListOf()).toMutableList()
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


    fun latestByChapter(c: Context, bookId: String): Map<Int, AudioItem> =
        list(c).filter { it.bookId == bookId && it.chaptersKnown && it.file.length() > 44 }
            .flatMap { a -> a.chapters.map { ch -> ch to a } }
            .groupBy { it.first }
            .mapValues { (_, pairs) -> pairs.maxBy { it.second.createdAt }.second }

    fun playableChapters(c: Context, bookId: String): Set<Int> =
        list(c).filter { it.bookId == bookId && it.chaptersKnown }
            .flatMap { it.chapters }.toSet()

    fun playableItems(c: Context, bookId: String): List<Pair<Int, AudioItem>> =
        latestByChapter(c, bookId).toList().sortedBy { it.first }

    fun audioFor(c: Context, bookId: String, chapterIndex: Int): AudioItem? =
        latestByChapter(c, bookId)[chapterIndex]

    fun chapterStartMs(c: Context, item: AudioItem, chapterIndex: Int): Long {
        if (!item.chaptersKnown) return 0L
        val f = File(dir(c), "${item.id}.chapters.json")
        if (!f.isFile) return 0L
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optInt("index", -1) == chapterIndex }
                ?.optLong("startMs", 0L)?.coerceAtLeast(0L) ?: 0L
        } catch (_: Throwable) { 0L }
    }

    fun remove(c: Context, id: String): Boolean {
        val all = readIndex(c)
        if (all == null) {
            writeError = "音频记录损坏，删除暂不可用"
            return false
        }
        all.find { it.id == id }?.file?.delete()
        return writeIndex(c, all.filter { it.id != id })
    }

    fun durationMsOfBytes(bytes: Long): Long =
        ((bytes - 44).coerceAtLeast(0) * 1000L / 48000L)

    fun durationMs(f: File): Long = durationMsOfBytes(f.length())
}
