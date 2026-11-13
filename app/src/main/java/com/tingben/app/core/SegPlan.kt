package com.tingben.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SegMeta(
    val seg: Int,
    val block: Int,
    val ann: Int,
    val fromModel: Boolean?,
    val text: String,
    val speaker: String,
    val instruct: String,
    val wavName: String
)

object SegPlan {
    @Volatile var lastWriteError: String? = null
        private set

    private const val MAP = "segmap.json"
    private const val REDO = "redo.json"

    private fun atomic(f: File, s: String) {
        val ok = AtomicJson.write(f, s)
        lastWriteError = if (ok) null else "制作记录写入失败：" + f.name
    }

    fun writeMap(work: File, segs: List<SegMeta>) {
        try {
            val a = JSONArray()
            segs.forEach { s ->
                a.put(JSONObject()
                    .put("seg", s.seg).put("block", s.block).put("ann", s.ann)
                    .put("fromModel", if (s.fromModel == null) JSONObject.NULL else s.fromModel)
                    .put("text", s.text).put("speaker", s.speaker)
                    .put("instruct", s.instruct).put("wav", s.wavName))
            }
            atomic(File(work, MAP), a.toString())
        } catch (_: Throwable) { }
    }

    fun readMap(work: File): List<SegMeta>? {
        val body = when (val r = AtomicJson.read(File(work, MAP))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { k ->
                val o = a.getJSONObject(k)
                val fm = if (o.isNull("fromModel")) null else o.optBoolean("fromModel", false)
                SegMeta(o.getInt("seg"), o.getInt("block"), o.getInt("ann"), fm,
                    o.optString("text", ""), o.optString("speaker", "旁白"),
                    o.optString("instruct", ""), o.optString("wav", ""))
            }
        } catch (_: Throwable) { null }
    }

    // R297 · 目录页每秒读、Producer 每段读/清、点「重做选中」时批量写，三处并发。
    // 旧实现是无锁的「读-改-写」：UI 勾几十段就逐条 fsync，主线程被拖住；
    // 还会和 Producer 的 clearRedo 互相覆盖，刚标记的段凭空消失。
    private val redoLock = Any()

    fun readRedo(work: File): MutableSet<String> = synchronized(redoLock) {
        val body = when (val r = AtomicJson.read(File(work, REDO))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return@synchronized mutableSetOf()
        }
        try {
            val a = JSONArray(body)
            // R329 · 只认段级标记：早期「整块重做」写的 block:N 写入侧早退场，
            // 旧 redo.json 里若还留着，谁也不会消费它，格子会永久转黄。
            (0 until a.length()).map { a.getString(it) }
                .filter { it.startsWith("seg:") }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf() }
    }

    private fun writeRedo(work: File, s: Set<String>) = synchronized(redoLock) {
        val a = JSONArray(); s.sorted().forEach { a.put(it) }
        atomic(File(work, REDO), a.toString())
    }

    fun markSeg(work: File, seg: Int) = synchronized(redoLock) {
        val r = readRedo(work); r.add("seg:$seg"); writeRedo(work, r)
    }

    /** R297 · 一次勾选 N 段只写一次盘，主线程不再做 N 次 fsync。 */
    fun markSegs(work: File, segs: Collection<Int>) = synchronized(redoLock) {
        val r = readRedo(work); segs.forEach { r.add("seg:$it") }; writeRedo(work, r)
    }

    fun clearRedo(work: File, key: String) = synchronized(redoLock) {
        val r = readRedo(work); if (r.remove(key)) writeRedo(work, r)
    }

    fun pending(work: File): Set<String> = synchronized(redoLock) { readRedo(work) }

    // R329 · 早期「整块重做 / 标注块」留下的 redoSynthesize / redoBlock /
    // markBlock 全工程零调用，写入侧早已退场，连同 block: 标记一起清掉。
}
