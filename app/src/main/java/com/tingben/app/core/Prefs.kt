package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object TtsDefaults {
    const val MAX_NEW_TOKENS = 2048
    const val TEMPERATURE = 0.9f
    const val TOP_K = 50
    const val TOP_P = 1.0f
    const val REPETITION_PENALTY = 1.05f
    const val SUBTALKER_TEMPERATURE = 0.9f
    const val SUBTALKER_TOP_K = 50
    const val SUBTALKER_TOP_P = 1.0f
    const val SEED = 42L
}

data class TtsKnobs(
    val seed: Long = TtsDefaults.SEED,
    val maxNewTokens: Int = TtsDefaults.MAX_NEW_TOKENS,
    val temperature: Float = TtsDefaults.TEMPERATURE,
    val topK: Int = TtsDefaults.TOP_K,
    val topP: Float = TtsDefaults.TOP_P,
    val repetitionPenalty: Float = TtsDefaults.REPETITION_PENALTY,
    val subTemperature: Float = TtsDefaults.SUBTALKER_TEMPERATURE,
    val subTopK: Int = TtsDefaults.SUBTALKER_TOP_K,
    val subTopP: Float = TtsDefaults.SUBTALKER_TOP_P
) {
    fun hash(): String = sha(
        listOf(seed, maxNewTokens, temperature, topK, topP, repetitionPenalty,
            subTemperature, subTopK, subTopP).joinToString("|"))
}

fun sha(s: String): String = try {
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
} catch (_: Throwable) { s.hashCode().toString(16) }

// R223 · 公开资料里适合长篇有声书的青年女声描述（云声配音 / DevPress /
// Qwen 官方 VoiceDesign 控制示例 / Wiro 官方 Vivian-Serena 音色说明综合）。
// 采样参数不决定性别年龄，女声只能由 instruct 身份层给。
const val DEFAULT_VOICE = "声线清澈的青年女声，约 20–25 岁，标准普通话无明显口音；" +
        "音调适中偏亮、不刺耳，吐字清晰，语调平和温柔，语速自然，适合长篇有声书朗读。"

object Prefs {
    const val SCHEMA_VERSION = 2
    private const val FILE = "tingben_settings"
    private const val KEY_SCHEMA = "schema"

    private const val KEY_NCTX = "nCtx"
    private const val KEY_THREADS = "threads"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_NONVERBAL = "nonVerbal"

    private const val KEY_VOICE_DESC = "voiceDesc"
    private const val KEY_SEED = "ttsSeed"
    private const val KEY_TTS_MAX = "ttsMax"
    private const val KEY_TTS_TEMP = "ttsTemp"
    private const val KEY_TTS_TOPK = "ttsTopK"
    private const val KEY_TTS_TOPP = "ttsTopP"
    private const val KEY_TTS_REP = "ttsRep"
    private const val KEY_TTS_SUBTEMP = "ttsSubTemp"
    private const val KEY_TTS_SUBTOPK = "ttsSubTopK"
    private const val KEY_TTS_SUBTOPP = "ttsSubTopP"
    private const val KEY_DEFAULT_PERF = "defaultPerf"
    private const val KEY_MIXER_OPEN = "mixerOpenSections"
    private const val KEY_RECENT_VOICES = "mixerRecentVoices"
    private const val KEY_THEME_MODE = "themeMode"
    private const val KEY_FONT_SCALE = "readerFontScale"
    private const val KEY_MODEL_MODE = "modelMode"
    private const val KEY_SPEAKER = "speakerName"
    private const val KEY_SPEAKER_NAMES = "speakerNames"
    private const val KEY_REF_TEXT = "refText"
    private const val KEY_CV_STYLE = "cvStyle"
    private const val KEY_TRIAL_TEXT = "trialText"
    // R159 · 开软件是否跳过确认直接续跑。默认关（仍弹窗问一句）。
    private const val KEY_AUTO_RESUME = "autoResumeOnStart"
    // R161 · 可选悬浮进度控件，默认关。它不是保活手段，只让进度看得见。
    private const val KEY_OVERLAY_PROGRESS = "overlayProgress"
    // R231 · 拼接偏好：按章自动拼成品 / 接缝淡入淡出时长
    private const val KEY_MERGE_AUTO = "mergeAutoChapter"
    private const val KEY_MERGE_DECLICK = "mergeDeclickMs"

    const val DEFAULT_TRIAL_TEXT = "雨下了一整夜，她终于忍不住笑出声，又轻轻叹了口气。"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun migrate(c: Context) {
        val s = sp(c)
        if (s.getInt(KEY_SCHEMA, 1) < SCHEMA_VERSION) {
            s.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
        }
    }

    fun autoResumeOnStart(c: Context) = sp(c).getBoolean(KEY_AUTO_RESUME, false)
    fun setAutoResumeOnStart(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_AUTO_RESUME, v).apply()

    fun overlayProgress(c: Context) = sp(c).getBoolean(KEY_OVERLAY_PROGRESS, false)
    fun setOverlayProgress(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_OVERLAY_PROGRESS, v).apply()

    // R231 · 默认开：每章小块齐了就单独出一份这一章的音频。
    // 存 Prefs 而非 ProduceConfig —— 改它不该让已做好的段换套归档。
    fun autoMergeChapter(c: Context) = sp(c).getBoolean(KEY_MERGE_AUTO, true)
    fun setAutoMergeChapter(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_MERGE_AUTO, v).apply()
    fun mergeDeclickMs(c: Context) = sp(c).getInt(KEY_MERGE_DECLICK, 30).coerceIn(0, 60)
    fun setMergeDeclickMs(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_MERGE_DECLICK, v.coerceIn(0, 60)).apply()

    fun nCtx(c: Context) = sp(c).getInt(KEY_NCTX, 4096)
    fun setNCtx(c: Context, v: Int) = sp(c).edit().putInt(KEY_NCTX, v).apply()

    fun threads(c: Context) = sp(c).getInt(KEY_THREADS, 4)
    fun setThreads(c: Context, v: Int) = sp(c).edit().putInt(KEY_THREADS, v).apply()

    fun temperature(c: Context) = sp(c).getFloat(KEY_TEMPERATURE, 0f)
    fun setTemperature(c: Context, v: Float) = sp(c).edit().putFloat(KEY_TEMPERATURE, v).apply()

    fun nonVerbal(c: Context) = sp(c).getBoolean(KEY_NONVERBAL, true)
    fun setNonVerbal(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_NONVERBAL, v).apply()

    // R223 · 用 contains 区分「从没设置过」与「用户显式清空」：
    // 前者给新默认女声；后者回落到 VoiceProfile.DEFAULT_IDENTITY（旧中性串），
    // 且 voiceStamp 也回到旧值 —— 调音台点「清空」就能切回升级前那套、
    // 认领回已做好的段，不丢进度。
    fun voiceDescription(c: Context): String {
        migrate(c)
        val s = sp(c)
        return if (s.contains(KEY_VOICE_DESC)) s.getString(KEY_VOICE_DESC, "") ?: ""
        else DEFAULT_VOICE
    }
    fun setVoiceDescription(c: Context, v: String) =
        sp(c).edit().putString(KEY_VOICE_DESC, v.trim()).apply()

    fun ttsKnobs(c: Context): TtsKnobs {
        migrate(c)
        val s = sp(c)
        return TtsKnobs(
            seed = s.getLong(KEY_SEED, TtsDefaults.SEED),
            maxNewTokens = s.getInt(KEY_TTS_MAX, TtsDefaults.MAX_NEW_TOKENS),
            temperature = s.getFloat(KEY_TTS_TEMP, TtsDefaults.TEMPERATURE),
            topK = s.getInt(KEY_TTS_TOPK, TtsDefaults.TOP_K),
            topP = s.getFloat(KEY_TTS_TOPP, TtsDefaults.TOP_P),
            repetitionPenalty = s.getFloat(KEY_TTS_REP, TtsDefaults.REPETITION_PENALTY),
            subTemperature = s.getFloat(KEY_TTS_SUBTEMP, TtsDefaults.SUBTALKER_TEMPERATURE),
            subTopK = s.getInt(KEY_TTS_SUBTOPK, TtsDefaults.SUBTALKER_TOP_K),
            subTopP = s.getFloat(KEY_TTS_SUBTOPP, TtsDefaults.SUBTALKER_TOP_P)
        )
    }

    fun setTtsKnobs(c: Context, k: TtsKnobs) {
        sp(c).edit()
            .putLong(KEY_SEED, k.seed)
            .putInt(KEY_TTS_MAX, k.maxNewTokens)
            .putFloat(KEY_TTS_TEMP, k.temperature)
            .putInt(KEY_TTS_TOPK, k.topK)
            .putFloat(KEY_TTS_TOPP, k.topP)
            .putFloat(KEY_TTS_REP, k.repetitionPenalty)
            .putFloat(KEY_TTS_SUBTEMP, k.subTemperature)
            .putInt(KEY_TTS_SUBTOPK, k.subTopK)
            .putFloat(KEY_TTS_SUBTOPP, k.subTopP)
            .apply()
    }

    fun resetTtsKnobs(c: Context) = setTtsKnobs(c, TtsKnobs())

    fun defaultPerf(c: Context): ProduceConfig = try {
        ProduceConfig.fromJson(JSONObject(sp(c).getString(KEY_DEFAULT_PERF, "") ?: ""))
    } catch (_: Throwable) { ProduceConfig() }

    fun setDefaultPerf(c: Context, v: ProduceConfig) =
        sp(c).edit().putString(KEY_DEFAULT_PERF, v.toJson().toString()).apply()

    fun mixerOpenSections(c: Context): Set<String> =
        (sp(c).getString(KEY_MIXER_OPEN, null) ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setMixerOpenSections(c: Context, keys: Set<String>) =
        sp(c).edit().putString(KEY_MIXER_OPEN, keys.sorted().joinToString(",")).apply()

    fun modelMode(c: Context): String =
        sp(c).getString(KEY_MODEL_MODE, "voice_design") ?: "voice_design"
    // R327 · 只放行三种合法模式，非法值不再写进设置，避免下次读取时静默
    // 兜回模式一。合法写入全部来自调音台切模式 / 模型页「启用」「改用」按钮。
    fun setModelMode(c: Context, v: String) {
        if (ModelStore.Mode.values().none { it.id == v }) {
            EventLog.log(EventLog.Kind.MODEL, "MODEL_MODE_REJECT",
                "不认识的模式值没写进设置 · raw=${EventLog.safe(v)}",
                level = EventLog.Level.WARN)
            return
        }
        sp(c).edit().putString(KEY_MODEL_MODE, v).apply()
    }

    fun speakerName(c: Context): String = sp(c).getString(KEY_SPEAKER, "") ?: ""
    fun setSpeakerName(c: Context, v: String) =
        sp(c).edit().putString(KEY_SPEAKER, v.trim()).apply()

    fun cvStyle(c: Context): String = sp(c).getString(KEY_CV_STYLE, "") ?: ""
    fun setCvStyle(c: Context, v: String) =
        sp(c).edit().putString(KEY_CV_STYLE, v.trim()).apply()

    fun speakerNames(c: Context): List<String> =
        (sp(c).getString(KEY_SPEAKER_NAMES, "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun setSpeakerNames(c: Context, names: List<String>) =
        sp(c).edit().putString(KEY_SPEAKER_NAMES, names.joinToString(",")).apply()

    fun trialText(c: Context): String =
        (sp(c).getString(KEY_TRIAL_TEXT, DEFAULT_TRIAL_TEXT) ?: DEFAULT_TRIAL_TEXT)
            .ifBlank { DEFAULT_TRIAL_TEXT }
    fun setTrialText(c: Context, v: String) =
        sp(c).edit().putString(KEY_TRIAL_TEXT, v).apply()

    fun refText(c: Context): String = sp(c).getString(KEY_REF_TEXT, "") ?: ""
    fun setRefText(c: Context, v: String) =
        sp(c).edit().putString(KEY_REF_TEXT, v.trim()).apply()

    fun themeMode(c: Context) = sp(c).getInt(KEY_THEME_MODE, 0)

    fun setThemeMode(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_THEME_MODE, v.coerceIn(0, 2)).apply()

    fun readerFontScale(c: Context) = sp(c).getFloat(KEY_FONT_SCALE, 16f)
        .coerceIn(12f, 24f)

    fun setReaderFontScale(c: Context, v: Float) =
        sp(c).edit().putFloat(KEY_FONT_SCALE, v.coerceIn(12f, 24f)).apply()

    fun recentVoices(c: Context): List<String> =
        (sp(c).getString(KEY_RECENT_VOICES, "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)

    fun pushRecentVoice(c: Context, id: String) {
        val next = (listOf(id) + recentVoices(c).filter { it != id }).distinct().take(4)
        sp(c).edit().putString(KEY_RECENT_VOICES, next.joinToString(",")).apply()
    }

    fun voiceStamp(c: Context): String = sha(listOf(
        INSTRUCT_TEMPLATE_VERSION.toString(),
        voiceDescription(c),
        ttsKnobs(c).hash()
    ).joinToString("||"))

    fun legacyVoiceStamp(c: Context): String = sha(listOf(
        INSTRUCT_TEMPLATE_VERSION.toString(),
        voiceDescription(c),
        if (sp(c).getBoolean("castEnabled", true)) legacyCastHash(c) else "off",
        ttsKnobs(c).hash()
    ).joinToString("||"))

    private fun legacyCastHash(c: Context): String = try {
        val f = File(c.filesDir, "cast.json")
        if (f.exists()) sha(f.readText()) else "empty"
    } catch (_: Throwable) { "err" }

    fun summary(c: Context) = buildString {
        append("主题 ").append(when (themeMode(c)) {
            1 -> "浅色"; 2 -> "深色"; else -> "跟随系统"
        })
        append(" · 正文字号 ").append(readerFontScale(c).toInt())
        append("\n声音 · 模式 ").append(when (modelMode(c)) {
            "custom_voice" -> "预设音色"
            "base" -> "参考音频"
            else -> "描述造音色"
        })
        append(" · 音色 ").append(if (voiceDescription(c).isBlank()) "默认" else "自定义")
        when (modelMode(c)) {
            "custom_voice" -> append(" · 预设音色 ").append(speakerName(c).ifBlank { "未选" })
            "base" -> append(" · 参考音频 ").append(if (VoiceRef.exists(c)) "已导入" else "未导入")
        }
        append(" · seed ").append(ttsKnobs(c).seed)
    }
}
