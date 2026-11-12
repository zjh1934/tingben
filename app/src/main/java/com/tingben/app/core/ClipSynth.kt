package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import java.io.File

/**
 * R215 / R276 · 「首页片段」与「调音台试听」共用的合成真源。
 *
 * 只管一件事：一段文字 → 一个 wav 绝对路径。不建书、不落书架、
 * 不碰章节表、不写任务、不起前台服务。调用方自己决定怎么播、往哪存：
 * 不传 outFile 就落 cacheDir/trial（试听，播完即丢），传了就落调用方
 * 给的正式文件（首页片段落 filesDir/clips，R276）。
 * 首页不是「粘上就响」：贴完点合成，产物进下方留白列表，合成要等几分钟。
 *
 * 模型口径、三种 mode 的参数、空音频判定都与正式制作同源，
 * 不再让首页和调音台各维护一份 —— 省得改了一边音色对不上。
 */
object ClipSynth {

    /** 单次合成能稳稳念完的上限；超了明确提示，不静默截断。 */
    const val MAX_CHARS = 1500

    /**
     * 阻塞方法，必须在 IO 线程调。失败抛中文原因的 IllegalStateException。
     * cancelled 由协程调用方传入，native 段之间检查，取消就别再往下合成。
     */
    fun synthesize(c: Context, text: String, outName: String, label: String,
                   cancelled: () -> Boolean = { false },
                   outFile: File? = null): String {
        if (text.isBlank()) throw IllegalStateException("先放点文字进来")
        if (text.length > MAX_CHARS)
            throw IllegalStateException("这段 ${text.length} 字太长，最多 $MAX_CHARS 字，先少粘一点")
        if (cancelled()) throw IllegalStateException("已取消")
        if (Producer.isBusy()) throw IllegalStateException("正在做整本书，做完再听这段")
        if (!NativeEngine.isAvailable())
            throw IllegalStateException("本地引擎没装载，请重启 App")
        ModelStore.synthesisBlockReason(c)?.let { throw IllegalStateException(it) }

        val mode = ModelStore.currentMode(c)
        val src = MixerDraft.active(c)
        val tk = ModelStore.modeFile(c, mode)
            ?: throw IllegalStateException("缺这一份模式主文件")
        val tok = ModelStore.tokenizer(c)
            ?: throw IllegalStateException("缺共用底座")
        val h = NativeEngine.ttsLoadGated(tk.absolutePath, tok.absolutePath, null)
        if (h == 0L) throw IllegalStateException("声音模型正在装载，稍后再试")
        try {
            if (cancelled()) throw IllegalStateException("已取消")
            when (mode) {
                ModelStore.Mode.BASE -> {
                    val txt = src.refText.takeIf { it.isNotBlank() }
                    if (!NativeEngine.ttsSetVoiceRef(h, VoiceRef.pcm(c), txt))
                        throw IllegalStateException("参考音频没能交给引擎")
                }
                else -> NativeEngine.ttsClearVoiceRef(h)
            }
            val v = when (mode) {
                ModelStore.Mode.VOICE_DESIGN -> {
                    val identity = src.voice.trim().ifBlank { DEFAULT_IDENTITY }
                    SynthInput.Params(
                        VoiceProfile.build(identity, src.perf.styleInstruct(),
                            "平稳、清晰地叙述，句间自然停顿。"), null)
                }
                ModelStore.Mode.CUSTOM_VOICE -> {
                    // R165 · custom_voice 必须给 speaker，缺了引擎直接失败；
                    // 与正式制作一致：没选就取音色表第一个并记住。
                    val tone = listOf(src.cvStyle.trim(), src.perf.styleInstruct().trim())
                        .filter { it.isNotEmpty() }.joinToString("；")
                    val speaker = listOf(src.speaker, Prefs.speakerName(c))
                        .firstOrNull { it.isNotBlank() }
                        ?: Prefs.speakerNames(c).firstOrNull()
                            ?.also { Prefs.setSpeakerName(c, it) }.orEmpty()
                    if (speaker.isBlank())
                        throw IllegalStateException("先在调音台里挑一个音色再听")
                    SynthInput.Params(tone.ifBlank { null }, speaker)
                }
                ModelStore.Mode.BASE -> SynthInput.Params(null, null)
            }
            if (cancelled()) throw IllegalStateException("已取消")
            // R256 · 试听临时文件统一进 cacheDir/trial，播完由 PlayQueue 清掉。
            // R276 · 首页片段传 outFile，落 filesDir/clips 常驻，不被 sweep 收走。
            val f = outFile ?: TrialFiles.file(c, outName)
            val code = NativeEngine.ttsSynthesize(h, text, v.instruct, v.speaker,
                "chinese", f.absolutePath, src.knobs)
            if (cancelled()) throw IllegalStateException("已取消")
            if (code != 0 || !f.exists() || f.length() <= 44) {
                EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL_EMPTY",
                    "来源=$label · mode=${mode.id} · code=$code · " +
                        "speaker=${EventLog.safe(v.speaker)} · chars=${text.length} · " +
                        "instructChars=${v.instruct?.length ?: 0}")
                throw IllegalStateException(
                    SynthErrors.explain(code, f.exists() && f.length() > 44))
            }
            EventLog.log(EventLog.Kind.ENGINE, "MIXER_TRIAL",
                "来源=$label · mode=${mode.id} · chars=${text.length} · " +
                    "seed=${src.knobs.seed} · bytes=${f.length()}")
            return f.absolutePath
        } finally { NativeEngine.ttsFreeGated(h) }
    }
}
