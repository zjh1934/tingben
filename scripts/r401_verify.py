#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R401 · 真机续跑截图 UI 修复 + 本机合成并发仲裁守门。

真机依据：CMP_20261125230305701.jpeg / CMP_20261125230306699.jpeg（两张续跑态）。
P0 依据：整本制作中远端请求被判 RELAY_FAIL 并删 queue（丢请求）；
         中继正在合成时用户点试听/首页合成会并发进 NativeEngine 单实例。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r401_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'arbiter': APP / 'core/SynthArbiter.kt',
    'synth': APP / 'gateway/GatewaySynth.kt',
    'relay': APP / 'relay/RelayService.kt',
    'client': APP / 'relay/RelayClient.kt',
    'mixer': APP / 'MixerUi.kt',
    'input': APP / 'InputReadUi.kt',
    'hub': APP / 'ChapterHub.kt',
    'overlay': APP / 'OverlayProgress.kt',
    'sim': ROOT / 'scripts/relay_sim.py',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'core/Producer.kt': ['fun isBusy()', 'busy = true'],
    APP / 'core/ClipSynth.kt': ['fun synthesize(', 'Producer.isBusy()'],
    APP / 'relay/RelayService.kt': ['RELAY_DEL', 'RELAY_SYNTH', 'RELAY_PUT_DONE',
                                   'SRC_RELAY', 'RequestArchive.add'],
    APP / 'gateway/GatewayService.kt': ['ACTION_REBIND', 'GatewaySynth', 'START_NOT_STICKY'],
    APP / 'core/RequestArchive.kt': ['SRC_RELAY', 'SRC_GATEWAY'],
    APP / 'diag/EventLog.kt': ['LOG_RETENTION', '24'],
}

def sources():
    d = {k: v.read_text(encoding='utf-8') for k, v in FILES.items() if v.exists()}
    for p in CHAIN:
        if p.exists():
            d['chain:' + str(p.relative_to(ROOT))] = p.read_text(encoding='utf-8')
    return d

def code(t):
    return '\n'.join(l for l in t.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/', '#')))

def live_counts():
    out = {}
    for p in sorted(APP.rglob('*.kt')):
        t = p.read_text(encoding='utf-8', errors='replace')
        out[str(p.relative_to(APP))] = {
            'Button': len(re.findall(r'\bButton\(', t)),
            'OutlinedButton': len(re.findall(r'\bOutlinedButton\(', t)),
            'TextButton': len(re.findall(r'\bTextButton\(', t)),
            'onClick': len(re.findall(r'\bonClick\s*=', t)),
            'clickable': len(re.findall(r'\.clickable\s*\(', t)),
        }
    return out

def check(s):
    e = []
    for k in FILES:
        if k not in s:
            e.append('缺文件: ' + k)
    if e:
        return e
    ci = s.get('ci', '')
    # R402 · 发版链路防呆：Release 资产真落地才算发版完成。
    for tok in ['Release 资产确实落地（防软失败）',
                'assets[] | select(.name == "tingben-v',
                '没带上 APK 资产（发布动作软失败）']:
        if tok not in ci:
            e.append('CI 缺 Release 资产落地校验: ' + tok[:40])
    a, g, r, cl, m, i, h, ov, sim = (s['arbiter'], s['synth'], s['relay'],
                                     s['client'], s['mixer'], s['input'],
                                     s['hub'], s['overlay'], s['sim'])

    # ── 甲 · 并发仲裁：六个入口各有归属 ──────────────────────────────
    for tok in ['OWNER_CLIP', 'OWNER_MIXER', 'OWNER_GATEWAY', 'OWNER_RELAY',
                'AtomicReference<String?>', 'fun producerBusy()',
                'fun tryAcquire(', 'fun release(', 'fun awaitAndAcquire(',
                'fun busyLabel()']:
        if tok not in a:
            e.append('SynthArbiter 缺: ' + tok[:40])
    # R405 · 优先级反过来：书跑时远端等段边界让路，只有等不到才 busy=true。
    # 硬约束不变：绝不能落回 Speak(ok=false, busy=false) 的失败删请求路径。
    for tok in ['fun longTaskBusy()', 'SynthArbiter.awaitProducerYield()',
                'Speak(false, true, null, "本机正在收尾这一小段，稍后优先处理远端请求")',
                'SynthArbiter.tryAcquire(who)', 'SynthArbiter.release(who)']:
        if tok not in g:
            e.append('GatewaySynth 仲裁缺: ' + tok[:48])
    if 'OWNER_RELAY' not in g:
        e.append('中继没有独立占用者（渠道又混了）')
    # speak 与 submit 两条路径都必须占锁；只守一处会让异步 job 绕过仲裁。
    if g.count('SynthArbiter.tryAcquire(who)') < 2:
        e.append('单一仲裁没同时覆盖同步 speak 与异步 submit')
    # 中继：忙时排队、不删、不判失败、不震动
    for tok in ['client.putBusy(id, busyStatus(', '"state", "busy"',
                'retry_after_ms', 'RELAY_BUSY', 'RELAY_DEFER',
                'RELAY_DEFER_DONE', 'Thread.sleep(RelayContract.MAX_BACKOFF_MS)',
                'continue']:
        if tok not in r:
            e.append('RelayService 忙态缺: ' + tok[:48])
    # 忙分支必须在 deleteQueue 之前 continue，且不得 putFailed
    busy = r[r.find('if (r.busy)'):]
    if 'continue' not in busy[:busy.find('setState("收到请求')]:
        e.append('忙分支没有保留请求直接 continue')
    if 'putFailed' in busy[:busy.find('setState("收到请求')]:
        e.append('忙时又走 putFailed（丢请求回潮）')
    if 'RelayHaptics.start(this)' in busy[:busy.find('setState("收到请求')]:
        e.append('忙时误触发开始震动')
    if 'fun putBusy(' not in cl or 'relay: 排队状态' not in cl:
        e.append('RelayClient 没写 busy 状态回写')
    # 本地两个入口：等远端收尾 + finally 释放（不掐断、不并发）
    for name, t, owner in [('MixerUi', m, 'OWNER_MIXER'), ('InputReadUi', i, 'OWNER_CLIP')]:
        if 'SynthArbiter.awaitAndAcquire(SynthArbiter.' + owner + ')' not in t:
            e.append(name + ' 没走单一仲裁')
        if 'SynthArbiter.release(SynthArbiter.' + owner + ')' not in t:
            e.append(name + ' 没在 finally 释放锁')
    if '不提前 return' not in i:
        e.append('InputReadUi 丢了 busy-- 守恒说明')
    if 'producerBusy()' not in m:
        e.append('调音台试听没统一读长任务')
    # 契约模拟器：忙态 7 条用例
    for tok in ["'state': 'busy'", "'retry_after_ms': 60000",
                "24 忙时回 state=busy", "25 忙时请求文件仍在",
                "28 忙完自动补做成功"]:
        if tok not in sim:
            e.append('relay_sim 忙态契约缺: ' + tok[:44])

    # ── 乙 · 真机截图 UI 七项 ────────────────────────────────────────
    for tok in ['已固化章音频 · 可听', '整本制作 · 全部段',
                '(finishedInScope.toFloat() / scopeSize.toFloat()).coerceIn(0f, 1f)',
                'height(8.dp)', 'height(6.dp)']:
        if tok not in h:
            e.append('ChapterHub 进度口径没改准: ' + tok[:48])
    if 'val chapterPctNow = if (chNow != null) chPct' in h:
        e.append('「已出章音频」又拿当前章进度冒充（96%↔14% 回潮）')
    if 'padding(horizontal = 14.dp)\n                .height(3.dp).clip(RoundedCornerShape(50))' not in h:
        e.append('通栏绿线没收进卡片内')
    if 'listOf("已生成" to HubGood' not in h or '"已删" to HubCard' in h:
        e.append('段网格图例仍是假图例')
    for tok in ['第 $chapterNo 章 · 已生成', '已暂停 · 等续做',
                'chapterNo = r.index + 1, running = bookRunning',
                'val busyHere = (bookRunning && r.index == liveChapter)',
                '已暂停 · " else ""']:
        if tok not in h:
            e.append('状态互斥/章号归属缺: ' + tok[:48])
    if 'bottom = 208.dp' not in h:
        e.append('底部播放器避让没做（最后一章仍被压）')
    if 'canListen && !bookRunning' not in h or '做完再听' not in h:
        e.append('制作中「按顺序播放」没互斥')
    if '"断点 %d:%02d".format(' not in h:
        e.append('断点时间没标来源')
    if '0.40f' not in ov:
        e.append('悬浮球默认落点没避开底部播放器')

    # ── 丙 · 守恒 ────────────────────────────────────────────────────
    for p, toks in CHAIN.items():
        t = s.get('chain:' + str(p.relative_to(ROOT)))
        if t is None:
            e.append('链路文件消失: ' + p.name); continue
        for k in toks:
            if k not in t:
                e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try:
        bl = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('基线不可读: %s' % ex); bl = {}
    live = live_counts()
    for f, cnt in bl.items():
        if f not in live:
            e.append('功能守恒: 文件消失 ' + f); continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    return e

BAD = [
    ('丢请求回潮', lambda d: d.update(relay=d['relay'].replace(
        'client.putBusy(id, busyStatus(', 'client.putFailed(id, failStatus(', 1))),
    ('忙时删 queue', lambda d: d.update(relay=d['relay'].replace(
        'Thread.sleep(RelayContract.MAX_BACKOFF_MS)',
        'client.deleteQueue(item.name, reqSha)', 1))),
    ('等让路超时被改成判失败', lambda d: d.update(synth=d['synth'].replace(
        'Speak(false, true, null, "本机正在收尾这一小段，稍后优先处理远端请求")',
        'Speak(false, false, null, "本机正忙")', 1))),
    ('单一仲裁被摘', lambda d: d.update(synth=d['synth'].replace(
        'SynthArbiter.tryAcquire(who)', 'true'))),
    ('本地入口并发', lambda d: d.update(mixer=d['mixer'].replace(
        'SynthArbiter.awaitAndAcquire(SynthArbiter.OWNER_MIXER)', 'true', 1))),
    ('本地锁不释放', lambda d: d.update(input=d['input'].replace(
        'SynthArbiter.release(SynthArbiter.OWNER_CLIP)', '', 1))),
    ('排队态回写被摘', lambda d: d.update(client=d['client'].replace(
        'fun putBusy(', 'fun putBusyGone(', 1))),
    ('忙态契约被摘', lambda d: d.update(sim=d['sim'].replace(
        "'state': 'busy'", "'state': 'done'", 1))),
    ('进度口径回潮', lambda d: d.update(hub=d['hub'].replace(
        '(finishedInScope.toFloat() / scopeSize.toFloat()).coerceIn(0f, 1f)',
        'if (chNow != null) chPct else 0f', 1))),
    ('通栏绿线回潮', lambda d: d.update(hub=d['hub'].replace(
        'padding(horizontal = 14.dp)\n                .height(3.dp).clip(RoundedCornerShape(50))',
        'height(3.dp)', 1))),
    ('假图例回潮', lambda d: d.update(hub=d['hub'].replace(
        'listOf("已生成" to HubGood', 'listOf("已生成" to HubPrimary', 1))),
    ('暂停态说在跑', lambda d: d.update(hub=d['hub'].replace(
        'if (!running) "已暂停 · 等续做"', 'if (true) "正在往下做"', 1))),
    ('底部避让被摘', lambda d: d.update(hub=d['hub'].replace('bottom = 208.dp', 'bottom = 100.dp', 1))),
    ('制作中又放行', lambda d: d.update(hub=d['hub'].replace(
        'canListen && !bookRunning,', 'canListen,', 1))),
    ('章号归属被摘', lambda d: d.update(hub=d['hub'].replace(
        '第 $chapterNo 章 · 已生成', '已生成', 1))),
    ('链路符号', lambda d: d.update(**{'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('资产校验被摘', lambda d: d.update(ci=d['ci'].replace(
        'Release 资产确实落地（防软失败）', 'Release 发布完成', 1))),
    ('功能守恒', lambda d: d.update(baseline='{}')),
]

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d):
            ok += 1
        else:
            print('  反向注入漏放:', name)
    print('R401 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e:
            print('RED:', x)
        print('R401: %d 处红' % len(e))
        return 1
    print('R401 PASS · 并发六撞法 / 截图 UI 七项 / 链路守恒 / 功能守恒')
    print('  功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
