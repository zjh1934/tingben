package com.tingben.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * R158 · 后台运行与系统级权限引导。
 *
 * 用户明确推翻了 1.1.1 立下的旧硬约束「不申请 SYSTEM_ALERT_WINDOW / 不碰
 * 系统级能力」，口径改成：**能拿的权限尽量拿，让软件能力更强**。
 * 这轮把权限引导做成正式能力，不再把「不做」写进契约、文案或守门。
 *
 * 但调研（沙盒根 `听本_后台保活与权限_方案.md`，多源交叉验证）给出两条
 * 必须如实说的边界，代码按这个来，不堆无用权限：
 *
 *  ① **权限多 ≠ 保活**。Android 15+ 的 `dataSync` / `mediaProcessing` 前台服务
 *     各自限「24 小时内累计 6 小时」，单本几小时的任务会撞上限；被杀后
 *     靠的是每段落盘的检查点 + 用户回到应用/点通知续跑，不是常驻进程。
 *  ② `SYSTEM_ALERT_WINDOW` **不是保活白名单**。官方从未规定它免于 LMK /
 *     Doze / 厂商冻结；Android 15+ 反而要求「权限 + 当前有可见叠加窗口」
 *     才能从后台启动前台服务。所以浮窗只做**可选的可见进度控件**，
 *     默认不常显，停止时立刻 removeView，绝不拿它当续跑基础。
 *
 * 因此这里只走**官方标准 Intent**（可复检、可回退）：
 *   · `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` + 复检
 *   · `ACTION_MANAGE_OVERLAY_PERMISSION` + `package:` + `canDrawOverlays()` 复检
 *   · `ACTION_APPLICATION_DETAILS_SETTINGS` 兜底（HyperOS 最稳定入口）
 * 小米「自启动 / 省电策略无限制 / 后台弹出界面 / 锁任务」没有小米官方
 * 承诺的稳定深层 Intent，**不硬编码组件名**。R158b：图文步骤也不进应用，
 * 说明放在沙盒方案文档里，界面只给按钮和状态。
 *
 * 无障碍（AccessibilityService）：旧约束「绝不申请」同样清掉，但这轮**不声明**——
 * 调研结论是它不是官方保活白名单，且仅用于拉活有明确合规风险。
 * 等有真实用途（例如朗读别的 App 选中文字）再单独评估，别为了保活硬上。
 */
object KeepAlive {

    /** 这台机器是不是小米/Redmi/POCO（只进诊断快照，不驱动 UI 教程）。 */
    fun isXiaomi(): Boolean =
        Build.MANUFACTURER?.lowercase()?.let {
            it.contains("xiaomi") || it.contains("redmi") || it.contains("poco")
        } ?: false

    fun batteryIgnoring(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
        else {
            val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(c.packageName) ?: false
        }
    } catch (_: Throwable) { false }

    fun overlayGranted(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
        else Settings.canDrawOverlays(c)
    } catch (_: Throwable) { false }

    /**
     * 打开一个系统设置页。先 resolveActivity 再 startActivity，任何一步失败
     * 都回退到「应用详情」；全失败返回 false 让 UI 给中文提示，不静默吞掉。
     * 不用 ActivityResult 判断授权结果——官方特殊权限流程不保证 result code，
     * 所以一律在回到前台时用 batteryIgnoring()/overlayGranted() 复检。
     */
    private fun open(c: Context, intent: Intent): Boolean = try {
        val pm = c.packageManager
        val i = if (intent.resolveActivity(pm) != null) intent else fallback(c)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
        true
    } catch (_: Throwable) {
        try {
            val f = fallback(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(f)
            true
        } catch (_: Throwable) { false }
    }

    private fun fallback(c: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + c.packageName))

    fun requestIgnoreBattery(c: Context): Boolean =
        open(c, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + c.packageName)))

    fun requestOverlay(c: Context): Boolean =
        open(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + c.packageName)))

    fun openAppDetails(c: Context): Boolean = open(c, fallback(c))

    /** 给诊断摘要用：一行说清权限现状，排查「任务怎么没了」时有据可查。 */
    fun snapshot(c: Context): String =
        "batteryIgnoring=${batteryIgnoring(c)} overlay=${overlayGranted(c)} " +
            "xiaomi=${isXiaomi()} sdk=${Build.VERSION.SDK_INT}"
}
