package com.tingben.app.engine

import com.tingben.app.core.TtsKnobs

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

    /**
     * R14 · 采样参数不再硬编码在 JNI。用 data class 一次传，避免加 8 个裸参数
     * 导致字段顺序漂移（抄 qwentts.cpp 的 qt_tts_default_params → 逐字段覆盖模式）。
     * 名字保持 ttsSynthesize，调用点只把最后的 seed 换成 knobs。
     */
    fun ttsSynthesize(
        handle: Long, text: String, instruct: String?, speaker: String?,
        lang: String?, outWavPath: String, knobs: TtsKnobs
    ): Int = ttsSynthesizeRaw(handle, text, instruct, speaker, lang, outWavPath,
        knobs.seed, knobs.maxNewTokens, knobs.temperature, knobs.topK, knobs.topP,
        knobs.repetitionPenalty, knobs.subTemperature, knobs.subTopK, knobs.subTopP)

    private external fun ttsSynthesizeRaw(
        handle: Long, text: String, instruct: String?, speaker: String?,
        lang: String?, outWavPath: String, seed: Long, maxNewTokens: Int,
        temperature: Float, topK: Int, topP: Float, repetitionPenalty: Float,
        subTemperature: Float, subTopK: Int, subTopP: Float
    ): Int

    external fun ttsSpeakers(handle: Long): String
    external fun ttsFree(handle: Long)
}
