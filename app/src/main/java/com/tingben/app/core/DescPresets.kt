package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R304 · 调音台的「描述预设」。
 * 「声音描述」与「风格描述」各用一份独立的预设表（slot 分开），互不影响。
 * 简短描述只给选择列表看；详细描述才是真正填进输入框、送进合成的文本。
 * 预设正文不写日志，也不进认知索引。
 */
data class DescPreset(val id: String, val short: String, val long: String)

object DescPresetStore {
    private fun file(c: Context, slot: String) = File(c.filesDir, "desc_presets_$slot.json")

    private fun decode(body: String): List<DescPreset> = try {
        val arr = JSONObject(body).optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) null
            else DescPreset(id, o.optString("short"), o.optString("long"))
        }
    } catch (_: Throwable) {
        emptyList()
    }

    fun list(c: Context, slot: String): List<DescPreset> =
        when (val r = AtomicJson.read(file(c, slot))) {
            is AtomicJson.Read.Ok -> decode(r.text)
            else -> emptyList()
        }

    /** 同名覆盖，连点保存不会堆出一排重复项。 */
    fun save(c: Context, slot: String, short: String, long: String): Boolean {
        val s = short.trim()
        if (s.isEmpty()) return false
        val l = long.trim()
        // R317 · 详细描述才是喂给模型的那一份；空着存进去，选中这条预设
        // 等于把音色 / 风格清空，用户会以为「设了不管用」。
        if (l.isEmpty()) return false
        val item = DescPreset(
            id = (s.hashCode().toLong() and 0x7fffffffL).toString(36),
            short = s,
            long = l
        )
        val next = list(c, slot).filter { it.short != s }.toMutableList()
        next.add(item)
        return writeAll(c, slot, next)
    }

    fun delete(c: Context, slot: String, id: String): Boolean {
        val cur = list(c, slot)
        val next = cur.filter { it.id != id }
        if (next.size == cur.size) return false
        return writeAll(c, slot, next)
    }

    private fun writeAll(c: Context, slot: String, items: List<DescPreset>): Boolean {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("short", it.short)
                .put("long", it.long))
        }
        val root = JSONObject().put("schema", 1).put("items", arr)
        return AtomicJson.write(file(c, slot), root.toString(1))
    }
}
