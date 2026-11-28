#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R413 · 续跑转圈定位 + 低电保护设置开关守门。

用户两句原话：
  ①「关闭后或者更新后，续跑……第一章已经合成完、拼接完、转码完，
     转圈还停在第一章第一个音频小块；其实已经在合成第二章一半了，
     我也不知道第二章有没有在合成。」
  ②「上一轮加的电量低于 20% 拒绝本地音频小块，这个应该在设置里有个开关，
     放哪里我也不知道，你帮我做上去。」

守：
  · 续跑开局从磁盘重建逐章进度（chapterProgress 不再因冷启动空表兜底到第 0 章）
  · makingChapter 以磁盘「成章音频」为真值优先，已固化章一律算完成
  · 已固化章既不画 busyIndex 转圈，也不认残留 redo 转圈
  · 进度卡给一句「正在制作第 N 章 · 已做 X/Y 段」明账
  · 低电开关默认开、可在「设置 → 运行与通知」关、关掉后连电池都不读
  · 零新增权限 / 依赖 / 功能守恒 / 链路符号 / 现行清单与 CI 登记

沙盒无 Android SDK，静态绿 != 编译过；真编译由 rc / main 两次 CI 负责。
用法：python3 scripts/r413_verify.py [--selftest]
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'producer': APP / 'core/Producer.kt',
    'hub': APP / 'ChapterHub.kt',
    'prefs': APP / 'core/Prefs.kt',
    'gate': APP / 'core/BatteryGate.kt',
    'settings': APP / 'SettingsUi.kt',
    'manifest': ROOT / 'app/src/main/AndroidManifest.xml',
    'gradle': ROOT / 'app/build.gradle.kts',
    'r135': ROOT / 'scripts/r135_verify.py',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
    'status': ROOT / 'STATUS.md',
    'changelog': ROOT / 'CHANGELOG.md',
}
CHAIN = {
    APP / 'core/TaskResume.kt': ['fun recoverScope('],
    APP / 'core/SynthArbiter.kt': ['setRemoteBatch('],
    APP / 'relay/RelayService.kt': ['RELAY_DEL', 'synthOk'],
    APP / 'diag/EventLog.kt': ['RETENTION_MS'],
}


def sources():
    d = {k: (v.read_text(encoding='utf-8', errors='replace') if v.exists() else '')
         for k, v in FILES.items()}
    for p, toks in CHAIN.items():
        d['chain:' + str(p.relative_to(ROOT))] = (
            p.read_text(encoding='utf-8', errors='replace') if p.exists() else '')
    return d


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


def pick_chapter(chapter_progress, rows, scope=None, legacy=False):
    """复刻 ChapterHub.makingChapter。legacy=True 还原 R412 的空表兜底 bug。"""
    if legacy:
        # 旧写法：只在 chapterProgress 里找，找不到就 scope.minOrNull() ?: 0
        cand = [k for k, v in chapter_progress.items()
                if v < 1.0 and (scope is None or k in scope)]
        return min(cand) if cand else (min(scope) if scope else 0)
    merged = {}
    for r in rows:
        i = r['index']
        if scope is not None and i not in scope:
            continue
        disk_done = r['audio'] or r['playable']
        live = chapter_progress.get(i)
        merged[i] = 1.0 if disk_done else (
            min(max(live, 0.0), 1.0) if live is not None else 0.0)
    cand = [k for k, v in merged.items() if v < 1.0]
    if cand:
        return min(cand)
    rest = [r['index'] for r in rows
            if not r['audio'] and not r['playable']
            and (scope is None or r['index'] in scope)]
    return rest[0] if rest else (min(scope) if scope else 0)


def seg_busy(running, shown_is_live, chapter_done, rows):
    """复刻 segBusyIndex：已固化章 / 回看章 / 没在跑，都不画转圈。"""
    if not running or not rows or not shown_is_live or chapter_done:
        return None
    return next((r['index'] for r in rows if not r['hasAudio']), None)


def guard_blocked(pct, enabled, limit=20):
    """复刻 BatteryGate.bookBlockReason：开关关掉直接放行，连电池都不读。"""
    if not enabled:
        return False
    return pct < limit


def logic():
    e = []
    # 真机场景：第一章成章音频、第二章做了一半、第三章没动
    rows = [
        {'index': 0, 'audio': True, 'playable': True, 'hasAudio': True},
        {'index': 1, 'audio': False, 'playable': False, 'hasAudio': False},
        {'index': 2, 'audio': False, 'playable': False, 'hasAudio': False},
    ]
    # ① 冷启动 / 续跑开局：内存态还是空表
    if pick_chapter({}, rows, legacy=True) != 0:
        e.append('逻辑：旧写法没复现「空表兜底到第 0 章」')
    if pick_chapter({}, rows) != 1:
        e.append('逻辑：空表 + 第一章已固化时没落到第二章（R413 核心）')
    # ② 内存态重建后仍要对
    if pick_chapter({0: 1.0, 1: 0.5}, rows) != 1:
        e.append('逻辑：逐章基线 0=1f / 1=0.5f 没落到第二章')
    # ③ 前两章都固化 → 第三章
    rows2 = [dict(r, audio=(i < 2), playable=(i < 2))
             for i, r in enumerate(rows)]
    if pick_chapter({0: 1.0, 1: 1.0, 2: 0.2}, rows2) != 2:
        e.append('逻辑：前两章固化后没落到第三章')
    # ④ 勾 2/3 章（下标 1/2），首章不在范围
    if pick_chapter({}, rows, scope={1, 2}) != 1:
        e.append('逻辑：勾选范围没生效')
    # ⑤ 全新书：没有固化章，应落在第一章
    fresh = [dict(r, audio=False, playable=False) for r in rows]
    if pick_chapter({}, fresh) != 0:
        e.append('逻辑：全新书没落在第一章')
    # ⑥ 转圈：已固化章不许画；正在做的章画在第一个灰格子
    if seg_busy(True, True, True, rows) is not None:
        e.append('逻辑：已固化章仍画转圈（真机 bug）')
    making_rows = [{'index': 0, 'hasAudio': False},
                   {'index': 1, 'hasAudio': True},
                   {'index': 2, 'hasAudio': False}]
    if seg_busy(True, True, False, making_rows) != 0:
        e.append('逻辑：制作中章没画在第一个灰格子')
    if seg_busy(True, False, False, rows) is not None:
        e.append('逻辑：回看章仍画转圈')
    if seg_busy(False, True, False, rows) is not None:
        e.append('逻辑：没在跑仍画转圈')
    # ⑦ 开关：默认开时 19% 拦、20% 放行；关掉后一律放行
    if not guard_blocked(19, True):
        e.append('逻辑：默认开时 19% 没拦')
    if guard_blocked(20, True):
        e.append('逻辑：20% 本身被误拦')
    if guard_blocked(1, False) or guard_blocked(0, False):
        e.append('逻辑：开关关掉仍拦')
    if guard_blocked(19, True, limit=5):
        e.append('逻辑：阈值改动后 19% 不该再拦')
    return e


BAD = [
    ('续跑逐章基线被摘', lambda d: d.update(producer=d['producer'].replace(
        'val restoredChapter = perChapterTotal.mapValues', 'val GONE = perChapterTotal.mapValues', 1))),
    ('开局 emit 不带 chapterProgress', lambda d: d.update(producer=d['producer'].replace(
        'chapterProgress = restoredChapter', 'chapterProgress = emptyMap()', 1))),
    ('perChapterDone 不以磁盘基线起步', lambda d: d.update(producer=d['producer'].replace(
        'perChapterDone.putAll(doneByChapter)', '', 1))),
    ('开局 emit 退回只认 restoredDone', lambda d: d.update(producer=d['producer'].replace(
        'if (restoredDone > 0 || restoredChapter.values.any { it > 0f }) {',
        'if (restoredDone > 0) {', 1))),
    ('doneByChapter 不按章归类', lambda d: d.update(producer=d['producer'].replace(
        'doneByChapter[ci] = (doneByChapter[ci] ?: 0) + 1', '// gone', 1))),
    ('makingChapter 丢了 ui 依赖', lambda d: d.update(hub=d['hub'].replace(
        'remember(st.chapterProgress, task, chapters.size, book.id, ui) {',
        'remember(st.chapterProgress, task, chapters.size, book.id) {', 1))),
    ('磁盘固化章没算完成', lambda d: d.update(hub=d['hub'].replace(
        'diskDone -> 1f', 'diskDone -> 0f', 1))),
    ('又回到空表兜底第 0 章', lambda d: d.update(hub=d['hub'].replace(
        'ui.firstOrNull { (scope == null || it.index in scope) &&',
        'null // gone', 1))),
    ('已固化章仍认 redo 转圈', lambda d: d.update(hub=d['hub'].replace(
        'sg.copy(pendingRedo = !chapterDone && "seg:${sg.index}" in redoKeys)',
        'sg.copy(pendingRedo = "seg:${sg.index}" in redoKeys)', 1))),
    ('segBusyIndex 丢了 chapterDone', lambda d: d.update(hub=d['hub'].replace(
        '|| shownChapter != liveChapter || chapterDone) null',
        '|| shownChapter != liveChapter) null', 1))),
    ('进度卡明账被摘', lambda d: d.update(hub=d['hub'].replace(
        'Text("正在制作第 ${(liveChapter ?: 0) + 1} 章$segHint"', '// gone', 1))),
    ('低电默认被改成关', lambda d: d.update(prefs=d['prefs'].replace(
        'getBoolean(KEY_LOW_BATTERY_GUARD, true)',
        'getBoolean(KEY_LOW_BATTERY_GUARD, false)', 1))),
    ('开关写入被摘', lambda d: d.update(prefs=d['prefs'].replace(
        'fun setLowBatteryGuard(c: Context, v: Boolean)', 'fun GONE(c: Context, v: Boolean)', 1))),
    ('BatteryGate 不尊重开关', lambda d: d.update(gate=d['gate'].replace(
        'if (!Prefs.lowBatteryGuard(c)) return null', '', 1))),
    ('设置页没有开关行', lambda d: d.update(settings=d['settings'].replace(
        'internal fun LowBatteryGuardRow(c: Context)', 'internal fun GONE(c: Context)', 1))),
    ('开关没挂进运行与通知', lambda d: d.update(settings=d['settings'].replace(
        '            AutoResumeRow(c)\n            LowBatteryGuardRow(c)',
        '            AutoResumeRow(c)', 1))),
    ('入口副标题没提低电', lambda d: d.update(settings=d['settings'].replace(
        '"自动续跑 · 低电量保护"', '"打开软件自动续跑"', 1))),
    ('副标题没写清只拦电子书', lambda d: d.update(settings=d['settings'].replace(
        '远端 / 试听 / 单段不受影响', '', 1))),
    ('开关切换没落盘', lambda d: d.update(settings=d['settings'].replace(
        'Prefs.setLowBatteryGuard(c, it)', '', 1))),
    ('新增电池权限', lambda d: d.update(manifest=d['manifest'].replace(
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.INTERNET" />\n'
        '<uses-permission android:name="android.permission.BATTERY_STATS" />', 1))),
    ('现行清单漏登记', lambda d: d.update(r135=d['r135'].replace('"r413_verify.py"', '', 1))),
    ('CI 漏挂正向', lambda d: d.update(ci=d['ci'].replace(
        'run: python3 scripts/r413_verify.py\n', 'run: echo skip\n', 1))),
    ('CI 漏挂反向', lambda d: d.update(ci=d['ci'].replace(
        'python3 scripts/r413_verify.py --selftest', '', 1))),
    ('欠账未登记', lambda d: d.update(status=d['status'].replace('R413 续跑转圈定位', ''))),
    ('链路符号丢失', lambda d: d.update(**{
        'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('功能守恒基线被改', lambda d: d.update(baseline='{}')),
]


def check(s):
    e = []
    for k in FILES:
        if not s.get(k):
            e.append('缺文件: ' + k)
    if e:
        return e
    prod, hub, prefs, gate, settings = (s['producer'], s['hub'], s['prefs'],
                                        s['gate'], s['settings'])
    man, grad, r135, ci, status = (s['manifest'], s['gradle'], s['r135'],
                                   s['ci'], s['status'])

    # ① 续跑开局：磁盘重建逐章进度，不再让 chapterProgress 空表兜底
    for tok in ['val doneByChapter = mutableMapOf<Int, Int>()',
                'doneByChapter[ci] = (doneByChapter[ci] ?: 0) + 1',
                'perChapterDone.putAll(doneByChapter)',
                'val restoredChapter = perChapterTotal.mapValues',
                'if (restoredDone > 0 || restoredChapter.values.any { it > 0f })',
                'chapterProgress = restoredChapter']:
        if tok not in prod:
            e.append('续跑逐章基线缺: ' + tok[:58])
    # 旧形态不许回潮
    if 'val pending = (0 until toSpeak.size).count' in prod:
        e.append('pending 退回 count{}（逐章基线会被跳过）')

    # ② 目录页：磁盘真值优先 + 已固化章零转圈 + 明账
    for tok in ['remember(st.chapterProgress, task, chapters.size, book.id, ui) {',
                'diskDone -> 1f',
                'ui.firstOrNull { (scope == null || it.index in scope) &&',
                'sg.copy(pendingRedo = !chapterDone && "seg:${sg.index}" in redoKeys)',
                '|| shownChapter != liveChapter || chapterDone) null',
                'Text("正在制作第 ${(liveChapter ?: 0) + 1} 章$segHint"']:
        if tok not in hub:
            e.append('目录页定位缺: ' + tok[:58])
    if hub.count('val chapterDone = ch != null && ui.firstOrNull { it.index == ch }') != 2:
        e.append('已固化章判定没在 chapterSegs 与 segBusyIndex 各写一份')
    if 'if (scope != null && k !in scope) continue' in hub:
        e.append('makingChapter 退回只认内存态 key 的旧写法')

    # ③ 低电开关：默认开 / 落盘 / 闸门尊重 / 设置页真入口
    for tok in ['private const val KEY_LOW_BATTERY_GUARD = "lowBatteryGuard"',
                'fun lowBatteryGuard(c: Context) = sp(c).getBoolean(KEY_LOW_BATTERY_GUARD, true)',
                'fun setLowBatteryGuard(c: Context, v: Boolean)']:
        if tok not in prefs:
            e.append('Prefs 开关缺: ' + tok[:58])
    if 'if (!Prefs.lowBatteryGuard(c)) return null' not in gate:
        e.append('BatteryGate 没尊重开关')
    for tok in ['internal fun LowBatteryGuardRow(c: Context)',
                'SettingLine(UiIcon.SHIELD, "低电量保护"',
                '远端 / 试听 / 单段不受影响',
                'Prefs.setLowBatteryGuard(c, it)',
                '"自动续跑 · 低电量保护"',
                '            AutoResumeRow(c)\n            LowBatteryGuardRow(c)']:
        if tok not in settings:
            e.append('设置页开关缺: ' + tok[:58])
    if 'BATTERY' in man.upper():
        e.append('多了电池权限')

    # ④ 登记与守恒
    if '"r413_verify.py"' not in r135:
        e.append('现行清单漏登记 r413')
    if 'run: python3 scripts/r413_verify.py\n' not in ci:
        e.append('CI 漏挂 r413 正向')
    if 'python3 scripts/r413_verify.py --selftest' not in ci:
        e.append('CI 漏挂 r413 反向')
    if 'R413 续跑转圈定位' not in status:
        e.append('STATUS 没登记 R413 欠账')
    if '1.1.66' not in s['changelog']:
        e.append('CHANGELOG 没写 1.1.66 段')
    for p, toks in CHAIN.items():
        tt = s.get('chain:' + str(p.relative_to(ROOT)), '')
        for k in toks:
            if k not in tt:
                e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try:
        old = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('基线不可读: %s' % ex)
        old = {}
    live = live_counts()
    for f, cnt in old.items():
        if f not in live:
            e.append('功能守恒: 文件消失 ' + f)
            continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    e += logic()
    return e


def selftest():
    base = sources()
    ok = 0
    for name, mut in BAD:
        d = dict(base)
        mut(d)
        if check(d):
            ok += 1
        else:
            print('  反向注入漏放:', name)
    print('R413 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        print('R413 FAIL')
        for x in e:
            print(' -', x)
        return 1
    print('R413 PASS · 续跑转圈跟真实章走 / 已固化章零转圈 / 低电开关默认开可关')
    print('  逻辑用例全绿 · 功能守恒零差异 · 链路符号全在')
    return 0


if __name__ == '__main__':
    sys.exit(main())
