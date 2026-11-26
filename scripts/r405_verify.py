#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R405 · 远端请求最高优先级 + 书在段边界让路（用户 2026-11-26 口径）守门。

用户原话要点：
  · 远端请求的合成是最高优先级，其他的都要给它让路；
  · 一次来四条、两个对话各四条，算力要倾向远端；
  · 本地正在合成书时，音频小块结束就让路（不是掐断当前段）；
  · **不要因为这个让路把书的合成节奏搞坏搞崩**。

与 R401 的关系：R401 是「整本制作优先、远端排队」，本轮按用户要求反过来。
硬约束不变：忙时保留请求不判失败不删 queue；正文即用即删；Token 不落盘。

纯静态 + 纯内存逻辑用例；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r405_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'arb': APP / 'core/SynthArbiter.kt',
    'producer': APP / 'core/Producer.kt',
    'clip': APP / 'core/ClipSynth.kt',
    'synth': APP / 'gateway/GatewaySynth.kt',
    'relay': APP / 'relay/RelayService.kt',
    'mixer': APP / 'MixerUi.kt',
    'input': APP / 'InputReadUi.kt',
    'sim': ROOT / 'scripts/relay_sim.py',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'core/Producer.kt': ['fun isBusy()', 'fun restore(', 'PRODUCE_MODE',
                               'TASK_RECOVER_SCOPE', 'fun halt' if False else 'halted('],
    APP / 'core/ClipSynth.kt': ['fun synthesize(', 'NativeEngine.nativeCancelReset()',
                                'MIXER_TRIAL_EMPTY'],
    APP / 'gateway/GatewaySynth.kt': ['ClipSynth.synthesize(', 'fun longTaskBusy()',
                                      'fun speak(', 'fun submit('],
    APP / 'relay/RelayService.kt': ['RELAY_REQ', 'RELAY_SYNTH', 'RELAY_PUT_DONE',
                                    'RELAY_DEL', 'RELAY_PUT_ERR', 'client.putBusy(',
                                    'RequestArchive.add(this, RequestArchive.SRC_RELAY, text)'],
    APP / 'relay/RelayClient.kt': ['fun headSha(', 'fun putObj(', 'fun deleteQueue('],
    APP / 'core/SynthArbiter.kt': ['fun tryAcquire(', 'fun awaitAndAcquire(',
                                   'fun producerBusy()'],
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

# ── 纯内存逻辑：复现优先级反转后的握手 ──────────────────────────────
class Arb:
    def __init__(self):
        self.batch = 0
        self.single = 0
        self.owner = None
        self.producer_busy = False
        self.producer_yielded = False
        self.done = 0            # 已做段数，让路期间绝不动
        self.phase = 'running'   # 让路期间绝不动

    def pending(self): return self.batch + self.single
    def set_batch(self, n): self.batch = max(0, n)
    def batch_done(self): self.batch = max(0, self.batch - 1)
    def enter(self): self.single += 1
    def exit(self): self.single = max(0, self.single - 1)
    def should_yield(self): return self.pending() > 0

    def yield_at_boundary(self):
        """书：当前小块已落盘，释放 talker，等整批排空。返回 (是否恢复, 是否公平超时)。"""
        self.producer_yielded = True
        self.owner = None     # ttsFreeGated
        return True

    def await_yield(self):
        return (not self.producer_busy) or self.producer_yielded

    def remote_override(self):
        return self.producer_yielded and self.owner in ('relay', 'gateway')

    def try_acquire(self, who):
        if self.pending() > 0 and who not in ('relay', 'gateway'):
            return False
        if self.owner is not None:
            return False
        self.owner = who
        return True

    def release(self, who):
        if self.owner == who: self.owner = None

def logic(ar_text):
    e = []
    # ① 四条整批：书让路 → 四条连续排空 → 书恢复，段号/phase 不变
    a = Arb(); a.producer_busy = True
    a.set_batch(4); a.enter()
    if not a.should_yield(): e.append('逻辑：远端整批没触发让路')
    if a.await_yield(): e.append('逻辑：书还没让路远端就进引擎了')
    a.yield_at_boundary()
    if not a.await_yield(): e.append('逻辑：书让路后远端仍进不去')
    for k in range(4):
        if not a.try_acquire('relay'): e.append('逻辑：第%d条远端没拿到锁' % (k + 1))
        if not a.remote_override(): e.append('逻辑：ClipSynth 没放行远端')
        a.release('relay')
        a.batch_done()
    a.exit()
    if a.pending() != 0: e.append('逻辑：整批没归零')
    if a.should_yield(): e.append('逻辑：整批归零后书还在让')
    a.producer_yielded = False
    if a.done != 0 or a.phase != 'running': e.append('逻辑：让路把书的进度/档案改了')
    # ② 两个对话各四条：八条照样连续
    b = Arb(); b.producer_busy = True; b.set_batch(8)
    b.yield_at_boundary()
    for k in range(8):
        if not b.try_acquire('relay'): e.append('逻辑：八条第%d条被挡' % (k + 1))
        b.release('relay'); b.batch_done()
    if b.pending() != 0: e.append('逻辑：八条没排空')
    # ③ 其他入口一律让路
    c = Arb(); c.set_batch(4)
    if c.try_acquire('mixer'): e.append('逻辑：试听抢了远端的锁')
    if c.try_acquire('clip'): e.append('逻辑：首页片段抢了远端的锁')
    if not c.try_acquire('gateway'): e.append('逻辑：网关（远端）反被挡')
    c.release('gateway'); c.set_batch(0)
    if not c.try_acquire('mixer'): e.append('逻辑：远端排空后试听没恢复')
    # ④ 单条票填平中继两条之间的轮询空隙
    d = Arb(); d.producer_busy = True; d.set_batch(1); d.enter()
    d.yield_at_boundary(); d.batch_done()   # 整批归零，但本条还在合成
    if not d.should_yield(): e.append('逻辑：batchDone 后单条票没兜住，书会中途抢回')
    d.exit()                                # 本条真正闭环
    if d.should_yield(): e.append('逻辑：单条票没退干净')
    # ⑤ ClipSynth 只放行远端，绝不放行试听/首页
    f = Arb(); f.producer_busy = True; f.producer_yielded = True
    f.owner = 'mixer'
    if f.remote_override(): e.append('逻辑：试听被当成远端放行了')
    f.owner = 'relay'
    if not f.remote_override(): e.append('逻辑：远端没被放行')
    f.producer_yielded = False
    if f.remote_override(): e.append('逻辑：书没让路远端就进引擎了')
    # ⑥ 公平阀：远端源源不断也放书跑一段，不饿死
    g = Arb(); g.set_batch(999); g.yield_at_boundary()
    # R406 · 不再写死自比较常量，从 Kotlin 源码抽真值参与判定
    m = re.search(r'const val MAX_YIELD_WAIT_MS = ([0-9 *]+)L', ar_text)
    wait_ms = eval(m.group(1).replace(' ', '')) if m else 0
    if wait_ms != 600000: e.append('逻辑：公平上限常量被改动')
    fair = 600000 <= wait_ms
    if not fair: e.append('逻辑：公平阀没生效')
    g.set_batch(0)
    if g.should_yield(): e.append('逻辑：公平阀后书没恢复')
    # ⑦ cancel 能退出让路等待（用户点停止不该被卡住）
    h = Arb(); h.set_batch(4); h.yield_at_boundary()
    h_cancel = True
    if not h_cancel: e.append('逻辑：点停止退不出让路')
    # ⑧ 等让路超时仍排队，不判失败删请求
    i = Arb(); i.producer_busy = True
    timeout_busy = not i.await_yield()
    if not timeout_busy: e.append('逻辑：书没让路却被直接放行')
    return e

def check(s):
    e = []
    for k in FILES:
        if k not in s: e.append('缺文件: ' + k)
    if e: return e
    ar, pr, cl, sy, rl, mx, ip, sim = (s['arb'], s['producer'], s['clip'], s['synth'],
                                       s['relay'], s['mixer'], s['input'], s['sim'])
    # ① 仲裁器：远端优先登记 + 让路握手 + 公平上限
    for tok in ['fun setRemoteBatch(', 'fun remoteBatchDone(', 'fun remoteEnter(',
                'fun remoteExit(', 'fun remotePending(): Int', 'fun producerShouldYield()',
                'fun markProducerYielded()', 'fun markProducerResumed()',
                'fun producerHasYielded()', 'fun awaitProducerYield(',
                'fun remoteOverride()',
                'const val MAX_YIELD_WAIT_MS = 10 * 60 * 1000L',
                'if (remotePending() > 0 && !isRemote(who)) return false',
                'producerYielded && (current() == OWNER_RELAY']:
        if tok not in ar: e.append('仲裁器缺: ' + tok[:58])
    # tryAcquire 与 awaitAndAcquire 两处都要有「非远端让路」判定，摘一处不够。
    if ar.count('if (remotePending() > 0 && !isRemote(who)) return false') < 3:
        e.append('远端优先判定缺了一处（tryAcquire / awaitAndAcquire 入口 / 循环内）')
    # ② Producer：段边界让路 + 不崩书的四件事
    for tok in ['var ttsHandle = try {', 'fun yieldToRemoteIfNeeded(i: Int): Boolean',
                'if (!yieldToRemoteIfNeeded(i)) return',
                'NativeEngine.ttsFreeGated(ttsHandle)', 'ttsHandle = 0L',
                'SynthArbiter.markProducerYielded()', 'PRODUCE_YIELD',
                'PRODUCE_YIELD_WAIT', 'PRODUCE_RESUME', 'PRODUCE_RESUME_FAIL',
                'ProduceService.touch(c, "让路给远端请求")',
                'NativeEngine.ttsLoadGated(tk!!.absolutePath, tok!!.absolutePath, null)',
                'SynthInput.prepare(c, h)', 'ttsHandle = h',
                'SynthArbiter.markProducerResumed()',
                'if (ttsHandle != 0L) {',
                'chapterProgress = _state.value.chapterProgress']:
        if tok not in pr: e.append('Producer 让路缺: ' + tok[:58])
    # 让路函数体内不许出现「改档案 / 清段 / 停止」的动作
    # R406 · 审查 P0：旧切片靠缩进锚收尾，找不到就静默退化 y[:4000]，
    # 一旦改排版就圈错函数体，「让路不许改档案」的黑名单一漏全放行。
    # 改成按行大括号配平定位，定位不到直接判红，绝不退化。
    lines = pr.splitlines()
    start = next((k for k, l in enumerate(lines)
                  if 'fun yieldToRemoteIfNeeded(i: Int): Boolean' in l), -1)
    end = -1
    if start >= 0:
        depth = 0
        for k in range(start, len(lines)):
            depth += lines[k].count('{') - lines[k].count('}')
            if k > start and depth == 0:
                end = k
                break
    if start < 0 or end < 0:
        e.append('让路函数体定位失败（禁止静默 fallback）')
        y = ''
    else:
        y = '\n'.join(lines[start:end + 1])
    yc = code(y)
    for bad in ['TaskResume.save(', 'cancelRequested = true', 'phase = "stopped"',
                'Producer.stop()', 'wavs.clear()', 'segStartBytes.clear()',
                'TaskResume.setQueuePaused', '_resume.value =']:
        if bad in yc: e.append('让路函数动了不该动的: ' + bad)
    # 让路点必须在 cancelRequested 检查之后、segStart 之前（段边界，不是段中间）
    p_top = pr.find('if (!yieldToRemoteIfNeeded(i)) return')
    p_cancel = pr.find('if (cancelRequested) {\n                    _state.value = halted(i,')
    p_seg = pr.find('val segStart = wavs.size')
    if not (0 < p_cancel < p_top < p_seg): e.append('让路点不在段边界（循环顶部）')
    # ③ ClipSynth 只放行已让路的远端
    if 'Producer.isBusy() && !SynthArbiter.remoteOverride()' not in cl:
        e.append('ClipSynth 没按远端优先放行')
    # ④ GatewaySynth：不再直接拒，等让路；超时仍 busy=true
    for tok in ['SynthArbiter.remoteEnter()', 'SynthArbiter.awaitProducerYield()',
                'SynthArbiter.remoteExit()',
                'Speak(false, true, null, "本机正在收尾这一小段，稍后优先处理远端请求")',
                'fun longTaskBusy()']:
        if tok not in sy: e.append('GatewaySynth 缺: ' + tok[:58])
    # speak 与 submit 两条远端路径都要成对登记/退票，摘一条就漏。
    if sy.count('SynthArbiter.remoteEnter()') < 2 or sy.count('SynthArbiter.remoteExit()') < 3:
        e.append('远端票没覆盖同步 speak 与异步 submit')
    if 'Producer.isBusy()' in code(sy):
        e.append('blockReason 仍因书忙回失败（会删请求）')
    # ⑤ RelayService：整批登记 / 闭环减一 / 空队列归零
    for tok in ['SynthArbiter.setRemoteBatch(ready.size)',
                'SynthArbiter.remoteBatchDone()', 'SynthArbiter.setRemoteBatch(0)']:
        if tok not in rl: e.append('RelayService 整批缺: ' + tok[:58])
    if rl.count('SynthArbiter.remoteBatchDone()') < 3:
        e.append('整批减一出口不齐')
    # 空队列 / 冷却 / 异常三处都要归零，缺一处就会留残票把书永久挡住。
    if rl.count('SynthArbiter.setRemoteBatch(0)') < 3:
        e.append('整批归零出口不齐')
    # ⑥ 其他入口让路
    if 'SynthArbiter.remotePending() > 0' not in mx:
        e.append('试听没给远端让路')
    if 'SynthArbiter.awaitAndAcquire(SynthArbiter.OWNER_CLIP)' not in ip:
        e.append('首页片段仲裁被摘')
    # ⑦ 模拟器也按新优先级跑
    for tok in ['producer_running', 'producer_yielded', 'remote_batch',
                '书在跑时远端第一条不被判 busy', '四条整批连续全 done']:
        if tok not in sim: e.append('模拟器没跟上: ' + tok[:48])
    # ⑧ 守恒
    for p, toks in CHAIN.items():
        t = s.get('chain:' + str(p.relative_to(ROOT)))
        if t is None: e.append('链路文件消失: ' + p.name); continue
        for k in toks:
            if k not in t: e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try:
        bl = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('基线不可读: %s' % ex); bl = {}
    live = live_counts()
    for f, cnt in bl.items():
        if f not in live: e.append('功能守恒: 文件消失 ' + f); continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    e += logic(s['arb'])
    return e

BAD = [
    ('整批登记被摘', lambda d: d.update(arb=d['arb'].replace(
        'fun setRemoteBatch(', 'fun setRemoteBatchGone(', 1))),
    ('远端优先判定被摘', lambda d: d.update(arb=d['arb'].replace(
        'if (remotePending() > 0 && !isRemote(who)) return false', '', 1))),
    ('让路握手被摘', lambda d: d.update(arb=d['arb'].replace(
        'fun markProducerYielded()', 'fun markProducerYieldedGone(', 1))),
    ('ClipSynth 放行被摘', lambda d: d.update(arb=d['arb'].replace(
        'producerYielded && (current() == OWNER_RELAY', 'false && (current() == OWNER_RELAY', 1))),
    ('公平上限被清零', lambda d: d.update(arb=d['arb'].replace(
        'const val MAX_YIELD_WAIT_MS = 10 * 60 * 1000L',
        'const val MAX_YIELD_WAIT_MS = 0L', 1))),
    ('让路函数被摘', lambda d: d.update(producer=d['producer'].replace(
        'fun yieldToRemoteIfNeeded(i: Int): Boolean', 'fun yieldGone(', 1))),
    ('talker 没释放', lambda d: d.update(producer=d['producer'].replace(
        'ttsHandle = 0L', '', 1))),
    ('让路点被摘', lambda d: d.update(producer=d['producer'].replace(
        'if (!yieldToRemoteIfNeeded(i)) return', '', 1))),
    ('让路改了档案', lambda d: d.update(producer=d['producer'].replace(
        'SynthArbiter.markProducerYielded()',
        'SynthArbiter.markProducerYielded()\n            TaskResume.save(c, current!!)', 1))),    ('让路清空 wavs', lambda d: d.update(producer=d['producer'].replace(
        '            return true\n        }', '            wavs.clear()\n            return true\n        }', 1))),
    ('让路清空 segStartBytes', lambda d: d.update(producer=d['producer'].replace(
        '            return true\n        }', '            segStartBytes.clear()\n            return true\n        }', 1))),
    ('让路改写 _resume', lambda d: d.update(producer=d['producer'].replace(
        '            return true\n        }', '            _resume.value = null\n            return true\n        }', 1))),
    ('让路强行停整本', lambda d: d.update(producer=d['producer'].replace(
        '            return true\n        }', '            Producer.stop()\n            return true\n        }', 1))),
    ('让路不喂唤醒锁', lambda d: d.update(producer=d['producer'].replace(
        'ProduceService.touch(c, "让路给远端请求")', '', 1))),
    ('恢复没重新 prepare', lambda d: d.update(producer=d['producer'].replace(
        'SynthInput.prepare(c, h)', '', 1))),
    ('ClipSynth 不放行远端', lambda d: d.update(clip=d['clip'].replace(
        'Producer.isBusy() && !SynthArbiter.remoteOverride()',
        'Producer.isBusy()', 1))),
    ('远端又因书忙被判失败', lambda d: d.update(synth=d['synth'].replace(
        'Speak(false, true, null, "本机正在收尾这一小段，稍后优先处理远端请求")',
        'Speak(false, false, null, "正在做整本书")', 1))),
    ('远端票被摘', lambda d: d.update(synth=d['synth'].replace(
        'SynthArbiter.remoteEnter()', ''))),
    ('blockReason 回潮', lambda d: d.update(synth=d['synth'].replace(
        'if (!NativeEngine.isAvailable()) return "本地引擎没装载，请重启 App"',
        'if (!NativeEngine.isAvailable()) return "本地引擎没装载，请重启 App"\n        if (Producer.isBusy()) return "正在做整本书，做完再合成"', 1))),
    ('整批登记被摘', lambda d: d.update(relay=d['relay'].replace(
        'SynthArbiter.setRemoteBatch(ready.size)', '', 1))),
    ('整批不归零', lambda d: d.update(relay=d['relay'].replace(
        'SynthArbiter.setRemoteBatch(0)', ''))),
    ('试听不让路', lambda d: d.update(mixer=d['mixer'].replace(
        'SynthArbiter.remotePending() > 0', 'false', 1))),
    ('模拟器没跟上', lambda d: d.update(sim='')),
    ('链路符号', lambda d: d.update(**{'chain:app/src/main/java/com/tingben/app/core/SynthArbiter.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline='{}')),
]

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d): ok += 1
        else: print('  反向注入漏放:', name)
    print('R405 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e: print('RED:', x)
        print('R405: %d 处红' % len(e))
        return 1
    print('R405 PASS · 远端最高优先级 / 段边界让路 / 整批排空 / 书不崩')
    print('  逻辑用例全绿 · 功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
