package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SegAudio(
    val key: String,
    val bookId: String,
    val chapter: Int,
    val file: File,
    val name: String,
    val createdAt: Long,
    val bytes: Long,
    val seq: Int
)

data class ChapterAudio(
    val bookId: String,
    val chapter: Int,
    val title: String,
    val segments: List<SegAudio>,
    val merged: File?
)

data class BookAudio(
    val bookId: String,
    val title: String,
    val onShelf: Boolean,
    val chapters: List<ChapterAudio>
)

object AudioTree {
    private val segRe = Regex("""^(?:direct|chunk)_(\d{4})\.wav$""")

    private fun metaFile(c: Context) = File(c.filesDir, "audio/audio_tree.json")

    fun meta(c: Context): JSONObject {
        val body = when (val r = AtomicJson.read(metaFile(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> "{}"
        }
        return try { JSONObject(body) } catch (_: Throwable) { JSONObject() }
    }

    private fun save(c: Context, o: JSONObject): Boolean =
        AtomicJson.write(metaFile(c), o.toString())

    private fun metaWritable(c: Context): Boolean =
        AtomicJson.read(metaFile(c)) !is AtomicJson.Read.Corrupt

    fun forget(c: Context, paths: List<String>) {
        if (paths.isEmpty() || !metaWritable(c)) return
        val o = meta(c)
        val n = o.optJSONObject("names") ?: JSONObject()
        val m = o.optJSONObject("moves") ?: JSONObject()
        paths.forEach { n.remove(it); m.remove(it) }
        o.put("names", n).put("moves", m)
        save(c, o)
    }

    fun bookKey(bookId: String) = bookId
    fun chapterKey(bookId: String, chapter: Int) = "$bookId#$chapter"

    /**
     * 章成品文件名里的「目录里那章的名字」。章节表是权威来源；
     * 老书没有章节表时退回 work/ 下的 chapters.json，最后才用序号兜底。
     */
    fun chapterTitle(c: Context, bookId: String, chapter: Int): String {
        try {
            ChapterPlan.load(c, bookId)?.chapters?.getOrNull(chapter)?.title
                ?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Throwable) { }
        try {
            val root = File(c.filesDir, "work/$bookId")
            for (f in root.walkTopDown().filter { it.isFile && it.name == "chapters.json" }) {
                val t = readChapterTitles(f.parentFile ?: continue)?.get(chapter)
                    ?.takeIf { it.isNotBlank() }
                if (t != null) return t
            }
        } catch (_: Throwable) { }
        return "第 ${chapter + 1} 章"
    }

    private fun nameOf(o: JSONObject, key: String): String? =
        o.optJSONObject("names")?.optString(key, "")?.takeIf { it.isNotBlank() }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun readSegChapters(dir: File): Map<Int, Int>? {
        val body = when (val r = AtomicJson.read(File(dir, "seg_chapters.json"))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).associate { it to a.optInt(it, 0) }
        } catch (_: Throwable) { null }
    }

    private fun readChapterTitles(dir: File): Map<Int, String>? {
        val body = when (val r = AtomicJson.read(File(dir, "chapters.json"))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val a = JSONArray(body)
            val out = mutableMapOf<Int, String>()
            for (k in 0 until a.length()) {
                val o = a.optJSONObject(k) ?: continue
                val title = o.optString("title", "")
                if (title.isNotBlank()) out[o.optInt("index", k)] = title
            }
            out
        } catch (_: Throwable) { null }
    }

    fun chapterOnlyItems(c: Context): List<AudioItem> =
        AudioLibrary.list(c).filter {
            it.bookId != null && it.chaptersKnown && it.chapters.size == 1 &&
                it.file.isFile && it.file.length() > 44
        }

    private fun doneFile(dir: File) = File(dir, "chapter_done.json")

    fun readDone(dir: File): Map<Int, File> {
        val body = when (val r = AtomicJson.read(doneFile(dir))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return emptyMap()
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val ch = o.optInt("index", -1)
                val f = File(o.optString("file", ""))
                if (ch >= 0 && f.isFile && f.length() > 44) ch to f else null
            }.toMap()
        } catch (_: Throwable) { emptyMap() }
    }

    private fun writeDone(dir: File, cur: Map<Int, File>): Boolean {
        val a = JSONArray()
        cur.toSortedMap().forEach { (k, f) ->
            a.put(JSONObject().put("index", k).put("file", f.absolutePath))
        }
        return AtomicJson.write(doneFile(dir), a.toString())
    }

    fun markDone(dir: File, chapter: Int, file: File): Boolean {
        if (AtomicJson.read(doneFile(dir)) is AtomicJson.Read.Corrupt) return false
        val cur = readDone(dir).toMutableMap()
        cur[chapter] = file
        return writeDone(dir, cur)
    }

    fun clearDone(c: Context, bookId: String, chapter: Int) {
        val root = File(c.filesDir, "work/$bookId")
        if (!root.isDirectory) return
        try {
            // R189 · 归档套里的 chapter_done.json 属于另一套音色，删当前套某章
            // 音频时绝不能顺手改它，否则切回旧音色会重复合成、重复拼整章。
            root.walkTopDown()
                .filter { it.isFile && it.name == "chapter_done.json" &&
                        it.parentFile?.name?.startsWith("_voice_") != true }
                .forEach { f ->
                if (AtomicJson.read(f) is AtomicJson.Read.Corrupt) return@forEach
                val cur = readDone(f.parentFile ?: return@forEach).toMutableMap()
                if (cur.remove(chapter) != null) writeDone(f.parentFile ?: return@forEach, cur)
            }
        } catch (_: Throwable) { }
    }

    fun load(c: Context): List<BookAudio> {
        val o = meta(c)
        val moves = o.optJSONObject("moves")
        val books = BookStore.list(c).associateBy { it.id }
        val root = File(c.filesDir, "work")
        val titles = mutableMapOf<String, MutableMap<Int, String>>()
        val raw = mutableListOf<Triple<String, Int, File>>()
        val scCache = mutableMapOf<String, Map<Int, Int>?>()

        try {
            root.listFiles()?.forEach { bd ->
                if (!bd.isDirectory) return@forEach
                val bookId = bd.name
                bd.walkTopDown().filter { it.isFile && it.name == "chapters.json" }.forEach { f ->
                    readChapterTitles(f.parentFile ?: return@forEach)?.let {
                        titles.getOrPut(bookId) { mutableMapOf() }.putAll(it)
                    }
                }
                bd.walkTopDown().filter { it.isFile }.forEach { f ->
                    // R185 · 归档套是「留着以后切回来用」的旧音色，不能当成
                    // 当前有效段列进看台，否则同一段会出现好几份、还可能被合并。
                    if (f.parentFile?.name?.startsWith("_voice_") == true) return@forEach
                    val m = segRe.matchEntire(f.name) ?: return@forEach
                    if (f.length() <= 44) return@forEach
                    val dir = f.parentFile ?: return@forEach
                    val sc = scCache.getOrPut(dir.absolutePath) { readSegChapters(dir) }
                    val seg = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val ch = sc?.getOrElse(seg) { 0 } ?: 0
                    raw.add(Triple(bookId, ch, f))
                }
            }
        } catch (_: Throwable) { }

        val chapterOnly = chapterOnlyItems(c)

        val byBook = mutableMapOf<String, MutableMap<Int, MutableList<SegAudio>>>()
        raw.forEach { (originBook, originCh, f) ->
            val mv = moves?.optJSONObject(f.absolutePath)
            val bookId = mv?.optString("book", "")?.takeIf { it.isNotBlank() } ?: originBook
            val chapter = mv?.optInt("chapter", originCh) ?: originCh
            val custom = nameOf(o, f.absolutePath)
            val item = SegAudio(
                key = f.absolutePath,
                bookId = bookId,
                chapter = chapter,
                file = f,
                name = custom ?: "第 %03d 块".format(originCh * 1000 + segIndexOf(f) + 1),
                createdAt = f.lastModified(),
                bytes = f.length(),
                seq = 0
            )
            byBook.getOrPut(bookId) { mutableMapOf() }
                .getOrPut(chapter) { mutableListOf() }.add(item)
        }

        chapterOnly.forEach { a ->
            val bid = a.bookId ?: return@forEach
            byBook.getOrPut(bid) { mutableMapOf() }.getOrPut(a.chapters.first()) { mutableListOf() }
        }

        return byBook.map { (bookId, chMap) ->
            val b = books[bookId]
            val bookTitle = nameOf(o, bookKey(bookId)) ?: b?.title ?: "未归属的音频"
            val chapters = chMap.toSortedMap().map { (ch, segs) ->
                val ordered = segs.sortedWith(compareBy({ it.file.parentFile?.absolutePath ?: "" }, { it.file.name }))
                val numbered = ordered.mapIndexed { i, s -> s.copy(seq = i + 1) }
                val chapterTitle = nameOf(o, chapterKey(bookId, ch))
                    ?: titles[bookId]?.get(ch)?.takeIf { it.isNotBlank() }
                    ?: "第 ${ch + 1} 章"
                ChapterAudio(bookId, ch, chapterTitle, numbered,
                    chapterOnly.filter { it.bookId == bookId && ch in it.chapters }
                        .maxByOrNull { it.createdAt }?.file)
            }
            BookAudio(bookId, bookTitle, b != null, chapters)
        }.sortedByDescending { bk ->
            bk.chapters.flatMap { it.segments }.maxOfOrNull { it.createdAt }
                ?: bk.chapters.mapNotNull { it.merged?.lastModified() }.maxOfOrNull { it }
                ?: 0L
        }
    }

    private fun segIndexOf(f: File): Int =
        segRe.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

}
