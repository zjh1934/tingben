package com.tingben.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.tingben.app.core.KeepAlive
import com.tingben.app.core.Prefs
import com.tingben.app.core.UiErrors
import com.tingben.app.diag.EventLog

/**
 * R161 · 可选悬浮进度控件，默认关。
 *
 * 用户口径是「能拿多大权限就拿多大权限」，但调研结论
 * （沙盒 `听本_后台保活与权限_方案.md`）写死两条：浮窗不是保活白名单，
 * Android 15+ 反而要求「有可见浮窗」才算后台启动豁免。所以它只做
 * 「看得见进度」的辅助，不承担续跑：停止 / 完成 / 超时 / 服务销毁
 * 都必须立刻 removeView，绝不留在屏幕上。
 *
 * 不用 Compose：Service 里没有 LifecycleOwner，挂 ComposeView 要额外造一套
 * owner，静态检查看不见、真机上最容易漏。纯 View + 主线程 Handler，
 * 权限没给就直接不显示，不弹窗、不报错、不拖垮制作。
 *
 * KeepAlive.kt 仍只负责权限引导，不碰 WindowManager；两件事不混在一起。
 */
object OverlayProgress {

    @Volatile private var root: View? = null
    @Volatile private var titleView: TextView? = null
    @Volatile private var infoView: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    private fun enabled(c: Context): Boolean =
        Prefs.overlayProgress(c) && KeepAlive.overlayGranted(c)

    private fun lp(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 96
        }

    private fun build(c: Context): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(0xE8222222.toInt())
            cornerRadius = 28f
        }
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(28, 20, 28, 20)
            elevation = 8f
        }
        val title = TextView(c).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        val info = TextView(c).apply {
            textSize = 11f
            setTextColor(0xFFBBBBBB.toInt())
            setPadding(0, 6, 0, 0)
        }
        val stop = TextView(c).apply {
            text = "停止"
            textSize = 11f
            setTextColor(0xFFFF8A80.toInt())
            setPadding(0, 10, 0, 0)
            setOnClickListener {
                try {
                    // 服务已在前台，只是补发一个取消 Intent，不是后台偷偷拉起
                    c.startService(ProduceService.cancelIntent(c))
                } catch (t: Throwable) {
                    UiErrors.report("悬浮窗上的停止没生效，请在通知或软件里点停止", t)
                }
                hide(c)
            }
        }
        box.addView(title)
        box.addView(info)
        box.addView(stop)
        titleView = title
        infoView = info
        return box
    }

    /** 每次进度更新都走这里：不存在就建，存在就改文字。 */
    fun update(c: Context, book: String, stage: String, done: Int, total: Int) {
        val app = c.applicationContext
        if (!enabled(app)) {
            hide(app)
            return
        }
        main.post {
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@post
                if (root == null) {
                    val v = build(app)
                    wm.addView(v, lp())
                    root = v
                    EventLog.log(EventLog.Kind.UI, "OVERLAY_SHOW", "悬浮进度已显示")
                }
                titleView?.text = if (book.isBlank()) "听本 · 制作中" else "《$book》"
                infoView?.text = buildString {
                    append(stage)
                    if (total > 0) append(" · ").append(done).append("/").append(total)
                }
            } catch (t: Throwable) {
                // 厂商 ROM 可能拒绝 addView；浮窗是可选增强，不能拖垮制作
                UiErrors.report("悬浮进度显示不了，制作照常进行", t)
                root = null
                titleView = null
                infoView = null
            }
        }
    }

    /** 停止 / 完成 / 超时 / 服务销毁一律调它，绝不把 View 留在屏幕上。 */
    fun hide(c: Context) {
        val app = c.applicationContext
        main.post {
            val v = root ?: return@post
            root = null
            titleView = null
            infoView = null
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeViewImmediate(v)
            } catch (_: Throwable) { }
        }
    }
}
