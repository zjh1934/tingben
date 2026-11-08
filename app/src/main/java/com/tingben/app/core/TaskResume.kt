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
    val modeId: String = "",
    val splitRule: Int = 1
)

object TaskResume {
    // 1 = 旧整本切分；2 = 逐行多形态组计分；
    // 3 = 章节表落盘 + 规则集自动选优（R138 批次 B）。改切章口径时 +1。
    // 旧任务不是一律卡死：老书的章节表会锁 legacy_v2，章号与旧任务一致，
    // 可以安全提升版本继续跑；只有用户真的换过规则才要求手动确认。
    const val SPLIT_RULE = 3

    private fun file(c: Context) = File(c.filesDir, "task_active.json")
    private fun listFile(c: Context) = File(c.filesDir, "tasks.json")
    private fun queueFile(c: Context) = File(c.filesDir, "queue.json")
    private fun pauseFile(c: Context) = File(c.filesDir, "queue_paused.json")

    // 队列里存的就是完整 TaskInfo（phase = "queued"），复用同一套 encode/decode，
    // 不另造一套字段，避免排队项和任务项的口径分家。
    fun queue(c: Context): List<TaskInfo> {
        val body = when (val r = AtomicJson.read(queueFile(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return emptyList()
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { decode(a.getJSONObject(it)) }
        } catch (_: Throwable) { emptyList() }
    }

    fun queueSize(c: Context): Int = queue(c).size

    fun enqueue(c: Context, t: TaskInfo) {
        try {
            if (AtomicJson.read(queueFile(c)) is AtomicJson.Read.Corrupt) return
            val all = queue(c).filter { it.bookId != t.bookId }.toMutableList()
            all.add(t.copy(phase = "queued", stage = "排队中",
                updatedAt = System.currentTimeMillis()))
            val a = JSONArray(); all.forEach { a.put(encode(it)) }
            AtomicJson.write(queueFile(c), a.toString())
        } catch (_: Throwable) { }
    }

    fun dequeue(c: Context, bookId: String) {
        try {
            val keep = queue(c).filter { it.bookId != bookId }
            if (keep.isEmpty()) { AtomicJson.delete(queueFile(c)); return }
            val a = JSONArray(); keep.forEach { a.put(encode(it)) }
            AtomicJson.write(queueFile(c), a.toString())
        } catch (_: Throwable) { }
    }

    fun clearQueue(c: Context) { AtomicJson.delete(queueFile(c)) }

    // 暂停状态落盘：进程被杀后重开软件，不会把用户主动停掉的队列又跑起来。
    fun queuePaused(c: Context): Boolean = when (val r = AtomicJson.read(pauseFile(c))) {
        is AtomicJson.Read.Ok -> r.text.trim() == "1"
        else -> false
    }

    fun setQueuePaused(c: Context, v: Boolean) {
        if (v) AtomicJson.write(pauseFile(c), "1")
        else AtomicJson.delete(pauseFile(c))
    }

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
            .put("splitRule", t.splitRule)
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
            o.optString("modeId", ""), o.optInt("splitRule", 1))
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

    // 返回 null = 可以自动续跑；非 null = 不自动跑的原因（直接给用户看）。
    // 只拦「用户主动停 / 失败中断 / 切章规则变了 / 书没了 / 模型没齐 / 调音改过」。
    // 进程被杀或崩溃留下的 interrupted 任务不拦，正是要自动接上的那种。
    fun autoResumeBlockReason(c: Context, t: TaskInfo): String? {
        if (t.phase == "stopped")
            return "上次是你手动停的，没自动续跑；在任务页点「继续制作」"
        if (t.phase != "interrupted")
            return "上次任务状态不是「中断」，没自动续跑"
        if (t.stage in listOf("没完成", "出错了", "已停止"))
            return "上次没做完（${t.stage}），没自动续跑；在任务页点「继续制作」"
        val book = BookStore.list(c).find { it.id == t.bookId }
            ?: return "这本书已从书架删掉，没法续跑"
        if (t.splitRule != SPLIT_RULE) {
            // 先确保章节表存在：有成品/任务的老书会锁 legacy_v2，章号不变 → 安全迁移
            ChapterPlan.resolve(c, book)
            val rid = ChapterPlan.currentRuleId(c, t.bookId)
            if (rid == TocRules.LEGACY || rid == ChapterPlan.EPUB) {
                migrateSplit(c, t.bookId)
            } else {
                return "分章规则变过（现在是${ChapterPlan.ruleName(rid)}），章号会变，" +
                    "请在目录页点「重新分章」后手动继续"
            }
        }
        ModelStore.synthesisBlockReason(c)?.let { return "模型没齐（$it），没自动续跑" }
        invalidationReason(c, t)?.let { return "$it，没自动续跑；请在任务页手动确认" }
        return null
    }

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

    /**
     * R138 · 旧断点迁移：只把 splitRule 提到当前值，不动章号、不动 only、不动音频。
     * 前提是调用方已确认这本书的章节表锁在 legacy_v2 / epub（章号与旧任务一致）。
     * 用户显式「重新分章」换过规则的书不走这里 —— 那种情况章号真的变了，
     * 必须让用户重新确认，不能假装把断点搬过去。
     */
    fun migrateSplit(c: Context, bookId: String): Boolean {
        val t = byBook(c, bookId) ?: return false
        if (t.splitRule >= SPLIT_RULE) return true
        save(c, t.copy(splitRule = SPLIT_RULE))
        EventLog.log(EventLog.Kind.PIPELINE, "SPLIT_MIGRATE",
            "旧任务切章口径升级到 $SPLIT_RULE，章号与音频保持不变 book=$bookId")
        return true
    }

    fun realProgress(c: Context, t: TaskInfo): Int {
        if (invalidationReason(c, t) != null) return 0
        val book = BookStore.list(c).find { it.id == t.bookId } ?: return t.done
        return try {
            val (_, chunks) = ChapterPlan.resolve(c, book)
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
