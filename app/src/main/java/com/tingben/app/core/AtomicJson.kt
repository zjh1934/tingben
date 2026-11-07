package com.tingben.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object AtomicJson {
    sealed class Read {
        object Missing : Read()
        data class Ok(val text: String, val from: String = "") : Read()
        data class Corrupt(val reason: String) : Read()
    }

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

    private fun raw(f: File, s: String): Boolean = try {
        FileOutputStream(f, false).use { o ->
            o.write(s.toByteArray(Charsets.UTF_8))
            o.flush()
            o.fd.sync()
        }
        true
    } catch (_: Throwable) { false }

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
        val t = text(File(f.parentFile, f.name + ".part"))
        if (t != null && legal(t)) return Read.Ok(t, f.name + ".part")
        return Read.Missing
    }

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
            return part.renameTo(f)
        } catch (_: Throwable) { return false }
    }
}
