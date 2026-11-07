package com.tingben.app.core

import android.content.Context
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
    private fun part(c: Context) = File(c.filesDir, "task_active.json.part")

    fun save(c: Context, t: TaskInfo) {
        try {
            val o = JSONObject()
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
            val a = JSONArray()
            t.only?.sorted()?.forEach { a.put(it) }
            o.put("only", a)
            o.put("onlyNull", t.only == null)
            AtomicJson.write(file(c), o.toString())
        } catch (_: Throwable) { }
    }

    fun load(c: Context): TaskInfo? {
        val body = when (val r = AtomicJson.read(file(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("only")
            val only = if (o.optBoolean("onlyNull", true) || arr == null) null else
                (0 until arr.length()).map { arr.getInt(it) }.toSet()
            val out = o.optString("output", "").ifBlank { null }
            TaskInfo(
                o.getString("bookId"), o.optString("title", "未命名"), only,
                o.optBoolean("direct", false), o.optBoolean("think", false),
                o.optInt("total", 0), o.optInt("done", 0),
                o.optString("phase", "interrupted"), o.optString("stage", ""),
                o.optLong("updatedAt", 0L), out,
                ProduceConfig.fromJson(o.optJSONObject("config")),
                o.optString("modeId", ""))
        } catch (_: Throwable) { null }
    }

    fun clear(c: Context) {
        try { file(c).delete(); part(c).delete() } catch (_: Throwable) { }
    }

    fun expectedModeStamp(c: Context): String = sha(
        listOf(ModelStore.currentMode(c).id, Prefs.speakerName(c), Prefs.cvStyle(c), VoiceRef.fingerprint(c))
            .joinToString("||"))

    fun invalidationReason(c: Context, t: TaskInfo): String? = try {
        val w = AnnCache.dirFor(c, t.bookId, t.direct, t.think, t.only)
        val modeFile = File(w, "mode_stamp.txt")
        if (modeFile.exists() && modeFile.readText().trim() != expectedModeStamp(c)) {
            "换过模式或音色输入，接着做会按新设置重出声"
        } else {
            val voiceFile = File(w, "voice_stamp.txt")
            val oldVoice = if (voiceFile.exists()) voiceFile.readText().trim() else ""
            if (voiceFile.exists() && oldVoice != Prefs.voiceStamp(c) &&
                oldVoice != Prefs.legacyVoiceStamp(c))
                "改过调音台设置，接着做会按新设置重出声" else null
        }
    } catch (_: Throwable) { null }

    fun active(c: Context): TaskInfo? = load(c)?.takeIf { it.phase != "done" }

    fun realProgress(c: Context, t: TaskInfo): Int {
        if (invalidationReason(c, t) != null) return 0
        val book = BookStore.list(c).find { it.id == t.bookId } ?: return t.done
        return try {
            val (_, chunks) = ChapterSplitter.split(BookStore.read(c, book))
            val selected = if (t.only == null) chunks.size else chunks.count { it.chapter in t.only!! }
            val w = AnnCache.dirFor(c, t.bookId, t.direct, t.think, t.only)
            val ann = AnnCache.count(w)
            val wav = w.listFiles()?.count { f ->
                f.isFile && (f.name.startsWith("chunk_") || f.name.startsWith("direct_")) &&
                        f.name.endsWith(".wav") && f.length() > 44
            } ?: 0
            minOf(selected, if (wav > 0) maxOf(wav, ann) else ann)
        } catch (_: Throwable) { t.done }
    }
}
