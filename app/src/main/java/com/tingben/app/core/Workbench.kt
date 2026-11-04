package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * R18 · 工作看台的分段数据。
 *
 * 不新造状态、不猜进度：直接扫 work 目录里已经落盘的东西。
 *  ann_%04d.json        = 这一块标注完成
 *  chunk/direct_%04d.wav = 这一段声音已生成
 * 两者索引不保证一一对应（一块可能切成好几段），所以分开如实列，不假装对齐。
 */
data class SegRow(
    val index: Int,
    val annotated: Boolean,
    val wav: File?,
    val preview: String,
    val durationMs: Long
) {
    val hasAudio: Boolean get() = wav != null
}

object Workbench {
    private val annRe = Regex("""^ann_(\d{4})\.json$""")
    private val wavRe = Regex("""^(?:chunk|direct)_(\d{4})\.wav$""")

    fun workDir(c: Context, t: TaskInfo?): File? = t?.let {
        AnnCache.dirFor(c, it.bookId, it.direct, it.think, it.only)
    }

    fun segments(c: Context, t: TaskInfo?): List<SegRow> {
        val work = workDir(c, t) ?: return emptyList()
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

        // 总段数 = 已落盘的最大索引、或任务报的总数，取大的那个。
        // 这样「还没轮到」的段也能画成灰色小胶囊，用户看得出后面还有多少。
        val last = maxOf(
            (annSet.maxOrNull() ?: -1),
            (wavMap.keys.maxOrNull() ?: -1),
            ((t?.total ?: 1) - 1)
        ).coerceAtLeast(0)
        // 长书别把几百个胶囊全铺开，看台只列前 60 段 + 一句「还有 N 段」。
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

            SegRow(
                index = i,
                annotated = annFile.exists(),
                wav = wavMap[i],
                preview = preview,
                durationMs = wavMap[i]?.let { AudioLibrary.durationMs(it) } ?: 0L
            )
        }
    }

    fun remainingCount(c: Context, t: TaskInfo?): Int {
        val work = workDir(c, t) ?: return 0
        val files = try { work.listFiles()?.toList() ?: emptyList() } catch (_: Throwable) { return 0 }
        val last = files.mapNotNull { f ->
            annRe.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: wavRe.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: -1
        return ((t?.total ?: 1) - 1 - last).coerceAtLeast(0)
    }
}
