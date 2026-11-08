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
        // 点通知主体 = 回到软件并展开播放器页，而不是停在书架猜「刚才播到哪」
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_player", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, CHANNEL)
            .setContentTitle(s.label.ifBlank { "听本" })
            .setContentText(if (s.isPlaying) "正在播放" else "已暂停")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(s.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
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
        } catch (_: Throwable) { }
    }

    fun cancel(c: Context) {
        try { c.getSystemService(NotificationManager::class.java)?.cancel(ID) }
        catch (_: Throwable) { }
    }
}
