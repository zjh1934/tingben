#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R400 · 1.1.55 真机诊断日志对照后的七个修复守门。

日志实证（tingben_diag_20261125_220454.md，2069 行）：
  ① 冷启动 + MY_PACKAGE_REPLACED 相隔 0.5s 各补拉一次 → 两条 RELAY_RESUME / 两条 RELAY_ON
  ② 07:50–07:54 断网 UnknownHostException 每 5s 一行 WARN，刷 46+ 行，无退避
  ③ 用户手动关中继的 InterruptedException 被记成 WARN「轮询失败」（14:23×2、21:59×1）
  ④ OVERLAY_PCT 同帧 1 秒内重复 2~6 行
  ⑤ APP_EXIT 历史 8 条每次冷启全量重放（4 轮）
  ⑥ RELAY_ON 文案写「轮询 4000ms」，但空闲实际是 10s
  ⑦ 117 条中继请求的 MIXER_TRIAL 全写「来源=网关」（label 写死，渠道分不清）

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r400_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'contract': APP / 'relay/RelayContract.kt',
    'relay': APP / 'relay/RelayService.kt',
    'gateway': APP / 'gateway/GatewayService.kt',
    'resume': APP / 'core/ServiceResume.kt',
    'overlay': APP / 'OverlayProgress.kt',
    'eventlog': APP / 'diag/EventLog.kt',
    'synth': APP / 'gateway/GatewaySynth.kt',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'relay/RelayService.kt': ['SRC_RELAY', 'RequestArchive.add', 'START_NOT_STICKY',
                                   'RELAY_DEL', 'RELAY_SYNTH', 'RELAY_PUT_DONE'],
    APP / 'gateway/GatewayService.kt': ['ACTION_REBIND', 'GatewaySynth', 'START_NOT_STICKY'],
    APP / 'gateway/GatewaySynth.kt': ['ClipSynth.synthesize', 'AudioOps.compressForTransfer'],
    APP / 'core/RequestArchive.kt': ['SRC_RELAY', 'SRC_GATEWAY'],
    APP / 'core/ServiceResume.kt': ['RelayPrefs.configured', 'GatewayPrefs.enabled'],
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
    c, r, g, res, ov, el = (s['contract'], s['relay'], s['gateway'],
                            s['resume'], s['overlay'], s['eventlog'])
    rc, gc = code(c), code(g)

    # ① 恢复幂等：静态存活标志 + ServiceResume 跳过 + ON 只在真新起时记
    for tok in ['@Volatile private var alive = false', 'fun isRunning(): Boolean = alive',
                'val started = startRelay()', 'RELAY_ON_NOOP', 'private fun startRelay(): Boolean',
                'alive = true', 'alive = false']:
        if tok not in r:
            e.append('RelayService 幂等缺: ' + tok[:40])
    for tok in ['RelayService.isRunning()', 'RELAY_RESUME_SKIP',
                'GatewayService.isRunning()', 'GATEWAY_RESUME_SKIP']:
        if tok not in res:
            e.append('ServiceResume 跳过重复补拉缺: ' + tok[:40])
    for tok in ['fun isRunning(): Boolean = alive', 'val started = startGateway()',
                'GATEWAY_ON_NOOP', 'private fun startGateway(): Boolean', 'return ok']:
        if tok not in g:
            e.append('GatewayService 幂等缺: ' + tok[:40])
    if 'fun isRunning(): Boolean = alive' not in g.split('companion object {')[1].split('\n    }')[0]:
        e.append('GatewayService 的 isRunning 不在 companion（ServiceResume 静态调不到）')

    # ② 断网退避 + 合并记录 + 恢复归零
    for tok in ['MAX_BACKOFF_MS = 60000L']:
        if tok not in rc:
            e.append('退避上限缺: ' + tok)
    for tok in ['var errStreak = 0', 'RELAY_POLL_RECOVER', 'backoffMs(errStreak)',
                'private fun backoffMs(', 'v.coerceAtMost(RelayContract.MAX_BACKOFF_MS)',
                'phase != "put" && phase != "busy" && phase != "del"',
                '(errStreak == 1 || errStreak % 12 == 0))',
                'try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }']:
        if tok not in r:
            e.append('退避/合并记录缺: ' + tok[:48])
    if 'RELAY_POLL_ERR' not in r:
        e.append('RELAY_POLL_ERR 埋点被摘')
    # ③ 正常关停不伪装 WARN
    if 'if (!running || t is InterruptedException)' not in r:
        e.append('InterruptedException 正常关停分支被摘')
    # ④ 进度日志去重（重绘不动）
    for tok in ['lastPctLog = ""', 'if (totalNow > 0 && sig != lastPctLog)', 'lastPctLog = sig',
                'topRing?.postInvalidate()']:
        if tok not in ov:
            e.append('进度去重缺: ' + tok[:40])
    # ⑤ APP_EXIT 历史去重
    for tok in ['tingben_diag_meta', 'last_exit_ts', 'if (r.timestamp <= lastTs) continue',
                'meta.edit().putLong("last_exit_ts", maxTs).apply()']:
        if tok not in el:
            e.append('退出原因去重缺: ' + tok[:44])
    if 'getHistoricalProcessExitReasons' not in el:
        e.append('APP_EXIT 读取本身被摘')
    # ⑥ 文案改准
    if '忙 ' not in r or 'IDLE_POLL_MS' not in r:
        e.append('RELAY_ON 文案没写「忙/闲」真实间隔')
    if '轮询 " +' in r and '忙 " +' not in r:
        e.append('RELAY_ON 旧文案回潮')

    # 链路符号守恒（继 R397/R398）
    for p, toks in CHAIN.items():
        t = s.get('chain:' + str(p.relative_to(ROOT)))
        if t is None:
            e.append('链路文件消失: ' + p.name); continue
        for k in toks:
            if k not in t:
                e.append('链路符号丢失: %s -> %s' % (p.name, k))
    # ⑦ 渠道标签：中继不再被标成网关（只改埋点文案，合成链守恒）
    if 'label: String = "网关"' not in s['synth']:
        e.append('GatewaySynth 没给 label 默认值')
    if 'ClipSynth.synthesize(c, text, "gateway.wav", label, outFile = f)' not in s['synth']:
        e.append('合成链没用调用方给的 label（渠道又混了）')
    if 'GatewaySynth.speak(this, text, 0, "中继")' not in s['relay']:
        e.append('中继没显式标「中继」')
    if 'ClipSynth.synthesize(c, text, "gateway.wav", "网关", outFile = f)' in s['synth']:
        e.append('label 又写死成「网关」')

    # 功能守恒
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
    ('退避上限', lambda d: d.update(contract=d['contract'].replace('MAX_BACKOFF_MS = 60000L', 'MAX_BACKOFF_MS = 5000L', 1))),
    ('退避调用', lambda d: d.update(relay=d['relay'].replace('backoffMs(errStreak)', 'RelayContract.RETRY_MS', 1))),
    ('合并记录', lambda d: d.update(relay=d['relay'].replace('phase != "put" && phase != "busy" && phase != "del"', 'true', 1))),
    ('恢复归零', lambda d: d.update(relay=d['relay'].replace('RELAY_POLL_RECOVER', 'RELAY_POLL_NOISE', 1))),
    ('正常关停', lambda d: d.update(relay=d['relay'].replace('if (!running || t is InterruptedException) {', 'if (false) {', 1))),
    ('ON 去重', lambda d: d.update(relay=d['relay'].replace('val started = startRelay()', 'val started = true', 1))),
    ('恢复跳过', lambda d: d.update(resume=d['resume'].replace('RelayService.isRunning()', 'false', 1))),
    ('进度去重', lambda d: d.update(overlay=d['overlay'].replace('if (totalNow > 0 && sig != lastPctLog)', 'if (true)', 1))),
    ('退出原因去重', lambda d: d.update(eventlog=d['eventlog'].replace('if (r.timestamp <= lastTs) continue', '', 1))),
    ('文案回潮', lambda d: d.update(relay=d['relay'].replace('" · 忙 " +', '" · 轮询 " +', 1))),
    ('渠道标签回潮', lambda d: d.update(synth=d['synth'].replace(
        'ClipSynth.synthesize(c, text, "gateway.wav", label, outFile = f)',
        'ClipSynth.synthesize(c, text, "gateway.wav", "网关", outFile = f)', 1))),
    ('中继标签被摘', lambda d: d.update(relay=d['relay'].replace(
        'GatewaySynth.speak(this, text, 0, "中继")',
        'GatewaySynth.speak(this, text, 0)', 1))),
    ('链路符号', lambda d: d.update(**{'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline=d['baseline'].replace('"Button": 2', '"Button": 99', 1)
                                  if '"Button": 2' in d['baseline'] else '{}')),
]

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d):
            ok += 1
        else:
            print('  反向注入漏放:', name)
    print('R400 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e:
            print('RED:', x)
        print('R400: %d 处红' % len(e))
        return 1
    print('R400 PASS · 日志七项修复 / 链路守恒 / 功能守恒')
    print('  功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
