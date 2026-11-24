package com.tingben.app.gateway

/**
 * R375 · 手机端语音合成网关的单一常量真值。
 *
 * 与 contracts/gateway_contract.json 逐项对齐（scripts/r375_verify.py 静态核对）。
 * 决策真值在 方案/听本_手机端语音合成网关_方案研究.md 的「Phase 0 决策记录」，
 * 后续每轮必须回头对照，不许偷偷扩到云端、不许给 API 加音色参数、
 * 不许放开并发、不许另造合成链。
 *
 * D1：INTERNET 只用于用户手动开启的本机/局域网监听，App 零出站请求、零上传。
 * D6：输入上限直接取 ClipSynth.MAX_CHARS，网关复用现成单次合成真源。
 */
internal object GatewayContract {
    const val DEFAULT_PORT = 8765
    const val DEFAULT_BIND = "127.0.0.1"
    const val BIND_LAN = "0.0.0.0"

    /** = core/ClipSynth.kt 的 MAX_CHARS；本轮不分段，长文本留 Phase 2。 */
    const val MAX_CHARS = 1500
    const val JOB_RETENTION_MS = 600_000L
    const val IDLE_HINT_MS = 300_000L
    const val MAX_CONCURRENCY = 1

    const val JSON = "application/json; charset=utf-8"
    const val PROBLEM = "application/problem+json; charset=utf-8"
    const val WAV = "audio/wav"
    /** R377 · 回传前已在手机本地压成 AAC-LC/64kbps/单声道；WAV 仅作转码失败时的如实回退。 */
    const val M4A = "audio/mp4"
    const val XFER_M4A = "m4a"
    const val XFER_WAV = "wav"
    const val HTML = "text/html; charset=utf-8"

    const val PATH_ROOT = "/"
    const val PATH_HEALTH = "/v1/health"
    const val PATH_OPENAPI = "/v1/openapi.json"
    const val PATH_SPEAK = "/v1/speak"
    const val PATH_JOBS = "/v1/jobs"

    /** 调用方带来的音色字段一律静默丢弃，绝不回显、绝不覆盖本机预设。 */
    val IGNORED_FIELDS = setOf(
        "voice", "speaker", "speed", "seed", "temperature", "topK", "topP",
        "mode", "knobs", "instruct", "style", "refText", "lang"
    )

    /** 状态码 → 错误码。错误体永远是 application/problem+json，绝不回 HTML。 */
    val ERROR_CODES = mapOf(
        400 to "empty_text",
        401 to "unauthorized",
        404 to "not_found",
        405 to "method_not_allowed",
        409 to "job_not_ready",
        413 to "text_too_long",
        429 to "busy",
        503 to "engine_unavailable"
    )

    /** 内置单文件网页：浏览器打开网关地址就能输文字、填 Token、播放 / 下载。 */
    val INDEX_HTML = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>听本 · 语音网关</title></head>
<body style="font:15px/1.6 system-ui;padding:24px;max-width:640px;margin:auto">
<h2>听本语音网关</h2>
<p>音色用的是这台手机上设好的预设，这里只填文字。</p>
<textarea id="t" rows="4" style="width:100%;box-sizing:border-box">你好，这是一段测试文字。</textarea>
<p>Token&nbsp;<input id="k" style="width:70%"></p>
<button onclick="go()">合成并播放</button>&nbsp;<a id="dl" download="tingben.m4a">下载</a>
<p><audio id="a" controls style="width:100%"></audio></p>
<pre id="s"></pre>
<script>
function go(){const el=document.getElementById('s');el.textContent='合成中…';
fetch('/v1/speak',{method:'POST',headers:{'Content-Type':'application/json',
 'Authorization':'Bearer '+document.getElementById('k').value.trim()},
 body:JSON.stringify({text:document.getElementById('t').value})})
 .then(async r=>{if(!r.ok){const j=await r.json();throw new Error(j.title||('HTTP '+r.status))}
   const ct=r.headers.get('Content-Type')||'';
   if(!/audio\//.test(ct))throw new Error('返回的不是音频');
   document.getElementById('dl').download='tingben.'+(ct.indexOf('mp4')>=0?'m4a':'wav');
   return r.blob()})
 .then(b=>{const u=URL.createObjectURL(b);const a=document.getElementById('a');
   a.src=u;document.getElementById('dl').href=u;a.play();el.textContent='好了'})
 .catch(e=>{el.textContent='没成：'+e.message})}
</script></body></html>"""
}
