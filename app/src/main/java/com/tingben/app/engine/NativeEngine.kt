package com.tingben.app.engine

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

    external fun llmLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun llmGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String
    external fun llmFree(handle: Long)

    external fun ttsLoad(talkerPath: String, codecPath: String, device: String?): Long
    external fun ttsSynthesize(
        handle: Long, text: String, instruct: String?, speaker: String?,
        lang: String?, outWavPath: String, seed: Long
    ): Int
    external fun ttsSpeakers(handle: Long): String
    external fun ttsFree(handle: Long)
}
