package com.tingben.app.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tingben.app.diag.EventLog

/**
 * R140 · 批次 D：播放前主动申请通知权限。
 *
 * 1.1.2 只有「开始制作」会申请 POST_NOTIFICATIONS（Pipeline.start 里 7001）。
 * 只播听、不做书的用户，Android 13+ 上播放控制通知根本不显示，
 * 设置页又只有「去系统设置」的被动引导。现在第一次点播放时主动问一次。
 *
 * 用 Prefs 持久化「问过没」：用户拒绝过就不再反复弹系统框（尤其永久拒绝后），
 * 改由设置页的「去开启」兜底。requestCode 7002 与制作流程用的编号区分。
 */
object NotifPerm {
    private const val REQ = 7002
    private const val KEY = "notifPermAsked"

    private fun activityOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) {
            if (x is Activity) return x
            x = x.baseContext
        }
        return null
    }

    private fun asked(c: Context): Boolean =
        c.getSharedPreferences("tingben_settings", Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    private fun markAsked(c: Context) =
        c.getSharedPreferences("tingben_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()

    // R165 · 设置页要按真实权限显示状态；Android 13 以下默认视为已具备。
    fun granted(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun ensureForPlayback(c: Context?, actHint: Activity? = null) {
        val ctx = c ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (asked(ctx)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            markAsked(ctx)
            return
        }
        val act = actHint ?: activityOf(ctx)
        if (act == null) {
            // 只有 applicationContext、拿不到 Activity 时先不标记，
            // 下次从界面点播放还能再试。
            EventLog.log(EventLog.Kind.PIPELINE, "PLAY_NOTIF_PERMISSION_NO_ACTIVITY",
                "播放前想申请通知权限，但当前上下文不是 Activity")
            return
        }
        markAsked(ctx)
        try {
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ)
            EventLog.log(EventLog.Kind.PIPELINE, "PLAY_NOTIF_PERMISSION",
                "播放前已申请 POST_NOTIFICATIONS（拒绝不影响播放，可在设置页开启）")
        } catch (t: Throwable) {
            UiErrors.playback("通知权限没申请下来，可在设置页手动开启", t)
        }
    }
}
