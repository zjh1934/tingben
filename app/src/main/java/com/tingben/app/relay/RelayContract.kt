package com.tingben.app.relay

/**
 * R377 · 跨网零付费中继的单一常量真值。
 *
 * 中间的服务器 = 用户自有 GitHub 免费私有仓库（zjh1934/tingben-relay）。
 * 手机主动出站到 api.github.com 轮询 queue/，调用方也只跟 api.github.com 说话；
 * 两边都不需要公网 IP、不需要穿透进程、不需要端口映射。
 *
 * 震动节奏（查证 Android VibrationEffect 后定）：
 * 开始 = 短促两下「嗒·嗒」；完成 = 三连渐强 + 一记长收尾；失败 = 三下轻而急。
 * timings[0]=0 表示立刻起振；timings 与 amplitudes 必须等长；
 * 设备没有振幅控制时回退到开/关波形，不让不同强度塌成同一种手感。
 */
internal object RelayContract {
    const val API_HOST = "api.github.com"
    const val API_BASE = "https://api.github.com/repos/"
    const val DEFAULT_OWNER = "zjh1934"
    const val DEFAULT_REPO = "tingben-relay"
    const val DEFAULT_BRANCH = "main"

    const val DIR_QUEUE = "queue"
    const val DIR_OUT = "out"
    const val DIR_STATUS = "status"

    const val POLL_MS = 4000L
    const val IDLE_POLL_MS = 10000L
    const val RETRY_MS = 5000L

    /** = ClipSynth.MAX_CHARS，超长请求直接回 failed，不静默截断。 */
    const val MAX_CHARS = 1500
    const val V = 1

    const val FORMAT_M4A = "m4a"
    const val FORMAT_WAV = "wav"

    /** 开始：短-停-短，中等强度，像「来活了」。 */
    val START_TIMINGS = longArrayOf(0, 55, 90, 55)
    val START_AMPS = intArrayOf(0, 170, 0, 170)
    val START_ONOFF = longArrayOf(0, 55, 90, 55)

    /** 完成：三连渐强 + 一记长收尾，与开始明显不同。 */
    val DONE_TIMINGS = longArrayOf(0, 70, 60, 70, 60, 150)
    val DONE_AMPS = intArrayOf(0, 120, 0, 190, 0, 255)
    val DONE_ONOFF = longArrayOf(0, 70, 60, 70, 60, 150)

    /** 失败：三下轻而急，弱于前两种。 */
    val FAIL_TIMINGS = longArrayOf(0, 40, 60, 40, 60, 40)
    val FAIL_AMPS = intArrayOf(0, 90, 0, 90, 0, 90)
    val FAIL_ONOFF = longArrayOf(0, 40, 60, 40, 60, 40)
}
