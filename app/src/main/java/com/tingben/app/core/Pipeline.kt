package com.tingben.app.core

import android.content.Context
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
import java.util.UUID

// ─────────────────────────── 数据模型 ───────────────────────────

data class Book(val id: String, val title: String, val path: String, val chars: Int)
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
                Book(o.getString("id"), o.getString("title"), o.getString("path"), o.optInt("chars", 0))
            }.filter { File(it.path).exists() }
        } catch (_: Throwable) { emptyList() }
    }

    private fun save(c: Context, books: List<Book>) {
        val a = JSONArray()
        books.forEach { b ->
            a.put(JSONObject().put("id", b.id).put("title", b.title)
                .put("path", b.path).put("chars", b.chars))
        }
        meta(c).writeText(a.toString())
    }

    fun add(c: Context, title: String, text: String): Book {
        val id = UUID.randomUUID().toString()
        val f = File(dir(c), "$id.txt")
        f.writeText(text)
        val b = Book(id, title, f.absolutePath, text.length)
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
    const val LLM = "mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf"
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

    fun llm(c: Context) = pick(c, LLM, "qwen3-4b", "4b-abliterated")
    fun talker(c: Context) = pick(c, TALKER, "talker")
    fun tokenizer(c: Context) = pick(c, TOKENIZER, "tokenizer", "12hz")

    fun missing(c: Context): List<String> {
        val m = mutableListOf<String>()
        if (llm(c) == null) m.add("语言模型")
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

// ─────────────────────────── 标注（Alexandria，加逐字 gate） ───────────────────────────

object Annotator {
    /** 思考闭标签。写成常量，避免再出现 `contains("")` 那种恒真的空串判断。 */
    const val THINK_CLOSE = "</think>"

    fun templates(c: Context): Pair<String, String> = try {
        val raw = c.assets.open("prompt_zh.txt").bufferedReader().use { it.readText() }
        val parts = raw.split("---SEPARATOR---", limit = 2)
        parts[0].trim() to parts[1].trim()
    } catch (_: Throwable) {
        "只输出 JSON 数组，不要解释。" to "原文：{{ORIGINAL}}"
    }

    fun buildPrompt(sys: String, user: String, chunk: Chunk, idx: Int, total: Int,
                    roster: Set<String>, recent: List<Ann>, thinking: Boolean = false,
                    contextBefore: String = "（无）"): String {
        val u = user
            .replace("{{CHUNK_INDEX}}", (idx + 1).toString())
            .replace("{{CHUNK_TOTAL}}", total.toString())
            .replace("{{ROSTER}}", if (roster.isEmpty()) "（暂无）" else roster.joinToString("、"))
            // R16 · 紧邻前因：上一块末尾的原文，给「谁正在说话 / 引号有没有闭合」。
            // 放 target 之前，不放之后 —— 端侧 prefill 很贵，不放目标段之后的整段原文。
            .replace("{{CONTEXT_BEFORE}}", contextBefore.ifBlank { "（无）" })
            // 最近 3 条给完整一点的句子，24 字经常被截在半句里反而误导归属。
            .replace("{{RECENT}}", if (recent.isEmpty()) "（无）" else recent.takeLast(3)
                .joinToString(" / ") { it.speaker + "：" + it.text.take(40) })
            .replace("{{ORIGINAL}}", chunk.text)
            // Qwen3 初版系软开关：放在 user 末尾，/no_think 让模板预置空 think 块
            .plus(if (thinking) "\n/think" else "\n/no_think")
        // 改 prompt 最容易出的哑巴错：新增占位符忘了在装配里替换，花括号原样喂给模型。
        if (u.contains("{{")) {
            EventLog.log(EventLog.Kind.PIPELINE, "PROMPT_PLACEHOLDER_LEAK",
                "块 ${idx + 1} 仍有未替换占位符，检查 prompt 模板与 buildPrompt 是否同步")
        }
        return "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$u<|im_end|>\n<|im_start|>assistant\n"
    }

    /** 把模型「想了什么」抽出来，单独存；标注 JSON 仍由 parse() 从 </think> 之后取。 */
    fun extractThinking(raw: String): String {
        val end = raw.lastIndexOf(THINK_CLOSE)
        if (end < 0) return ""
        var s = raw.indexOf("<think>")
        if (s < 0) s = 0 else s += 7
        return raw.substring(s, end).trim()
    }

    /** 四级 JSON 修复，抄 Alexandria repair_json_array，但更克制。 */
    fun parse(rawIn: String): List<Ann>? {
        var raw = rawIn
        val close = raw.lastIndexOf(THINK_CLOSE)
        if (close >= 0) raw = raw.substring(close + THINK_CLOSE.length)
        raw = raw.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()
        val s = raw.indexOf('[')
        val e = raw.lastIndexOf(']')
        if (s < 0) return null
        val body = if (e > s) raw.substring(s, e + 1) else raw.substring(s) + "]"

        val tries = mutableListOf(body)
        tries.add(body.replace(Regex("\\}\\s*\\{"), "},\n{"))
        // 正则只捞完整的单条对象
        val objs = Regex("\\{[^{}]*\\}").findAll(body).map { it.value }.toList()
        if (objs.isNotEmpty()) tries.add("[" + objs.joinToString(",") + "]")
        val last = body.lastIndexOf("},")
        if (last > 0) tries.add(body.substring(0, last + 1) + "]")

        for (t in tries) {
            try {
                val a = JSONArray(t)
                val out = mutableListOf<Ann>()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out.add(Ann(o.optString("text", ""), o.optString("speaker", "旁白"),
                        o.optString("instruct", "平稳叙述")))
                }
                if (out.isNotEmpty() && out.all { it.text.isNotEmpty() }) return out
            } catch (_: Throwable) { }
        }
        return null
    }

    /** 字符级红线：所有 text 顺序拼接必须 == 本块原文。 */
    fun gate(anns: List<Ann>, original: String): Boolean =
        anns.joinToString("") { it.text } == original

    // 降级也必须切短句：真机日志里块 1 降级成整块 500 字一次喂 TTS，
    // 合成阶段从 04:49 卡到 05:24（35 分钟）。长文本单次合成在手机上是灾难。
    fun fallback(chunk: Chunk) = directSegments(chunk.text)

    /** 直接朗读用的短句切分：只按边界断，不删一个字，避免 TTS 一次吃 500 字。 */
    fun directSegments(text: String): List<Ann> {
        val out = mutableListOf<Ann>()
        val buf = StringBuilder()
        for (ch0 in text) {
            buf.append(ch0)
            val boundary = ch0 == '。' || ch0 == '！' || ch0 == '？' || ch0 == '；' ||
                    ch0 == '\n' || ch0 == '!' || ch0 == '?'
            if (buf.length >= 110 || (buf.length >= 60 && boundary)) {
                if (buf.isNotBlank()) out.add(Ann(buf.toString(), "旁白", "平稳、清晰地叙述，句间自然停顿"))
                buf.setLength(0)
            }
        }
        if (buf.isNotBlank()) out.add(Ann(buf.toString(), "旁白", "平稳、清晰地叙述，句间自然停顿"))
        return out
    }
}

/** 思考外显的内容本地落盘，只留最近 20 块，不进主界面、不联网。 */
object ThinkingStore {
    private fun dir(c: Context) = File(c.filesDir, "thinking").apply { mkdirs() }

    fun write(c: Context, index: Int, text: String) {
        try {
            File(dir(c), "block_%04d.txt".format(index)).writeText(text)
            val all = dir(c).listFiles()?.filter { it.isFile && it.name.startsWith("block_") }
                ?.sortedByDescending { it.name } ?: return
            all.drop(20).forEach { it.delete() }
        } catch (_: Throwable) { }
    }

    fun latest(c: Context): Pair<Int, String> = try {
        val f = dir(c).listFiles()?.filter { it.isFile && it.name.startsWith("block_") }
            ?.maxByOrNull { it.name }
        if (f != null) {
            val idx = f.name.removePrefix("block_").removeSuffix(".txt").toIntOrNull() ?: 0
            idx to f.readText()
        } else -1 to ""
    } catch (_: Throwable) { -1 to "" }

    fun clear(c: Context) {
        try { dir(c).listFiles()?.forEach { it.delete() } } catch (_: Throwable) { }
    }
}

/**
 * 标注结果按块落盘。4B 在手机上一块要几分钟，不缓存就永远别想「接着跑」。
 * work 目录按「模式 + 选中章节」分桶，换选章/换思考档不会串味。
 */
object AnnCache {
    fun read(work: File, i: Int): List<Ann>? {
        return try {
            val f = File(work, "ann_%04d.json".format(i))
            if (!f.exists()) null else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { k ->
                    val o = arr.getJSONObject(k)
                    Ann(o.getString("text"), o.optString("speaker", "旁白"), o.optString("instruct", ""))
                }.takeIf { it.isNotEmpty() }
            }
        } catch (_: Throwable) { null }
    }

    fun write(work: File, i: Int, list: List<Ann>) {
        try {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().put("text", a.text)
                    .put("speaker", a.speaker).put("instruct", a.instruct))
            }
            // R11 · 原子落位：进程在写入途中被杀，半成品不能占着正式文件名，
            // 否则重启后 read() 会把它当「已完成块」跳过，整段原文就丢了。
            val dst = File(work, "ann_%04d.json".format(i))
            val tmp = File(work, "ann_%04d.json.part".format(i))
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) { dst.writeText(arr.toString()); tmp.delete() }
        } catch (_: Throwable) { }
    }

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
        (if (direct) "direct" else if (think) "think" else "fast") + "/" +
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

    fun merge(parts: List<File>, out: File): Boolean {
        return try {
            val chunks = parts.mapNotNull { pcmOf(it) }
            if (chunks.isEmpty()) false else {
                val total = chunks.sumOf { it.size }
                out.outputStream().use { os ->
                    os.write(header(total))
                    chunks.forEach { os.write(it) }
                }
                EventLog.log(EventLog.Kind.PIPELINE, "MERGE_DONE", "${parts.size} 块 / $total 字节")
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
    val output: String? = null
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
              directRead: Boolean = false, thinkOverride: Boolean? = null) {
        if (busy) return
        busy = true
        cancelRequested = false
        appCtx = c.applicationContext
        // 续跑必须沿用建任务时的思考档：bucket 里含 think，换了档会读到空缓存、全书重跑
        val thinkOn = thinkOverride ?: Prefs.thinking(c)
        current = TaskInfo(book.id, book.title, onlyChapters, directRead, thinkOn,
            0, 0, "running", "正在准备", System.currentTimeMillis())
        appCtx?.let { TaskResume.save(it, current!!) }
        _resume.value = current
        // 上一次点过「停止」的话，native 的 g_cancel 还是 true；整次制作开始才复位。
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        AnnCache.cleanup(AnnCache.dirFor(c, book.id, directRead, thinkOn, onlyChapters))
        // R17 · 制作方式从 UI 小按钮升成正式选择后，所有入口（制作页 / 任务页 / 进度页续跑）
        // 都汇到 start，在这里记一条，诊断里能直接看出这一本是过了语言模型还是没过。
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_MODE",
            (if (directRead) "直接朗读（不过语言模型）" else "标注后合成") +
                " · think=" + thinkOn +
                " · " + (onlyChapters?.let { "选中 ${it.size} 章" } ?: "整本"))
        scope.launch {
            try {
                run(c, book, onlyChapters, directRead, thinkOverride)
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

    private fun emit(stage: String, done: Int, total: Int, failed: Int, msg: String, out: String? = null) {
        _state.value = Progress(true, stage, done, total, failed, msg, out)
        // 每完成一块就落一次书签：进程被杀时，最坏只丢当前这一块，前面全留着
        val c = appCtx
        val t = current
        if (c != null && t != null && t.phase == "running") {
            val up = t.copy(total = total, done = done, stage = stage,
                updatedAt = System.currentTimeMillis())
            current = up
            TaskResume.save(c, up)
        }
    }

    private fun run(c: Context, book: Book, only: Set<Int>?, direct: Boolean,
                    thinkOverride: Boolean? = null) {
        if (!NativeEngine.isAvailable()) {
            _state.value = Progress(false, "没开始", 0, 0, 0, NativeEngine.shortReason())
            return
        }
        val llm = ModelStore.llm(c); val tk = ModelStore.talker(c); val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("声音主文件")
        if (tok == null) miss.add("声音配套文件")
        if (!direct && llm == null) miss.add("语言模型")
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
        val (sys, usr) = Annotator.templates(c)
        // R16 · prompt 是契约，不是文案。哪一版 prompt 出的标注必须能查，
        // 否则以后 A/B 或回滚时，旧缓存和新结果混在一起说不清。
        EventLog.log(EventLog.Kind.ENGINE, "PROMPT_VERSION",
            "v2 sha=" + Integer.toHexString(sys.hashCode()) + " sysChars=" + sys.length)
        val thinkOn = thinkOverride ?: Prefs.thinking(c)
        val work = AnnCache.dirFor(c, book.id, direct, thinkOn, only)
        val nCtx = Prefs.nCtx(c)
        val threads = Prefs.threads(c)
        val temp = if (thinkOn) 0.6f else Prefs.temperature(c)
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_CONFIG",
            "think=$thinkOn nCtx=$nCtx threads=$threads temp=$temp chunks=${chunks.size}")
        if (thinkOn) ThinkingStore.clear(c)
        EventLog.resetEngine()

        val annotated: List<Ann>
        var gateFails = 0
        if (direct) {
            // 快速出声路径：不加载 4B、不标注，原文整块当旁白直接交给 TTS。
            // 真机 4B 逐 token 约 10 秒，整本不可等；先让用户几分钟内听到声音。
            annotated = chunks.flatMap { Annotator.directSegments(it.text) }
            EventLog.log(EventLog.Kind.PIPELINE, "DIRECT_READ",
                "${chunks.size} 块跳过标注，切成 ${annotated.size} 段直接朗读")
            emit("正在生成声音", 0, annotated.size, 0, "直接朗读原文，不跑语言模型…")
        } else {
            // ── 标注阶段：只加载 LLM ──
            emit("正在理解文字", 0, chunks.size, 0, "语言模型正在逐块标注谁说的、怎么读…")
            val llmHandle = try {
                NativeEngine.llmLoad(llm!!.absolutePath, nCtx, threads)
            } catch (t: Throwable) {
                _state.value = Progress(false, "没开始", 0, 0, 0, "语言模型加载失败：" + (t.message ?: ""))
                return
            }
            if (llmHandle == 0L) {
                _state.value = Progress(false, "没开始", 0, 0, 0, "语言模型打不开，请确认文件完整")
                return
            }

            val roster = LinkedHashSet<String>()
            val recent = mutableListOf<Ann>()
            val collected = mutableListOf<Ann>()
            var resumed = 0
            try {
                for ((i, chunk) in chunks.withIndex()) {
                    if (cancelRequested) {
                        _state.value = halted(i, chunks.size, gateFails)
                        return
                    }
                    // 断点续传：这一块之前标过就直接读盘，不重跑 4B；
                    // 同时照样喂进 roster / recent，保证后面块的上下文是连贯的。
                    var result = AnnCache.read(work, i)
                    if (result != null) {
                        resumed++
                        EventLog.log(EventLog.Kind.PIPELINE, "ANN_BLOCK_BEGIN",
                            "块 ${i + 1}/${chunks.size} 从磁盘续上 · 角色表 ${roster.size} · 近邻 ${recent.size}")
                    } else {
                        val prompt = Annotator.buildPrompt(sys, usr, chunk, i, chunks.size, roster, recent, thinkOn,
                            // R16 · 上一块末尾 120 字作为「紧邻前因」。续跑读盘的块也照样传，
                            // 保证同一块无论重跑还是续跑，看到的上下文一致。
                            chunks.getOrNull(i - 1)?.text?.takeLast(120) ?: "（无）")
                        // 按块字数给预算，避免模型无限吐字（旧项目 768 token 不收敛的教训）
                        // 思考档要留空间给推理，且禁用贪心（思考模式贪心会无限重复）
                        val budget = if (thinkOn) minOf(2048, chunk.text.length * 2 + 900)
                                    else minOf(1024, chunk.text.length * 2 + 160)
                        EventLog.log(EventLog.Kind.PIPELINE, "ANN_BLOCK_BEGIN",
                            "块 ${i + 1}/${chunks.size} 原文 ${chunk.text.length} 字 · prompt ${prompt.length} 字" +
                                " · 预算 $budget · nCtx $nCtx · 角色表 ${roster.size} · 近邻 ${recent.size}" +
                                " · 思考 $thinkOn")
                        // 手机 CPU 上一块要几分钟，prefill 单次 llama_decode 期间 native 没有任何事件，
                        // 界面又只在块完成才更新 i+1/N —— 用户看起来就像「只跑第一段」。
                        // 这条纯 Kotlin 心跳不碰状态机，只往事件流写「还活着、算到哪块」。
                        val beat = scope.launch(Dispatchers.IO) {
                            var sec = 0
                            while (isActive) {
                                delay(5000)
                                sec += 5
                                EventLog.log(EventLog.Kind.ENGINE, "ANN_WAIT",
                                    "块 ${i + 1}/${chunks.size} 仍在算 · 已 ${sec}s（手机 CPU 慢，不是死机）")
                            }
                        }
                        try {
                        for (attempt in 0..1) {
                            // R9 · 停止可能发生在 prefill 里，generate 返回后必须立刻查；
                            // 否则空结果会被当成「标注失败」再重试一次，停止就永远不生效。
                            if (cancelRequested) {
                                _state.value = halted(i, chunks.size, gateFails)
                                return
                            }
                            val raw = try {
                                NativeEngine.llmGenerate(llmHandle, prompt, budget, temp)
                            } catch (t: Throwable) {
                                EventLog.log(EventLog.Kind.ENGINE, "LLM_ERROR", t.message ?: "")
                                break
                            }
                            if (cancelRequested) {
                                _state.value = halted(i, chunks.size, gateFails)
                                return
                            }
                            if (thinkOn) {
                                val th = Annotator.extractThinking(raw)
                                if (th.isNotBlank()) ThinkingStore.write(c, i, th)
                            }
                            val parsed = Annotator.parse(raw)
                            if (parsed != null && Annotator.gate(parsed, chunk.text)) { result = parsed; break }
                            // 思考档常见：预算全花在推理上，JSON 被拦腰截断。
                            // 重试同样的 prompt 只会再来一次几分钟的空转，直接降级。
                            // 旧写法是 !raw.contains("") —— 空串恒包含，这个分支永远进不来，
                            // 于是思考被截断后照样重试一次、白烧几分钟。用常量杜绝再写空串。
                            if (thinkOn && !raw.contains(Annotator.THINK_CLOSE)) {
                                EventLog.log(EventLog.Kind.PIPELINE, "THINK_TRUNCATED",
                                    "块 $i 没有闭合思考标签，推理被预算截断，跳过重试")
                                break
                            }
                            EventLog.log(EventLog.Kind.PIPELINE, "GATE_RETRY", "块 $i 第 $attempt 次")
                        }
                        if (result == null) {
                            result = Annotator.fallback(chunk)
                            gateFails++
                            EventLog.log(EventLog.Kind.PIPELINE, "GATE_FALLBACK", "块 $i 降级直读")
                        }
                        AnnCache.write(work, i, result)
                        } finally {
                            beat.cancel()
                            EventLog.log(EventLog.Kind.PIPELINE, "ANN_BLOCK_END",
                                "块 ${i + 1}/${chunks.size} 结束 · ${result?.size ?: 0} 条标注")
                        }
                    }
                    val safe = result!!
                    safe.forEach { a ->
                        collected.add(a)
                        if (a.speaker.isNotBlank() && a.speaker != "旁白") roster.add(a.speaker)
                    }
                    recent.addAll(safe)
                    while (recent.size > 3) recent.removeAt(0)
                    emit("正在理解文字", i + 1, chunks.size, gateFails,
                        "已标注 ${i + 1}/${chunks.size} 块" +
                            (if (resumed > 0) " · $resumed 块直接续上" else "") +
                            if (gateFails > 0) " · $gateFails 块改走直读" else "")
                }
            } finally {
                try { NativeEngine.llmFree(llmHandle) } catch (_: Throwable) { }
            }
            if (cancelRequested) { _state.value = halted(chunks.size, chunks.size, gateFails); return }
            annotated = collected
        }

        // ── 合成阶段：先释放再加载 TTS，绝不同时常驻 ──
        emit("正在生成声音", 0, annotated.size, gateFails, "音频模型正在把文字读出来…")

        // R14 · 声音指纹：音色描述 / 角色表 / 采样 / instruct 模板 任一变化，
        // 旧 WAV 必须作废，否则「续跑」会跳过旧块、听起来还是旧声音，界面还假装续上了。
        // 只删 WAV，标注结果（跑了几分钟的 4B 产物）留着不重算。
        // 第一次写指纹时不删（默认配置下 instruct 与旧版完全一致，旧 WAV 仍有效）。
        val knobs = Prefs.ttsKnobs(c)
        val globalVoice = Prefs.voiceDescription(c)
        val castOn = Prefs.castEnabled(c)
        val cast = if (castOn) VoiceCast.reconcile(c) else emptyList()
        val voiceStamp = Prefs.voiceStamp(c)
        val stampFile = File(work, "voice_stamp.txt")
        try {
            if (stampFile.exists() && stampFile.readText().trim() != voiceStamp) {
                val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()
                killed.forEach { it.delete() }
                EventLog.log(EventLog.Kind.PIPELINE, "VOICE_INVALIDATE",
                    "声音配置变了，作废 ${killed.size} 段旧音频，标注保留")
            }
            stampFile.writeText(voiceStamp)
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_MODE",
            "voice_design · 指纹=$voiceStamp · 角色=${cast.size} · seed=${knobs.seed} · temp=${knobs.temperature}")

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

        // R11 · 拟声表达：只在合成前增强 instruct，text 一字不动（逐字 gate 不受影响）
        val nonVerbalOn = Prefs.nonVerbal(c)
        var unmapped = 0
        val toSpeak = annotated.map { ann ->
            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann
            // R14 · 身份层（角色专属 > 全局）由用户/角色表固定，4B 只负责本段情绪。
            // 不暂停长任务：未映射的角色沿用全局音色，并如实记一条，角色页里能看见待确认。
            val role = if (castOn) VoiceCast.resolve(c, ann.speaker) else null
            if (castOn && !VoiceCast.isNarrator(ann.speaker) && role == null) unmapped++
            val identity = role?.description?.takeIf { it.isNotBlank() } ?: globalVoice
            val instruct = VoiceProfile.build(identity, null, base.instruct)
            Ann(base.text, base.speaker, instruct)
        }
        if (nonVerbalOn) {
            val hits = NonVerbal.count(annotated)
            EventLog.log(EventLog.Kind.PIPELINE, "NONVERBAL",
                "命中 $hits/${annotated.size} 段，只增强导演指令，正文未改")
        }
        if (unmapped > 0) EventLog.log(EventLog.Kind.PIPELINE, "VOICE_UNMAPPED",
            "$unmapped 段角色还没指定音色，暂用全局音色；去设置页「角色音色」确认")

        val wavs = mutableListOf<File>()
        var synthFails = 0
        try {
            for ((i, a) in toSpeak.withIndex()) {
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val wav = File(work, String.format(if (direct) "direct_%04d.wav" else "chunk_%04d.wav", i))
                if (wav.exists() && wav.length() > 44) {
                    wavs.add(wav)
                } else {
                    val code = try {
                        NativeEngine.ttsSynthesize(ttsHandle, a.text, a.instruct, null, "auto",
                            wav.absolutePath, knobs)
                    } catch (t: Throwable) {
                        EventLog.log(EventLog.Kind.ENGINE, "TTS_ERROR", t.message ?: "")
                        -99
                    }
                    if (code == 0 && wav.exists() && wav.length() > 44) wavs.add(wav)
                    else { synthFails++; wav.delete() }
                }
                emit("正在生成声音", i + 1, annotated.size, gateFails + synthFails,
                    "已生成 ${i + 1}/${annotated.size} 段声音")
            }
        } finally {
            try { NativeEngine.ttsFree(ttsHandle) } catch (_: Throwable) { }
        }
        if (cancelRequested) {
            _state.value = halted(annotated.size, annotated.size, gateFails + synthFails)
            return
        }

        // ── 合并 ──
        emit("正在整理音频", annotated.size, annotated.size, gateFails + synthFails, "正在把各段接成一本…")
        val outFile = File(c.filesDir, "audio/${book.id}.wav").apply { parentFile?.mkdirs() }
        val ok = WavMerger.merge(wavs, outFile)
        if (!ok || outFile.length() <= 44) {
            _state.value = Progress(false, "没完成", annotated.size, annotated.size,
                gateFails + synthFails, "没能生成音频，请点设置页复制诊断信息发我")
            return
        }
        // 章节信息（起止样本数，不伪造内嵌轨）
        try {
            val chJson = JSONArray()
            chapters.forEach { ch ->
                val n = wavs.size
                chJson.put(JSONObject().put("index", ch.index).put("title", ch.title))
            }
            File(outFile.parentFile, "${book.id}.chapters.json").writeText(chJson.toString())
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
