package com.tingben.app.engine

import com.tingben.app.core.TtsKnobs
import com.tingben.app.diag.EventLog

/**
 * 听本 · JNI 入口（移植自 OfflineLLM 的 llama.cpp Android 构建方式）。
 * libllama.so / libqwen.so 由 scripts/build_native.sh 在 CI 交叉编译，
 * 两个上游共用唯一一份 ggml（旧项目真机 dlopen failed 换来的硬规则）。
 * .so 没进包时明确失败，不假装已就绪。
 */
object NativeEngine {
    @Volatile private var loaded = false
    @Volatile private var err: String? = null

    fun isAvailable(): Boolean = ensure()
    fun shortReason(): String = err ?: ""
    fun version(): String = try { nativeVersion() } catch (t: Throwable) { "不可用" }

    @Synchronized
    private fun ensure(): Boolean {
        if (loaded) return true
        if (err != null) return false
        return try {
            System.loadLibrary("tingben_native")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            err = "本地引擎没装进这个安装包：${e.message ?: "找不到 libtingben_native.so"}"
            false
        } catch (t: Throwable) {
            err = "本地引擎加载失败：${t.javaClass.simpleName}"
            false
        }
    }

    external fun nativeVersion(): String
    external fun nativeCancel()
    external fun nativeCancelReset()

    external fun llmLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun llmGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String
    external fun llmFree(handle: Long)

    external fun ttsLoad(talkerPath: String, codecPath: String, device: String?): Long

    // R105 · 全局单 loader：连点两次试听、或试听与正式制作撞上，不再同时载两份 2GB talker。
    // 门闩只管「装载」这一段；native 的逐采样取消入口是 nativeCancel()，两者各管一件事。
    private val loadGate = Any()
    @Volatile private var loadBusy = false

    fun tryBeginTtsLoad(): Boolean = synchronized(loadGate) {
        if (loadBusy) false else { loadBusy = true; true }
    }

    fun endTtsLoad() { synchronized(loadGate) { loadBusy = false } }

    /** 带门闩的装载：已有一次在跑时返回 0L，调用方放弃而不是再载一份。 */
    fun ttsLoadGated(talkerPath: String, codecPath: String, device: String?): Long {
        if (!tryBeginTtsLoad()) return 0L
        return try { ttsLoad(talkerPath, codecPath, device) }
               catch (t: Throwable) { endTtsLoad(); throw t }
    }

    /** 与 ttsLoadGated 成对；重复调用是幂等的（门闩是布尔，不是计数）。 */
    fun ttsFreeGated(handle: Long) {
        try { ttsFree(handle) } finally { endTtsLoad() }
    }

    /**
     * R14 · 采样参数不再硬编码在 JNI。用 data class 一次传，避免加 8 个裸参数
     * 导致字段顺序漂移（抄 qwentts.cpp 的 qt_tts_default_params → 逐字段覆盖模式）。
     * 名字保持 ttsSynthesize，调用点只把最后的 seed 换成 knobs。
     */
    fun ttsSynthesize(
        handle: Long, text: String, instruct: String?, speaker: String?,
        lang: String?, outWavPath: String, knobs: TtsKnobs
    ): Int {
        // R38 · 触达证据：三条合成路径（主链 / 试音 / A/B）共用一个包装，
        // 把「UI 改过的值实际传给引擎了什么」写进诊断日志，用户能逐项对照控制台。
        EventLog.log(EventLog.Kind.ENGINE, "TTS_PARAMS",
            "seed=${knobs.seed} max=${knobs.maxNewTokens} temp=${knobs.temperature} " +
                "topK=${knobs.topK} topP=${knobs.topP} rep=${knobs.repetitionPenalty} " +
                "subTemp=${knobs.subTemperature} subTopK=${knobs.subTopK} subTopP=${knobs.subTopP} " +
                "lang=${lang ?: "auto"} instructChars=${instruct?.length ?: 0} " +
                "speaker=${speaker ?: "null"} hash=${knobs.hash()}",
            level = EventLog.Level.TRACE)
        return ttsSynthesizeRaw(handle, text, instruct, speaker, lang, outWavPath,
            knobs.seed, knobs.maxNewTokens, knobs.temperature, knobs.topK, knobs.topP,
            knobs.repetitionPenalty, knobs.subTemperature, knobs.subTopK, knobs.subTopP)
    }

    private external fun ttsSynthesizeRaw(
        handle: Long, text: String, instruct: String?, speaker: String?,
        lang: String?, outWavPath: String, seed: Long, maxNewTokens: Int,
        temperature: Float, topK: Int, topP: Float, repetitionPenalty: Float,
        subTemperature: Float, subTopK: Int, subTopP: Float
    ): Int

    /** R93 · base 模式：把 24kHz 单声道 PCM 与转录文本交给引擎，整个合成期间有效。 */
    external fun ttsSetVoiceRef(handle: Long, pcm: FloatArray, refText: String?): Boolean
    external fun ttsClearVoiceRef(handle: Long)

    external fun ttsSpeakers(handle: Long): String
    external fun ttsFree(handle: Long)
}
