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

/**
 * R26 · 长任务前台服务（dataSync）。
 *
 * 形状移植自 ReaderVoice-Mobile 的 ChapterDirectorService.kt 与 VoiceDesignService.kt（MIT）：
 * ACTION_START / ACTION_CANCEL、createChannel、startForegroundCompat、buildNotification
 * 的「打开 + 停止」两个 PendingIntent、START_NOT_STICKY、结束用 STOP_FOREGROUND_DETACH。
 * 不搬它的业务 runner：本服务只管「可见通知 + 按块唤醒锁 + 停止入口」，
 * 推理与逐块落盘仍由 core/Producer.kt 的协程跑，唯一真源还是 TaskResume 书签。
 *
 * 如实边界：FGS 不保证不被杀；Doze 会忽略常规唤醒锁；Android 15 起 dataSync
 * 约 6 小时后会被系统停。目标是「到点停 + 回来点继续」，不是永不死。
 */
class ProduceService : Service() {

    companion object {
        const val ACTION_START = "com.tingben.app.action.PRODUCE_START"
        const val ACTION_CANCEL = "com.tingben.app.action.PRODUCE_CANCEL"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "tingben_produce"
        private const val NOTIF_ID = 4711
        // 单块/单段唤醒锁的硬超时：真机有过 643 秒的块，给足余量但绝不整轮常驻。
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

        /** LocalContext.current 有时是 ContextThemeWrapper，逐层剥到 Activity 才能申请运行时权限。 */
        fun activityOf(c: Context): android.app.Activity? {
            var x: Context? = c
            while (x is android.content.ContextWrapper) {
                if (x is android.app.Activity) return x
                x = x.baseContext
            }
            return null
        }

        /** 从可见界面点「开始制作」时调用，必须先于 Producer 的协程启动。 */
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

        /** 每块 / 每段推进时更新通知，后台唯一可见界面不能一直写「准备中」。 */
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

        /** UI 点停止（通知里的停止走 ACTION_CANCEL，两者都汇到 Producer.stop）。 */
        fun stopping(c: Context) {
            if (finished) return
            lastText = "正在停止…（已做好的块都留着）"
            publish(c)
        }

        /** 整次制作结束（完成 / 停止 / 出错 / 缺模型都走这里，由 Producer 的 finally 保证）。 */
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

        /**
         * 按块申请唤醒锁：先释放再带 30 分钟硬超时重新 acquire。
         * 不从首次点击一直持有到任务结束，避免整轮数小时常驻被 Android Vitals 记过度唤醒锁。
         */
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

        /** 诊断摘要里的一行，能看出服务到底起没起、通知最后写了什么。 */
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
        // startForegroundService 的硬约束：进服务后必须立刻 startForeground，
        // 哪怕任务已经结束也要先挂上通知再摘掉，否则系统直接抛 ForegroundServiceDidNotStartInTimeException。
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
                // 任务极快结束（例如模型没导全）时，begin 之后 finish 可能已经先跑完。
                if (!Producer.isBusy() || finished) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWake()
        foreground = false
        try {
            // DETACH：保留「任务已结束」那条通知给用户点，不把它伪装成还在跑。
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

/** 通知渠道：照 ChapterDirectorService.createChannel 的形状，名称如实写「本地制作进度」。 */
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

/** 打开回书架；「停止」直接发 ACTION_CANCEL 给本服务，不新建线程、不绕开 Producer。 */
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
