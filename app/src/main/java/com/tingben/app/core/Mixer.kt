package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog

enum class VoiceDimension(val label: String, val hint: String, val options: List<String>) {
    GENDER("性别", "男声 / 女声 / 中性", listOf("不指定", "男声", "女声", "中性")),
    AGE("年龄", "少年 / 青年 / 中年 / 老年", listOf("不指定", "少年", "青年", "中年", "老年")),
    PITCH("音区", "高 / 中高 / 中 / 中低 / 低", listOf("不指定", "高", "中高", "中", "中低", "低")),
    TIMBRE("音色质地", "清亮 / 温润 / 浑厚 / 沙哑 / 醇厚", listOf("不指定", "清亮", "温润", "浑厚", "沙哑", "醇厚", "磁性", "苍老")),
    BRIGHTNESS("明暗", "明亮 / 中等 / 低沉 / 暗", listOf("不指定", "明亮", "中等", "低沉", "暗")),
    ROUGHNESS("颗粒感", "光滑 / 微粗 / 粗糙", listOf("不指定", "光滑", "微粗", "粗糙")),
    BREATH("气息", "低 / 中 / 高（气声）", listOf("不指定", "低", "中", "高（明显气声）")),
    WEIGHT("分量与音量", "轻 / 中 / 重", listOf("不指定", "轻，音量柔和", "中，正常交谈音量", "重，音量充足")),
    RATE("语速", "慢 / 中 / 快；只写稳定倾向", listOf("不指定", "慢", "适中", "偏快")),
    CLARITY("吐字", "清晰标准 / 自然 / 略含混", listOf("不指定", "吐字清晰标准", "自然", "略含混")),
    ACCENT("口音", "普通话 / 方言 / 无", listOf("不指定", "普通话", "略带江浙口音", "京腔", "川渝口音")),
    PERSONA("气质", "儒雅 / 沉稳 / 活泼 / 威严（只做锚点）", listOf("不指定", "儒雅", "沉稳", "活泼", "威严", "内敛", "慵懒"))
}

data class VoicePreset(
    val id: String,
    val name: String,
    val fields: Map<String, String>,
    val free: String
) {
    fun render(): String {
        val body = buildList {
            listOf(
                VoiceDimension.GENDER, VoiceDimension.AGE, VoiceDimension.PITCH,
                VoiceDimension.TIMBRE, VoiceDimension.BRIGHTNESS, VoiceDimension.ROUGHNESS,
                VoiceDimension.BREATH, VoiceDimension.WEIGHT, VoiceDimension.RATE,
                VoiceDimension.CLARITY, VoiceDimension.ACCENT, VoiceDimension.PERSONA
            ).forEach { d ->
                fields[d.name]?.takeIf { it.isNotBlank() }?.let { add("${d.label}：${it}") }
            }
        }.joinToString("；")
        val tail = free.trim().removeSuffix("。")
        return buildString {
            if (body.isNotBlank()) append(body).append("。")
            if (tail.isNotBlank()) append(tail).append("。")
        }
    }

    fun chips(): String = listOf(
        VoiceDimension.GENDER, VoiceDimension.AGE, VoiceDimension.PITCH, VoiceDimension.TIMBRE
    ).mapNotNull { fields[it.name] }.joinToString(" · ")
}

object VoicePresets {
    val ALL: List<VoicePreset> = listOf(
        VoicePreset("P01", "阳光少年", mapOf(
            G to "男声", A to "少年", P to "高", T to "清亮", B to "明亮", R to "光滑",
            BR to "低", W to "轻，音量适中", RA to "偏快", C to "吐字清晰", AC to "普通话", PE to "活泼"),
            "阳光少年，语速快，声音清亮上扬"),
        VoicePreset("P02", "温润青年", mapOf(
            G to "男声", A to "青年", P to "中", T to "温润", B to "中等", R to "光滑",
            BR to "中", W to "中，正常交谈音量", RA to "适中", C to "吐字清晰标准", AC to "普通话", PE to "温和儒雅"),
            "温润青年，书卷气，语调平稳"),
        VoicePreset("P03", "中年威严", mapOf(
            G to "男声", A to "中年", P to "低", T to "浑厚", B to "低沉", R to "微粗",
            BR to "低", W to "重，音量充足", RA to "慢", C to "字字分明", AC to "普通话", PE to "沉稳威严"),
            "中年威严男，低沉浑厚，语速慢，有压迫感"),
        VoicePreset("P04", "苍老老人", mapOf(
            G to "男声", A to "老年", P to "低", T to "苍老", B to "暗", R to "粗糙",
            BR to "高，气息偏弱", W to "重但音量偏小", RA to "慢", C to "略含混", AC to "普通话", PE to "沉稳"),
            "苍老老人声，粗糙带微颤，气息偏弱"),
        VoicePreset("P05", "俏皮少女", mapOf(
            G to "女声", A to "少女", P to "高", T to "清脆", B to "明亮", R to "光滑",
            BR to "中", W to "轻", RA to "快", C to "吐字清晰", AC to "普通话", PE to "俏皮"),
            "俏皮少女，清脆上扬"),
        VoicePreset("P06", "温柔女青年", mapOf(
            G to "女声", A to "青年", P to "中高", T to "甜美圆润", B to "明亮", R to "光滑",
            BR to "中", W to "轻，音量柔和", RA to "适中", C to "吐字清晰", AC to "普通话", PE to "温柔"),
            "温柔女青年，语调柔和"),
        VoicePreset("P10", "磁性低音", mapOf(
            G to "男声", A to "青年", P to "低", T to "磁性", B to "暗", R to "光滑",
            BR to "中", W to "重", RA to "慢", C to "吐字清晰", AC to "普通话", PE to "深沉内敛"),
            "磁性低音男，暗色调，深沉"),
        VoicePreset("P11", "沙哑烟嗓", mapOf(
            G to "女声", A to "青年", P to "低", T to "沙哑", B to "暗", R to "粗糙",
            BR to "高（明显气声）", W to "中", RA to "适中", C to "自然", AC to "普通话", PE to "慵懒"),
            "慵懒沙哑女声，烟嗓感"),
        VoicePreset("P16", "强势暗哑", mapOf(
            G to "女声", A to "中年", P to "低", T to "暗哑", B to "暗", R to "粗糙",
            BR to "中", W to "重", RA to "慢", C to "字字分明", AC to "普通话", PE to "强势"),
            "强势中年女，暗哑低沉，压迫感"),
        VoicePreset("P21", "儒雅轻声", mapOf(
            G to "男声", A to "青年", P to "中", T to "温和", B to "中等", R to "光滑",
            BR to "低", W to "轻，音量偏小", RA to "慢", C to "吐字清晰", AC to "普通话", PE to "儒雅"),
            "儒雅青年，轻声细语，如沐春风")
    )

    private const val G = "GENDER"; private const val A = "AGE"; private const val P = "PITCH"
    private const val T = "TIMBRE"; private const val B = "BRIGHTNESS"; private const val R = "ROUGHNESS"
    private const val BR = "BREATH"; private const val W = "WEIGHT"; private const val RA = "RATE"
    private const val C = "CLARITY"; private const val AC = "ACCENT"; private const val PE = "PERSONA"

    fun find(id: String): VoicePreset? = ALL.find { it.id == id }
}

data class SamplingPreset(
    val id: String, val name: String, val why: String, val knobs: TtsKnobs
)

object SamplingPresets {
    val ALL: List<SamplingPreset> = listOf(
        SamplingPreset("upstream", "上游默认", "qwentts.cpp 原样默认值；先听这个当基准",
            TtsKnobs()),
        SamplingPreset("stable", "稳定朗读 ★", "主/副温度降到 0.6、重复惩罚 1.1，减少语速抖动与电音感",
            TtsKnobs(temperature = 0.6f, subTemperature = 0.6f, repetitionPenalty = 1.1f, seed = 42L)),
        SamplingPreset("drama", "戏剧张力", "主温度 1.1、副 0.8，演绎起伏更大，适合对白多的段",
            TtsKnobs(temperature = 1.1f, subTemperature = 0.8f, seed = 42L)),
        SamplingPreset("deterministic", "完全确定", "温度 0 = 该栈 argmax（qwen.h:308），固定 seed 可逐字节复现",
            TtsKnobs(temperature = 0f, subTemperature = 0f, seed = 42L))
    )
    fun find(id: String): SamplingPreset? = ALL.find { it.id == id }
}

data class MixerRow(val group: String, val label: String, val value: String)

object Mixer {
    fun snapshot(c: Context, cfg: ProduceConfig = ProduceConfig()): List<MixerRow> {
        val k = Prefs.ttsKnobs(c)
        val voice = Prefs.voiceDescription(c).trim().ifBlank { DEFAULT_IDENTITY }
        return buildList {
            add(MixerRow("身份层", "音色描述（instruct ①）", voice))
            add(MixerRow("身份层", "拟声表达", if (Prefs.nonVerbal(c)) "开" else "关"))
            // R221 · 三档停用，风格层只可能是自填附加语气
            add(MixerRow("表演层", "跨书默认风格（新任务）",
                cfg.extraInstruct.trim().ifBlank { "无附加语气" }))
            add(MixerRow("表演层", "instruct 模板版本", "v$INSTRUCT_TEMPLATE_VERSION"))
            add(MixerRow("采样", "seed", if (k.seed < 0) "随机（每次不同）" else k.seed.toString()))
            add(MixerRow("采样", "temperature（主 talker）", k.temperature.toString()))
            add(MixerRow("采样", "top_k / top_p（主）", "${k.topK} / ${k.topP}"))
            add(MixerRow("采样", "repetition_penalty", k.repetitionPenalty.toString()))
            add(MixerRow("采样", "subtalker temperature", k.subTemperature.toString()))
            add(MixerRow("采样", "subtalker top_k / top_p", "${k.subTopK} / ${k.subTopP}"))
            add(MixerRow("边界", "max_new_tokens", "${k.maxNewTokens}（≈ ${k.maxNewTokens * 80 / 1000} 秒音频上限）"))
            add(MixerRow("边界", "语言 lang", "chinese（显式，不用 auto）"))
            add(MixerRow("边界", "段间静音", "${cfg.gapMs} ms"))
            add(MixerRow("边界", "合成并发", "1（CPU 单 compute worker，qwen.h:133）"))
            add(MixerRow("可复现性", "采样指纹", k.hash()))
            add(MixerRow("可复现性", "声音指纹", Prefs.voiceStamp(c)))
        }
    }

    fun logProfile(c: Context, tag: String, extra: String = "") {
        val k = Prefs.ttsKnobs(c)
        val detail = buildString {
            append(tag)
            append(" · seed=").append(k.seed)
            append(" max=").append(k.maxNewTokens)
            append(" temp=").append(k.temperature)
            append(" topK=").append(k.topK)
            append(" topP=").append(k.topP)
            append(" rep=").append(k.repetitionPenalty)
            append(" subTemp=").append(k.subTemperature)
            append(" subTopK=").append(k.subTopK)
            append(" subTopP=").append(k.subTopP)
            append(" · hash=").append(k.hash())
            // R225 · 音色描述是用户填的，只留长度与短哈希
            append(" · voice=").append(EventLog.safe(Prefs.voiceDescription(c)))
            append(" · voiceStamp=").append(Prefs.voiceStamp(c))
            if (extra.isNotBlank()) append(" · ").append(extra)
        }
        EventLog.log(EventLog.Kind.ENGINE, "MIXER_PROFILE", detail)
    }

    const val CHAIN = "repetition_penalty → temperature → top_k → top_p → 采样；副解码器独立一套"

    fun coverage(): List<Triple<String, String, String>> = buildList {
        ParamCatalog.SAMPLING.forEach { add(Triple("到引擎 · 9 项采样", it.official, "可编辑")) }
        ParamCatalog.UPPER.filter { it.kind == ParamKind.ENGINE }
            .forEach { add(Triple("到引擎 · 输入", it.official, "可编辑")) }
        ParamCatalog.UPPER.filter { it.kind == ParamKind.UPPER }
            .forEach { add(Triple("上层 · 不进模型 API", it.label, "上层")) }
        ParamCatalog.LOCKED.forEach { add(Triple("结构 · 换模型才有", it.label, "结构")) }
        listOf("预设库（自定义加/删/覆盖）")
            .forEach { add(Triple("预设", it, "可编辑")) }
    }

    fun logPreset(action: String, p: MixerPreset) {
        EventLog.log(EventLog.Kind.ENGINE, "MIXER_PRESET",
            "$action · id=${p.id} · name=${EventLog.safe(p.name)} · voiceChars=${p.voice.length} · " +
                "dims=${p.fields.size} · hash=${p.knobs.hash()} · perf=${p.perf.audioStamp()} · " +
                "builtin=${p.builtin}")
    }
}
