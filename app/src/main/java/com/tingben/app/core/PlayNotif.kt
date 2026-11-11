package com.tingben.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tingben.app.MainActivity
import com.tingben.app.R

object PlayNotif {
    private const val CHANNEL = "tingben_play"
    private const val ID = 20261107

    /** R248 · 收听位置，替代「正在播放 / 已暂停」这类状态废话。 */
    private fun mmss(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "播放控制",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        description = "锁屏与通知栏控制播放，不联网、不常驻后台"
                        setShowBadge(false)
                    })
            }
        }
    }

    private fun action(c: Context, act: String, req: Int): PendingIntent {
        val i = Intent(c, PlaybackActions::class.java).setAction(act)
        return PendingIntent.getBroadcast(
            c, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun show(c: Context, s: PlaybackState) {
        // 两态隔离：小段 / 调音台试听不占用播放控制通知
        if (s.label.isEmpty() || s.preview) { cancel(c); return }
        ensureChannel(c)
        // R248 · 提前建好平台会话，MediaStyle 才能把锁屏/线控接上。
        PlaySession.ensure(c)
        // 点通知主体 = 回到软件并展开播放器页，而不是停在书架猜「刚才播到哪」
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_player", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // R248 · 借鉴播放器通知：进度条 + 位置/时长，不再写状态形容词。
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        PlaySession.token?.let { style.setMediaSession(it) }
        val b = Notification.Builder(c, CHANNEL)
            .setContentTitle(s.label.ifBlank { "听本" })
            .setContentText(mmss(s.positionMs) + " / " + mmss(s.durationMs))
            .setProgress(
                s.durationMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                s.positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                false)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(s.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(style)
        b.addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,
            "上一段", action(c, "com.tingben.app.PLAY_PREV", 1)).build())
        b.addAction(Notification.Action.Builder(
            if (s.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (s.isPlaying) "暂停" else "播放", action(c, "com.tingben.app.PLAY_TOGGLE", 2)).build())
        b.addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,
            "下一段", action(c, "com.tingben.app.PLAY_NEXT", 3)).build())
        b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
            "关闭", action(c, "com.tingben.app.PLAY_STOP", 4)).build())
        try {
            c.getSystemService(NotificationManager::class.java)?.notify(ID, b.build())
        } catch (t: Throwable) {
            UiErrors.notif("播放控制通知没显示出来，音频照常播放", t)
        }
    }

    fun cancel(c: Context) {
        try { c.getSystemService(NotificationManager::class.java)?.cancel(ID) }
        catch (_: Throwable) { }
    }
}
