package com.tingben.app.relay

import android.content.Context
import org.json.JSONObject

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

    /**
     * R407 · 审查 C 的 P1：回写冷却原来只在内存 HashMap，断网/限流常伴随
     * 进程被杀，重启后 Map 清空，R404 防的「每 10 秒重做一分钟合成」原样回来。
     * 落到私有 SharedPreferences，存绝对到期时间（墙上钟）；只认未过期项，
     * 设备校时回拨不会把冷却变成永久（回拨时 until <= now，直接丢弃）。
     * 键只含请求文件名与时间戳，不含正文、不含 Token。
     */
    fun cooldowns(c: Context): Map<String, Long> {
        val s = sp(c).getString("put_cooldowns", "") ?: return emptyMap()
        if (s.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(s)
            val now = System.currentTimeMillis()
            val m = HashMap<String, Long>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val until = o.optLong(k, 0L)
                if (until > now) m[k] = until
            }
            m
        } catch (_: Throwable) { emptyMap() }
    }

    private fun saveCooldowns(c: Context, m: Map<String, Long>) =
        sp(c).edit().putString("put_cooldowns", JSONObject(m).toString()).apply()

    fun setCooldown(c: Context, name: String, until: Long) {
        val m = cooldowns(c).toMutableMap()
        m[name] = until
        saveCooldowns(c, m)
    }

    fun clearCooldown(c: Context, name: String) {
        val m = cooldowns(c).toMutableMap()
        if (m.remove(name) != null) saveCooldowns(c, m)
    }

    /** 只记状态与字符数，正文一个字不落。 */
    fun state(c: Context): String = sp(c).getString("state", "没开") ?: "没开"
    fun setState(c: Context, v: String) = sp(c).edit().putString("state", v).apply()
}
