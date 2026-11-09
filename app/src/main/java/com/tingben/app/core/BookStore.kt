package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

// @core-part: BookStore
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

