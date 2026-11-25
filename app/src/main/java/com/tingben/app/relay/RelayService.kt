package com.tingben.app.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.tingben.app.core.RequestArchive
import com.tingben.app.core.sha
import com.tingben.app.diag.EventLog
import com.tingben.app.gateway.GatewaySynth
import org.json.JSONObject

/**
 * R377 · 跨网中继前台服务（独立 specialUse，不复用网关 / 不复用 mediaProcessing）。
 *
 * 一条循环：轮询 queue → 取请求 → 开始震动 → 用本机调音台预设合成
 * （GatewaySynth.speak 复用 ClipSynth、并发锁 1、回传前本地压缩）→
 * 合成压缩一结束就给「完成」震动（R381：不再等两次上传，反馈不被拉断）
 * → 回写 out + status → DELETE 请求文件（正文即用即删）。
 *
 * R381 · 每个链路节点都写一条开发者向日志：开关、请求（只记 id / 字数 /
 * 短哈希）、压缩前后体积、耗时、回写、删请求、错误码、震动触发与跳过。
 * R387 · 正文口径按用户新要求改准：仓库侧请求文件仍「即用即删」，但取到
 * 请求时原文会写一份进本机私有留存区（core/RequestArchive.kt，用户主动查看用，
 * 只留文本）；**日志 / 通知 / 状态 JSON / 诊断导出仍一个字不落正文**，
 * 只记字符数与短原因，Token 不落盘。两者不是一回事，别再沿用旧的一刀切说法。
 * 唤醒锁只在合成期持；Doze 下必然断，写进文案不对抗系统。
 */
class RelayService : Service() {

    companion object {
        const val ACTION_START = "com.tingben.app.RELAY_START"
        /** R400 · 进程内静态存活标志：冷启动与 MY_PACKAGE_REPLACED 广播可能间隔
         *  几百毫秒各补拉一次，ServiceResume 靠它判断「已经在轮询，不重复起」。 */
        @Volatile private var alive = false
        fun isRunning(): Boolean = alive
        const val ACTION_STOP = "com.tingben.app.RELAY_STOP"
        private const val NOTIF_ID = 0x524C
        private const val CHANNEL = "tingben_relay"

        fun start(c: Context) {
            val i = Intent(c, RelayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, RelayService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private lateinit var wake: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tingben:relay-unit")
            wake.setReferenceCounted(false)
        }
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRelay()
            EventLog.log(EventLog.Kind.PIPELINE, "RELAY_OFF", "用户关闭跨网中继")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RelayPrefs.configured(this)) {
            EventLog.log(EventLog.Kind.PIPELINE, "RELAY_START_BLOCKED",
                "开关 / Token / 仓库没配齐，不起服务", level = EventLog.Level.WARN)
            stopSelf()
            return START_NOT_STICKY
        }
        // R400 · 只在真的从「没在跑」拉起来时记 RELAY_ON：真机日志里冷启动与
        // 应用更新广播相隔 0.5s 各补拉一次，会留下两条 RELAY_ON，看着像起了两个服务。
        val started = startRelay()
        if (started) {
            EventLog.log(EventLog.Kind.PIPELINE, "RELAY_ON",
                RelayPrefs.owner(this) + "/" + RelayPrefs.repo(this) + " · 忙 " +
                    RelayContract.POLL_MS + "ms / 闲 " + RelayContract.IDLE_POLL_MS +
                    "ms · 上限 " + RelayContract.MAX_CHARS + " 字")
        } else {
            EventLog.log(EventLog.Kind.PIPELINE, "RELAY_ON_NOOP",
                "已在轮询 · 重复补拉不重启、不重复记 ON", level = EventLog.Level.TRACE)
        }
        return START_NOT_STICKY
    }

    /** @return 是否真的从停止变成运行；重复补拉返回 false。 */
    private fun startRelay(): Boolean {
        if (running) return false
        return synchronized(this) {
            if (running) false else {
                running = true
                alive = true
                thread = Thread({ loop() }, "tingben-relay-poll").apply { isDaemon = true; start() }
                true
            }
        }
    }

    private fun stopRelay() {
        running = false
        alive = false
        try { thread?.interrupt() } catch (_: Throwable) { }
        thread = null
        releaseWake()
    }

    private fun loop() {
        // R400 · 连续网络失败计数：断网（UnknownHostException）时按指数退避，
        // 同类错误只记首行 + 每 12 次一行汇总，恢复时记一行 RECOVER；
        // 正常关停的 InterruptedException 不再伪装成 WARN「轮询失败」。
        var errStreak = 0
        // R401 · 本机忙（整本制作 / 试听 / 另一条远端）时连续让路的轮数，
        // 只用于汇总日志与恢复快档，不改变「请求保留排队」的行为。
        var busyStreak = 0
        while (running) {
            try {
                if (!RelayPrefs.configured(this)) {
                    Thread.sleep(RelayContract.RETRY_MS); continue
                }
                val client = RelayClient(RelayPrefs.owner(this), RelayPrefs.repo(this),
                    RelayPrefs.branch(this), RelayPrefs.token(this))
                val items = client.listQueue()
                if (errStreak > 0) {
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_POLL_RECOVER",
                        "网络恢复 · 连续出错 $errStreak 次后重新取到队列 · 退避归零")
                    errStreak = 0
                }
                if (items.isEmpty()) {
                    setState("等着请求 · " + RelayPrefs.owner(this) + "/" + RelayPrefs.repo(this))
                    Thread.sleep(RelayContract.IDLE_POLL_MS)
                    continue
                }
                val item = items[0]
                val id = item.name.removeSuffix(".json")
                val (text, reqSha) = client.getRequest(item.name)
                val chars = text.length
                // R387 · 用户要「每次过来的请求把文本留下」：在校验之前就留，
                // 被判空 / 超 1500 字 / 合成失败的也留，排查时看得到全貌。
                // 只写本机私有留存区，不进日志，也不影响仓库侧「取完即删」。
                RequestArchive.add(this, RequestArchive.SRC_RELAY, text)
                // R381 · 只记 id / 字数 / 短哈希，正文与 Token 一个字都不落日志。
                EventLog.log(EventLog.Kind.PIPELINE, "RELAY_REQ",
                    "id=$id · ${chars}字 · h=${sha(text).take(8)} · 取走请求",
                    fields = mapOf("id" to id, "chars" to chars.toString()))
                if (text.isBlank() || chars > RelayContract.MAX_CHARS) {
                    val why = if (text.isBlank()) "没给文字" else "最多 1500 字，先少贴一点"
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_REJECT",
                        "id=$id · $why", level = EventLog.Level.WARN)
                    client.putFailed(id, failStatus(chars, why))
                    client.deleteQueue(item.name, reqSha)
                    continue
                }

                val t0 = android.os.SystemClock.elapsedRealtime()
                // R400 · 渠道标签：中继链路的 MIXER_TRIAL 记「来源=中继」，不再混成网关。
                val r = GatewaySynth.speak(this, text, 0, "中继")
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                if (r.busy) {
                    // R401 · 撞上整本制作 / 用户在试听 / 另一条远端请求：
                    // **不判失败、不删 queue、不震动**。写一条 state=busy 让调用方
                    // 知道在排队，然后切 60s 慢档等，制作/试听结束自动恢复快档。
                    busyStreak++
                    client.putBusy(id, busyStatus(chars, r.reason ?: "本机正忙",
                        RelayContract.MAX_BACKOFF_MS))
                    if (busyStreak == 1 || busyStreak % 12 == 0) {
                        // R381 埋点清单里「RELAY_BUSY」是首轮让路的固定节点，
                        // 保留它；后续汇总另用 RELAY_DEFER，不刷屏也不丢埋点。
                        EventLog.log(EventLog.Kind.PIPELINE,
                            if (busyStreak == 1) "RELAY_BUSY" else "RELAY_DEFER",
                            "id=$id · 本机忙（${r.reason?.take(24) ?: "引擎占用"}）· " +
                                "请求保留排队 · 连续 ${busyStreak}轮 · 退避 " +
                                RelayContract.MAX_BACKOFF_MS + "ms",
                            level = EventLog.Level.WARN,
                            fields = mapOf("id" to id, "streak" to busyStreak.toString()))
                    }
                    setState("本机正忙 · 请求留着排队，忙完自动补")
                    releaseWake()
                    // 忙时不要每 5 秒重取（整本制作可能几十分钟），走上限慢档。
                    try { Thread.sleep(RelayContract.MAX_BACKOFF_MS) }
                    catch (_: InterruptedException) { return }
                    continue
                }
                if (busyStreak > 0) {
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_DEFER_DONE",
                        "本机空出来了 · 连续让了 $busyStreak 轮 · 开始合成")
                    busyStreak = 0
                }
                setState("收到请求 · " + chars + " 字 · 本机合成中")
                RelayHaptics.start(this)
                holdWake()
                val bytes = r.bytes
                val fmt = r.format
                if (!r.ok || bytes == null || bytes.size <= 44) {
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_FAIL",
                        "id=$id · ${chars}字 · ${ms}ms · 合成没成",
                        level = EventLog.Level.WARN,
                        fields = mapOf("id" to id, "ms" to ms.toString()))
                    client.putFailed(id, failStatus(chars, r.reason ?: "合成没成功"))
                    RelayHaptics.fail(this)
                    setState("合成没成 · " + chars + " 字（原因不落正文）")
                } else {
                    // R381 · 合成与本地压缩一结束就给「完成」震动：旧版排在两次
                    // GitHub 上传之后，用户只记得开始两下。上传失败另走 fail。
                    RelayHaptics.done(this)
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_SYNTH",
                        "id=$id · ${chars}字 · $fmt ${bytes.size}B · 合成+压缩 ${ms}ms",
                        fields = mapOf("id" to id, "format" to fmt,
                            "bytes" to bytes.size.toString(), "ms" to ms.toString()))
                    val blob = client.putOut(id, bytes, fmt)
                    client.putDone(id, doneStatus(chars, fmt, bytes.size,
                        client.audioPath(id, fmt), blob))
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_PUT_DONE",
                        "id=$id · out/status 已回写 · $fmt ${bytes.size}B",
                        fields = mapOf("id" to id))
                    setState("完成 · " + chars + " 字 · " + fmt + " " + bytes.size + " B")
                }
                // 无论成功失败，正文即用即删。
                client.deleteQueue(item.name, reqSha)
                EventLog.log(EventLog.Kind.PIPELINE, "RELAY_DEL",
                    "id=$id · 请求文件已删（正文即用即删）", fields = mapOf("id" to id))
                releaseWake()
                Thread.sleep(RelayContract.POLL_MS)
            } catch (t: Throwable) {
                // R400 · stopRelay() 用 interrupt() 打断 sleep 让线程退出，是正常关停，
                // 不是失败；旧版把它记成 WARN「轮询失败 · InterruptedException」，
                // 用户每次关开关都会多一条假错误。
                if (!running || t is InterruptedException) {
                    releaseWake()
                    return
                }
                // R381 · 异常 message 可能带 URL / 路径，只记错误码与异常类名。
                val msg = if (t is RelayClient.Err) "HTTP " + t.code
                    else t.javaClass.simpleName
                errStreak++
                val backoff = backoffMs(errStreak)
                // 首行 + 每 12 次汇总：真机日志里断网 4 分钟刷了 46 行同款 WARN。
                if (errStreak == 1 || errStreak % 12 == 0) {
                    EventLog.log(EventLog.Kind.PIPELINE, "RELAY_POLL_ERR",
                        "轮询失败 · $msg · 连续 ${errStreak}次 · 退避 ${backoff}ms",
                        level = EventLog.Level.WARN,
                        fields = mapOf("err" to t.javaClass.simpleName,
                            "streak" to errStreak.toString(), "backoff" to backoff.toString()))
                }
                setState("等一等 · " + msg)
                releaseWake()
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
            }
        }
    }

    /** R400 · 5s→10s→20s→40s→60s 封顶；成功后由 loop 的 RECOVER 分支归零。 */
    private fun backoffMs(streak: Int): Long {
        var v = RelayContract.RETRY_MS
        repeat(streak.coerceAtMost(4)) { v *= 2 }
        return v.coerceAtMost(RelayContract.MAX_BACKOFF_MS)
    }

    private fun doneStatus(chars: Int, format: String, bytes: Int,
                           audio: String, blob: String): JSONObject = JSONObject()
        .put("state", "done").put("v", RelayContract.V)
        .put("chars", chars).put("format", format).put("bytes", bytes)
        .put("audio", audio).put("sha", blob)
        .put("ts", System.currentTimeMillis() / 1000L)

    /** R401 · 排队态：不含正文、不含 Token；调用方据此显示「手机正忙」。 */
    private fun busyStatus(chars: Int, reason: String,
                           retryAfterMs: Long): JSONObject = JSONObject()
        .put("state", "busy").put("v", RelayContract.V)
        .put("chars", chars).put("reason", reason.take(80))
        .put("retry_after_ms", retryAfterMs)
        .put("ts", System.currentTimeMillis() / 1000L)

    private fun failStatus(chars: Int, reason: String): JSONObject = JSONObject()
        .put("state", "failed").put("v", RelayContract.V)
        .put("chars", chars).put("reason", reason.take(80))
        .put("ts", System.currentTimeMillis() / 1000L)

    private fun setState(s: String) {
        RelayPrefs.setState(this, s)
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif()) }
        catch (_: Throwable) { }
    }

    private fun holdWake() {
        try { if (::wake.isInitialized && !wake.isHeld) wake.acquire(10 * 60 * 1000L) }
        catch (_: Throwable) { }
    }

    private fun releaseWake() {
        try { if (::wake.isInitialized && wake.isHeld) wake.release() }
        catch (_: Throwable) { }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL, "跨网中继", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun notif(): Notification {
        val pi = PendingIntent.getService(this, 3,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("听本跨网中继")
            .setContentText(RelayPrefs.state(this) + " · 点这里关闭")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRelay()
        super.onDestroy()
    }
}
