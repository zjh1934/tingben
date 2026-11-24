package com.tingben.app.gateway

import android.content.Context
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * R375 · 网关配置。默认关、默认只绑 127.0.0.1、只要开启就要 Token（D3）。
 *
 * Token 取舍（如实写清）：校验侧只比 SHA-256，不比明文；但面板要能把
 * Token 复制给用户，所以设备本地另存一份明文（与整本正文、模型文件同在
 * allowBackup=false 的私有区，不进日志、不进诊断摘要、不外发）。
 * 报告里「只存 SHA-256」指的是校验路径，这一份本地明文是普通用户可用性
 * 的必要妥协，Phase 3 可改成「生成时只显示一次」再收紧。
 */
internal object GatewayPrefs {
    private const val FILE = "tingben_gateway"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun port(c: Context): Int = sp(c).getInt("port", GatewayContract.DEFAULT_PORT).coerceIn(1024, 65535)
    fun setPort(c: Context, v: Int) = sp(c).edit().putInt("port", v.coerceIn(1024, 65535)).apply()

    fun bind(c: Context): String {
        val v = sp(c).getString("bind", GatewayContract.DEFAULT_BIND) ?: GatewayContract.DEFAULT_BIND
        return if (v == GatewayContract.BIND_LAN) v else GatewayContract.DEFAULT_BIND
    }

    fun setBind(c: Context, lan: Boolean) = sp(c).edit()
        .putString("bind", if (lan) GatewayContract.BIND_LAN else GatewayContract.DEFAULT_BIND)
        .apply()

    fun token(c: Context): String {
        val s = sp(c)
        val old = s.getString("token", null)
        if (!old.isNullOrBlank()) return old
        return regenerate(c)
    }

    fun regenerate(c: Context): String {
        val raw = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val plain = raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        sp(c).edit().putString("token", plain)
            .putString("token_hash", sha(plain)).apply()
        return plain
    }

    /** 恒定时间比较，避免按字节早退被计时侧信道探测。 */
    fun verify(c: Context, authorization: String?): Boolean {
        val h = authorization?.trim() ?: return false
        if (!h.startsWith("Bearer ")) return false
        val want = sp(c).getString("token_hash", null) ?: return false
        return constantTimeEq(sha(h.removePrefix("Bearer ").trim()), want)
    }

    fun displayUrl(c: Context): String {
        val host = if (bind(c) == GatewayContract.BIND_LAN) (localIpv4() ?: "127.0.0.1")
        else GatewayContract.DEFAULT_BIND
        return "http://$host:${port(c)}"
    }

    /** R377 修 R375 遗留编译错：InetAddress 是 isLoopbackAddress，不是 isLoopback（那是 NetworkInterface 的）。 */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is InetAddress && !it.isLoopbackAddress && it.address?.size == 4 }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun constantTimeEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0
        for (i in a.indices) d = d or (a[i].code xor b[i].code)
        return d == 0
    }
}
