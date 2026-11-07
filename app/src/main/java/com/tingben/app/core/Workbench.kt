package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import java.io.File

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
        AnnCache.dirFor(c, it.bookId, it.only)
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
            } else if (t?.direct == true) "原文照读" else ""
            val w = wavMap[i]
            SegRow(i, i, 0, null, w,
                w?.name ?: "chunk_%04d.wav".format(i), preview, "旁白", "", false)
        }
    }

    fun segChapters(c: Context, t: TaskInfo?): Map<Int, Int>? {
        val work = workDir(c, t) ?: return null
        val body = when (val r = AtomicJson.read(File(work, "seg_chapters.json"))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return null
        }
        return try {
            val a = JSONArray(body)
            (0 until a.length()).associate { it to a.getInt(it) }
        } catch (_: Throwable) { null }
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

    fun verdict(c: Context, t: TaskInfo?): SegPlan.Verdict? {
        val work = workDir(c, t) ?: return null
        return SegPlan.readMap(work)?.let { SegPlan.verdict(it) }
    }
}
