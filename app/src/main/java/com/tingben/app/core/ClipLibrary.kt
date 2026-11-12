package com.tingben.app.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.tingben.app.diag.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * R276 · 首页片段的独立落点与登记层。
 *
 * 用户口径：首页是「贴一段 → 点合成 → 等它做完 → 产物进下方留白列表」，
 * 不是粘上就响。这些片段要：单独进一层目录、不与任何书交叉、能播、
 * 名字取正文前 10 个字、可对它们做删除 / 导出 / 重做。
 *
 * 四层互不相干：cacheDir/trial（试听播完即丢）/ filesDir/work（中间小块）
 * / filesDir/audio（章成品）/ filesDir/clips（首页片段，常驻）。
 * 试听清理只碰第一层，书的批量导出只收 audio/，谁都不收 clips/。
 *
 * 原文存私有 {id}.txt 供「按当前调音台重做」用，绝不进日志；
 * 日志只留长度与短哈希（R225/R257 脱敏口径）。
 */
object ClipLibrary {

    const val NAME_CHARS = 10
    private const val INDEX = "clips.json"
    private const val CLIP_EXPORT_DIR = "首页片段"

    /** running / done / failed；done 还要求 wav 真实存在，防重启把半截当完成。
     *  reason 只存失败原因短码，不落异常原文，重启后仍能回看。 */
    data class Item(
        val id: String,
        val name: String,
        val file: String,
        val status: String,
        val chars: Int,
        val createdAt: Long,
        val reason: String = ""
    ) {
        val audio: File get() = File(file)
        val isDone: Boolean get() = status == "done" && audio.isFile && audio.length() > 44
        val isRunning: Boolean get() = status == "running"
        val exportName: String get() = "${safe(name)}_${id.take(6)}.wav"
    }

    fun dir(c: Context): File = File(c.filesDir, "clips").apply { mkdirs() }
    private fun index(c: Context) = File(dir(c), INDEX)
    private fun textFile(c: Context, id: String) = File(dir(c), "$id.txt")
    fun redoFile(c: Context, id: String) = File(dir(c), "$id.redo.wav")

    /** 展示名：正文前 10 字，换行空白并成空格；文件名再缀短 id 防重名覆盖。 */
    fun displayName(text: String): String =
        text.trim().replace(Regex("[\\r\\n\\t ]+"), " ").take(NAME_CHARS).ifBlank { "片段" }

    private fun safe(s: String): String =
        s.replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "_").trim().take(40).ifBlank { "片段" }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun toJson(it: Item) = JSONObject().apply {
        put("id", it.id); put("name", it.name); put("file", it.file)
        put("status", it.status); put("chars", it.chars); put("createdAt", it.createdAt)
        put("reason", it.reason)
    }

    private fun fromJson(o: JSONObject) = Item(
        o.optString("id"), o.optString("name"), o.optString("file"),
        o.optString("status", "failed"), o.optInt("chars", 0), o.optLong("createdAt", 0L),
        o.optString("reason", "")
    )

    private fun save(c: Context, items: List<Item>): Boolean {
        val a = JSONArray()
        items.forEach { a.put(toJson(it)) }
        return AtomicJson.write(index(c), a.toString())
    }

    /**
     * 登记 + 文件真实存在双判：App 被杀时留在 running 的条目，重启后
     * 既不能假装完成，也不该永远转圈 —— 映射成 failed 可回看、可重做。
     */
    private fun rawList(c: Context): List<Item> {
        val raw = when (val r = AtomicJson.read(index(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return emptyList()
        }
        val a = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Item>()
        for (i in 0 until a.length()) {
            val it = try { fromJson(a.getJSONObject(i)) } catch (_: Throwable) { continue }
            if (it.id.isBlank()) continue
            out += it
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun list(c: Context): List<Item> = rawList(c).map {
        if (it.isRunning && !it.audio.isFile) it.copy(status = "failed") else it
    }

    /** 新建一条 running：先写原文（重做要用），再登记；wav 还没生成。 */
    fun create(c: Context, text: String): Item {
        val id = shortId()
        val name = displayName(text)
        val f = File(dir(c), "${safe(name)}_$id.wav")
        try { textFile(c, id).writeText(text) } catch (_: Throwable) { }
        val it = Item(id, name, f.absolutePath, "running", text.length,
            System.currentTimeMillis())
        save(c, listOf(it) + rawList(c))
        EventLog.ui("CLIP_ADD", "id=${id.take(6)} chars=${text.length} h=${Integer.toHexString(text.hashCode())}")
        return it
    }

    fun markRunning(c: Context, id: String) {
        save(c, rawList(c).map { if (it.id == id) it.copy(status = "running") else it })
    }

    /** 合成收尾：ok 且 wav 到位才算 done，否则 failed；成功清掉旧原因。 */
    fun finish(c: Context, id: String, ok: Boolean) {
        save(c, rawList(c).map {
            if (it.id != id) it
            else {
                val done = ok && it.audio.isFile && it.audio.length() > 44
                it.copy(status = if (done) "done" else "failed",
                    reason = if (done) "" else it.reason)
            }
        })
        EventLog.ui("CLIP_FINISH", "id=${id.take(6)} ok=$ok")
    }

    /** 失败原因只记短码，落盘可回看；异常原文一律不进索引、不进日志。 */
    fun setFailReason(c: Context, id: String, code: String) {
        save(c, rawList(c).map { if (it.id == id) it.copy(reason = code) else it })
    }

    fun reasonOf(t: Throwable?): String {
        val m = t?.message ?: return "write_fail"
        return when {
            m.contains("太长") -> "too_long"
            m.contains("取消") -> "cancelled"
            m.contains("整本书") -> "book_busy"
            m.contains("模型") || m.contains("装载") || m.contains("引擎") -> "engine"
            m.contains("音色") -> "voice"
            m.contains("参考音频") -> "ref"
            m.contains("缺") -> "missing"
            else -> "synth_fail"
        }
    }

    fun reasonText(code: String): String = when (code) {
        "too_long" -> "这段太长"
        "cancelled" -> "已取消"
        "book_busy" -> "整本书正在做，等它结束"
        "engine" -> "模型没装好或还在加载"
        "voice" -> "还没挑音色"
        "ref" -> "参考音频没交给引擎"
        "missing" -> "缺模型文件"
        "write_fail" -> "文件没写成功"
        else -> "没合成出来"
    }

    fun remove(c: Context, ids: Set<String>) {
        val before = rawList(c)
        val dropped = before.filter { it.id in ids }
        save(c, before.filter { it.id !in ids })
        dropped.forEach { it.audio.takeIf { f -> f.isFile }?.let { f ->
            try { f.delete() } catch (_: Throwable) { }
        } }
        ids.forEach { id ->
            listOf(redoFile(c, id), textFile(c, id)).forEach { f ->
                try { if (f.isFile) f.delete() } catch (_: Throwable) { }
            }
        }
        EventLog.ui("CLIP_REMOVE", "n=${ids.size}")
    }

    /** 原文只在这里读，用于重做；绝不写进日志。 */
    fun text(c: Context, id: String): String = try {
        textFile(c, id).takeIf { it.isFile }?.readText().orEmpty()
    } catch (_: Throwable) { "" }

    fun playable(c: Context): List<Item> = list(c).filter { it.isDone }

    /**
     * 批量导出选中片段到设置里记住的目录树，单独一层「首页片段」。
     * 同名同大小跳过、大小变了删旧写新，跟章成品导出同一套幂等口径（R268）。
     */
    fun exportTo(c: Context, tree: Uri?, items: List<Item>): Triple<Int, Int, String> {
        val t = tree ?: return Triple(0, 0, "还没选导出位置")
        val r = c.contentResolver
        val root = try {
            DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        } catch (_: Throwable) { return Triple(0, 0, "上次选的文件夹失效了") }

        fun find(parent: Uri, name: String, isDir: Boolean): Pair<Uri, Long>? = try {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
                t, DocumentsContract.getDocumentId(parent))
            val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE)
            r.query(kids, proj, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getString(1) != name) continue
                    val dir = cur.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    if (dir != isDir) continue
                    return DocumentsContract.buildDocumentUriUsingTree(t, cur.getString(0)) to
                        cur.getLong(3)
                }
            }
            null
        } catch (_: Throwable) { null }

        val parent = find(root, CLIP_EXPORT_DIR, true)?.first ?: try {
            DocumentsContract.createDocument(r, root,
                DocumentsContract.Document.MIME_TYPE_DIR, CLIP_EXPORT_DIR)
        } catch (_: Throwable) { null } ?: return Triple(0, 0, "建不了「$CLIP_EXPORT_DIR」文件夹")

        var ok = 0
        var skipped = 0
        for (it in items) {
            val src = it.audio
            if (!src.isFile) continue
            val old = find(parent, it.exportName, false)
            if (old != null && old.second == src.length()) { skipped++; continue }
            if (old != null) {
                try { DocumentsContract.deleteDocument(r, old.first) } catch (_: Throwable) { }
            }
            val child = try {
                DocumentsContract.createDocument(r, parent, "audio/wav", it.exportName)
            } catch (_: Throwable) { null } ?: continue
            if (AudioOps.copyTo(c, src, child)) ok++
        }
        return Triple(ok, skipped, "")
    }
}
