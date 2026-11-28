#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R416 · 维护轮守门（出包 1.1.69）。

用户原话：「出包维护GitHub维护oico」→ 出包 + GitHub 页面 + aoci 索引一起维护，
本地自主推进，发现问题自己修，不追问。

顺着代码过链路查到的真问题：
  A 异常路径真值断链：run() catch 与 abortBeforeStart() 把 _state / TaskResume
    档案写成 0/0；后者还落盘。续跑时临时缺模型/断网报错，下一轮 start()
    的 prior 就是 0/0，R414/R415 修好的开局又被打回原形。
  B 文档基线自相矛盾：STATUS 顶部 1.1.63、表格 1.1.57，而 11-28 11:42:09
    诊断日志「环境」块实证是 1.1.66；ROADMAP 也停在 1.1.63。
  C 调用页 busy 不显示状态里已有的 retry_after_ms，只说「稍候」。

核实后排除（不硬找）：docs/使用指南.md 用 Contents API 读到 84 字是解析层
截断，Blobs API 实读 4398 字节 / 1940 字，与本地 blob sha 完全一致；Pages
三处 --check 原本就 0 差异；busy 状态确实带 reason 与 retry_after_ms。

守：异常路径带真值 / 文档基线同源 / 调用页秒数 / 版本四处对齐 /
    零新增权限 / 功能守恒 / 链路符号 / 现行清单与 CI 登记 / aoci 索引。
用法：python3 scripts/r416_verify.py [--selftest]
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'producer': APP / 'core/Producer.kt',
    'relay': ROOT / 'docs/pages/relay.html',
    'status': ROOT / 'STATUS.md',
    'roadmap': ROOT / 'ROADMAP.md',
    'readme': ROOT / 'README.md',
    'version': ROOT / 'version.txt',
    'changelog': ROOT / 'CHANGELOG.md',
    'aoci': ROOT / 'aoci.code.txt',
    'manifest': ROOT / 'app/src/main/AndroidManifest.xml',
    'gradle': ROOT / 'app/build.gradle.kts',
    'r135': ROOT / 'scripts/r135_verify.py',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'core/TaskResume.kt': ['fun byBook('],
    APP / 'core/SynthArbiter.kt': ['setRemoteBatch('],
    APP / 'relay/RelayService.kt': ['busyStatus('],
    APP / 'core/BatteryGate.kt': ['fun bookBlockReason('],
    APP / 'core/AudioOps.kt': ['CHAPTER_ENCODE_PULSE'],
}


def sources():
    d = {k: (v.read_text(encoding='utf-8', errors='replace') if v.exists() else '')
         for k, v in FILES.items()}
    for p in CHAIN:
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


# ── 纯内存复刻：把「真值断链」复现出来做对照 ──

def error_progress(state, legacy=False):
    """复刻 run() catch 的 Progress。state=(done,total)。"""
    return (0, 0) if legacy else (state[0], state[1])


def blocked_task(state, legacy=False):
    """复刻 abortBeforeStart 写进 TaskResume 的 done/total。"""
    return (0, 0) if legacy else (state[0], state[1])


def next_start_prior(state, legacy=False):
    """复刻下一轮 start() 读到的 prior（决定开局归不归零）。"""
    return blocked_task(state, legacy)


def busy_text(retry_ms, legacy=False):
    """复刻 relay.html 的 busy 文案。"""
    if legacy:
        return '手机正处理另一批合成（引擎占用），请求留着不丢，忙完自动补…'
    sec = max(1, -(-retry_ms // 1000)) if retry_ms else 60
    return '手机正处理另一批合成（引擎占用），请求留着不丢，约 %d 秒后自动补…' % sec


def logic():
    e = []
    s = (203, 26678)
    # ① 旧行为必须能复现（否则守门是假绿）
    if error_progress(s, legacy=True) != (0, 0):
        e.append('逻辑：旧 catch 清 0/0 没复现')
    if blocked_task(s, legacy=True) != (0, 0):
        e.append('逻辑：旧档案清 0/0 没复现')
    if next_start_prior(s, legacy=True) != (0, 0):
        e.append('逻辑：旧下一轮开局归零没复现')
    # ② 新行为保留真值
    if error_progress(s) != s or blocked_task(s) != s or next_start_prior(s) != s:
        e.append('逻辑：新异常路径丢了恢复真值')
    if error_progress((0, 0)) != (0, 0):
        e.append('逻辑：全新书不该凭空多段')
    # ③ busy 秒数（60000 → 60 秒；缺省 60 秒）
    if '秒' in busy_text(60000, legacy=True):
        e.append('逻辑：旧 busy 文案没复现无秒数')
    if '60 秒' not in busy_text(60000):
        e.append('逻辑：busy 没把 retry_after_ms 换成秒')
    if '60 秒' not in busy_text(0):
        e.append('逻辑：缺 retry_after_ms 没给 60 秒兜底')
    if '2 秒' not in busy_text(1500):
        e.append('逻辑：非整秒没向上取整')
    return e


# R417 · 版本/基线不再绑死字面量：发版轮改 version.txt 时判据自动跟着走
VER = (ROOT / 'version.txt').read_text(encoding='utf-8').strip()
_m = re.search(r'用户真机基线 `(\d+\.\d+\.\d+)`',
               '\n'.join((ROOT / 'STATUS.md').read_text(encoding='utf-8').splitlines()[:12]))
BV = _m.group(1) if _m else '0.0.0'

BAD = [
    ('catch 又清 0/0', lambda d: d.update(producer=d['producer'].replace(
        'val p0 = _state.value\n                    _state.value = Progress(false, "出错了", p0.done, p0.total, 0,',
        'val p0 = _state.value\n                    _state.value = Progress(false, "出错了", 0, 0, 0,', 1))),
    ('catch 不带 p0', lambda d: d.update(producer=d['producer'].replace(
        'val p0 = _state.value', '', 1))),
    ('abort 又清 0/0', lambda d: d.update(producer=d['producer'].replace(
        'val kept = _state.value\n        _state.value = Progress(false, stage, kept.done, kept.total, 0, msg)',
        'val kept = _state.value\n        _state.value = Progress(false, stage, 0, 0, 0, msg)', 1))),
    ('abort 档案又清 0/0', lambda d: d.update(producer=d['producer'].replace(
        'done = kept.done, total = kept.total,', 'done = 0, total = 0,', 1))),
    ('abort 不取 kept', lambda d: d.update(producer=d['producer'].replace(
        'val kept = _state.value', '', 1))),
    ('R175 blocked 阶段被摘', lambda d: d.update(producer=d['producer'].replace(
        'phase = "blocked"', 'phase = "running"', 1))),
    ('STATUS 顶部基线回潮', lambda d: d.update(status=d['status'].replace(
        '用户真机基线 `%s`' % BV, '用户真机基线 `1.1.57`', 1))),
    ('STATUS 表格基线回潮', lambda d: d.update(status=d['status'].replace(
        '用户真机安装的 `%s`' % BV, '用户真机安装的 `1.1.63`', 1))),
    ('ROADMAP 基线回潮', lambda d: d.update(roadmap=d['roadmap'].replace(
        '用户真机基线 `%s`' % BV, '用户真机基线 `1.1.63`', 1))),
    ('busy 秒数被摘', lambda d: d.update(relay=d['relay'].replace(
        'j.retry_after_ms?Math.ceil(j.retry_after_ms/1000):60', '0', 1))),
    ('busy 文案又只说稍候', lambda d: d.update(relay=d['relay'].replace(
        "，约 '+sec+' 秒后自动补…", '，忙完自动补…', 1))),
    ('版本没升', lambda d: d.update(version='1.1.0\n')),
    ('README 没写本版说明行', lambda d: d.update(readme=d['readme'].replace(
        '> %s ' % VER, '> ', 1))),
    ('CHANGELOG 缺本版段', lambda d: d.update(changelog=d['changelog'].replace(
        '## [%s]' % VER, '', 1))),
    ('aoci 漏登记 r416', lambda d: d.update(aoci=d['aoci'].replace(
        'r416_verify.py[', 'gone.py[', 1))),
    ('现行清单漏登记', lambda d: d.update(r135=d['r135'].replace('"r416_verify.py"', '', 1))),
    ('CI 漏挂正向', lambda d: d.update(ci=d['ci'].replace(
        'run: python3 scripts/r416_verify.py\n', 'run: echo skip\n', 1))),
    ('CI 漏挂反向', lambda d: d.update(ci=d['ci'].replace(
        'python3 scripts/r416_verify.py --selftest', '', 1))),
    ('新增电池权限', lambda d: d.update(manifest=d['manifest'].replace(
        'android.permission.INTERNET', 'android.permission.INTERNET" />\n'
        '    <uses-permission android:name="android.permission.BATTERY_STATS', 1))),
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
    pr, rl, st, rm, rd = (s['producer'], s['relay'], s['status'],
                          s['roadmap'], s['readme'])

    # ① 异常路径真值同源
    for tok in ['val p0 = _state.value',
                'Progress(false, "出错了", p0.done, p0.total, 0,',
                'val kept = _state.value',
                'Progress(false, stage, kept.done, kept.total, 0, msg)',
                'done = kept.done, total = kept.total,',
                'phase = "blocked"']:
        if tok not in pr:
            e.append('异常路径真值缺: ' + tok[:62])
    if 'Progress(false, "出错了", 0, 0, 0,' in pr:
        e.append('catch 回潮清 0/0')
    if 'done = 0, total = 0,' in pr:
        e.append('abort 档案回潮清 0/0')

    # ② 文档基线同源：真值从 STATUS 顶部抽，表格与 ROADMAP 必须同一个值。
    # R417 迁移：R416 当时绑死 1.1.66，发新版就恒红；改成动态但判据不放宽
    # （顶部/表格/ROADMAP 三处同源 + 旧基线不许回潮 + 版本号必须格式合法）。
    head = '\n'.join(st.splitlines()[:12])
    rm_head = '\n'.join(rm.splitlines()[:6])
    mb = re.search(r'用户真机基线 `(\d+\.\d+\.\d+)`', head)
    if not mb:
        e.append('STATUS 顶部没写真机基线')
    else:
        bv = mb.group(1)
        if head.count('`%s`' % bv) < 2:
            e.append('STATUS 顶部与表格基线没同源')
        if '用户真机基线 `%s`' % bv not in rm_head:
            e.append('ROADMAP 基线不同源')
    for oldv in ('1.1.57', '1.1.63'):
        if ('`%s`' % oldv) in head or ('`%s`' % oldv) in rm_head:
            e.append('旧基线回潮: ' + oldv)

    # ③ 调用页 busy 秒数
    for tok in ['j.retry_after_ms?Math.ceil(j.retry_after_ms/1000):60',
                "约 '+sec+' 秒后自动补…"]:
        if tok not in rl:
            e.append('busy 秒数缺: ' + tok[:62])
    if "j.reason||'稍候'" not in rl and "j.reason||" not in rl:
        e.append('busy 原有 reason 兜底被改坏')

    # ④ 版本与登记（动态对齐 version.txt，R417 迁移）
    ver = s['version'].strip()
    if not re.fullmatch(r'\d+\.\d+\.\d+', ver):
        e.append('version.txt 格式不对')
    for k, name in [('readme', 'README'), ('status', 'STATUS'),
                    ('roadmap', 'ROADMAP')]:
        if ver not in s[k]:
            e.append(name + ' 没对齐 ' + ver)
    if ('> %s ' % ver) not in rd:
        e.append('README 没写本版说明行')
    if ('## [%s]' % ver) not in s['changelog']:
        e.append('CHANGELOG 缺本版段')
    if 'r416_verify.py[' not in s['aoci']:
        e.append('aoci 索引漏登记 r416')
    if '"r416_verify.py"' not in s['r135']:
        e.append('现行清单漏登记 r416')
    if 'run: python3 scripts/r416_verify.py\n' not in s['ci']:
        e.append('CI 漏挂 r416 正向')
    if 'python3 scripts/r416_verify.py --selftest' not in s['ci']:
        e.append('CI 漏挂 r416 反向')

    # ⑤ 守恒
    if 'BATTERY' in s['manifest'].upper():
        e.append('多了电池权限')
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
    print('R416 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        print('R416 FAIL')
        for x in e:
            print(' -', x)
        return 1
    print('R416 PASS · 异常路径真值同源 / 文档基线一致 / busy 秒数 / 版本对齐')
    print('  零权限 · 功能守恒零差异 · 链路符号全在 · 清单与 CI 已登记')
    return 0


if __name__ == '__main__':
    sys.exit(main())
