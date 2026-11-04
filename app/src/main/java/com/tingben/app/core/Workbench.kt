package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * R18 建、R28 重写 · 工作看台的分段数据。
 *
 * R28 修一个旧账：以前把 `ann_%04d.json`（**块**索引）和 `chunk_%04d.wav`（**段**索引）
 * 按同一个 i 并排画格。一块常切成好几段，所以「第 N 格」既不是第 N 块也不是第 N 段，
 * 颜色（来自块）和声音（来自段）也永远对不上。现在以 `SegPlan.segmap.json` 为唯一真实
 * 来源；没有 segmap 的旧缓存才降级扫目录，并一律标 UNKNOWN，不冒充已标注。
 */
data class SegRow(
    val index: Int,
    val block: Int,
    val ann: Int,
    val fromModel: Boolean?,
    val wav: File?,
    val wavName: String,
    val text: String,
    val speaker: String,
    val instruct: String,
    val pendingRedo: Boolean
) {
    val hasAudio: Boolean get() = wav != null
    val src: String get() = when (fromModel) {
        true -> "MODEL"
        false -> "FILL"
        null -> "UNKNOWN"
    }
}

object Workbench {
    private val annRe = Regex("""^ann_(\d{4})\.json$""")
    private val wavRe = Regex("""^(?:chunk|direct)_(\d{4})\.wav$""")

    fun workDir(c: Context, t: TaskInfo?): File? = t?.let {
        AnnCache.dirFor(c, it.bookId, it.direct, it.think, it.only)
    }

    fun segments(c: Context, t: TaskInfo?): List<SegRow> {
        val work = workDir(c, t) ?: return emptyList()
        val redo = SegPlan.pending(work)
        val mapped = SegPlan.readMap(work)
        if (mapped != null) {
            return mapped.take(60).map { m ->
                val f = File(work, m.wavName)
                SegRow(m.seg, m.block, m.ann, m.fromModel,
                    f.takeIf { it.exists() && it.length() > 44 }, m.wavName,
                    m.text, m.speaker, m.instruct,
                    "seg:${m.seg}" in redo || "block:${m.block}" in redo)
            }
        }

        // 旧缓存降级：仍按目录扫，但明确告诉 UI「这是旧缓存，来源未知」。
        val files = try { work.listFiles()?.toList() ?: emptyList() } catch (_: Throwable) { return emptyList() }
        val annSet = mutableSetOf<Int>()
        val wavMap = mutableMapOf<Int, File>()
        files.forEach { f ->
            annRe.matchEntire(f.name)?.let { annSet.add(it.groupValues[1].toInt()) }
            wavRe.matchEntire(f.name)?.let {
                val i = it.groupValues[1].toInt()
                if (f.length() > 44) wavMap[i] = f
            }
        }
        val last = maxOf(
            (annSet.maxOrNull() ?: -1), (wavMap.keys.maxOrNull() ?: -1),
            ((t?.total ?: 1) - 1)
        ).coerceAtLeast(0)
        val upTo = last.coerceAtMost(59)

        return (0..upTo).map { i ->
            val annFile = File(work, "ann_%04d.json".format(i))
            val preview = if (annFile.exists()) {
                try {
                    val a = JSONArray(annFile.readText())
                    (0 until a.length()).joinToString("") { k ->
                        a.getJSONObject(k).optString("text", "")
                    }.take(64)
                } catch (_: Throwable) { "" }
            } else if (t?.direct == true) "直接朗读（原文照读，不产出标注）" else ""
            val w = wavMap[i]
            SegRow(i, i, 0, null, w,
                w?.name ?: "chunk_%04d.wav".format(i), preview, "旁白", "", false)
        }
    }

    fun remainingCount(c: Context, t: TaskInfo?): Int {
        val work = workDir(c, t) ?: return 0
        val mapped = SegPlan.readMap(work)
        if (mapped != null) return mapped.count { m ->
            !File(work, m.wavName).let { it.exists() && it.length() > 44 }
        }
        val files = try { work.listFiles()?.toList() ?: emptyList() } catch (_: Throwable) { return 0 }
        val last = files.mapNotNull { f ->
            annRe.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: wavRe.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: -1
        return ((t?.total ?: 1) - 1 - last).coerceAtLeast(0)
    }

    /** 判定条用：模型真标注 / 直读 / 未知。没有 segmap 时返回 null，UI 如实说「还没数据」。 */
    fun verdict(c: Context, t: TaskInfo?): SegPlan.Verdict? {
        val work = workDir(c, t) ?: return null
        return SegPlan.readMap(work)?.let { SegPlan.verdict(it) }
    }
}
