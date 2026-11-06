package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务的「书签」。
 *
 * R11 之前 Producer 的 _state / busy 全在内存里，进程被系统杀掉或用户退出后
 * 一切归零：块文件其实还在 work/ 里，但界面没有任何入口，用户只能看到空书架，
 * 于是「之前跑的任务没有了」。
 *
 * 这里只存**恢复所需的最小信息**：哪本书、用了哪套参数、跑到哪。
 * 真正的进度以磁盘上的分段 WAV 为准（realProgress 重算），
 * 不信任进程死前写下的那个数字。
 */
data class TaskInfo(
    val bookId: String,
    val title: String,
    val only: Set<Int>?,
    val direct: Boolean,
    val think: Boolean,
    val total: Int,
    val done: Int,
    val phase: String,          // running / interrupted / stopped / done
    val stage: String,
    val updatedAt: Long,
    val output: String? = null,
    // R20 · 本次制作预设随书签走：进程被杀后回来续跑，风格/静音/重试次数不丢。
    // 放末尾且带默认值 ⇒ 所有既有构造点零改动。
    val config: ProduceConfig = ProduceConfig(),
    // R101 · 这次任务是用哪份模式主文件做的。任务页要照实显示，续跑也要先核对
    // 当前模式没被切走（切了会把已做好的 WAV 作废），不能拿「当前设置」冒充。
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
            // 原子落位：写一半被杀不会留下坏 JSON，下次读到的是上一次的完整书签
            part(c).writeText(o.toString())
            if (!part(c).renameTo(file(c))) {
                file(c).writeText(o.toString())
                part(c).delete()
            }
        } catch (_: Throwable) { }
    }

    fun load(c: Context): TaskInfo? = try {
        val f = file(c)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
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
        }
    } catch (_: Throwable) { null }

    fun clear(c: Context) {
        try { file(c).delete(); part(c).delete() } catch (_: Throwable) { }
    }

    /**
     * R94 · run() 里作废 WAV 用的模式指纹，抽到这里做唯一算法真源：
     * 界面预判和真正开工读的是同一个值，不会出现两处算法慢慢漂移。
     */
    fun expectedModeStamp(c: Context): String = sha(
        listOf(ModelStore.currentMode(c).id, Prefs.speakerName(c), Prefs.cvStyle(c), VoiceRef.fingerprint(c))
            .joinToString("||"))

    /**
     * R94 · 续跑对拍：界面写「已做好 X 段、不用重算」之前，先问一句
     * 「点继续时 run() 会不会把这几段 WAV 作废」。会作废就返回原因，
     * realProgress 直接报 0，不把即将被删的 WAV 算成已完成。
     * 两枚指纹与 run() 一一对应：voice_stamp（含旧版 legacy 兼容）、mode_stamp。
     */
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

    /** 没做完的任务（供书架 / 常驻条显示「继续」）。 */
    fun active(c: Context): TaskInfo? = load(c)?.takeIf { it.phase != "done" }

    /**
     * 以磁盘实况重算进度，不信任死前记下的 done。
     * 合成阶段数 wav；返回「已完成多少朗读段」的近似值供 UI 显示，
     * 真正的跳过逻辑在 Producer.run() 里按块判断。
     */
    fun realProgress(c: Context, t: TaskInfo): Int {
        // R94 · 先对拍：点继续会作废的 WAV，界面上一律不算「已做好」。
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
