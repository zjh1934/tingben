package com.tingben.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * R386 · 应用更新 / 开机后按已保存的开关恢复两个前台服务。
 *
 * 只接这三个系统广播：Android Developers 把 ACTION_BOOT_COMPLETED /
 * ACTION_LOCKED_BOOT_COMPLETED / ACTION_MY_PACKAGE_REPLACED 列为
 * Android 12+ 「从后台启动前台服务」的豁免场景，其余（USER_PRESENT /
 * SCREEN_ON / CONNECTIVITY_CHANGE 之类）一律不注册——那是强拉活，
 * 会把进程变成常驻，和 R374 既定口径打架。
 *
 * 接收器里不做网络请求、不读正文、不写 Token；失败由 ServiceResume 记日志降级。
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                ServiceResume.resumeIfNeeded(c, "应用更新")
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                ServiceResume.resumeIfNeeded(c, "开机")
            else -> Unit
        }
    }
}
