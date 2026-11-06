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

// ─────────────────────────── 数据模型 ───────────────────────────

/** R18 · sha 是正文指纹：反复用「打开方式」打开同一本书不该让书架一直膨胀。 */
data class Book(val id: String, val title: String, val path: String, val chars: Int,
                val sha: String = "")
data class Chapter(val index: Int, val title: String, val start: Int, val end: Int)
data class Chunk(val index: Int, val chapter: Int, val text: String, val start: Int, val end: Int)
data class Ann(val text: String, val speaker: String, val instruct: String)

// ─────────────────────────── 书架 ───────────────────────────

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
     * R18 · 同一份正文（同名 + 同指纹）只保留一本。
     * 之前每次「打开方式 / 分享 / 粘贴」都无脑新建 UUID，书架越用越满，
     * 而用户其实只是想再打开那本书。命中时返回旧书，不写新文件。
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

// ─────────────────────────── 模型槽位 ───────────────────────────

object ModelStore {
    /**
     * R68 · P1b：旧文本权重整套退场后，下面两个名称常量只作「旧数据白名单」保留——
     * 老用户私有目录里若还躺着旧 GGUF，不主动删、也不再加载；模型清单只读两份声音权重。
     */
    const val LLM = "huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf"
    const val LLM_LEGACY = "mlabonne_Qwen3-旧权重-abliterated-Q4_K_M.gguf"
    const val TALKER = "qwen-talker-1.7b-voicedesign-Q8_0.gguf"
    const val TOKENIZER = "qwen-tokenizer-12hz-Q8_0.gguf"

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun files(c: Context): List<File> = try {
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /**
     * 把 SAF 选中的文件复制进私有目录；同名覆盖。
     * 必须流式、必须在后台线程调用、必须写 .part 再原子改名：
     * 2.5GB 一次性读进内存会 OOM，主线程同步复制会 ANR 被系统杀掉。
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
            tmp.delete()   // 半截文件绝不留下来被当成就绪
            val reason = t.message ?: ""
            EventLog.log(EventLog.Kind.CRASH, "MODEL_IMPORT_FAIL",
                "$safe ${t.javaClass.simpleName}: $reason")
            throw t
        }
    }

    fun delete(c: Context, f: File) { f.delete(); EventLog.log(EventLog.Kind.MODEL, "MODEL_DELETE", f.name) }

    /** 上次导入被系统中断留下的半截文件，清掉，绝不被当成就绪。 */
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

    // R68 · P1b：旧文本权重的加载函数已删，只剩上面的名称常量兜旧数据。
    fun talker(c: Context) = pick(c, TALKER, "talker")
    fun tokenizer(c: Context) = pick(c, TOKENIZER, "tokenizer", "12hz")

    fun missing(c: Context): List<String> {
        val m = mutableListOf<String>()
        if (talker(c) == null) m.add("声音主文件")
        if (tokenizer(c) == null) m.add("声音配套文件")
        return m
    }
    fun ready(c: Context) = missing(c).isEmpty()
}

// ─────────────────────────── 分块（Alexandria + Antioch） ───────────────────────────

object ChapterSplitter {
    /** txt 兜底用的标题正则：除「第X章」，补上中文小说常见的序章类与 Chapter N。 */
    private val headRe = Regex(
        """^\s*(第[一二三四五六七八九十百千零〇0-9]+[章节回卷篇].{0,40}|""" +
        """[0-9]{1,3}[、.．]\s*.{1,40}|""" +
        """(?:楔子|序章|序言|引子|尾声|后记|番外|楔|序|跋)(?:.{0,30})|""" +
        """(?:Chapter|CHAPTER|PART|Part)\s*[0-9一二三四五六七八九十]{1,4}(?:.{0,30}))\s*$""",
        RegexOption.MULTILINE
    )

    /** 一段文字按 ≤500 字切成朗读块，绝不改动一个字符。 */
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

        // R10 · epub 由 Importer 打了显式边界：一个 spine 文件 = 一章。
        // 走这条路就不再靠正则猜标题，「楔子 / Chapter 1 / 一、」都能正确成章，且顺序由 OPF 保证。
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

// ─────────────────────────── 文本切分（直接朗读） ───────────────────────────

object Annotator {
    /**
     * R25 · 断句只在句末标点 / 段落 / 次级标点边界，绝不按字数腰斩；
     * 切片拼回与原文逐字相等（TextSplitter.Stats.charsIn == charsOut）。
     * R68 · P1b：旧分段管线退场，本对象只剩「直接朗读」的断句入口。
     */
    fun directWithStats(text: String): Pair<List<Ann>, TextSplitter.Stats> {
        val (segs, st) = TextSplitter.split(text)
        return segs.map { Ann(it, "旁白", "平稳、清晰地叙述，句间自然停顿") } to st
    }

    fun directSegments(text: String): List<Ann> = directWithStats(text).first
}

/**
 * 制作工作目录按块落盘，断点续传靠它认「哪几块做好了」。
 * R68 · P1b：只剩一条直接朗读路径，分桶前缀继续沿用 "v11/direct"，
 * 旧版本的分桶目录仍按原规则读取，不删用户已做好的分段 WAV。
 */
object AnnCache {

    fun count(work: File): Int = try {
        work.listFiles()?.count { it.isFile && it.name.startsWith("ann_") && it.name.endsWith(".json") } ?: 0
    } catch (_: Throwable) { 0 }

    /** 每次开工前清掉上次被杀留下的半成品，避免被误当成完成块。 */
    fun cleanup(work: File) {
        try { work.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        catch (_: Throwable) { }
    }

    fun clear(work: File) {
        try { work.listFiles()?.forEach { it.delete() } } catch (_: Throwable) { }
    }

    /** 这本书、这个模式下有没有没跑完的东西可续。 */
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


// ─────────────────────────── WAV 合并（Antioch merger） ───────────────────────────

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
     * R20 · 段间静音：24kHz / 16bit / mono ⇒ 每毫秒 48 字节，取偶数保证 16bit 对齐。
     * 依据 epub_to_audiobook 的 break_duration 参数（0-5000ms）。
     * 只在段与段之间插，最后一段后不插；header 的 dataLen 必须把静音算进去。
     * gapMs 默认 0，旧调用点行为不变；不影响分段 WAV 内容，故不进任何缓存指纹。
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

// ─────────────────────────── 一键编排（唯一入口） ───────────────────────────

data class Progress(
    val running: Boolean = false,
    val stage: String = "还没开始",
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val message: String = "",
    val output: String? = null,
    // R74 · 逐章真实进度：章索引 -> 该章已生成段数 / 该章总段数。
    // 只有合成循环填它；其余阶段保持空表，界面不许拿整本 done/total 冒充逐章。
    val chapterProgress: Map<Int, Float> = emptyMap()
)

object Producer {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state
    @Volatile private var busy = false
    @Volatile private var cancelRequested = false

    // R11 · 进程死了以后唯一还活着的东西
    @Volatile private var appCtx: Context? = null
    @Volatile private var current: TaskInfo? = null
    private val _resume = MutableStateFlow<TaskInfo?>(null)
    val resume: StateFlow<TaskInfo?> = _resume

    fun reset() { if (!busy) _state.value = Progress() }
    fun isBusy() = busy
    fun isStopping() = cancelRequested

    /** Application.onCreate 调一次：把上次被杀/退出的任务重新摆到界面上。 */
    fun restore(c: Context) {
        appCtx = c.applicationContext
        val t = TaskResume.active(c) ?: return
        // 写的是 running 但进程已经重启 ⇒ 它绝不可能还在跑，如实标成「中断」
        val fixed = if (t.phase == "running") t.copy(phase = "interrupted") else t
        TaskResume.save(c, fixed)
        current = fixed
        val real = TaskResume.realProgress(c, fixed)
        _resume.value = fixed.copy(done = real)
        _state.value = Progress(false, "上次中断", real, fixed.total, 0,
            "上次做到《${fixed.title}》，已做好的块都留着。点「继续制作」从这接着跑，不用重算。")
        EventLog.log(EventLog.Kind.PIPELINE, "TASK_RESTORE",
            "book=${fixed.title} phase=${fixed.phase} real=$real/${fixed.total}")
    }

    /** 停止：不杀进程、不删已做好的块， native 解码下一 token 会自己退，块边界收尾。 */
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
        // 「从头重做 / 放弃任务」必须把书签也清掉。
        // 重启后 current 是 null，所以以磁盘记录为准，不能只判断内存里的 current。
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
        // R67 · P1a：续跑仍沿用书签里的旧分桶档位；新建任务恒 false，不再读写思考设置键。
        val thinkOn = thinkOverride ?: false
        // R20 · 本次预设随书签落盘；续跑入口统一传 t.config，风格不因进程重启而断层。
        current = TaskInfo(book.id, book.title, onlyChapters, true, thinkOn,
            0, 0, "running", "正在准备", System.currentTimeMillis(),
            config = config)
        appCtx?.let { TaskResume.save(it, current!!) }
        _resume.value = current
        // 上一次点过「停止」的话，native 的 g_cancel 还是 true；整次制作开始才复位。
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        AnnCache.cleanup(AnnCache.dirFor(c, book.id, true, thinkOn, onlyChapters))
        // R17 · 制作方式从 UI 小按钮升成正式选择后，所有入口（制作页 / 任务页 / 进度页续跑）
        // 所有启动入口都汇到 start，在这里记一次本次制作范围与预设。
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_MODE",
            "直接朗读" +
                " · think=" + thinkOn +
                " · " + (onlyChapters?.let { "选中 ${it.size} 章" } ?: "整本") +
                " · 本次预设=" + config.summary())
        // R26 · 通知权限：四个启动入口全汇到 start，只在这里申请一次。
        // Android 13+ 不授权时通知不进抽屉，前台服务仍在但用户看不见；拒绝也不拦制作。
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
        // R26 · 必须在协程开跑前起服务：startForegroundService 要求 5 秒内 startForeground，
        // 且只能从可见界面发起，不能等进程进后台再偷偷起。
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
                // 只要没走到 done，就如实落一条「中断」书签，进程被杀也有据可查
                if (current?.phase == "running") markInterrupted("中断")
                // R26 · 完成 / 停止 / 出错 / 缺模型提前 return 全走这里：
                // 收服务、释放唤醒锁、把通知换成「任务已结束」。漏了会挂一整夜。
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
        // 每完成一块就落一次书签：进程被杀时，最坏只丢当前这一块，前面全留着
        val c = appCtx
        val t = current
        if (c != null && t != null && t.phase == "running") {
            val up = t.copy(total = total, done = done, stage = stage,
                updatedAt = System.currentTimeMillis())
            current = up
            TaskResume.save(c, up)
        }
        // R26 · 通知是切到后台后唯一可见的界面，每块 / 每段都要更新进度条。
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
        val tk = ModelStore.talker(c); val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("声音主文件")
        if (tok == null) miss.add("声音配套文件")
        // R67 · P1a：只剩声音模型，不再检查第二份权重。
        if (miss.isNotEmpty()) {
            _state.value = Progress(false, "没开始", 0, 0, 0,
                "还差：" + miss.joinToString("、") + "。去设置页导入。")
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
        // R68 · P1b：只剩一条直接朗读路径，不加载第二份权重、不读旧文本管线参数。
        val thinkOn = thinkOverride ?: false
        val work = AnnCache.dirFor(c, book.id, direct, thinkOn, only)
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_CONFIG",
            "chunks=${chunks.size} · 预设=" + config.summary())
        EventLog.resetEngine()

        // R68 · P1b：单一路径后没有块级审计/模型命中计数；gateFails 恒 0，
        // 只为不改合成/合并段 6 处算式而保留，kotlin 会内联成常量。
        val gateFails = 0
        // R68 · 单一路径：原文按标点/段落边界切段后直接交给 TTS，不跑任何文本管线。
        val stats = mutableListOf<TextSplitter.Stats>()
        // R74 · 段 -> 章 的归属表：flatMap 会让段丢掉 chapter，这里按块补齐，
        // 合成循环才知道「这一段属于第几章」，逐章进度才有真值来源。
        val segChapter = mutableListOf<Int>()
        val annotated = chunks.flatMap { ch ->
            val (segs, s) = Annotator.directWithStats(ch.text)
            stats.add(s)
            repeat(segs.size) { segChapter.add(ch.chapter) }
            segs
        }
        // R25 · 用户原话「起码按符号断，不能按字符段」：把断句结果写进日志，
        // 贴一段事件就能核对有没有从半句腰斩、原文有没有少字。
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
        // ── 合成阶段：先释放再加载 TTS，绝不同时常驻 ──
        emit("正在生成声音", 0, annotated.size, gateFails, "音频模型正在把文字读出来…")

        // 声音指纹：音色描述 / 采样 / instruct 模板 任一变化，旧 WAV 必须作废。
        // 只删 WAV，文本切分结果留着不重算。
        // 双指纹兼容：旧版指纹含角色表项，命中 legacy 就不作废，只改写成新值，
        // 避免升级后把用户已经做好的音频一次性删光。
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
            "voice_design · 指纹=$voiceStamp · seed=${knobs.seed}" +
                " · temp=${knobs.temperature} · lang=chinese（官方 README：目标语言已知时显式传，别用 auto）")

        // R20 · 预设指纹：voiceStamp 覆盖「升级 App 导致模板版本变化」，
        // 这里覆盖「同一版本内用户把叙事改成广播剧」。两者都只删 WAV、保留切分结果：
        // 换风格应该「复用切分、重合成」，不是重新切一遍书。
        // gapMs 不进指纹（只在合并插静音）。
        val presetFile = File(work, "produce_stamp.txt")
        val presetStamp = config.audioStamp()
        try {
            val oldWavs = work.listFiles()?.count { f ->
                f.isFile && f.name.endsWith(".wav") && f.length() > 44
            } ?: 0
            val changed = presetFile.exists() && presetFile.readText().trim() != presetStamp
            // 首次写也要删：如果 App 刚从没有风格层的版本升上来且已有旧 WAV，
            // 不能让续跑跳过它们、听出「前半本旧味道、后半本新味道」。
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
            NativeEngine.ttsLoad(tk!!.absolutePath, tok!!.absolutePath, null)
        } catch (t: Throwable) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "音频模型加载失败：" + (t.message ?: ""))
            return
        }
        if (ttsHandle == 0L) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "音频模型打不开，请确认两个文件都导入了")
            return
        }

        // R11 · 拟声表达：只在合成前增强 instruct，正文一字不动
        val nonVerbalOn = Prefs.nonVerbal(c)
        var strippedTotal = 0
        val toSpeak = annotated.map { ann ->
            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann
            // 声音只由调音台的全局音色决定；拟声表达与本次风格层只改 instruct，正文一字不动。
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
        // R74 · 每章总段数 / 已生成段数；只统计真正落盘成功的段，失败段不算完成。
        val perChapterTotal = segChapter.groupingBy { it }.eachCount()
        val perChapterDone = mutableMapOf<Int, Int>()
        try {
            for ((i, a) in toSpeak.withIndex()) {
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val segStart = wavs.size
                // R26 · 合成阶段同样按段刷新唤醒锁；TTS 单次可能几分钟。
                ProduceService.touch(c.applicationContext, "合成段 ${i + 1}/${toSpeak.size}")
                val wav = File(work, String.format("direct_%04d.wav", i))
                // 让「这一段到底用什么指令读的」可见：最终 instruct（含身份层/风格层）。
                // voice_design 的 speaker 必须传 null。
                EventLog.log(EventLog.Kind.ENGINE, "TTS_INPUT",
                    "段 ${i + 1}/${toSpeak.size} [${a.speaker}] " +
                        a.text.take(50).replace('\n', '⏎') + " → " + a.instruct.replace('\n', '｜'),
                    level = EventLog.Level.TRACE,
                    fields = mapOf("seg" to (i + 1).toString(), "speaker" to a.speaker,
                        "textChars" to a.text.length.toString(), "instructChars" to a.instruct.length.toString(),
                        "lang" to "chinese", "speakerParam" to "null"))
                if (wav.exists() && wav.length() > 44) {
                    wavs.add(wav)
                    SegPlan.clearRedo(work, "seg:$i")
                } else {
                    val code = try {
                        // 官方 Qwen3-TTS README：目标语言已知时显式传，别用 auto；全中文书固定 chinese。
                        NativeEngine.ttsSynthesize(ttsHandle, a.text, a.instruct, null, "chinese",
                            wav.absolutePath, knobs)
                    } catch (t: Throwable) {
                        EventLog.log(EventLog.Kind.ENGINE, "TTS_ERROR", t.message ?: "")
                        -99
                    }
                    if (code == 0 && wav.exists() && wav.length() > 44) {
                        wavs.add(wav)
                        SegPlan.clearRedo(work, "seg:$i")
                    } else { synthFails++; wav.delete() }
                }
                // R74 · 只有这一段真的落了 WAV 才推进该章；续跑跳过已有 WAV 也算完成。
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
            try { NativeEngine.ttsFree(ttsHandle) } catch (_: Throwable) { }
        }
        if (cancelRequested) {
            _state.value = halted(annotated.size, annotated.size, gateFails + synthFails)
            return
        }

        // ── 合并 ──
        emit("正在整理音频", annotated.size, annotated.size, gateFails + synthFails, "正在把各段接成一个音频…")
        // R18 · 旧版写死 audio/<bookId>.wav：做第 1 章和第 3 章会互相覆盖，
        // 音频页又把文件名当 bookId，一章的成品显示成整本书。改成每次独立成品 id。
        val outFile = File(AudioLibrary.dir(c), "${UUID.randomUUID()}.wav")
        val ok = WavMerger.merge(wavs, outFile, config.gapMs)
        if (!ok || outFile.length() <= 44) {
            _state.value = Progress(false, "没完成", annotated.size, annotated.size,
                gateFails + synthFails, "没能生成音频，请点设置页复制诊断信息发我")
            return
        }
        // R61 · 把全书章数带进去：整本成品要展开成 0..n-1，第四列才亮。
        val item = AudioLibrary.register(c, book.id, book.title, only, direct, outFile, chapters.size)
        EventLog.log(EventLog.Kind.PIPELINE, "AUDIO_REGISTER",
            "${item.title} · ${item.scope} · ${item.mode} · ${item.bytes} 字节")
        // 章节信息跟着成品 id 走，不再被下一次制作覆盖
        try {
            val chJson = JSONArray()
            chapters.forEach { ch ->
                chJson.put(JSONObject().put("index", ch.index).put("title", ch.title))
            }
            File(outFile.parentFile, "${outFile.nameWithoutExtension}.chapters.json")
                .writeText(chJson.toString())
        } catch (_: Throwable) { }

        _state.value = Progress(false, "完成", annotated.size, annotated.size,
            gateFails + synthFails, "做好了，可以去「我的音频」听", outFile.absolutePath)
        // 任务中心要能显示「最近完成」，所以 done 也留一条记录（active() 会自动过滤掉它）
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
