package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TaskInfo(
    val bookId: String,
    val title: String,
    val only: Set<Int>?,
    val direct: Boolean,
    val think: Boolean,
    val total: Int,
    val done: Int,
    val phase: String,
    val stage: String,
    val updatedAt: Long,
    val output: String? = null,
    val config: ProduceConfig = ProduceConfig(),
    val modeId: String = ""
)

object TaskResume {
    private fun file(c: Context) = File(c.filesDir, "task_active.json")
    private fun listFile(c: Context) = File(c.filesDir, "tasks.json")

    private fun encode(t: TaskInfo): JSONObject {
        val a = JSONArray()
        t.only?.sorted()?.forEach { a.put(it) }
        return JSONObject()
            .put("bookId", t.bookId)
            .put("title", t.title)
            .put("direct", t.direct)
            .put("think", t.think)
            .put("total", t.total)
            .put("done", t.done)
            .put("phase", t.phase)
            .put("stage", t.stage)
            .put("updatedAt", t.updatedAt)
            .put("output", t.output ?: "")
            .put("config", t.config.toJson())
            .put("modeId", t.modeId)
            .put("only", a)
            .put("onlyNull", t.only == null)
    }

    private fun decode(o: JSONObject): TaskInfo {
        val arr = o.optJSONArray("only")
        val only = if (o.optBoolean("onlyNull", true) || arr == null) null else
            (0 until arr.length()).map { arr.getInt(it) }.toSet()
        val out = o.optString("output", "").ifBlank { null }
        return TaskInfo(
            o.getString("bookId"), o.optString("title", "未命名"), only,
            o.optBoolean("direct", false), o.optBoolean("think", false),
            o.optInt("total", 0), o.optInt("done", 0),
            o.optString("phase", "interrupted"), o.optString("stage", ""),
            o.optLong("updatedAt", 0L), out,
            ProduceConfig.fromJson(o.optJSONObject("config")),
            o.optString("modeId", ""))
    }

    private fun legacy(c: Context): TaskInfo? {
        val body = when (val r = AtomicJson.read(file(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try { decode(JSONObject(body)) } catch (_: Throwable) { null }
    }

    private fun readAll(c: Context): List<TaskInfo> {
        val out = mutableListOf<TaskInfo>()
        val body = when (val r = AtomicJson.read(listFile(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> ""
        }
        if (body.isNotBlank()) {
            try {
                val a = JSONArray(body)
                for (i in 0 until a.length()) out.add(decode(a.getJSONObject(i)))
            } catch (_: Throwable) { }
        }
        val old = legacy(c)
        if (old != null && out.none { it.bookId == old.bookId }) out.add(old)
        return out.sortedByDescending { it.updatedAt }
    }

    fun list(c: Context): List<TaskInfo> = readAll(c)

    fun byBook(c: Context, bookId: String): TaskInfo? =
        readAll(c).firstOrNull { it.bookId == bookId }

    fun load(c: Context): TaskInfo? = readAll(c).firstOrNull()

    fun save(c: Context, t: TaskInfo) {
        try {
            if (AtomicJson.read(listFile(c)) is AtomicJson.Read.Corrupt) return
            val o = encode(t)
            AtomicJson.write(file(c), o.toString())
            val all = readAll(c).toMutableList()
            all.removeAll { it.bookId == t.bookId }
            all.add(0, t)
            val a = JSONArray()
            all.forEach { a.put(encode(it)) }
            AtomicJson.write(listFile(c), a.toString())
        } catch (_: Throwable) { }
    }

    fun remove(c: Context, bookId: String) {
        try {
            if (AtomicJson.read(listFile(c)) is AtomicJson.Read.Corrupt) return
            val keep = readAll(c).filter { it.bookId != bookId }
            if (keep.isEmpty()) {
                AtomicJson.delete(listFile(c))
                AtomicJson.delete(file(c))
            } else {
                val a = JSONArray()
                keep.forEach { a.put(encode(it)) }
                if (!AtomicJson.write(listFile(c), a.toString())) return
                AtomicJson.delete(file(c))
            }
        } catch (_: Throwable) { }
    }

    fun clear(c: Context) {
        try { AtomicJson.delete(file(c)); AtomicJson.delete(listFile(c)) } catch (_: Throwable) { }
    }

    fun expectedModeStamp(c: Context): String = sha(
        listOf(ModelStore.currentMode(c).id, Prefs.speakerName(c), Prefs.cvStyle(c), VoiceRef.fingerprint(c))
            .joinToString("||"))

    fun invalidationReason(c: Context, t: TaskInfo): String? = try {
        val w = AnnCache.dirFor(c, t.bookId, t.only)
        val modeFile = File(w, "mode_stamp.txt")
        if (modeFile.exists() && modeFile.readText().trim() != expectedModeStamp(c)) {
            "换过模式或音色输入，接着做会按新设置重出声"
        } else {
            val voiceFile = File(w, "voice_stamp.txt")
            val oldVoice = if (voiceFile.exists()) voiceFile.readText().trim() else ""
            val currentVoice = Prefs.voiceStamp(c)
            if (voiceFile.exists() && oldVoice != currentVoice &&
                oldVoice != Prefs.legacyVoiceStamp(c)) {
                "改过调音台设置，接着做会按新设置重出声"
            } else {
                if (voiceFile.exists() && oldVoice != currentVoice) {
                    try { voiceFile.writeText(currentVoice) } catch (_: Throwable) { }
                    EventLog.log(EventLog.Kind.PIPELINE, "VOICE_STAMP_MIGRATE",
                        "旧指纹写回新口径，已生成段保持不变")
                }
                null
            }
        }
    } catch (_: Throwable) { null }

    fun active(c: Context): TaskInfo? = readAll(c).firstOrNull { it.phase != "done" }

    fun currentChapter(c: Context, t: TaskInfo): Int? {
        val body = when (val r = AtomicJson.read(File(AnnCache.dirFor(c, t.bookId, t.only),
            "seg_chapters.json"))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val a = JSONArray(body)
            if (a.length() == 0) null else
                a.getInt(realProgress(c, t).coerceIn(0, a.length() - 1))
        } catch (_: Throwable) { null }
    }

    fun realProgress(c: Context, t: TaskInfo): Int {
        if (invalidationReason(c, t) != null) return 0
        val book = BookStore.list(c).find { it.id == t.bookId } ?: return t.done
        return try {
            val (_, chunks) = ChapterSplitter.split(BookStore.read(c, book))
            val selected = if (t.only == null) chunks.size else chunks.count { it.chapter in t.only!! }
            val w = AnnCache.dirFor(c, t.bookId, t.only)
            val ann = AnnCache.count(w)
            val wav = w.listFiles()?.count { f ->
                f.isFile && (f.name.startsWith("chunk_") || f.name.startsWith("direct_")) &&
                        f.name.endsWith(".wav") && f.length() > 44
            } ?: 0
            val doneMap = AudioTree.readDone(w)
            val covered = if (doneMap.isEmpty()) 0 else {
                val body = when (val r = AtomicJson.read(File(w, "seg_chapters.json"))) {
                    is AtomicJson.Read.Ok -> r.text
                    else -> ""
                }
                try {
                    val a = JSONArray(body)
                    (0 until a.length()).count { a.optInt(it, -1) in doneMap }
                } catch (_: Throwable) { 0 }
            }
            val base = minOf(selected, if (wav > 0) maxOf(wav, ann) else ann)
            minOf(selected, base + covered)
        } catch (_: Throwable) { t.done }
    }
}
