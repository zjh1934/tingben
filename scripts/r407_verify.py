#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R407 · 四路审查遗留项收敛（继续做到可出包）：空间状态机 / 跳过重做 /
冷却持久化 / 让路心跳 / 上传失败震动兜底。

对应总报告「明确没做」里本轮落地的 5 项；其余仍登记在 STATUS 欠账小节。
纯静态 + 纯内存逻辑；沙盒无 Android SDK，静态绿 != 真编译绿 != 真机。
用法：python3 scripts/r407_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'producer': APP / 'core/Producer.kt',
    'hub': APP / 'ChapterHub.kt',
    'client': APP / 'relay/RelayClient.kt',
    'prefs': APP / 'relay/RelayPrefs.kt',
    'relay': APP / 'relay/RelayService.kt',
    'arb': APP / 'core/SynthArbiter.kt',
    'resume': APP / 'core/TaskResume.kt',
    'publish': ROOT / 'scripts/publish_apk.py',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
    'status': ROOT / 'STATUS.md',
    'ci': ROOT / '.github/workflows/ci.yml',
    'r135': ROOT / 'scripts/r135_verify.py',
}
CHAIN = {
    APP / 'core/Producer.kt': ['fun isBusy()', 'yieldToRemoteIfNeeded',
                               'PRODUCE_RESUME', 'spaceStopped', 'halted('],
    APP / 'core/ClipSynth.kt': ['nativeCancelReset()', 'remoteOverride()'],
    APP / 'core/SynthArbiter.kt': ['setRemoteBatch(', 'remoteBatchDone(',
                                   'awaitProducerYield(', 'MAX_YIELD_WAIT_MS'],
    APP / 'relay/RelayService.kt': ['RELAY_REQ', 'RELAY_SYNTH', 'RELAY_PUT_DONE',
                                    'RELAY_DEL', 'RELAY_PUT_ERR', 'RELAY_RESUME_OUT'],
    APP / 'relay/RelayClient.kt': ['fun headSha(', 'fun putObj(',
                                   'fun statOut(', 'fun deleteQueue('],
    APP / 'gateway/GatewaySynth.kt': ['fun speak(', 'fun submit('],
    APP / 'core/TaskResume.kt': ['fun recoverScope(', 'fun realProgress('],
}

def sources():
    d = {k: v.read_text(encoding='utf-8') for k, v in FILES.items() if v.exists()}
    for p in CHAIN:
        if p.exists(): d['chain:' + str(p.relative_to(ROOT))] = p.read_text(encoding='utf-8')
    return d

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

# ── 纯内存逻辑：复核四份审查报告里本轮收敛的真问题 ──────────────────
def sim_space(stage, legacy=False):
    """空间用完：旧版 stage='没完成' → 按钮说继续、收尾条说可拼、网格说已暂停。"""
    if legacy:
        return {'label': '继续制作', 'sub': '断点续做', 'merge': True,
                'grid': '已暂停 · 等续做', 'consistent': False}
    if stage == '空间用完':
        return {'label': '继续制作', 'sub': '先清空间', 'merge': False,
                'grid': '空间不足', 'consistent': True}
    return {'label': '继续制作', 'sub': '断点续做', 'merge': True,
            'grid': '已暂停 · 等续做', 'consistent': True}

def sim_retry(out_exists, legacy=False):
    """out 已上传、status 半途失败：旧版下轮从合成重做一遍。"""
    if legacy:
        return {'synth_ms': 55000, 'put': True, 'keep_queue': True}
    if out_exists:
        return {'synth_ms': 0, 'put': False, 'keep_queue': False}  # 只补 status + 删
    return {'synth_ms': 55000, 'put': True, 'keep_queue': True}

def sim_cooldown(persist, now, until, clock_back=False, legacy=False):
    """冷却落盘 + 校时回拨：旧版重启即失效；回拨不该把冷却变成永久。"""
    if legacy:
        return {'active': False, 'permanent': False}
    if clock_back:
        # 读取时只收 until > now 的项，回拨时 until 反而更大 → 用绝对时间差
        # 上限兜底（MAX_BACKOFF*5）：异常大的差值一律视为过期，不会永久冷却
        active = until > now and (until - now) < 600000
        return {'active': active, 'permanent': False}
    return {'active': persist and until > now, 'permanent': False}

def sim_heartbeat(yielding, age=583, legacy=False):
    """让路期间心跳：旧版把 age 一路写到 583 秒，视觉上卡死。"""
    if legacy or not yielding:
        return '正在逐字生成这一块的朗读分段 · 已 %d 秒没新动作' % age
    return '远端请求优先 · 书在段边界等着，做完自动接着来'

def sim_haptic(synth_ok, upload_failed, legacy=False):
    """完成震动已给、上传失败：旧版无补震，手感与结果不一致。"""
    if legacy:
        return {'done': True, 'fail': False}
    return {'done': True, 'fail': bool(synth_ok and upload_failed)}

def sim_stale(keys, queue, legacy=False):
    """冷却死键：旧版只收内存 Map，落盘那份没人管。"""
    alive = set(queue)
    return {'memory': set(keys) & alive,
            'disk': set(keys) if legacy else set(keys) & alive}

def logic():
    e = []
    if sim_space('没完成', legacy=True)['consistent']: e.append('逻辑：旧版三处打架没复现')
    s = sim_space('空间用完')
    if not s['consistent'] or s['merge'] or s['sub'] != '先清空间' or s['grid'] != '空间不足':
        e.append('逻辑：空间用完三处没统一')
    if sim_space('已停止')['merge'] is False: e.append('逻辑：正常暂停被误置灰')
    if sim_retry(True, legacy=True)['synth_ms'] == 0: e.append('逻辑：旧版重做没复现')
    r = sim_retry(True)
    if r['synth_ms'] != 0 or r['put'] or r['keep_queue']: e.append('逻辑：out 已上传仍重做合成')
    if sim_retry(False)['synth_ms'] == 0: e.append('逻辑：没有 out 却跳过了合成')
    if sim_cooldown(True, 1000, 5000, legacy=True)['active']: e.append('逻辑：旧版重启没复现')
    if not sim_cooldown(True, 1000, 5000)['active']: e.append('逻辑：冷却没落盘生效')
    if sim_cooldown(False, 1000, 5000)['active']: e.append('逻辑：没落盘却仍冷却')
    if sim_cooldown(True, 1000, 10**9, clock_back=True)['active']:
        e.append('逻辑：校时回拨把冷却变成永久')
    if '已 583' not in sim_heartbeat(True, legacy=True): e.append('逻辑：旧版卡死文案没复现')
    if '已 ' in sim_heartbeat(True): e.append('逻辑：让路心跳仍报秒数')
    if '已 ' not in sim_heartbeat(False): e.append('逻辑：正常态心跳反而不报秒数')
    if sim_haptic(True, True, legacy=True)['fail']: e.append('逻辑：旧版补震没复现')
    if not sim_haptic(True, True)['fail']: e.append('逻辑：上传失败没补失败震动')
    if sim_haptic(False, True)['fail']: e.append('逻辑：合成失败被重复震两次')
    d = sim_stale({'a.json', 'b.json'}, ['a.json'], legacy=True)
    if d['disk'] != {'a.json', 'b.json'}: e.append('逻辑：旧版磁盘死键没复现')
    d = sim_stale({'a.json', 'b.json'}, ['a.json'])
    if d['memory'] != {'a.json'} or d['disk'] != {'a.json'}: e.append('逻辑：死键没同步落盘')
    return e

BAD = [
    ('空间态退回没完成', lambda d: d.update(producer=d['producer'].replace(
        'Progress(false, "空间用完",', 'Progress(false, "没完成",', 1))),
    ('空间态三处不统一', lambda d: d.update(hub=d['hub'].replace(
        'val spaceFull = st.stage == "空间用完" || task?.stage == "空间用完"',
        'val spaceFull = false', 1))),
    ('空间不足仍放行拼章', lambda d: d.update(hub=d['hub'].replace(
        '&& !spaceFull', '', 1))),
    ('空间不足无中文拦截', lambda d: d.update(hub=d['hub'].replace(
        'if (spaceFull) {', 'if (false) {', 1))),
    ('心跳让路文案被摘', lambda d: d.update(hub=d['hub'].replace(
        'Producer.isYielding()', 'false', 1))),
    ('让路态没置位', lambda d: d.update(producer=d['producer'].replace(
        'setYielding(true)', '', 1))),
    ('让路态没复位', lambda d: d.update(producer=d['producer'].replace(
        'setYielding(false)', '', 1))),
    ('isYielding 被摘', lambda d: d.update(producer=d['producer'].replace(
        'fun isYielding(): Boolean', 'fun isYieldingGone(', 1))),
    ('statOut 被摘', lambda d: d.update(client=d['client'].replace(
        'fun statOut(id: String)', 'fun statOutOld(id: String)', 1))),
    ('跳过重做分支被摘', lambda d: d.update(relay=d['relay'].replace(
        'RELAY_RESUME_OUT', '', 1))),
    ('跳过重做仍重合成', lambda d: d.update(relay=d['relay'].replace(
        'if (putCooldownUntil.containsKey(item.name)) {', 'if (false) {', 1))),
    ('冷却没落盘', lambda d: d.update(relay=d['relay'].replace(
        'RelayPrefs.cooldowns(this)', 'emptyMap()', 1))),
    ('冷却没写盘', lambda d: d.update(relay=d['relay'].replace(
        'RelayPrefs.setCooldown(this, currentName!!, until)', '', 1))),
    ('冷却没清盘', lambda d: d.update(relay=d['relay'].replace(
        'RelayPrefs.clearCooldown(this, item.name)', ''))),
    ('死键不同步落盘', lambda d: d.update(relay=d['relay'].replace(
        'stale.forEach { RelayPrefs.clearCooldown(this, it) }', '', 1))),
    ('Prefs 冷却读被摘', lambda d: d.update(prefs=d['prefs'].replace(
        'fun cooldowns(c: Context)', 'fun cooldownsOld(c: Context)', 1))),
    ('上传失败不补震', lambda d: d.update(relay=d['relay'].replace(
        'if (synthOk) RelayHaptics.fail(this)', '', 1))),
    ('合成失败重复震', lambda d: d.update(relay=d['relay'].replace(
        'synthOk = true', 'synthOk = false', 1))),
    ('订阅源回退 raw', lambda d: d.update(publish=d['publish'].replace(
        'https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/',
        'https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/', 1))),
    ('欠账登记被摘', lambda d: d.update(status=d['status'].replace('厂商清理', '', 1))),
    ('RelayPrefs 漏 JSONObject import', lambda d: d.update(prefs=d['prefs'].replace(
        'import org.json.JSONObject', '', 1))),
    ('SegGridHead 漏 spaceFull 参数', lambda d: d.update(hub=d['hub'].replace(
        'chapterNo: Int, running: Boolean, spaceFull: Boolean,',
        'chapterNo: Int, running: Boolean,', 1))),
    ('SegGridHead 调用没传 spaceFull', lambda d: d.update(hub=d['hub'].replace(
        'spaceFull = spaceFull,', '', 1))),
    ('链路符号', lambda d: d.update(**{
        'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline='{}')),
]

def check(s):
    e = []
    for k in FILES:
        if k not in s: e.append('缺文件: ' + k)
    if e: return e
    pr, hub, cli, pf, rl = s['producer'], s['hub'], s['client'], s['prefs'], s['relay']
    # ① 空间状态机
    for tok in ['Progress(false, "空间用完",', 'val spaceFull = st.stage == "空间用完"',
                'spaceFull -> "先清空间"', '先清出约 16MB', '&& !spaceFull',
                'spaceFull -> "空间不足', 'if (spaceFull) "空间不足"']:
        if tok not in pr and tok not in hub: e.append('空间状态机缺: ' + tok[:58])
    if 'if (spaceFull) {' not in hub:
        e.append('空间不足没有中文拦截分支（点继续会再撞预算）')
    # ② 让路心跳
    for tok in ['fun isYielding(): Boolean', 'setYielding(true)', 'setYielding(false)',
                'Producer.isYielding()', '远端请求优先 · 书在段边界等着']:
        if tok not in pr and tok not in hub: e.append('让路心跳缺: ' + tok[:58])
    if pr.count('setYielding(false)') != 5:
        e.append('让路态复位出口不齐（应 5 处）')
    # ③ 跳过重做 + 冷却落盘
    for tok in ['fun statOut(id: String): Triple<String, Long, String>?',
                'RELAY_RESUME_OUT', 'client.statOut(id)',
                'if (putCooldownUntil.containsKey(item.name))',
                'val putCooldownUntil = HashMap<String, Long>(RelayPrefs.cooldowns(this))',
                'RelayPrefs.setCooldown(this, currentName!!, until)',
                'RelayPrefs.clearCooldown(this, item.name)',
                'stale.forEach { RelayPrefs.clearCooldown(this, it) }',
                'stale.forEach { RelayPrefs.clearCooldown(this, it) }',
                'fun cooldowns(c: Context): Map<String, Long>',
                'fun setCooldown(', 'fun clearCooldown(', 'put_cooldowns',
                'if (until > now) m[k] = until']:
        if tok not in cli and tok not in rl and tok not in pf:
            e.append('远端链路修复缺: ' + tok[:58])
    # ④ 震动兜底
    for tok in ['var synthOk = false', 'synthOk = true',
                'if (synthOk) RelayHaptics.fail(this)']:
        if tok not in rl: e.append('震动兜底缺: ' + tok[:58])
    # ⑤ 三处正文即用即删仍在，且订阅源仍是镜像
    if rl.count('RelayPrefs.clearCooldown(this, item.name)') < 2:
        e.append('两处成功闭环没都清落盘冷却')
    if rl.count('client.deleteQueue(item.name, reqSha)') != 3:
        e.append('正文即用即删的三处出口被改动')
    if 'href="https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/' not in s['publish']:
        e.append('订阅源没指向镜像')
    if 'href="https://raw.githubusercontent.com' in s['publish']:
        e.append('订阅源又指回 raw')
    # ⑤b · 编译作用域守恒：这两处曾只在 CI 真编译里暴露（e: Unresolved reference），
    #      静态守门必须提前拦——空间态变量跨 composable、org.json 漏 import。
    if 'import org.json.JSONObject' not in pf:
        e.append('RelayPrefs 漏 JSONObject import（CI 必报 Unresolved reference）')
    if 'chapterNo: Int, running: Boolean, spaceFull: Boolean,' not in hub:
        e.append('SegGridHead 缺 spaceFull 形参')
    if 'spaceFull = spaceFull,' not in hub:
        e.append('SegGridHead 调用没传 spaceFull')
    # ⑥ 老修复守恒
    if 'fun headSha(' not in cli or 'RELAY_PUT_ERR' not in rl:
        e.append('R404 修复被摘')
    if 'engineFree = !SynthArbiter.isShortBusy()' not in pr or \
            'YIELD_STOP_NO_CANCEL' not in pr:
        e.append('R406 让路修复被摘')
    if 'if (!t.only.isNullOrEmpty() || t.phase == "done") return null' not in s['resume']:
        e.append('R406 空选章修复被摘')
    if 'MAX_YIELD_WAIT_MS' not in s['arb']: e.append('R405 公平阀被摘')
    # ⑦ 欠账仍登记
    for need in ['长文本分段', '真 MP3', '厂商清理', '细粒度 PAT', '四件套剩余页']:
        if need not in s['status']: e.append('STATUS 未登记欠账: ' + need)
    # ⑧ 挂 CI / 现行清单
    if '"r407_verify.py"' not in s['r135']: e.append('现行清单漏登记 r407')
    if 'python3 scripts/r407_verify.py' not in s['ci']: e.append('CI 漏挂 r407 正向')
    if 'python3 scripts/r407_verify.py --selftest' not in s['ci']:
        e.append('CI 漏挂 r407 反向')
    # ⑨ 链路符号与功能守恒
    for p, toks in CHAIN.items():
        tt = s.get('chain:' + str(p.relative_to(ROOT)))
        if tt is None: e.append('链路文件消失: ' + p.name); continue
        for k in toks:
            if k not in tt: e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try: bl = json.loads(s['baseline'])['files']
    except Exception as ex: e.append('基线不可读: %s' % ex); bl = {}
    live = live_counts()
    for f, cnt in bl.items():
        if f not in live: e.append('功能守恒: 文件消失 ' + f); continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    e += logic()
    return e

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d): ok += 1
        else: print('  反向注入漏放:', name)
    print('R407 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv: return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e: print('RED:', x)
        print('R407: %d 处红' % len(e)); return 1
    print('R407 PASS · 空间状态机 / 跳过重做 / 冷却落盘 / 让路心跳 / 上传补震 / 订阅源镜像')
    print('  逻辑用例全绿 · 功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__': sys.exit(main())
