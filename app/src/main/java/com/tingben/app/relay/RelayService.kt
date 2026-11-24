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
import com.tingben.app.gateway.GatewaySynth
import org.json.JSONObject

/**
 * R377 · 跨网中继前台服务（独立 specialUse，不复用网关 / 不复用 mediaProcessing）。
 *
 * 一条循环：轮询 queue → 取请求 → 开始震动 → 用本机调音台预设合成
 * （GatewaySynth.speak 复用 ClipSynth、并发锁 1、回传前本地压缩）→
 * 回写 out + status → 结束震动 → DELETE 请求文件（正文即用即删）。
 *
 * 正文只在内存里，日志 / 通知 / 状态 JSON 一律只记字符数与短原因。
 * 唤醒锁只在合成期持；Doze 下必然断，写进文案不对抗系统。
 */
class RelayService : Service() {

    companion object {
        const val ACTION_START = "com.tingben.app.RELAY_START"
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RelayPrefs.configured(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startRelay()
        return START_NOT_STICKY
    }

    private fun startRelay() {
        if (running) return
        synchronized(this) {
            if (running) return
            running = true
            thread = Thread({ loop() }, "tingben-relay-poll").apply { isDaemon = true; start() }
        }
    }

    private fun stopRelay() {
        running = false
        try { thread?.interrupt() } catch (_: Throwable) { }
        thread = null
        releaseWake()
    }

    private fun loop() {
        while (running) {
            try {
                if (!RelayPrefs.configured(this)) {
                    Thread.sleep(RelayContract.RETRY_MS); continue
                }
                val client = RelayClient(RelayPrefs.owner(this), RelayPrefs.repo(this),
                    RelayPrefs.branch(this), RelayPrefs.token(this))
                val items = client.listQueue()
                if (items.isEmpty()) {
                    setState("等着请求 · " + RelayPrefs.owner(this) + "/" + RelayPrefs.repo(this))
                    Thread.sleep(RelayContract.IDLE_POLL_MS)
                    continue
                }
                val item = items[0]
                val id = item.name.removeSuffix(".json")
                val (text, sha) = client.getRequest(item.name)
                val chars = text.length
                if (text.isBlank() || chars > RelayContract.MAX_CHARS) {
                    client.putFailed(id, failStatus(chars,
                        if (text.isBlank()) "没给文字" else "最多 1500 字，先少贴一点"))
                    client.deleteQueue(item.name, sha)
                    continue
                }

                setState("收到请求 · " + chars + " 字 · 本机合成中")
                RelayHaptics.start(this)
                holdWake()
                val r = GatewaySynth.speak(this, text, 0)
                if (r.busy) {
                    // 本机网关或整本书正在用引擎：保留 queue，下一轮再取，不删请求。
                    releaseWake()
                    Thread.sleep(RelayContract.RETRY_MS)
                    continue
                }
                val bytes = r.bytes
                val fmt = r.format
                if (!r.ok || bytes == null || bytes.size <= 44) {
                    client.putFailed(id, failStatus(chars, r.reason ?: "合成没成功"))
                    RelayHaptics.fail(this)
                    setState("合成没成 · " + chars + " 字（原因不落正文）")
                } else {
                    val blob = client.putOut(id, bytes, fmt)
                    client.putDone(id, doneStatus(chars, fmt, bytes.size,
                        client.audioPath(id, fmt), blob))
                    RelayHaptics.done(this)
                    setState("完成 · " + chars + " 字 · " + fmt + " " + bytes.size + " B")
                }
                // 无论成功失败，正文即用即删。
                client.deleteQueue(item.name, sha)
                releaseWake()
                Thread.sleep(RelayContract.POLL_MS)
            } catch (t: Throwable) {
                val msg = if (t is RelayClient.Err) "HTTP " + t.code
                    else (t.message ?: "网络没通").take(60)
                setState("等一等 · " + msg)
                releaseWake()
                Thread.sleep(RelayContract.RETRY_MS)
            }
        }
    }

    private fun doneStatus(chars: Int, format: String, bytes: Int,
                           audio: String, blob: String): JSONObject = JSONObject()
        .put("state", "done").put("v", RelayContract.V)
        .put("chars", chars).put("format", format).put("bytes", bytes)
        .put("audio", audio).put("sha", blob)
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
