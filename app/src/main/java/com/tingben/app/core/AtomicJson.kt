package com.tingben.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * R104 · F-02：索引类 JSON 的统一落盘与安全读取。
 *
 * 旧写法在 rename 失败时直接覆写正式文件，写一半被杀就把正式文件写坏；
 * 读取端又把坏文件当成空索引，磁盘上的音频还在，列表却全没了。
 * 现在：先写 .part 并 flush + fd.sync，再 rename；rename 失败保留旧正式文件，
 * 不覆写。读取区分「不存在 / 正常 / 损坏」，损坏时回退 .bak 或 .part，
 * 都不合法就明确报损坏，绝不静默给一份空数据。
 */
object AtomicJson {
    sealed class Read {
        object Missing : Read()
        data class Ok(val text: String, val from: String = "") : Read()
        data class Corrupt(val reason: String) : Read()
    }

    /** 只认完整合法的 JSON 数组 / 对象；半截内容一律不算成品。 */
    private fun legal(s: String): Boolean {
        return try {
            when (s.trimStart().firstOrNull()) {
                '[' -> { JSONArray(s); true }
                '{' -> { JSONObject(s); true }
                else -> false
            }
        } catch (_: Throwable) { false }
    }

    private fun text(f: File): String? = try {
        if (f.isFile) f.readText() else null
    } catch (_: Throwable) { null }

    /** 写 .part：flush 后 fd.sync，进程被杀也不留下「看着像成品」的半截内容。 */
    private fun raw(f: File, s: String): Boolean = try {
        FileOutputStream(f, false).use { o ->
            o.write(s.toByteArray(Charsets.UTF_8))
            o.flush()
            o.fd.sync()
        }
        true
    } catch (_: Throwable) { false }

    /** 正式文件 → .bak（上一次成功版本）→ .part；都不合法才报损坏。 */
    fun read(f: File): Read {
        val own = text(f)
        if (own != null) {
            if (legal(own)) return Read.Ok(own)
            for (name in listOf(f.name + ".bak", f.name + ".part")) {
                val t = text(File(f.parentFile, name))
                if (t != null && legal(t)) return Read.Ok(t, name)
            }
            return Read.Corrupt("索引损坏且没有可用的上一次记录")
        }
        // 正式文件不在：上次 rename 前被杀时 .part 可能已经是完整内容
        val t = text(File(f.parentFile, f.name + ".part"))
        if (t != null && legal(t)) return Read.Ok(t, f.name + ".part")
        return Read.Missing
    }

    /** 成功才 true；失败时旧正式文件保持原样，绝不覆写、绝不吞掉。 */
    fun write(f: File, s: String): Boolean {
        if (!legal(s)) return false
        val part = File(f.parentFile, f.name + ".part")
        val bak = File(f.parentFile, f.name + ".bak")
        try {
            val old = text(f)
            if (old != null && legal(old)) {
                val bp = File(f.parentFile, f.name + ".bak.part")
                if (raw(bp, old)) {
                    if (!bp.renameTo(bak)) bp.delete()
                } else bp.delete()
            }
            if (!raw(part, s)) { part.delete(); return false }
            // rename 失败：保留旧正式文件与完整 .part，交给 read 回退
            return part.renameTo(f)
        } catch (_: Throwable) { return false }
    }
}
