package com.tingben.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R28 · 段级映射与「重做这一段」的失效层。
 *
 * 移植自 ReaderVoice-Mobile `scheduler`（MIT，声明见 docs/THIRD_PARTY_NOTICES.md）：
 *  - `RenderUnitRef`：稳定段 id（book:ch:seg）+ 内容指纹 cacheKey
 *  - `Scheduler.plan()`：命中缓存才跳过；`invalidate(unitIds)` 只标脏、禁整章/整本清缓存
 *  - `AudioCacheScanner`：只认 wav、按序、忽略其它文件
 *
 * 改写点（不是照抄）：
 *  - 不引 Room / coroutine 状态机：本项目是线性 Producer，脏标记就落 `redo.json` 一个文件
 *  - 「重做」靠删对应文件让既有跳过逻辑自然失效，不改 `Producer.start` 签名 / `bucket()` /
 *    `TaskResume` 书签 / native / prompt v2 —— 这是用户点名过的「改一处联动很多处」
 *  - 段 id 直接用 work 目录里的 wav 索引，因为合成循环本来就用它命名
 *
 * R28 顺带修一个旧账：看台以前把 `ann_%04d`（**块**索引）和
 * `chunk_%04d`（**段**索引）按同一个 i 并排画格。一块常切成好几段，
 * 所以「第 N 格」既不是第 N 块也不是第 N 段。segmap 是唯一真实来源。
 */

data class SegMeta(
    val seg: Int,
    val block: Int,
    val ann: Int,
    /** null = 旧缓存没记来源，不猜、不冒充已标注。 */
    val fromModel: Boolean?,
    val text: String,
    val speaker: String,
    val instruct: String,
    val wavName: String
)

object SegPlan {
    private const val MAP = "segmap.json"
    private const val REDO = "redo.json"
    private const val FLAG = "flag_%04d.json"

    private fun atomic(f: File, s: String) {
        try {
            val p = File(f.parentFile, f.name + ".part")
            p.writeText(s)
            if (!p.renameTo(f)) { f.writeText(s); p.delete() }
        } catch (_: Throwable) { }
    }

    /** 每块「逐条来源」落盘。R27 只在内存里算 srcFlags，续跑后全变 UNKNOWN。 */
    fun writeFlags(work: File, block: Int, flags: List<Boolean>) {
        try {
            val a = JSONArray(); flags.forEach { a.put(it) }
            atomic(File(work, FLAG.format(block)), a.toString())
        } catch (_: Throwable) { }
    }

    fun readFlags(work: File, block: Int): List<Boolean>? = try {
        val f = File(work, FLAG.format(block))
        if (!f.exists()) null else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getBoolean(it) }
        }
    } catch (_: Throwable) { null }

    /** 整块级兜底：MODEL 块里也可能夹补齐段，所以优先用 flags。 */
    private fun blockSrc(work: File, block: Int): Boolean? = try {
        val f = File(work, "ann_%04d.src".format(block))
        if (!f.exists()) null else when (f.readText().trim().uppercase()) {
            "MODEL" -> true
            "FILL" -> false
            else -> null
        }
    } catch (_: Throwable) { null }

    /** 从磁盘重建某块第 ann 条的来源；旧缓存返回 null。 */
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

    fun readMap(work: File): List<SegMeta>? = try {
        val f = File(work, MAP)
        if (!f.exists()) null else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { k ->
                val o = a.getJSONObject(k)
                val fm = if (o.isNull("fromModel")) null else o.optBoolean("fromModel", false)
                SegMeta(o.getInt("seg"), o.getInt("block"), o.getInt("ann"), fm,
                    o.optString("text", ""), o.optString("speaker", "旁白"),
                    o.optString("instruct", ""), o.optString("wav", ""))
            }
        }
    } catch (_: Throwable) { null }

    // ── 脏标记：语义等同 Scheduler.invalidate(unitIds)，只标脏、不删别人的缓存 ──
    fun readRedo(work: File): MutableSet<String> = try {
        val f = File(work, REDO)
        if (!f.exists()) mutableSetOf() else {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getString(it) }.toMutableSet()
        }
    } catch (_: Throwable) { mutableSetOf() }

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

    /**
     * 重合成这一段：只删该段 wav，其余段与标注一律不动。
     * 合成循环见 wav 不存在就重合成；标注阶段 ann 全在 → 不会重跑 4B。
     */
    fun redoSynthesize(work: File, s: SegMeta): Boolean {
        val w = File(work, s.wavName)
        try { if (w.exists() && !w.delete()) return false } catch (_: Throwable) { return false }
        markSeg(work, s.seg)
        return true
    }

    /**
     * 重新标注本块：删该块 ann/flag/src + 该块所有段的 wav，其余块不动。
     * 续跑时该块 ann 不存在 → 重跑 4B；对应段 wav 不存在 → 重合成。
     * 禁整章 / 整本清（Scheduler §5 不变量）。
     */
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

    /** 一眼判定：模型真标注 / 直读 / 旧缓存未知 各几段。 */
    data class Verdict(val model: Int, val fill: Int, val unknown: Int, val total: Int) {
        val effective: Boolean get() = model > 0
        val label: String
            get() = when {
                total == 0 -> "还没落盘，跑起来后这里会给出结论"
                model == 0 && fill > 0 -> "语言模型没起作用：${fill} 段全是按原文直读"
                model == 0 && unknown > 0 -> "${unknown} 段是旧缓存，没记来源，无法判定"
                model > 0 -> "语言模型已生效：${model}/${total} 段用了模型标注"
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
