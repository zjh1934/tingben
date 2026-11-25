#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R375 · 手机端语音合成网关守门。

这一轮用户授权「三个技术问题我不懂，你自己定」，定案写在
方案/听本_手机端语音合成网关_方案研究.md 的「Phase 0 决策记录」：
D1 加 INTERNET 但零出站 / D2 本轮只本机+局域网 / D4 手写 ServerSocket /
D6 复用 ClipSynth / D7 音色只读本机 / D8 并发锁 1。

钉住七件事：
  ① 清单含 INTERNET + specialUse 服务，且**源码零出站网络客户端调用**
  ② 契约 JSON 与 GatewayContract.kt 逐项对齐（含 MAX_CHARS=ClipSynth.MAX_CHARS）
  ③ 错误体一律 problem+json，除内置网页外零 HTML 响应
  ④ 合成只经 ClipSynth.synthesize，请求里的音色字段静默丢弃
  ⑤ 并发 AtomicBoolean 锁 1、Token 恒定时间校验、默认关默认绑本机
  ⑥ 设置页接了网关独立页、模拟器归档可跑
  ⑦ Python 契约模拟器 28/28 真跑通（纯 loopback，不连云端）

纯静态 + 标准库模拟，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r375_verify.py [--selftest]
"""
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
GW = APP / 'gateway'


def sources():
    # R377 · relay/ 是唯一允许主动出站的面，零出站守门按包排除，
    # 由 r377_verify.py 单独守「只连 api.github.com / 正文即用即删」。
    kt = '\n'.join(p.read_text(encoding='utf-8', errors='replace')
                   for p in sorted((APP).rglob('*.kt')) if 'relay' not in p.parts)
    return {
        'manifest': (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8'),
        'contract': (ROOT / 'contracts/gateway_contract.json').read_text(encoding='utf-8'),
        'const': (GW / 'GatewayContract.kt').read_text(encoding='utf-8'),
        'http': (GW / 'GatewayHttp.kt').read_text(encoding='utf-8'),
        'prefs': (GW / 'GatewayPrefs.kt').read_text(encoding='utf-8'),
        'synth': (GW / 'GatewaySynth.kt').read_text(encoding='utf-8'),
        'service': (GW / 'GatewayService.kt').read_text(encoding='utf-8'),
        'ui': (GW / 'GatewayUi.kt').read_text(encoding='utf-8'),
        'clip': (APP / 'core/ClipSynth.kt').read_text(encoding='utf-8'),
        'settings': (APP / 'SettingsUi.kt').read_text(encoding='utf-8'),
        'allkt': kt,
        'r135': (ROOT / 'scripts/r135_verify.py').read_text(encoding='utf-8'),
        'ci': (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8'),
        'sim': (ROOT / 'scripts/gateway_sim.py').read_text(encoding='utf-8'),
    }


def check(s=None):
    s = s or sources()
    e = []
    c = json.loads(s['contract'])

    # ① 权限与零出站（relay 包按 R377 口径单独放行并另守）
    if 'android.permission.INTERNET' not in s['manifest']:
        e.append('R375 网关 INTERNET 权限被摘')
    if 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' not in s['manifest']:
        e.append('网关 specialUse 权限被摘')
    if 'android:name=".gateway.GatewayService"' not in s['manifest']:
        e.append('网关服务声明被摘')
    if 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' not in s['manifest']:
        e.append('specialUse 用途说明被摘')
    if s['manifest'].count('android:foregroundServiceType="specialUse"') < 2:
        e.append('specialUse 前台服务声明不齐（R375 网关 + R377 中继各一处）')
    for bad in ('HttpURLConnection', 'OkHttpClient', '.openConnection(',
                'java.net.Socket(', 'new URL('):
        if bad in s['allkt']:
            e.append('零出站被破：出现 ' + bad)
    if re.search(r'(?<!Server)Socket\(', s['allkt']):
        e.append('零出站被破：出现客户端 Socket( 调用')

    # ② 契约 ↔ Kotlin 常量
    pairs = [
        ('DEFAULT_PORT', c['default_port']), ('MAX_CHARS', c['max_chars']),
        ('JOB_RETENTION_MS', str(c['job_retention_seconds']) + '_000L'),
        ('MAX_CONCURRENCY', c['max_concurrency']),
    ]
    for k, v in pairs:
        if 'const val %s = %s' % (k, v) not in s['const']:
            e.append('契约常量不一致：%s = %s' % (k, v))
    if 'const val MAX_CHARS = 1500' not in s['clip']:
        e.append('ClipSynth.MAX_CHARS 不是 1500，契约上限要跟着改')
    for t in ('application/json; charset=utf-8', 'application/problem+json; charset=utf-8',
              'audio/wav', 'audio/mp4', 'text/html; charset=utf-8'):
        if t not in s['const']:
            e.append('Content-Type 常量缺失：' + t)
    for ep in c['endpoints']:
        p = ep['path'].split('{')[0]
        if p == '*':
            continue
        if '{' in ep['path']:
            # job 子端点在 Kotlin 里是 PATH_JOBS + "/" 拼出来的，不是字面量
            if 'jobRoute(req)' not in s['service'] or 'sub == "audio"' not in s['service']:
                e.append('job 子端点没落地：' + ep['path'])
        elif p not in s['const'] + s['service']:
            e.append('端点没落地：' + p)
    for code in c['errors']:
        if str(code['status']) not in s['const'] or code['code'] not in s['const']:
            e.append('错误码没落地：%s %s' % (code['status'], code['code']))
    for f in c['ignored_request_fields']:
        if '"%s"' % f not in s['const']:
            e.append('音色冻结字段没落地：' + f)

    # ③ 错误体绝不回 HTML
    if 'GatewayContract.PROBLEM' not in s['http']:
        e.append('错误没走 problem+json')
    if s['http'].count('GatewayContract.HTML') != 1:
        e.append('HTML 响应构造不是唯一一处（只允许内置网页那一处）')
    if '/' not in c.get('html_allowed_paths', []):
        e.append('契约没声明内置网页是唯一 HTML 出口')
    if 'Access-Control-Allow-Origin' not in s['http']:
        e.append('CORS 头被摘')

    # ④ 只经现成合成链 + 音色冻结
    if 'ClipSynth.synthesize(' not in s['synth']:
        e.append('网关另造合成链（必须复用 ClipSynth.synthesize）')
    if 'AudioOps.compressForTransfer(' not in s['synth']:
        e.append('R377 回传前本地压缩桥被摘（会回传大 WAV）')
    if 'ModelStore.synthesisBlockReason' not in s['synth']:
        e.append('缺件守卫没接现成链')
    if 'val ignored = GatewayContract.IGNORED_FIELDS.count { o.has(it) }' not in s['service']:
        e.append('音色字段没有静默丢弃')
    if 'Preset' not in s['http'] or 'device-local' not in s['http']:
        e.append('响应没标明用的是本机预设')

    # ⑤ 并发 / Token / 默认态
    if 'AtomicBoolean' not in s['synth'] or \
            s['synth'].count('busy.compareAndSet(false, true)') != 2:
        e.append('并发锁 1 被放开或漏一处（同步 / 异步各一把）')
    if 'MessageDigest.getInstance("SHA-256")' not in s['prefs']:
        e.append('Token 只存 SHA-256 的校验被摘')
    if 'constantTimeEq' not in s['prefs']:
        e.append('Token 恒定时间比较被摘')
    if 'getBoolean("enabled", false)' not in s['prefs']:
        e.append('网关不是默认关')
    if 'const val DEFAULT_BIND = "127.0.0.1"' not in s['const']:
        e.append('默认绑定不是 127.0.0.1')
    if 'FOREGROUND_SERVICE_TYPE_SPECIAL_USE' not in s['service']:
        e.append('网关没用独立 specialUse 前台服务')
    if 'ServerSocket(' not in s['service'] or 'accept()' not in s['service']:
        e.append('ServerSocket 监听被摘')
    if 'if (!GatewayPrefs.verify(this, req.headers["authorization"]))' not in s['service']:
        e.append('Token 校验没接进路由')

    # ⑥ 设置页 / 登记 / 模拟器
    if 'import com.tingben.app.gateway.GatewayScreen' not in s['settings']:
        e.append('设置页没引入网关页')
    # R390 · 子页状态上提到 App 层，入口改成 onPageChange 上报；能力不放宽。
    if 'onPageChange("gateway")' not in s['settings'] or \
            '"gateway" -> "语音网关"' not in s['settings']:
        e.append('设置页网关入口或标题没接')
    if '"r375_verify.py"' not in s['r135']:
        e.append('现行验证清单漏登记 r375_verify.py')
    if '"r377_verify.py"' not in s['r135']:
        e.append('R377 跨网中继守门没登记进现行清单')
    if s['ci'].count('scripts/r375_verify.py') < 2:
        e.append('CI 正向或反向注入漏挂 r375_verify.py')
    if '28/28' in s['sim'] and 'def main()' not in s['sim']:
        e.append('契约模拟器结构被改')
    return e


INJECT = [
    ('网关 INTERNET 权限被摘', 'manifest',
     '<uses-permission android:name="android.permission.INTERNET" />', ''),
    ('specialUse 服务被摘', 'manifest',
     'android:foregroundServiceType="specialUse"', ''),
    ('出站客户端调用回潮', 'allkt', 'import java.net.ServerSocket',
     'import java.net.ServerSocket\nimport java.net.HttpURLConnection'),
    ('契约上限偷偷改回 2000', 'contract', '"max_chars": 1500', '"max_chars": 2000'),
    ('回传前压缩桥被摘', 'synth', 'AudioOps.compressForTransfer(', 'Fake.noCompress('),
    ('错误体改回 HTML', 'http', 'GatewayContract.PROBLEM', 'GatewayContract.HTML'),
    ('另造合成链', 'synth', 'ClipSynth.synthesize(', 'FakeSynth.synthesize('),
    ('并发锁放开', 'synth', 'compareAndSet(false, true)', 'compareAndSet(true, true)'),
    ('Token 校验被摘', 'service', 'GatewayPrefs.verify(', 'true || GatewayPrefs.verify('),
    ('网关默认开', 'prefs', 'getBoolean("enabled", false)', 'getBoolean("enabled", true)'),
    ('默认绑公网', 'const', 'const val DEFAULT_BIND = "127.0.0.1"',
     'const val DEFAULT_BIND = "0.0.0.0"'),
    ('设置页入口被摘', 'settings', 'onPageChange("gateway")', 'onPageChange("")'),
    ('CI 漏挂', 'ci', 'python3 scripts/r375_verify.py', ''),
    ('音色字段不再丢弃', 'service', 'IGNORED_FIELDS.count', '0 * IGNORED_FIELDS.count'),
]


def selftest():
    s = sources()
    assert not check(s), '基线自己就红了：' + '; '.join(check(s))
    bad = []
    for name, key, old, new in INJECT:
        x = dict(s)
        assert old in x[key], '注入锚不存在：' + name
        x[key] = x[key].replace(old, new, 1)
        if check(x):
            print('  反向注入 拦下 ·', name)
        else:
            bad.append(name)
    # 契约模拟器真跑：28 条用例，纯 loopback、零安装、不连云端
    r = subprocess.run([sys.executable, str(ROOT / 'scripts/gateway_sim.py')],
                       capture_output=True, text=True, timeout=120)
    if r.returncode == 0 and '28/28 通过' in r.stdout:
        print('  契约模拟 28/28 真跑通（loopback）')
    else:
        bad.append('契约模拟器没跑通：rc=%d tail=%s' % (r.returncode, r.stdout[-300:]))
    if bad:
        print('未拦下 / 未通过：', '、'.join(bad))
        return 1
    print('R375 反向注入 %d/%d 全拦 + 契约模拟全绿' % (len(INJECT), len(INJECT)))
    return 0


def main():
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    e = check()
    if e:
        print('R375 FAIL:')
        for x in e:
            print(' -', x)
        sys.exit(1)
    print('R375 PASS · 网关契约 / 零出站 / 并发锁 / 音色冻结 / 模拟器全绿')


if __name__ == '__main__':
    main()
