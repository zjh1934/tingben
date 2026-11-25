package com.tingben.app.gateway

import android.content.Context
import com.tingben.app.core.AudioOps
import com.tingben.app.core.ClipSynth
import com.tingben.app.core.ModelStore
import com.tingben.app.core.Producer
import com.tingben.app.core.SynthArbiter
import com.tingben.app.engine.NativeEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R375 · 网关到现成合成真源的桥（D6 / D7 / D8）。
 *
 * **不另造合成链**：直接调 core/ClipSynth.synthesize —— 三种 mode、9 项
 * 采样、缺件守卫、空音频判定全部与首页片段 / 调音台试听同源。
 * 请求体里的 voice/speaker/seed 等字段在服务层就被丢掉，到这里连参数
 * 都收不到；出队时才由 ClipSynth 自己读 MixerDraft.active(c) 与
 * ModelStore.currentMode(c)，也就是「手机上本地设好的那些预设」。
 *
 * 并发硬锁 1：三份 talker 不共存、NativeEngine 单实例，ClipSynth 本身
 * 也会查 Producer.isBusy()。日志只留字符数，正文一个字不落（R225）。
 *
 * R405 · 优先级反过来：书里正在做整本时，远端**不再被判忙排队**，而是
 * 「书在当前音频小块结束后释放 talker 让路 → 远端整批连续合成 → 书续跑」。
 * 这里的 awaitProducerYield 就是等那个段边界，等不到才回 busy 排队（不删请求）。
 */
internal object GatewaySynth {

    data class Job(
        val id: String,
        val chars: Int,
        val ignored: Int,
        val created: Long,
        @Volatile var state: String = "queued",
        @Volatile var progress: Float = 0f,
        @Volatile var stage: String = "synthesize",
        @Volatile var audio: ByteArray? = null,
        @Volatile var format: String = GatewayContract.XFER_WAV,
        @Volatile var reason: String? = null
    )

    data class Speak(
        val ok: Boolean,
        val busy: Boolean,
        val bytes: ByteArray?,
        val reason: String?,
        val format: String = GatewayContract.XFER_WAV
    )

    private val busy = AtomicBoolean(false)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 由 GatewayService 注入：唤醒锁只在真干活时持（D5），不常驻。 */
    @Volatile var onBusyStart: () -> Unit = {}
    @Volatile var onBusyEnd: () -> Unit = {}

    private fun dir(c: Context): File = File(c.cacheDir, "gateway").apply { mkdirs() }

    /** R401 · 把 label（网关 / 中继）映射到仲裁的占用者。 */
    private fun arbiterOwner(label: String): String =
        if (label == "中继") SynthArbiter.OWNER_RELAY else SynthArbiter.OWNER_GATEWAY

    /**
     * R405 · 「书还在跑、且尚未让出引擎」。只用于判定握手状态，**不再**拿它
     * 直接拒远端（R401 的老做法）：用户要求远端最高优先级，书要让路。
     * 真要拒只有 awaitProducerYield 超时一种，且仍走 busy=true 排队不删请求。
     */
    fun longTaskBusy(): Boolean =
        SynthArbiter.producerBusy() && !SynthArbiter.producerHasYielded()

    /** 引擎状态 → 503 中文原因；顺序与 ClipSynth 内部守卫一致。
     *  R405 · 去掉「Producer.isBusy() 就回正在做整本书」：书跑时远端要等让路，
     *  不是回失败。旧口径会让 RelayService 判 RELAY_FAIL、把请求文件删掉。 */
    fun blockReason(c: Context): String? {
        if (!NativeEngine.isAvailable()) return "本地引擎没装载，请重启 App"
        return ModelStore.synthesisBlockReason(c)
    }

    /** R377 · 合成 → 手机本地压缩 → 只把压缩后的字节给调用方；失败如实回退 WAV。
     *  R400 · label 由调用方给：局域网网关 =「网关」，跨网中继 =「中继」。
     *  之前写死「网关」，真机日志里 117 条中继请求的 MIXER_TRIAL 全标成网关，
     *  开发者看日志分不清是哪条渠道来的。只改埋点文案，合成链一字不动。 */
    private fun synth(c: Context, text: String, id: String,
                      label: String): Pair<ByteArray, String> {
        val f = File(dir(c), "gw_$id.wav")
        ClipSynth.synthesize(c, text, "gateway.wav", label, outFile = f)
        val wav = f.readBytes()
        if (wav.size <= 44) throw IllegalStateException("合成出来的音频是空的")
        return AudioOps.compressForTransfer(c, wav, "gw_" + id)
    }

    private fun newId(): String = java.lang.Long.toHexString(System.nanoTime()) +
        Integer.toHexString((Math.random() * 0xffff).toInt())

    /** 同步短文本：一次一句，成功直接给 WAV 字节。 */
    fun speak(c: Context, text: String, ignored: Int,
              label: String = "网关"): Speak {
        // R405 · 远端整条请求登记一张票，书据此在段边界让路；整批由 RelayService
        // 另设 batchPending，两条之间的轮询空档也不会让书抢回引擎。
        SynthArbiter.remoteEnter()
        try {
            // 书正在做整本：等它把当前这一小段做完、落盘、释放 talker。
            // 等不到才回 busy 排队——绝不判失败、绝不删请求文件。
            if (!SynthArbiter.awaitProducerYield())
                return Speak(false, true, null, "本机正在收尾这一小段，稍后优先处理远端请求")
            blockReason(c)?.let { return Speak(false, false, null, it) }
            val who = arbiterOwner(label)
            // 与首页片段 / 调音台试听共一把锁；远端待处理时那两个根本抢不到。
            if (!SynthArbiter.tryAcquire(who))
                return Speak(false, true, null, SynthArbiter.busyLabel() + "，稍后再试")
            if (!busy.compareAndSet(false, true)) {
                SynthArbiter.release(who)
                return Speak(false, true, null, "一次只合成一句，稍后重试")
            }
            onBusyStart()
            try {
                val (b, fmt) = synth(c, text, newId(), label)
                return Speak(true, false, b, null, fmt)
            } catch (e: Throwable) {
                return Speak(false, false, null,
                    e.message?.take(80) ?: "合成没成功，小块都留着，可以再试一次")
            } finally {
                onBusyEnd()
                busy.set(false)
                SynthArbiter.release(who)
            }
        } finally {
            SynthArbiter.remoteExit()
        }
    }

    /** 异步 job：本轮只解决「不想等同步响应」，输入同样 ≤ MAX_CHARS。 */
    fun submit(c: Context, text: String, ignored: Int): String? {
        // R405 · 异步网关 job 同样登记远端票、等书让路，口径与 speak 一致。
        SynthArbiter.remoteEnter()
        if (!SynthArbiter.awaitProducerYield()) { SynthArbiter.remoteExit(); return null }
        blockReason(c)?.let { SynthArbiter.remoteExit(); return null }
        val who = SynthArbiter.OWNER_GATEWAY
        if (!SynthArbiter.tryAcquire(who)) { SynthArbiter.remoteExit(); return null }
        if (!busy.compareAndSet(false, true)) {
            SynthArbiter.release(who); SynthArbiter.remoteExit(); return null
        }
        val id = newId()
        val j = Job(id, text.length, ignored, System.currentTimeMillis())
        jobs[id] = j
        Thread({
            onBusyStart()
            try {
                j.state = "running"
                val (b, fmt) = synth(c, text, id, "网关")
                j.audio = b
                j.format = fmt
                j.progress = 1f
                j.stage = "verify"
                j.state = "succeeded"
            } catch (e: Throwable) {
                j.reason = e.message?.take(80) ?: "合成没成功"
                j.state = "failed"
            } finally {
                onBusyEnd()
                busy.set(false)
                SynthArbiter.release(who)
                SynthArbiter.remoteExit()
            }
        }, "tingben-gateway-job").start()
        return id
    }

    fun job(id: String): Job? {
        sweep()
        return jobs[id]
    }

    /** 过期任务与临时文件一起收，避免 cacheDir 里越积越多。 */
    private fun sweep() {
        val now = System.currentTimeMillis()
        for (e in jobs.entries) {
            if (now - e.value.created > GatewayContract.JOB_RETENTION_MS) jobs.remove(e.key)
        }
    }

    fun clear(c: Context) {
        jobs.clear()
        try {
            dir(c).listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) { }
    }
}
