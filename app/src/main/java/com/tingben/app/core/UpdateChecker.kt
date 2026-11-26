package com.tingben.app.core

import android.content.Context
import android.util.Xml
import com.tingben.app.BuildConfig
import com.tingben.app.diag.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

/**
 * R408 · 应用内「检查更新」。研究员 B 的定案：听本不需要更新 SDK、不需要服务器，
 * 现有的 jsdelivr 镜像 `updates.atom` 就是唯一数据源。
 *
 * 只做三件事：拉公开 Atom → 手析第一个 entry 的 title / updated / enclosure →
 * 按点分段比数值版本号。不应用内下载、不安装、不验签（系统安装器本来会验），
 * 发现新版只把直链递给系统浏览器/用户已装的下载器。
 *
 * 隐私（三份调研一致，r408_verify 逐条守）：
 *  · 固定单域名、固定 UA，不设 Referer；
 *  · 请求里没有 Token / 设备标识 / 安装列表 / 账号 / 朗读正文；
 *  · 日志只记 code / ms / cache / latest 一行，响应体不落盘；
 *  · 复用已有 INTERNET，AndroidManifest 零改动。
 */
internal object UpdateChecker {

    const val FEED = "https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom"
    const val CURRENT_SOURCE = "jsdelivr"
    private const val SP = "tingben_update"
    private const val THROTTLE_MS = 24L * 60 * 60 * 1000
    private const val CONNECT_MS = 8000
    private const val READ_MS = 10000

    data class Info(
        val version: String,
        val updated: String,
        val url: String,
        val bytes: Long,
        val summary: String
    )

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class Latest(val version: String, val fromCache: Boolean) : State()
        data class Available(val info: Info, val fromCache: Boolean) : State()
        data class Failed(val reason: String) : State()
    }

    private fun sp(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /**
     * 按点分段比数值：1.1.9 < 1.1.62；v/V 前缀与 -beta 之外的后缀剥掉。
     * 返回 -2 = 版本串格式异常（必须当「更新信息格式异常」，不许当成「已是最新」）。
     */
    fun compare(a: String, b: String): Int {
        fun parts(s: String): List<Int>? {
            val t = s.trim().trimStart('v', 'V').substringBefore('-')
            val ps = t.split('.')
            if (ps.isEmpty() || ps.any { it.isBlank() }) return null
            val out = ArrayList<Int>(ps.size)
            for (p in ps) out.add(p.toIntOrNull() ?: return null)
            return out
        }
        val x = parts(a) ?: return -2
        val y = parts(b) ?: return -2
        for (i in 0 until maxOf(x.size, y.size)) {
            val xi = if (i < x.size) x[i] else 0
            val yi = if (i < y.size) y[i] else 0
            if (xi != yi) return if (xi < yi) -1 else 1
        }
        return 0
    }

    fun cached(c: Context): Info? {
        val s = sp(c)
        val v = s.getString("version", "") ?: ""
        if (v.isBlank()) return null
        return Info(v, s.getString("updated", "") ?: "",
            s.getString("url", "") ?: "", s.getLong("bytes", 0L),
            s.getString("summary", "") ?: "")
    }

    fun lastCheckedAt(c: Context): Long = sp(c).getLong("last_ms", 0L)

    fun shouldAutoCheck(c: Context): Boolean =
        System.currentTimeMillis() - lastCheckedAt(c) >= THROTTLE_MS

    fun skipped(c: Context): String = sp(c).getString("skipped", "") ?: ""
    fun skip(c: Context, version: String) =
        sp(c).edit().putString("skipped", version).apply()

    private fun save(c: Context, info: Info) =
        sp(c).edit()
            .putString("version", info.version)
            .putString("updated", info.updated)
            .putString("url", info.url)
            .putLong("bytes", info.bytes)
            .putString("summary", info.summary)
            .putLong("last_ms", System.currentTimeMillis()).apply()

    private fun touch(c: Context) =
        sp(c).edit().putLong("last_ms", System.currentTimeMillis()).apply()

    private fun log(code: Int, ms: Long, cache: Boolean, latest: String) {
        EventLog.log(EventLog.Kind.PIPELINE, "UPDATE_CHECK",
            "code=$code ms=$ms cache=$cache latest=$latest src=$CURRENT_SOURCE")
    }

    private fun stateFrom(c: Context, info: Info, fromCache: Boolean, respectSkip: Boolean): State {
        val cmp = compare(BuildConfig.VERSION_NAME, info.version)
        if (cmp == -2) return State.Failed("更新信息格式异常，可去项目主页查看")
        if (cmp >= 0) return State.Latest(BuildConfig.VERSION_NAME, fromCache)
        if (respectSkip && skipped(c) == info.version)
            return State.Latest(BuildConfig.VERSION_NAME, fromCache)
        return State.Available(info, fromCache)
    }

    /** 手析 Atom：只认第一个 entry，不依赖任何 XML 库。 */
    private fun parse(body: String): Info? {
        val p = Xml.newPullParser()
        p.setInput(body.reader())
        var inEntry = false
        var tag = ""
        val buf = StringBuilder()
        var version = ""
        var updated = ""
        var summary = ""
        var url = ""
        var bytes = 0L
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (p.name) {
                        "entry" -> inEntry = true
                        "title", "updated", "content" -> {
                            tag = p.name; buf.setLength(0)
                        }
                        "link" -> if (inEntry && url.isBlank() &&
                            p.getAttributeValue(null, "rel") == "enclosure") {
                            url = p.getAttributeValue(null, "href") ?: ""
                            bytes = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inEntry && tag.isNotEmpty()) buf.append(p.text)
                XmlPullParser.END_TAG -> {
                    if (inEntry && tag == p.name && tag.isNotEmpty()) {
                        when (tag) {
                            "title" -> if (version.isBlank()) version = buf.toString().trim()
                            "updated" -> if (updated.isBlank()) updated = buf.toString().trim()
                            "content" -> if (summary.isBlank()) summary = buf.toString().trim()
                        }
                        tag = ""; buf.setLength(0)
                    }
                    if (p.name == "entry" && inEntry) break
                }
            }
            event = p.next()
        }
        if (version.isBlank() || url.isBlank()) return null
        return Info(version, updated, url, bytes, summary)
    }

    /**
     * @param force 手动「再查一次」= true，无视 24h 节流与「跳过此版本」；
     *              进页自动检查 = false，命中缓存就不发请求。
     */
    suspend fun check(c: Context, force: Boolean): State = withContext(Dispatchers.IO) {
        val cache = cached(c)
        if (!force && cache != null && !shouldAutoCheck(c)) {
            log(200, 0L, true, cache.version)
            return@withContext stateFrom(c, cache, true, true)
        }
        val t0 = System.currentTimeMillis()
        var code = -1
        try {
            val conn = URL(FEED).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent",
                "TingBen/${BuildConfig.VERSION_NAME} (Android; update-check)")
            // 不设 Referer、不带任何 Token / 设备标识 / 账号
            code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                touch(c)
                log(code, System.currentTimeMillis() - t0, false, cache?.version ?: "-")
                return@withContext State.Failed("更新源暂时不可用，点此重试")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val info = try { parse(body) } catch (_: Throwable) { null }
            if (info == null) {
                touch(c)
                log(code, System.currentTimeMillis() - t0, false, "-")
                return@withContext State.Failed("更新信息格式异常，可去项目主页查看")
            }
            save(c, info)
            log(code, System.currentTimeMillis() - t0, false, info.version)
            return@withContext stateFrom(c, info, false, !force)
        } catch (_: Throwable) {
            touch(c)
            log(code, System.currentTimeMillis() - t0, false, cache?.version ?: "-")
            return@withContext State.Failed("网络开小差了，点此重试")
        }
    }
}
