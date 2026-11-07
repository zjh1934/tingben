package com.tingben.app

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
import com.tingben.app.core.Producer
import com.tingben.app.diag.EventLog

class ProduceService : Service() {

    companion object {
        const val ACTION_START = "com.tingben.app.action.PRODUCE_START"
        const val ACTION_CANCEL = "com.tingben.app.action.PRODUCE_CANCEL"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "tingben_produce"
        private const val NOTIF_ID = 4711
        private const val UNIT_TIMEOUT_MS = 30L * 60L * 1000L

        private val wakeGuard = Any()
        private var wakeLock: PowerManager.WakeLock? = null

        @Volatile private var lastBook = ""
        @Volatile private var lastTitle = "听本 · 本地制作中"
        @Volatile private var lastText = "准备中…"
        @Volatile private var lastDone = 0
        @Volatile private var lastTotal = 0
        @Volatile private var lastStage = "准备中"
        @Volatile private var finished = false
        @Volatile private var foreground = false

        fun startIntent(c: Context, title: String): Intent =
            Intent(c, ProduceService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }

        fun cancelIntent(c: Context): Intent =
            Intent(c, ProduceService::class.java).apply { action = ACTION_CANCEL }

        fun activityOf(c: Context): android.app.Activity? {
            var x: Context? = c
            while (x is android.content.ContextWrapper) {
                if (x is android.app.Activity) return x
                x = x.baseContext
            }
            return null
        }

        fun begin(c: Context, title: String) {
            val ctx = c.applicationContext
            lastBook = title
            lastTitle = "听本 · 本地制作中"
            lastText = "《$title》 · 准备中"
            lastStage = "准备中"
            lastDone = 0
            lastTotal = 0
            finished = false
            EventLog.log(EventLog.Kind.PIPELINE, "FGS_BEGIN",
                "title=$title type=dataSync reason=用户从界面主动开始长任务",
                fields = mapOf("book" to title, "type" to "dataSync"))
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(startIntent(ctx, title))
                else ctx.startService(startIntent(ctx, title))
            } catch (t: Throwable) {
                EventLog.log(EventLog.Kind.CRASH, "FGS_START_FAIL",
                    "前台服务起不来，任务仍在内存协程里跑：" + (t.message ?: ""),
                    fields = mapOf("error" to (t.javaClass.simpleName)))
            }
        }

        fun update(c: Context, stage: String, done: Int, total: Int, msg: String) {
            if (finished) return
            lastStage = stage
            lastDone = done
            lastTotal = total
            lastText = buildString {
                if (lastBook.isNotBlank()) append("《").append(lastBook).append("》 · ")
                append(stage)
                if (total > 0) append(" · ").append(done).append("/").append(total)
                if (msg.isNotBlank()) append(" · ").append(msg.take(48))
            }
            publish(c)
        }

        fun stopping(c: Context) {
            if (finished) return
            lastText = "正在停止…（已做好的块都留着）"
            publish(c)
        }

        fun finish(c: Context, stage: String, done: Int, total: Int) {
            if (finished) return
            finished = true
            foreground = false
            lastTitle = "听本 · 任务已结束"
            lastStage = stage
            lastDone = done
            lastTotal = total
            lastText = buildString {
                if (lastBook.isNotBlank()) append("《").append(lastBook).append("》 · ")
                append(stage)
                if (total > 0) append(" · ").append(done).append("/").append(total)
                append(" · 点开看结果或继续")
            }
            publish(c, final = true)
            releaseWake()
            EventLog.log(EventLog.Kind.PIPELINE, "FGS_FINISH",
                "stage=$stage done=$done/$total wake=released",
                fields = mapOf("stage" to stage, "done" to done.toString(), "total" to total.toString()))
            try {
                c.applicationContext.stopService(Intent(c.applicationContext, ProduceService::class.java))
            } catch (_: Throwable) { }
        }

        fun touch(c: Context, unit: String) {
            if (finished) return
            val pm = c.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            synchronized(wakeGuard) {
                val wl = wakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "tingben:produce-unit"
                ).apply { setReferenceCounted(false) }.also { wakeLock = it }
                runCatching { if (wl.isHeld) wl.release() }
                runCatching { wl.acquire(UNIT_TIMEOUT_MS) }
            }
            EventLog.log(EventLog.Kind.ENGINE, "FGS_WAKELOCK",
                "unit=$unit type=PARTIAL_WAKE_LOCK timeoutMs=$UNIT_TIMEOUT_MS scope=每块",
                level = EventLog.Level.TRACE,
                fields = mapOf("unit" to unit, "timeoutMs" to UNIT_TIMEOUT_MS.toString()))
        }

        fun releaseWake() {
            synchronized(wakeGuard) {
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
                wakeLock = null
            }
        }

        fun status(): String =
            "foreground=$foreground finished=$finished stage=$lastStage " +
                "done=$lastDone/$lastTotal text=$lastText"

        private fun publish(c: Context, final: Boolean = finished) {
            runCatching {
                val ctx = c.applicationContext
                ensureChannel(ctx)
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                nm.notify(NOTIF_ID, buildNotification(ctx, lastTitle, lastText,
                    lastDone, lastTotal, final))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForegroundCompat(lastTitle, lastText)
        foreground = true
        when (intent?.action) {
            ACTION_CANCEL -> {
                Producer.stop()
                lastText = "正在停止…（已做好的块都留着）"
                publish(this)
                EventLog.log(EventLog.Kind.PIPELINE, "FGS_CANCEL",
                    "通知里点了停止，复用 Producer.stop（不另写一套停止逻辑）")
                if (!Producer.isBusy()) stopSelf()
            }
            else -> {
                val t = intent?.getStringExtra(EXTRA_TITLE)
                if (!t.isNullOrBlank()) {
                    lastBook = t
                    lastText = "《$t》 · 准备中"
                    publish(this)
                }
                EventLog.log(EventLog.Kind.PIPELINE, "FGS_READY",
                    "notification=visible type=dataSync busy=${Producer.isBusy()}",
                    fields = mapOf("busy" to Producer.isBusy().toString(), "type" to "dataSync"))
                if (!Producer.isBusy() || finished) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        foreground = false
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.PIPELINE, "FGS_DESTROY",
            "服务结束 · 唤醒锁已释放 · 通知保留（DETACH）")
        super.onDestroy()
    }

    private fun startForegroundCompat(title: String, text: String) {
        val n = buildNotification(this, title, text, lastDone, lastTotal, finished)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}

private fun ensureChannel(c: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("tingben_produce") == null) {
            nm.createNotificationChannel(
                NotificationChannel("tingben_produce", "本地制作进度",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "离线把小说变成音频的逐块进度与停止入口"
                    setShowBadge(false)
                })
        }
    }
}

private fun buildNotification(
    c: Context, title: String, text: String, done: Int, total: Int, final: Boolean
): Notification {
    val open = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val b = Notification.Builder(c, "tingben_produce")
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(!final)
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
    if (total > 0) b.setProgress(total, done.coerceIn(0, total), false)
    if (!final) {
        val cancel = PendingIntent.getService(
            c, 1, ProduceService.cancelIntent(c),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        b.addAction(Notification.Action.Builder(null, "停止", cancel).build())
    }
    return b.build()
}
