#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R392 · GitHub 页面与工程文件整理后的口径守门。

守三件事：
① 版本徽章 / STATUS / ROADMAP / CHANGELOG / 文档链接都跟 version.txt 对齐，
   README 不许再写「App 无网络权限」（R375 起有 INTERNET，只是默认零联网）。
② 直链仓 Pages 两页分工：index.html 是项目入口（不收 Token、不提交文字），
   relay.html 才是调用页（queue/status/out + 取回 M4A）；两页不许再完全相同。
③ 工程文件地图存在，现行 / 归档 / 删前评估三件事都写清；挂 CI 与 r135 现行清单。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r392_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    'ver': ROOT / 'version.txt',
    'readme': ROOT / 'README.md',
    'status': ROOT / 'STATUS.md',
    'roadmap': ROOT / 'ROADMAP.md',
    'changelog': ROOT / 'CHANGELOG.md',
    'guide': ROOT / 'docs/使用指南.md',
    'fixlist': ROOT / 'docs/修复清单_按优先级.md',
    'map': ROOT / '工程文件地图.md',
    'index': ROOT / 'docs/pages/index.html',
    'relay': ROOT / 'docs/pages/relay.html',
    'ci': ROOT / '.github/workflows/ci.yml',
    'r135': ROOT / 'scripts/r135_verify.py',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def check(s):
    e = []
    v = s['ver'].strip()
    # ① 版本与口径对齐
    if 'version-' + v + '-' not in s['readme']:
        e.append('README 版本徽章没跟 version.txt 对齐')
    if 'App 无网络权限' in s['readme']:
        e.append('README 还写「App 无网络权限」（R375 起有 INTERNET，只是默认零联网）')
    if 'tingben-v' + v + '.apk' not in s['readme']:
        e.append('README 下载段没用带软件名+版本的主文件名')
    for k in ('status', 'roadmap'):
        if '`' + v + '`' not in s[k]:
            e.append(k + ' 版本行没跟 version.txt 对齐')
    if '## [' + v + ']' not in s['changelog']:
        e.append('CHANGELOG 缺当前版本段')
    if 'tingben-v' not in s['guide'] or '下载 `app-debug.apk` 安装' in s['guide']:
        e.append('使用指南还在教用户装 app-debug.apk（R391 已改名）')
    if '跨网中继' not in s['fixlist']:
        e.append('修复清单守恒行还只写「INTERNET 仅本机网关」，漏了跨网中继')

    # ② 页面分工
    idx, rly = s['index'], s['relay']
    if idx == rly:
        e.append('Pages 两页内容完全相同（R392 之前首页打开就是填 Token 的表单）')
    for bad in ('type="password"', 'onclick="go()"'):
        if bad in idx:
            e.append('项目首页不该收 Token / 提交文字：' + bad)
    for need in ('relay.html', 'updates.atom', 'releases/latest'):
        if need not in idx:
            e.append('项目首页缺入口：' + need)
    for need in ('type="password"', 'onclick="go()"', 'queue/', 'status/',
                 'index.html', '/git/blobs/'):
        if need not in rly:
            e.append('中继调用页缺：' + need)

    # ③ 地图与清单
    for need in ('## 现行真值', '## 归档：保留史实', '删任何东西之前先评估',
                 '## 页面（用户看得见的那些）'):
        if need not in s['map']:
            e.append('工程文件地图缺：' + need)
    if s['ci'].count('scripts/r392_verify.py') < 2:
        e.append('CI 没挂 R392 正向 + 反向两步')
    if '"r392_verify.py"' not in s['r135']:
        e.append('r135 现行清单漏登记 R392')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        v = s['ver'].strip()
        cases = [
            ('徽章退回旧版', 'readme', 'version-' + v + '-', 'version-1.1.0-'),
            ('README 回潮无网络权限', 'readme', '默认零联网', 'App 无网络权限'),
            ('README 退回旧文件名', 'readme', 'tingben-v' + v + '.apk', 'app-debug.apk'),
            ('STATUS 版本不对齐', 'status', '`' + v + '`', '`1.1.0`'),
            ('ROADMAP 版本不对齐', 'roadmap', '`' + v + '`', '`1.1.0`'),
            ('CHANGELOG 缺当前段', 'changelog', '## [' + v + ']', '## [1.1.0]'),
            ('使用指南退回旧名', 'guide', 'tingben-v', 'app-debug.apk'),
            ('修复清单漏跨网中继', 'fixlist', '跨网中继', '本机网关'),
            ('地图丢归档段', 'map', '## 归档：保留史实', '## 随便放放'),
            ('地图丢删前评估', 'map', '删任何东西之前先评估', '直接删'),
            ('首页又收 Token', 'index', '<h1>听本 · Tingben</h1>',
             '<h1>听本 · Tingben</h1><input type="password">'),
            ('首页又能提交', 'index', '<h1>听本 · Tingben</h1>',
             '<h1>听本 · Tingben</h1><button onclick="go()">'),
            ('首页丢中继入口', 'index', 'relay.html', 'nowhere.html'),
            ('中继页丢取回逻辑', 'relay', 'queue/', 'inbox/'),
            ('两页又变一样', 'relay', None, s['index']),
            ('CI 漏挂', 'ci', 'scripts/r392_verify.py', ''),
            ('r135 漏登记', 'r135', '"r392_verify.py"', ''),
        ]
        bad = 0
        for name, key, old, new in cases:
            s2 = dict(s)
            if old is None:
                s2[key] = new
            else:
                if old not in s2[key]:
                    bad += 1; print('  注入锚失效', name); continue
                s2[key] = s2[key].replace(old, new)
            if not check(s2):
                bad += 1; print('  漏放', name)
            else:
                print('  拦下', name)
        print('R392 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R392 %s' % ('FAIL: ' + '; '.join(e) if e else
          'PASS · 版本徽章对齐 / 无网络旧口径零残留 / Pages 两页分工 / 工程地图 / CI 清单'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
