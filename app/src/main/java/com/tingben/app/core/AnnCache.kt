package com.tingben.app.core

import android.content.Context
import java.io.File

// @core-part: AnnCache
object AnnCache {

    fun count(work: File): Int = try {
        work.listFiles()?.count { it.isFile && it.name.startsWith("ann_") && it.name.endsWith(".json") } ?: 0
    } catch (_: Throwable) { 0 }

    fun cleanup(work: File) {
        try { work.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } }
        catch (_: Throwable) { }
    }

    fun clear(work: File) {
        // R185 · 归档套是子目录，原来的 it.delete() 对非空目录会静默失败，
        // 删书时留一堆旧音色缓存。保留 work 目录本身，只清里面的内容。
        try {
            work.listFiles()?.forEach { f ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        } catch (_: Throwable) { }
    }

    fun hasProgress(c: Context, bookId: String, only: Set<Int>?): Boolean {
        val w = File(c.filesDir, "work/${bookId}/${bucket(only)}")
        val ann = count(w)
        val wav = try {
            w.listFiles()?.count { it.isFile &&
                (it.name.startsWith("chunk_") || it.name.startsWith("direct_")) &&
                it.name.endsWith(".wav") && it.length() > 44 } ?: 0
        } catch (_: Throwable) { 0 }
        return ann > 0 || wav > 0
    }

    // R148 · 这是 1.1.3 日志里两个毛病的同一个根因。选中章一多，
    // joinToString("_") 会撑爆文件系统「单个目录名 255 字节」上限：
    // 103 章拼出 304 字符，mkdirs() 静默失败 → work 目录根本不存在 →
    //   · SegPlan.writeMap 写不进去（日志：制作记录写入失败：segmap.json）
    //   · work.usableSpace 返回 0 → minOf 取到 0 → 误判「存储不够」→ 全本直接被拒
    // 日志实证：103 章被拒（free=0），2 章正常。少章保持原命名以兼容已有目录，
    // 长名改成定长指纹，任意章数都安全，同一组章仍映射到同一目录（断点不丢）。
    private const val DIRECT_PREFIX = "v11/direct/"

    fun bucket(only: Set<Int>?): String {
        if (only == null) return DIRECT_PREFIX + "all"
        val raw = only.sorted().joinToString("_")
        if (raw.length <= 64) return DIRECT_PREFIX + raw
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        return DIRECT_PREFIX + "h" + hex.take(20)
    }

    fun dirFor(c: Context, bookId: String, only: Set<Int>?) =
        File(c.filesDir, "work/${bookId}/${bucket(only)}").apply { mkdirs() }
}

