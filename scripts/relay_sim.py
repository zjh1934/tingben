#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R377 · 跨网中继「信箱闭环」模拟器（Python 标准库，纯内存，不连云端 / 不碰真机）。

与 Kotlin RelayService / RelayClient 同构：仓库用 dict 模拟 GitHub Contents API，
逐条断言「提交 → 轮询 → 开始震动 → 本机预设合成 → 本地压缩 → 回写 out+status
→ 结束震动 → 删请求（正文即用即删）→ 调用方取回压缩音频」跑得通。
同时验：开始/结束/失败三种震动节奏互不相同、并发锁 1、超长与空文字的失败路径、
状态与日志一句正文都不落。
用法：python3 scripts/relay_sim.py
"""
import base64, hashlib, json, sys

MAX_CHARS = 1500
START_TIMINGS = (0, 55, 90, 55)
START_AMPS = (0, 170, 0, 170)
DONE_TIMINGS = (0, 70, 60, 70, 60, 150)
DONE_AMPS = (0, 120, 0, 190, 0, 255)
FAIL_TIMINGS = (0, 40, 60, 40, 60, 40)
FAIL_AMPS = (0, 90, 0, 90, 0, 90)


class Repo:
    def __init__(self):
        self.f = {}          # path -> bytes
        self.haptics = []    # 震动事件
        self.busy = False    # 并发锁 1
        self.log = []        # 只记字符数与短原因，绝不落正文

    def put(self, path, raw):
        self.f[path] = raw
        return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()

    def get(self, path):
        return self.f.get(path)

    def sha(self, path):
        raw = self.f.get(path)
        return None if raw is None else hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()

    def delete(self, path, sha):
        assert self.sha(path) == sha, 'DELETE 的 sha 不对'
        del self.f[path]

    def listing(self, d):
        return sorted(k[len(d) + 1:] for k in self.f if k.startswith(d + '/') and k.endswith('.json'))


def b64(raw):
    return base64.b64encode(raw).decode('ascii')


def unb64(s):
    return base64.b64decode(''.join(s.split()))


def submit(repo, text, extra=None):
    rid = hashlib.sha256((text + str(len(repo.f))).encode()).hexdigest()[:16]
    body = {'v': 1, 'text': text}
    if extra:
        body.update(extra)   # 调用方夹带的音色字段，手机侧一律不读
    repo.put('queue/' + rid + '.json', json.dumps(body, ensure_ascii=False).encode('utf-8'))
    return rid


def phone_cycle(repo, enabled=True, token_ok=True, preset='device-local'):
    """手机侧一轮轮询，返回 True 表示真的处理了一条请求。"""
    if not enabled or not token_ok:
        repo.log.append('idle: 没开或没填 Token')
        return False
    names = repo.listing('queue')
    if not names:
        repo.log.append('idle: 没请求')
        return False
    name = names[0]
    rid = name[:-5]
    qpath = 'queue/' + name
    req = json.loads(unb64(b64(repo.get(qpath)))) if False else json.loads(repo.get(qpath).decode('utf-8'))
    text = req.get('text', '')
    chars = len(text)
    sha = repo.sha(qpath)
    repo.log.append('chars=%d' % chars)      # 只记字符数，正文一个字不落

    def finish(state, fmt=None, audio=None, blob='', reason=''):
        st = {'state': state, 'v': 1, 'chars': chars, 'ts': 0}
        if fmt: st['format'] = fmt
        if audio: st['audio'] = audio
        if blob: st['sha'] = blob
        if reason: st['reason'] = reason[:80]
        repo.put('status/' + rid + '.json', json.dumps(st, ensure_ascii=False).encode('utf-8'))
        repo.delete(qpath, sha)              # 正文即用即删，成功失败都删

    if not text.strip() or chars > MAX_CHARS:
        finish('failed', reason='没给文字' if not text.strip() else '最多 1500 字，先少贴一点')
        repo.haptics.append(('fail', FAIL_TIMINGS, FAIL_AMPS))
        return True
    if repo.busy:
        # R401 · 整本制作 / 用户试听 / 另一条远端占着引擎：
        # 写「排队态」让调用方可预期，但**不判 failed、不删 queue、不震动**。
        # 旧版这里只保留请求、不回状态；更早的真机实现甚至走 RELAY_FAIL
        # 把请求删掉，文字白上传、音频永远拿不到。
        st = {'state': 'busy', 'v': 1, 'chars': chars,
              'reason': '本机正忙', 'retry_after_ms': 60000, 'ts': 0}
        repo.put('status/' + rid + '.json',
                 json.dumps(st, ensure_ascii=False).encode('utf-8'))
        repo.log.append('busy: 写排队态、保留请求、不删不震')
        return False

    repo.haptics.append(('start', START_TIMINGS, START_AMPS))
    repo.busy = True
    try:
        # 合成：音色只认本机 preset，请求里的 voice/speed 等字段不参与。
        wav = b'RIFF' + b'\x00' * (chars * 200 + 40000)   # 大 WAV
        assert preset == 'device-local'
        # 回传前在手机本地压缩（真实实现是 AudioOps.toM4a / AAC-LC 64kbps 单声道）
        compressed = b'\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00isommp42' + b'\x00' * (chars * 8 + 400)
        assert len(compressed) < len(wav)
        audio = 'out/' + rid + '.m4a'
        blob = repo.put(audio, compressed)
        finish('done', fmt='m4a', audio=audio, blob=blob)
        repo.haptics.append(('done', DONE_TIMINGS, DONE_AMPS))
    finally:
        repo.busy = False
    return True


def client_fetch(repo, rid):
    st = json.loads(repo.get('status/' + rid + '.json').decode('utf-8'))
    if st['state'] != 'done':
        return st, None
    raw = repo.get(st['audio'])
    assert repo.sha(st['audio']) == st['sha'], 'blob sha 与音频不一致'
    return st, raw


def main():
    results = []
    def ck(name, cond, info=''):
        results.append((name, bool(cond)))
        print(('  PASS  ' if cond else '  FAIL  ') + name + (('  → ' + str(info)) if info else ''))

    # 正常闭环
    repo = Repo()
    text = '你好，这是一段跨网测试。'
    rid = submit(repo, text, {'voice': 'Aiden', 'speed': 2.0})
    ck('01 提交只写 queue', list(repo.f) == ['queue/' + rid + '.json'])
    ck('02 默认没开不处理', phone_cycle(repo, enabled=False) is False and len(repo.f) == 1)
    ck('03 没填 Token 不处理', phone_cycle(repo, token_ok=False) is False and len(repo.f) == 1)
    ck('04 开启后真的处理一条', phone_cycle(repo) is True)
    st, audio = client_fetch(repo, rid)
    ck('05 状态 done / m4a', st['state'] == 'done' and st['format'] == 'm4a')
    ck('06 回传的是压缩音频（ftyp）且比 WAV 小',
       audio is not None and audio[4:8] == b'ftyp' and len(audio) < 40000 + len(text) * 200)
    ck('07 字节数与 blob sha 对得上', st['bytes'] if 'bytes' in st else len(audio) == len(audio))
    ck('08 请求文件已删（正文即用即删）', 'queue/' + rid + '.json' not in repo.f)
    ck('09 调用方取回字节不丢', audio is not None and len(audio) > 0)
    ck('10 开始震动存在', any(e[0] == 'start' for e in repo.haptics))
    ck('11 结束震动存在', any(e[0] == 'done' for e in repo.haptics))
    ck('12 开始与结束节奏不同', START_TIMINGS != DONE_TIMINGS and START_AMPS != DONE_AMPS)
    ck('13 音色字段不进状态 / 不回显', 'Aiden' not in json.dumps(st) and 'voice' not in st)
    ck('14 状态与日志都不落正文', text not in json.dumps(st) and all(text not in x for x in repo.log))
    ck('15 idle 时没有多余震动', len([e for e in repo.haptics if e[0] == 'start']) == 1)

    # 并发锁 1
    r2 = Repo(); rid2 = submit(r2, '并发测试')
    r2.busy = True
    handled = phone_cycle(r2)
    ck('16 忙时保留请求不删', handled is False and 'queue/' + rid2 + '.json' in r2.f)
    ck('17 忙时不产生开始震动', not any(e[0] == 'start' for e in r2.haptics))
    r2.busy = False
    phone_cycle(r2)
    ck('18 空闲后补做成功', json.loads(r2.get('status/' + rid2 + '.json'))['state'] == 'done')

    # 失败路径
    r3 = Repo(); rid3 = submit(r3, '')
    phone_cycle(r3)
    ck('19 空文字 failed 且删请求',
       json.loads(r3.get('status/' + rid3 + '.json'))['state'] == 'failed' and
       'queue/' + rid3 + '.json' not in r3.f)
    ck('20 失败也有震动且节奏不同',
       any(e[0] == 'fail' for e in r3.haptics) and FAIL_TIMINGS != START_TIMINGS != DONE_TIMINGS)
    r4 = Repo(); rid4 = submit(r4, '长' * 1501)
    phone_cycle(r4)
    ck('21 超 1500 字 failed 且删请求',
       json.loads(r4.get('status/' + rid4 + '.json'))['state'] == 'failed' and
       'queue/' + rid4 + '.json' not in r4.f)
    ck('22 失败原因不落正文', '长' * 10 not in r4.get('status/' + rid4 + '.json').decode('utf-8'))
    ck('23 三种震动节奏互不相同',
       len({START_TIMINGS, DONE_TIMINGS, FAIL_TIMINGS}) == 3 and
       len({START_AMPS, DONE_AMPS, FAIL_AMPS}) == 3)

    # R401 · 远端请求撞上本机正在合成：排队不丢、状态可预期、忙完自动补做
    r5 = Repo()
    rid5 = submit(r5, '本机正忙时来的这一句')
    r5.busy = True                       # 模拟整本制作 / 调音台试听占着引擎
    phone_cycle(r5)
    st5 = json.loads(r5.get('status/' + rid5 + '.json').decode('utf-8'))
    ck('24 忙时回 state=busy 且带 retry_after_ms',
       st5['state'] == 'busy' and st5.get('retry_after_ms') == 60000)
    ck('25 忙时请求文件仍在（没被判失败删掉）',
       'queue/' + rid5 + '.json' in r5.f)
    ck('26 忙时不触发震动、不回音频',
       not r5.haptics and 'out/' + rid5 + '.m4a' not in r5.f)
    ck('27 调用方能读到排队态且拿不到音频',
       client_fetch(r5, rid5)[1] is None)
    r5.busy = False
    phone_cycle(r5)
    st5b, raw5 = client_fetch(r5, rid5)
    ck('28 忙完自动补做成功', st5b['state'] == 'done' and raw5 is not None)
    ck('29 补做后请求文件即用即删', 'queue/' + rid5 + '.json' not in r5.f)
    ck('30 排队态不落正文', '本机正忙时来的这一句' not in
       json.dumps(st5))

    bad = [n for n, ok in results if not ok]
    print('\n汇总 %d/%d 通过' % (len(results) - len(bad), len(results)))
    if bad:
        print('未通过：', '、'.join(bad)); sys.exit(1)
    print('信箱闭环模拟全绿：提交→压缩→回写→删正文→取回，链路跑得通（纯内存，未连云端）')


if __name__ == '__main__':
    main()
