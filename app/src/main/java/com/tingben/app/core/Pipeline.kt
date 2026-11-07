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
    private fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    private fun meta(c: Context) = File(dir(c), "books.json")

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

    /**
     */
    fun add(c: Context, title: String, text: String): Book {
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
    }
}


object ModelStore {
    /**
     */
    const val TOKENIZER = "qwen-tokenizer-12hz-Q8_0.gguf"

    /**
     */
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

    /**
     */
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
            if (out.exists() && !out.delete()) throw IllegalStateException("旧文件删不掉")
            if (!tmp.renameTo(out)) throw IllegalStateException("文件没能保存")
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
            dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach {
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

    /**
     */
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

    /**
     */
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
    private val headRe = Regex(
        """^\s*(第[一二三四五六七八九十百千零〇0-9]+[章节回卷篇].{0,40}|""" +
        """[0-9]{1,3}[、.．]\s*.{1,40}|""" +
        """(?:楔子|序章|序言|引子|尾声|后记|番外|楔|序|跋)(?:.{0,30})|""" +
        """(?:Chapter|CHAPTER|PART|Part)\s*[0-9一二三四五六七八九十]{1,4}(?:.{0,30}))\s*$""",
        RegexOption.MULTILINE
    )

    private fun cut(text: String, s: Int, e: Int, chIndex: Int,
                    out: MutableList<Chunk>, startIdx: Int): Int {
        var idx = startIdx
        var buf = StringBuilder()
        var bufStart = s
        var i = s
        while (i < e) {
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
        if (buf.isNotEmpty()) out.add(Chunk(idx++, chIndex, buf.toString(), bufStart, e))
        return idx
    }

    fun split(text: String): Pair<List<Chapter>, List<Chunk>> {
        val chapters = mutableListOf<Chapter>()
        val chunks = mutableListOf<Chunk>()
        var idx = 0

        if (text.contains(Importer.CHAPTER_SEP)) {
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

        val marks = mutableListOf<Pair<Int, String>>()
        headRe.findAll(text).forEach { marks.add(it.range.first to it.value.trim()) }
        if (marks.isEmpty()) marks.add(0 to "整本")
        for (i in marks.indices) {
            val s = marks[i].first
            val e = if (i + 1 < marks.size) marks[i + 1].first else text.length
            chapters.add(Chapter(i, marks[i].second, s, e))
            idx = cut(text, s, e, i, chunks, idx)
        }
        return chapters to chunks
    }
}


object Annotator {
    /**
     */
    fun directWithStats(text: String): Pair<List<Ann>, TextSplitter.Stats> {
        val (segs, st) = TextSplitter.split(text)
        return segs.map { Ann(it, "旁白", "平稳、清晰地叙述，句间自然停顿") } to st
    }

    fun directSegments(text: String): List<Ann> = directWithStats(text).first
}

/**
 */
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

    fun hasProgress(c: Context, bookId: String, direct: Boolean, think: Boolean, only: Set<Int>?): Boolean {
        val w = File(c.filesDir, "work/${bookId}/${bucket(direct, think, only)}")
        val ann = count(w)
        val wav = try {
            w.listFiles()?.count { it.isFile &&
                (it.name.startsWith("chunk_") || it.name.startsWith("direct_")) &&
                it.name.endsWith(".wav") && it.length() > 44 } ?: 0
        } catch (_: Throwable) { 0 }
        return ann > 0 || wav > 0
    }

    fun bucket(direct: Boolean, think: Boolean, only: Set<Int>?) =
        "v11/" + (if (direct) "direct" else "annotated") + "/" +
                (only?.sorted()?.joinToString("_") ?: "all")

    fun dirFor(c: Context, bookId: String, direct: Boolean, think: Boolean, only: Set<Int>?) =
        File(c.filesDir, "work/${bookId}/${bucket(direct, think, only)}").apply { mkdirs() }
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

    /**
     */
    fun merge(parts: List<File>, out: File, gapMs: Int = 0): Boolean {
        return try {
            val chunks = parts.mapNotNull { pcmOf(it) }
            if (chunks.isEmpty()) false else {
                val gapBytes = (gapMs.coerceIn(0, 5000) * 48 / 2) * 2
                val silence = if (gapBytes > 0) ByteArray(gapBytes) else ByteArray(0)
                val total = chunks.sumOf { it.size } + silence.size * (chunks.size - 1).coerceAtLeast(0)
                out.outputStream().use { os ->
                    os.write(header(total))
                    chunks.forEachIndexed { i, pcm ->
                        if (i > 0 && silence.isNotEmpty()) os.write(silence)
                        os.write(pcm)
                    }
                }
                EventLog.log(EventLog.Kind.PIPELINE, "MERGE_DONE",
                    "${parts.size} 块 / $total 字节 · 段间静音 ${gapMs}ms")
                true
            }
        } catch (_: Throwable) { false }
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

    @Volatile private var appCtx: Context? = null
    @Volatile private var current: TaskInfo? = null
    private val _resume = MutableStateFlow<TaskInfo?>(null)
    val resume: StateFlow<TaskInfo?> = _resume

    fun reset() { if (!busy) _state.value = Progress() }
    fun isBusy() = busy
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

    fun stop() {
        if (!busy) return
        cancelRequested = true
        try { NativeEngine.nativeCancel() } catch (_: Throwable) { }
        appCtx?.let { c ->
            current?.let { TaskResume.save(c, it.copy(phase = "stopped",
                stage = _state.value.stage, done = _state.value.done,
                updatedAt = System.currentTimeMillis())) }
            ProduceService.stopping(c)
        }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_STOP", "用户点了停止")
    }

    private fun halted(done: Int, total: Int, failed: Int): Progress = Progress(
        false, "已停止", done, total, failed,
        "已停在 $done/$total 块，已做好的都留着。点「继续制作」从这接着跑。")

    fun hasProgress(c: Context, bookId: String, direct: Boolean, think: Boolean, only: Set<Int>?) =
        AnnCache.hasProgress(c, bookId, direct, think, only)

    fun clearProgress(c: Context, bookId: String, direct: Boolean, think: Boolean, only: Set<Int>?) {
        AnnCache.clear(AnnCache.dirFor(c, bookId, direct, think, only))
        val saved = TaskResume.load(c)
        if (saved != null && saved.bookId == bookId) {
            current = null; _resume.value = null; TaskResume.clear(c)
        } else if (current?.bookId == bookId) {
            current = null; _resume.value = null; TaskResume.clear(c)
        }
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
            config = config, modeId = ModelStore.currentMode(c).id)
        appCtx?.let { TaskResume.save(it, current!!) }
        _resume.value = current
        _state.value = Progress(true, "正在准备", 0, 0, 0, "正在启动…")
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        AnnCache.cleanup(AnnCache.dirFor(c, book.id, true, thinkOn, onlyChapters))
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
        ProduceService.begin(c, book.title)
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
                if (current?.phase == "running") markInterrupted("中断")
                val p = _state.value
                ProduceService.finish(c.applicationContext, p.stage, p.done, p.total)
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
        val (chapters, allChunks) = ChapterSplitter.split(text)
        val chunks = if (only == null) allChunks else allChunks.filter { it.chapter in only }
        if (chunks.isEmpty()) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "这本书没有可制作的文字")
            return
        }
        val thinkOn = thinkOverride ?: false
        val work = AnnCache.dirFor(c, book.id, direct, thinkOn, only)
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

        val wavs = mutableListOf<File>()
        var synthFails = 0
        val gapBytes = ((config.gapMs.coerceIn(0, 5000) * 48) / 2) * 2
        val segStartBytes = mutableListOf<Long>()
        var cursorBytes = 0L
        val perChapterTotal = segChapter.groupingBy { it }.eachCount()
        val perChapterDone = mutableMapOf<Int, Int>()
        val pending = (0 until toSpeak.size).count { i ->
            val f = File(work, String.format("direct_%04d.wav", i))
            !(f.exists() && f.length() > 44)
        }
        val sample = (0 until toSpeak.size).mapNotNull { i ->
            File(work, String.format("direct_%04d.wav", i))
                .takeIf { it.exists() && it.length() > 44 }?.length()
        }
        val segBytes = if (sample.isNotEmpty()) sample.average().toLong()
                       else 24_000L * 2L * 60L
        val mergeBytes = toSpeak.size.toLong() * segBytes
        val needBytes = pending * segBytes * 3L / 2L + mergeBytes + 16L * 1024 * 1024
        val freeBytes = minOf(work.usableSpace, AudioLibrary.dir(c).usableSpace)
        try {
            if (needBytes > freeBytes) {
                val needMb = (needBytes + 1024 * 1024 - 1) / (1024 * 1024)
                val freeMb = freeBytes / (1024 * 1024)
                _state.value = Progress(false, "没完成", 0, annotated.size,
                    gateFails + synthFails,
                    "存储空间不够，这次大约要 " + needMb + " MB、可用 " + freeMb +
                        " MB；清出空间后在任务里点「继续制作」，已生成的段都留着")
                EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_SHORT",
                    "need=$needBytes free=$freeBytes pending=$pending seg=$segBytes")
                return
            }
            for ((i, a) in toSpeak.withIndex()) {
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val segStart = wavs.size
                ProduceService.touch(c.applicationContext, "合成段 ${i + 1}/${toSpeak.size}")
                val wav = File(work, String.format("direct_%04d.wav", i))
                val v = SynthInput.params(c, a.instruct, config.styleInstruct())
                EventLog.log(EventLog.Kind.ENGINE, "TTS_INPUT",
                    "段 ${i + 1}/${toSpeak.size} [${a.speaker}] " +
                        a.text.take(50).replace('\n', '⏎') + " → " + (v.instruct ?: "（无指令）"),
                    level = EventLog.Level.TRACE,
                    fields = mapOf("seg" to (i + 1).toString(), "speaker" to a.speaker,
                        "textChars" to a.text.length.toString(), "instructChars" to (v.instruct?.length ?: 0).toString(),
                        "lang" to "chinese", "mode" to ModelStore.currentMode(c).id,
                        "speakerParam" to (v.speaker ?: "null")))
                if (wav.exists() && wav.length() > 44) {
                    wavs.add(wav)
                    SegPlan.clearRedo(work, "seg:$i")
                    segStartBytes.add(cursorBytes)
                    cursorBytes += (wav.length() - 44) + gapBytes
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
        } finally {
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
        }
        if (cancelRequested) {
            _state.value = halted(annotated.size, annotated.size, gateFails + synthFails)
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
            File(outFile.parentFile, "${outFile.nameWithoutExtension}.chapters.json")
                .writeText(chJson.toString())
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
