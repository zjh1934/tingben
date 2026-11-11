package com.tingben.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.tingben.app.core.KeepAlive
import com.tingben.app.core.Prefs
import com.tingben.app.core.UiErrors
import com.tingben.app.diag.EventLog

/**
 * R161 · 可选悬浮进度控件，默认关。
 * R214 · 按用户口径重画：不写字，只留圆环 + 中间百分比数字。
 *
 * 形态：整本书合成 = 上下两球叠成 8 字（上=整本进度、下=章节进度）；
 *       首页「粘一段即贴即听」= 单球。两态由 chapterPct 是否 >= 0 决定。
 * 可拖动：按住浮窗任意位置移动，位置只在本会话内有效，不落盘。
 *
 * 浮窗不是保活白名单、不承担续跑；停止 / 完成 / 超时 / 服务销毁
 * 都必须立刻 removeView。停止入口仍在通知与软件内，浮窗本身不再写「停止」二字。
 *
 * 不用 Compose：Service 里没有 LifecycleOwner，挂 ComposeView 要额外造一套 owner。
 * 纯 View + 主线程 Handler，权限没给就直接不显示，不弹窗、不拖垮制作。
 */
object OverlayProgress {

    @Volatile private var root: View? = null
    @Volatile private var lp: WindowManager.LayoutParams? = null
    @Volatile private var topRing: RingView? = null
    @Volatile private var bottomRing: RingView? = null
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
            // R214 · 改成 TOP|START，拖动时 x 增大就往右走，跟手指一致
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 96
        }

    /** R217 · 0~1 比例 → 0~100 的纯数值文本；越界一律夹住，绝不露出段号。 */
    private fun pctLabel(pct: Float): String =
        (pct * 100).toInt().coerceIn(0, 100).toString()

    /** 一枚球：底环 + 按百分比走的描边弧 + 中间百分比数字，不写书名、不写解释。 */
    private class RingView(c: Context, private val sizePx: Int) : View(c) {
        var pct: Float = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }

        private val stroke = sizePx * 0.11f
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = 0x55FFFFFF
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = 0xFF7CC4FF.toInt()
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.40f
            isFakeBoldText = true
        }
        // R217 · 用户真机反馈：球上写「1」「2」看着像段号，不像百分比。
        // 数字主体仍是 0~100 的纯数值（0% 写 0、90% 写 90），右侧补一个小号
        // % 字符标明单位；不写书名、不写解释、不写段数。
        private val percentSign = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.16f
            isFakeBoldText = true
        }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(sizePx, sizePx)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = sizePx / 2f - stroke
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, 0f, 360f, false, track)
            // 从 12 点方向顺时针描到百分比处：50% 就转到半圈
            if (pct > 0f) canvas.drawArc(oval, -90f, 360f * pct, false, arc)
            val fm = label.fontMetrics
            val baseY = cy - (fm.ascent + fm.descent) / 2f
            val num = pctLabel(pct)
            val signW = percentSign.measureText("%")
            // 数字略偏左、% 贴在右，两块合起来仍居中
            canvas.drawText(num, cx - signW / 2f - sizePx * 0.01f, baseY, label)
            canvas.drawText("%", cx + label.measureText(num) / 2f,
                baseY - sizePx * 0.11f, percentSign)
        }
    }

    private fun build(c: Context): LinearLayout {
        val size = (56 * c.resources.displayMetrics.density).toInt()
        val top = RingView(c, size)
        val bottom = RingView(c, size).apply { visibility = View.GONE }
        val pad = (4 * c.resources.displayMetrics.density).toInt()
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        box.addView(top)
        // 两球略叠，视觉上成 8 字；单球时这枚 GONE，只剩上面那枚
        box.addView(bottom, LinearLayout.LayoutParams(size, size).apply {
            topMargin = -(size / 7)
        })
        // R214 · 可拖动：按住整块移动，落点只改 LayoutParams，不动任何状态
        box.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0
            private var downY = 0
            private var originX = 0
            private var originY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val params = lp ?: return true
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX.toInt(); downY = e.rawY.toInt()
                        originX = params.x; originY = params.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = originX + e.rawX.toInt() - downX
                        params.y = originY + e.rawY.toInt() - downY
                        try {
                            (v.context.getSystemService(Context.WINDOW_SERVICE)
                                as? WindowManager)?.updateViewLayout(v, params)
                        } catch (_: Throwable) { }
                    }
                }
                return true
            }
        })
        topRing = top
        bottomRing = bottom
        return box
    }

    /**
     * chapterPct >= 0 → 双球（整本 + 章节，8 字）；< 0 → 单球（即贴即听）。
     * 默认 -1f 保持旧调用点零改动。
     */
    fun update(c: Context, book: String, stage: String, done: Int, total: Int,
               chapterPct: Float = -1f) {
        val app = c.applicationContext
        if (!enabled(app)) { hide(app); return }
        main.post {
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@post
                if (root == null) {
                    val params = lp()
                    val v = build(app)
                    wm.addView(v, params)
                    root = v
                    lp = params
                    EventLog.log(EventLog.Kind.UI, "OVERLAY_SHOW", "悬浮进度已显示")
                }
                val topPct = if (total > 0) done.toFloat() / total else 0f
                topRing?.pct = topPct
                // R255 · 上球真机反馈「不更新」：每次都强制重绘一次；不落书名，
                // 只记两个纯数值，真机日志可定位是上游没传还是 View 没画。
                topRing?.postInvalidate()
                EventLog.log(EventLog.Kind.UI, "OVERLAY_PCT",
                    "top=${(topPct * 100).toInt()} bottom=${(chapterPct * 100).toInt()} " +
                        "done=$done total=$total", level = EventLog.Level.TRACE)
                if (chapterPct >= 0f) {
                    bottomRing?.visibility = View.VISIBLE
                    bottomRing?.pct = chapterPct
                } else {
                    bottomRing?.visibility = View.GONE
                }
            } catch (t: Throwable) {
                // 厂商 ROM 可能拒绝 addView；浮窗是可选增强，不能拖垮制作
                UiErrors.report("悬浮进度显示不了，制作照常进行", t)
                root = null; lp = null; topRing = null; bottomRing = null
            }
        }
    }

    /** 停止 / 完成 / 超时 / 服务销毁一律调它，绝不把 View 留在屏幕上。 */
    fun hide(c: Context) {
        val app = c.applicationContext
        main.post {
            val v = root ?: return@post
            root = null; lp = null; topRing = null; bottomRing = null
            try {
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeViewImmediate(v)
            } catch (_: Throwable) { }
        }
    }
}
