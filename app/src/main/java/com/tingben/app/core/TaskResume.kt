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
    val splitRule: Int = 1,
    /** R172 · 建任务时的章节表代数；重新分章后与新表不一致就失效。老任务默认 0。 */
    val planEpoch: Int = 0
)

object TaskResume {
    // 1 = 旧整本切分；2 = 逐行多形态组计分；
    // 3 = 章节表落盘 + 规则集自动选优（R138 批次 B）。改切章口径时 +1。
    // 旧任务不是一律卡死：老书的章节表会锁 legacy_v2，章号与旧任务一致，
    // 可以安全提升版本继续跑；只有用户真的换过规则才要求手动确认。
    const val SPLIT_RULE = 3

    // R280 · invalidationReason 是纯查询，恢复流程一轮里会反复调它；
    // 同一进程同一工作目录只记一条 MODE_CHANGED_KEEP，别把诊断日志刷成 5 条。
    // 不回写模式指纹文件 —— 它是 VoiceCache 反推旧音色套的真值源。
    private val notedModeChanged: MutableSet<String> =
        java.util.Collections.synchronizedSet(HashSet())

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
    // R213 · AtomicJson.legal 只放行 { / [ 开头的 JSON，原来这里写纯 "1"
    // 被静默拒写：1.1.22 真机日志 05:07:38 记了「QUEUE_PAUSE 队列停在原地」，
    // 05:09:35 诊断快照却是「暂停=false」。标记从来没落过盘。
    fun queuePaused(c: Context): Boolean {
        // 兼容早期裸写留下的纯标量文件（AtomicJson.read 会把它判成 Corrupt）
        try {
            val f = pauseFile(c)
            if (f.isFile && f.readText().trim() == "1") return true
        } catch (_: Throwable) { }
        return when (val r = AtomicJson.read(pauseFile(c))) {
            is AtomicJson.Read.Ok -> try {
                JSONObject(r.text).optInt("paused", 0) == 1
            } catch (_: Throwable) { r.text.trim() == "1" }
            else -> false
        }
    }

    fun setQueuePaused(c: Context, v: Boolean) {
        if (v) AtomicJson.write(pauseFile(c),
            JSONObject().put("paused", 1).toString())
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
            .put("planEpoch", t.planEpoch)
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
            o.optString("modeId", ""), o.optInt("splitRule", 1),
            o.optInt("planEpoch", 0))
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
        // R172 · 章节表代数不一致 = 用户重新分过章：旧 only / total / 分段全按旧
        // 章号，直接续跑会合成错章。这里一拦，autoResumeBlockReason、任务页提示、
        // realProgress（归零）三处同时生效，不会再显示对不上的进度数字。
        if (t.planEpoch != ChapterPlan.epoch(c, t.bookId))
            "重新分章后章号变了，旧任务与已生成的分段不能再接着用，请重新选章制作"
        else {
            val w = AnnCache.dirFor(c, t.bookId, t.only)
            val splitFile = File(w, "split_stamp.txt")
            val oldSplit = if (splitFile.exists()) splitFile.readText().trim() else ""
            val hasWav = w.listFiles { _, n -> n.endsWith(".wav") }?.isNotEmpty() == true
            if (hasWav && oldSplit != TextSplitter.SPLIT_EPOCH.toString()) {
                "切分粒度改过，旧分段的段号对不上了，请从头重做（原来那套留着不删）"
            } else {
            // R220 · 调音台 / 模式改过不再算「任务失效」。旧逻辑返回一句话，
            // realProgress 当场归零、自动续跑被拦，用户看着就像全没了。
            // 现在 VoiceCache 把已做好的段认领保留，这里只记一笔并写回 stamp，
            // 点继续就按新参数做剩下的段。
            val modeFile = File(w, "mode_stamp.txt")
            val modeNow = expectedModeStamp(c)
            if (modeFile.exists() && modeFile.readText().trim() != modeNow) {
                // 纯查询被恢复流程多处反复调用：按「工作目录 + 当前指纹」只记一条，
                // 用户真改了设置会换指纹，仍会再记一条；绝不回写 stamp 文件。
                val first = synchronized(notedModeChanged) {
                    notedModeChanged.add(w.absolutePath + "|" + modeNow)
                }
                if (first) {
                    EventLog.log(EventLog.Kind.PIPELINE, "MODE_CHANGED_KEEP",
                        "换过模式/音色输入，已做好的段与章节留着，后面按新设置继续")
                }
                null
            } else {
                val voiceFile = File(w, "voice_stamp.txt")
                val oldVoice = if (voiceFile.exists()) voiceFile.readText().trim() else ""
                val currentVoice = Prefs.voiceStamp(c)
                val legacy = oldVoice != Prefs.legacyVoiceStamp(c)
                if (voiceFile.exists() && oldVoice != currentVoice) {
                    try { voiceFile.writeText(currentVoice) } catch (_: Throwable) { }
                    EventLog.log(EventLog.Kind.PIPELINE,
                        if (legacy) "VOICE_CHANGED_KEEP" else "VOICE_STAMP_MIGRATE",
                        if (legacy)
                            "改过调音台设置，已做好的段与章节不丢，后面按新参数继续"
                        else "旧指纹写回新口径，已生成段保持不变")
                }
                null
            }
            }
        }
    } catch (_: Throwable) { null }

    fun active(c: Context): TaskInfo? = readAll(c).firstOrNull { it.phase != "done" }

    // 返回 null = 可以自动续跑；非 null = 不自动跑的原因（直接给用户看）。
    // 只拦「用户主动停 / 失败中断 / 切章规则变了 / 书没了 / 模型没齐」。
    // R220 · 调过调音台不再拦：已做好的段认领保留，点继续按新参数接着做。
    // 进程被杀或崩溃留下的 interrupted 任务不拦，正是要自动接上的那种。
    fun autoResumeBlockReason(c: Context, t: TaskInfo): String? {
        if (t.phase == "reconfirm")
            return "重新分章后章号变了，请在目录页重新选章制作"
        // R175 · P2-5：配置/系统原因「没开始」，不是手动停、也不是意外中断。
        // 明确拦住自动续跑，避免开软件反复弹窗、反复重试。
        if (t.phase == "blocked")
            return "上次制作没能开始（${t.stage}），没自动续跑；在任务页点「继续制作」重试"
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
