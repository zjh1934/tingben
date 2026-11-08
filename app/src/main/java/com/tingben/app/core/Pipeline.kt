package com.tingben.app.core

import android.content.Context
import com.tingben.app.ProduceService
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

data class Book(val id: String, val title: String, val path: String, val chars: Int,
                val sha: String = "")
data class Chapter(val index: Int, val title: String, val start: Int, val end: Int)
data class Chunk(val index: Int, val chapter: Int, val text: String, val start: Int, val end: Int)
data class Ann(val text: String, val speaker: String, val instruct: String)

object BookStore {
    // R139 · 文本字符数上限。Importer 的 64MB 是「字节」上限，中文 UTF-8 一个字 3 字节，
    // 64MB ≈ 2100 万字，JVM 里 String 是 UTF-16（约 42MB），再叠加切块列表与
    // 正则候选，很容易 OOM。这里按「字符」再设一道更早、更贴合内存的闸门。
    const val MAX_TEXT_CHARS = 6_000_000

    private fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    private fun meta(c: Context) = File(dir(c), "books.json")
    // H1 · 置顶单独存一份，不动 Book 数据类与既有 save()（老版本 books.json 零迁移）。
    // list() 只按这个集合把置顶的书排到前面，稳定排序保留其余原顺序。
    private fun pinFile(c: Context) = File(dir(c), "pins.json")

    fun pinned(c: Context): Set<String> = try {
        val a = JSONArray(pinFile(c).readText())
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (_: Throwable) { emptySet() }

    fun setPinned(c: Context, id: String, on: Boolean) {
        try {
            val s = pinned(c).toMutableSet()
            if (on) s.add(id) else s.remove(id)
            val a = JSONArray()
            s.forEach { a.put(it) }
            pinFile(c).writeText(a.toString())
            EventLog.log(EventLog.Kind.MODEL, "BOOK_PIN", "$id on=$on")
        } catch (_: Throwable) { }
    }

    fun list(c: Context): List<Book> {
        val f = meta(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Book(o.getString("id"), o.getString("title"), o.getString("path"),
                    o.optInt("chars", 0), o.optString("sha", ""))
            }.filter { File(it.path).exists() }
            .let { books ->
                val pins = pinned(c)
                books.sortedByDescending { it.id in pins }
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun save(c: Context, books: List<Book>) {
        val a = JSONArray()
        books.forEach { b ->
            a.put(JSONObject().put("id", b.id).put("title", b.title)
                .put("path", b.path).put("chars", b.chars).put("sha", b.sha))
        }
        meta(c).writeText(a.toString())
    }

    private fun fingerprint(text: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Throwable) { "${text.length}_${text.hashCode()}" }

    fun add(c: Context, title: String, text: String): Book {
        if (text.length > MAX_TEXT_CHARS)
            throw IllegalStateException(
                "这本书有 ${text.length / 10000} 万字，超过 ${MAX_TEXT_CHARS / 10000} 万字上限；" +
                    "先拆成几本再导入（太长的书在手机上会内存不够）")
        val sha = fingerprint(text)
        val old = list(c).find {
            it.title == title && (it.sha == sha || (it.sha.isEmpty() && it.chars == text.length))
        }
        if (old != null) {
            EventLog.log(EventLog.Kind.MODEL, "BOOK_IMPORT_DUP", "$title 已在书架，不重复添加")
            return old
        }
        val id = UUID.randomUUID().toString()
        val f = File(dir(c), "$id.txt")
        f.writeText(text)
        val b = Book(id, title, f.absolutePath, text.length, sha)
        val all = list(c).toMutableList().also { it.add(0, b) }
        save(c, all)
        EventLog.log(EventLog.Kind.MODEL, "BOOK_ADD", title)
        return b
    }

    fun read(c: Context, b: Book): String = File(b.path).readText()

    fun delete(c: Context, id: String) {
        val all = list(c)
        all.find { it.id == id }?.let { File(it.path).delete() }
        save(c, all.filter { it.id != id })
        try { setPinned(c, id, false) } catch (_: Throwable) { }
        purge(c, id)
    }

    /**
     * R138 · 删书全量清理。1.1.2 只删原书，留下 work/ 分段 WAV、audio/ 成品与索引、
     * 任务/队列项、以及新的章节表 —— 占空间、索引里挂着不存在的书、
     * 队列要等 pumpQueue 撞上才发现书没了。现在一次清干净。
     */
    private fun purge(c: Context, id: String) {
        try { ChapterPlan.delete(c, id) } catch (_: Throwable) { }
        try {
            AudioLibrary.list(c).filter { it.bookId == id }
                .forEach { AudioLibrary.remove(c, it.id) }
        } catch (_: Throwable) { }
        try { File(c.filesDir, "work/$id").deleteRecursively() }
        catch (t: Throwable) {
            UiErrors.storage("删书时分段缓存没清干净，可稍后在设置页清理", t)
        }
        try { TaskResume.remove(c, id); TaskResume.dequeue(c, id) } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.MODEL, "BOOK_PURGE",
            "书已删：章节表 / 音频成品与索引 / 分段 WAV / 任务 / 队列一并清理")
    }
}

object ModelStore {
    const val TOKENIZER = "qwen-tokenizer-12hz-Q8_0.gguf"

    enum class Mode(
        val id: String, val label: String, val file: String,
        val key: String, val hint: String
    ) {
        VOICE_DESIGN("voice_design", "描述造音色",
            "qwen-talker-1.7b-voicedesign-Q8_0.gguf", "voicedesign",
            "用一段文字描述想要的嗓音，可叠加速度、情绪等可调项。"),
        CUSTOM_VOICE("custom_voice", "预设音色",
            "qwen-talker-1.7b-customvoice-Q8_0.gguf", "customvoice",
            "从模型自带的音色里挑一个，再给它一句念法要求。"),
        BASE("base", "参考音频",
            "qwen-talker-1.7b-base-Q8_0.gguf", "base",
            "给一小段人声做样本，照着这个声音来念；不再用文字描述。")
    }

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun files(c: Context): List<File> = try {
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    fun import(c: Context, name: String, source: java.io.InputStream,
               onProgress: (Long) -> Unit = {}): File {
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val tmp = File(dir(c), "$safe.part")
        val out = File(dir(c), safe)
        try {
            tmp.outputStream().buffered(64 * 1024).use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    total += n
                    if (total - lastReport >= 8L * 1024 * 1024) {
                        lastReport = total
                        onProgress(total)
                    }
                }
                o.flush()
                onProgress(total)
            }
            if (tmp.length() == 0L) throw IllegalStateException("选到的文件是空的")
            val bak = File(dir(c), "$safe.bak")
            if (bak.exists()) bak.delete()
            if (out.exists() && !out.renameTo(bak))
                throw IllegalStateException("旧文件挪不开，原文件留着")
            if (!tmp.renameTo(out)) {
                if (bak.exists()) bak.renameTo(out)
                throw IllegalStateException("文件没能保存，原文件已放回")
            }
            bak.delete()
            EventLog.log(EventLog.Kind.MODEL, "MODEL_IMPORT", "$safe ${out.length()}")
            return out
        } catch (t: Throwable) {
            tmp.delete()
            val reason = t.message ?: ""
            EventLog.log(EventLog.Kind.CRASH, "MODEL_IMPORT_FAIL",
                "$safe ${t.javaClass.simpleName}: $reason")
            throw t
        }
    }

    fun delete(c: Context, f: File) { f.delete(); EventLog.log(EventLog.Kind.MODEL, "MODEL_DELETE", f.name) }

    fun cleanup(c: Context) {
        try {
            dir(c).listFiles()?.filter { it.isFile &&
                (it.name.endsWith(".part") || it.name.endsWith(".bak")) }?.forEach {
                EventLog.log(EventLog.Kind.MODEL, "PART_DISCARD", "${it.name} ${it.length()}")
                it.delete()
            }
        } catch (_: Throwable) { }
    }

    private fun pick(c: Context, exact: String, vararg hints: String): File? {
        val all = files(c)
        all.find { it.name.equals(exact, true) }?.let { return it }
        return all.firstOrNull { f -> hints.any { f.name.contains(it, true) } }
    }

    private fun pickFor(c: Context, m: Mode): File? {
        val all = files(c)
        all.find { it.name.equals(m.file, true) }?.let { return it }
        return all.firstOrNull { it.name.contains("-" + m.key + "-", true) }
    }

    fun modeFile(c: Context, m: Mode) = pickFor(c, m)
    fun modeFiles(c: Context): Map<Mode, File?> = Mode.values().associateWith { pickFor(c, it) }
    fun installedModes(c: Context): List<Mode> = Mode.values().filter { pickFor(c, it) != null }
    fun currentMode(c: Context): Mode =
        Mode.values().firstOrNull { it.id == Prefs.modelMode(c) } ?: Mode.VOICE_DESIGN

    fun talker(c: Context): File? = pickFor(c, currentMode(c)) ?: pickFor(c, Mode.VOICE_DESIGN)
    fun tokenizer(c: Context) = pick(c, TOKENIZER, "tokenizer", "12hz")

    fun synthesisBlockReason(c: Context): String? {
        val m = currentMode(c)
        if (modeFile(c, m) == null)
            return "「${m.label}」的模式主文件还没导入。到「模型」页导入，或切回「描述造音色」。"
        return when (m) {
            Mode.VOICE_DESIGN -> null
            Mode.CUSTOM_VOICE -> {
                val picked = Prefs.speakerName(c).ifBlank {
                    Prefs.speakerNames(c).firstOrNull()?.also { Prefs.setSpeakerName(c, it) }.orEmpty()
                }
                if (picked.isBlank())
                    "先在调音台点「读取模型自带的音色表」挑一个音色，再合成。" else null
            }
            Mode.BASE ->
                if (!VoiceRef.exists(c)) "先在调音台导入一小段参考音频，再合成。" else null
        }
    }

    fun missing(c: Context): List<String> {
        val m = mutableListOf<String>()
        if (tokenizer(c) == null) m.add("共用底座（12Hz）")
        if (installedModes(c).isEmpty()) m.add("模式主文件")
        else if (modeFile(c, currentMode(c)) == null)
            m.add("「${currentMode(c).label}」的模式主文件")
        return m
    }
    fun ready(c: Context) = missing(c).isEmpty()
}

object ChapterSplitter {
    // R138 · 批次 B：TXT 分章委托给 TocRules 规则集自动选优；
    // EPUB 仍走 Importer.CHAPTER_SEP 结构化分隔，一个字不动。
    // 本对象只负责「编排」：候选 → Chapter 区间 → 切 Chunk。
    // 真正的单一真值源是 ChapterPlan（落盘、按书记住 ruleId），
    // UI 与制作运行都应调 ChapterPlan.resolve，而不是直接调 split。

    fun fromMarks(text: String, marks: List<Pair<Int, String>>): List<Chapter> {
        val safe = if (marks.isEmpty()) listOf(0 to "整本") else marks
        return safe.mapIndexed { i, m ->
            val e = if (i + 1 < safe.size) safe[i + 1].first else text.length
            Chapter(i, m.second, m.first, e)
        }
    }

    fun cutFrom(text: String, chapters: List<Chapter>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var idx = 0
        chapters.forEach { ch -> idx = cut(text, ch.start, ch.end, ch.index, out, idx) }
        return out
    }

    private fun cut(text: String, s: Int, e: Int, chIndex: Int,
                    out: MutableList<Chunk>, startIdx: Int): Int {
        var idx = startIdx
        var buf = StringBuilder()
        var bufStart = s
        var i = s
        val lo = s.coerceIn(0, text.length)
        val hi = e.coerceIn(lo, text.length)
        i = lo; bufStart = lo
        while (i < hi) {
            val ch0 = text[i]
            buf.append(ch0); i++
            val len = buf.length
            val boundary = ch0 == '\n' || ch0 == '。' || ch0 == '！' || ch0 == '？' ||
                    ch0 == '!' || ch0 == '?' || ch0 == '；' || ch0 == ';'
            if (len >= 500 || (len >= 350 && boundary)) {
                if (buf.isNotEmpty()) out.add(Chunk(idx++, chIndex, buf.toString(), bufStart, i))
                bufStart = i
                buf = StringBuilder()
            }
        }
        if (buf.isNotEmpty()) out.add(Chunk(idx++, chIndex, buf.toString(), bufStart, hi))
        return idx
    }

    fun split(text: String): Pair<List<Chapter>, List<Chunk>> {
        if (!text.contains(Importer.CHAPTER_SEP)) {
            val chapters = fromMarks(text, TocRules.choose(text).second)
            return chapters to cutFrom(text, chapters)
        }
        // ---- 以下 EPUB 分支原样保留 ----
        val chapters = mutableListOf<Chapter>()
        val chunks = mutableListOf<Chunk>()
        var idx = 0
        val segs = text.split(Importer.CHAPTER_SEP)
            .map { it.trim() }.filter { it.isNotBlank() }
        var cursor = 0
        segs.forEachIndexed { ci, seg ->
            val title = seg.lineSequence().firstOrNull()?.trim()?.take(40)
                ?.ifBlank { "第 ${ci + 1} 节" } ?: "第 ${ci + 1} 节"
            val start = text.indexOf(seg, cursor).let { if (it < 0) cursor else it }
            val end = start + seg.length
            chapters.add(Chapter(ci, title, start, end))
            idx = cut(text, start, end, ci, chunks, idx)
            cursor = end
        }
        return chapters to chunks
    }
}

object Annotator {
    fun directWithStats(text: String): Pair<List<Ann>, TextSplitter.Stats> {
        // R148 · 日志里段 1~4 把下载站页眉（书名/作者、URL、E-mail、
        // 「------章节内容开始」「请稍后重」）当正文念了出来。进切分前先整行清洗，
        // 统计基于清洗后的文本，逐字无损仍然成立；阅读正文与章节表不受影响。
        val (segs, st) = TextSplitter.split(SiteNoise.clean(text))
        return segs.map { Ann(it, "旁白", "平稳、清晰地叙述，句间自然停顿") } to st
    }

    fun directSegments(text: String): List<Ann> = directWithStats(text).first
}

object AnnCache {

    fun count(work: File): Int = try {
        work.listFiles()?.count { it.isFile && it.name.startsWith("ann_") && it.name.endsWith(".json") } ?: 0
    } catch (_: Throwable) { 0 }

    fun cleanup(work: File) {
        try { work.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        catch (_: Throwable) { }
    }

    fun clear(work: File) {
        try { work.listFiles()?.forEach { it.delete() } } catch (_: Throwable) { }
    }

    fun hasProgress(c: Context, bookId: String, only: Set<Int>?): Boolean {
        val w = File(c.filesDir, "work/${bookId}/${bucket(only)}")
        val ann = count(w)
        val wav = try {
            w.listFiles()?.count { it.isFile &&
                (it.name.startsWith("chunk_") || it.name.startsWith("direct_")) &&
                it.name.endsWith(".wav") && it.length() > 44 } ?: 0
        } catch (_: Throwable) { 0 }
        return ann > 0 || wav > 0
    }

    // R148 · 这是 1.1.3 日志里两个毛病的同一个根因。选中章一多，
    // joinToString("_") 会撑爆文件系统「单个目录名 255 字节」上限：
    // 103 章拼出 304 字符，mkdirs() 静默失败 → work 目录根本不存在 →
    //   · SegPlan.writeMap 写不进去（日志：制作记录写入失败：segmap.json）
    //   · work.usableSpace 返回 0 → minOf 取到 0 → 误判「存储不够」→ 全本直接被拒
    // 日志实证：103 章被拒（free=0），2 章正常。少章保持原命名以兼容已有目录，
    // 长名改成定长指纹，任意章数都安全，同一组章仍映射到同一目录（断点不丢）。
    private const val DIRECT_PREFIX = "v11/direct/"

    fun bucket(only: Set<Int>?): String {
        if (only == null) return DIRECT_PREFIX + "all"
        val raw = only.sorted().joinToString("_")
        if (raw.length <= 64) return DIRECT_PREFIX + raw
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        return DIRECT_PREFIX + "h" + hex.take(20)
    }

    fun dirFor(c: Context, bookId: String, only: Set<Int>?) =
        File(c.filesDir, "work/${bookId}/${bucket(only)}").apply { mkdirs() }
}

object WavMerger {
    private fun pcmOf(f: File): ByteArray? {
        return try {
            val b = f.readBytes()
            if (b.size < 44) null else {
                var i = 12
                var found: ByteArray? = null
                while (i + 8 <= b.size) {
                    val id = String(b, i, 4, Charsets.US_ASCII)
                    val sz = (b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or
                            ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24)
                    if (id == "data") {
                        found = b.copyOfRange(i + 8, minOf(i + 8 + sz, b.size))
                        break
                    }
                    i += 8 + sz + (if (sz % 2 == 1) 1 else 0)
                }
                found
            }
        } catch (_: Throwable) { null }
    }

    private fun header(dataLen: Int): ByteArray {
        val rate = 24000; val ch = 1; val bits = 16
        val byteRate = rate * ch * bits / 8
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, off) }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte()
            h[off + 2] = ((v shr 16) and 0xff).toByte(); h[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) { h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte() }
        putStr(0, "RIFF"); putInt(4, 36 + dataLen); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, ch)
        putInt(24, rate); putInt(28, byteRate); putShort(32, ch * bits / 8); putShort(34, bits)
        putStr(36, "data"); putInt(40, dataLen)
        return h
    }

    private fun declick(pcm: ByteArray, ms: Int): ByteArray {
        if (ms <= 0 || pcm.size < 4) return pcm
        val frames = (24000 * ms / 1000).coerceIn(1, pcm.size / 2)
        val out = pcm.copyOf()
        fun ramp(at: Int, gain: Float) {
            val p = at * 2
            if (p < 0 || p + 1 >= out.size) return
            val s = ((out[p].toInt() and 0xff) or
                ((out[p + 1].toInt() and 0xff) shl 8)).toShort().toInt()
            val v = (s * gain).toInt().coerceIn(-32768, 32767)
            out[p] = (v and 0xff).toByte()
            out[p + 1] = ((v shr 8) and 0xff).toByte()
        }
        for (j in 0 until frames) {
            val g = j.toFloat() / frames
            ramp(j, g)
            ramp(pcm.size / 2 - 1 - j, g)
        }
        return out
    }

    fun merge(parts: List<File>, out: File, gapMs: Int = 0, declickMs: Int = 30): Boolean {
        val part = File(out.parentFile, out.name + ".part")
        return try {
            val chunks = parts.mapNotNull { p -> pcmOf(p)?.let { declick(it, declickMs) } }
            if (chunks.isEmpty() || chunks.size != parts.size) { part.delete(); false } else {
                val gapBytes = (gapMs.coerceIn(0, 5000) * 48 / 2) * 2
                val silence = if (gapBytes > 0) ByteArray(gapBytes) else ByteArray(0)
                val total = chunks.sumOf { it.size } + silence.size * (chunks.size - 1).coerceAtLeast(0)
                java.io.FileOutputStream(part, false).use { os ->
                    os.write(header(total))
                    chunks.forEachIndexed { i, pcm ->
                        if (i > 0 && silence.isNotEmpty()) os.write(silence)
                        os.write(pcm)
                    }
                    os.flush()
                    os.fd.sync()
                }
                if (part.length() != 44L + total) { part.delete(); return false }
                if (out.exists() && !out.delete()) { part.delete(); return false }
                if (!part.renameTo(out)) { part.delete(); return false }
                EventLog.log(EventLog.Kind.PIPELINE, "MERGE_DONE",
                    "${parts.size} 块 / $total 字节 · 段间静音 ${gapMs}ms")
                true
            }
        } catch (_: Throwable) { part.delete(); false }
    }
}

data class Progress(
    val running: Boolean = false,
    val stage: String = "还没开始",
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val message: String = "",
    val output: String? = null,
    val chapterProgress: Map<Int, Float> = emptyMap()
)

object Producer {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state
    @Volatile private var busy = false
    @Volatile private var cancelRequested = false
    @Volatile private var queuePaused = false

    @Volatile private var appCtx: Context? = null
    @Volatile private var current: TaskInfo? = null
    private val _resume = MutableStateFlow<TaskInfo?>(null)
    val resume: StateFlow<TaskInfo?> = _resume

    fun reset() { if (!busy) _state.value = Progress() }
    fun isBusy() = busy

    // R139 · begin() 返回 false 时写在这里，autoResume 读它给 toast，不静默吞掉
    @Volatile var lastStartError: String? = null
        private set
    fun isStopping() = cancelRequested

    fun restore(c: Context) {
        appCtx = c.applicationContext
        val t = TaskResume.active(c) ?: return
        val fixed = if (t.phase == "running") t.copy(phase = "interrupted") else t
        TaskResume.save(c, fixed)
        current = fixed
        val real = TaskResume.realProgress(c, fixed)
        val why = TaskResume.invalidationReason(c, fixed)
        _resume.value = fixed.copy(done = real)
        _state.value = Progress(false, "上次中断", real, fixed.total, 0,
            if (why != null)
                "上次做到《${fixed.title}》。$why，分段文字还留着，点「继续制作」接着做。"
            else
                "上次做到《${fixed.title}》，已做好的块都留着。点「继续制作」从这接着跑，不用重算。")
        EventLog.log(EventLog.Kind.PIPELINE, "TASK_RESTORE",
            "book=${fixed.title} phase=${fixed.phase} real=$real/${fixed.total}" +
                (if (why != null) " invalidate=$why" else ""))
    }

    // 软件重新打开时调用：只有「进程被杀 / 崩溃」留下的 interrupted 任务自动接上。
    // 必须在 Activity 可见之后调，不能在 Application.onCreate 里，
    // 否则 Android 12+ 会拦后台启动前台服务。
    fun queueState(c: Context): Pair<Int, Boolean> =
        TaskResume.queueSize(c) to (queuePaused || TaskResume.queuePaused(c))

    // 目录页点「合成音频」：忙时不再拒绝，排进队列；空闲且队列为空时直接开跑。
    fun enqueueOrStart(c: Context, book: Book, only: Set<Int>?, think: Boolean?,
                       config: ProduceConfig): String {
        val t = TaskInfo(book.id, book.title, only, true, think ?: false,
            0, 0, "queued", "排队中", System.currentTimeMillis(),
            config = config, modeId = ModelStore.currentMode(c).id,
            splitRule = TaskResume.SPLIT_RULE)
        val paused = TaskResume.queuePaused(c)
        if (!busy && TaskResume.queue(c).isEmpty() && !paused) {
            start(c, book, only, true, think, config)
            return "开始制作《${book.title}》"
        }
        TaskResume.enqueue(c, t)
        if (!busy && !paused) {
            queuePaused = false
            TaskResume.setQueuePaused(c, false)
            scope.launch { pumpQueue(c.applicationContext) }
        }
        val n = TaskResume.queueSize(c)
        return "《${book.title}》已排到队列第 $n 个" +
            if (paused) "（队列已暂停，点「继续队列」开跑）" else "，做完当前这本自动接着跑"
    }

    private fun pumpQueue(c: Context): String? {
        if (busy || queuePaused || TaskResume.queuePaused(c)) return null
        while (true) {
            val t = TaskResume.queue(c).firstOrNull() ?: return null
            // R138 · 排队项可能是旧口径任务，先按章节表判定能否安全迁移
            if (t.splitRule < TaskResume.SPLIT_RULE) {
                ChapterPlan.resolve(c, BookStore.list(c).find { it.id == t.bookId }
                    ?: return null)
                val rid = ChapterPlan.currentRuleId(c, t.bookId)
                if (rid != TocRules.LEGACY && rid != ChapterPlan.EPUB) return "《${t.title}》分章规则变过，队列暂停，请先在目录页重新分章"
                TaskResume.migrateSplit(c, t.bookId)
            }
            val book = BookStore.list(c).find { it.id == t.bookId }
            if (book == null) {
                TaskResume.dequeue(c, t.bookId)
                EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_SKIP", "书没了 book=${t.title}")
                continue
            }
            val why = TaskResume.autoResumeBlockReason(c, t.copy(phase = "interrupted"))
            if (why != null) {
                queuePaused = true
                TaskResume.setQueuePaused(c, true)
                EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_PAUSE", "reason=$why")
                return "队列已暂停：$why"
            }
            TaskResume.dequeue(c, t.bookId)
            start(c, book, t.only, true, t.think, t.config)
            if (lastStartError != null) {
                // R139 · 前台服务没起来：把这一项放回队列并暂停，
                // 不能因为一次系统限制就把排队任务吞掉。
                TaskResume.enqueue(c, t)
                queuePaused = true
                appCtx?.let { TaskResume.setQueuePaused(it, true) }
                return lastStartError
            }
            return "队列接着做《${t.title}》 · 还剩 ${TaskResume.queueSize(c)} 个"
        }
    }

    fun resumeQueue(c: Context): String? {
        queuePaused = false
        TaskResume.setQueuePaused(c, false)
        EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_RESUME", "用户恢复队列")
        return pumpQueue(c.applicationContext)
    }

    // G1 · 开软件先弹窗问，不再闷头自动跑。纯查询，不启动任何东西：
    // 只认「真的能自动续跑」的任务（崩溃中断 / 队列里待做），
    // 用户手动停的、失败的、模型没齐的、切章口径变过的一律不弹，
    // 沿用 autoResumeBlockReason 那套守卫，避免出现「我明明停了还问我续不续」。
    fun hasResumable(c: Context): TaskInfo? {
        if (busy) return null
        if (TaskResume.queuePaused(c)) { queuePaused = true; return null }
        val q = TaskResume.queue(c).firstOrNull()
        if (q != null) return q
        val t = TaskResume.active(c) ?: return null
        if (t.phase == "done") return null
        if (TaskResume.autoResumeBlockReason(c, t) != null) return null
        if (BookStore.list(c).none { it.id == t.bookId }) return null
        return t
    }

    // 软件重新打开时调用：队列优先（用户要「队列里有任务就自动开始跑」），
    // 没有排队任务才处理崩溃中断的单本续跑。
    // G1 之后只在弹窗里用户点「继续制作」时才被调，不再由 ON_RESUME 直接触发。
    fun autoResume(c: Context): String? {
        lastStartError = null
        if (busy) return null
        if (TaskResume.queuePaused(c)) queuePaused = true
        else {
            val q = pumpQueue(c.applicationContext)
            if (lastStartError != null) return lastStartError
            if (q != null) return q
        }
        val t = TaskResume.active(c) ?: return null
        if (t.phase == "done") return null
        val why = TaskResume.autoResumeBlockReason(c, t)
        if (why != null) {
            EventLog.log(EventLog.Kind.PIPELINE, "TASK_AUTO_RESUME_SKIP",
                "book=${t.title} phase=${t.phase} reason=$why")
            return why
        }
        val book = BookStore.list(c).find { it.id == t.bookId }
            ?: return "这本书已从书架删掉，没法续跑"
        start(c, book, t.only, true, t.think, t.config)
        if (lastStartError != null) return lastStartError
        EventLog.log(EventLog.Kind.PIPELINE, "TASK_AUTO_RESUME",
            "book=${t.title} phase=${t.phase} done=${t.done}/${t.total}")
        return "已自动接着上次的《${t.title}》继续制作"
    }

    fun stop() {
        if (!busy) return
        // 用户点停止 = 暂停整个队列，不是只停当前这一本，
        // 否则停完 finally 又自动接下一个，等于没停。
        queuePaused = true
        appCtx?.let { TaskResume.setQueuePaused(it, true) }
        cancelRequested = true
        // R148 · 1.1.3 日志：点停止后 79 秒才 FGS_FINISH，用户以为「按了没反应」。
        // 取消只在段间检查，而一段推理要 1~3 分钟。先立刻把状态改成已停止，
        // 当前那段跑完直接丢掉（不写盘、不计入进度），不再让用户干等。
        _state.value = Progress(false, "已停止", _state.value.done, _state.value.total,
            _state.value.failed, "已停止，正在收尾当前这一小段…已做好的都留着")
        try { NativeEngine.nativeCancel() } catch (_: Throwable) { }
        appCtx?.let { c ->
            current?.let { t ->
                val stopped = t.copy(phase = "stopped",
                    stage = _state.value.stage, done = _state.value.done,
                    updatedAt = System.currentTimeMillis())
                // 同步内存态：否则 finally 会按 running 再标成 interrupted，
                // 下次开软件就被当成崩溃中断而自动续跑，违背用户主动停止的意图。
                current = stopped
                TaskResume.save(c, stopped)
            }
            ProduceService.stopping(c)
        }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_STOP", "用户点了停止")
    }

    private fun halted(done: Int, total: Int, failed: Int): Progress = Progress(
        false, "已停止", done, total, failed,
        "已停在 $done/$total 块，已做好的都留着。点「继续制作」从这接着跑。")

    fun hasProgress(c: Context, bookId: String, only: Set<Int>?) =
        AnnCache.hasProgress(c, bookId, only)

    fun clearProgress(c: Context, bookId: String, only: Set<Int>?) {
        AnnCache.clear(AnnCache.dirFor(c, bookId, only))
        val saved = TaskResume.byBook(c, bookId)
        if (saved != null || current?.bookId == bookId) {
            current = null; _resume.value = null
        }
        TaskResume.remove(c, bookId)
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_RESET", "清空这本书的进度，从头重做")
    }

    fun start(c: Context, book: Book, onlyChapters: Set<Int>? = null,
              directRead: Boolean = false, thinkOverride: Boolean? = null,
              config: ProduceConfig = ProduceConfig()) {
        if (busy) return
        busy = true
        cancelRequested = false
        appCtx = c.applicationContext
        val thinkOn = thinkOverride ?: false
        current = TaskInfo(book.id, book.title, onlyChapters, true, thinkOn,
            0, 0, "running", "正在准备", System.currentTimeMillis(),
            config = config, modeId = ModelStore.currentMode(c).id,
            splitRule = TaskResume.SPLIT_RULE)
        appCtx?.let { TaskResume.save(it, current!!) }
        _resume.value = current
        _state.value = Progress(true, "正在准备", 0, 0, 0, "正在启动…")
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        AnnCache.cleanup(AnnCache.dirFor(c, book.id, onlyChapters))
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_MODE",
            "直接朗读" +
                " · think=" + thinkOn +
                " · " + (onlyChapters?.let { "选中 ${it.size} 章" } ?: "整本") +
                " · 本次预设=" + config.summary())
        ProduceService.activityOf(c)?.let { act ->
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    act.requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001)
                    EventLog.log(EventLog.Kind.PIPELINE, "FGS_NOTIF_PERMISSION",
                        "已向用户申请 POST_NOTIFICATIONS（拒绝不影响制作）")
                }
            }
        }
        // R139 · 前台服务起不来就别开协程：没有 FGS 的进程随时被杀，
        // 断点会留在半途，界面却一直显示「正在制作」。停在「没开始」并说清原因。
        if (!ProduceService.begin(c, book.title)) {
            val why = "系统没让制作服务启动，任务没有开始；回到软件前台再点一次开始"
            lastStartError = why
            busy = false
            cancelRequested = false
            _state.value = Progress(false, "没开始", 0, 0, 0, why)
            val t = current
            if (t != null) {
                val stopped = t.copy(phase = "stopped", stage = "没开始")
                current = stopped
                appCtx?.let { TaskResume.save(it, stopped) }
                _resume.value = stopped
            }
            EventLog.log(EventLog.Kind.CRASH, "PRODUCE_FGS_BLOCKED", why)
            return
        }
        lastStartError = null
        scope.launch {
            try {
                run(c, book, onlyChapters, true, thinkOverride, config)
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    EventLog.log(EventLog.Kind.CRASH, "PRODUCE_EXCEPTION", t.message ?: "")
                    _state.value = Progress(false, "出错了", 0, 0, 0, "制作中断：" + (t.message ?: ""))
                    markInterrupted("出错中断")
                }
            } finally {
                busy = false
                if (current?.phase == "running") {
                    val s = _state.value.stage
                    markInterrupted(if (s in listOf("没完成", "出错了", "已停止")) s else "中断")
                }
                val p = _state.value
                ProduceService.finish(c.applicationContext, p.stage, p.done, p.total)
                // 只有真正「完成」才自动接下一个；停止 / 报错 / 存储不足一律停在原地，
                // 队列保留但暂停，不自动跳过、不无限重试。
                if (p.stage == "完成" || current?.phase == "done") {
                    if (!queuePaused && !TaskResume.queuePaused(c.applicationContext))
                        scope.launch { pumpQueue(c.applicationContext) }
                } else {
                    queuePaused = true
                    TaskResume.setQueuePaused(c.applicationContext, true)
                    EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_PAUSE",
                        "stage=${p.stage} 没正常完成，队列停在原地")
                }
            }
        }
    }

    private fun markInterrupted(stage: String) {
        val c = appCtx ?: return
        val t = current ?: return
        val fixed = t.copy(phase = "interrupted", stage = stage,
            done = _state.value.done, total = _state.value.total.coerceAtLeast(t.total),
            updatedAt = System.currentTimeMillis())
        current = fixed
        TaskResume.save(c, fixed)
        _resume.value = fixed.copy(done = TaskResume.realProgress(c, fixed))
    }

    private fun emit(stage: String, done: Int, total: Int, failed: Int, msg: String,
                     out: String? = null, chapterProgress: Map<Int, Float> = emptyMap()) {
        _state.value = Progress(true, stage, done, total, failed, msg, out, chapterProgress)
        val c = appCtx
        val t = current
        if (c != null && t != null && t.phase == "running") {
            val up = t.copy(total = total, done = done, stage = stage,
                updatedAt = System.currentTimeMillis())
            current = up
            TaskResume.save(c, up)
        }
        appCtx?.let { ctx ->
            ProduceService.update(ctx.applicationContext, stage, done, total, msg)
        }
    }

    private fun run(c: Context, book: Book, only: Set<Int>?, direct: Boolean,
                    thinkOverride: Boolean? = null, config: ProduceConfig = ProduceConfig()) {
        if (!NativeEngine.isAvailable()) {
            _state.value = Progress(false, "没开始", 0, 0, 0, NativeEngine.shortReason())
            return
        }
        ModelStore.synthesisBlockReason(c)?.let { reason ->
            _state.value = Progress(false, "没开始", 0, 0, 0, reason)
            return
        }
        val tk = ModelStore.modeFile(c, ModelStore.currentMode(c))
        val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("模式主文件")
        if (tok == null) miss.add("共用底座（12Hz）")
        if (miss.isNotEmpty()) {
            _state.value = Progress(false, "没开始", 0, 0, 0,
                "还差：" + miss.joinToString("、") + "。去模型页导入。")
            return
        }

        emit("正在分章", 0, 0, 0, "正在把这本书切成能读的小块…")
        val text = BookStore.read(c, book)
        // R138 · 制作运行也用落盘章节表，保证与目录页、断点校验同一套章号
        val (chapters, allChunks) = ChapterPlan.resolve(c, book, text)
        val chunks = if (only == null) allChunks else allChunks.filter { it.chapter in only }
        if (chunks.isEmpty()) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "这本书没有可制作的文字")
            return
        }
        val thinkOn = thinkOverride ?: false
        val work = AnnCache.dirFor(c, book.id, only)
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_CONFIG",
            "chunks=${chunks.size} · 预设=" + config.summary())
        EventLog.resetEngine()

        val gateFails = 0
        val stats = mutableListOf<TextSplitter.Stats>()
        val segChapter = mutableListOf<Int>()
        val annotated = chunks.flatMap { ch ->
            val (segs, s) = Annotator.directWithStats(ch.text)
            stats.add(s)
            repeat(segs.size) { segChapter.add(ch.chapter) }
            segs
        }
        EventLog.log(EventLog.Kind.PIPELINE, "SPLIT_STATS",
            "${chunks.size} 块 → ${annotated.size} 段 · 只在标点/段落边界断 · " +
                "字符硬切 0 · 句段边界 ${stats.sumOf { it.sentenceCuts }} · " +
                "次级边界 ${stats.sumOf { it.softCuts }} · " +
                "超长整句保留 ${stats.sumOf { it.oversize }} · " +
                "最长 ${stats.maxOfOrNull { it.longest } ?: 0} 字 · " +
                "原文 ${stats.sumOf { it.charsIn }} 字 拼回 ${stats.sumOf { it.charsOut }} 字" +
                (if (stats.sumOf { it.charsIn } == stats.sumOf { it.charsOut }) " · 逐字无损" else " · ⚠️字数不一致"),
            fields = mapOf("chunks" to chunks.size.toString(), "segments" to annotated.size.toString(),
                "hardCuts" to "0", "lossless" to
                    (stats.sumOf { it.charsIn } == stats.sumOf { it.charsOut }).toString()))
        EventLog.log(EventLog.Kind.PIPELINE, "DIRECT_READ",
            "${chunks.size} 块跳过分段，切成 ${annotated.size} 段直接朗读")
        try {
            val sc = JSONArray()
            segChapter.forEach { sc.put(it) }
            AtomicJson.write(File(work, "seg_chapters.json"), sc.toString())
            val ct = JSONArray()
            chapters.forEach { ch ->
                ct.put(JSONObject().put("index", ch.index).put("title", ch.title))
            }
            AtomicJson.write(File(work, "chapters.json"), ct.toString())
            // R140 · 批次 D：启用 segmap。之前 writeMap 全工程零调用、readMap 恒 null，
            // 看台 / 剩余段数 / verdict 全都走「按已落盘最大序号 + total」的兜底推算。
            // 现在把本次计划段一次性落盘，UI 拿到的是真值而不是猜的。
            // wavName 与 Producer 合成循环里的命名严格一致（direct_%04d.wav）。
            SegPlan.writeMap(work, annotated.mapIndexed { i, a ->
                SegMeta(i, i, 0, null, a.text.take(200), a.speaker, a.instruct,
                    String.format("direct_%04d.wav", i))
            })
            SegPlan.lastWriteError?.let { UiErrors.storage(it, null) }
        } catch (t: Throwable) {
            UiErrors.storage("分段计划没写进磁盘，看台会改用兜底推算", t)
        }
        emit("正在生成声音", 0, annotated.size, gateFails, "音频模型正在把文字读出来…")

        val knobs = Prefs.ttsKnobs(c)
        val globalVoice = Prefs.voiceDescription(c)
        val voiceStamp = Prefs.voiceStamp(c)
        val legacyStamp = Prefs.legacyVoiceStamp(c)
        val stampFile = File(work, "voice_stamp.txt")
        try {
            val old = if (stampFile.exists()) stampFile.readText().trim() else ""
            val changed = stampFile.exists() && old != voiceStamp && old != legacyStamp
            if (changed) {
                val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()
                killed.forEach { it.delete() }
                AudioTree.forget(c, killed.filter { !it.isFile }.map { it.absolutePath })
                EventLog.log(EventLog.Kind.PIPELINE, "VOICE_INVALIDATE",
                    "声音配置变了，作废 ${killed.size} 段旧音频，分段保留")
            }
            if (stampFile.exists() && old != voiceStamp && !changed) {
                EventLog.log(EventLog.Kind.PIPELINE, "VOICE_STAMP_MIGRATE",
                    "旧指纹迁移到新版，已合成音频保持不变")
            }
            stampFile.writeText(voiceStamp)
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_MODE",
            "${SynthInput.label(c)} · 指纹=$voiceStamp · seed=${knobs.seed}" +
                " · temp=${knobs.temperature} · lang=chinese（官方 README：目标语言已知时显式传，别用 auto）")

        val modeStamp = TaskResume.expectedModeStamp(c)
        val modeStampFile = File(work, "mode_stamp.txt")
        try {
            val oldMode = if (modeStampFile.exists()) modeStampFile.readText().trim() else ""
            if (modeStampFile.exists() && oldMode != modeStamp) {
                val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()
                killed.forEach { it.delete() }
                AudioTree.forget(c, killed.filter { !it.isFile }.map { it.absolutePath })
                EventLog.log(EventLog.Kind.PIPELINE, "MODE_INVALIDATE",
                    "模式输入变了（$oldMode → $modeStamp），作废 ${killed.size} 段旧音频，分段保留")
            }
            modeStampFile.writeText(modeStamp)
        } catch (_: Throwable) { }

        val presetFile = File(work, "produce_stamp.txt")
        val presetStamp = config.audioStamp()
        try {
            val oldWavs = work.listFiles()?.count { f ->
                f.isFile && f.name.endsWith(".wav") && f.length() > 44
            } ?: 0
            val changed = presetFile.exists() && presetFile.readText().trim() != presetStamp
            if (changed || (!presetFile.exists() && oldWavs > 0)) {
                val killed = work.listFiles()?.filter { f ->
                    f.isFile && f.name.endsWith(".wav")
                } ?: emptyList()
                killed.forEach { it.delete() }
                AudioTree.forget(c, killed.filter { !it.isFile }.map { it.absolutePath })
                EventLog.log(EventLog.Kind.PIPELINE, "PRESET_INVALIDATE",
                    "本次预设变了（$presetStamp），作废 ${killed.size} 段旧音频，分段保留")
            }
            presetFile.writeText(presetStamp)
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.PIPELINE, "PRESET_APPLIED",
            config.summary() + " · 指纹=" + presetStamp)

        val ttsHandle = try {
            NativeEngine.ttsLoadGated(tk!!.absolutePath, tok!!.absolutePath, null)
        } catch (t: Throwable) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "音频模型加载失败：" + (t.message ?: ""))
            return
        }
        if (ttsHandle == 0L) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "音频模型打不开，请确认两个文件都导入了")
            return
        }
        try {
            SynthInput.prepare(c, ttsHandle)
        } catch (t: Throwable) {
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
            _state.value = Progress(false, "没开始", 0, 0, 0, t.message ?: "模式输入没准备好")
            return
        }

        val nonVerbalOn = Prefs.nonVerbal(c)
        var strippedTotal = 0
        val toSpeak = annotated.map { ann ->
            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann
            val built = VoiceProfile.buildWithReport(globalVoice, config.styleInstruct(), base.instruct)
            strippedTotal += built.second
            Ann(base.text, base.speaker, built.first)
        }
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_IDENTITY",
            "身份层=${if (globalVoice.isBlank()) "默认锚点" else "自定义"} · " +
                    "版本=$INSTRUCT_TEMPLATE_VERSION · " +
                    "seed=${knobs.seed} · ${annotated.size} 段")
        if (strippedTotal > 0) EventLog.log(EventLog.Kind.PIPELINE, "INSTRUCT_SANITIZED",
            "从 $strippedTotal 处导演指令里剥掉音色身份词；身份由固定层提供，不再逐段重造")
        if (nonVerbalOn) {
            val hits = NonVerbal.count(annotated)
            EventLog.log(EventLog.Kind.PIPELINE, "NONVERBAL",
                "命中 $hits/${annotated.size} 段，只增强导演指令，正文未改")
        }

        val doneChapterFiles = try { AudioTree.readDone(work) } catch (_: Throwable) { emptyMap() }
        val usedDoneChapters = mutableSetOf<Int>()
        val wavs = mutableListOf<File>()
        var synthFails = 0
        val gapBytes = ((config.gapMs.coerceIn(0, 5000) * 48) / 2) * 2
        val segStartBytes = mutableListOf<Long>()
        var cursorBytes = 0L
        val perChapterTotal = segChapter.groupingBy { it }.eachCount()
        val perChapterDone = mutableMapOf<Int, Int>()
        val pending = (0 until toSpeak.size).count { i ->
            val f = File(work, String.format("direct_%04d.wav", i))
            if (f.exists() && f.length() > 44) return@count false
            val ci = segChapter.getOrElse(i) { -1 }
            val covered = ci >= 0 && doneChapterFiles[ci]
                ?.let { it.isFile && it.length() > 44 } == true
            !covered
        }
        val sample = (0 until toSpeak.size).mapNotNull { i ->
            File(work, String.format("direct_%04d.wav", i))
                .takeIf { it.exists() && it.length() > 44 }?.length()
        }
        // R148 · 三处修正（1.1.3 日志实证：free=0 误判，103 章全本被直接拒掉）
        //  ① 可用空间改读 filesDir：诊断日志用它读到 273GB；work 子目录若
        //     mkdirs 失败（见 bucket 修复），usableSpace 会返回 0
        //  ② 单段兜底从「60 秒」改成「12 秒」，且不再「分段 + 合并」双算；
        //     原算法 9137 段估出 61GB，实际远不需要
        //  ③ 真不够也不一刀切拒绝：算出预算段数，做到哪算哪，停时明确说
        //     「已做 N 段，清出空间后继续」，已生成的段全留着
        val segBytes = if (sample.isNotEmpty()) sample.average().toLong()
                       else 24_000L * 2L * 12L
        val freeBytes = try { c.filesDir.usableSpace } catch (_: Throwable) { 0L }
        val headroom = 16L * 1024 * 1024
        // 查不到可用空间就放行，绝不因为「查不到」拒绝制作
        val budgetBytes = if (freeBytes > 0L) maxOf(0L, freeBytes - headroom)
                          else Long.MAX_VALUE
        val estimateBytes = pending.toLong() * segBytes + headroom
        val budgetSegs = if (budgetBytes == Long.MAX_VALUE) Int.MAX_VALUE
                         else (budgetBytes / maxOf(1L, segBytes)).toInt()
        if (estimateBytes > budgetBytes) {
            EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_PARTIAL",
                "空间可能不够，按预算先做 · free=$freeBytes need≈$estimateBytes " +
                    "budgetSegs=$budgetSegs pending=$pending seg=$segBytes")
        }
        var spaceStopped = false
        try {
            for ((i, a) in toSpeak.withIndex()) {
                if (wavs.size >= budgetSegs) { spaceStopped = true; break }
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val segStart = wavs.size
                ProduceService.touch(c.applicationContext, "合成段 ${i + 1}/${toSpeak.size}")
                val wav = File(work, String.format("direct_%04d.wav", i))
                val ci = segChapter.getOrElse(i) { -1 }
                val doneWav = if (ci >= 0) doneChapterFiles[ci] else null
                val covered = doneWav != null && doneWav.isFile && doneWav.length() > 44 &&
                    !(wav.exists() && wav.length() > 44)
                val v = SynthInput.params(c, a.instruct, config.styleInstruct())
                EventLog.log(EventLog.Kind.ENGINE, "TTS_INPUT",
                    "段 ${i + 1}/${toSpeak.size} [${a.speaker}] " +
                        a.text.take(50).replace('\n', '⏎') + " → " + (v.instruct ?: "（无指令）"),
                    level = EventLog.Level.TRACE,
                    fields = mapOf("seg" to (i + 1).toString(), "speaker" to a.speaker,
                        "textChars" to a.text.length.toString(), "instructChars" to (v.instruct?.length ?: 0).toString(),
                        "lang" to "chinese", "mode" to ModelStore.currentMode(c).id,
                        "speakerParam" to (v.speaker ?: "null")))
                if (covered || (wav.exists() && wav.length() > 44)) {
                    if (covered) {
                        if (usedDoneChapters.add(ci)) {
                            wavs.add(doneWav!!)
                            segStartBytes.add(cursorBytes)
                            cursorBytes += (doneWav.length() - 44) + gapBytes
                        } else {
                            segStartBytes.add(cursorBytes)
                        }
                    } else {
                        wavs.add(wav)
                        segStartBytes.add(cursorBytes)
                        cursorBytes += (wav.length() - 44) + gapBytes
                    }
                    SegPlan.clearRedo(work, "seg:$i")
                } else {
                    var ok = false
                    var lastCode = -1
                    for (attempt in 0..1) {
                        if (cancelRequested) {
                            _state.value = halted(i, annotated.size, gateFails + synthFails)
                            return
                        }
                        val code = try {
                            NativeEngine.ttsSynthesize(ttsHandle, a.text, v.instruct, v.speaker, "chinese",
                                wav.absolutePath, knobs)
                        } catch (t: Throwable) {
                            EventLog.log(EventLog.Kind.ENGINE, "TTS_ERROR", t.message ?: "")
                            -99
                        }
                        lastCode = code
                        if (code == 0 && wav.exists() && wav.length() > 44) { ok = true; break }
                        wav.delete()
                    }
                    if (!ok) {
                        synthFails++
                        _state.value = Progress(false, "没完成", i, annotated.size,
                            gateFails + synthFails,
                            "第 ${i + 1} 段没生成声音（引擎返回 $lastCode）；前面 ${wavs.size} 段都留着，" +
                                "点「继续制作」从这一段接着做，不会重算切分")
                        EventLog.log(EventLog.Kind.PIPELINE, "TTS_SEG_ABORT",
                            "seg=${i + 1} code=$lastCode kept=${wavs.size}")
                        return
                    }
                    if (cancelRequested) {
                        // 停止发生在这段推理途中：这段结果不要了，直接收尾
                        try { wav.delete() } catch (_: Throwable) { }
                        _state.value = halted(wavs.size, annotated.size, gateFails + synthFails)
                        return
                    }
                    wavs.add(wav)
                    SegPlan.clearRedo(work, "seg:$i")
                    segStartBytes.add(cursorBytes)
                    cursorBytes += (wav.length() - 44) + gapBytes
                }
                val chapterSnapshot = if (wavs.size > segStart) {
                    val ci = segChapter.getOrElse(i) { -1 }
                    if (ci >= 0) perChapterDone[ci] = (perChapterDone[ci] ?: 0) + 1
                    perChapterDone.mapValues { (k, v) ->
                        (v.toFloat() / (perChapterTotal[k] ?: 1).coerceAtLeast(1).toFloat())
                            .coerceIn(0f, 1f)
                    }
                } else _state.value.chapterProgress
                emit("正在生成声音", i + 1, annotated.size, gateFails + synthFails,
                    "已生成 ${i + 1}/${annotated.size} 段声音",
                    chapterProgress = chapterSnapshot)
            }
            if (spaceStopped) {
                _state.value = Progress(false, "没完成", wavs.size, annotated.size,
                    gateFails + synthFails,
                    "空间用完了，已做好 ${wavs.size} 段并都留着；清出空间后点「继续制作」从这接着做")
                EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_PARTIAL_STOP",
                    "kept=${wavs.size} total=${annotated.size} free=$freeBytes")
                return
            }
        } finally {
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
        }
        if (cancelRequested) {
            _state.value = halted(wavs.size, annotated.size, gateFails + synthFails)
            return
        }

        emit("正在整理音频", annotated.size, annotated.size, gateFails + synthFails, "正在把各段接成一个音频…")
        val outFile = File(AudioLibrary.dir(c), "${UUID.randomUUID()}.wav")
        val finalBytes = wavs.sumOf { it.length() } + 16L * 1024 * 1024
        if (finalBytes > outFile.parentFile!!.usableSpace) {
            _state.value = Progress(false, "没完成", annotated.size, annotated.size,
                gateFails + synthFails,
                "存储空间不够，音频接不起来；已生成的 ${wavs.size} 段都留着，" +
                    "清出空间后点「继续制作」")
            EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_SHORT_MERGE",
                "need=$finalBytes free=${outFile.parentFile!!.usableSpace} parts=${wavs.size}")
            return
        }
        val ok = WavMerger.merge(wavs, outFile, config.gapMs)
        if (!ok || outFile.length() <= 44) {
            outFile.delete()
            File(outFile.parentFile, outFile.name + ".part").delete()
            _state.value = Progress(false, "没完成", annotated.size, annotated.size,
                gateFails + synthFails, "没能生成音频，可以回书架重新合成这一本")
            return
        }
        val item = AudioLibrary.register(c, book.id, book.title, only, direct, outFile, chapters.size)
        val regWarn = AudioLibrary.writeError
        EventLog.log(EventLog.Kind.PIPELINE, "AUDIO_REGISTER",
            "${item.title} · ${item.scope} · ${item.mode} · ${item.bytes} 字节")
        try {
            val chJson = JSONArray()
            val firstOffset = LinkedHashMap<Int, Long>()
            segStartBytes.forEachIndexed { si, off ->
                val ci = segChapter.getOrElse(si) { -1 }
                if (ci >= 0 && !firstOffset.containsKey(ci)) firstOffset[ci] = off / 48L
            }
            chapters.forEach { ch ->
                chJson.put(JSONObject().put("index", ch.index).put("title", ch.title)
                    .put("startMs", firstOffset[ch.index] ?: 0L))
            }
            AtomicJson.write(File(outFile.parentFile,
                "${outFile.nameWithoutExtension}.chapters.json"), chJson.toString())
        } catch (_: Throwable) { }

        _state.value = Progress(false, "完成", annotated.size, annotated.size,
            gateFails + synthFails,
            if (regWarn != null) "音频已生成，但记录没写上：$regWarn；文件仍在「我的音频」里"
            else "做好了，可以去「我的音频」听", outFile.absolutePath)
        val c = appCtx
        val t = current
        if (c != null && t != null) {
            val done = t.copy(phase = "done", stage = "完成",
                total = annotated.size, done = annotated.size,
                updatedAt = System.currentTimeMillis(), output = outFile.absolutePath)
            current = done
            TaskResume.save(c, done)
            _resume.value = done
        }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_DONE", "${outFile.length()} 字节")
    }
}
