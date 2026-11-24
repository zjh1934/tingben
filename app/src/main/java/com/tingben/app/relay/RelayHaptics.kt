package com.tingben.app.relay

import android.content.Context
import com.tingben.app.diag.EventLog
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * R377 · 远程 API 被调用时手机给反馈（用户明确要「开始和结束的震动不一样」）。
 *
 * 查证结论：VibrationEffect.createWaveform(timings, amplitudes, -1) 要
 * timings/amplitudes 等长、首项 0 起振、振幅 0 是停不是静音；设备没有
 * hasAmplitudeControl 时非零振幅会被拉到满幅，所以回退一套纯开/关节奏。
 * 前台服务里用 USAGE_NOTIFICATION 的 VibrationAttributes，避免后台振动被系统吞掉。
 * 没有振动马达 / 任何异常都静默放过，绝不影响合成主链。
 */
internal object RelayHaptics {

    fun start(c: Context) = play(c, RelayContract.HAPTIC_START,
        RelayContract.START_TIMINGS, RelayContract.START_AMPS, RelayContract.START_ONOFF)

    fun done(c: Context) = play(c, RelayContract.HAPTIC_DONE,
        RelayContract.DONE_TIMINGS, RelayContract.DONE_AMPS, RelayContract.DONE_ONOFF)

    fun fail(c: Context) = play(c, RelayContract.HAPTIC_FAIL,
        RelayContract.FAIL_TIMINGS, RelayContract.FAIL_AMPS, RelayContract.FAIL_ONOFF)

    private fun vibrator(c: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) { null }

    private fun play(c: Context, phase: String, timings: LongArray,
                     amps: IntArray, onOff: LongArray) {
        try {
            val v = vibrator(c)
            if (v == null || !v.hasVibrator()) {
                EventLog.log(EventLog.Kind.AUDIT, "HAPTIC_SKIP",
                    "phase=$phase · 设备没有振动马达")
                return
            }
            val amp = v.hasAmplitudeControl()
            val ms = timings.sum()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val e = if (amp)
                    VibrationEffect.createWaveform(timings, amps, -1)
                else VibrationEffect.createWaveform(onOff, -1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val a = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION)
                    v.vibrate(e, a)
                } else v.vibrate(e)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(onOff, -1)
            }
            // R381 · 用户说「完成的长震动没注意到」：日志先把「真调用了」记下来，
            // 下轮贴日志就能分清是没触发、被系统吞、还是节奏不够明显。
            EventLog.log(EventLog.Kind.AUDIT, "HAPTIC",
                "phase=$phase · ${ms}ms · 振幅控制=$amp",
                fields = mapOf("phase" to phase, "ms" to ms.toString()))
        } catch (t: Throwable) {
            // 震动是反馈不是主链，任何设备差异都不许把合成搞失败。
            EventLog.log(EventLog.Kind.AUDIT, "HAPTIC_SKIP",
                "phase=$phase · 被系统拒绝", level = EventLog.Level.WARN,
                fields = mapOf("err" to t.javaClass.simpleName))
        }
    }
}
