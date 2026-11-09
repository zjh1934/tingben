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

// R157 · origin 只用于「导出时归到哪个文件夹」，不参与分章/合成/索引：
//   file  = 从 txt/md/epub 导入的书
//   input = 输入框粘贴 / 别的 App 分享文字建的书
// 老 books.json 没有这个字段，list() 用 optString 兜底成 file，零迁移。
data class Book(val id: String, val title: String, val path: String, val chars: Int,
                val sha: String = "", val origin: String = "file")
data class Chapter(val index: Int, val title: String, val start: Int, val end: Int)
data class Chunk(val index: Int, val chapter: Int, val text: String, val start: Int, val end: Int)
data class Ann(val text: String, val speaker: String, val instruct: String)

object BookStore {
    // R139 · 文本字符数上限。Importer 的字节闸门在 R172 压到与它同量级
    // （TXT 24MB / EPUB 64MB），中文 UTF-8 一个字 3 字节，解码前就先挡住。
    const val MAX_TEXT_CHARS = 6_000_000

    private fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    private fun meta(c: Context) = File(dir(c), "books.json")
    // H1 · 置顶单独存一份，不动 Book 数据类与既有 save()（老版本 books.json 零迁移）。
    private fun pinFile(c: Context) = File(dir(c), "pins.json")

    /**
     * R172 · P1「一次导入静默抹掉整个书架」根治。
     *
     * 原来 books.json 裸写 + 解析失败 return emptyList()：写入中途被杀留下半截
     * JSON，list() 就当书架是空的且不上报，随后 add() 里 list().toMutableList()
     * 已经是空的，save() 覆写成只含新书的一行 —— 旧书正文还在磁盘里却成了孤儿。
     * 现在读写全走 AtomicJson（.part/.bak 恢复），损坏时明确拒绝写入，
     * 绝不把「读不到」当成「没有书」。pins.json 同样裸写，一并收掉。
     */
    @Volatile private var recoveryNotified = false
    private fun indexCorrupt(c: Context): Boolean =
        AtomicJson.read(meta(c)) is AtomicJson.Read.Corrupt

    fun pinned(c: Context): Set<String> {
        val body = when (val r = AtomicJson.read(pinFile(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return emptySet()
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun setPinned(c: Context, id: String, on: Boolean) {
        try {
            val s = pinned(c).toMutableSet()
            if (on) s.add(id) else s.remove(id)
            val a = JSONArray()
            s.forEach { a.put(it) }
            if (!AtomicJson.write(pinFile(c), a.toString()))
                UiErrors.storage("置顶记录没写进磁盘", null)
            EventLog.log(EventLog.Kind.MODEL, "BOOK_PIN", "$id on=$on")
        } catch (_: Throwable) { }
    }

    fun list(c: Context): List<Book> {
        val r = AtomicJson.read(meta(c))
        val body = when (r) {
            is AtomicJson.Read.Missing -> return emptyList()
            is AtomicJson.Read.Corrupt -> {
                // 读不到就是读不到，绝不当成空书架；调用方 save() 会拒绝覆写。
                EventLog.log(EventLog.Kind.MODEL, "BOOK_INDEX_CORRUPT",
                    "书架索引损坏且无可用备份，已阻止写入以免抹掉旧书")
                return emptyList()
            }
            is AtomicJson.Read.Ok -> {
                if (r.from.isNotEmpty() && !recoveryNotified) {
                    recoveryNotified = true
                    EventLog.log(EventLog.Kind.MODEL, "BOOK_INDEX_RECOVERED",
                        "已从 ${r.from} 恢复书架索引")
                    UiErrors.storage("书架索引已从上一次备份恢复", null)
                }
                r.text
            }
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Book(o.getString("id"), o.getString("title"), o.getString("path"),
                    o.optInt("chars", 0), o.optString("sha", ""),
                    o.optString("origin", "file"))
            }.filter { File(it.path).exists() }
            .let { books ->
                val pins = pinned(c)
                books.sortedByDescending { it.id in pins }
            }
        } catch (_: Throwable) {
            // 结构合法但字段缺失也算损坏：同样不许被一次导入覆写
            EventLog.log(EventLog.Kind.MODEL, "BOOK_INDEX_BAD_FIELDS",
                "书架索引字段读不出来，已阻止写入")
            emptyList()
        }
    }

    /** 返回是否真的写成功；索引损坏时一律拒写，返回 false。 */
    private fun save(c: Context, books: List<Book>): Boolean {
        if (indexCorrupt(c)) {
            EventLog.log(EventLog.Kind.MODEL, "BOOK_WRITE_BLOCKED",
                "索引损坏，拒绝覆写 books.json，避免抹掉旧书")
            return false
        }
        val a = JSONArray()
        books.forEach { b ->
            a.put(JSONObject().put("id", b.id).put("title", b.title)
                .put("path", b.path).put("chars", b.chars).put("sha", b.sha)
                .put("origin", b.origin))
        }
        val ok = AtomicJson.write(meta(c), a.toString())
        if (!ok) UiErrors.storage("书架索引没写进磁盘", null)
        return ok
    }

    private fun fingerprint(text: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Throwable) { "${text.length}_${text.hashCode()}" }

    fun add(c: Context, title: String, text: String, origin: String = "file"): Book {
        if (text.length > MAX_TEXT_CHARS)
            throw IllegalStateException(
                "这本书有 ${text.length / 10000} 万字，超过 ${MAX_TEXT_CHARS / 10000} 万字上限；" +
                    "先拆成几本再导入（太长的书在手机上会内存不够）")
        // 先挡在写正文之前：索引坏了就别导入，否则一次 save() 会把旧书抹掉
        if (indexCorrupt(c))
            throw IllegalStateException("书架索引损坏，已阻止导入以免抹掉旧书")
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
        val b = Book(id, title, f.absolutePath, text.length, sha, origin)
        val all = list(c).toMutableList().also { it.add(0, b) }
        if (!save(c, all)) {
            // 索引没写成功就把刚写的正文撤掉，不留孤儿文件
            f.delete()
            throw IllegalStateException("书架索引没写进磁盘，已取消导入，旧书没动")
        }
        EventLog.log(EventLog.Kind.MODEL, "BOOK_ADD", title)
        return b
    }

    fun read(c: Context, b: Book): String = File(b.path).readText()

    /**
     * R193 · 返回 false = 这本书还在收尾，已替用户点了停止但**什么都没删**，
     * 调用方要给「稍等再删」的反馈；true 才是真删干净了。
     * 绝不能在后台还握着 work/<id> 时 deleteRecursively。
     */
    fun delete(c: Context, id: String): Boolean {
        if (Producer.isBusyForBook(id)) {
            try { Producer.stopIfBook(id) } catch (_: Throwable) { }
            EventLog.log(EventLog.Kind.MODEL, "BOOK_DELETE_DEFERRED",
                "书=$id 还在收尾上一段，已点停止、暂不删除，等停稳再删")
            return false
        }
        val all = list(c)
        all.find { it.id == id }?.let { File(it.path).delete() }
        if (!save(c, all.filter { it.id != id })) {
            EventLog.log(EventLog.Kind.MODEL, "BOOK_DELETE_INDEX_FAILED",
                "删书时索引没写成；原书正文文件已删，旧索引先留着不动")
        }
        try { setPinned(c, id, false) } catch (_: Throwable) { }
        purge(c, id)
        return true
    }

    /**
     * R138 · 删书全量清理。1.1.2 只删原书，留下 work/ 分段 WAV、audio/ 成品与索引、
     * 任务/队列项、以及新的章节表 —— 占空间、索引里挂着不存在的书、
     * 队列要等 pumpQueue 撞上才发现书没了。现在一次清干净。
     */
    private fun purge(c: Context, id: String) {
        // R192 · 顺序要紧：先停正在播 / 正在做的这本书，再删文件。
        // release() 内部 savePos() 会写回断点，所以 ListenState 清理必须放它后面。
        try { PlayQueue.releaseIfBook(id) } catch (_: Throwable) { }
        try { Producer.stopIfBook(id) } catch (_: Throwable) { }
        try { ListenState.removeForBook(c, id) } catch (_: Throwable) { }
        try { ReadingState.remove(c, id) } catch (_: Throwable) { }
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
            "书已删：章节表 / 音频成品与索引 / 分段 WAV / 任务 / 队列 / " +
                "收听与阅读位置一并清理；正在播放或制作这本书的先停")
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

    /**
     * R175 · P3-9「模型导入零完整性校验」根治。
     * 原来 import() 只判「文件非空」，pick/pickFor 又靠文件名子串认主文件：
     * 下载残件、误选的小文件、改名后的错模型都能通过 ready() 一路传进 native，
     * 轻则加载失败重则进程崩溃，UI 却显示「已导入 ✓」。
     * 现在文件头必须是 GGUF 魔数且不小于 64KB；精确名与模糊匹配一律只认
     * 通过校验的文件。校验发生在 rename 之前，残件进不了 models/。
     */
    private const val GGUF_MAGIC = "GGUF"
    private const val MIN_GGUF_BYTES = 64L * 1024L

    private fun looksLikeGguf(f: File): Boolean = try {
        if (!f.isFile || f.length() < MIN_GGUF_BYTES) false
        else java.io.RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(4)
            raf.readFully(h)
            String(h, Charsets.US_ASCII) == GGUF_MAGIC
        }
    } catch (_: Throwable) { false }

    private fun validFiles(c: Context): List<File> = files(c).filter { looksLikeGguf(it) }

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
            // R175 · 残件在 rename 成正式文件之前就被挡下，旧文件与 .bak 一个字不动
            if (!looksLikeGguf(tmp))
                throw IllegalStateException(
                    "这个文件不像完整的 GGUF 模型（文件头不对或下载不完整），重新下载后再导入")
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
            // R175 · 疑似残件不自动删（不替用户做破坏性决定），但明确记账
            files(c).filter { !looksLikeGguf(it) }.forEach {
                EventLog.log(EventLog.Kind.MODEL, "MODEL_SUSPECT",
                    "${it.name} ${it.length()} 字节 · 头不对或过小，已排除在可用模型之外")
            }
        } catch (_: Throwable) { }
    }

    private fun pick(c: Context, exact: String, vararg hints: String): File? {
        val all = validFiles(c)
        all.find { it.name.equals(exact, true) }?.let { return it }
        return all.firstOrNull { f -> hints.any { f.name.contains(it, true) } }
    }

    private fun pickFor(c: Context, m: Mode): File? {
        val all = validFiles(c)
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

// @core-insert: ChapterSplitter
// @core-insert: Annotator
object AnnCache {

    fun count(work: File): Int = try {
        work.listFiles()?.count { it.isFile && it.name.startsWith("ann_") && it.name.endsWith(".json") } ?: 0
    } catch (_: Throwable) { 0 }

    fun cleanup(work: File) {
        try { work.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        catch (_: Throwable) { }
    }

    fun clear(work: File) {
        // R185 · 归档套是子目录，原来的 it.delete() 对非空目录会静默失败，
        // 删书时留一堆旧音色缓存。保留 work 目录本身，只清里面的内容。
        try {
            work.listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        } catch (_: Throwable) { }
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
    /**
     * R172 · P0「长书必崩」根治。
     *
     * 原来把整本书所有分段 PCM 一次性 readBytes() 进堆（6 小时 ≈ 1.04 GB，
     * declick 又逐段 copyOf 一份，峰值超 2 GB），走到「正在整理音频」必 OOM，
     * 前面几千段全白做。现在只读每个 WAV 的 chunk 头定位 data 段，按固定缓冲
     * 逐段流式写出；declick 只处理当前段的首尾帧，内存与整本长度无关。
     * 顺带修掉旧 pcmOf 用有符号 Int 拼 chunk 长度时高位为 1 会变负、
     * i 不前进甚至倒退的死循环（畸形 / 超大 WAV）。
     */
    private const val IO_BUF = 64 * 1024

    /** 返回 data chunk 的 (起始偏移, 字节数)；只扫 chunk 头，绝不把 PCM 读进内存。 */
    private fun dataSpan(f: File): Pair<Long, Long>? = try {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(12)
            raf.readFully(h)
            if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(h, 8, 4, Charsets.US_ASCII) != "WAVE") null
            else {
                val fileLen = raf.length()
                var pos = 12L
                var found: Pair<Long, Long>? = null
                while (found == null && pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val ch = ByteArray(8)
                    raf.readFully(ch)
                    val id = String(ch, 0, 4, Charsets.US_ASCII)
                    // 无符号 32 位：用 Long 拼，高位为 1 也不会变成负数
                    val sz = (ch[4].toLong() and 0xffL) or
                            ((ch[5].toLong() and 0xffL) shl 8) or
                            ((ch[6].toLong() and 0xffL) shl 16) or
                            ((ch[7].toLong() and 0xffL) shl 24)
                    val payload = pos + 8
                    if (payload + sz > fileLen) break
                    if (id == "data") {
                        found = payload to minOf(sz, fileLen - payload)
                    } else {
                        val pad = if (sz % 2L == 1L) 1L else 0L
                        val next = payload + sz + pad
                        if (next <= pos) break      // 畸形头：不许原地打转
                        pos = next
                    }
                }
                found
            }
        }
    } catch (_: Throwable) { null }

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

    /** 就地改一帧的增益，不再 copyOf 整段。 */
    private fun rampFrame(out: ByteArray, frame: Int, gain: Float) {
        val p = frame * 2
        if (p < 0 || p + 1 >= out.size) return
        val s = ((out[p].toInt() and 0xff) or
            ((out[p + 1].toInt() and 0xff) shl 8)).toShort().toInt()
        val v = (s * gain).toInt().coerceIn(-32768, 32767)
        out[p] = (v and 0xff).toByte()
        out[p + 1] = ((v shr 8) and 0xff).toByte()
    }

    private fun declickInPlace(pcm: ByteArray, ms: Int) {
        if (ms <= 0 || pcm.size < 4) return
        val frames = (24000 * ms / 1000).coerceIn(1, pcm.size / 2)
        for (j in 0 until frames) {
            val g = j.toFloat() / frames
            rampFrame(pcm, j, g)
            rampFrame(pcm, pcm.size / 2 - 1 - j, g)
        }
    }

    private fun copyRaw(raf: java.io.RandomAccessFile, offset: Long, len: Long,
                        os: java.io.OutputStream) {
        raf.seek(offset)
        val buf = ByteArray(IO_BUF)
        var left = len
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            os.write(buf, 0, n)
            left -= n
        }
    }

    /** 只把首尾各 frames 帧读进内存做 ramp，中间按缓冲直拷；峰值恒定几十 KB。 */
    private fun writePcm(raf: java.io.RandomAccessFile, offset: Long, len: Long,
                         os: java.io.OutputStream, declickMs: Int) {
        if (len <= 0) return
        val frames = if (declickMs <= 0) 0 else
            (24000 * declickMs / 1000).coerceIn(1, (len / 2).toInt().coerceAtLeast(1))
        if (frames == 0) { copyRaw(raf, offset, len, os); return }
        val edge = frames * 2
        if (len <= edge * 2L) {
            // 极短段：整段读进内存（单段最多几十秒，与整本长度无关）
            val one = ByteArray(len.toInt())
            raf.seek(offset); raf.readFully(one)
            declickInPlace(one, declickMs)
            os.write(one)
            return
        }
        val head = ByteArray(edge)
        raf.seek(offset); raf.readFully(head)
        for (j in 0 until frames) rampFrame(head, j, j.toFloat() / frames)
        os.write(head)
        copyRaw(raf, offset + edge, len - edge * 2L, os)
        val tail = ByteArray(edge)
        raf.seek(offset + len - edge); raf.readFully(tail)
        for (k in 0 until frames)
            rampFrame(tail, k, (frames - 1 - k).toFloat() / frames)
        os.write(tail)
    }

    fun merge(parts: List<File>, out: File, gapMs: Int = 0, declickMs: Int = 30): Boolean {
        val part = File(out.parentFile, out.name + ".part")
        return try {
            if (parts.isEmpty()) { part.delete(); return false }
            // ① 只扫头算总长；任何一段坏了就整体失败（等价于旧 chunks.size != parts.size）
            val spans = ArrayList<Pair<Long, Long>>(parts.size)
            for (p in parts) {
                val sp = dataSpan(p)
                if (sp == null || sp.second <= 0) { part.delete(); return false }
                spans.add(sp)
            }
            val gapBytes = ((gapMs.coerceIn(0, 5000) * 48) / 2) * 2
            var total = 0L
            spans.forEach { total += it.second }
            if (spans.size > 1) total += gapBytes.toLong() * (spans.size - 1)
            // WAV 的 RIFF / data 长度是 32 位有符号 Int，越界就明确拒绝，不写出坏头
            if (total <= 0L || total > Int.MAX_VALUE - 36L) {
                part.delete()
                EventLog.log(EventLog.Kind.PIPELINE, "MERGE_TOO_LARGE",
                    "成品 $total 字节超过单个 WAV 上限，先分章导出")
                return false
            }
            // ② 逐段流式写出，全书 PCM 从不同时驻留
            java.io.FileOutputStream(part, false).use { os ->
                os.write(header(total.toInt()))
                val zero = ByteArray(minOf(IO_BUF, gapBytes.coerceAtLeast(1)))
                spans.forEachIndexed { i, (offset, len) ->
                    if (i > 0 && gapBytes > 0) {
                        var left = gapBytes
                        while (left > 0) {
                            val n = minOf(zero.size, left)
                            os.write(zero, 0, n)
                            left -= n
                        }
                    }
                    java.io.RandomAccessFile(parts[i], "r").use { raf ->
                        writePcm(raf, offset, len, os, declickMs)
                    }
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

    /**
     * R193 · 点「停止」后 running 立刻变 false，但 busy 要等当前那段推理
     * 收尾完才在 finally 里清（native 一段 1~3 分钟）。这段时间后台还握着
     * work/<id>，删书 / 重新分章不能只看 state.running，否则会删掉正在写的
     * 目录、重建幽灵分段。stop() 把 current 换成 stopped 副本但 bookId 不变，
     * 所以这个判据能完整覆盖收尾窗口。
     */
    fun isBusyForBook(bookId: String): Boolean =
        busy && current?.bookId == bookId

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
            if (t.phase == "blocked")
                "《${fixed.title}》上次没能开始制作；请在任务页点「继续制作」重试。"
            else if (t.phase == "reconfirm")
                "《${fixed.title}》重新分过章，旧任务不能接着用；请重新选章制作。"
            else if (why != null)
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
            splitRule = TaskResume.SPLIT_RULE,
            planEpoch = ChapterPlan.epoch(c, book.id))
        val paused = TaskResume.queuePaused(c)
        if (!busy && TaskResume.queue(c).isEmpty() && !paused) {
            start(c, book, only, true, think, config)
            // R175 · P2-6：队列泵与自动续跑都查了，只有这条直接启动路径漏了。
            // FGS 起不来时 start() 已把任务存成 blocked，这里绝不能再报「开始制作」。
            lastStartError?.let { return it }
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

    // R159 · 开软件时的「待处理任务」。
    //
    // 用户实证：「关闭以后重新打开应用，它不一定弹」。查实有四条原因，其中三条
    // 在这里：原来 hasResumable 只放行「真的能自动续跑」的任务，手动停 / 失败 /
    // 模型没齐 / 队列暂停一律返回 null，界面就静默不弹；用户眼里就是「看运气」。
    // 现在改成：只要存在未完成任务就返回，由 PendingTask.action 决定弹窗给什么
    // 出口（自动续跑 / 继续队列 / 去任务页手动处理）。守卫仍留在 autoResume 里，
    // 放宽「看不看得见」不等于放宽「能不能自动跑」。
    enum class ResumeAction { AUTO, QUEUE, VIEW }

    data class PendingTask(
        val task: TaskInfo,
        val action: ResumeAction,
        val label: String,     // 短状态标签，进胶囊，不写成长句解释
        val reason: String?    // 只进日志，不进 UI
    )

    fun pending(c: Context): PendingTask? {
        if (busy) return null
        val paused = TaskResume.queuePaused(c)
        if (paused) queuePaused = true
        val q = TaskResume.queue(c).firstOrNull()
        if (q != null) {
            return if (paused)
                PendingTask(q, ResumeAction.QUEUE, "队列已暂停", "队列已暂停")
            else
                PendingTask(q, ResumeAction.AUTO, "排队中", null)
        }
        val t = TaskResume.active(c) ?: return null
        if (t.phase == "done") return null
        if (BookStore.list(c).none { it.id == t.bookId }) return null
        val why = TaskResume.autoResumeBlockReason(c, t)
        val label = when {
            t.stage == "已达时长上限" -> "已达时长上限"
            t.phase == "blocked" -> "没开始成功"
            t.phase == "stopped" -> "已停止"
            t.phase == "interrupted" && why == null -> "意外中断"
            why != null -> "待确认"
            else -> "未完成"
        }
        return PendingTask(
            t, if (why == null) ResumeAction.AUTO else ResumeAction.VIEW, label, why)
    }

    /** 兼容旧调用：只把「能自动续跑」的挑出来，口径与 pending 同源，不分家。 */
    fun hasResumable(c: Context): TaskInfo? =
        pending(c)?.takeIf { it.action == ResumeAction.AUTO }?.task

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

    /**
     * R192 · 删书时若正在制作这本书，立刻停。否则后台协程会往刚
     * deleteRecursively 掉的 work/<id> 重新写盘，重建幽灵目录继续推理。
     * 复用 stop()（它会同步 current / 落 stopped / nativeCancel），
     * 因此会连带把队列置为暂停 —— 如实写进回执，不静默吞掉。
     */
    fun stopIfBook(bookId: String): Boolean {
        if (!busy || current?.bookId != bookId) return false
        stop()
        return true
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

    /**
     * R161 · 系统前台服务跑到 6 小时上限（Service.onTimeout）时的统一出口。
     * 与用户点停止共用 stop()，但把 stage 写成可辨识的「已达时长上限」：
     * 下次开软件弹窗显示这个原因，而不是「意外中断」，也不会被当成崩溃
     * 自动续跑（一续跑又会立刻撞上限）。手动停的任务绝不被偷偷跑起来。
     */
    fun onServiceTimeout(c: Context, fgsType: Int) {
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_FGS_TIMEOUT",
            "前台服务时长到点，任务安全停下 · fgsType=$fgsType · " +
                "done=${_state.value.done}/${_state.value.total}",
            fields = mapOf("fgsType" to fgsType.toString()))
        if (busy) stop()
        val ctx = appCtx ?: c.applicationContext
        val p = _state.value
        current?.let { t ->
            val fixed = t.copy(phase = "stopped", stage = "已达时长上限",
                done = p.done, updatedAt = System.currentTimeMillis())
            current = fixed
            TaskResume.save(ctx, fixed)
            _resume.value = fixed
        }
        _state.value = p.copy(running = false, stage = "已达时长上限",
            message = "已到系统本次运行时长上限，安全停在 ${p.done}/${p.total} 块，做好的都留着。")
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
            splitRule = TaskResume.SPLIT_RULE,
            planEpoch = ChapterPlan.epoch(c, book.id))
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
            // R175 · 系统限制不是「用户手动停」，也不该被 finally 误标中断
            abortBeforeStart(why)
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

    /**
     * R175 · P2-5「没开始的配置失败被记成意外中断」根治。
     *
     * 原来 run() 里 native 不可用 / 模型没齐 / 没有可制作文字 / 模型加载失败
     * 都只设 _state 后 return，current.phase 仍是 "running"；finally 于是
     * finally 把它标成「意外中断」。而 autoResumeBlockReason 的 stage 黑名单只有
     * 没完成/出错了/已停止，不含「中断」；模型齐全时 synthesisBlockReason 与
     * invalidationReason 都返回 null → pending() 给 AUTO → 开软件自动续跑、
     * 再次早退、再次记中断，循环往复，弹窗还写着「意外中断」。
     * 现在显式落 blocked 阶段并说清原因，finally 不再碰它。
     */
    private fun abortBeforeStart(msg: String, stage: String = "没开始") {
        _state.value = Progress(false, stage, 0, 0, 0, msg)
        val t = current ?: return
        val blocked = t.copy(phase = "blocked", stage = stage,
            done = 0, total = 0, updatedAt = System.currentTimeMillis())
        current = blocked
        appCtx?.let { TaskResume.save(it, blocked) }
        _resume.value = blocked
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_BLOCKED", "$stage：$msg")
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

    /**
     * R176 · 长书落盘 / 通知节流。
     *
     * 原来每段 emit 都全量重写 tasks.json 与任务清单：AtomicJson.write 内部还要
     * 读旧文件、写 .bak、写 .part、rename，一段就是十来次文件操作；9000 段的
     * 6 小时书累计几万次 IO，外加 9000 次 nm.notify。长书后段明显变慢、耗电。
     *
     * 现在 1.5 秒落一次盘、1.5 秒刷一次通知；阶段切换与收尾强制落一次。
     * 断点不丢：已落盘 WAV 才是进度真值（TaskResume.realProgress 按磁盘重算），
     * tasks.json 的 done 只影响文案，进程被硬杀时最多滞后 1.5 秒。
     * 内存态 _state / current 仍每段更新，界面与进度条完全不受节流影响。
     */
    private const val PERSIST_INTERVAL_MS = 1500L
    private var lastPersistMs = 0L
    private var lastNotifyMs = 0L

    private fun persistTick(force: Boolean) {
        val c = appCtx ?: return
        val t = current ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < PERSIST_INTERVAL_MS) return
        TaskResume.save(c, t)
        lastPersistMs = now
    }

    /** R183 · 把毫秒说成人能估量的「X 小时 Y 分 / X 分」，不给假精确。 */
    private fun formatEta(ms: Long): String {
        val min = (ms / 60000L).coerceAtLeast(1L)
        return if (min >= 60L) "${min / 60L} 小时 ${min % 60L} 分" else "$min 分"
    }

    /**
     * R183 · 制作前就能说清「这次设置和已做好的段不一样，会重做」。
     * 判据与 run() 里两处作废逻辑同源：mode_stamp / produce_stamp 任一变了，
     * 或从未写过 stamp 但已有 wav（老数据）。只数有效 wav，不读正文。
     */
    fun voiceCacheSummary(c: Context, bookId: String, only: Set<Int>?,
                          config: ProduceConfig = ProduceConfig()): VoiceCache.Summary = try {
        val work = AnnCache.dirFor(c, bookId, only)
        val key = VoiceCache.keyOf(Prefs.voiceStamp(c),
            TaskResume.expectedModeStamp(c), config.audioStamp())
        VoiceCache.summary(work, key)
    } catch (_: Throwable) { VoiceCache.Summary(0, 0, 0, 0L) }

    private fun emit(stage: String, done: Int, total: Int, failed: Int, msg: String,
                     out: String? = null, chapterProgress: Map<Int, Float> = emptyMap()) {
        _state.value = Progress(true, stage, done, total, failed, msg, out, chapterProgress)
        val c = appCtx
        val t = current
        // t 是 copy 之前的旧对象，拿它比 stage 能准确识别「刚换了阶段」
        val stageChanged = t?.stage != stage
        if (c != null && t != null && t.phase == "running") {
            val up = t.copy(total = total, done = done, stage = stage,
                updatedAt = System.currentTimeMillis())
            current = up
            persistTick(force = stageChanged)
        }
        val now = System.currentTimeMillis()
        if (stageChanged || now - lastNotifyMs >= PERSIST_INTERVAL_MS) {
            appCtx?.let { ctx ->
                ProduceService.update(ctx.applicationContext, stage, done, total, msg)
            }
            lastNotifyMs = now
        }
    }

    private fun run(c: Context, book: Book, only: Set<Int>?, direct: Boolean,
                    thinkOverride: Boolean? = null, config: ProduceConfig = ProduceConfig()) {
        // R176 · 每个任务重新计时，别让上一任务的节流窗口吃掉本次首帧
        lastPersistMs = 0L
        lastNotifyMs = 0L
        if (!NativeEngine.isAvailable()) {
            abortBeforeStart(NativeEngine.shortReason())
            return
        }
        ModelStore.synthesisBlockReason(c)?.let { reason ->
            abortBeforeStart(reason)
            return
        }
        val tk = ModelStore.modeFile(c, ModelStore.currentMode(c))
        val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("模式主文件")
        if (tok == null) miss.add("共用底座（12Hz）")
        if (miss.isNotEmpty()) {
            abortBeforeStart("还差：" + miss.joinToString("、") + "。去模型页导入。")
            return
        }

        emit("正在分章", 0, 0, 0, "正在把这本书切成能读的小块…")
        val text = BookStore.read(c, book)
        // R138 · 制作运行也用落盘章节表，保证与目录页、断点校验同一套章号
        val (chapters, allChunks) = ChapterPlan.resolve(c, book, text)
        val chunks = if (only == null) allChunks else allChunks.filter { it.chapter in only }
        if (chunks.isEmpty()) {
            abortBeforeStart("这本书没有可制作的文字")
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
                (if (stats.sumOf { it.charsIn } == stats.sumOf { it.charsOut }) " · 逐字无损" else " · ⚠️字数不一致"))
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
        val modeStamp = TaskResume.expectedModeStamp(c)
        val presetStamp = config.audioStamp()

        // R185 · 原来这里有三段「stamp 一变就把 work 根下所有 wav 删光」的逻辑。
        // 用户经常调音色，一次保存调优就把几小时的成果全抹了（真机日志
        // MODE_INVALIDATE 作废 9 段、real 从 9/88 掉回 1/88）。现在改成按
        // 音色指纹分套归档：旧套搬到 _voice_{key}/ 留着，目标套搬回根目录。
        // 三个 stamp 文件照旧写，TaskResume 的失效判据继续有效，但不再删文件。
        val cacheKey = VoiceCache.keyOf(voiceStamp, modeStamp, presetStamp)
        val sw = VoiceCache.switchTo(work, cacheKey)
        if (sw.archived > 0 || sw.restored > 0) {
            EventLog.log(EventLog.Kind.PIPELINE, "VOICE_CACHE_SWITCH",
                "音色套=$cacheKey · 归档 ${sw.archived} 段 · 取回 ${sw.restored} 段" +
                    " · 旧成果没删，切回原设置就能接着用")
        }
        try { File(work, "voice_stamp.txt").writeText(voiceStamp) } catch (_: Throwable) { }
        try { File(work, "mode_stamp.txt").writeText(modeStamp) } catch (_: Throwable) { }
        try { File(work, "produce_stamp.txt").writeText(presetStamp) } catch (_: Throwable) { }

        // R166 · 括号里那句关于目标语言取值的说明是写给我看的开发依据，
        // 混进运行时日志只会让每行拖一句解释；依据留在 ParamSpec / 官方文档。
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_MODE",
            "${SynthInput.label(c)} · 指纹=$voiceStamp · seed=${knobs.seed}" +
                " · temp=${knobs.temperature} · lang=chinese")
        EventLog.log(EventLog.Kind.PIPELINE, "PRESET_APPLIED",
            config.summary() + " · 指纹=" + presetStamp)

        val ttsHandle = try {
            NativeEngine.ttsLoadGated(tk!!.absolutePath, tok!!.absolutePath, null)
        } catch (t: Throwable) {
            abortBeforeStart("音频模型加载失败：" + (t.message ?: ""))
            return
        }
        if (ttsHandle == 0L) {
            abortBeforeStart("音频模型打不开，请确认两个文件都导入了")
            return
        }
        try {
            SynthInput.prepare(c, ttsHandle)
        } catch (t: Throwable) {
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
            abortBeforeStart(t.message ?: "模式输入没准备好")
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
        // R157 · 只有真合成的段才算参数；跳过段不碰它（见循环里的 TTS_SKIP）。
        // 声明成可空并在用时兜底重算，任何意外路径都不会拿到空指令。
        var lastParams: SynthInput.Params? = null
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
        // R175 · P2-7：budgetSegs 是「这次还能新合成多少段」。原来拿 wavs.size
        // （含跳过复用的旧段）去比，纯续跑一个字节没写也会被判定空间用完、
        // 提前 break 卡在合并之前。只数真正落盘的新段。
        var newlyWritten = 0
        // R183 · 真机日志实证：每段 50~220 秒，用户等 4 分 23 秒只出 1 段就点了
        // 停止。进度里给「按当前速度还需约多久」，让人知道不是卡死，也好决定
        // 要不要整本做。只统计真正合成的新段，跳过的旧段不算进均值。
        var segCostSum = 0L
        var segCostCount = 0
        try {
            for ((i, a) in toSpeak.withIndex()) {
                if (newlyWritten >= budgetSegs) { spaceStopped = true; break }
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val segStart = wavs.size
                val wav = File(work, String.format("direct_%04d.wav", i))
                val ci = segChapter.getOrElse(i) { -1 }
                val doneWav = if (ci >= 0) doneChapterFiles[ci] else null
                val covered = doneWav != null && doneWav.isFile && doneWav.length() > 44 &&
                    !(wav.exists() && wav.length() > 44)
                val already = wav.exists() && wav.length() > 44
                val skip = covered || already
                // R157 · 第二份真机日志：每次「继续制作」都从 TTS_INPUT 段 1/88 打起，
                // 看起来像从头重算，实际前 9 段走的是下面这个「已有音频」跳过分支。
                // 把日志和前台通知挪到真正要合成的段上；跳过段只记一条精简 SKIP。
                // 用户之前报过「愣住、不知道后面进哪张」，这条日志误导也占一份。
                if (skip) {
                    EventLog.log(EventLog.Kind.PIPELINE, "TTS_SKIP",
                        "段 ${i + 1}/${toSpeak.size} 已有音频，跳过",
                        level = EventLog.Level.TRACE,
                        fields = mapOf("seg" to (i + 1).toString(),
                            "src" to if (covered) "chapter" else "wav"))
                } else {
                    ProduceService.touch(c.applicationContext,
                        "合成段 ${i + 1}/${toSpeak.size}")
                    val v = SynthInput.params(c, a.instruct, config.styleInstruct())
                    EventLog.log(EventLog.Kind.ENGINE, "TTS_INPUT",
                        "段 ${i + 1}/${toSpeak.size} [${a.speaker}] " +
                            a.text.take(50).replace('\n', '⏎') + " → " + (v.instruct ?: "（无指令）"),
                        level = EventLog.Level.TRACE,
                        fields = mapOf("seg" to (i + 1).toString(), "speaker" to a.speaker,
                            "textChars" to a.text.length.toString(),
                            "instructChars" to (v.instruct?.length ?: 0).toString(),
                            "lang" to "chinese", "mode" to ModelStore.currentMode(c).id,
                            "speakerParam" to (v.speaker ?: "null")))
                    // 参数只给真正合成的那一段用
                    lastParams = v
                }
                if (skip) {
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
                    val segT0 = System.currentTimeMillis()
                    var ok = false
                    var lastCode = -1
                    for (attempt in 0..1) {
                        if (cancelRequested) {
                            _state.value = halted(i, annotated.size, gateFails + synthFails)
                            return
                        }
                        val v = lastParams
                            ?: SynthInput.params(c, a.instruct, config.styleInstruct())
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
                            "第 ${i + 1} 段没生成声音（${SynthErrors.reason(lastCode, false)}）；" +
                                "前面 ${wavs.size} 段都留着，" +
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
                    newlyWritten++
                    segCostSum += (System.currentTimeMillis() - segT0).coerceAtLeast(0L)
                    segCostCount++
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
                val left = annotated.size - (i + 1)
                val avg = if (segCostCount > 0) segCostSum / segCostCount else 0L
                val eta = if (avg > 0L && left > 0) avg * left else 0L
                emit("正在生成声音", i + 1, annotated.size, gateFails + synthFails,
                    "已生成 ${i + 1}/${annotated.size} 段声音" +
                        if (eta > 0L) " · 按当前速度还需约 ${formatEta(eta)}" else "",
                    chapterProgress = chapterSnapshot)
            }
            if (spaceStopped) {
                _state.value = Progress(false, "没完成", wavs.size, annotated.size,
                    gateFails + synthFails,
                    "空间用完了，已做好 ${wavs.size} 段并都留着；清出空间后点「继续制作」从这接着做")
                EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_PARTIAL_STOP",
                    "kept=${wavs.size} newly=$newlyWritten total=${annotated.size} free=$freeBytes")
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
