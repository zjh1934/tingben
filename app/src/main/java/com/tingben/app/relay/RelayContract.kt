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
    /** R400 · 连续网络失败时的退避上限：断网夜里不再每 5 秒刷一行 WARN。
     *  忙时 4s / 空闲 10s / 出错 5s→10s→20s→40s→60s，成功后立刻归零。 */
    const val MAX_BACKOFF_MS = 60000L

    /** = ClipSynth.MAX_CHARS，超长请求直接回 failed，不静默截断。 */
    const val MAX_CHARS = 1500
    const val V = 1

    const val FORMAT_M4A = "m4a"
    const val FORMAT_WAV = "wav"

    /** 开始：短-停-短，中等强度，像「来活了」。 */
    val START_TIMINGS = longArrayOf(0, 55, 90, 55)
    val START_AMPS = intArrayOf(0, 170, 0, 170)
    val START_ONOFF = longArrayOf(0, 55, 90, 55)

    /**
     * 完成：三连渐强 + 一记明显更长的收尾（总约 930ms，收尾 420ms）。
     * R381 真机反馈「只记得开始两下，长震动没注意到」：旧版收尾只有 150ms，
     * 又排在两次 GitHub 上传之后，感知被拉断。现在加长收尾、并把触发点提前到
     * 合成压缩完成那一刻（见 RelayService），开始 200ms 两下 / 结束近 1 秒，
     * 不用看屏幕也能分出来。
     */
    val DONE_TIMINGS = longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)
    val DONE_AMPS = intArrayOf(0, 110, 0, 160, 0, 210, 0, 255)
    val DONE_ONOFF = longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)

    /** R381 · 日志用的节奏名，排查「用户说没震」时先分清是没触发还是没注意。 */
    const val HAPTIC_START = "start"
    const val HAPTIC_DONE = "done"
    const val HAPTIC_FAIL = "fail"

    /** 失败：三下轻而急，弱于前两种。 */
    val FAIL_TIMINGS = longArrayOf(0, 40, 60, 40, 60, 40)
    val FAIL_AMPS = intArrayOf(0, 90, 0, 90, 0, 90)
    val FAIL_ONOFF = longArrayOf(0, 40, 60, 40, 60, 40)
}
