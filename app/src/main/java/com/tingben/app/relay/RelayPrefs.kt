package com.tingben.app.relay

import android.content.Context

/**
 * R377 · 跨网中继配置。默认关；Token 只能由用户在这一页填一次。
 *
 * Token 绝不打进 APK、绝不进日志、绝不进诊断摘要：它与整本正文、模型文件
 * 同在 allowBackup=false 的私有区（AndroidManifest 已是主闸门）。
 * 这里存的是调用 api.github.com 的凭据，不是 R375 网关那个本机 Bearer Token，
 * 两者互不复用。
 */
internal object RelayPrefs {
    private const val FILE = "tingben_relay"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun setToken(c: Context, v: String) = sp(c).edit().putString("token", v.trim()).apply()

    fun owner(c: Context): String =
        (sp(c).getString("owner", null) ?: RelayContract.DEFAULT_OWNER).trim().ifBlank { RelayContract.DEFAULT_OWNER }
    fun setOwner(c: Context, v: String) = sp(c).edit().putString("owner", v.trim()).apply()

    fun repo(c: Context): String =
        (sp(c).getString("repo", null) ?: RelayContract.DEFAULT_REPO).trim().ifBlank { RelayContract.DEFAULT_REPO }
    fun setRepo(c: Context, v: String) = sp(c).edit().putString("repo", v.trim()).apply()

    fun branch(c: Context): String = RelayContract.DEFAULT_BRANCH

    /** 三个条件缺一不开：开关、Token、仓库。 */
    fun configured(c: Context): Boolean =
        enabled(c) && token(c).isNotBlank() && owner(c).isNotBlank() && repo(c).isNotBlank()

    /** 只记状态与字符数，正文一个字不落。 */
    fun state(c: Context): String = sp(c).getString("state", "没开") ?: "没开"
    fun setState(c: Context, v: String) = sp(c).edit().putString("state", v).apply()
}
