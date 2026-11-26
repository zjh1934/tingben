#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R406 · 四路代码审查复核后的修复守门（远端优先链路 / 回写 / 守门自身 / 订阅源）。

R406 由四份独立审查报告（章节目录页 / 合成主链与让路 / 中继回写与日志 /
守门与守恒）交叉复核而来：剔除 3 处误判，落地 12 处修复。本守门逐条验在，
并守「R403/R404/R405 老修复不被顺手改坏」。

纯静态 + 纯内存逻辑；沙盒无 Android SDK，静态绿 != 真编译绿 != 真机。
用法：python3 scripts/r406_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'producer': APP / 'core/Producer.kt',
    'arb': APP / 'core/SynthArbiter.kt',
    'clip': APP / 'core/ClipSynth.kt',
    'resume': APP / 'core/TaskResume.kt',
    'archive': APP / 'core/RequestArchive.kt',
    'engine': APP / 'engine/NativeEngine.kt',
    'synth': APP / 'gateway/GatewaySynth.kt',
    'relay': APP / 'relay/RelayService.kt',
    'client': APP / 'relay/RelayClient.kt',
    'publish': ROOT / 'scripts/publish_apk.py',
    'sim': ROOT / 'scripts/relay_sim.py',
    'r405': ROOT / 'scripts/r405_verify.py',
    'r388': ROOT / 'scripts/r388_verify.py',
    'r391': ROOT / 'scripts/r391_verify.py',
    'status': ROOT / 'STATUS.md',
    'changelog': ROOT / 'CHANGELOG.md',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'core/Producer.kt': ['fun isBusy()', 'fun stop(', 'yieldToRemoteIfNeeded',
                               'PRODUCE_RESUME', 'halted('],
    APP / 'core/ClipSynth.kt': ['fun synthesize(', 'nativeCancelReset()',
                                'SynthArbiter.remoteOverride()'],
    APP / 'core/SynthArbiter.kt': ['fun setRemoteBatch(', 'fun remoteBatchDone(',
                                   'fun awaitProducerYield(', 'fun remoteOverride(',
                                   'MAX_YIELD_WAIT_MS'],
    APP / 'relay/RelayService.kt': ['RELAY_REQ', 'RELAY_SYNTH', 'RELAY_PUT_DONE',
                                    'RELAY_DEL', 'RELAY_PUT_ERR', 'client.putBusy(',
                                    'RequestArchive.add(this, RequestArchive.SRC_RELAY, text)'],
    APP / 'relay/RelayClient.kt': ['fun headSha(', 'fun putObj(', 'fun deleteQueue('],
    APP / 'gateway/GatewaySynth.kt': ['fun speak(', 'fun submit(',
                                      'ClipSynth.synthesize('],
    APP / 'core/TaskResume.kt': ['fun recoverScope(', 'fun realProgress('],
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

# ── 纯内存逻辑：复核四份审查报告里的真问题 ──────────────────────────
def sim_stop_in_yield(yielded, legacy=False):
    """书让路中点停止：旧版无条件 nativeCancel → 在飞的远端被砍成 -5。"""
    if legacy:
        return {'g_cancel': True, 'remote_hit': True}
    if yielded:
        return {'g_cancel': False, 'remote_hit': False}   # 不砍 + 顺手清
    return {'g_cancel': True, 'remote_hit': False}        # 书内推理要取消

def sim_interrupt(reset=False):
    """让路等待被中断：旧版不写 halted、不复位 yielded，会报「完成」且状态机脏。"""
    return {'halted': reset, 'yielded': not reset, 'reported_done': not reset}

def sim_fair_break(owner_free, load_busy, legacy=False):
    """公平上限出口：旧版只看 owner，会在远端已 CAS、未 ttsLoad 的窗口抢跑。"""
    if legacy:
        return owner_free
    return owner_free and not load_busy

def sim_empty_only(raw):
    """only:[] + onlyNull:false → 旧版空集合，新版按 null。"""
    return None if not raw else set(raw)

def sim_dedupe(now, ts, window=600000, legacy=False):
    """墙上钟回拨：旧版 now-ts 为负恒小于窗 → 永久去重。"""
    dt = now - ts
    if legacy:
        return dt < window
    return 0 <= dt < window

def sim_put_err(t, phase, conflict_codes=(422, 409)):
    """回写错误分流：只有 422/409 是冲突，其它如实归仓库侧/传输故障。"""
    kind = type(t).__name__
    code = getattr(t, 'code', None)
    label = ('回写冲突 422/409' if code in conflict_codes else
             ('仓库侧故障 HTTP %s' % code if code is not None else
              '传输故障 ' + kind))
    return {'label': label, 'cooldown': phase == 'put', 'keep_queue': True}

def sim_del_fail(logged=False):
    return {'deletable': False, 'observable': logged, 'still_deleted': False}

def logic():
    e = []
    # ① 让路中点停止
    old = sim_stop_in_yield(True, legacy=True)
    new = sim_stop_in_yield(True)
    if not old['g_cancel'] or not old['remote_hit']: e.append('逻辑：没复现旧版 -5 故障')
    if new['g_cancel'] or new['remote_hit']: e.append('逻辑：让路中停止仍砍到远端')
    if not sim_stop_in_yield(False)['g_cancel']: e.append('逻辑：书内推理反而没被取消')
    # ② 中断分支
    if sim_interrupt(False)['reported_done'] is False: e.append('逻辑：旧版中断没复现')
    n = sim_interrupt(True)
    if not n['halted'] or n['yielded'] or n['reported_done']:
        e.append('逻辑：中断后状态机没归零')
    # ③ 公平上限引擎锁窗口
    if sim_fair_break(True, True, legacy=True) is False: e.append('逻辑：旧版窗口没复现')
    if sim_fair_break(True, True) is not False: e.append('逻辑：loadBusy 时仍抢跑')
    if sim_fair_break(True, False) is not True: e.append('逻辑：引擎真空时没恢复')
    # ④ 空选章
    if sim_empty_only([]) is not None: e.append('逻辑：空 only 没归 null')
    if sim_empty_only([2, 3]) != {2, 3}: e.append('逻辑：正常选章被改写')
    # ⑤ 去重窗回拨
    if sim_dedupe(1000, 9000, legacy=True) is False: e.append('逻辑：回拨旧行为没复现')
    if sim_dedupe(1000, 9000) is not False: e.append('逻辑：回拨时仍误去重')
    if sim_dedupe(9000, 1000) is not True: e.append('逻辑：窗内重复没去重')
    # ⑥ 错误分流
    class Err:
        def __init__(self, c): self.code = c
    class IO: pass
    if sim_put_err(Err(422), 'put')['label'] != '回写冲突 422/409': e.append('逻辑：422 没归冲突')
    if '429' not in sim_put_err(Err(429), 'put')['label']: e.append('逻辑：429 被误当冲突')
    if 'IO' not in sim_put_err(IO(), 'put')['label']: e.append('逻辑：传输故障没如实归类')
    if not all(sim_put_err(x, 'put')['keep_queue'] for x in (Err(422), Err(429), IO())):
        e.append('逻辑：某种错误把请求删了')
    if sim_put_err(Err(422), 'poll')['cooldown']: e.append('逻辑：轮询阶段误写冷却')
    # ⑦ DELETE 失败留痕
    if sim_del_fail(False)['observable']: e.append('逻辑：旧版删失败不该可观测')
    if not sim_del_fail(True)['observable']: e.append('逻辑：删失败没留痕')
    # ⑧ 单条票成对（try/finally）
    def ticket(path, launched):
        pending = 1
        if not launched: pending -= 1    # 外层 finally
        elif path == 'job': pending -= 1  # 异步 job finally
        return pending
    if ticket('early_return', False) != 0: e.append('逻辑：提前 return 留残票')
    if ticket('job', True) != 0: e.append('逻辑：异步 job 没退票')
    return e

def check(s):
    e = []
    for k in FILES:
        if k not in s: e.append('缺文件: ' + k)
    if e: return e
    pr, ar, cl, rs, ac, en, sy, rl, cli, pub, sim, r405 = (
        s['producer'], s['arb'], s['clip'], s['resume'], s['archive'],
        s['engine'], s['synth'], s['relay'], s['client'], s['publish'],
        s['sim'], s['r405'])
    # ① 让路中止不再污染 g_cancel
    for tok in ['YIELD_STOP_NO_CANCEL', 'if (!SynthArbiter.producerHasYielded())',
                'PRODUCE_YIELD_INTERRUPTED', 'Thread.currentThread().interrupt()',
                'SynthArbiter.markProducerResumed()',
                'try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }',
                'fun isLoadBusy(): Boolean',
                'val engineFree = !SynthArbiter.isShortBusy() &&',
                '!NativeEngine.isLoadBusy()',
                '(fair && engineFree)']:
        if tok not in pr and tok not in en:
            e.append('让路中止/引擎锁缺: ' + tok[:58])
    # cancel 分支必须同时清 g_cancel 并复位让路态（R403 的 -5 故障不许回潮）
    p_cancel = pr.find('if (cancelRequested) {\n                // 用户点停止')
    if p_cancel < 0:
        e.append('让路取消分支定位失败')
    else:
        seg = pr[p_cancel:p_cancel + 600]
        if 'nativeCancelReset()' not in seg or 'markProducerResumed()' not in seg:
            e.append('让路取消分支没清 g_cancel / 没复位状态机')
    # start() 也要幂等复位
    p_start = pr.find('fun start(')
    if p_start < 0 or 'SynthArbiter.markProducerResumed()' not in pr[p_start:p_start + 6000]:
        e.append('start() 没幂等复位让路态')
    # ② submit 票成对
    for tok in ['var launched = false', 'var acquired = false',
                'if (!launched) {', 'SynthArbiter.remoteExit()']:
        if tok not in sy: e.append('submit 成对票缺: ' + tok[:50])
    if sy.count('SynthArbiter.remoteEnter()') < 2 or sy.count('SynthArbiter.remoteExit()') < 3:
        e.append('submit 的票登记/退出不齐')
    # ③ 空选章 + 去重窗
    for tok in ['arr.length() == 0', '!t.only.isNullOrEmpty()']:
        if tok not in rs: e.append('空选章口径缺: ' + tok[:50])
    if 'dt >= 0 && dt < DEDUPE_MS' not in ac:
        e.append('去重窗回拨防护缺')
    # ④ 回写错误分流 / DELETE 留痕 / 冷却死键
    for tok in ['回写冲突 422/409', '仓库侧故障 ', '传输故障 ',
                'RELAY_DEL_ERR', 'RELAY_BUSY_ERR', 'phase = "busy"',
                'putCooldownUntil.keys.retainAll',
                'phase != "put" && phase != "busy" && phase != "del"']:
        if tok not in rl: e.append('回写归类缺: ' + tok[:50])
    if rl.count('client.deleteQueue(item.name, reqSha)') != 3:
        e.append('正文即用即删的两处出口被改动')
    if 'phase = "del"\n                    client.deleteQueue' not in rl:
        e.append('reject 分支没把 delete 前 phase 正名')
    # ⑤ 订阅源换镜像（R388 判据同步反转，不是放宽）
    if 'href="https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/' not in pub:
        e.append('订阅源没指向镜像')
    if 'href="https://raw.githubusercontent.com' in pub:
        e.append('订阅源又指回 raw')
    if 'purge.jsdelivr.net' not in pub:
        e.append('发版没刷镜像缓存')
    for tok in ['cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom',
                'href="https://raw.githubusercontent.com',
                'purge.jsdelivr.net']:
        if tok not in s['r388'] and tok not in s['r391']:
            e.append('域名守门没同步: ' + tok[:50])
    # ⑥ 守门自身不再静默退化 / 模拟器不再假绿
    for tok in ['让路函数体定位失败（禁止静默 fallback）', 'lines = pr.splitlines()',
                '让路清空 wavs', '让路清空 segStartBytes', '让路改写 _resume',
                '让路强行停整本', 'const val MAX_YIELD_WAIT_MS = ([0-9 *]+)L']:
        if tok not in r405: e.append('r405 加固缺: ' + tok[:50])
    if 'else y[:4000]' in r405: e.append('r405 仍有静默 fallback')
    for tok in ["repo.get(qpath).decode('utf-8')", "len(audio) == int(st['bytes'])",
                "ck('40 补做后请求文件即用即删'", "ck('41 排队态不落正文'"]:
        if tok not in sim: e.append('relay_sim 假绿未修: ' + tok[:50])
    if re.search(r'if False', code(sim)): e.append('relay_sim 仍有死分支')
    # ⑦ 文档口径：数字一致 + 欠账登记 + 静态非真机尾注
    ch = s['changelog']; st = s['status']
    if '155 零红、156 个 selftest' in ch or '155 零红、156 个 selftest' in s.get('notes', ''):
        e.append('文档数字仍不一致（155 零红 / 156 selftest）')
    for need in ['长文本分段', '真 MP3', '厂商清理', '细粒度 PAT', '四件套剩余页']:
        if need not in st: e.append('STATUS 未登记欠账: ' + need)
    # ⑧ 老修复守恒
    for tok in ['fun headSha(', 'fun putObj(', 'RELAY_PUT_ERR']:
        if tok not in cli and tok not in rl: e.append('R404 修复被摘: ' + tok)
    if 'Producer.isBusy() && !SynthArbiter.remoteOverride()' not in cl:
        e.append('R405 ClipSynth 放行被摘')
    if 'if (wholeWav > 32 || bestScore < wholeWav * 2) return null' not in rs:
        e.append('R404 自愈边界被摘')
    # ⑨ 链路符号与功能守恒
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
    e += logic()
    return e

BAD = [
    ('让路中又砍远端', lambda d: d.update(producer=d['producer'].replace(
        'if (!SynthArbiter.producerHasYielded()) {', 'if (true) {', 1))),
    ('让路失败不清 g_cancel', lambda d: d.update(producer=d['producer'].replace(
        'try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }\n                SynthArbiter.markProducerResumed()\n                _state.value = halted(i, annotated.size, gateFails + synthFails)',
        '_state.value = halted(i, annotated.size, gateFails + synthFails)', 1))),
    ('中断分支又直接 return', lambda d: d.update(producer=d['producer'].replace(
        'PRODUCE_YIELD_INTERRUPTED', '', 1))),
    ('引擎锁判定被摘', lambda d: d.update(producer=d['producer'].replace(
        '!NativeEngine.isLoadBusy()', 'true', 1))),
    ('isLoadBusy 被摘', lambda d: d.update(engine=d['engine'].replace(
        'fun isLoadBusy(): Boolean', 'fun isLoadBusyGone(', 1))),
    ('submit 又手写退票', lambda d: d.update(synth=d['synth'].replace(
        'var launched = false', 'var launched = true', 1))),
    ('submit 丢 finally', lambda d: d.update(synth=d['synth'].replace(
        'if (!launched) {', 'if (false) {', 1))),
    ('空选章口径回潮', lambda d: d.update(resume=d['resume'].replace(
        'arr.length() == 0', '', 1))),
    ('去重窗又用墙上钟负值', lambda d: d.update(archive=d['archive'].replace(
        'dt >= 0 && dt < DEDUPE_MS', 'dt < DEDUPE_MS', 1))),
    ('错误分流被摘', lambda d: d.update(relay=d['relay'].replace(
        '回写冲突 422/409', '回写没成功', 1))),
    ('DELETE 失败不留痕', lambda d: d.update(relay=d['relay'].replace(
        'RELAY_DEL_ERR', '', 1))),
    ('busy 态又伪装轮询失败', lambda d: d.update(relay=d['relay'].replace(
        'phase = "busy"', '', 1))),
    ('冷却死键不清理', lambda d: d.update(relay=d['relay'].replace(
        'putCooldownUntil.keys.retainAll', '', 1))),
    ('同一件事又打两条矛盾日志', lambda d: d.update(relay=d['relay'].replace(
        'phase != "put" && phase != "busy" && phase != "del"', 'true', 1))),
    ('订阅源回退 raw', lambda d: d.update(publish=d['publish'].replace(
        'https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/',
        'https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/', 1))),
    ('发版不刷缓存', lambda d: d.update(publish=d['publish'].replace(
        'purge.jsdelivr.net', '', 1))),
    ('守门又静默退化', lambda d: d.update(r405=d['r405'].replace(
        "e.append('让路函数体定位失败（禁止静默 fallback）')", 'pass', 1))),
    ('模拟器又恒真断言', lambda d: d.update(sim=d['sim'].replace(
        "len(audio) == int(st['bytes'])", 'True', 1))),
    ('欠账登记被摘', lambda d: d.update(status=d['status'].replace('厂商清理', '', 1))),
    ('链路符号', lambda d: d.update(**{'chain:app/src/main/java/com/tingben/app/core/SynthArbiter.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline='{}')),
]

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d): ok += 1
        else: print('  反向注入漏放:', name)
    print('R406 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e: print('RED:', x)
        print('R406: %d 处红' % len(e))
        return 1
    print('R406 PASS · 让路中止安全 / 引擎锁窗口 / 票成对 / 回写归类 / 订阅源镜像 / 守门不假绿')
    print('  逻辑用例全绿 · 功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
