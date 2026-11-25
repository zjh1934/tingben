package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import com.tingben.app.gateway.GatewayPrefs
import com.tingben.app.gateway.GatewayService
import com.tingben.app.relay.RelayPrefs
import com.tingben.app.relay.RelayService

/**
 * R386 · 修真机实证的「更新后要重新点一次开关才生效」。
 *
 * 根因：应用更新（killDueToPackageUpdate）/ 系统清理会把进程连同前台服务一起杀掉，
 * 两个服务都返回 START_NOT_STICKY，进程一死就没人再读 RelayPrefs / GatewayPrefs。
 * 可在 Prefs 里 enabled 仍是 true，设置页开关照常显示「已开启」——
 * **界面说开着、服务其实是死的**，这才是这个 bug 最别扭的地方。
 *
 * 定案（查 Android 12+ 后台启动 FGS 官方限制后定）：
 * ① Application.onCreate 补一次：用户打开 App（可见状态）时按 Prefs 拉起，最稳、全版本可；
 * ② MY_PACKAGE_REPLACED / BOOT_COMPLETED / LOCKED_BOOT_COMPLETED 广播也补一次，
 *    这三个是官方列明的后台启动豁免，更新完不打开 App 也能自恢复；
 * ③ 两处都包 try/catch：厂商 ROM / 电池优化下若系统仍抛
 *    ForegroundServiceStartNotAllowedException / SecurityException，只记一行不崩，
 *    等下次冷启再补。**不改 START_NOT_STICKY、不注册高频广播强拉活**，
 *    沿用 R374 报告里「不强拉活、冷启恢复 + 通知一键关」的既定口径。
 *
 * 只恢复用户自己开过的：RelayPrefs.configured（开关+Token+仓库）、
 * GatewayPrefs.enabled。用户点过「关闭」的不会被悄悄拉回来。
 */
internal object ServiceResume {

    /** @param from 只写来源短词（冷启动 / 应用更新 / 开机），不写 Token、不写正文。 */
    fun resumeIfNeeded(c: Context, from: String) {
        if (RelayPrefs.configured(c)) {
            if (RelayService.isRunning()) {
                // R400 · 冷启动与 MY_PACKAGE_REPLACED 可能相隔几百毫秒各来一次；
                // 真机日志里留下两条 RELAY_RESUME + 两条 RELAY_ON。已在轮询就别再起。
                EventLog.log(EventLog.Kind.PIPELINE, "RELAY_RESUME_SKIP",
                    from + " · 已在轮询，跳过重复补拉", level = EventLog.Level.TRACE)
            } else try {
                RelayService.start(c)
                EventLog.log(EventLog.Kind.PIPELINE, "RELAY_RESUME",
                    from + " · 按已保存的开关恢复跨网中继")
            } catch (t: Throwable) {
                EventLog.log(EventLog.Kind.PIPELINE, "RELAY_RESUME_BLOCKED",
                    from + " · " + t.javaClass.simpleName + " · 等下次冷启再补",
                    level = EventLog.Level.WARN)
            }
        }
        if (GatewayPrefs.enabled(c)) {
            if (GatewayService.isRunning()) {
                EventLog.log(EventLog.Kind.PIPELINE, "GATEWAY_RESUME_SKIP",
                    from + " · 已在监听，跳过重复补拉", level = EventLog.Level.TRACE)
            } else try {
                GatewayService.start(c)
                EventLog.log(EventLog.Kind.PIPELINE, "GATEWAY_RESUME",
                    from + " · 按已保存的开关恢复语音网关")
            } catch (t: Throwable) {
                EventLog.log(EventLog.Kind.PIPELINE, "GATEWAY_RESUME_BLOCKED",
                    from + " · " + t.javaClass.simpleName + " · 等下次冷启再补",
                    level = EventLog.Level.WARN)
            }
        }
    }
}
