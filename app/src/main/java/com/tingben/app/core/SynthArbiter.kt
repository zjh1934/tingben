package com.tingben.app.core

import java.util.concurrent.atomic.AtomicReference

/**
 * R401 · 本机合成单一仲裁。
 *
 * 引擎只有一个（NativeEngine 单实例、ClipSynth.synthesize 是唯一真源），
 * 却有五个入口：整本制作 / 首页粘贴文本 / 调音台试听 / 语音网关 / 跨网中继。
 * 改之前各拦各的：ClipSynth 只查 Producer.isBusy()，GatewaySynth 只跟自己
 * 的 AtomicBoolean 互斥，于是「远端正在合成一句，用户点试听」会两个 talker
 * 并发进引擎；「整本制作中远端来请求」更被判成失败、请求文件当场删掉。
 *
 * 定案（见 听本_合成并发仲裁方案_R401.md）：
 *  · 长任务（整本制作）不进 owner，由 Producer.isBusy() 单独读，优先级最高；
 *  · 四个短任务共一把 CAS 锁，谁先抢到谁用，抢不到的明确回「忙」，不并发；
 *  · 用户当面操作 awaitAndAcquire 等远端这一句收尾，不掐断正在生成的音频；
 *  · 远端忙时**保留请求排队**，绝不判失败、绝不删 queue。
 *
 * 不注册广播、不持唤醒锁、不碰生命周期；纯进程内标志，进程死了自然归零。
 */
internal object SynthArbiter {

    const val OWNER_CLIP = "clip"
    const val OWNER_MIXER = "mixer"
    const val OWNER_GATEWAY = "gateway"
    const val OWNER_RELAY = "relay"

    private val owner = AtomicReference<String?>(null)

    /** 整本制作是长任务，单独读，不算短任务占用。 */
    fun producerBusy(): Boolean = try {
        Producer.isBusy()
    } catch (_: Throwable) {
        false
    }

    fun current(): String? = owner.get()
    fun isShortBusy(): Boolean = owner.get() != null

    fun busyLabel(): String = when (owner.get()) {
        OWNER_RELAY -> "跨网中继正在合成一句"
        OWNER_GATEWAY -> "语音网关正在合成一句"
        OWNER_CLIP -> "首页片段正在合成"
        OWNER_MIXER -> "调音台正在试听"
        else -> "本机正在合成一句"
    }

    fun tryAcquire(who: String): Boolean = owner.compareAndSet(null, who)

    fun release(who: String) {
        owner.compareAndSet(who, null)
    }

    /**
     * 用户当面操作：等当前短任务收尾再占锁，不掐断正在生成的音频。
     * 整本制作不在这里等（几十分钟），由调用方用 producerBusy() 直接提示。
     * @return true=拿到锁，调用方必须在 finally 里 release(who)。
     */
    fun awaitAndAcquire(who: String, timeoutMs: Long = 180_000L): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
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
