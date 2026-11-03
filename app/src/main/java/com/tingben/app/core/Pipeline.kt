package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val headRe = Regex(
        """^\s*(第[一二三四五六七八九十百千零〇0-9]+[章节回卷篇].{0,40}|[0-9]{1,3}[、.．]\s*.{1,40})\s*$""",
        RegexOption.MULTILINE
    )

    fun split(text: String): Pair<List<Chapter>, List<Chunk>> {
        val marks = mutableListOf<Pair<Int, String>>()
        headRe.findAll(text).forEach { marks.add(it.range.first to it.value.trim()) }
        if (marks.isEmpty()) marks.add(0 to "整本")

        val chapters = mutableListOf<Chapter>()
        for (i in marks.indices) {
            val s = marks[i].first
            val e = if (i + 1 < marks.size) marks[i + 1].first else text.length
            chapters.add(Chapter(i, marks[i].second, s, e))
        }

        val chunks = mutableListOf<Chunk>()
        var idx = 0
        for (ch in chapters) {
            var buf = StringBuilder()
            var bufStart = ch.start
            var i = ch.start
            while (i < ch.end) {
                val ch0 = text[i]
                buf.append(ch0); i++
                val len = buf.length
                val boundary = ch0 == '\n' || ch0 == '。' || ch0 == '！' || ch0 == '？' ||
                        ch0 == '!' || ch0 == '?' || ch0 == '；' || ch0 == ';'
                if (len >= 500 || (len >= 350 && boundary)) {
                    if (buf.isNotEmpty()) {
                        chunks.add(Chunk(idx++, ch.index, buf.toString(), bufStart, i))
                    }
                    bufStart = i
                    buf = StringBuilder()
                }
            }
            if (buf.isNotEmpty()) chunks.add(Chunk(idx++, ch.index, buf.toString(), bufStart, ch.end))
        }
        return chapters to chunks
    }
}

// ─────────────────────────── 标注（Alexandria，加逐字 gate） ───────────────────────────

object Annotator {
    fun templates(c: Context): Pair<String, String> = try {
        val raw = c.assets.open("prompt_zh.txt").bufferedReader().use { it.readText() }
        val parts = raw.split("---SEPARATOR---", limit = 2)
        parts[0].trim() to parts[1].trim()
    } catch (_: Throwable) {
        "只输出 JSON 数组，不要解释。" to "原文：{{ORIGINAL}}"
    }

    fun buildPrompt(sys: String, user: String, chunk: Chunk, idx: Int, total: Int,
                    roster: Set<String>, recent: List<Ann>, thinking: Boolean = false): String {
        val u = user
            .replace("{{CHUNK_INDEX}}", (idx + 1).toString())
            .replace("{{CHUNK_TOTAL}}", total.toString())
            .replace("{{ROSTER}}", if (roster.isEmpty()) "（暂无）" else roster.joinToString("、"))
            .replace("{{RECENT}}", if (recent.isEmpty()) "（无）" else recent.takeLast(3)
                .joinToString(" / ") { it.speaker + "：" + it.text.take(24) })
            .replace("{{ORIGINAL}}", chunk.text)
            // Qwen3 初版系软开关：放在 user 末尾，/no_think 让模板预置空 think 块
            .plus(if (thinking) "\n/think" else "\n/no_think")
        return "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$u<|im_end|>\n<|im_start|>assistant\n"
    }

    /** 把模型「想了什么」抽出来，单独存；标注 JSON 仍由 parse() 从 </think> 之后取。 */
    fun extractThinking(raw: String): String {
        val end = raw.lastIndexOf("</think>")
        if (end < 0) return ""
        var s = raw.indexOf("<think>")
        if (s < 0) s = 0 else s += 7
        return raw.substring(s, end).trim()
    }

    /** 四级 JSON 修复，抄 Alexandria repair_json_array，但更克制。 */
    fun parse(rawIn: String): List<Ann>? {
        var raw = rawIn
        val close = raw.lastIndexOf("</think>")
        if (close >= 0) raw = raw.substring(close + 8)
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

    fun fallback(chunk: Chunk) = listOf(Ann(chunk.text, "旁白", "平稳、清晰地叙述，句间自然停顿"))
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

    fun reset() { if (!busy) _state.value = Progress() }

    fun start(c: Context, book: Book, onlyChapters: Set<Int>? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try { run(c, book, onlyChapters) } finally { busy = false }
        }
    }

    private fun emit(stage: String, done: Int, total: Int, failed: Int, msg: String, out: String? = null) {
        _state.value = Progress(true, stage, done, total, failed, msg, out)
    }

    private fun run(c: Context, book: Book, only: Set<Int>?) {
        if (!NativeEngine.isAvailable()) {
            _state.value = Progress(false, "没开始", 0, 0, 0, NativeEngine.shortReason())
            return
        }
        val llm = ModelStore.llm(c); val tk = ModelStore.talker(c); val tok = ModelStore.tokenizer(c)
        if (llm == null || tk == null || tok == null) {
            _state.value = Progress(false, "没开始", 0, 0, 0,
                "还差模型文件：" + ModelStore.missing(c).joinToString("、") + "。去设置页导入。")
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
        val work = File(c.filesDir, "work/${book.id}").apply { mkdirs() }
        val (sys, usr) = Annotator.templates(c)
        val thinkOn = Prefs.thinking(c)
        val nCtx = Prefs.nCtx(c)
        val threads = Prefs.threads(c)
        val temp = if (thinkOn) 0.6f else Prefs.temperature(c)
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_CONFIG",
            "think=$thinkOn nCtx=$nCtx threads=$threads temp=$temp chunks=${chunks.size}")
        if (thinkOn) ThinkingStore.clear(c)
        EventLog.resetEngine()

        // ── 标注阶段：只加载 LLM ──
        emit("正在理解文字", 0, chunks.size, 0, "语言模型正在逐块标注谁说的、怎么读…")
        val llmHandle = try {
            NativeEngine.llmLoad(llm.absolutePath, nCtx, threads)
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
        val annotated = mutableListOf<Ann>()
        var gateFails = 0
        try {
            for ((i, chunk) in chunks.withIndex()) {
                val prompt = Annotator.buildPrompt(sys, usr, chunk, i, chunks.size, roster, recent, thinkOn)
                // 按块字数给预算，避免模型无限吐字（旧项目 768 token 不收敛的教训）
                // 思考档要留空间给推理，且禁用贪心（思考模式贪心会无限重复）
                val budget = if (thinkOn) minOf(2048, chunk.text.length * 2 + 900)
                            else minOf(1024, chunk.text.length * 2 + 160)
                var result: List<Ann>? = null
                for (attempt in 0..1) {
                    val raw = try {
                        NativeEngine.llmGenerate(llmHandle, prompt, budget, temp)
                    } catch (t: Throwable) {
                        EventLog.log(EventLog.Kind.ENGINE, "LLM_ERROR", t.message ?: "")
                        break
                    }
                    if (thinkOn) {
                        val th = Annotator.extractThinking(raw)
                        if (th.isNotBlank()) ThinkingStore.write(c, i, th)
                    }
                    val parsed = Annotator.parse(raw)
                    if (parsed != null && Annotator.gate(parsed, chunk.text)) { result = parsed; break }
                    EventLog.log(EventLog.Kind.PIPELINE, "GATE_RETRY", "块 $i 第 $attempt 次")
                }
                if (result == null) {
                    result = Annotator.fallback(chunk)
                    gateFails++
                    EventLog.log(EventLog.Kind.PIPELINE, "GATE_FALLBACK", "块 $i 降级直读")
                }
                result.forEach { a ->
                    annotated.add(a)
                    if (a.speaker.isNotBlank() && a.speaker != "旁白") roster.add(a.speaker)
                }
                recent.addAll(result)
                while (recent.size > 3) recent.removeAt(0)
                emit("正在理解文字", i + 1, chunks.size, gateFails,
                    "已标注 ${i + 1}/${chunks.size} 块" + if (gateFails > 0) " · $gateFails 块改走直读" else "")
            }
        } finally {
            try { NativeEngine.llmFree(llmHandle) } catch (_: Throwable) { }
        }

        // ── 合成阶段：先释放再加载 TTS，绝不同时常驻 ──
        emit("正在生成声音", 0, annotated.size, gateFails, "音频模型正在把文字读出来…")
        val ttsHandle = try {
            NativeEngine.ttsLoad(tk.absolutePath, tok.absolutePath, null)
        } catch (t: Throwable) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "音频模型加载失败：" + (t.message ?: ""))
            return
        }
        if (ttsHandle == 0L) {
            _state.value = Progress(false, "没开始", 0, 0, 0, "音频模型打不开，请确认两个文件都导入了")
            return
        }

        val wavs = mutableListOf<File>()
        var synthFails = 0
        try {
            for ((i, a) in annotated.withIndex()) {
                val wav = File(work, String.format("chunk_%04d.wav", i))
                if (wav.exists() && wav.length() > 44) {
                    wavs.add(wav)
                } else {
                    val code = try {
                        NativeEngine.ttsSynthesize(ttsHandle, a.text, a.instruct, null, "auto",
                            wav.absolutePath, 42L)
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
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_DONE", "${outFile.length()} 字节")
    }
}
