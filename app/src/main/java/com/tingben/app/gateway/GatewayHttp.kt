package com.tingben.app.gateway

/**
 * R375 · 零第三方依赖的手写 HTTP/1.1 解析与响应构造（D4）。
 *
 * 这里全是纯 Kotlin、不碰 Android、不碰 socket，所以能逐字节单测；
 * GatewayService 只负责把 ServerSocket 接进来的字节喂给 parse()，
 * 再把 bytes() 写回去。Python 契约模拟器 gateway_sim.py 是它的行为镜像。
 *
 * 最重要的守恒：**除 GET / 的内置网页外，任何响应都不含 text/html**。
 * 错误一律 application/problem+json —— 下载器把 release 网页当 APK 下回来
 * 那个坑（R373）就是 HTML 错误页造成的，这里从构造层堵死。
 */
internal data class HttpReq(
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

internal data class HttpRes(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val extra: Map<String, String> = emptyMap()
)

internal object GatewayHttp {

    fun parse(headLines: List<String>, body: ByteArray): HttpReq? {
        if (headLines.isEmpty()) return null
        val first = headLines[0].trim().split(' ')
        if (first.size < 2) return null
        val full = first[1]
        val q = full.indexOf('?')
        val path = if (q >= 0) full.substring(0, q) else full
        val query = if (q >= 0) full.substring(q + 1) else ""
        val h = HashMap<String, String>()
        for (i in 1 until headLines.size) {
            val line = headLines[i]
            val k = line.indexOf(':')
            if (k > 0) h[line.substring(0, k).trim().lowercase()] = line.substring(k + 1).trim()
        }
        return HttpReq(first[0].uppercase(), path, query, h, body)
    }

    /** 状态行 + 统一 CORS + Connection: close + 二进制体。 */
    fun bytes(r: HttpRes): ByteArray {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ')
            .append(reason(r.status)).append("\r\n")
        if (r.contentType != null)
            sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        sb.append("Access-Control-Max-Age: 600\r\n")
        for ((k, v) in r.extra) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        return head + r.body
    }

    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    /** 所有错误的唯一出口：problem+json，不接收也不构造 HTML。 */
    fun problem(status: Int, title: String, extra: Map<String, String> = emptyMap()): HttpRes {
        val code = GatewayContract.ERROR_CODES[status] ?: "internal_error"
        return HttpRes(status, GatewayContract.PROBLEM,
            ("{\"type\":\"about:blank\",\"title\":\"" + esc(title) +
                "\",\"status\":" + status + ",\"code\":\"" + esc(code) +
                "\",\"detail\":\"" + esc(title) + "\"}")
                .toByteArray(Charsets.UTF_8), extra)
    }

    fun json(status: Int, raw: String, extra: Map<String, String> = emptyMap()): HttpRes =
        HttpRes(status, GatewayContract.JSON, raw.toByteArray(Charsets.UTF_8), extra)

    fun html(raw: String): HttpRes =
        HttpRes(200, GatewayContract.HTML, raw.toByteArray(Charsets.UTF_8))

    /** R377 · 回传的是手机本地压缩后的音频；format 决定 Content-Type 与下载名，不许谎报。 */
    fun audio(bytes: ByteArray, ignored: Int, format: String): HttpRes {
        val m4a = format == GatewayContract.XFER_M4A
        val ext = if (m4a) "m4a" else "wav"
        return HttpRes(
            200, if (m4a) GatewayContract.M4A else GatewayContract.WAV, bytes,
            mapOf(
                "Content-Disposition" to "attachment; filename=\"tingben.$ext\"",
                "X-Tingben-Preset" to "device-local",
                "X-Tingben-Format" to format,
                "X-Tingben-Ignored" to ignored.toString()
            )
        )
    }

    fun options(): HttpRes = HttpRes(204, null, ByteArray(0))

    fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
