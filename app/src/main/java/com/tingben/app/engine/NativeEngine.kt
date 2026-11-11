package com.tingben.app.engine

import com.tingben.app.core.TtsKnobs
import com.tingben.app.diag.EventLog

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

    private val loadGate = Any()
    @Volatile private var loadBusy = false

    fun tryBeginTtsLoad(): Boolean = synchronized(loadGate) {
        if (loadBusy) false else { loadBusy = true; true }
    }

    fun endTtsLoad() { synchronized(loadGate) { loadBusy = false } }

    fun ttsLoadGated(talkerPath: String, codecPath: String, device: String?): Long {
        if (!tryBeginTtsLoad()) return 0L
        return try { ttsLoad(talkerPath, codecPath, device) }
               catch (t: Throwable) { endTtsLoad(); throw t }
    }

    fun ttsFreeGated(handle: Long) {
        try { ttsFree(handle) } finally { endTtsLoad() }
    }

    fun ttsSynthesize(
        handle: Long, text: String, instruct: String?, speaker: String?,
        lang: String?, outWavPath: String, knobs: TtsKnobs
    ): Int {
        EventLog.log(EventLog.Kind.ENGINE, "TTS_PARAMS",
            "seed=${knobs.seed} max=${knobs.maxNewTokens} temp=${knobs.temperature} " +
                "topK=${knobs.topK} topP=${knobs.topP} rep=${knobs.repetitionPenalty} " +
                "subTemp=${knobs.subTemperature} subTopK=${knobs.subTopK} subTopP=${knobs.subTopP} " +
                "lang=${lang ?: "auto"} instructChars=${instruct?.length ?: 0} " +
                "speaker=${EventLog.safe(speaker)} hash=${knobs.hash()}",
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

    external fun ttsSetVoiceRef(handle: Long, pcm: FloatArray, refText: String?): Boolean
    external fun ttsClearVoiceRef(handle: Long)

    external fun ttsSpeakers(handle: Long): String
    external fun ttsFree(handle: Long)
}
