package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog

/**
 * R40 · 调音台唯一编辑态（P0）。
 *
 * 评审实查：旧版有 5 个能写「生效态」的入口（身份保存 / 九项各自提交 / 12 维直接覆盖 /
 * 表演层长期默认 / 预设应用），界面没有「待提交」这一层，于是「选完 12 维以为生效、
 * 点旁边网格却直接覆盖了 instruct」。这里把它收成：
 *
 *   active（只从 Prefs 读，唯一真值源）  ←→  draft（唯一编辑态，界面只写它）
 *                     ↑ 唯一出口 commit() 写回 Prefs
 *
 * 不手搓依据：
 *  - 单向数据流 / 状态只能在一个位置更新、只有一个可信来源：官方
 *    developer.android.google.cn/jetpack/compose/architecture（R39 §6）。
 *  - 「未保存更改」单一布尔 + 保存后复位：FormViewModel.hasUnsavedChanges /
 *    onDataChanged / onDataSaved 形状（只取状态机语义，不引 ViewModel 依赖）。
 *  - 可复现 profile hash 随配置变化：沿用 R14 的 Prefs.voiceStamp，不新建第二份指纹。
 *
 * 边界：不碰任务书签续跑、ProduceService、native/JNI、qt_tts_params 字段、Gradle 依赖。
 * dims / dimText 只是「帮你写描述」的生成器状态，**不进生效态、不参与 diff、不落盘**。
 */

/** 生效时机只有两种说法，抽成常量，避免每个分区各写一种（评审第 17 条）。 */
const val TIMING_NOW = "提交后新合成起生效；正在跑的任务不受影响"
const val TIMING_NEXT = "提交后对所有新任务生效；正在跑的任务不受影响"

data class MixerDraft(
    val voice: String = "",
    val knobs: TtsKnobs = TtsKnobs(),
    val perf: ProduceConfig = ProduceConfig(),
    val nonVerbal: Boolean = true,
    // 以下两项只是生成器工作区，不写 Prefs、不计入「未提交」
    val dims: Map<String, String> = emptyMap(),
    val dimText: String = ""
) {
    companion object {
        /** 唯一真值源：每次都从既有 Prefs 读，不缓存、不复制第二份。 */
        fun active(c: Context): MixerDraft = MixerDraft(
            voice = Prefs.voiceDescription(c),
            knobs = Prefs.ttsKnobs(c),
            perf = Prefs.defaultPerf(c),
            nonVerbal = Prefs.nonVerbal(c)
        )
    }
}

/** 一行差异：旧值 → 新值 + 生效时机。差异弹层逐项画，不截断 instruct。 */
data class MixerChange(val label: String, val old: String, val new: String, val timing: String)

object MixerCommit {
    private fun showVoice(v: String): String = v.trim().ifBlank { DEFAULT_IDENTITY }
    private fun onOff(b: Boolean): String = if (b) "开" else "关"

    /**
     * 逐字段 diff，界面顶部「未提交 N 项」直接用它算，不由各控件自己上报。
     * dims / dimText 不参与，避免「只是拼了句描述」也被算成待提交。
     */
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
        // R50 · 表演层三枚自定义小框合并后的附加语气，进 diff 才能出现在顶部计数与差异弹层。
        put("附加语气", a.perf.extraInstruct, d.perf.extraInstruct, TIMING_NEXT)
        put("拟声表达", onOff(a.nonVerbal), onOff(d.nonVerbal), TIMING_NOW)
    }

    /**
     * 全 App 调音台唯一写生效态的出口。只走既有 Prefs 写入口，写完后重算
     * voiceStamp（旧 WAV 作废由指纹比对负责），再各写一条可复盘日志。
     */
    fun commit(c: Context, d: MixerDraft, n: Int): String {
        val before = Prefs.voiceStamp(c)
        Prefs.setVoiceDescription(c, d.voice)
        Prefs.setTtsKnobs(c, d.knobs)
        Prefs.setDefaultPerf(c, d.perf)
        Prefs.setNonVerbal(c, d.nonVerbal)
        val after = Prefs.voiceStamp(c)
        EventLog.log(EventLog.Kind.ENGINE, "MIXER_COMMIT",
            "提交 $n 项变更 · hash=${d.knobs.hash()} · voiceStamp $before → $after · " +
                "stampChanged=${before != after}")
        Mixer.logProfile(c, "提交变更（$n 项）")
        return after
    }
}
