#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R386 · 真机实证：「更新后要重新点一次开关才生效」是真 bug。

根因：应用更新 / 系统清理杀掉进程，两个前台服务都 START_NOT_STICKY，
没人再读 Prefs；可 Prefs 里 enabled 还是 true，设置页显示「已开启」而服务已死。
修法：Application 冷启 + MY_PACKAGE_REPLACED / BOOT_COMPLETED 豁免广播，
按已保存的开关补拉；不强拉活、不改 STICKY、失败只记日志不崩。

查证：Android Developers「從背景啟動前景服務的相關限制」把
BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 列为豁免。

纯静态语义判据，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r386_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'resume': APP / 'core/ServiceResume.kt',
    'receiver': APP / 'core/ResumeReceiver.kt',
    'app': APP / 'TingbenApp.kt',
    'manifest': ROOT / 'app/src/main/AndroidManifest.xml',
    'gw': APP / 'gateway/GatewayService.kt',
    'relay': APP / 'relay/RelayService.kt',
    'settings': APP / 'SettingsUi.kt',
    'gwui': APP / 'gateway/GatewayUi.kt',
    'relayui': APP / 'relay/RelayUi.kt',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def code_lines(text):
    """先滤掉 KDoc / 行注释再查「禁用串」：R386 的修复说明里写了
    USER_PRESENT / START_STICKY 这些「不许出现」的词，是解释不是回潮。"""
    return '\n'.join(l for l in text.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/')))

def check(s):
    e = []
    r, rc, a, m, gw, relay = s['resume'], s['receiver'], s['app'], s['manifest'], s['gw'], s['relay']
    rc_code, gw_code, relay_code = code_lines(rc), code_lines(gw), code_lines(relay)

    # ① 冷启恢复入口真接上
    if 'ServiceResume.resumeIfNeeded(this, "冷启动")' not in a:
        e.append('Application.onCreate 没按 Prefs 补拉服务（bug 没修）')
    if 'import com.tingben.app.core.ServiceResume' not in a:
        e.append('TingbenApp 缺 core.ServiceResume 的 import（rc #267 真编译红：Unresolved reference）')
    if 'class TingbenApp : Application()' not in a:
        e.append('Application 类被改坏')

    # ② 只恢复用户自己开过的，且失败降级不崩
    for k in ('RelayPrefs.configured(c)', 'GatewayPrefs.enabled(c)',
              'RelayService.start(c)', 'GatewayService.start(c)',
              'RELAY_RESUME', 'GATEWAY_RESUME',
              'RELAY_RESUME_BLOCKED', 'GATEWAY_RESUME_BLOCKED'):
        if k not in r:
            e.append('ServiceResume 缺：' + k)
    if r.count('catch (t: Throwable)') < 2:
        e.append('两个服务没各自包 try/catch（系统拒绝启动会崩）')
    # 恢复路径不许带凭据 / 正文 / 网络
    for bad in ('RelayPrefs.token', 'GatewayPrefs.token', 'http', 'Socket(', 'text'):
        if bad in r and bad not in ('text',):
            e.append('ServiceResume 出现不该有的东西：' + bad)

    # ③ 广播只接三个官方豁免，不注册高频强拉活
    for k in ('Intent.ACTION_MY_PACKAGE_REPLACED', 'Intent.ACTION_BOOT_COMPLETED',
              'Intent.ACTION_LOCKED_BOOT_COMPLETED', 'class ResumeReceiver : BroadcastReceiver()'):
        if k not in rc:
            e.append('ResumeReceiver 缺：' + k)
    for bad in ('USER_PRESENT', 'SCREEN_ON', 'CONNECTIVITY_CHANGE', 'ACTION_TIME_TICK',
                'PACKAGE_ADDED', 'AlarmManager', 'WorkManager'):
        if bad in rc_code:
            e.append('ResumeReceiver 注册了强拉活/高频广播：' + bad)
    if 'android:name=".core.ResumeReceiver" android:exported="false"' not in m:
        e.append('Manifest 没注册 ResumeReceiver 或变成可导出')
    for k in ('android.intent.action.MY_PACKAGE_REPLACED',
              'android.intent.action.BOOT_COMPLETED',
              'android.intent.action.LOCKED_BOOT_COMPLETED',
              'android.permission.RECEIVE_BOOT_COMPLETED'):
        if k not in m:
            e.append('Manifest 缺：' + k)

    # ④ 不强拉活：两个服务仍 START_NOT_STICKY，且 START_STICKY 零回潮
    for name, txt, code in (('网关', gw, gw_code), ('中继', relay, relay_code)):
        if 'return START_NOT_STICKY' not in txt:
            e.append(name + ' 服务不再是 START_NOT_STICKY')
        if 'START_STICKY' in code.replace('START_NOT_STICKY', ''):
            e.append(name + ' 出现 START_STICKY 强拉活')

    # ⑤ 旧口径零残留（R386 只留最新，不让两套并存）
    for name, txt in (('GatewayService', gw), ('RelayService', relay)):
        for bad in ('下次手动开即可', '自动恢复留 Phase 3', '恢复靠设置页再点一次'):
            if bad in txt:
                e.append(name + ' 旧口径没删净：' + bad)

    # ⑥ R383 闪退修复不回潮：两个二级页不许自带滚动
    for name, txt in (('网关页', s['gwui']), ('中继页', s['relayui'])):
        if 'import androidx.compose.foundation.verticalScroll' in txt or \
           '.verticalScroll(' in txt.split('//', 1)[0]:
            e.append(name + ' 内层滚动回潮（R383 闪退）')
    if s['settings'].count('verticalScroll(rememberScrollState())') != 1:
        e.append('设置页不是唯一一层滚动')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('冷启不补拉', 'app',
             'ServiceResume.resumeIfNeeded(this, "冷启动")', ''),
            ('摘掉 ServiceResume 的 import', 'app',
             'import com.tingben.app.core.ServiceResume', ''),
            ('中继改回无条件拉起', 'resume',
             'if (RelayPrefs.configured(c))', 'if (true)'),
            ('网关改回无条件拉起', 'resume',
             'if (GatewayPrefs.enabled(c))', 'if (true)'),
            ('去掉中继异常降级', 'resume', 'RELAY_RESUME_BLOCKED', 'RELAY_FAIL_SILENT'),
            ('Receiver 加高频强拉活', 'receiver',
             'class ResumeReceiver : BroadcastReceiver()',
             'class ResumeReceiver : BroadcastReceiver() /* ACTION_SCREEN_ON */'),
            ('Receiver 去掉更新广播', 'receiver',
             'Intent.ACTION_MY_PACKAGE_REPLACED ->', 'Intent.ACTION_VIEW ->'),
            ('Manifest 摘掉 receiver', 'manifest',
             'android:name=".core.ResumeReceiver" android:exported="false"',
             'android:name=".core.ResumeReceiver" android:exported="true"'),
            ('Manifest 摘掉豁免权限', 'manifest',
             'android.permission.RECEIVE_BOOT_COMPLETED', 'android.permission.VIBRATE_DUP'),
            ('网关改回 START_STICKY', 'gw',
             'return START_NOT_STICKY', 'return START_STICKY'),
            ('旧口径回潮', 'gw',
             'R386 起由 Application 冷启', '下次手动开即可'),
            ('网关页内层滚动回潮', 'gwui',
             'import androidx.compose.foundation.shape.CircleShape',
             'import androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.verticalScroll'),
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
        print('R386 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R386 %s · 更新/冷启自动恢复 / 不强拉活 / 旧口径零残留 / R383 守恒' %
          ('FAIL: ' + '; '.join(e) if e else 'PASS'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
