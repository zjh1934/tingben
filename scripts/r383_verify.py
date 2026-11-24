#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R383 · 真机日志实证修复：两个设置二级页闪退（嵌套滚动 / 无限高约束）。

根因：SettingsUi 外壳是 Column(fillMaxSize().verticalScroll())，R381 给
GatewayScreen / RelayScreen 又各加了一层 verticalScroll，内层滚动容器收到
infinity 最大高度，Compose 在 measure 阶段抛 IllegalStateException。
修法：页面只放内容，滚动交给外层唯一一层。

钉住：① 两个二级页零自带滚动容器（import 与调用串都不许有）
      ② 设置页外壳恰好一处 verticalScroll，弹层 LazyColumn 必须有界高
      ③ 两个页面仍是 R381 重设计的卡片形态，没被「顺手改回老页面」
      ④ 诊断抬头不再写「不记随手点击流水」（与 R147 UI 埋点事实打架）

纯静态语义判据，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r383_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'settings': APP / 'SettingsUi.kt',
    'relay': APP / 'relay/RelayUi.kt',
    'gateway': APP / 'gateway/GatewayUi.kt',
    'log': APP / 'diag/EventLog.kt',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def code_lines(text):
    """去掉纯注释行再查调用串：R383 的修复说明里写了 verticalScroll() 字样，
    不能被当成回潮。import 行单独精确查。"""
    return '\n'.join(l for l in text.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*')))

def check(s):
    e = []
    settings, relay, gateway, log = s['settings'], s['relay'], s['gateway'], s['log']
    rs, gs = code_lines(relay), code_lines(gateway)

    # ① 两个二级页不许自带任何滚动容器
    for name, txt in (('中继页', relay), ('网关页', gateway)):
        for imp in ('import androidx.compose.foundation.verticalScroll',
                    'import androidx.compose.foundation.rememberScrollState',
                    'import androidx.compose.foundation.lazy.LazyColumn'):
            if imp in txt:
                e.append(name + ' 又引入了滚动 import：' + imp.rsplit('.', 1)[-1])
    for name, code in (('中继页', rs), ('网关页', gs)):
        if '.verticalScroll(' in code:
            e.append(name + ' 又套了内层 verticalScroll（无限高约束闪退）')
        if 'LazyColumn(' in code:
            e.append(name + ' 又套了 LazyColumn')

    # ② 设置页外壳恰好一处滚动，弹层列表必须有界
    outer = 'Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())'
    if outer not in settings:
        e.append('设置页外壳唯一那层滚动被摘（页面会变成不可滚）')
    if settings.count('verticalScroll(rememberScrollState())') != 1:
        e.append('设置页滚动容器不是恰好一层（多层 = 无限高约束）')
    if 'LazyColumn(Modifier.heightIn(max = 380.dp))' not in settings:
        e.append('弹层 LazyColumn 丢了有界高（无限高约束）')

    # ③ 仍是 R381 重设计形态，没被改回老页面
    for name, txt in (('中继页', relay), ('网关页', gateway)):
        if 'Column(Modifier.fillMaxWidth().padding(HiPagePad),' not in txt:
            e.append(name + ' 不是 R383 的无自滚 Column 形态')
        for k in ('CardBox {', 'Spacer(Modifier.height(72.dp))',
                  'horizontalArrangement = Arrangement.spacedBy(8.dp)'):
            if k not in txt:
                e.append(name + ' R381 重设计被摘：' + k[:30])

    # ④ 抬头口径与 UI 埋点事实对齐
    if '不记随手点击流水' in log:
        e.append('诊断抬头仍写「不记随手点击流水」，与 R147 UI 埋点事实打架')
    if '记链路节点、界面导航、错误码、耗时与体积' not in log:
        e.append('诊断抬头没按事实改准')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('中继页把内层滚动加回来', 'relay',
             'Column(Modifier.fillMaxWidth().padding(HiPagePad),',
             'Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())\n        .padding(HiPagePad),'),
            ('网关页把内层滚动加回来', 'gateway',
             'Column(Modifier.fillMaxWidth().padding(HiPagePad),',
             'Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())\n        .padding(HiPagePad),'),
            ('中继页偷偷 import 滚动', 'relay',
             'import androidx.compose.foundation.shape.CircleShape',
             'import androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.verticalScroll'),
            ('网关页偷偷 import 滚动', 'gateway',
             'import androidx.compose.foundation.shape.CircleShape',
             'import androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.verticalScroll'),
            ('设置页外壳滚动被摘', 'settings',
             'Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())',
             'Column(Modifier.fillMaxSize()'),
            ('弹层 LazyColumn 丢了有界高', 'settings',
             'LazyColumn(Modifier.heightIn(max = 380.dp))', 'LazyColumn(Modifier.fillMaxWidth())'),
            ('中继页改回老页面', 'relay', 'Spacer(Modifier.height(72.dp))', 'Spacer(Modifier.height(0.dp))'),
            ('网关页改回老页面', 'gateway', 'CardBox {', 'Column {'),
            ('抬头旧口径回潮', 'log',
             '记链路节点、界面导航、错误码、耗时与体积',
             '只记链路节点 / 错误码 / 耗时 / 体积，不记随手点击流水'),
        ]
        bad = 0
        for name, key, old, new in cases:
            s2 = dict(s)
            if old not in s2[key]:
                bad += 1; print('  注入锚失效', name); continue
            s2[key] = s2[key].replace(old, new)
            if not check(s2):
                bad += 1; print('  漏放', name)
            else:
                print('  拦下', name)
        print('R383 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R383 %s · 两级滚动修复 / 设置页唯一滚动 / 两页形态守恒' %
          ('FAIL: ' + '; '.join(e) if e else 'PASS'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
