#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R412 · 低电保护守门：只拦本地电子书合成，不碰其余任何合成链路。

用户口径：手机电量低于 20% 就拒绝；**只拒绝本地电子书的合成请求**，
远端中继、语音网关、调音台试听、首页粘贴文字合成都不拒绝。

守：阈值严格 <20（20% 本身放行）、读不到电池不误伤、闸门在四个入口齐全、
start 兜底必须落在 busy=true 之前（不许写档案 / 启服务）、被拦时 UI 有明确
反馈、零新增权限与依赖、其余链路文件里不许出现 BatteryGate、功能守恒、
链路符号与现行清单 / CI 登记。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 rc / main 两次 CI 负责。
用法：python3 scripts/r412_verify.py [--selftest]
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'gate': APP / 'core/BatteryGate.kt',
    'producer': APP / 'core/Producer.kt',
    'main': APP / 'MainActivity.kt',
    'hub': APP / 'ChapterHub.kt',
    'taskui': APP / 'TaskUi.kt',
    'manifest': ROOT / 'app/src/main/AndroidManifest.xml',
    'gradle': ROOT / 'app/build.gradle.kts',
    'r135': ROOT / 'scripts/r135_verify.py',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
    'status': ROOT / 'STATUS.md',
    'changelog': ROOT / 'CHANGELOG.md',
}

# 这些文件一旦出现 BatteryGate，就是拦错了链路（远端 / 网关 / 试听 / 单段 / 播放器）
FORBIDDEN = [
    'relay/RelayService.kt', 'relay/RelayClient.kt', 'relay/RelayUi.kt',
    'relay/RelayHaptics.kt', 'relay/RelayPrefs.kt',
    'gateway/GatewaySynth.kt', 'gateway/GatewayService.kt', 'gateway/GatewayUi.kt',
    'core/ClipSynth.kt', 'core/SynthArbiter.kt', 'core/SynthInput.kt',
    'core/Mixer.kt', 'core/UpdateChecker.kt',
    'InputReadUi.kt', 'MixerUi.kt', 'AboutUi.kt', 'ResumeUi.kt',
    'PlayerSheet.kt', 'LibraryUi.kt', 'PlayQueue.kt',
]

CHAIN = {
    APP / 'relay/RelayService.kt': ['RELAY_DEL', 'synthOk'],
    APP / 'gateway/GatewaySynth.kt': ['fun speak('],
    APP / 'core/ClipSynth.kt': ['fun synthesize('],
    APP / 'core/SynthArbiter.kt': ['setRemoteBatch('],
    APP / 'core/TaskResume.kt': ['fun recoverScope('],
    APP / 'diag/EventLog.kt': ['RETENTION_MS'],
}


def sources():
    d = {k: (v.read_text(encoding='utf-8', errors='replace') if v.exists() else '')
         for k, v in FILES.items()}
    for f in FORBIDDEN:
        p = APP / f
        d['forbid:' + f] = p.read_text(encoding='utf-8', errors='replace') if p.exists() else ''
    for p, toks in CHAIN.items():
        d['chain:' + str(p.relative_to(ROOT))] = (
            p.read_text(encoding='utf-8', errors='replace') if p.exists() else '')
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


def blocked(pct, valid, limit=20, strict=True):
    """复刻 BatteryGate.blocked：严格 <20；读不到（valid=False）一律放行。"""
    if not valid:
        return False
    return pct < limit if strict else pct <= limit


def logic():
    e = []
    cases = [
        (19, True, True, '19% 应拦'),
        (20, True, False, '20% 本身必须放行（「低于」不含等于）'),
        (21, True, False, '21% 应放行'),
        (0, True, True, '0% 应拦'),
        (100, True, False, '100% 应放行'),
        (-1, False, False, '读不到电池不能误伤'),
        (5, False, False, '无电池设备不能误伤'),
    ]
    for pct, valid, want, name in cases:
        if blocked(pct, valid) != want:
            e.append('逻辑：' + name)
    # 对照：<= 版本确实会误拦 20%，用来证明「严格小于」这条口径不是空话
    if not blocked(20, True, strict=False):
        e.append('逻辑：<= 对照没复现出 20% 被误拦')
    if blocked(19, True, limit=5):
        e.append('逻辑：阈值被改小后 19% 不该再拦')
    return e


BAD = [
    ('阈值被改小', lambda d: d.update(gate=d['gate'].replace(
        'const val LIMIT_PCT = 20', 'const val LIMIT_PCT = 5', 1))),
    ('阈值被改成含等于', lambda d: d.update(gate=d['gate'].replace(
        'pct < LIMIT_PCT', 'pct <= LIMIT_PCT', 1))),
    ('读不到电池也拦', lambda d: d.update(gate=d['gate'].replace(
        'valid && pct < LIMIT_PCT', 'pct < LIMIT_PCT', 1))),
    ('比例不再按 scale 归一', lambda d: d.update(gate=d['gate'].replace(
        '(level * 100) / scale', 'level', 1))),
    ('拒绝对话不提其余链路', lambda d: d.update(gate=d['gate'].replace(
        '远端请求、试听和单段合成不受影响', '', 1))),
    ('enqueueOrStart 闸门被摘', lambda d: d.update(producer=d['producer'].replace(
        '"at=enqueueOrStart · "', '"at=gone · "', 1))),
    ('resumeQueue 闸门被摘', lambda d: d.update(producer=d['producer'].replace(
        '"at=resumeQueue · "', '"at=gone · "', 1))),
    ('autoResume 闸门被摘', lambda d: d.update(producer=d['producer'].replace(
        '"at=autoResume · "', '"at=gone · "', 1))),
    ('start 兜底闸门被摘', lambda d: d.update(producer=d['producer'].replace(
        '"at=start · "', '"at=gone · "', 1))),
    ('start 闸门挪到写档案之后', lambda d: d.update(producer=d['producer'].replace(
        '              config: ProduceConfig = ProduceConfig()) {',
        '              config: ProduceConfig = ProduceConfig()) {\n        busy = true', 1))),
    ('闸门日志被删', lambda d: d.update(producer=d['producer'].replace(
        '"BOOK_BATTERY_GATE"', '"GATE_GONE"'))),
    ('拒因不回写 lastStartError', lambda d: d.update(producer=d['producer'].replace(
        'lastStartError = why', '// gone'))),
    ('续跑静默无提示', lambda d: d.update(main=d['main'].replace(
        'Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()', '', 1))),
    ('队列恢复静默无提示', lambda d: d.update(taskui=d['taskui'].replace(
        'android.widget.Toast.makeText(c, why,', '// gone', 1))),
    ('单章重做仍弹假成功', lambda d: d.update(hub=d['hub'].replace(
        'Producer.lastStartError?.let { why ->\n                Toast.makeText(c, why, Toast.LENGTH_LONG).show()',
        '// gone', 1))),
    ('多选段重做仍弹假成功', lambda d: d.update(hub=d['hub'].replace(
        'Producer.lastStartError?.let { why ->\n                                                            Toast.makeText(c, why,',
        '// gone', 1))),
    ('远端链路被误挂闸门', lambda d: d.update(**{
        'forbid:relay/RelayService.kt': d['forbid:relay/RelayService.kt'] +
        '\n// BatteryGate.bookBlockReason(c)'})),
    ('试听链被误挂闸门', lambda d: d.update(**{
        'forbid:core/ClipSynth.kt': d['forbid:core/ClipSynth.kt'] +
        '\n// BatteryGate.bookBlockReason(c)'})),
    ('新增电池权限', lambda d: d.update(manifest=d['manifest'].replace(
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.INTERNET" />\n'
        '<uses-permission android:name="android.permission.BATTERY_STATS" />', 1))),
    ('新增依赖', lambda d: d.update(gradle=d['gradle'].replace(
        'implementation(', 'implementation("androidx.battery:battery:1.0")\n    implementation(', 1))),
    ('现行清单漏登记', lambda d: d.update(r135=d['r135'].replace(
        '"r412_verify.py"', '', 1))),
    ('CI 漏挂正向', lambda d: d.update(ci=d['ci'].replace(
        'run: python3 scripts/r412_verify.py\n', 'run: echo skip\n', 1))),
    ('CI 漏挂反向', lambda d: d.update(ci=d['ci'].replace(
        'python3 scripts/r412_verify.py --selftest', '', 1))),
    ('欠账未登记', lambda d: d.update(status=d['status'].replace('R412 低电保护', ''))),
    ('链路符号丢失', lambda d: d.update(**{
        'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('功能守恒基线被改', lambda d: d.update(baseline='{}')),
]


def check(s):
    e = []
    for k in FILES:
        if not s.get(k):
            e.append('缺文件: ' + k)
    if e:
        return e
    gate, prod, main, hub, taskui = s['gate'], s['producer'], s['main'], s['hub'], s['taskui']
    man, grad, r135, ci, status = s['manifest'], s['gradle'], s['r135'], s['ci'], s['status']
    gate_code = '\n'.join(l for l in gate.splitlines()
                          if not l.strip().startswith(('//', '*', '/*')))

    # ① 判定：严格 <20 / 读得到才判 / 零权限零依赖
    for tok in ['const val LIMIT_PCT = 20', 'valid && pct < LIMIT_PCT',
                '(level * 100) / scale', 'ACTION_BATTERY_CHANGED',
                'registerReceiver(\n                null, IntentFilter',
                '远端请求、试听和单段合成不受影响']:
        if tok not in gate:
            e.append('电量判定缺: ' + tok[:56])
    if 'BATTERY' in man.upper():
        e.append('清单多了电池权限（sticky 广播不需要）')
    if 'battery' in grad.lower() and 'BatteryGate' not in grad:
        e.append('构建脚本多了电池依赖')
    if 'android.permission.BATTERY' in gate:
        e.append('闸门文件里出现电池权限声明')

    # ② 四个入口齐全，且 start 兜底必须落在 busy=true 之前
    for at in ['at=enqueueOrStart', 'at=resumeQueue', 'at=autoResume', 'at=start']:
        if at not in prod:
            e.append('Producer 缺闸门: ' + at)
    i_start_gate = prod.find('"at=start · "')
    i_busy = prod.find('        busy = true')
    if i_start_gate < 0 or i_busy < 0:
        e.append('start 闸门 / busy=true 锚点找不到')
    elif i_start_gate > i_busy:
        e.append('start 闸门落在 busy=true 之后（会写档案 / 启服务）')
    if 'lastStartError = why' not in prod:
        e.append('拒因没回写 lastStartError（续跑会静默）')
    if prod.count('"BOOK_BATTERY_GATE"') < 4:
        e.append('四个闸门没都记日志')
    # 主循环里不许出现闸门：已经在跑的书不被打断
    body = prod[prod.find('    fun start('):]
    if 'BOOK_BATTERY_GATE' in body[body.find('scope.launch'):]:
        e.append('低电闸门渗进了制作主循环（会打断正在跑的书）')

    # ③ UI 反馈：续跑 / 队列 / 两处单章重做，都不能「点了没反应」或弹假成功
    if 'Toast.makeText(ctx, why, Toast.LENGTH_LONG).show()' not in main:
        e.append('MainActivity 续跑没给低电提示')
    if 'android.widget.Toast.makeText(c, why,' not in taskui:
        e.append('TaskUi 队列恢复没给低电提示')
    if hub.count('Producer.lastStartError?.let { why ->') != 2:
        e.append('ChapterHub 两处单章重做没都处理低电拒因')
    if '低电被拦时别再弹「已排重做」' not in hub:
        e.append('单章重做假成功提示没标注来源')

    # ④ 其余链路一律不许出现 BatteryGate
    for f in FORBIDDEN:
        if 'BatteryGate' in s.get('forbid:' + f, ''):
            e.append('闸门误挂到不该拦的链路: ' + f)

    # ⑤ 登记与守恒
    if '"r412_verify.py"' not in r135:
        e.append('现行清单漏登记 r412')
    if 'run: python3 scripts/r412_verify.py\n' not in ci:
        e.append('CI 漏挂 r412 正向')
    if 'python3 scripts/r412_verify.py --selftest' not in ci:
        e.append('CI 漏挂 r412 反向')
    if 'R412 低电保护' not in status:
        e.append('STATUS 没登记 R412 欠账')
    if '1.1.65' not in s['changelog']:
        e.append('CHANGELOG 没写 1.1.65 段')
    for p, toks in CHAIN.items():
        tt = s.get('chain:' + str(p.relative_to(ROOT)), '')
        for k in toks:
            if k not in tt:
                e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try:
        old = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('基线不可读: %s' % ex)
        old = {}
    live = live_counts()
    for f, cnt in old.items():
        if f not in live:
            e.append('功能守恒: 文件消失 ' + f)
            continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    e += logic()
    return e


def selftest():
    base = sources()
    ok = 0
    for name, mut in BAD:
        d = dict(base)
        mut(d)
        if check(d):
            ok += 1
        else:
            print('  反向注入漏放:', name)
    print('R412 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        print('R412 FAIL')
        for x in e:
            print(' -', x)
        return 1
    print('R412 PASS · 低电只拦电子书制作 / 阈值严格 <20 / 其余链路零误伤')
    print('  逻辑用例全绿 · 功能守恒零差异 · 链路符号全在')
    return 0


if __name__ == '__main__':
    sys.exit(main())
