package com.tingben.app.relay

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * R377 · **全 App 唯一允许主动出站的类**（其余源码仍零出站，r375 守门迁移后按包放行）。
 *
 * 只连 api.github.com，用平台自带 HttpURLConnection + org.json，零第三方依赖。
 * 走 GitHub Contents API：列 queue / 取请求 / PUT 音频与状态 / DELETE 请求。
 * Token 由 RelayPrefs 传入，不硬编码、不落日志；请求正文只在内存里，不进任何日志行。
 */
internal class RelayClient(
    private val owner: String,
    private val repo: String,
    private val branch: String,
    private val token: String
) {
    class Err(val code: Int, val msg: String) : RuntimeException("relay http $code: $msg")

    data class QItem(val name: String, val sha: String)

    private fun url(path: String): URL {
        val u = URL(RelayContract.API_BASE + owner + "/" + repo + path)
        if (u.host != RelayContract.API_HOST) throw Err(0, "只允许连 api.github.com")
        return u
    }

    private fun open(path: String, method: String, body: ByteArray? = null): HttpURLConnection {
        val c = url(path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer " + token)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "tingben-relay-app")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body) }
        }
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val s = if (code in 200..299) c.inputStream else c.errorStream
        val b = try {
            val bos = ByteArrayOutputStream()
            s?.use { it.copyTo(bos) }
            bos.toByteArray()
        } catch (_: Throwable) { ByteArray(0) } finally { c.disconnect() }
        if (code !in 200..299) {
            val m = try { JSONObject(String(b, Charsets.UTF_8)).optString("message") }
                catch (_: Throwable) { "" }
            throw Err(code, m.take(120))
        }
        return b
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray =
        Base64.decode(s.replace(Regex("\\s"), ""), Base64.DEFAULT)

    /**
     * R404 · 真机日志实证（01:02~01:08）：同一条 id 合成其实成功了，却每轮
     * 报「HTTP 422 轮询失败」、请求文件一直不删、每 10 秒重做一次 50~60 秒的合成。
     * 根因是 GitHub Contents API **更新已存在文件必须带当前 sha**，旧 putObj
     * 一律裸 PUT：out/<id>.m4a 或 status/<id>.json 一旦写过，再写就 422。
     * 先 GET 目标拿 sha（404 = 新建不带），422/409 表示并发改过，重取一次再试。
     */
    private fun headSha(path: String): String? = try {
        val raw = read(open("/contents/" + path + "?ref=" + branch, "GET"))
        JSONObject(String(raw, Charsets.UTF_8)).optString("sha").takeIf { it.isNotEmpty() }
    } catch (e: Err) {
        if (e.code == 404) null else throw e
    }

    private fun putObj(path: String, raw: ByteArray, message: String): JSONObject {
        fun body(sha: String?): ByteArray = JSONObject()
            .put("message", message)
            .put("content", b64(raw))
            .put("branch", branch)
            .apply { if (sha != null) put("sha", sha) }
            .toString().toByteArray(Charsets.UTF_8)
        var sha = headSha(path)
        return try {
            JSONObject(String(read(open("/contents/" + path, "PUT", body(sha))), Charsets.UTF_8))
        } catch (e: Err) {
            // 并发：GET 之后文件被改过，按最新 sha 再试一次，仍失败才如实上抛。
            if (e.code != 422 && e.code != 409) throw e
            sha = headSha(path)
            JSONObject(String(read(open("/contents/" + path, "PUT", body(sha))), Charsets.UTF_8))
        }
    }

    /** 取最老的一条请求；.gitkeep 与目录项不进队列。 */
    fun listQueue(): List<QItem> {
        val raw = read(open("/contents/" + RelayContract.DIR_QUEUE + "?ref=" + branch, "GET"))
        val arr = JSONArray(String(raw, Charsets.UTF_8))
        val out = ArrayList<QItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name")
            if (o.optString("type") == "file" && n.endsWith(".json") && n != ".gitkeep")
                out.add(QItem(n, o.optString("sha")))
        }
        return out.sortedBy { it.name }
    }

    /** 返回 (正文, 文件 sha)；sha 供合成完 DELETE 用。 */
    fun getRequest(name: String): Pair<String, String> {
        val raw = read(open("/contents/" + RelayContract.DIR_QUEUE + "/" + name + "?ref=" + branch, "GET"))
        val o = JSONObject(String(raw, Charsets.UTF_8))
        val req = JSONObject(String(unb64(o.optString("content")), Charsets.UTF_8))
        return Pair(req.optString("text"), o.optString("sha"))
    }

    fun audioPath(id: String, format: String): String =
        RelayContract.DIR_OUT + "/" + id + "." +
            if (format == RelayContract.FORMAT_M4A) "m4a" else "wav"

    /** 上传压缩后的音频，返回 blob sha（>1MB 时调用方要用它走 git/blobs）。 */
    fun putOut(id: String, bytes: ByteArray, format: String): String {
        val r = putObj(audioPath(id, format), bytes, "relay: 手机本地压缩后的音频")
        return r.optJSONObject("content")?.optString("sha") ?: ""
    }

    fun putDone(id: String, status: JSONObject) =
        putObj(RelayContract.DIR_STATUS + "/" + id + ".json",
            status.toString().toByteArray(Charsets.UTF_8), "relay: 完成状态")

    /** R401 · 排队态回写：与 done/failed 同一条 status 路径，不含正文。 */
    fun putBusy(id: String, status: JSONObject) =
        putObj(RelayContract.DIR_STATUS + "/" + id + ".json",
            status.toString().toByteArray(Charsets.UTF_8), "relay: 排队状态")

    fun putFailed(id: String, status: JSONObject) =
        putObj(RelayContract.DIR_STATUS + "/" + id + ".json",
            status.toString().toByteArray(Charsets.UTF_8), "relay: 失败状态")

    /** 正文即用即删：无论合成成功还是失败，请求文件都要 DELETE 掉。 */
    fun deleteQueue(name: String, sha: String) {
        val body = JSONObject()
            .put("message", "relay: 请求已取走，正文即用即删")
            .put("sha", sha)
            .put("branch", branch)
            .toString().toByteArray(Charsets.UTF_8)
        read(open("/contents/" + RelayContract.DIR_QUEUE + "/" + name, "DELETE", body))
    }
}
