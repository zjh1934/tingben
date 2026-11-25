#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R396 · UI 四件套守门（R395 综合回看后的第一版施工守门）。

守住五件事：
  ① 状态机：计数 / 状态 / 可用性由 state 派生，禁止画死常量；
  ② 语义色：两套 Pal 都有 btnSecondary/onBtnSecondary/btnTertiary/
     onBtnTertiary/btnTertiaryLine，8 组前景/容器过 WCAG 2.2 AA 4.5:1；
  ③ 布局契约：四向 inset 齐全且主外框 / 播放器真接入；
  ④ 模态协议：弹窗期间底层降透明 + 停命中、设计说明不进界面；
  ⑤ 功能守恒：逐 kt 文件比对 Button / OutlinedButton / TextButton /
     onClick / clickable 数量基线，UI 改动不许把回调带少；
     并守住 danger 只在确认弹窗最终按钮（常态批量动作不准警示实心）。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r396_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'ui_spec': APP / 'UiSpec.kt',
    'ui_insets': APP / 'UiInsets.kt',
    'theme': APP / 'Theme.kt',
    'main': APP / 'MainActivity.kt',
    'player': APP / 'PlayerSheet.kt',
    'archive': APP / 'ArchiveUi.kt',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
DRAW = pathlib.Path('/data/workspace/听本全页面图集_绘制.py')  # 可选，不存在则跳过


def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items() if v.exists()}


def code_lines(t):
    return '\n'.join(l for l in t.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/', '#')))


def hexes(block):
    return {k: v.upper() for k, v in
            re.findall(r'(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)', block)}


def pal_block(t, name):
    m = re.search(r'internal val ' + name + r'\s*=\s*Pal\((.*?)\n\)', t, re.S)
    return hexes(m.group(1)) if m else {}


def lum(h):
    ch = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    ch = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in ch]
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]


def ratio(a, b):
    x, y = sorted((lum(a), lum(b)), reverse=True)
    return (x + 0.05) / (y + 0.05)


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
    need = ['ui_spec', 'ui_insets', 'theme', 'main', 'player', 'archive', 'baseline']
    for k in need:
        if k not in s:
            e.append('缺文件: ' + k)
    if e:
        return e

    sp, ins, th = s['ui_spec'], s['ui_insets'], s['theme']
    ma, pl, ar = s['main'], s['player'], s['archive']
    spc, insc, arc = code_lines(sp), code_lines(ins), code_lines(ar)

    # ① 状态机
    for token in ['data class UiListState', 'data class UiSwitchState',
                  'val countLabel', 'val selectedLabel', 'val bulkEnabled',
                  'val statusLabel', 'fun exclusiveAction', 'fun dangerAllowed']:
        if token not in sp:
            e.append('状态机缺: ' + token)
    if '$itemCount' not in spc or '$selectedCount' not in spc:
        e.append('计数没有由 state 派生')
    if '!isEmpty' not in spc:
        e.append('空态动作未置灰（bulkEnabled 必须 = !isEmpty）')
    if re.search(r'Text\(\s*"已选 \d', arc):
        e.append('ArchiveUi 仍有画死的「已选 N」字面量')

    # ② 语义色 + 对比度
    lp, dp = pal_block(th, 'LightPal'), pal_block(th, 'DarkPal')
    fields = ['btnSecondary', 'onBtnSecondary', 'btnTertiary',
              'onBtnTertiary', 'btnTertiaryLine']
    for nm, p in (('LightPal', lp), ('DarkPal', dp)):
        for f in fields:
            if f not in p:
                e.append(nm + ' 缺语义色字段: ' + f)
    if dp.get('onBtnSecondary') != 'FFFFFF':
        e.append('深色次按钮前景必须是纯白（修 soft 深底 + 浅紫字像禁用）')
    if dp.get('btnSecondary') == dp.get('primarySoft'):
        e.append('深色次按钮底未提亮')
    pairs = [('onBtnSecondary', 'btnSecondary'),
             ('onBtnTertiary', 'btnTertiary'),
             ('text', 'card'), ('onPrimary', 'primary')]
    for nm, p in (('浅色', lp), ('深色', dp)):
        for fg, bg in pairs:
            if fg in p and bg in p:
                r = ratio(p[fg], p[bg])
                if r < 4.5:
                    e.append('%s %s/%s 对比 %.2f < 4.5:1' % (nm, fg, bg, r))
    for token in ['enum class UiRole', 'fun actionColors', 'UiRole.DANGER']:
        if token not in sp:
            e.append('语义色角色缺: ' + token)

    # ③ 布局契约
    for token in ['.statusBarsPadding()', '.displayCutoutPadding()',
                  '.navigationBarsPadding()', '.imePadding()',
                  'fun Modifier.tbContentInsets', 'fun Modifier.tbBottomActionInsets',
                  'fun Modifier.tbListInsets']:
        # 用去注释后的代码判断：注释里提到旧形态不算接入
        if token not in insc:
            e.append('布局契约缺: ' + token)
    if 'INSET_SIDES' not in sp or 'GESTURE_BAR_DP = 24' not in spc:
        e.append('设备层契约常量缺')
    if 'tbContentInsets()' not in ma:
        e.append('主外框没接入 tbContentInsets()')
    if 'tbContentInsets()' not in pl:
        e.append('播放器全屏没接入 tbContentInsets()')

    # ④ 模态协议
    for token in ['SCRIM_ALPHA', 'BACKGROUND_DIM_WHEN_MODAL',
                  'HIT_TEST_WHEN_MODAL = false', 'DESIGN_NOTE_IN_UI = false']:
        if token not in sp:
            e.append('模态协议缺: ' + token)

    # ⑤ 功能守恒 + danger 约束
    try:
        bl = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('功能守恒基线不可读: %s' % ex)
        bl = {}
    live = live_counts()
    for f, counts in bl.items():
        if f not in live:
            e.append('功能守恒: 文件消失 ' + f)
            continue
        for k, v in counts.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' %
                         (f, k, v, live[f].get(k)))
    if re.search(r'fun dangerAllowed\(inConfirmDialog: Boolean\): Boolean\s*=\s*true', spc):
        e.append('dangerAllowed 恒 true，常态也允许警示色')
    # 常态清空入口不得 warn 实心；确认弹窗最终按钮仍保留 warn
    seg = ar[ar.find('一键清空全部文本') - 400:ar.find('一键清空全部文本') + 200]
    if 'color = palette.warn' in seg:
        e.append('常态「一键清空全部文本」仍在用警示色')
    if 'actionColors(UiRole.TERTIARY, palette)' not in seg:
        e.append('常态批量动作未走 TERTIARY 语义角色')
    if 'containerColor = palette.warn' not in arc:
        e.append('确认弹窗最终按钮应保留 DANGER（warn）')
    # 新文件不许碰业务
    for biz in ['ChapterPlan', 'SegPlan', 'ClipSynth', 'RequestArchive',
                'EventLog', 'Prefs.']:
        if biz in spc or biz in insc:
            e.append('UI 真值源里混进了业务符号: ' + biz)

    # 可选：图集设备层
    if DRAW.exists():
        dr = DRAW.read_text(encoding='utf-8')
        for token in ['def device_shell', '小白条', '挖孔', 'INSET']:
            if token not in dr:
                e.append('图集绘制脚本缺设备层: ' + token)
    return e


BAD = [
    ('状态机', lambda d: d.update(ui_spec=d['ui_spec'].replace('val bulkEnabled', 'val unused'))),
    ('计数派生', lambda d: d.update(ui_spec=d['ui_spec'].replace('$selectedCount', '3'))),
    ('对比度', lambda d: d.update(theme=d['theme'].replace(
        'onBtnSecondary = Color(0xFFFFFFFF)', 'onBtnSecondary = Color(0xFF3D4A72)'))),
    ('语义字段', lambda d: d.update(theme=d['theme'].replace(
        'btnTertiaryLine = Color(0xFF5C6A85)', '', 1))),
    ('inset 四向', lambda d: d.update(ui_insets=d['ui_insets'].replace(
        '.displayCutoutPadding()', ''))),
    ('主外框接入', lambda d: d.update(main=d['main'].replace(
        '.tbContentInsets()', '.statusBarsPadding()'))),
    ('播放器接入', lambda d: d.update(player=d['player'].replace(
        '.tbContentInsets()', '.navigationBarsPadding()'))),
    ('模态协议', lambda d: d.update(ui_spec=d['ui_spec'].replace(
        'HIT_TEST_WHEN_MODAL = false', 'HIT_TEST_WHEN_MODAL = true'))),
    ('功能守恒', lambda d: d.update(baseline=d['baseline'].replace(
        '"TextButton": 2', '"TextButton": 99', 1) if '"TextButton": 2' in d['baseline']
        else __import__('json').dumps({'version': 'R396', 'files': {
            'ArchiveUi.kt': {'Button': 99}}}))),
    ('danger', lambda d: d.update(archive=d['archive'].replace(
        'actionColors(UiRole.TERTIARY, palette)', 'palette.warn'))),
    ('业务隔离', lambda d: d.update(ui_spec=d['ui_spec'] + '\ninternal val leaked = "ClipSynth"')),
    ('基线', lambda d: d.update(baseline='{}')),
]


def selftest():
    base = sources()
    ok = 0
    for name, mut in BAD:
        d = dict(base)
        mut(d)
        errs = check(d)
        if errs:
            ok += 1
        else:
            print('  反向注入漏放:', name)
    print('R396 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    s = sources()
    errs = check(s)
    if errs:
        for x in errs:
            print('RED:', x)
        print('R396: %d 处红' % len(errs))
        return 1
    lp, dp = pal_block(s['theme'], 'LightPal'), pal_block(s['theme'], 'DarkPal')
    print('R396 PASS · 四件套 / 功能守恒 / 模态 / 设备层')
    for nm, p in (('浅色', lp), ('深色', dp)):
        for fg, bg in [('onBtnSecondary', 'btnSecondary'),
                       ('onBtnTertiary', 'btnTertiary'),
                       ('text', 'card'), ('onPrimary', 'primary')]:
            print('  %s %s/%s %.2f:1' % (nm, fg, bg, ratio(p[fg], p[bg])))
    print(' 功能守恒 %d 个 kt 文件零差异' % len(json.loads(s['baseline'])['files']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
