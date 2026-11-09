package com.tingben.app.core

import com.tingben.app.diag.EventLog
import java.io.File
import java.security.MessageDigest

/**
 * R185 · 调音色不再让前面做好的段作废。
 *
 * 一本书的段音频按「音色组合指纹」分套存。换音色时把当前这套搬到
 * `_voice_{旧key}/` 留着，再把目标套搬回根目录；切回原设置时把目标套
 * 搬回来即可，零重算。切分产物（ann_*.json / segmap.json / 章号索引 / stamp）
 * 留在根目录，跨套共享 —— 切分从来不会因为音色改变。
 *
 * 为什么不直接改文件名带 hash：现有工程到处是 `direct_%04d.wav` 与
 * `listFiles { it.isFile && endsWith(".wav") }` 的约定，改命名要动十几处，
 * 还容易被 SegPlan 的 segmap 漏掉。分目录后这些扫描天然只看见当前套。
 */
object VoiceCache {
    private const val PREFIX = "_voice_"
    private const val KEY_FILE = "voice_cache_key.txt"
    private const val MAX_KEEP = 12

    /**
     * R188 · 这两个不是切分产物，是「音频状态」，必须和 wav 同一套：
     *   chapter_done.json —— 整章另存后的成品索引（AudioTree.readDone），
     *     续跑时靠它跳过整章。留在根目录会让新音色复用旧音色成品 → 混音。
     *   redo.json —— 看台手动标记「这几段/块要重做」，同样是段级状态。
     * segmap.json / chapters.json / seg_chapters.json / flag_* / ann_* 是
     * 切分与章号索引，换音色不用重切，继续留根目录共享。
     */
    private val SIDECARS = listOf("chapter_done.json", "redo.json")

    private fun sideFiles(dir: File): List<File> =
        SIDECARS.map { File(dir, it) }.filter { it.isFile }

    /** 根目录只要还有任何音频状态，就算「有老数据」，不能凭空认领成空套。 */
    private fun hasAudioState(dir: File): Boolean =
        rootWavs(dir).isNotEmpty() || sideFiles(dir).isNotEmpty()

    data class Summary(
        val current: Int,
        val others: Int,
        val bestOther: Int,
        val totalBytes: Long
    )

    data class Switch(val archived: Int, val restored: Int, val key: String)

    /** 三个 stamp 合起来才算「一套音色」，只改其中一个也会开新套。 */
    fun keyOf(voice: String, mode: String, preset: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(listOf(voice, mode, preset).joinToString("||").toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }.take(12)
    } catch (_: Throwable) {
        (voice + mode + preset).hashCode().toString().take(12)
    }

    private fun keyFile(work: File) = File(work, KEY_FILE)

    /**
     * R185 · 从 1.1.9 升级上来的老数据没有 key 文件，但根目录有已经做好的段。
     * 不能一律当成「别的套」搬走 —— 用户没换音色，一升级就把进度清空是回归；
     * 也不能一律当成「当前套」留下 —— 用户换了音色再继续做，会把两种声音
     * 混进同一本书。老版本留着三个 stamp 文件，用它们反推旧套指纹最准。
     */
    private fun readKey(work: File, incoming: String): String = try {
        val f = keyFile(work)
        when {
            f.exists() -> f.readText().trim()
            !hasAudioState(work) -> incoming
            else -> {
                val v = File(work, "voice_stamp.txt").takeIf { it.exists() }?.readText()?.trim()
                val m = File(work, "mode_stamp.txt").takeIf { it.exists() }?.readText()?.trim()
                val p = File(work, "produce_stamp.txt").takeIf { it.exists() }?.readText()?.trim()
                // 三个 stamp 齐 → 反推旧套；不齐（更老的数据）→ 保守认作当前套，
                // 宁可少一次归档，也不凭空抹掉用户已做好的段。
                if (v != null && m != null && p != null) keyOf(v, m, p) else incoming
            }
        }
    } catch (_: Throwable) { incoming }

    private fun rootWavs(dir: File): List<File> = try {
        dir.listFiles()?.filter { f ->
            f.isFile && f.name.endsWith(".wav") &&
                (f.name.startsWith("direct_") || f.name.startsWith("chunk_")) &&
                f.length() > 44
        } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    private fun moveWav(from: File, to: File): Boolean = try {
        if (to.exists() && !to.delete()) false
        else if (from.renameTo(to)) true
        else {
            // 个别文件系统不支持跨目录 rename，退化成复制 + 删原
            from.copyTo(to, overwrite = true)
            from.delete()
            to.exists() && to.length() > 44
        }
    } catch (_: Throwable) { false }

    /** 侧车是小 JSON，不能套用 wav 的 >44 字节判据。 */
    private fun moveSide(from: File, to: File): Boolean = try {
        if (to.exists() && !to.delete()) false
        else if (from.renameTo(to)) true
        else {
            from.copyTo(to, overwrite = true)
            from.delete()
            to.exists()
        }
    } catch (_: Throwable) { false }

    /** 换套：旧的归档、目标套搬回来。同一套则什么都不做。 */
    fun switchTo(work: File, key: String): Switch {
        val cur = readKey(work, key)
        // 老数据第一次写 key 文件：段本来就是这一套的，直接认领，不做任何搬动。
        if (cur == key) {
            try { keyFile(work).writeText(key) } catch (_: Throwable) { }
            return Switch(0, rootWavs(work).size, key)
        }
        try { work.mkdirs() } catch (_: Throwable) { }

        var archived = 0
        val bucket = File(work, PREFIX + cur.ifBlank { "empty" })
        val src = rootWavs(work)
        val sides = sideFiles(work)
        // R188 · 即使 wav 全被「另存整章」删光了，只要还有 chapter_done.json
        // 也必须归档，否则换音色后旧成品会被拼进新书。
        if (src.isNotEmpty() || sides.isNotEmpty()) {
            try { bucket.mkdirs() } catch (_: Throwable) { }
            src.forEach { f -> if (moveWav(f, File(bucket, f.name))) archived++ }
            sides.forEach { f -> moveSide(f, File(bucket, f.name)) }
        }

        var restored = 0
        val target = File(work, PREFIX + key)
        if (target.isDirectory) {
            rootWavs(target).forEach { f ->
                if (moveWav(f, File(work, f.name))) restored++
            }
            sideFiles(target).forEach { f -> moveSide(f, File(work, f.name)) }
        }
        try { keyFile(work).writeText(key) } catch (_: Throwable) { }
        purge(work)
        return Switch(archived, restored, key)
    }

    /** 给制作页说人话：这套做了几段、还留着几套、最多的一套做了几段。 */
    fun summary(work: File, key: String): Summary = try {
        val dirs = work.listFiles()?.filter {
            it.isDirectory && it.name.startsWith(PREFIX)
        } ?: emptyList()
        val counts = dirs.map { rootWavs(it).size }
        val bytes = dirs.sumOf { d -> rootWavs(d).sumOf { it.length() } }
        // 只另存过整章、没有散段的套也算「留着」，别让它从提示里消失。
        val alive = dirs.count { d ->
            rootWavs(d).isNotEmpty() || sideFiles(d).isNotEmpty()
        }
        Summary(
            current = rootWavs(work).size,
            others = alive,
            bestOther = counts.maxOrNull() ?: 0,
            totalBytes = bytes
        )
    } catch (_: Throwable) { Summary(0, 0, 0, 0L) }

    /**
     * 防无限膨胀：最多留 12 套。真要删了也明写日志，不静默抹掉用户的成果。
     * 12 套 × 88 段 × 约 2.8MB ≈ 3GB，对这台 278GB 可用的设备可忽略。
     */
    private fun purge(work: File) {
        try {
            val dirs = (work.listFiles()?.filter {
                it.isDirectory && it.name.startsWith(PREFIX)
            } ?: emptyList()).sortedByDescending { it.lastModified() }
            dirs.drop(MAX_KEEP).forEach { d ->
                val n = rootWavs(d).size
                d.deleteRecursively()
                EventLog.log(EventLog.Kind.PIPELINE, "VOICE_CACHE_PURGE",
                    "缓存超过 $MAX_KEEP 套，清掉最旧一套 ${d.name} 的 $n 段")
            }
        } catch (_: Throwable) { }
    }
}
