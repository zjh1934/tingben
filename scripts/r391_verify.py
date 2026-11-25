#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R391 · 1.1.53 真机两处反馈，本轮修完并守死。

① 下载器里好几个项目全是 `app-debug.apk`，分不清谁是谁。
   Release 资产 / 直链仓主文件 / 订阅源 enclosure 全改成
   `tingben-v<版本>.apk`；`app-debug.apk` 同字节保留只为老直链不 404。
② 请求文本页「上下滑滑不动、左右滑很生硬」。
   根因 detectDragGestures 把竖向也消费了；改 detectHorizontalDragGestures
   （只认横向、竖向放行给外壳唯一那层滚动）+ 阻尼跟手 + 180ms 弹回
   + 指示条伸缩动画；多选态横滑不误切渠道。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r391_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = {
    'archive': ROOT / 'app/src/main/java/com/tingben/app/ArchiveUi.kt',
    'ci': ROOT / '.github/workflows/ci.yml',
    'publish': ROOT / 'scripts/publish_apk.py',
    'notes': ROOT / 'scripts/release_notes.py',
    'readme': ROOT / 'README.md',
    'status': ROOT / 'STATUS.md',
    'changelog': ROOT / 'CHANGELOG.md',
    'r135': ROOT / 'scripts/r135_verify.py',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def code_lines(text):
    """滤注释行再查禁用串（修复说明里会提到旧形态，那是解释不是回潮）。"""
    return '\n'.join(l for l in text.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/', '#')))

def check(s):
    e = []
    a, ac = s['archive'], code_lines(s['archive'])
    pub, ci, notes = s['publish'], s['ci'], s['notes']

    # ① 安装包命名：四处都得带软件名 + 版本号
    for k in ('APK_NAME = "tingben-v" + VER + ".apk"',
              'if VER == "unknown" or "app-debug" in APK_NAME or VER not in APK_NAME:',
              'if APK_NAME not in ATOM:',
              '{"path": APK_NAME, "mode": "100644"',
              '{"path": "app-debug.apk", "mode": "100644"',
              '<title>听本 v%s</title>',
              'href="https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/%s"',
              '"base_tree": base_tree',
              'updates.atom', 'docs/updates.atom', 'rel="enclosure"'):
        if k not in pub:
            e.append('发版命名缺：' + k[:62])
    if 'tingben-v${{ steps.ver.outputs.v }}.apk' not in ci or \
            'cp app/build/outputs/apk/debug/app-debug.apk "tingben-v${V}.apk"' not in ci:
        e.append('CI 没把 Release 资产改成软件名+版本')
    # 两行分别查：只改一行、另一行还带旧串时不能蒙混过关
    if 'download/v" + v + "/tingben-v" + v + ".apk"' not in notes or \
            'main/tingben-v" + v + ".apk"' not in notes:
        e.append('Release 说明里的下载链接还是旧名')
    if 'main/app-debug.apk' not in notes:
        e.append('兼容旧直链说明丢了（老链接会 404）')
    for k in ('tingben-v1.1.54.apk', '当前版本 `1.1.54`'):
        if k not in s['readme']:
            e.append('README 没对齐新命名：' + k)
    if 'tingben-v1.1.54.apk' not in s['status']:
        e.append('STATUS 直链没对齐新命名')
    if '## [1.1.54]' not in s['changelog']:
        e.append('CHANGELOG 缺 1.1.54 段')
    # 订阅源只能推主名，不许把 enclosure 又指回旧名
    if 'href="https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk"' in pub:
        e.append('订阅源 enclosure 又指回 app-debug.apk（下载器继续分不清）')

    # ② 手势：只认横向、竖向放行；跟手 + 弹回 + 指示条动画；R383 不回潮
    for k in ('detectHorizontalDragGestures(', 'onHorizontalDrag = {',
              'onDragEnd = {', 'onDragCancel = {',
              'Animatable(0f)', 'animateFloatAsState(', 'tween(180)',
              'dragX.animateTo(0f, tween(180))',
              '!selecting && abs(dx) > 60f',
              '.pointerInput(tab, selecting)',
              '.offset { IntOffset((dragX.value * 0.35f).roundToInt(), 0) }'):
        if k not in a:
            e.append('手势修复缺：' + k[:62])
    # onDragEnd 与 onDragCancel 两处都要弹回，只留一处不算修好
    if a.count('dragX.animateTo(0f, tween(180))') < 2:
        e.append('弹回动画只留一处（松手或被取消时又会硬跳）')
    if 'detectDragGestures(' in ac:
        e.append('回潮 detectDragGestures（会吞竖向滚动，真机滑不动）')
    for bad in ('verticalScroll(', 'LazyColumn', 'rememberScrollState'):
        if bad in ac:
            e.append('二级页自带滚动 → R383 闪退回潮：' + bad)
    if 'import androidx.compose.foundation.gestures.detectHorizontalDragGestures' not in a:
        e.append('没引入只认横向的手势 API')

    # ③ 挂 CI 与现行清单
    if '"r391_verify.py"' not in s['r135']:
        e.append('r135 现行清单漏登记 R391')
    if s['ci'].count('scripts/r391_verify.py') < 2:
        e.append('CI 没挂 R391 正向 + 反向两步')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('主名退回旧名', 'publish',
             'APK_NAME = "tingben-v" + VER + ".apk"',
             'APK_NAME = "app-debug.apk"'),
            ('订阅源指回旧名', 'publish',
             'href="https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/%s"',
             'href="https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk"'),
            ('丢掉兼容旧名', 'publish',
             '{"path": "app-debug.apk", "mode": "100644"', ''),
            ('丢掉版本自检', 'publish',
             'if APK_NAME not in ATOM:', ''),
            ('CI 资产改回通配', 'ci',
             'files: tingben-v${{ steps.ver.outputs.v }}.apk',
             'files: app/build/outputs/apk/debug/*.apk'),
            ('CI 不复制新名', 'ci',
             'cp app/build/outputs/apk/debug/app-debug.apk "tingben-v${V}.apk"', ''),
            ('Release 说明退回旧链', 'notes',
             '/tingben-v" + v + ".apk"', '/app-debug.apk"'),
            ('README 版本没对齐', 'readme', '当前版本 `1.1.54`', '当前版本 `1.1.53`'),
            ('回潮吞竖向的手势', 'archive',
             'detectHorizontalDragGestures(', 'detectDragGestures('),
            ('丢掉跟手位移', 'archive',
             '.offset { IntOffset((dragX.value * 0.35f).roundToInt(), 0) }', ''),
            ('丢掉弹回动画', 'archive',
             'dragX.animateTo(0f, tween(180))', ''),
            ('多选态又能误切', 'archive',
             '!selecting && abs(dx) > 60f', 'abs(dx) > 60f'),
            ('二级页自带滚动', 'archive',
             '.pointerInput(tab, selecting) {',
             'Column(Modifier.verticalScroll(rememberScrollState())) { }\n        .pointerInput(tab, selecting) {'),
            ('r135 漏登记', 'r135', '"r391_verify.py"', ''),
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
        print('R391 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R391 %s' % ('FAIL: ' + '; '.join(e) if e else
          'PASS · 安装包带软件名+版本 / 只认横向手势（竖向放行）/ 跟手弹回动画 / 兼容旧名'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
