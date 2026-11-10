package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PRESET_SCHEMA = 1

data class MixerPreset(
    val id: String,
    val name: String,
    val voice: String = "",
    val fields: Map<String, String> = emptyMap(),
    val knobs: TtsKnobs = TtsKnobs(),
    val perf: ProduceConfig = ProduceConfig(),
    val builtin: Boolean = false
) {
    fun toJson(): JSONObject {
        val f = JSONObject(); fields.forEach { f.put(it.key, it.value) }
        val k = JSONObject()
            .put("seed", knobs.seed)
            .put("maxNewTokens", knobs.maxNewTokens)
            .put("temperature", knobs.temperature.toDouble())
            .put("topK", knobs.topK)
            .put("topP", knobs.topP.toDouble())
            .put("repetitionPenalty", knobs.repetitionPenalty.toDouble())
            .put("subTemperature", knobs.subTemperature.toDouble())
            .put("subTopK", knobs.subTopK)
            .put("subTopP", knobs.subTopP.toDouble())
        return JSONObject()
            .put("schema", PRESET_SCHEMA)
            .put("id", id).put("name", name).put("voice", voice)
            .put("fields", f).put("knobs", k)
            .put("perf", perf.toJson()).put("builtin", builtin)
    }

    fun chips(): String = buildList {
        add("主 ${knobs.temperature}/${knobs.topK}/${knobs.topP}")
        add("副 ${knobs.subTemperature}/${knobs.subTopK}")
        add("rep ${knobs.repetitionPenalty}")
        add("max ${knobs.maxNewTokens}")
        add("seed ${knobs.seed}")
        add(perf.style.label)
        if (voice.isNotBlank()) add("身份 ${voice.length}字")
        if (fields.isNotEmpty()) add("${fields.size} 维")
    }.joinToString(" · ")

    companion object {
        fun fromJson(o: JSONObject?): MixerPreset? {
            if (o == null) return null
            val id = o.optString("id").ifBlank { return null }
            val k = o.optJSONObject("knobs") ?: JSONObject()
            val f = o.optJSONObject("fields")
            val fields = mutableMapOf<String, String>()
            if (f != null) {
                val it = f.keys()
                while (it.hasNext()) { val key = it.next(); fields[key] = f.optString(key, "") }
            }
            val knobs = TtsKnobs(
                seed = k.optLong("seed", TtsDefaults.SEED),
                maxNewTokens = k.optInt("maxNewTokens", TtsDefaults.MAX_NEW_TOKENS),
                temperature = k.optDouble("temperature", TtsDefaults.TEMPERATURE.toDouble()).toFloat(),
                topK = k.optInt("topK", TtsDefaults.TOP_K),
                topP = k.optDouble("topP", TtsDefaults.TOP_P.toDouble()).toFloat(),
                repetitionPenalty = k.optDouble("repetitionPenalty", TtsDefaults.REPETITION_PENALTY.toDouble()).toFloat(),
                subTemperature = k.optDouble("subTemperature", TtsDefaults.SUBTALKER_TEMPERATURE.toDouble()).toFloat(),
                subTopK = k.optInt("subTopK", TtsDefaults.SUBTALKER_TOP_K),
                subTopP = k.optDouble("subTopP", TtsDefaults.SUBTALKER_TOP_P.toDouble()).toFloat()
            )
            return MixerPreset(
                id = id,
                name = o.optString("name").ifBlank { id },
                voice = o.optString("voice", ""),
                fields = fields,
                knobs = knobs,
                perf = ProduceConfig.fromJson(o.optJSONObject("perf")),
                builtin = o.optBoolean("builtin", false)
            )
        }
    }
}

object MixerPresetStore {
    private fun file(c: Context) = File(c.filesDir, "mixer_presets.json")

    fun safeName(raw: String): String {
        var s = raw.trim().replace(Regex("[\\\\/:*?\"|]+"), "")
        s = s.replace(Regex("\\s+"), "").trim('.', '_')
        return s.ifBlank { "untitled" }
    }

    fun builtins(): List<MixerPreset> = listOf(
        MixerPreset("builtin_upstream", "上游默认", "", emptyMap(), TtsKnobs(),
            ProduceConfig(), true),
        MixerPreset("builtin_stable", "稳定朗读", "", emptyMap(),
            TtsKnobs(temperature = 0.6f, subTemperature = 0.6f, repetitionPenalty = 1.1f, seed = 42L),
            ProduceConfig(), true),
        // R221 · 风格枚举停用，张力由采样参数拉开，不再往 instruct 塞写死句
        MixerPreset("builtin_drama", "戏剧张力", "", emptyMap(),
            TtsKnobs(temperature = 1.1f, subTemperature = 0.8f, seed = 42L),
            ProduceConfig(), true),
        MixerPreset("builtin_exact", "完全确定", "", emptyMap(),
            TtsKnobs(temperature = 0f, subTemperature = 0f, seed = 42L),
            ProduceConfig(), true)
    )

    private fun readUser(c: Context): List<MixerPreset> {
        val body = when (val r = AtomicJson.read(file(c))) {
            is AtomicJson.Read.Ok -> r.text
            else -> return emptyList()
        }
        return try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("presets") ?: JSONArray()
            (0 until arr.length()).mapNotNull { MixerPreset.fromJson(arr.optJSONObject(it)) }
        } catch (_: Throwable) { emptyList() }
    }

    fun list(c: Context): List<MixerPreset> = builtins() + readUser(c)

    fun find(c: Context, id: String): MixerPreset? = list(c).firstOrNull { it.id == id }

    fun exists(c: Context, name: String): Boolean =
        readUser(c).any { it.name == safeName(name) }

    private fun usableName(raw: String): String? {
        if (raw.trim().isEmpty()) return null
        val n = safeName(raw)
        return if (n == "untitled") null else n
    }

    private fun writeAll(c: Context, list: List<MixerPreset>): Boolean {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        val root = JSONObject().put("schema", PRESET_SCHEMA).put("presets", arr)
        return AtomicJson.write(file(c), root.toString(1))
    }

    fun save(c: Context, p: MixerPreset): Boolean {
        val name = usableName(p.name) ?: return false
        val item = p.copy(id = sha(name).take(10), name = name, builtin = false)
        val list = readUser(c).filter { it.name != name }.toMutableList()
        list.add(item)
        return writeAll(c, list)
    }

    fun rename(c: Context, id: String, raw: String): Boolean {
        val name = usableName(raw) ?: return false
        val list = readUser(c).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        if (list.any { it.name == name && it.id != id }) return false
        list[idx] = list[idx].copy(id = sha(name).take(10), name = name)
        return writeAll(c, list)
    }

    fun delete(c: Context, id: String): Boolean {
        if (id.startsWith("builtin_")) return false
        val list = readUser(c).toMutableList()
        if (list.removeIf { it.id == id }.not()) return false
        return writeAll(c, list)
    }
}
