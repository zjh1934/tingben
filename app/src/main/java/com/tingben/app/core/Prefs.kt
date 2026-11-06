package com.tingben.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 用户能改的东西全部集中在这里，不散落在界面里。
 *
 * 抄法说明（R14 · 不手搓）：
 * - `KEY_* / DEFAULT_*` 常量集中：抄 OfflineLLM 的 SettingsRepository.kt
 * - 9 个 TTS 采样默认值：逐字抄 qwentts.cpp/src/sampling-defaults.h（MIT）
 * - schema 版本 + 宽容迁移：抄 PocketPal 的 completionSettingsVersions.ts
 * - profileHash 用于缓存失效：抄 ReaderVoice 的 CosyVoiceVoiceProfile.kt
 */
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

/** 一次把 9 个采样参数传给 JNI，避免加 8 个裸参数导致字段顺序漂移。 */
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
    /** 采样配置变了 ⇒ 旧 WAV 不能复用。抄 CosyVoiceVoiceProfile.profileHash 的思路。 */
    fun hash(): String = sha(
        listOf(seed, maxNewTokens, temperature, topK, topP, repetitionPenalty,
            subTemperature, subTopK, subTopP).joinToString("|"))
}

fun sha(s: String): String = try {
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
} catch (_: Throwable) { s.hashCode().toString(16) }

object Prefs {
    /** 配置结构版本。旧设置缺字段一律回默认值，不崩（抄 PocketPal）。 */
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
    // 纯 UI 偏好：不进 voiceStamp、不进 diff、不碰任务书签
    private const val KEY_MIXER_OPEN = "mixerOpenSections"
    private const val KEY_RECENT_VOICES = "mixerRecentVoices"
    // R73 · P2 软件设置：主题 0 跟随 / 1 浅 / 2 深；正文字号 12–24sp。
    private const val KEY_THEME_MODE = "themeMode"
    private const val KEY_FONT_SCALE = "readerFontScale"
    // R84 · 当前启用的音频模式 id；不进 voiceStamp，切模式不误删旧 WAV。
    private const val KEY_MODEL_MODE = "modelMode"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 版本化入口：每次读之前确保 schema 已写；旧版本没有的字段由 get* 默认值兜住。 */
    private fun migrate(c: Context) {
        val s = sp(c)
        if (s.getInt(KEY_SCHEMA, 1) < SCHEMA_VERSION) {
            s.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply()
        }
    }

    // 旧思考档位设置键已退场，不再读写。

    fun nCtx(c: Context) = sp(c).getInt(KEY_NCTX, 4096)
    fun setNCtx(c: Context, v: Int) = sp(c).edit().putInt(KEY_NCTX, v).apply()

    fun threads(c: Context) = sp(c).getInt(KEY_THREADS, 4)
    fun setThreads(c: Context, v: Int) = sp(c).edit().putInt(KEY_THREADS, v).apply()

    // 0 = 贪心，输出 JSON 最稳
    fun temperature(c: Context) = sp(c).getFloat(KEY_TEMPERATURE, 0f)
    fun setTemperature(c: Context, v: Float) = sp(c).edit().putFloat(KEY_TEMPERATURE, v).apply()

    // R11 · 非语言表达：哭腔 / 笑 / 叹气等，默认开；只增强导演指令，不动原文
    fun nonVerbal(c: Context) = sp(c).getBoolean(KEY_NONVERBAL, true)
    fun setNonVerbal(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_NONVERBAL, v).apply()

    // ── 声音：固定音色描述（voice_design 的 instruct 身份层）──
    /** 留空 = 沿用原来的单一默认声音，不擅自改用户已经听惯的效果。 */
    fun voiceDescription(c: Context): String {
        migrate(c)
        return sp(c).getString(KEY_VOICE_DESC, "") ?: ""
    }
    fun setVoiceDescription(c: Context, v: String) =
        sp(c).edit().putString(KEY_VOICE_DESC, v.trim()).apply()

    // ── 声音：9 个采样参数（默认值来自 qwentts.cpp，不再硬编码在 JNI）──
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

    /** R36 · 新任务的默认表演层。只影响「以后新建的任务」，不回写正在跑的 TaskInfo。 */
    fun defaultPerf(c: Context): ProduceConfig = try {
        ProduceConfig.fromJson(JSONObject(sp(c).getString(KEY_DEFAULT_PERF, "") ?: ""))
    } catch (_: Throwable) { ProduceConfig() }

    fun setDefaultPerf(c: Context, v: ProduceConfig) =
        sp(c).edit().putString(KEY_DEFAULT_PERF, v.toJson().toString()).apply()

    // ── R41 · 调音台 UI 偏好（纯界面，不参与声音指纹）──
    /** R45 · 默认全部折叠；首屏身份卡常驻不折叠。只有用户主动展开过才落盘，
     *  所以改默认值对老用户也立即生效，不覆盖手动记住的展开态。 */
    fun mixerOpenSections(c: Context): Set<String> =
        (sp(c).getString(KEY_MIXER_OPEN, null) ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setMixerOpenSections(c: Context, keys: Set<String>) =
        sp(c).edit().putString(KEY_MIXER_OPEN, keys.sorted().joinToString(",")).apply()

    /** 「最近使用」最多 4 条，最新在前；只存 VoicePreset.id。 */
    // R84 · 1 底座 + 3 模式里的当前模式，默认描述造音色（兼容旧数据）。
    fun modelMode(c: Context): String =
        sp(c).getString(KEY_MODEL_MODE, "voice_design") ?: "voice_design"
    fun setModelMode(c: Context, v: String) =
        sp(c).edit().putString(KEY_MODEL_MODE, v).apply()

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

    /**
     * 声音指纹：音色描述 / 采样 / instruct 模板 任一变化，旧 WAV 必须作废。
     * 抄 ReaderVoice 的 profileHash 思路。角色表整套退场后不再进指纹。
     */
    fun voiceStamp(c: Context): String = sha(listOf(
        INSTRUCT_TEMPLATE_VERSION.toString(),
        voiceDescription(c),
        ttsKnobs(c).hash()
    ).joinToString("||"))

    /**
     * 迁移专用：旧版四项指纹（含角色表）。只用于识别手机上 voice_stamp.txt 里
     * 存的旧值 —— 命中就不作废旧 WAV，并把文件改写成新值。算法固定，
     * 角色表读法内联，不依赖已删除的 VoiceCast。
     */
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
        // 只报软件自身状态与声音配置。
        append("主题 ").append(when (themeMode(c)) {
            1 -> "浅色"; 2 -> "深色"; else -> "跟随系统"
        })
        append(" · 正文字号 ").append(readerFontScale(c).toInt())
        append(" · 拟声表达 ").append(if (nonVerbal(c)) "开" else "关")
        append("\n声音 · ").append(if (voiceDescription(c).isBlank()) "默认身份锚点" else "自定义音色")
        append(" · instruct v").append(INSTRUCT_TEMPLATE_VERSION)
        append(" · seed ").append(ttsKnobs(c).seed)
        append(" · 指纹 ").append(voiceStamp(c))
    }
}
