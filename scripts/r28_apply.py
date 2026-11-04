#!/usr/bin/env python3
# R28 · 段级重做 + 「语言模型是否生效」一眼判定
# 每步都断言锚点唯一命中，任何一处不命中就整体回滚，避免半改状态。
import sys, shutil, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"
WB   = ROOT / "app/src/main/java/com/tingben/app/core/Workbench.kt"
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"

for p in (PIPE, WB, MAIN):
    shutil.copy(p, pathlib.Path(str(p) + ".r28bak"))

def sub(s, old, new, tag):
    n = s.count(old)
    assert n == 1, f"[{tag}] 锚点命中 {n} 次，应为 1：{old[:70]!r}"
    return s.replace(old, new, 1)

# ───────── Pipeline.kt ─────────
s = PIPE.read_text()

s = sub(s,
"""        val collected = mutableListOf<Ann>()
""",
"""        val collected = mutableListOf<Ann>()
        // R28 · 与 collected 一一对应的段来源（块号 / 块内第几条 / 是否模型标注）。
        // 只从磁盘读，不猜：旧缓存没记来源就是 null。
        val segOrigin = mutableListOf<Triple<Int, Int, Boolean?>>()
""", "segOrigin")

s = sub(s,
"""                    safe.forEach { a ->
                        collected.add(a)
""",
"""                    safe.forEachIndexed { k, a ->
                        collected.add(a)
                        segOrigin.add(Triple(i, k, SegPlan.sourceOf(work, i, k)))
""", "forEachIndexed")

s = sub(s,
"""                            File(work, "ann_%04d.src".format(i))
                                .writeText(if (srcKept > 0) "MODEL" else "FILL")
                        } catch (_: Throwable) { }
""",
"""                            File(work, "ann_%04d.src".format(i))
                                .writeText(if (srcKept > 0) "MODEL" else "FILL")
                        } catch (_: Throwable) { }
                        // R27 只在内存里算 srcFlags，进程被杀续跑后就丢了；落盘后
                        // 看台才能逐段（而不是逐块）判「这条是不是模型标的」。
                        SegPlan.writeFlags(work, i, srcFlags)
""", "writeFlags")

s = sub(s,
"""                        AnnCache.write(work, i, result)
""",
"""                        AnnCache.write(work, i, result)
                        // 这一块是真重跑过了，撤销「重新标注本块」的待办标记。
                        SegPlan.clearRedo(work, "block:$i")
""", "clearBlockRedo")

s = sub(s,
"""        EventLog.log(EventLog.Kind.ENGINE, "VOICE_IDENTITY",
""",
"""        // R28 · 唯一真实来源：把「第几段来自第几块第几条、是不是模型标的」落盘。
        // 看台以前把 ann_（块索引）和 chunk_（段索引）按同一个 i 并排画格，
        // 一块常切成好几段，格子既不是块也不是段。direct 档没跑 4B，不写 segmap，
        // 判定条按 direct 单独说明，不把「没跑模型」冒充成「模型没采纳」。
        if (!direct && segOrigin.size == toSpeak.size) {
            val metas = toSpeak.mapIndexed { si, a ->
                val (blk, ai, fm) = segOrigin[si]
                SegMeta(si, blk, ai, fm, a.text, a.speaker, a.instruct,
                    "chunk_%04d.wav".format(si))
            }
            SegPlan.writeMap(work, metas)
            val v = SegPlan.verdict(metas)
            EventLog.log(EventLog.Kind.PIPELINE, "LLM_VERDICT",
                v.label + " · 模型 ${v.model} / 直读 ${v.fill} / 旧缓存未知 ${v.unknown}",
                level = if (v.model == 0) EventLog.Level.WARN else EventLog.Level.INFO,
                fields = mapOf("model" to v.model.toString(), "fill" to v.fill.toString(),
                    "unknown" to v.unknown.toString(), "total" to v.total.toString()))
        }
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_IDENTITY",
""", "segmap")

s = sub(s,
"""                if (wav.exists() && wav.length() > 44) {
                    wavs.add(wav)
                } else {
""",
"""                if (wav.exists() && wav.length() > 44) {
                    wavs.add(wav)
                    SegPlan.clearRedo(work, "seg:$i")
                } else {
""", "clearSegCached")

s = sub(s,
"""                    if (code == 0 && wav.exists() && wav.length() > 44) wavs.add(wav)
                    else { synthFails++; wav.delete() }
""",
"""                    if (code == 0 && wav.exists() && wav.length() > 44) {
                        wavs.add(wav)
                        SegPlan.clearRedo(work, "seg:$i")
                    } else { synthFails++; wav.delete() }
""", "clearSegFresh")

PIPE.write_text(s)

# ───────── Workbench.kt 全量重写 ─────────
WB.write_text('''package com.tingben.app.core

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
    private val annRe = Regex("""^ann_(\\d{4})\\.json$""")
    private val wavRe = Regex("""^(?:chunk|direct)_(\\d{4})\\.wav$""")

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
''')

print("Pipeline.kt / Workbench.kt 已改")
