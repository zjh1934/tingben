package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog

const val TIMING_NOW = "保存后新合成起生效；正在跑的任务不受影响"
const val TIMING_NEXT = "保存后对所有新任务生效；正在跑的任务不受影响"

data class MixerDraft(
    val voice: String = "",
    val knobs: TtsKnobs = TtsKnobs(),
    val perf: ProduceConfig = ProduceConfig(),
    val nonVerbal: Boolean = true,
    val speaker: String = "",
    val refText: String = "",
    val cvStyle: String = "",
    val dims: Map<String, String> = emptyMap(),
    val dimText: String = ""
) {
    companion object {
        fun active(c: Context): MixerDraft = MixerDraft(
            voice = Prefs.voiceDescription(c),
            knobs = Prefs.ttsKnobs(c),
            perf = Prefs.defaultPerf(c),
            nonVerbal = Prefs.nonVerbal(c),
            speaker = Prefs.speakerName(c),
            refText = Prefs.refText(c),
            cvStyle = Prefs.cvStyle(c)
        )
    }
}

data class MixerChange(val label: String, val old: String, val new: String, val timing: String)

object MixerCommit {
    private fun showVoice(v: String): String = v.trim().ifBlank { DEFAULT_IDENTITY }
    private fun onOff(b: Boolean): String = if (b) "开" else "关"

    fun diff(a: MixerDraft, d: MixerDraft): List<MixerChange> = buildList {
        fun put(label: String, old: String, new: String, timing: String) {
            if (old != new) add(MixerChange(label, old, new, timing))
        }
        put("音色身份（全书固定）", showVoice(a.voice), showVoice(d.voice), TIMING_NOW)
        ParamCatalog.SAMPLING.forEach { s ->
            put(s.label, s.read(a.knobs), s.read(d.knobs), TIMING_NOW)
        }
        put("朗读档位", a.perf.style.label, d.perf.style.label, TIMING_NEXT)
        put("语速倾向", a.perf.pace.label, d.perf.pace.label, TIMING_NEXT)
        put("情绪强度", a.perf.intensity.label, d.perf.intensity.label, TIMING_NEXT)
        put("段间静音", "${a.perf.gapMs} ms", "${d.perf.gapMs} ms", TIMING_NEXT)
        put("失败重做", "${a.perf.gateRetries} 次", "${d.perf.gateRetries} 次", TIMING_NEXT)
        put("附加语气", a.perf.extraInstruct, d.perf.extraInstruct, TIMING_NEXT)
        put("拟声表达", onOff(a.nonVerbal), onOff(d.nonVerbal), TIMING_NOW)
        put("预设音色", a.speaker, d.speaker, TIMING_NOW)
        put("参考音频转录", a.refText, d.refText, TIMING_NOW)
        put("预设音色念法", a.cvStyle, d.cvStyle, TIMING_NOW)
    }

    fun commit(c: Context, d: MixerDraft, n: Int): String {
        val before = Prefs.voiceStamp(c)
        Prefs.setVoiceDescription(c, d.voice)
        Prefs.setTtsKnobs(c, d.knobs)
        Prefs.setDefaultPerf(c, d.perf)
        Prefs.setNonVerbal(c, d.nonVerbal)
        Prefs.setSpeakerName(c, d.speaker)
        Prefs.setRefText(c, d.refText)
        Prefs.setCvStyle(c, d.cvStyle)
        val after = Prefs.voiceStamp(c)
        EventLog.log(EventLog.Kind.ENGINE, "MIXER_COMMIT",
            "保存 $n 项 · hash=${d.knobs.hash()} · voiceStamp $before → $after · " +
                "stampChanged=${before != after}")
        Mixer.logProfile(c, "保存（$n 项）")
        return after
    }
}
