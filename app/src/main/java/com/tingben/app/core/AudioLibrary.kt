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
    @Volatile var lastRegisterOk: Boolean = true
        private set

    fun dir(c: Context) = File(c.filesDir, "audio").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), "index.json")

    /** 目录名/文件名统一清洗：去路径分隔符与控制字符、限长、空则兜底。 */
    fun safeName(s: String): String {
        val cleaned = s.map { ch ->
            if (ch in "/\\:*?\"<>|\u0000\u0001" || ch.isISOControl()) '_' else ch
        }.joinToString("").trim().trim('.')
        return cleaned.take(60).ifBlank { "听本" }
    }

    /**
     * 整章成品按书名分目录：audio/<书名>/。同名书另开带短 id 的目录，
     * 不然两本书的 001_第一章.wav 会互相覆盖（导出侧 R157 早防过这件事）。
     */
    fun bookDir(c: Context, bookId: String, bookTitle: String): File {
        val base = File(dir(c), safeName(bookTitle))
        if (!base.isDirectory) return base.apply { mkdirs() }
        val owner = base.listFiles()
            ?.firstOrNull { it.isFile && it.name.endsWith(".meta.json") }
            ?.let { f ->
                try { JSONObject(f.readText()).optString("bookId", "") }
                catch (_: Throwable) { "" }
            } ?: ""
        val target = if (owner.isBlank() || owner == bookId) base
        else File(dir(c), safeName(bookTitle) + "_" + bookId.take(6))
        return target.apply { mkdirs() }
    }

    /** 章成品名 = 全书章序号 + 目录里那一章的名字，如 003_第三章 山雨欲来.wav。 */
    fun chapterFile(c: Context, bookId: String, bookTitle: String,
                    chapter: Int, chapterTitle: String): File =
        File(bookDir(c, bookId, bookTitle),
            "%03d_%s.wav".format(chapter + 1, safeName(chapterTitle)))

    /**
     * R272 · 不再新生成整本.wav：一本书拼一份大文件会把手机撑爆，唯一成品形态
     * 是「一章一个音频」（chapterFile）。下面 isWholeBook / chapterOnlyFor /
     * hasChapterAudio 只用于**兼容读取旧版已落盘的整本.wav**，不主动删用户文件；
     * 新制作链路（Producer 收尾）不再调用任何整本路径函数。
     */
    /** 整本成品（含旧版兜底、单章书重做后沿用整本文件名）不是单章成品。 */
    fun isWholeBook(item: AudioItem): Boolean =
        item.scope.startsWith("整本") || item.file.name == "整本.wav"

    /** 严格：只取真章级成品，供重做/删除用——绝不能把整本成品删掉。 */
    fun chapterOnlyFor(c: Context, bookId: String, chapterIndex: Int): AudioItem? =
        list(c).filter { it.bookId == bookId && it.chaptersKnown &&
            chapterIndex in it.chapters && !isWholeBook(it) && it.file.length() > 44L }
            .maxByOrNull { it.createdAt }

    /**
     * 宽松：供补拼去重。多章书的整本成品覆盖很多章，不能算「这章已拼」；
     * 单章书的整本成品恰好只覆盖那一章，本身就是章成品，别再拼一份一样的。
     */
    fun hasChapterAudio(c: Context, bookId: String, chapterIndex: Int): Boolean =
        list(c).any { it.bookId == bookId && it.chaptersKnown &&
            chapterIndex in it.chapters && it.file.length() > 44L &&
            (!isWholeBook(it) || it.chapters.size == 1) }

    /** 成品可能散在顶层（旧版、整本成品）与各书名子目录，恢复扫描都看。 */
    fun candidateDirs(c: Context): List<File> {
        val out = mutableListOf(dir(c))
        dir(c).listFiles()?.filter { it.isDirectory }?.let { out.addAll(it) }
        return out
    }

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
                if (!file.isFile || file.length() <= 44) null else {
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
        if (!ok) UiErrors.storage(writeError!!, null)
        return ok
    }

    fun register(c: Context, bookId: String, bookTitle: String, only: Set<Int>?,
                 direct: Boolean, file: File, allChapterCount: Int = -1): AudioItem {
        lastRegisterOk = false
        val chs: Set<Int> = when {
            !only.isNullOrEmpty() -> only
            allChapterCount > 0 -> (0 until allChapterCount).toSet()
            else -> emptySet()
        }
        val madeMode = (TaskResume.byBook(c, bookId)?.modeId ?: TaskResume.load(c)?.modeId)
            ?.let { id -> ModelStore.Mode.values().firstOrNull { it.id == id }?.label }
            ?: ModelStore.currentMode(c).label
        val item = AudioItem(
            id = file.nameWithoutExtension,
            file = file,
            bookId = bookId,
            title = bookTitle,
            scope = scopeLabel(only),
            mode = madeMode,
            createdAt = System.currentTimeMillis(),
            bytes = file.length(),
            chapters = chs,
            chaptersKnown = chs.isNotEmpty()
        )
        val all = readIndex(c)
        if (all == null) {
            writeError = "音频记录损坏，本次成品已保留但未登记"
            UiErrors.storage(writeError!!, null)
            return item
        }
        // R175 · P3-8：成品文件名是 UUID，index.json 一旦损坏/丢失，
        // 光靠文件名永远认不出是哪本书、哪些章。写一份自描述伴生元数据，
        // 兜底恢复时读它，书名与章号不会永久退化成「有声书 / 整本（旧版）」。
        try {
            val meta = JSONObject()
                .put("bookId", item.bookId ?: "")
                .put("title", item.title)
                .put("scope", item.scope)
                .put("mode", item.mode)
                .put("createdAt", item.createdAt)
                .put("bytes", item.bytes)
            if (item.chaptersKnown) {
                val arr = JSONArray()
                item.chapters.sorted().forEach { arr.put(it) }
                meta.put("chapterIndices", arr)
            }
            AtomicJson.write(File(item.file.parentFile ?: dir(c),
                "${item.id}.meta.json"), meta.toString())
        } catch (_: Throwable) { }
        // R259 · 成品名是「章序号_章名.wav」，两本同名书的同章文件同名（id 撞车）。
        // 原来按 id 去重会把另一本书的记录一起清掉；只去掉同一路径的重复登记。
        all.removeAll { it.file.absolutePath == item.file.absolutePath }
        all.add(0, item)
        lastRegisterOk = writeIndex(c, all)
        return item
    }

    fun list(c: Context): List<AudioItem> {
        val items = (readIndex(c) ?: mutableListOf()).toMutableList()
        val known = items.map { it.file.absolutePath }.toSet()
        try {
            val books = BookStore.list(c)
            // 整章成品按书名分了子目录，恢复扫描要连这些子目录一起看；
            // 顶层仍留着旧版 chapter_*.wav 与整本成品，兼容逻辑不删。
            candidateDirs(c).forEach { folder ->
                folder.listFiles()
                    ?.filter { it.isFile && it.length() > 44 && it.name.endsWith(".wav") &&
                            it.absolutePath !in known }
                    ?.forEach { f ->
                        val stem = f.nameWithoutExtension
                        // R175 · 先认自描述元数据；没有才退回旧的按文件名猜
                        val meta = try {
                            val r = AtomicJson.read(File(f.parentFile ?: folder, "$stem.meta.json"))
                            if (r is AtomicJson.Read.Ok) JSONObject(r.text) else null
                        } catch (_: Throwable) { null }
                        val cm = Regex("""^chapter_([0-9a-fA-F]{8})_(\d{3})_[0-9a-fA-F]{8}$""").matchEntire(stem)
                        val ch = cm?.groupValues?.get(2)?.toIntOrNull()?.let { it - 1 }
                        val b = if (cm != null)
                            books.find { it.id.take(8).equals(cm.groupValues[1], ignoreCase = true) }
                        else books.find { it.id == stem }
                        if (meta != null) {
                            val idx = meta.optJSONArray("chapterIndices")
                            val chs = if (idx == null) emptySet() else
                                (0 until idx.length()).mapNotNull { idx.optInt(it, -1).takeIf { v -> v >= 0 } }.toSet()
                            items.add(AudioItem(stem, f,
                                meta.optString("bookId", "").ifBlank { null },
                                meta.optString("title", "有声书").ifBlank { "有声书" },
                                meta.optString("scope", "整本").ifBlank { "整本" },
                                meta.optString("mode", "—").ifBlank { "—" },
                                meta.optLong("createdAt", f.lastModified()),
                                meta.optLong("bytes", f.length()), chs, chs.isNotEmpty()))
                        } else if (cm != null && b != null && ch != null && ch >= 0) {
                            items.add(AudioItem(stem, f, b.id, b.title, scopeLabel(setOf(ch)), "—",
                                f.lastModified(), f.length(), setOf(ch), true))
                        } else {
                            items.add(AudioItem(stem, f, b?.id, b?.title ?: "有声书",
                                "整本（旧版）", "—", f.lastModified(), f.length()))
                        }
                    }
            }
        } catch (_: Throwable) { }
        return items.sortedByDescending { it.createdAt }
    }

    /**
     * R282 · 跳章起点：旧版整本.wav 覆盖全书章号，只要它比章成品新，
     * 原来 maxBy(createdAt) 就会把整本大文件选成某一章的播放源；而
     * chapterStartMs 的伴生偏移表整本成品从来没有，起点恒 0 —— 从第 N
     * 章点播放实际播的是整本开头。现在只要这一章有「一章一个音频」的
     * 成品，一律优先它；整本成品只在某章确实没有任何单章成品时兜底
     * （旧版已落盘文件不主动删、仍可听，但起点只能从 0 起，不假装精确）。
     */
    fun latestByChapter(c: Context, bookId: String): Map<Int, AudioItem> =
        list(c).filter { it.bookId == bookId && it.chaptersKnown && it.file.length() > 44 }
            .flatMap { a -> a.chapters.map { ch -> ch to a } }
            .groupBy { it.first }
            .mapValues { (_, pairs) ->
                val single = pairs.filter { !isWholeBook(it.second) }
                (single.ifEmpty { pairs }).maxBy { it.second.createdAt }.second
            }

    fun playableChapters(c: Context, bookId: String): Set<Int> =
        list(c).filter { it.bookId == bookId && it.chaptersKnown }
            .flatMap { it.chapters }.toSet()

    /**
     * R287 · 一次 list() 同时给出「章级最新成品」与「可播章集合」。
     * 原来目录页两个查询各扫一遍全盘（读 index.json + 遍历所有成品目录 +
     * 逐文件读伴生 .meta.json），制作中 stage 一变就双份 IO，是目录页发卡的主因之一。
     * 语义与分别调用 latestByChapter / playableChapters 完全等价：
     * latest 只看 length>44，playable 不看长度。
     */
    fun chapterSnapshot(c: Context, bookId: String): Pair<Map<Int, AudioItem>, Set<Int>> {
        val all = list(c).filter { it.bookId == bookId && it.chaptersKnown }
        val playable = all.flatMap { it.chapters }.toSet()
        val latest = all.filter { it.file.length() > 44 }
            .flatMap { a -> a.chapters.map { ch -> ch to a } }
            .groupBy { it.first }
            .mapValues { (_, pairs) ->
                val single = pairs.filter { !isWholeBook(it.second) }
                (single.ifEmpty { pairs }).maxBy { it.second.createdAt }.second
            }
        return latest to playable
    }

    /** R287 · 书架页原来每本书各扫一遍全盘算可播章数，改成一次 list() 分组统计。 */
    fun playableCountByBook(c: Context): Map<String, Int> =
        list(c).filter { it.bookId != null && it.chaptersKnown }
            .groupBy { it.bookId!! }
            .mapValues { (_, items) -> items.flatMap { it.chapters }.toSet().size }

    fun playableItems(c: Context, bookId: String): List<Pair<Int, AudioItem>> =
        latestByChapter(c, bookId).toList().sortedBy { it.first }

    fun audioFor(c: Context, bookId: String, chapterIndex: Int): AudioItem? =
        latestByChapter(c, bookId)[chapterIndex]

    fun chapterStartMs(c: Context, item: AudioItem, chapterIndex: Int): Long {
        if (!item.chaptersKnown) return 0L
        val f = File(item.file.parentFile ?: dir(c), "${item.id}.chapters.json")
        val body = when (val r = AtomicJson.read(f)) {
            is AtomicJson.Read.Ok -> r.text
            else -> return 0L
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { a.getJSONObject(it) }
                .firstOrNull { it.optInt("index", -1) == chapterIndex }
                ?.optLong("startMs", 0L)?.coerceAtLeast(0L) ?: 0L
        } catch (_: Throwable) { 0L }
    }

    fun remove(c: Context, id: String): Boolean {
        val all = readIndex(c) ?: return corruptRemove()
        val recorded = all.find { it.id == id }?.file
        val f = recorded ?: run {
            var found: File? = null
            for (d in candidateDirs(c)) {
                val cand = File(d, "$id.wav")
                if (cand.isFile) { found = cand; break }
            }
            found ?: File(dir(c), "$id.wav")
        }
        return removeAt(c, all, f)
    }

    /** R259 · 有完整 File 时直接按路径删，不用同名 id 去猜是哪本书的成品。 */
    fun removeFile(c: Context, f: File): Boolean {
        val all = readIndex(c) ?: return corruptRemove()
        return removeAt(c, all, f)
    }

    // R272 · 整本.wav 不再新生成，「作废旧整本」(R271) 整条伪需求已删。
    // 旧版落在磁盘的整本.wav 仍由 list() 兜底扫描兼容读取，不主动删用户文件。

    private fun corruptRemove(): Boolean {
        writeError = "音频记录损坏，删除暂不可用"
        UiErrors.storage(writeError!!, null)
        return false
    }

    private fun removeAt(c: Context, all: List<AudioItem>, f: File): Boolean {
        // R259 · 两本同名书的同章成品 id 会撞车，按 id 过滤会连坐删掉另一本的记录。
        if (!writeIndex(c, all.filterNot { it.file.absolutePath == f.absolutePath })) return false
        if (f.isFile && !f.delete()) {
            writeError = "记录已移除，但文件删不掉，会在「我的音频」里重新出现"
            UiErrors.storage(writeError!!, null)
            return false
        }
        // R175 · P3-8：原来只删 .wav，.chapters.json / .meta.json 留成孤儿，
        // 越删越多；现在一并清掉，且按实际所在目录找伴生文件。
        val home = f.parentFile ?: dir(c)
        val stem = f.nameWithoutExtension
        listOf("${stem}.chapters.json", "${stem}.meta.json").forEach { n ->
            try { File(home, n).delete() } catch (_: Throwable) { }
        }
        return true
    }

    fun durationMsOfBytes(bytes: Long): Long =
        ((bytes - 44).coerceAtLeast(0) * 1000L / 48000L)

    fun durationMs(f: File): Long = durationMsOfBytes(f.length())
}
