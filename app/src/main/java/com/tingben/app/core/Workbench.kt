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
    private val wavRe = Regex("""^(?:chunk|direct)_(\d{4})\.wav$""")

    fun workDir(c: Context, t: TaskInfo?): File? = t?.let {
        AnnCache.dirFor(c, it.bookId, it.only)
    }

    fun segments(c: Context, t: TaskInfo?): List<SegRow> {
        val work = workDir(c, t) ?: return emptyList()
        val redo = SegPlan.pending(work)
        val mapped = SegPlan.readMap(work)
        if (mapped != null) {
            // 不截断：看台要显示全部计划段，灰格也是段。段数很多时由 UI 承担滚动成本。
            return mapped.map { m ->
                val f = File(work, m.wavName)
                SegRow(m.seg, m.block, m.ann, m.fromModel,
                    f.takeIf { it.exists() && it.length() > 44 }, m.wavName,
                    m.text, m.speaker, m.instruct,
                    "seg:${m.seg}" in redo)
            }
        }

        // R328 · 兜底分支原来还要读 ann_%04d.json 拼预览文字、按标注序号推段数。
        // 这套 JSON 是早期标注体系的产物，写入侧早已退场、文件根本不存在，
        // 读它只会白跑一次 IO。现在只按真实落盘的 WAV 和任务总数推算。
        val files = try { work.listFiles()?.toList() ?: emptyList() } catch (_: Throwable) { return emptyList() }
        val wavMap = mutableMapOf<Int, File>()
        files.forEach { f ->
            wavRe.matchEntire(f.name)?.let {
                val i = it.groupValues[1].toInt()
                if (f.length() > 44) wavMap[i] = f
            }
        }
        val last = maxOf(
            (wavMap.keys.maxOrNull() ?: -1),
            ((t?.total ?: 1) - 1)
        ).coerceAtLeast(0)
        // 兜底（没写 segmap 时）按已落盘最大序号 + 任务总数推算，不再砍到 59；
        // 只留一个极端保险，避免 total 被写坏时生成上万行把界面拖死。
        val upTo = last.coerceAtMost(2000)

        return (0..upTo).map { i ->
            val w = wavMap[i]
            SegRow(i, i, 0, null, w,
                w?.name ?: "chunk_%04d.wav".format(i),
                if (t?.direct == true) "原文照读" else "", "旁白", "", false)
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
}
