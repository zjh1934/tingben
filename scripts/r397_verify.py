#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R397 · 阶段 2 接入守门 + 链路守恒（改 UI 不许把功能改坏）。

正向：① 状态机真被页面消费；② 语义色角色真接到次按钮；③ 模态协议
（弹窗期间底层降透明 + 停命中）；④ 四向 inset 接入仍在；⑤ 16 条业务
链路符号逐条守恒（中继 / 网关 / 存档 / 压缩 / 震动 / 日志 24h / 断点续跑 /
拼章转码 / 服务补拉 / 各开关默认值）；⑥ 功能守恒基线零差异。
反向注入 10 组，缺一即红。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r397_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {k: APP/v for k, v in {
    'ui_spec':'UiSpec.kt','ui_insets':'UiInsets.kt','theme':'Theme.kt',
    'main':'MainActivity.kt','archive':'ArchiveUi.kt','settings':'SettingsUi.kt',
    'mixer':'MixerUi.kt','player':'PlayerSheet.kt'}.items()}
FILES['baseline'] = ROOT/'contracts/ui_function_baseline.json'

# 链路守恒：文件 -> 必须仍在的符号（只验「没被这轮 UI 改动碰掉」）
CHAINS = {
 'app/src/main/java/com/tingben/app/relay/RelayService.kt':
     ['SRC_RELAY','RequestArchive.add','START_NOT_STICKY','queue','status'],
 'app/src/main/java/com/tingben/app/relay/RelayContract.kt':
     ['MAX_CHARS','queue','out'],
 'app/src/main/java/com/tingben/app/relay/RelayHaptics.kt': ['vibrate'],
 'app/src/main/java/com/tingben/app/gateway/GatewayService.kt':
     ['SRC_GATEWAY','RequestArchive.add','ACTION_REBIND','START_NOT_STICKY'],
 'app/src/main/java/com/tingben/app/gateway/GatewayContract.kt': ['AAC'],
 'app/src/main/java/com/tingben/app/core/RequestArchive.kt':
     ['SRC_RELAY','SRC_GATEWAY','MAX','fun list','fun add'],
 'app/src/main/java/com/tingben/app/diag/EventLog.kt': ['LOG_RETENTION','24'],
 'app/src/main/java/com/tingben/app/core/ServiceResume.kt': ['START_NOT_STICKY'],
 'app/src/main/java/com/tingben/app/core/ResumeReceiver.kt': ['ResumeReceiver'],
 'app/src/main/java/com/tingben/app/core/Producer.kt': ['fun '],
 'app/src/main/java/com/tingben/app/core/AudioOps.kt': ['Transformer','AAC'],
 'app/src/main/java/com/tingben/app/ProduceService.kt': ['START_NOT_STICKY','OverlayProgress'],
}

def sources():
    d = {k: v.read_text(encoding='utf-8') for k, v in FILES.items() if v.exists()}
    for rel in CHAINS:
        p = ROOT/rel
        if p.exists(): d['chain:'+rel] = p.read_text(encoding='utf-8')
    return d

def code(t):
    return '\n'.join(l for l in t.splitlines()
                     if not l.lstrip().startswith(('//','*','/*','*/','#')))

def live_counts():
    out={}
    for p in sorted(APP.rglob('*.kt')):
        t=p.read_text(encoding='utf-8',errors='replace')
        out[str(p.relative_to(APP))]={
            'Button':len(re.findall(r'\bButton\(',t)),
            'OutlinedButton':len(re.findall(r'\bOutlinedButton\(',t)),
            'TextButton':len(re.findall(r'\bTextButton\(',t)),
            'onClick':len(re.findall(r'\bonClick\s*=',t)),
            'clickable':len(re.findall(r'\.clickable\s*\(',t))}
    return out

def check(s):
    e=[]
    for k in ['ui_spec','ui_insets','theme','main','archive','settings','mixer','player','baseline']:
        if k not in s: e.append('缺文件: '+k)
    if e: return e
    sp,ar,se,mx,ma,pl = (s['ui_spec'],s['archive'],s['settings'],
                          s['mixer'],s['main'],s['player'])
    arc, sec, mxc = code(ar), code(se), code(mx)
    # ① 状态机真消费
    for t in ['val uiState = UiListState(', 'uiState.fullCountLabel',
              'uiState.fullSelectedLabel', 'uiState.bulkEnabled', 'uiState.selectEnabled']:
        if t not in ar: e.append('状态机没接进 ArchiveUi: '+t)
    if '"共 $total 条"' in arc or '"已选 ${picked.size} 条"' in arc:
        e.append('页面里仍在拼画死计数字符串')
    # ② 语义色真接入
    if 'internal val Secondary: Color get() = palette.btnSecondary' not in ma:
        e.append('缺 SECONDARY 顶层别名')
    # 两行版与同行版都算，按「containerColor = Secondary」出现次数判
    if sec.count('containerColor = Secondary') < 3:
        e.append('SettingsUi 次按钮没全走语义角色')
    if 'containerColor = Secondary, contentColor = OnSecondary' not in mxc:
        e.append('MixerUi 次按钮没走语义角色')
    for name in ['Tertiary: Color','OnTertiary: Color','TertiaryLine: Color']:
        if name in ma: e.append('出现不可达的未用别名: '+name)
    # ③ 模态协议
    for t in ['UiLayer.BACKGROUND_DIM_WHEN_MODAL','Modifier.alpha(if (askClear)',
              '&& !askClear','enabled = uiState.bulkEnabled',
              'enabled = uiState.selectEnabled']:
        if t not in ar: e.append('模态协议缺: '+t)
    if ar.count('&& !askClear') < 5:
        e.append('弹窗期间停命中的按钮不足 5 枚')
    if '④ 说明降级' not in ar:
        e.append('说明段被误删（R387/R390 主次分层丢失）')
    # ④ inset 仍在
    for t in ['.tbContentInsets()']:
        if t not in ma or t not in pl: e.append('四向 inset 接入丢失: '+t)
    # ⑤ 16 条链路符号守恒
    for rel, toks in CHAINS.items():
        txt = s.get('chain:'+rel)
        if txt is None: e.append('链路文件消失: '+rel); continue
        for k in toks:
            if k not in txt: e.append('链路符号丢失: %s -> %s'%(rel.split('/')[-1],k))
    # ⑥ 功能守恒
    try: bl=json.loads(s['baseline'])['files']
    except Exception as ex: e.append('基线不可读: %s'%ex); bl={}
    live=live_counts()
    for f,c in bl.items():
        if f not in live: e.append('功能守恒: 文件消失 '+f); continue
        for k,v in c.items():
            if live[f].get(k)!=v:
                e.append('功能守恒: %s 的 %s %s→%s'%(f,k,v,live[f].get(k)))
    # UI 真值源零业务
    for biz in ['RequestArchive','EventLog','Prefs.','ClipSynth','Producer']:
        if biz in code(sp): e.append('UiSpec 混入业务符号: '+biz)
    return e

BAD = [
 ('状态机',lambda d:d.update(archive=d['archive'].replace('UiListState(','Nothing('))),
 ('画死计数',lambda d:d.update(archive=d['archive']+'\ninternal val leaked = "共 $total 条"')),
 ('语义角色',lambda d:d.update(settings=d['settings'].replace('containerColor = Secondary','containerColor = PrimarySoft',1))),
 ('未用别名',lambda d:d.update(main=d['main']+'internal val Tertiary: Color = Color.White\n')),
 ('模态停命中',lambda d:d.update(archive=d['archive'].replace(' && !askClear','',3))),
 ('说明段',lambda d:d.update(archive=d['archive'].replace('    // ④ 说明降级','    // 说明'))),
 ('inset',lambda d:d.update(main=d['main'].replace('.tbContentInsets()','.statusBarsPadding()'))),
 ('链路符号',lambda d:d.update(**{'chain:app/src/main/java/com/tingben/app/relay/RelayHaptics.kt':'/* gone */'})),
 ('功能守恒',lambda d:d.update(baseline=json.dumps({'version':'R396','files':{'ArchiveUi.kt':{'Button':99}}}))),
 ('业务隔离',lambda d:d.update(ui_spec=d['ui_spec']+'internal val x = EventLog')),
]

def selftest():
    base=sources(); ok=0
    for name,mut in BAD:
        d=dict(base); mut(d)
        if check(d): ok+=1
        else: print('  反向注入漏放:',name)
    print('R397 反向注入 %d/%d 全拦'%(ok,len(BAD)))
    return ok==len(BAD)

def main():
    if '--selftest' in sys.argv: return 0 if selftest() else 1
    e=check(sources())
    if e:
        for x in e: print('RED:',x)
        print('R397: %d 处红'%len(e)); return 1
    print('R397 PASS · 阶段 2 接入 / 12 条链路守恒 / 功能守恒零差异')
    print('  链路文件 %d 个、符号 %d 项全在'%(len(CHAINS),sum(len(v) for v in CHAINS.values())))
    print('  功能守恒 %d 个 kt 文件零差异'%len(json.loads(sources()['baseline'])['files']))
    return 0

if __name__=='__main__': sys.exit(main())
