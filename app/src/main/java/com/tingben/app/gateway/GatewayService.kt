package com.tingben.app.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * R375 · 手机端语音合成网关服务（D2 / D5）。
 *
 * 独立 `specialUse` 前台服务，**不复用** ProduceService 的 mediaProcessing：
 * Android 15 起那类前台服务每 24h 只有 6h 额度，长驻监听会把整本听书挤掉。
 * 唤醒锁只在真合成时持，监听段不持；被厂商清理不强拉活，下次手动开即可
 * （冷启自动恢复留 Phase 3，见方案报告决策记录 D5）。
 *
 * 本轮只绑 127.0.0.1 / 局域网，**不连云端、不开公网**（D3）。
 * 请求正文不落日志，只留方法与路径；错误体一律 problem+json，不回 HTML。
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_START = "com.tingben.app.GATEWAY_START"
        const val ACTION_STOP = "com.tingben.app.GATEWAY_STOP"
        private const val NOTIF_ID = 0x4757
        private const val CHANNEL = "tingben_gateway"

        fun start(c: Context) {
            val i = Intent(c, GatewayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, GatewayService::class.java).setAction(ACTION_STOP))
        }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "tingben-gateway-io").apply { isDaemon = true }
    }
    private lateinit var wake: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tingben:gateway-unit")
            wake.setReferenceCounted(false)
            GatewaySynth.onBusyStart = {
                if (!wake.isHeld) wake.acquire(10 * 60 * 1000L)
            }
            GatewaySynth.onBusyEnd = {
                if (wake.isHeld) wake.release()
            }
        }
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopGateway()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!GatewayPrefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startGateway()
        // 不做 START_STICKY 拉活：进程被杀后监听线程已死，系统重启只是空壳。
        // 恢复靠设置页再点一次「开启网关」；自动恢复留 Phase 3。
        return START_NOT_STICKY
    }

    private fun startGateway() {
        if (running) return
        synchronized(this) {
            if (running) return
            try {
                val port = GatewayPrefs.port(this)
                val bind = GatewayPrefs.bind(this)
                server = ServerSocket(port, 8, InetAddress.getByName(bind))
                running = true
                Thread({
                    while (running) {
                        try {
                            val s = server?.accept() ?: break
                            pool.execute { handle(s) }
                        } catch (_: Throwable) {
                            if (running) continue else break
                        }
                    }
                }, "tingben-gateway-accept").apply { isDaemon = true }.start()
            } catch (_: Throwable) {
                running = false
            }
        }
    }

    private fun stopGateway() {
        running = false
        try { server?.close() } catch (_: Throwable) { }
        server = null
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15_000
            val inp = BufferedInputStream(sock.getInputStream())
            val lines = ArrayList<String>()
            while (true) {
                val l = readLine(inp) ?: break
                if (l.isEmpty()) break
                lines.add(l)
                if (lines.size > 80) break
            }
            val len = lines.firstOrNull { it.startsWith("content-length", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
                ?.coerceIn(0, 1 shl 20) ?: 0
            val body = ByteArray(len)
            if (len > 0) readFully(inp, body)
            val req = GatewayHttp.parse(lines, body) ?: return
            val res = route(req, sock.inetAddress?.isLoopbackAddress == true)
            val out = sock.getOutputStream()
            out.write(GatewayHttp.bytes(res))
            out.flush()
        } catch (_: Throwable) {
            // 连接级异常不落正文；统一在路由层给 JSON，这里不补 HTML。
        } finally {
            try { sock.close() } catch (_: Throwable) { }
        }
    }

    /** 全部端点。除 GET / 外都要 Token；错误一律 problem+json。 */
    private fun route(req: HttpReq, loopback: Boolean): HttpRes {
        if (req.method == "OPTIONS") return GatewayHttp.options()
        if (req.method == "GET" && req.path == GatewayContract.PATH_ROOT)
            return GatewayHttp.html(GatewayContract.INDEX_HTML)
        if (!GatewayPrefs.verify(this, req.headers["authorization"]))
            return GatewayHttp.problem(401, "先在网关面板复制 Token")

        return when {
            req.method == "GET" && req.path == GatewayContract.PATH_HEALTH -> health(loopback)
            req.method == "GET" && req.path == GatewayContract.PATH_OPENAPI -> openapi()
            req.method == "POST" && req.path == GatewayContract.PATH_SPEAK -> speak(req)
            req.method == "GET" && req.path == GatewayContract.PATH_SPEAK ->
                GatewayHttp.problem(405, "这个端点要用 POST")
            req.method == "POST" && req.path == GatewayContract.PATH_JOBS -> submit(req)
            req.path.startsWith(GatewayContract.PATH_JOBS + "/") -> jobRoute(req)
            req.method != "GET" && req.method != "POST" ->
                GatewayHttp.problem(405, "只支持 GET / POST / OPTIONS")
            else -> GatewayHttp.problem(404, "没有这个地址")
        }
    }

    private fun health(loopback: Boolean): HttpRes {
        val reason = GatewaySynth.blockReason(this)
        val e = GatewayHttp::esc
        val raw = "{\"ok\":${reason == null},\"engine\":${reason == null}," +
            "\"version\":\"gateway-1\",\"preset\":\"device-local\",\"concurrency\":1," +
            "\"bind\":\"${e(GatewayPrefs.bind(this))}\",\"loopback\":$loopback," +
            "\"reason\":\"${e(reason ?: "")}\"}"
        return GatewayHttp.json(200, raw)
    }

    private fun openapi(): HttpRes {
        val e = GatewayHttp::esc
        val paths = listOf(
            Triple("GET", GatewayContract.PATH_ROOT, GatewayContract.HTML),
            Triple("GET", GatewayContract.PATH_HEALTH, GatewayContract.JSON),
            Triple("GET", GatewayContract.PATH_OPENAPI, GatewayContract.JSON),
            Triple("POST", GatewayContract.PATH_SPEAK, GatewayContract.M4A),
            Triple("POST", GatewayContract.PATH_JOBS, GatewayContract.JSON),
            Triple("GET", GatewayContract.PATH_JOBS + "/{id}", GatewayContract.JSON),
            Triple("GET", GatewayContract.PATH_JOBS + "/{id}/audio", GatewayContract.M4A)
        )
        val body = paths.joinToString(",") { (m, p, t) ->
            "\"${e(p)}\":{\"${m.lowercase()}\":{\"responses\":{\"200\":{\"content\":" +
                "{\"${e(t)}\":{\"schema\":{\"type\":\"string\",\"format\":\"binary\"}}}}}}}"
        }
        return GatewayHttp.json(200,
            "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"听本网关\",\"version\":\"1\"}," +
                "\"paths\":{$body}}")
    }

    private fun parseText(req: HttpReq): Pair<String?, Int> {
        val o = try { JSONObject(String(req.body, Charsets.UTF_8)) } catch (_: Throwable) { null }
            ?: return Pair(null, 0)
        val t = o.opt("text")
        if (t !is String || t.isBlank()) return Pair(null, 0)
        val ignored = GatewayContract.IGNORED_FIELDS.count { o.has(it) }
        return Pair(t, ignored)
    }

    private fun tooLong(text: String): Boolean = text.length > GatewayContract.MAX_CHARS

    private fun speak(req: HttpReq): HttpRes {
        val (text, ignored) = parseText(req)
        if (text == null) return GatewayHttp.problem(400, "先给一段要念的文字")
        if (tooLong(text)) return GatewayHttp.problem(413,
            "最多 ${GatewayContract.MAX_CHARS} 字（= ClipSynth.MAX_CHARS），先少贴一点")
        val r = GatewaySynth.speak(this, text, ignored)
        return when {
            r.busy -> GatewayHttp.problem(429, "一次只合成一句，稍后重试",
                mapOf("Retry-After" to "2"))
            !r.ok -> GatewayHttp.problem(503, r.reason ?: "合成没成功")
            else -> GatewayHttp.audio(r.bytes ?: ByteArray(0), ignored, r.format)
        }
    }

    private fun submit(req: HttpReq): HttpRes {
        val (text, ignored) = parseText(req)
        if (text == null) return GatewayHttp.problem(400, "先给一段要念的文字")
        if (tooLong(text)) return GatewayHttp.problem(413,
            "最多 ${GatewayContract.MAX_CHARS} 字（= ClipSynth.MAX_CHARS），先少贴一点")
        val id = GatewaySynth.submit(this, text, ignored)
            ?: return GatewayHttp.problem(503,
                GatewaySynth.blockReason(this) ?: "一次只合成一句，稍后重试",
                mapOf("Retry-After" to "2"))
        return GatewayHttp.json(202,
            "{\"id\":\"${GatewayHttp.esc(id)}\",\"state\":\"queued\",\"chars\":${text.length}}",
            mapOf("Location" to GatewayContract.PATH_JOBS + "/" + id, "Retry-After" to "2"))
    }

    private fun jobRoute(req: HttpReq): HttpRes {
        if (req.method != "GET") return GatewayHttp.problem(405, "任务查询要用 GET")
        val tail = req.path.removePrefix(GatewayContract.PATH_JOBS + "/")
        val parts = tail.split('/')
        val id = parts.getOrNull(0).orEmpty()
        val sub = parts.getOrNull(1).orEmpty()
        val j = GatewaySynth.job(id)
            ?: return GatewayHttp.problem(404, "这条任务不存在或已过期")
        if (sub == "audio") {
            val b = j.audio
            return when {
                j.state == "succeeded" && b != null -> GatewayHttp.audio(b, j.ignored, j.format)
                j.state == "failed" -> GatewayHttp.problem(503, j.reason ?: "合成没成功")
                else -> GatewayHttp.problem(409, "还在合成，等一会儿再取")
            }
        }
        if (sub.isNotEmpty()) return GatewayHttp.problem(404, "没有这个子路径")
        val extra = if (j.state == "queued" || j.state == "running")
            mapOf("Retry-After" to "2") else emptyMap()
        val e = GatewayHttp::esc
        return GatewayHttp.json(200,
            "{\"id\":\"${e(j.id)}\",\"state\":\"${e(j.state)}\",\"progress\":${j.progress}," +
                "\"stage\":\"${e(j.stage)}\",\"chars\":${j.chars}}", extra)
    }

    private fun readLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.endsWith("\r")) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }

    private fun readFully(inp: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = inp.read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL, "语音网关", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun notif(): Notification {
        val pi = PendingIntent.getService(this, 2,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("听本语音网关")
            .setContentText(GatewayPrefs.displayUrl(this) + " · 点这里关闭")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopGateway()
        pool.shutdownNow()
        GatewaySynth.onBusyStart = {}
        GatewaySynth.onBusyEnd = {}
        if (::wake.isInitialized && wake.isHeld) {
            try { wake.release() } catch (_: Throwable) { }
        }
        super.onDestroy()
    }
}
