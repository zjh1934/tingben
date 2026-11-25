package com.tingben.app.core

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * R405 · 本机合成单一仲裁：**远端请求最高优先级**。
 *
 * 用户口径（2026-11-26）：「远端请求的合成是最高优先级，其他的都要给它让路」；
 * 一次来四条、两个对话各四条，算力要倾向远端；本地正在合成书时，**音频小块
 * 结束就让路**（不是掐断当前段），但「不要因为这个让路把书的合成节奏搞坏搞崩」。
 *
 * 引擎只有一个（NativeEngine 单实例、ttsLoadGated 全局 loadBusy、ClipSynth.synthesize
 * 是唯一真源），所以「四条并发」不是四个 talker 同时推理——那会直接崩。而是：
 *  · 远端整批登记（batchPending），书在**段边界**释放 talker 并挂起等待；
 *  · 这四条（乃至两个对话八条）连续排空，中间书不抢回引擎；
 *  · 整批归零后书重新加载 talker + prepare，从下一段接着做，段号/进度/档案不动。
 *
 * 与 R401 的关系：R401 是「整本制作优先、远端排队」，本轮按用户要求**反过来**。
 * 「忙时保留请求不判失败不删 queue」这条硬约束不变（RelayService 仍走 putBusy）。
 *
 * 不注册广播、不持唤醒锁、不碰生命周期；纯进程内标志，进程死了自然归零。
 */
internal object SynthArbiter {

    const val OWNER_CLIP = "clip"
    const val OWNER_MIXER = "mixer"
    const val OWNER_GATEWAY = "gateway"
    const val OWNER_RELAY = "relay"

    /** 让路最长等这一会儿；到了就放书跑一段，防远端源源不断把书饿死。 */
    const val MAX_YIELD_WAIT_MS = 10 * 60 * 1000L
    /** 远端等书让出引擎的上限：一段推理一般 1~3 分钟。 */
    const val REMOTE_WAIT_MS = 180_000L

    private val owner = AtomicReference<String?>(null)

    /** 跨网中继一次列队看到的整批条数；整批不归零书就不许抢回引擎。 */
    private val batchPending = AtomicInteger(0)
    /** 单条票：网关（局域网）与每条 speak 自己登记，填平中继两条之间的轮询空隙。 */
    private val singlePending = AtomicInteger(0)

    @Volatile private var producerYielded = false
    @Volatile private var lastYieldLogMs = 0L

    private fun isRemote(who: String) = who == OWNER_RELAY || who == OWNER_GATEWAY

    /** 整本制作是长任务，单独读，不算短任务占用。 */
    fun producerBusy(): Boolean = try {
        Producer.isBusy()
    } catch (_: Throwable) {
        false
    }

    fun current(): String? = owner.get()
    fun isShortBusy(): Boolean = owner.get() != null

    // ── 远端优先登记 ────────────────────────────────────────────────
    /** RelayService 每轮列队后按「本轮真正要做的条数」设整批，不用累加避免残留。 */
    fun setRemoteBatch(n: Int) {
        batchPending.set(n.coerceAtLeast(0))
    }

    /** 一条远端请求真正处理完（成功 / 失败 / 请求已删）才减；冷却跳过的也减。 */
    fun remoteBatchDone() {
        batchPending.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** 单条票：GatewaySynth.speak / submit 包住整次合成。 */
    fun remoteEnter() { singlePending.incrementAndGet() }
    fun remoteExit() { singlePending.updateAndGet { if (it > 0) it - 1 else 0 } }

    fun remotePending(): Int = batchPending.get() + singlePending.get()

    /** 书在段边界看到它 >0 就让路。 */
    fun producerShouldYield(): Boolean = remotePending() > 0

    // ── 让路握手 ────────────────────────────────────────────────────
    /** 书已经把 talker 释放、引擎空出来了。 */
    fun markProducerYielded() { producerYielded = true }

    /** 书重新加载完 talker、要接着做下一段了。 */
    fun markProducerResumed() { producerYielded = false }

    fun producerHasYielded(): Boolean = producerYielded

    /** 远端等书让路：书没跑，或书已在段边界让出引擎。 */
    fun awaitProducerYield(timeoutMs: Long = REMOTE_WAIT_MS): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (!producerBusy() || producerYielded) return true
            if (android.os.SystemClock.elapsedRealtime() >= end) return false
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * ClipSynth 放行判定：只有「书已让路 + 当前占用者确实是远端」才允许
     * 在 Producer.isBusy() 仍为 true 时进引擎。试听 / 首页片段永远不放行。
     */
    fun remoteOverride(): Boolean =
        producerYielded && (current() == OWNER_RELAY || current() == OWNER_GATEWAY)

    /** 记一条让路日志就够了，别每 200ms 刷一行。 */
    fun shouldLogYield(now: Long): Boolean = synchronized(this) {
        if (now - lastYieldLogMs < 30_000L) false
        else { lastYieldLogMs = now; true }
    }

    fun busyLabel(): String = when {
        remotePending() > 0 -> "远端请求正在合成（最高优先级）"
        else -> when (owner.get()) {
            OWNER_RELAY -> "跨网中继正在合成一句"
            OWNER_GATEWAY -> "语音网关正在合成一句"
            OWNER_CLIP -> "首页片段正在合成"
            OWNER_MIXER -> "调音台正在试听"
            else -> "本机正在合成一句"
        }
    }

    /** 非远端在有远端待处理时一律抢不到锁——「其他的都要给它让路」。 */
    fun tryAcquire(who: String): Boolean {
        if (remotePending() > 0 && !isRemote(who)) return false
        return owner.compareAndSet(null, who)
    }

    fun release(who: String) {
        owner.compareAndSet(who, null)
    }

    /**
     * 用户当面操作：等当前短任务收尾再占锁，不掐断正在生成的音频。
     * 远端待处理时不干等三分钟，直接让路（由调用方给明确提示）。
     */
    fun awaitAndAcquire(who: String, timeoutMs: Long = 180_000L): Boolean {
        if (remotePending() > 0 && !isRemote(who)) return false
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (remotePending() > 0 && !isRemote(who)) return false
            if (tryAcquire(who)) return true
            if (android.os.SystemClock.elapsedRealtime() >= end) return false
            try {
                Thread.sleep(60L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}
