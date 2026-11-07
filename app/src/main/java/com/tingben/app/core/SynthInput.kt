package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine

object SynthInput {
    data class Params(val instruct: String?, val speaker: String?)

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

    fun params(c: Context, instructVD: String?, style: String?): Params =
        when (ModelStore.currentMode(c)) {
            ModelStore.Mode.VOICE_DESIGN -> Params(instructVD, null)
            ModelStore.Mode.CUSTOM_VOICE -> {
                val tone = listOf(Prefs.cvStyle(c).trim(), style?.trim().orEmpty())
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
