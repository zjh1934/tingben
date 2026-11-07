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
    private const val FLAG = "flag_%04d.json"

    private fun atomic(f: File, s: String) {
        val ok = AtomicJson.write(f, s)
        lastWriteError = if (ok) null else "制作记录写入失败：" + f.name
    }

    fun writeFlags(work: File, block: Int, flags: List<Boolean>) {
        try {
            val a = JSONArray(); flags.forEach { a.put(it) }
            atomic(File(work, FLAG.format(block)), a.toString())
        } catch (_: Throwable) { }
    }

    fun readFlags(work: File, block: Int): List<Boolean>? {
        val body = when (val r = AtomicJson.read(File(work, FLAG.format(block)))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { a.getBoolean(it) }
        } catch (_: Throwable) { null }
    }

    private fun blockSrc(work: File, block: Int): Boolean? = try {
        val f = File(work, "ann_%04d.src".format(block))
        if (!f.exists()) null else when (f.readText().trim().uppercase()) {
            "MODEL" -> true
            "FILL" -> false
            else -> null
        }
    } catch (_: Throwable) { null }

    fun sourceOf(work: File, block: Int, ann: Int): Boolean? {
        val fl = readFlags(work, block)
        if (fl != null) return fl.getOrNull(ann)
        return blockSrc(work, block)
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

    fun readRedo(work: File): MutableSet<String> {
        val body = when (val r = AtomicJson.read(File(work, REDO))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return mutableSetOf()
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).map { a.getString(it) }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf() }
    }

    private fun writeRedo(work: File, s: Set<String>) {
        val a = JSONArray(); s.sorted().forEach { a.put(it) }
        atomic(File(work, REDO), a.toString())
    }

    fun markSeg(work: File, seg: Int) {
        val r = readRedo(work); r.add("seg:$seg"); writeRedo(work, r)
    }

    fun markBlock(work: File, block: Int) {
        val r = readRedo(work); r.add("block:$block"); writeRedo(work, r)
    }

    fun clearRedo(work: File, key: String) {
        val r = readRedo(work); if (r.remove(key)) writeRedo(work, r)
    }

    fun pending(work: File): Set<String> = readRedo(work)

    fun redoSynthesize(work: File, s: SegMeta): Boolean {
        val w = File(work, s.wavName)
        try { if (w.exists() && !w.delete()) return false } catch (_: Throwable) { return false }
        markSeg(work, s.seg)
        return true
    }

    fun redoBlock(work: File, block: Int, segs: List<SegMeta>): Int {
        var killed = 0
        segs.filter { it.block == block }.forEach { s ->
            val w = File(work, s.wavName)
            try { if (w.exists() && w.delete()) killed++ } catch (_: Throwable) { }
        }
        listOf("ann_%04d.json".format(block), "ann_%04d.src".format(block),
            FLAG.format(block), "ann_%04d.json.part".format(block)).forEach { n ->
            try { File(work, n).delete() } catch (_: Throwable) { }
        }
        markBlock(work, block)
        return killed
    }

    data class Verdict(val model: Int, val fill: Int, val unknown: Int, val total: Int) {
        val effective: Boolean get() = model > 0
        val label: String
            get() = when {
                total == 0 -> "还没落盘，跑起来后这里会给出结论"
                model == 0 && fill > 0 -> "文本处理没起作用：${fill} 段全是按原文直读"
                model == 0 && unknown > 0 -> "${unknown} 段是旧缓存，没记来源，无法判定"
                model > 0 -> "文本处理已生效：${model}/${total} 段用了模型分段"
                else -> "暂无数据"
            }
    }

    fun verdict(segs: List<SegMeta>): Verdict = Verdict(
        segs.count { it.fromModel == true },
        segs.count { it.fromModel == false },
        segs.count { it.fromModel == null },
        segs.size
    )
}
