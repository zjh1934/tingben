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

    fun setName(c: Context, key: String, name: String): Boolean {
        val o = meta(c)
        val n = o.optJSONObject("names") ?: JSONObject()
        if (name.isBlank()) n.remove(key) else n.put(key, name.trim())
        o.put("names", n)
        return save(c, o)
    }

    fun moveSegment(c: Context, path: String, bookId: String, chapter: Int): Boolean {
        val o = meta(c)
        val m = o.optJSONObject("moves") ?: JSONObject()
        m.put(path, JSONObject().put("book", bookId).put("chapter", chapter))
        o.put("moves", m)
        return save(c, o)
    }

    fun bookKey(bookId: String) = bookId
    fun chapterKey(bookId: String, chapter: Int) = "$bookId#$chapter"

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
            (0 until a.length()).associate { k ->
                val o = a.optJSONObject(k) ?: return@associate 0 to ""
                o.optInt("index", k) to o.optString("title", "")
            }.filter { it.value.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    fun mergedFile(c: Context, bookId: String, chapter: Int): File? =
        AudioLibrary.list(c)
            .filter { it.bookId == bookId && it.chaptersKnown && chapter in it.chapters && it.file.isFile }
            .maxByOrNull { it.createdAt }?.file

    fun load(c: Context): List<BookAudio> {
        val o = meta(c)
        val moves = o.optJSONObject("moves")
        val books = BookStore.list(c).associateBy { it.id }
        val root = File(c.filesDir, "work")
        val titles = mutableMapOf<String, MutableMap<Int, String>>()
        val raw = mutableListOf<Triple<String, Int, File>>()

        try {
            root.listFiles()?.forEach { bd ->
                if (!bd.isDirectory) return@forEach
                val bookId = bd.name
                bd.walkTopDown().filter { it.isFile }.forEach { f ->
                    val m = segRe.matchEntire(f.name) ?: return@forEach
                    if (f.length() <= 44) return@forEach
                    val dir = f.parentFile ?: return@forEach
                    val sc = readSegChapters(dir)
                    readChapterTitles(dir)?.let {
                        titles.getOrPut(bookId) { mutableMapOf() }.putAll(it)
                    }
                    val seg = m.groupValues[1].toIntOrNull() ?: return@forEach
                    val ch = sc?.getOrElse(seg) { 0 } ?: 0
                    raw.add(Triple(bookId, ch, f))
                }
            }
        } catch (_: Throwable) { }

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

        return byBook.map { (bookId, chMap) ->
            val b = books[bookId]
            val bookTitle = nameOf(o, bookKey(bookId)) ?: b?.title ?: "不在书架上的音频"
            val chapters = chMap.toSortedMap().map { (ch, segs) ->
                val ordered = segs.sortedWith(compareBy({ it.file.parentFile?.absolutePath ?: "" }, { it.file.name }))
                val numbered = ordered.mapIndexed { i, s -> s.copy(seq = i + 1) }
                val chapterTitle = nameOf(o, chapterKey(bookId, ch))
                    ?: titles[bookId]?.get(ch)?.takeIf { it.isNotBlank() }
                    ?: "第 ${ch + 1} 章"
                ChapterAudio(bookId, ch, chapterTitle, numbered, mergedFile(c, bookId, ch))
            }
            BookAudio(bookId, bookTitle, b != null, chapters)
        }.sortedByDescending { bk ->
            bk.chapters.flatMap { it.segments }.maxOfOrNull { it.createdAt } ?: 0L
        }
    }

    private fun segIndexOf(f: File): Int =
        segRe.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun allSegments(b: BookAudio): List<SegAudio> = b.chapters.flatMap { it.segments }

    fun chapterSegments(ch: ChapterAudio): List<File> = ch.segments.map { it.file }

    fun chapterTracks(c: Context, ch: ChapterAudio): List<Track> {
        val merged = ch.merged
        if (merged != null && merged.isFile) {
            return listOf(Track(merged, "${ch.title} · 整章", id = "merged:${merged.absolutePath}"))
        }
        return ch.segments.map { s ->
            Track(s.file, "${ch.title} · ${s.name}", id = "seg:${s.file.absolutePath}")
        }
    }
}
