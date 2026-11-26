#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R377 · 跨网零付费中继守门（静态 + 反向注入 + 信箱模拟真跑）。

钉住：只连 api.github.com / Token 由用户填不进包 / 正文即用即删 /
回传前手机本地压缩 / 开始与结束震动节奏不同 / 三种回退 / 并发锁 1 /
默认关 / 前台服务与权限 / 契约 ↔ Kotlin 常量 / 设置页与 CI 登记。

纯静态 + 标准库模拟；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r377_verify.py [--selftest]
"""
import json, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
RL = APP / 'relay'


def sources():
    return {
        'manifest': (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8'),
        'contract': (ROOT / 'contracts/gateway_contract.json').read_text(encoding='utf-8'),
        'relaykt': '\n'.join(p.read_text(encoding='utf-8', errors='replace')
                             for p in sorted(RL.glob('*.kt'))),
        'const': (RL / 'RelayContract.kt').read_text(encoding='utf-8'),
        'prefs': (RL / 'RelayPrefs.kt').read_text(encoding='utf-8'),
        'client': (RL / 'RelayClient.kt').read_text(encoding='utf-8'),
        'haptics': (RL / 'RelayHaptics.kt').read_text(encoding='utf-8'),
        'service': (RL / 'RelayService.kt').read_text(encoding='utf-8'),
        'ui': (RL / 'RelayUi.kt').read_text(encoding='utf-8'),
        'audioops': (APP / 'core/AudioOps.kt').read_text(encoding='utf-8'),
        'synth': (APP / 'gateway/GatewaySynth.kt').read_text(encoding='utf-8'),
        'http': (APP / 'gateway/GatewayHttp.kt').read_text(encoding='utf-8'),
        'settings': (APP / 'SettingsUi.kt').read_text(encoding='utf-8'),
        'ci': (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8'),
        'r135': (ROOT / 'scripts/r135_verify.py').read_text(encoding='utf-8'),
        'sim': (ROOT / 'scripts/relay_sim.py').read_text(encoding='utf-8'),
    }


def check(s=None):
    s = s or sources()
    e = []
    c = json.loads(s['contract'])
    r = c.get('relay', {})

    # ① 唯一出站面：只连 api.github.com，凭据不硬编码
    if 'const val API_HOST = "api.github.com"' not in s['const']:
        e.append('中继域名常量被改')
    if 'const val API_BASE = "https://api.github.com/repos/"' not in s['const']:
        e.append('中继 API 基址被改')
    # 只扫真正发起出站请求的 RelayClient.kt；RelayUi 里给用户看的仓库/网页链接不算出站。
    hosts = set(re.findall(r'https?://([A-Za-z0-9._-]+)', s['client']))
    if hosts - {'api.github.com'}:
        e.append('中继出现非 api.github.com 的地址：' + ','.join(sorted(hosts - {'api.github.com'})))
    if 'github_pat_' in s['relaykt'] or 'ghp_' in s['relaykt']:
        e.append('中继源码硬编码了 GitHub Token')
    if 'host != RelayContract.API_HOST' not in s['client']:
        e.append('客户端没做域名白名单硬校验')

    # ② 默认关 / Token 用户填 / 前台服务 / 权限
    if 'getBoolean("enabled", false)' not in s['prefs']:
        e.append('跨网中继不是默认关')
    if 'getString("token", "")' not in s['prefs']:
        e.append('Token 不是默认空（只能由用户填）')
    if 'android.permission.VIBRATE' not in s['manifest']:
        e.append('VIBRATE 权限被摘')
    if 'android:name=".relay.RelayService"' not in s['manifest']:
        e.append('RelayService 没声明')
    if 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' not in s['manifest']:
        e.append('specialUse 用途说明被摘')
    if 'ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE' not in s['service']:
        e.append('中继没用独立 specialUse 前台服务')

    # ③ 正文即用即删（成功/失败都要删）
    if 'fun deleteQueue(' not in s['client']:
        e.append('RelayClient 没有 DELETE 请求文件')
    if s['service'].count('client.deleteQueue(item.name, reqSha)') != 3:
        e.append('正文即用即删的 DELETE 不齐（超长分支与主路径各一处）')
    if '正文即用即删' not in s['client']:
        e.append('DELETE 的隐私语义注释被摘')

    # ④ 回传前手机本地压缩，失败如实回退
    if 'fun compressForTransfer(' not in s['audioops']:
        e.append('AudioOps 压缩桥被摘')
    if 'AudioOps.compressForTransfer(' not in s['synth']:
        e.append('GatewaySynth 没走压缩桥（会回传大 WAV）')
    if 'X-Tingben-Format' not in s['http'] or 'GatewayContract.M4A' not in s['http']:
        e.append('HTTP 响应没如实标注压缩格式')
    if c.get('audio_type') != 'audio/mp4' or c.get('audio_fallback') != 'audio/wav':
        e.append('契约没写清 M4A 主路与 WAV 回退')
    if r.get('audio', '').find('MP3') < 0:
        e.append('契约没留 MP3 查证结论（Android 无原生编码器）')

    # ⑤ 震动：开始/结束/失败三种不同节奏 + 无振幅控制回退
    if 'fun start(' not in s['haptics'] or 'fun done(' not in s['haptics'] or 'fun fail(' not in s['haptics']:
        e.append('开始/结束/失败震动没齐')
    # R381 · 真机反馈「完成的长震动没注意到」：结束节奏从 410ms 拉到约 930ms
    # （收尾 420ms），并把触发点提前到压缩完成。锚按新形态迁移，不放宽：
    # 仍必须三种节奏齐、开始/失败不变、完成与开始明显不同。
    if 'START_TIMINGS = longArrayOf(0, 55, 90, 55)' not in s['const'] or \
       'DONE_TIMINGS = longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)' not in s['const'] or \
       'FAIL_TIMINGS = longArrayOf(0, 40, 60, 40, 60, 40)' not in s['const']:
        e.append('三种震动节奏常量被改')
    if 'longArrayOf(0, 70, 60, 70, 60, 150)' in s['const']:
        e.append('R381 旧完成节奏回潮（长收尾又缩回 150ms）')
    if 'hasAmplitudeControl()' not in s['haptics']:
        e.append('振幅控制回退被摘')
    if s['service'].count('RelayHaptics.start(this)') != 1 or \
       s['service'].count('RelayHaptics.done(this)') != 1 or \
       s['service'].count('RelayHaptics.fail(this)') != 2:
        e.append('服务里震动触发点不齐（开始/完成/失败各一处）')

    # ⑥ 复用现成合成链 + 并发锁 + 本机预设只读
    # R400 · 调用形态带渠道标签，判据按新形态改准、不放宽
    if 'GatewaySynth.speak(this, text, 0, "中继")' not in s['service']:
        e.append('中继没复用 GatewaySynth / ClipSynth 现成链，或渠道标签丢失')
    if 'if (r.busy)' not in s['service']:
        e.append('并发锁 1 的忙等待分支被摘')
    if 'const val MAX_CHARS = 1500' not in s['const']:
        e.append('1500 字上限被改')
    if 'chars > RelayContract.MAX_CHARS' not in s['service']:
        e.append('超长请求没拦')

    # ⑦ 契约 ↔ 常量 / 设置页 / CI / 现行清单
    for k, v in (('poll_ms', 4000), ('idle_poll_ms', 10000), ('max_chars', 1500)):
        if r.get(k) != v: e.append('契约 relay.%s 不对' % k)
    if 'const val POLL_MS = 4000L' not in s['const'] or 'const val IDLE_POLL_MS = 10000L' not in s['const']:
        e.append('轮询常量与契约不一致')
    # R390 · 子页状态上提到 App 层，入口改成 onPageChange 上报；能力不放宽。
    if 'import com.tingben.app.relay.RelayScreen' not in s['settings'] or \
            'onPageChange("relay")' not in s['settings']:
        e.append('设置页跨网中继入口被摘')
    if '"relay" -> "跨网中继"' not in s['settings']:
        e.append('设置页标题被摘')
    if s['ci'].count('scripts/r377_verify.py') < 2:
        e.append('CI 正向或反向注入漏挂 r377_verify.py')
    if 'echo "::error::APK 含 INTERNET 权限"' in s['ci'] or \
       'R375 起 INTERNET 应存在' not in s['ci']:
        e.append('CI APK 闸门还守旧口径 / 没迁到 R375-R377 的 INTERNET 合法形态')
    if '"r377_verify.py"' not in s['r135']:
        e.append('r135 现行清单漏登记 r377_verify.py')
    if 'def main()' not in s['sim']:
        e.append('信箱模拟器结构被改')
    return e


INJECT = [
    ('VIBRATE 权限被摘', 'manifest', '<uses-permission android:name="android.permission.VIBRATE" />', ''),
    ('中继服务声明被摘', 'manifest', 'android:name=".relay.RelayService"', ''),
    ('默认开', 'prefs', 'getBoolean("enabled", false)', 'getBoolean("enabled", true)'),
    ('Token 硬编码', 'relaykt', 'const val API_HOST', 'const val API_HOST\n    // github_pat_1234'),
    ('改连别的服务器', 'const', 'const val API_HOST = "api.github.com"', 'const val API_HOST = "evil.example.com"'),
    ('域名白名单被摘', 'client', 'host != RelayContract.API_HOST', 'false'),
    ('删请求被摘', 'service', 'client.deleteQueue(item.name, reqSha)', ''),
    ('压缩桥被摘', 'synth', 'AudioOps.compressForTransfer(', 'Fake.noCompress('),
    ('谎报压缩格式', 'http', 'X-Tingben-Format', 'X-Fake'),
    ('结束震动改成和开始一样', 'const', 'DONE_TIMINGS = longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)', 'DONE_TIMINGS = longArrayOf(0, 55, 90, 55)'),
    ('R381 长收尾回潮', 'const', 'DONE_TIMINGS = longArrayOf(0, 80, 90, 80, 90, 80, 90, 420)', 'DONE_TIMINGS = longArrayOf(0, 70, 60, 70, 60, 150)'),
    ('振幅回退被摘', 'haptics', 'hasAmplitudeControl()', 'false'),
    ('另造合成链', 'service', 'GatewaySynth.speak(this, text, 0, "中继")', 'FakeSynth.speak(this, text)'),
    ('并发等待被摘', 'service', 'if (r.busy)', 'if (false)'),
    ('上限改成 2000', 'const', 'const val MAX_CHARS = 1500', 'const val MAX_CHARS = 2000'),
    ('设置页入口被摘', 'settings', 'onPageChange("relay")', 'onPageChange("")'),
    ('CI 漏挂', 'ci', 'python3 scripts/r377_verify.py', ''),
    ('APK 闸门回潮旧口径', 'ci', 'R375 起 INTERNET 应存在', 'APK 含 INTERNET 权限'),
    ('现行清单漏登记', 'r135', '"r377_verify.py"', ''),
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
    r = subprocess.run([sys.executable, str(ROOT / 'scripts/relay_sim.py')],
                       capture_output=True, text=True, timeout=120)
    if r.returncode == 0 and '汇总' in r.stdout and '通过' in r.stdout:
        print('  信箱闭环模拟真跑通（纯内存，未连云端）')
    else:
        bad.append('信箱模拟器没跑通：rc=%d tail=%s' % (r.returncode, r.stdout[-300:]))
    if bad:
        print('未拦下 / 未通过：', '、'.join(bad)); return 1
    print('R377 反向注入 %d/%d 全拦 + 信箱模拟全绿' % (len(INJECT), len(INJECT)))
    return 0


def main():
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    e = check()
    if e:
        print('R377 FAIL:')
        for x in e: print(' -', x)
        sys.exit(1)
    print('R377 PASS · 跨网中继 / 压缩回传 / 三种震动 / 正文即用即删 / 零付费全绿')


if __name__ == '__main__':
    main()
