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
 *
 * R61 · 目录页四列要按章判「已合成 / 已标注且可整本顺序播」，
 * 而 scope 只是一句文字（「第 3 章」），索引在 register 时就被丢掉了。
 * 这里补 chapterIndices：新成品写精确索引，旧成品按 scope 反推，
 * 反推不出来的（整本 / 选中 N 章 / 旧版文件）一律 known=false，
 * 界面显示灰，不冒充某一章已完成。
 */
data class AudioItem(
    val id: String,
    val file: File,
    val bookId: String?,
    val title: String,
    val scope: String,
    val mode: String,
    val createdAt: Long,
    val bytes: Long,
    // R61 · 末尾带默认值 ⇒ 所有既有构造点零改动
    val chapters: Set<Int> = emptySet(),
    val chaptersKnown: Boolean = false
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

    /**
     * 旧 index.json 没有 chapterIndices：只能从人写的范围文字反推。
     * 「第 3 章」「第 2–5 章」是可信的；「整本」「选中 3 章」「整本（旧版）」没有
     * 逐章信息，返回 known=false —— 宁可灰着，也不把整本成品点亮成某一章。
     */
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
        // 显式写了空数组 = 整本且未展开，仍标 unknown，界面不点亮单章
        return s.toSet() to s.isNotEmpty()
    }

    private fun readIndex(c: Context): MutableList<AudioItem> {
        val f = index(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
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
        } catch (_: Throwable) { mutableListOf() }
    }

    private fun writeIndex(c: Context, items: List<AudioItem>) {
        try {
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
            val f = index(c)
            val tmp = File(f.parentFile, "index.json.part")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.writeText(a.toString()); tmp.delete() }
        } catch (_: Throwable) { }
    }

    fun register(c: Context, bookId: String, bookTitle: String, only: Set<Int>?,
                 direct: Boolean, file: File, allChapterCount: Int = -1): AudioItem {
        // 整本时把 0..n-1 展开，否则第四列永远不亮；只有单章/范围才写精确索引。
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
            mode = if (direct) "直接朗读" else "标注后合成",
            createdAt = System.currentTimeMillis(),
            bytes = file.length(),
            chapters = chs,
            chaptersKnown = chs.isNotEmpty()
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

    // ── R61 · 目录页四列的查询入口（唯一真值来源） ──────────────────────────

    /** 每章取最近一份成品；只认 chaptersKnown，旧「整本」不冒充具体章。 */
    fun latestByChapter(c: Context, bookId: String): Map<Int, AudioItem> =
        list(c).filter { it.bookId == bookId && it.chaptersKnown && it.file.length() > 44 }
            .flatMap { a -> a.chapters.map { ch -> ch to a } }
            .groupBy { it.first }
            .mapValues { (_, pairs) -> pairs.maxBy { it.second.createdAt }.second }

    /** 第四列准入门槛：标注后合成 + 索引已知。直接朗读的成品只能进第三列。 */
    fun playableChapters(c: Context, bookId: String): Set<Int> =
        list(c).filter { it.bookId == bookId && it.chaptersKnown && it.mode == "标注后合成" }
            .flatMap { it.chapters }.toSet()

    /** 按章序返回第四列可播条目，供 PlayQueue 与悬浮条目录共用同一顺序。 */
    fun playableItems(c: Context, bookId: String): List<Pair<Int, AudioItem>> =
        latestByChapter(c, bookId).filter { (_, a) -> a.mode == "标注后合成" }
            .toList().sortedBy { it.first }

    /** 第三列单章播放：直接朗读成品也算，但未标注。 */
    fun audioFor(c: Context, bookId: String, chapterIndex: Int): AudioItem? =
        latestByChapter(c, bookId)[chapterIndex]

    fun remove(c: Context, id: String) {
        val all = readIndex(c)
        all.find { it.id == id }?.file?.delete()
        writeIndex(c, all.filter { it.id != id })
    }

    /** WAV 24kHz / 16bit / mono：48000 字节 = 1 秒。 */
    fun durationMs(f: File): Long =
        ((f.length() - 44).coerceAtLeast(0) * 1000L / 48000L)
}
