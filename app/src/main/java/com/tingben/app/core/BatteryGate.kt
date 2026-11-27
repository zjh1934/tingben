package com.tingben.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * R412 · 低电保护：**只拦「本地电子书合成请求」**。
 *
 * 用户口径（原话）：「手机电量低于 20% 就拒绝……只拒绝合成电子书的」——
 * 远端中继、语音网关、调音台试听、首页粘贴文字合成、播放器一律不受影响。
 *
 * 判定严格按字面：电量百分比 **< 20** 就拦，20% 本身放行；**不看是否在充电**
 * （用户没给这个例外，不擅自放宽；plugged 只记进日志，将来一句话就能加豁免）。
 *
 * 实现零新增权限、零新增依赖：`ACTION_BATTERY_CHANGED` 是系统 sticky 广播，
 * `registerReceiver(null, filter)` 直接取最后一条快照，不用动态注册、不用
 * BatteryManager 系统服务，也不申请任何 BATTERY_* 权限。
 *
 * 读不到电池（模拟器 / 无电池设备 / scale<=0）一律**放行**，避免把正常设备
 * 误判成低电而全线拦死；这台真机是 Xiaomi 24117RK2CC，读得到。
 */
internal object BatteryGate {

    /** 阈值：低于（不含）20% 拒绝。改这一个常量即可，守门 r412 会验它。 */
    const val LIMIT_PCT = 20

    data class Snap(
        val pct: Int,          // -1 = 读不到
        val plugged: Int,      // 0 / AC / USB / WIRELESS
        val status: Int,
        val valid: Boolean
    )

    fun snap(c: Context): Snap {
        val i: Intent? = try {
            c.applicationContext.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Throwable) {
            null
        }
        if (i == null) return Snap(-1, 0, BatteryManager.BATTERY_STATUS_UNKNOWN, false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        if (level < 0 || scale <= 0)
            return Snap(-1, plugged, status, false)
        val pct = (level * 100) / scale
        return Snap(pct.coerceIn(0, 100), plugged, status, true)
    }

    /** 纯判定，给纯内存逻辑用例与守门复刻；不碰 Context。 */
    fun blocked(pct: Int, valid: Boolean): Boolean = valid && pct < LIMIT_PCT

    /**
     * 返回 null = 放行；非 null = 拒绝原因（直接给用户看，也进日志）。
     * 日志只记百分比与充电状态，不记书名、不记正文、不记任何标识。
     */
    fun bookBlockReason(c: Context): String? {
        val s = snap(c)
        if (!blocked(s.pct, s.valid)) return null
        return "电量 ${s.pct}%（低于 20%），电子书制作先等等；充到 20% 以上再点。" +
            "远端请求、试听和单段合成不受影响"
    }

    /** 一行脱敏埋点，供排查「为什么点了没反应」。 */
    fun logLine(s: Snap): String =
        "pct=${s.pct} plugged=${s.plugged} status=${s.status} valid=${s.valid} limit=$LIMIT_PCT"
}
