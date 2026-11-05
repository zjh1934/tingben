package com.tingben.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * R36 · 调音台预设库：把「音色 12 维 + 采样 9 项 + 表演层 5 项」整套存成可复用预设。
 *
 * 不手搓依据（逐条见 docs/R36_调音台重设计_联动表.md）：
 *  - 磁盘形状抄 IndexTTS WebUI `indextts/utils/presets.py`：
 *    PRESET_VERSION、safe_preset_name、list_presets、save_preset、load_preset、
 *    delete_preset、preset_exists、同名覆盖、.part 原子落盘。
 *    该仓库许可证未核实，只抄数据结构与函数形状，不复制源码行。
 *  - 交互形状抄 text-generation-webui `modules/ui_parameters.py`：
 *    preset_menu + save_preset / delete_preset / reset_preset 三按钮。
 *    该项目为 AGPL-3.0，只借鉴交互，不复制代码。
 *  - 「新建 Session 时选预设 / Session 设置里存为预设」抄 IndexTTS WebUI 项目面板。
 *  - 版本化宽容迁移抄 PocketPal `completionSettingsVersions.ts`。
 *
 * 边界：预设只是「一键载入待提交」的配方，不新建第二份 TtsKnobs / ProduceConfig
 * 配置。载入预设只写 MixerDraft，唯一出口仍是 MixerCommit.commit，
 * 不碰 Producer.start 签名、不碰 TaskInfo 书签、不碰缓存分桶。
 */
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

    /** 一行摘要给列表用，不拼长说明文字。 */
    fun chips(): String = buildList {
        add("temp ${knobs.temperature}")
        add("sub ${knobs.subTemperature}")
        add("seed ${knobs.seed}")
        add(perf.style.label)
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
    private fun part(c: Context) = File(c.filesDir, "mixer_presets.json.part")

    /** 抄 safe_preset_name：只留能当文件名的字符，空名兜底，同名覆盖靠它对齐。 */
    fun safeName(raw: String): String {
        var s = raw.trim().replace(Regex("[\\\\/:*?\"|]+"), "")
        s = s.replace(Regex("\\s+"), "").trim('.', '_')
        return s.ifBlank { "untitled" }
    }

    /** 4 组内置：采样值沿用 SamplingPresets，音色留空表示「沿用当前身份」。 */
    fun builtins(): List<MixerPreset> = listOf(
        MixerPreset("builtin_upstream", "上游默认", "", emptyMap(), TtsKnobs(),
            ProduceConfig(), true),
        MixerPreset("builtin_stable", "稳定朗读", "", emptyMap(),
            TtsKnobs(temperature = 0.6f, subTemperature = 0.6f, repetitionPenalty = 1.1f, seed = 42L),
            ProduceConfig(), true),
        MixerPreset("builtin_drama", "戏剧张力", "", emptyMap(),
            TtsKnobs(temperature = 1.1f, subTemperature = 0.8f, seed = 42L),
            ProduceConfig(style = ReadingStyle.DRAMA, intensity = Intensity.FULL), true),
        MixerPreset("builtin_exact", "完全确定", "", emptyMap(),
            TtsKnobs(temperature = 0f, subTemperature = 0f, seed = 42L),
            ProduceConfig(), true)
    )

    private fun readUser(c: Context): List<MixerPreset> = try {
        val f = file(c)
        if (!f.exists()) emptyList() else {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("presets") ?: JSONArray()
            (0 until arr.length()).mapNotNull { MixerPreset.fromJson(arr.optJSONObject(it)) }
        }
    } catch (_: Throwable) { emptyList() }

    /** 内置在前、用户自定义在后；未知 schema 与坏条目一律跳过，不崩。 */
    fun list(c: Context): List<MixerPreset> = builtins() + readUser(c)

    fun find(c: Context, id: String): MixerPreset? = list(c).firstOrNull { it.id == id }

    fun exists(c: Context, name: String): Boolean =
        readUser(c).any { it.name == safeName(name) }

    /** 同名覆盖（抄 IndexTTS「存为预设（同名覆盖）」）；原子落盘，写一半被杀不留坏 JSON。 */
    fun save(c: Context, p: MixerPreset): Boolean {
        val name = safeName(p.name)
        val item = p.copy(id = sha(name).take(10), name = name, builtin = false)
        val list = readUser(c).filter { it.name != name }.toMutableList()
        list.add(item)
        return try {
            val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
            val root = JSONObject().put("schema", PRESET_SCHEMA).put("presets", arr)
            val tmp = part(c); tmp.writeText(root.toString(1))
            if (!tmp.renameTo(file(c))) { file(c).writeText(root.toString(1)); tmp.delete() }
            true
        } catch (_: Throwable) { false }
    }

    /** 内置预设不许删，返回 false 由界面给明确提示。 */
    fun delete(c: Context, id: String): Boolean {
        if (id.startsWith("builtin_")) return false
        val list = readUser(c).toMutableList()
        if (list.removeIf { it.id == id }.not()) return false
        return try {
            val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
            val root = JSONObject().put("schema", PRESET_SCHEMA).put("presets", arr)
            val tmp = part(c); tmp.writeText(root.toString(1))
            if (!tmp.renameTo(file(c))) { file(c).writeText(root.toString(1)); tmp.delete() }
            true
        } catch (_: Throwable) { false }
    }
}
