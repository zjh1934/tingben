#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R375 · 听本语音网关「契约模拟器」。

用途：用户要求「在上下文里模拟跑一遍，能不能跑通，跑不通就找原因修复」。
本文件**只用 Python 标准库**（http.server / http.client / threading），
零安装、纯 127.0.0.1 loopback、不碰云端、不碰真机，完整实现
contracts/gateway_contract.json 定义的同一份 API 契约，然后真起服务、
真发 HTTP 请求，逐条断言。

它同时是 Kotlin 侧 GatewayHttp.kt 的行为镜像：端点、状态码、
Content-Type、错误体、CORS、Token、并发锁 1、音色只读本机预设，
两边由 scripts/r375_verify.py 做静态对齐。

用法：python3 scripts/gateway_sim.py
"""
import hashlib
import http.client
import json
import pathlib
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONTRACT_PATH = pathlib.Path(__file__).resolve().parents[1] / 'contracts/gateway_contract.json'
C = json.loads(CONTRACT_PATH.read_text(encoding='utf-8'))
TOKEN = 'r375-local-test-token'
ENGINE_OK = {'v': True}
JOBS = {}
BUSY = threading.Lock()
SEEN_TYPES = []

# 模拟器测试钩子：?delay=0.5 用来制造并发窗口，不是 API 契约字段。
def wav_bytes(samples=1600):
    # R377 · 行为镜像：回传的是手机本地压缩后的 M4A（ftyp box 开头），不是大 WAV。
    # 名字保留 wav_bytes 只是不改调用点，字节形状按真实压缩产物。
    body = b'\x00\x00' * samples
    return (b'\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00isommp42' + body)


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'TingbenGateway/1.0'
    # 异步 job 的音频走 GET，没有 _body() 来设它；类属性兜底，
    # 并在提交 job 时把真实忽略数存进 job，取音频时回填（见 do_GET）。
    _ignored = 0

    def log_message(self, fmt, *args):
        # 隐私口径：正文绝不落日志，只留方法与路径。
        sys.stderr.write('[gateway] %s %s\n' % (self.command, self.path.split('?')[0]))

    # BaseHTTPRequestHandler 默认错误页是 HTML —— 正是下载器那个坑。
    # 覆盖掉，所有错误一律 problem+json。
    def send_error(self, code, message=None, explain=None):
        self._problem(code, 'internal_error', message or '请求没走通')

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', C['cors']['allow_origin'])
        self.send_header('Access-Control-Allow-Methods', C['cors']['allow_methods'])
        self.send_header('Access-Control-Allow-Headers', C['cors']['allow_headers'])
        self.send_header('Access-Control-Max-Age', C['cors']['max_age'])

    def _send(self, code, ctype, body, extra=None):
        if isinstance(body, str):
            body = body.encode('utf-8')
        SEEN_TYPES.append((code, self.path.split('?')[0], ctype))
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self._cors()
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _json(self, code, obj, extra=None):
        self._send(code, 'application/json; charset=utf-8',
                   json.dumps(obj, ensure_ascii=False), extra)

    def _problem(self, code, errcode, title, extra=None):
        self._send(code, C['error_type'], json.dumps({
            'type': 'about:blank', 'title': title, 'status': code,
            'code': errcode, 'detail': title}, ensure_ascii=False), extra)

    def _audio(self, data):
        self._send(200, 'audio/mp4', data, {
            'Content-Disposition': 'attachment; filename="tingben.m4a"',
            'X-Tingben-Preset': 'device-local',
            'X-Tingben-Format': 'm4a',
            'X-Tingben-Ignored': str(self._ignored),
        })

    def _auth_ok(self):
        return self.headers.get('Authorization') == 'Bearer ' + TOKEN

    def _body(self):
        n = int(self.headers.get('Content-Length') or 0)
        raw = self.rfile.read(n) if n else b''
        try:
            o = json.loads(raw.decode('utf-8'))
        except Exception:
            return None, 0
        if not isinstance(o, dict):
            return None, 0
        # 音色只读本机：调用方传来的音色字段一律静默丢弃，不回显值。
        self._ignored = sum(1 for k in o if k in C['ignored_request_fields'])
        return o, len(str(o.get('text', '')))

    def do_OPTIONS(self):
        self._send(204, 'no-body', b'')

    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/':
            self._send(200, 'text/html; charset=utf-8', INDEX_HTML)
            return
        if not self._auth_ok():
            self._problem(401, 'unauthorized', '先在网关面板复制 Token')
            return
        if path == '/v1/health':
            self._json(200, {'ok': True, 'engine': ENGINE_OK['v'],
                             'busy': BUSY.locked(), 'version': '1.1.48-gateway',
                             'preset': 'device-local', 'concurrency': 1})
            return
        if path == '/v1/openapi.json':
            self._json(200, {'openapi': '3.0.3',
                             'info': {'title': '听本网关', 'version': '1'},
                             'paths': {e['path']: {e['method'].lower(): {
                                 'responses': {str(e['status']): {'description': e['type']}}}
                             } for e in C['endpoints']}})
            return
        if path.startswith('/v1/jobs/'):
            tail = path[len('/v1/jobs/'):]
            jid, _, sub = tail.partition('/')
            j = JOBS.get(jid)
            if not j:
                self._problem(404, 'not_found', '这条任务不存在或已过期')
                return
            if sub == 'audio':
                if j['state'] != 'succeeded':
                    self._problem(409, 'job_not_ready', '还在合成，等一会儿再取')
                else:
                    self._ignored = j.get('ignored', 0)
                    self._audio(j['audio'])
                return
            if sub:
                self._problem(404, 'not_found', '没有这个子路径')
                return
            extra = {'Retry-After': '2'} if j['state'] in ('queued', 'running') else {}
            self._json(200, {k: j[k] for k in
                             ('id', 'state', 'progress', 'stage', 'chars')}, extra)
            return
        if path == '/v1/speak':
            self._problem(405, 'method_not_allowed', '这个端点要用 POST')
            return
        self._problem(404, 'not_found', '没有这个地址')

    def do_POST(self):
        path = self.path.split('?')[0]
        if path not in ('/v1/speak', '/v1/jobs'):
            self._problem(404, 'not_found', '没有这个地址')
            return
        if not self._auth_ok():
            self._problem(401, 'unauthorized', '先在网关面板复制 Token')
            return
        o, _ = self._body()
        if o is None:
            self._problem(400, 'empty_text', '请求体不是 JSON 对象')
            return
        text = o.get('text')
        if not isinstance(text, str) or not text.strip():
            self._problem(400, 'empty_text', '先给一段要念的文字')
            return
        chars = len(text)
        self.log_message('chars=%d', chars)
        # R375 二次精读发现的冲突：契约原写 2000 字，但现成合成真源
        # ClipSynth.MAX_CHARS = 1500，异步 job 同样复用它 —— 两边必须同限。
        if chars > C['max_chars']:
            self._problem(413, 'text_too_long',
                          '最多 %d 字（= ClipSynth.MAX_CHARS），先少贴一点' % C['max_chars'])
            return
        if not ENGINE_OK['v']:
            self._problem(503, 'engine_unavailable', '模型缺件或正在做整本书，稍后再试')
            return
        if not BUSY.acquire(blocking=False):
            self._problem(429, 'busy', '一次只合成一句，稍后重试',
                          {'Retry-After': '2'})
            return
        try:
            delay = 0.0
            if 'delay=' in self.path:
                try:
                    delay = float(self.path.split('delay=')[1].split('&')[0])
                except Exception:
                    delay = 0.0
            if path == '/v1/jobs':
                jid = hashlib.sha256(('%f' % time.time()).encode()).hexdigest()[:12]
                JOBS[jid] = {'id': jid, 'state': 'queued', 'progress': 0.0,
                             'stage': 'synthesize', 'chars': chars, 'audio': b'',
                             'ignored': self._ignored}
                def run():
                    JOBS[jid]['state'] = 'running'
                    time.sleep(max(delay, 0.35))
                    JOBS[jid].update(state='succeeded', progress=1.0,
                                     stage='verify', audio=wav_bytes())
                    BUSY.release()
                threading.Thread(target=run, daemon=True).start()
                self._json(202, {'id': jid, 'state': 'queued', 'chars': chars},
                           {'Location': '/v1/jobs/' + jid, 'Retry-After': '2'})
                return
            if delay:
                time.sleep(delay)
            self._audio(wav_bytes())
        except Exception as ex:
            BUSY.release()
            self._problem(503, 'engine_unavailable', '合成没成：%s' % type(ex).__name__)
        else:
            if path == '/v1/speak':
                BUSY.release()


INDEX_HTML = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<title>听本 · 语音网关</title><body style="font:15px/1.6 system-ui;padding:24px;max-width:640px">
<h2>听本语音网关</h2><p>音色用的是这台手机上设好的预设，这里只填文字。</p>
<textarea id="t" rows="4" style="width:100%">你好，这是一段测试文字。</textarea>
<p>Token <input id="k" style="width:60%"></p>
<button onclick="go()">合成并播放</button> <a id="dl" download="tingben.m4a">下载</a>
<audio id="a" controls></audio><pre id="s"></pre><script>
function go(){fetch('/v1/speak',{method:'POST',headers:{'Content-Type':'application/json',
 'Authorization':'Bearer '+document.getElementById('k').value},
 body:JSON.stringify({text:document.getElementById('t').value})})
 .then(async r=>{if(!r.ok){document.getElementById('s').textContent=
  (await r.json()).title;return null}return r.blob()})
 .then(b=>{if(!b)return;const u=URL.createObjectURL(b);
  document.getElementById('a').src=u;document.getElementById('dl').href=u;
  document.getElementById('a').play();document.getElementById('s').textContent='好了'})}
</script></body></html>'''


def req(port, method, path, body=None, token=True, timeout=10):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=timeout)
    headers = {}
    if token:
        headers['Authorization'] = 'Bearer ' + TOKEN
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode('utf-8')
        headers['Content-Type'] = 'application/json'
    conn.request(method, path, payload, headers)
    r = conn.getresponse()
    data = r.read()
    out = (r.status, dict((k.lower(), v) for k, v in r.getheaders()), data)
    conn.close()
    return out


def main():
    srv = ThreadingHTTPServer(('127.0.0.1', 0), H)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    results = []

    def ck(name, cond, info=''):
        results.append((name, bool(cond), info))
        print(('  PASS  ' if cond else '  FAIL  ') + name + (('  → ' + str(info)) if info else ''))

    try:
        s, h, b = req(port, 'GET', '/v1/health')
        ck('01 health 200 + json', s == 200 and 'application/json' in h.get('content-type', ''),
           'status=%s' % s)
        ck('02 health 报的是本机预设', json.loads(b).get('preset') == 'device-local')

        s, h, b = req(port, 'GET', '/v1/openapi.json')
        paths = json.loads(b).get('paths', {})
        ck('03 openapi 含全部端点', s == 200 and len(paths) == len(C['endpoints']),
           'paths=%d' % len(paths))

        s, h, b = req(port, 'GET', '/')
        ck('04 内置网页是唯一合法 HTML', s == 200 and 'text/html' in h.get('content-type', ''))

        s, h, b = req(port, 'OPTIONS', '/v1/speak')
        ck('05 OPTIONS 204 + CORS', s == 204 and
           h.get('access-control-allow-origin') == '*' and
           'POST' in h.get('access-control-allow-methods', ''), 'status=%s' % s)

        s, h, b = req(port, 'POST', '/v1/speak', {'text': '你好'}, token=False)
        ck('06 无 Token 401 且是 problem+json',
           s == 401 and 'problem+json' in h.get('content-type', '') and
           'text/html' not in h.get('content-type', ''), 'type=%s' % h.get('content-type'))

        s, h, b = req(port, 'POST', '/v1/speak', {'text': '你好，这是一段测试文字。'})
        ck('07 speak 200 audio/mp4（压缩后）', s == 200 and h.get('content-type') == 'audio/mp4',
           'type=%s' % h.get('content-type'))
        ck('08 音频体是压缩后的 MP4（ftyp）', b[4:8] == b'ftyp', 'bytes=%d' % len(b))
        ck('09 带下载头且标明本机预设',
           'attachment' in h.get('content-disposition', '') and
           h.get('x-tingben-preset') == 'device-local')

        s, h, b = req(port, 'POST', '/v1/speak',
                      {'text': '音色别被我带偏',
                       'voice': 'Aiden', 'speaker': 'Serena', 'speed': 2.0,
                       'seed': 999, 'temperature': 0.1, 'mode': 'base'})
        ck('10 音色字段静默丢弃、仍用本机预设',
           s == 200 and h.get('x-tingben-preset') == 'device-local' and
           int(h.get('x-tingben-ignored', '0')) >= 6,
           'ignored=%s' % h.get('x-tingben-ignored'))
        ck('11 响应不回显任何音色值', b'AIden' not in b and b'Serena' not in b)

        for name, body in [('12 空文字 400', {'text': ''}),
                           ('13 text 非字符串 400', {'text': 123})]:
            s, h, b = req(port, 'POST', '/v1/speak', body)
            ck(name, s == 400 and 'problem+json' in h.get('content-type', ''),
               'status=%s' % s)

        s, h, b = req(port, 'POST', '/v1/speak', {'text': '长' * 1501})
        ck('14 超 1500 字 413 且说清上限',
           s == 413 and '1500' in b.decode('utf-8', 'ignore'), 'status=%s' % s)
        s, h, b = req(port, 'POST', '/v1/jobs', {'text': '长' * 1501})
        ck('14b 异步 job 也守同一 1500 字上限',
           s == 413 and 'problem+json' in h.get('content-type', ''), 'status=%s' % s)
        s, h, b = req(port, 'POST', '/v1/speak', {'text': '长' * 1500})
        ck('14c 恰好 1500 字仍能合成（边界不误杀）', s == 200, 'status=%s' % s)

        s, h, b = req(port, 'GET', '/v1/nope')
        ck('15 未知路径 404 不是 HTML',
           s == 404 and 'problem+json' in h.get('content-type', ''))
        s, h, b = req(port, 'GET', '/v1/speak')
        ck('16 方法不对 405', s == 405, 'status=%s' % s)

        ENGINE_OK['v'] = False
        s, h, b = req(port, 'POST', '/v1/speak', {'text': '模型没装好'})
        ck('17 引擎不可用 503 JSON', s == 503 and 'problem+json' in h.get('content-type', ''))
        ENGINE_OK['v'] = True

        # 并发锁 1：一个慢请求占住，另一个必须 429。
        box = {}
        def slow():
            box['slow'] = req(port, 'POST', '/v1/speak?delay=0.6', {'text': '慢一点'}, timeout=15)
        t = threading.Thread(target=slow)
        t.start()
        time.sleep(0.15)
        s2, h2, b2 = req(port, 'POST', '/v1/speak', {'text': '同时来一句'})
        t.join()
        ck('18 并发第二个 429 带 Retry-After',
           s2 == 429 and h2.get('retry-after') == '2', 'status=%s' % s2)
        ck('19 慢请求自己仍成功', box['slow'][0] == 200, 'status=%s' % box['slow'][0])

        s, h, b = req(port, 'POST', '/v1/jobs', {'text': '这是一段要走异步的文字'})
        jid = json.loads(b).get('id')
        ck('20 jobs 202 + Location', s == 202 and
           h.get('location') == '/v1/jobs/' + str(jid), 'status=%s' % s)

        s, h, b = req(port, 'GET', '/v1/jobs/' + str(jid))
        ck('21 轮询带 Retry-After 且状态可见',
           s == 200 and json.loads(b).get('state') in ('queued', 'running', 'succeeded')
           and (h.get('retry-after') == '2' or json.loads(b).get('state') == 'succeeded'),
           'state=%s' % json.loads(b).get('state'))

        s, h, b = req(port, 'GET', '/v1/jobs/' + str(jid) + '/audio')
        ck('22 未完成取音频 409 JSON',
           s == 409 and 'problem+json' in h.get('content-type', ''), 'status=%s' % s)

        time.sleep(0.6)
        s, h, b = req(port, 'GET', '/v1/jobs/' + str(jid) + '/audio')
        ck('23 完成后取音频 200 压缩 M4A',
           s == 200 and h.get('content-type') == 'audio/mp4' and b[4:8] == b'ftyp',
           'status=%s' % s)

        s, h, b = req(port, 'GET', '/v1/jobs/not-exist')
        ck('24 不存在的 job 404 JSON',
           s == 404 and 'problem+json' in h.get('content-type', ''))

        bad = [(c, p, t) for (c, p, t) in SEEN_TYPES
               if p != '/' and 'text/html' in t]
        ck('25 全局：除内置网页外零 HTML 响应', not bad, 'html响应=%d' % len(bad))

        ctypes = set(t for (_, _, t) in SEEN_TYPES)
        ck('26 全局：错误体只用 problem+json',
           all('problem+json' in t or t in ('audio/mp4', 'application/json; charset=utf-8',
                                            'text/html; charset=utf-8', 'no-body')
               for t in ctypes), 'types=%d' % len(ctypes))
    finally:
        srv.shutdown()
        srv.server_close()

    bad = [n for n, ok, _ in results if not ok]
    print('\n汇总 %d/%d 通过' % (len(results) - len(bad), len(results)))
    if bad:
        print('未通过：', '、'.join(bad))
        sys.exit(1)
    print('契约模拟全绿：网关逻辑跑得通（纯 loopback，未连云端 / 未碰真机）')


if __name__ == '__main__':
    main()
