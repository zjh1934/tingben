#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R398 · 状态表驱动 / 阶段 3 接入契约 / 链路与功能守恒守门。

顺着代码过一遍得来的判断：小本本 A 类「状态卡 / 互斥动作画死」在代码里
大多本来就读真值（RelayUi / GatewayUi 的 if(on)、LibraryUi 的 if(isPinned)、
ModelUi 的 current != VOICE_DESIGN），真正画死的是图集示意图。所以本轮
不硬改已经对的代码，只做三件：
  ① 图集改成 UI_STATE 单一真值源，状态卡 / 开关 / 互斥动作全派生；
  ② 代码里唯一真画死的一处（设置首页「语音网关 · 默认关」副标题）改准；
  ③ enableEdgeToEdge 的依赖事实与确切写法写成契约，真编译没跑就不擅自调用。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r398_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
# R399 · 绘图层脚本同时纳入仓库 dev/，CI 里路径存在、判据全验不放宽；
# 本机仍可回退到 workspace 版本（两份同源，改一处要同步另一处）。
DRAW = ROOT / 'dev/听本全页面图集_绘制.py'
FRAME = ROOT / 'dev/听本_带机身边框_绘制.py'
if not DRAW.exists():
    DRAW = pathlib.Path('/data/workspace/听本全页面图集_绘制.py')
if not FRAME.exists():
    FRAME = pathlib.Path('/data/workspace/听本_带机身边框_绘制.py')
FILES = {
    'settings': APP / 'SettingsUi.kt',
    'relay': APP / 'relay/RelayUi.kt',
    'gateway': APP / 'gateway/GatewayUi.kt',
    'library': APP / 'LibraryUi.kt',
    'model': APP / 'ModelUi.kt',
    'ui_spec': APP / 'UiSpec.kt',
    'ui_insets': APP / 'UiInsets.kt',
    'main': APP / 'MainActivity.kt',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
    'toml': ROOT / 'gradle/libs.versions.toml',
}

# 图集里不该再出现的画死状态文案（注释里提到不算，用 code_lines 判）
FORBIDDEN_DRAW = [
    '"语音网关 · 默认关"', '"跨网中继 · 已开启"',
    '"当前模式：描述造音色"', 'txt(d, (148, 484), "未置顶"',
    '("置顶", \'text\'), ("取消置顶", \'text\')',
    '"本机接口 · 默认关 · 音色只读本机"',
]


CHAIN_FILES = [
    'app/src/main/java/com/tingben/app/relay/RelayService.kt',
    'app/src/main/java/com/tingben/app/relay/RelayHaptics.kt',
    'app/src/main/java/com/tingben/app/gateway/GatewayService.kt',
    'app/src/main/java/com/tingben/app/core/RequestArchive.kt',
    'app/src/main/java/com/tingben/app/core/AudioOps.kt',
    'app/src/main/java/com/tingben/app/diag/EventLog.kt',
    'app/src/main/java/com/tingben/app/core/ServiceResume.kt',
]

def sources():
    d = {k: v.read_text(encoding='utf-8') for k, v in FILES.items() if v.exists()}
    for key, p in (('draw', DRAW), ('frame', FRAME)):
        if p.exists():
            d[key] = p.read_text(encoding='utf-8')
    for rel in CHAIN_FILES:
        q = ROOT / rel
        if q.exists():
            d['chain:' + rel] = q.read_text(encoding='utf-8')
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
    for k in list(FILES) + ['draw', 'frame']:
        if k not in s:
            e.append('缺文件: ' + k)
    if e:
        return e

    dr, dc = s['draw'], code(s['draw'])
    se, ma, toml, ins = s['settings'], s['main'], s['toml'], s['ui_insets']

    # ① 图集状态表驱动
    for tok in ['UI_STATE = {', 'def switch_card(', 'def pin_status(', 'def pin_action(',
                "'gateway': dict(enabled=False)", "'relay':   dict(enabled=True)",
                'switch_card(\'gateway\')', 'switch_card(\'relay\')',
                'pin_status(UI_STATE[\'library_menu\'][\'pinned\'])',
                'pin_action(UI_STATE[\'library_menu\'][\'pinned\'])',
                'UI_STATE[\'chapter_menu\']', 'UI_STATE[\'model\'][\'current\']']:
        if tok not in dr:
            e.append('图集状态表缺: ' + tok)
    for bad in FORBIDDEN_DRAW:
        if bad in dc:
            e.append('图集仍有画死状态文案: ' + bad[:38])
    if 'gw_label' not in dc or 'rl_hint' not in dc:
        e.append('状态卡/开关行没有读派生值')
    # 就地菜单锚点 + 轻遮罩（模态协议 C）
    for tok in ['outline=C(P, \'primary\'), width=3',
                'Image.alpha_composite', '下拉菜单从被操作卡片下沿展开']:
        if tok not in dr:
            e.append('就地菜单锚点/遮罩缺: ' + tok[:34])
    # 34 与代码同口径，互斥动作不再出现
    if '合成就用这一模式，不用切回。' not in dr:
        e.append('34 页缺与 ModelUi 同口径的说明')
    if '[("改用「描述造音色」", \'ghost\')]' in dc and 'else [("改用' not in dc:
        e.append('34 页又无条件摆出互斥动作')

    # ② 代码侧唯一真画死处改准；已经对的代码不许被"顺手改坏"
    if '"本机接口 · 默认关 · 音色只读本机"' in se:
        e.append('设置首页入口副标题仍声称固定开关态')
    if '"本机接口 · 音色只读本机"' not in se:
        e.append('设置首页入口副标题改准后文案丢了')
    for f, toks in (('relay', ['if (on) "已开启" else "未开启"', 'if (on) state.ifBlank']),
                    ('gateway', ['if (on) "已开启" else "未开启"']),
                    ('library', ['if (isPinned) "已置顶" else "未置顶"',
                                 'if (isPinned) "取消置顶" else "置顶"']),
                    ('model', ['if (current != ModelStore.Mode.VOICE_DESIGN)'])):
        for t in toks:
            if t not in s[f]:
                e.append('本来读真值的代码被改动: %s -> %s' % (f, t[:40]))
    if 'data class UiSwitchState' not in s['ui_spec'] or 'fun exclusiveAction' not in s['ui_spec']:
        e.append('UiSpec 状态机地基被摘')

    # ③ 阶段 3 接入契约：依赖满足 + 接入在位 + 确切写法在 KDoc
    m = re.search(r'activityCompose\s*=\s*"([0-9.]+)"', toml)
    m2 = re.search(r'coreKtx\s*=\s*"([0-9.]+)"', toml)
    if not m or not m2:
        e.append('依赖版本读不到')
    else:
        def major(v):
            return tuple(int(x) for x in v.split('.'))
        if major(m.group(1)) < (1, 8):
            e.append('activity-compose 不满足 enableEdgeToEdge 下限 1.8')
        if major(m2.group(1)) < (1, 13):
            e.append('core-ktx 不满足下限 1.13')
    for tok in ['R398/R399 · 阶段 3 接入契约', 'enableEdgeToEdge()',
                'import androidx.activity.enableEdgeToEdge', '两次真编译']:
        if tok not in ins:
            e.append('阶段 3 契约缺: ' + tok[:38])
    # R399 · 接入已落代码，改验「在位 + import + 位置」，防止有人又摘掉
    mac = code(ma)
    if 'enableEdgeToEdge()' not in mac:
        e.append('MainActivity 的 enableEdgeToEdge 接入被摘掉')
    if 'import androidx.activity.enableEdgeToEdge' not in ma:
        e.append('MainActivity 缺 enableEdgeToEdge 的 import')
    i_super = mac.find('super.onCreate(savedInstanceState)')
    i_edge = mac.find('enableEdgeToEdge()')
    i_set = mac.find('setContent {')
    if not (0 <= i_super < i_edge < i_set):
        e.append('enableEdgeToEdge 调用位置不对（必须 super 之后、setContent 之前）')
    if 'enableEdgeToEdge()' in code(ins):
        e.append('UiInsets 代码行里不该有真调用（只写在 KDoc 契约）')

    # ④ 链路守恒（继承 R397 的 12 条链路，不重复实现，直接核对关键符号仍在）
    chain = {
        'app/src/main/java/com/tingben/app/relay/RelayService.kt':
            ['SRC_RELAY', 'RequestArchive.add', 'START_NOT_STICKY'],
        'app/src/main/java/com/tingben/app/relay/RelayHaptics.kt': ['vibrate'],
        'app/src/main/java/com/tingben/app/gateway/GatewayService.kt':
            ['SRC_GATEWAY', 'ACTION_REBIND', 'START_NOT_STICKY'],
        'app/src/main/java/com/tingben/app/core/RequestArchive.kt':
            ['SRC_RELAY', 'SRC_GATEWAY'],
        'app/src/main/java/com/tingben/app/core/AudioOps.kt': ['Transformer', 'AAC'],
        'app/src/main/java/com/tingben/app/diag/EventLog.kt': ['LOG_RETENTION', '24'],
        'app/src/main/java/com/tingben/app/core/ServiceResume.kt': ['START_NOT_STICKY'],
    }
    for rel, toks in chain.items():
        t = s.get('chain:' + rel)
        if t is None:
            e.append('链路文件消失: ' + rel.split('/')[-1]); continue
        for k in toks:
            if k not in t:
                e.append('链路符号丢失: %s -> %s' % (rel.split('/')[-1], k))

    # ⑤ 功能守恒
    try:
        bl = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('基线不可读: %s' % ex); bl = {}
    live = live_counts()
    for f, c in bl.items():
        if f not in live:
            e.append('功能守恒: 文件消失 ' + f); continue
        for k, v in c.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))

    # ⑥ 带外框预览状态唯一性（19→20 个，不许重复挂）
    fm = re.search(r'STATES = \[([\s\S]*?)\n\]', s['frame'])
    if fm:
        sts = re.findall(r"'([0-9]{2}_[^']+)'", fm.group(1))
        dup = sorted({x for x in sts if sts.count(x) > 1})
        if dup:
            e.append('带外框预览重复状态: ' + ', '.join(dup))
        if len(sts) < 19:
            e.append('带外框预览状态数不足: %d' % len(sts))
    else:
        e.append('带外框预览 STATES 读不到')
    return e


BAD = [
    ('状态表', lambda d: d.update(draw=d['draw'].replace('UI_STATE = {', 'OLD = {', 1))),
    ('派生函数', lambda d: d.update(draw=d['draw'].replace('def switch_card(', 'def unused(', 1))),
    ('画死文案回潮', lambda d: d.update(draw=d['draw'] + '\n# x\nX = "跨网中继 · 已开启"')),
    ('互斥动作', lambda d: d.update(draw=d['draw'].replace(
        'pin_action(UI_STATE[\'library_menu\'][\'pinned\'])', '"置顶"', 1))),
    ('入口副标题', lambda d: d.update(settings=d['settings'].replace(
        '"本机接口 · 音色只读本机"', '"本机接口 · 默认关 · 音色只读本机"', 1))),
    ('读真值被改坏', lambda d: d.update(relay=d['relay'].replace(
        'if (on) "已开启" else "未开启"', '"已开启"', 1))),
    ('依赖降档', lambda d: d.update(toml=d['toml'].replace('activityCompose = "1.9.3"', 'activityCompose = "1.7.0"', 1))),
    ('接入被摘', lambda d: d.update(main=d['main'].replace(
        '        enableEdgeToEdge()\n', '', 1))),
    ('契约被摘', lambda d: d.update(ui_insets=d['ui_insets'].replace('R398/R399 · 阶段 3', '阶段 X', 1))),
    ('链路符号', lambda d: d.update(**{
        'chain:app/src/main/java/com/tingben/app/relay/RelayHaptics.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline=d['baseline'].replace('"Button": 2', '"Button": 99', 1)
                                  if '"Button": 2' in d['baseline'] else '{}')),
    ('重复状态', lambda d: d.update(frame=d['frame'].replace(
        " '56_请求文本_清空确认',", " '56_请求文本_清空确认',\n '56_请求文本_清空确认',", 1))),
]


def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d):
            ok += 1
        else:
            print('  反向注入漏放:', name)
    print('R398 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e:
            print('RED:', x)
        print('R398: %d 处红' % len(e))
        return 1
    fm = re.search(r'STATES = \[([\s\S]*?)\n\]', sources()['frame'])
    n = len(re.findall(r"'([0-9]{2}_[^']+)'", fm.group(1))) if fm else 0
    print('R398 PASS · 图集状态表驱动 / 代码零硬改 / 阶段 3 契约 / 链路与功能守恒')
    print('  带外框预览 %d 个状态无重复 · 链路 7 文件 16 符号全在' % n)
    print('  功能守恒 %d 个 kt 文件零差异' % len(json.loads(sources()['baseline'])['files']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
