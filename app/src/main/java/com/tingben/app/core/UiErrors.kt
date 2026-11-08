package com.tingben.app.core

import com.tingben.app.diag.EventLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * R140 · 批次 D：错误可见化。
 *
 * 工程里有 62 处 `catch (_: Throwable) { }`。绝大多数是资源释放、native cancel、
 * 读取兜底这类「静默是合理的」，全改成弹窗反而变噪声。这里只把**影响用户可见结果**
 * 的失败汇到一条通道，由 App 顶层 collect 成 Toast：
 *   · 音频文件打不开 / 删不掉
 *   · 音频索引写入失败（成品明明生成了却没登记）
 *   · 通知栏显示失败
 *   · 分段计划落盘失败（续跑会按兜底推算）
 *   · 删书时附属数据没清干净
 *
 * 用 SharedFlow 而不是 StateFlow：每条是一次事件，展示过就不会残留、不会重复弹。
 */
object UiErrors {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> get() = _events

    // 连续几段音频都坏时会连着报同一句，节流一下免得 Toast 刷屏。
    @Volatile private var lastMsg = ""
    @Volatile private var lastAt = 0L

    fun report(msg: String, t: Throwable? = null) {
        if (msg.isBlank()) return
        val now = System.currentTimeMillis()
        if (msg == lastMsg && now - lastAt < 1500L) return
        lastMsg = msg
        lastAt = now
        _events.tryEmit(msg)
        EventLog.log(EventLog.Kind.CRASH, "UI_ERROR",
            msg + (t?.let { " · " + (it.message ?: it.javaClass.simpleName) } ?: ""))
    }

    fun playback(msg: String, t: Throwable?) = report("播放：$msg", t)
    fun storage(msg: String, t: Throwable? = null) = report("文件：$msg", t)
    fun notif(msg: String, t: Throwable? = null) = report("通知栏：$msg", t)
}
