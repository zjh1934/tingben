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
import com.tingben.app.core.UiErrors
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

        fun fgsTypeLabel(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                "mediaProcessing" else "dataSync"

        /** 返回 false = 系统不让起前台服务；调用方必须停止任务，不能假装还在跑。 */
        fun begin(c: Context, title: String): Boolean {
            val ctx = c.applicationContext
            lastBook = title
            lastTitle = "听本 · 本地制作中"
            lastText = "《$title》 · 准备中"
            lastStage = "准备中"
            lastDone = 0
            lastTotal = 0
            finished = false
            // R166 · title/type 已在 detail 里，fields 原样再来一遍纯属双写。
            EventLog.log(EventLog.Kind.PIPELINE, "FGS_BEGIN",
                "title=$title type=${fgsTypeLabel()} reason=用户从界面主动开始长任务")
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(startIntent(ctx, title))
                else ctx.startService(startIntent(ctx, title))
                true
            } catch (t: Throwable) {
                // R139 · 原来 catch 掉异常后注释写「任务仍在内存协程里跑」：
                // 没有前台服务，进程随时被系统杀掉，界面却一直显示在制作。
                // 现在明确返回 false，Producer 会把这次任务停在「没开始」并告知用户。
                EventLog.log(EventLog.Kind.CRASH, "FGS_START_FAIL",
                    "前台服务起不来，本次任务不启动（没有前台服务，进程随时被杀）：" +
                        (t.message ?: ""),
                    fields = mapOf("error" to (t.javaClass.simpleName)))
                false
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
            OverlayProgress.update(c, lastBook, stage, done, total)
        }

        fun stopping(c: Context) {
            if (finished) return
            lastText = "正在停止…（已做好的块都留着）"
            publish(c)
            // R161 · 用户点停止，浮窗立刻收起，绝不留在屏幕上
            OverlayProgress.hide(c)
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
            OverlayProgress.hide(c)
            releaseWake()
            EventLog.log(EventLog.Kind.PIPELINE, "FGS_FINISH",
                "stage=$stage done=$done/$total wake=released")
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
                level = EventLog.Level.TRACE)
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
        try {
            startForegroundCompat(lastTitle, lastText)
        } catch (t: Throwable) {
            // 服务内部 startForeground 失败也必须停自己，否则系统会判 ANR；
            // 同时让制作停下来，不假装还在跑。
            EventLog.log(EventLog.Kind.CRASH, "FGS_START_FAIL",
                "服务内 startForeground 失败，停止本次制作：" + (t.message ?: ""),
                fields = mapOf("error" to t.javaClass.simpleName))
            Producer.stop()
            stopSelf()
            return START_NOT_STICKY
        }
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
                    "notification=visible type=${fgsTypeLabel()} busy=${Producer.isBusy()}")
                if (!Producer.isBusy() || finished) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * R161 · Android 15（API 35，本仓 targetSdk 35）起，dataSync / mediaProcessing
     * 前台服务在 24 小时内累计跑满 6 小时会回调这里。回调时服务已经不再被视为
     * 前台服务，只有几秒钟调用 stopSelf()，否则系统抛 RemoteServiceException 判 ANR。
     *
     * 不能等这个回调才保护数据：Android 14 及以下没有 onTimeout，到点直接把进程
     * 置为 cached。真正的兜底是「每段落盘 + 开软件弹窗续跑」（R159），这里只负责
     * 优雅收尾，并给用户一句能看懂的原因。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        val typeName = when (fgsType) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING -> "mediaProcessing"
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
            else -> "type=$fgsType"
        }
        val why = "已达系统本次运行时长上限，已安全停下，做好的都留着"
        EventLog.log(EventLog.Kind.PIPELINE, "FGS_TIMEOUT",
            "startId=$startId type=$typeName done=$lastDone/$lastTotal",
            fields = mapOf("startId" to startId.toString(), "type" to typeName))
        UiErrors.report(why)
        // 复用同一条停止链：写回 stopped、暂停队列、保留断点、释放唤醒锁
        Producer.onServiceTimeout(applicationContext, fgsType)
        lastTitle = "听本 · 已到时长上限"
        lastStage = "已达时长上限"
        lastText = "《$lastBook》 · $lastDone/$lastTotal · $why"
        finished = true
        foreground = false
        OverlayProgress.hide(this)
        releaseWake()
        runCatching {
            ensureChannel(this)
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIF_ID, buildNotification(this, lastTitle, lastText,
                    lastDone, lastTotal, true))
        }
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        stopSelf()
    }

    override fun onDestroy() {
        releaseWake()
        // R161 · WindowManager 泄漏 View 是常见崩溃源，销毁必须 remove
        OverlayProgress.hide(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_tasks", true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val b = Notification.Builder(c, "tingben_produce")
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(com.tingben.app.R.mipmap.ic_launcher)
        .setOngoing(!final)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
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
