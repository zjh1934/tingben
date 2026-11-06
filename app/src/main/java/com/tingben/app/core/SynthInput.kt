package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine

/**
 * R93 · 三处合成（主链 / 两态试听 / 声音自检）共用的唯一装配点。
 *
 * 模式由引擎在 qt_init 时从 talker GGUF 读出的 model_type 决定，这里不决定模式，
 * 只按 qwen.h 的字段语义供料，避免三套调用点各自传 null 而分叉。
 * base 的参考音频在 prepare() 里一次性交给 handle，params() 每段只装字段。
 */
object SynthInput {
    data class Params(val instruct: String?, val speaker: String?)

    /** ttsLoad 成功后、第一段合成之前调一次。 */
    fun prepare(c: Context, handle: Long) {
        when (ModelStore.currentMode(c)) {
            ModelStore.Mode.BASE -> {
                val pcm = VoiceRef.pcm(c)
                val txt = Prefs.refText(c).takeIf { it.isNotBlank() }
                if (!NativeEngine.ttsSetVoiceRef(handle, pcm, txt))
                    throw IllegalStateException("参考音频没能交给引擎")
                EventLog.log(EventLog.Kind.ENGINE, "VOICE_REF_APPLIED",
                    "${pcm.size} samples · refText=${if (txt == null) "空（克隆模式）" else "${txt.length} 字（ICL）"}")
            }
            else -> NativeEngine.ttsClearVoiceRef(handle)
        }
    }

    /**
     * @param instructVD voice_design 用的最终 instruct（身份层 + 风格层 + 本段导演指令）
     * @param style      朗读档位等风格短语；custom_voice 下与念法要求一起当可选 instruct
     */
    fun params(c: Context, instructVD: String?, style: String?): Params =
        when (ModelStore.currentMode(c)) {
            ModelStore.Mode.VOICE_DESIGN -> Params(instructVD, null)
            ModelStore.Mode.CUSTOM_VOICE -> {
                val tone = listOf(Prefs.voiceDescription(c).trim(), style?.trim().orEmpty())
                    .filter { it.isNotEmpty() }.joinToString("；")
                Params(tone.ifBlank { null }, Prefs.speakerName(c).takeIf { it.isNotBlank() })
            }
            ModelStore.Mode.BASE -> Params(null, null)
        }

    fun label(c: Context): String = when (ModelStore.currentMode(c)) {
        ModelStore.Mode.VOICE_DESIGN -> "voice_design · 描述造音色"
        ModelStore.Mode.CUSTOM_VOICE -> "custom_voice · ${Prefs.speakerName(c).ifBlank { "未选音色" }}"
        ModelStore.Mode.BASE -> "base · 参考音频克隆"
    }
}
