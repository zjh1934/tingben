#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R388 · 修诊断日志里那个小别扭：`GATEWAY_BIND` 紧跟 `GATEWAY_OFF`。

真因不是「别扭」而是 bug：切「允许局域网」原本无条件 stop+start，
ACTION_STOP 会记一行「用户关闭语音网关」；更要命的是网关本来没开时
拨一下绑定开关，也会平白留下这条假 OFF（start 又因 !enabled 立刻自杀）。

修法：加 ACTION_REBIND，服务开着才按新地址重开监听；没开只存 Prefs，
不碰服务、不记 OFF。顺带把订阅源 self 从有 12h 缓存的 jsdelivr 改成实际生效的 raw。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r387_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    'gw': ROOT / 'app/src/main/java/com/tingben/app/gateway/GatewayService.kt',
    'ui': ROOT / 'app/src/main/java/com/tingben/app/gateway/GatewayUi.kt',
    'publish': ROOT / 'scripts/publish_apk.py',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def code_lines(text):
    """滤掉注释行再查调用串，避免修复说明被误判成回潮。"""
    return '\n'.join(l for l in text.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/')))

def check(s):
    e = []
    gw, ui, pub = s['gw'], s['ui'], s['publish']
    gw_code, ui_code = code_lines(gw), code_lines(ui)

    # ① 重绑通道独立存在
    for k in ('const val ACTION_REBIND', 'fun rebind(c: Context)',
              'setAction(ACTION_REBIND)', 'startForegroundService(i)'):
        if k not in gw_code:
            e.append('GatewayService 缺 REBIND 通道：' + k)
    if 'if (intent?.action == ACTION_REBIND)' not in gw_code:
        e.append('onStartCommand 没处理 ACTION_REBIND')

    # ② REBIND 分支：没开就不碰服务；开着才重开监听
    rebind_block = gw_code.split('if (intent?.action == ACTION_REBIND)', 1)
    if len(rebind_block) == 2:
        # 分支内部 enabled 判断自带一个 return，不能用它截断；截到下一个顶层判断为止
        blk = rebind_block[1].split('\n        if (!GatewayPrefs.enabled(this)) {', 1)[0]
        if 'if (!GatewayPrefs.enabled(this))' not in blk:
            e.append('REBIND 分支没判 enabled（没开也会动服务）')
        if 'stopGateway()' not in blk or 'startGateway()' not in blk:
            e.append('REBIND 分支没按新绑定重开监听')
        if 'GATEWAY_OFF' in blk:
            e.append('REBIND 分支里出现假的「用户关闭」')
        if 'GATEWAY_BIND_APPLIED' not in blk:
            e.append('REBIND 分支没记自己的日志')
    else:
        e.append('REBIND 分支结构被改坏')

    # ③ 假的 GATEWAY_OFF 只准出现在 ACTION_STOP 分支
    stop_block = gw_code.split('if (intent?.action == ACTION_STOP)', 1)
    if len(stop_block) == 2:
        blk = stop_block[1].split('return START_NOT_STICKY', 1)[0]
        if 'GATEWAY_OFF' not in blk:
            e.append('真「用户关闭」通道反而没记 OFF')
    else:
        e.append('ACTION_STOP 分支被改坏')

    # ④ 面板切绑定不再 stop+start，且只在服务开着才 rebind
    for bad in ('GatewayService.stop(c)', 'GatewayService.start(c)'):
        if bad in ui_code and bad not in gw:
            # 主操作里 start/stop 是合法的，只查绑定 Switch 那段
            bind_blk = ui_code.split('Switch(checked = lan', 1)
            if len(bind_blk) == 2 and bad in bind_blk[1].split('})', 1)[0]:
                e.append('切绑定仍在用 ' + bad + '（会记假 OFF）')
    if 'val wasOn = GatewayPrefs.enabled(c)' not in ui_code:
        e.append('切绑定没判服务是否开着')
    if 'if (wasOn) GatewayService.rebind(c)' not in ui_code:
        e.append('切绑定没走 rebind')
    if 'GatewayPrefs.setBind(c, wantLan)' not in ui_code:
        e.append('切绑定没存新绑定值')

    # ⑤ 订阅源 self 指向实际生效的 raw，不再指向 12h 缓存的 jsdelivr
    if '<link rel="self"' not in pub:
        e.append('订阅源模板丢了 self')
    if 'https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/updates.atom' not in pub:
        e.append('self 没指向 raw 订阅源')
    if 'cdn.jsdelivr.net' in pub:
        e.append('self 还指着有 12h 缓存的 jsdelivr')
    if '<link rel="enclosure"' not in pub:
        e.append('订阅源丢了 enclosure（下载器会下回 HTML）')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('摘掉 REBIND 常量', 'gw', 'const val ACTION_REBIND', ''),
            ('摘掉 rebind 入口', 'gw', 'fun rebind(c: Context)', 'fun rebindOld(c: Context)'),
            ('REBIND 不判 enabled', 'gw',
             'if (!GatewayPrefs.enabled(this)) {\n                stopSelf()\n                return START_NOT_STICKY\n            }\n            stopGateway()',
             'stopGateway()'),
            ('REBIND 记假 OFF', 'gw', 'GATEWAY_BIND_APPLIED', 'GATEWAY_OFF'),
            ('面板切绑定回退成 stop', 'ui',
             'if (wasOn) GatewayService.rebind(c)',
             'GatewayService.stop(c); GatewayService.start(c)'),
            ('面板不判服务状态', 'ui', 'val wasOn = GatewayPrefs.enabled(c)', 'val wasOn = true'),
            ('self 回退 jsdelivr', 'publish',
             'https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/updates.atom',
             'https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom'),
            ('订阅源摘掉 enclosure', 'publish', 'rel="enclosure"', 'rel="alternate"'),
        ]
        bad = 0
        for name, key, old, new in cases:
            s2 = dict(s)
            if old not in s2[key]:
                bad += 1; print('  注入锚失效', name); continue
            s2[key] = s2[key].replace(old, new, 1)
            if not check(s2):
                bad += 1; print('  漏放', name)
            else:
                print('  拦下', name)
        print('R388 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R388 %s' % ('FAIL: ' + '; '.join(e) if e else
          'PASS · 切绑定不记假 OFF / 没开不碰服务 / self 指 raw / enclosure 守恒'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
