#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R414 · 日志综合修复守门（1.1.66 真机诊断 → 1.1.67）。

用户原话：「有新发现的很多，一个个列出来记载小本本文落文件，综合分析一起修掉」
诊断：tingben_diag_20261128_114207.md（1.1.66 / 3919 行 / 11-28 11:42:09）

6 条真问题 → 3 个根因 → 整体方案：
  A 开局没有单一真值源：-100 / 0/0 / 203→0 / 空 detail 前导分隔符
  B 章进度两种口径混算：R413 回归 bottom=0 → 100（第 4 章才 81%）
  C 长耗时阶段没有心跳契约：章转码 4~5 分钟零日志

守：
  · start() 从恢复态带出 seedDone/seedTotal/chapterProgress，begin() 透传
  · emit 下球取「最靠前未完成章」，绝不漏 -1f；total<=0 不画不记
  · restoredChapter 已固化章 1f、perChapterDone 只累计未固化章散段
  · OverlayProgress clamp 0..1 + total<=0 不记日志（双保险）
  · CHAPTER_ENCODE_BEGIN / PULSE / END 心跳，不动 done/total
  · EventLog 空 detail 不加前导「 · 」
  · 4 次启动开局帧做成 fixture，-100 / 0/0 / 0→100 一律不许回潮
  · 零权限 / 零依赖 / 功能守恒 / 现行清单与 CI 登记

沙盒无 Android SDK，静态绿 != 编译过；真编译由 rc / main 两次 CI 负责。
用法：python3 scripts/r414_verify.py [--selftest]
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'producer': APP / 'core/Producer.kt',
    'service': APP / 'ProduceService.kt',
    'overlay': APP / 'OverlayProgress.kt',
    'ops': APP / 'core/AudioOps.kt',
    'eventlog': APP / 'diag/EventLog.kt',
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
    APP / 'relay/RelayService.kt': ['RELAY_DEL'],
    APP / 'core/BatteryGate.kt': ['fun bookBlockReason('],
}


def sources():
    d = {k: (v.read_text(encoding='utf-8', errors='replace') if v.exists() else '')
         for k, v in FILES.items()}
    for p in CHAIN:
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


# ── 纯内存复刻：与 Kotlin 同语义，用来把真机 bug 复现出来做对照 ──

def emit_chapter_pct(progress, legacy=False):
    """复刻 Producer.emit 的下球取值。legacy=True = 1.1.66 旧口径。"""
    if legacy:
        pct, idx = -1.0, -1
        for k, v in progress.items():
            if k > idx:
                idx, pct = k, v
        return pct  # 哨兵 -1f 会原样漏进日志 → bottom=-100
    pct, idx = -1.0, 1 << 30
    for k, v in progress.items():
        if v < 1.0 and k < idx:
            idx, pct = k, v
    if pct < 0:
        # R415 · 空表 = 真值还没算出来，保持 -1 单球哨兵，不装作 0%
        return 1.0 if progress else -1.0
    return pct


def restored_chapter(sealed, done_by_ch, totals, legacy=False):
    """复刻续跑开局逐章基线。legacy=True = R413 的全表 + 灌满增量。"""
    if legacy:
        return {ch: min(max((done_by_ch.get(ch, 0) / max(total, 1)), 0.0), 1.0)
                for ch, total in totals.items()}
    return {ch: (1.0 if ch in sealed else
                 min(max((done_by_ch.get(ch, 0) / max(total, 1)), 0.0), 1.0))
            for ch, total in totals.items()}


def per_chapter_done(sealed, done_by_ch, ci, legacy=False):
    """复刻增量计数：已固化章不许进 perChapterDone。"""
    if legacy:
        out = dict(done_by_ch)
    else:
        out = {k: v for k, v in done_by_ch.items() if k not in sealed}
    if ci >= 0 and (legacy or ci not in sealed):
        out[ci] = out.get(ci, 0) + 1
    return out


def overlay_frame(total, ch_pct, legacy=False):
    """复刻 OverlayProgress.update 的展示帧。返回 (bottom, logged)。"""
    if legacy:
        return int(ch_pct * 100), True, 2
    shown = min(max(ch_pct, 0.0), 1.0)
    rings = 2 if ch_pct >= 0 else 1
    return int(shown * 100), total > 0, rings


def kv_line(detail, fields, legacy=False):
    """复刻 EventLog.log 的 detail + fields 拼接。"""
    kept = [(k, v) for k, v in fields.items() if ('%s=%s' % (k, v)) not in detail]
    raw = ' · '.join('%s=%s' % (k, v) for k, v in kept)
    if legacy:
        return detail + ''.join(' · %s=%s' % (k, v) for k, v in kept)
    if not raw:
        return detail
    if not detail.strip():
        return raw
    return detail + ' · ' + raw


def opening_emit_done(seed_done, total, legacy=False):
    """复刻 run() 第 741 行占位 emit 的 done 参数。legacy=True = 硬写 0。"""
    if legacy:
        return 0
    return min(max(seed_done, 0), total)


# 1.1.66 真机 fixture：4 次启动的开局帧（坏值）
FIXTURE_BAD = [
    ('01:09:11.134', 0, 0, -100),
    ('01:09:19.651', 0, 26678, -100),
    ('06:12:19.199', 0, 0, -100),
    ('06:12:32.053', 0, 26678, -100),
    ('06:12:44.485', 33, 26678, -100),
    ('08:29:39.257', 0, 0, -100),
    ('08:29:51.359', 0, 26678, -100),
    ('08:30:01.484', 96, 26678, -100),
    ('11:39:35.472', 0, 0, -100),
    ('11:39:45.668', 0, 26678, -100),
    ('11:39:54.511', 203, 26678, 0),    # R413 回归：第 4 章实际约 81%
    ('11:40:43.668', 204, 26678, 100),  # R413 回归：灌满 → 永远 100%
]


def logic():
    e = []
    # ① 旧口径必须能把真机 bug 复现出来（否则守门是假绿）
    if int(emit_chapter_pct({}, legacy=True) * 100) != -100:
        e.append('逻辑：旧哨兵没复现 bottom=-100')
    full = {0: 1.0, 1: 1.0, 2: 1.0, 3: 0.81, 4: 0.0, 337: 0.0}
    if abs(emit_chapter_pct(full, legacy=True) - 0.0) > 1e-6:
        e.append('逻辑：旧「章号最大章」没复现 bottom=0')
    # ② 新口径：取最靠前未完成章
    if abs(emit_chapter_pct(full) - 0.81) > 1e-6:
        e.append('逻辑：新口径没取到第 4 章的 81%')
    if emit_chapter_pct({}) != -1.0:
        e.append('逻辑：空表没保持单球哨兵 -1')
    if emit_chapter_pct({0: 1.0, 1: 1.0}) != 1.0:
        e.append('逻辑：全完成没给 1')
    if emit_chapter_pct(full) < 0:
        e.append('逻辑：有真值时仍漏负数')
    # ③ 已固化章不进增量：第一段后不能跳到 100%
    sealed = {0, 1, 2}
    totals = {0: 64, 1: 54, 2: 58, 3: 33, 4: 40}
    done = {0: 64, 1: 54, 2: 58, 3: 27}
    base = restored_chapter(sealed, {k: v for k, v in done.items()
                                     if k not in sealed}, totals)
    if base[0] != 1.0 or base[1] != 1.0 or base[2] != 1.0:
        e.append('逻辑：已固化章没标 1f')
    if abs(base[3] - 27 / 33) > 1e-6:
        e.append('逻辑：第 4 章基线不是 27/33≈81%')
    after = per_chapter_done(sealed, {k: v for k, v in done.items()
                                      if k not in sealed}, 3)
    cur = min(max(after.get(3, 0) / totals[3], 0.0), 1.0)
    if abs(cur - 28 / 33) > 1e-6:
        e.append('逻辑：第一段后当前章被灌满（旧 bug 是 clamp 到 1f）')
    if 0 in after or 2 in after:
        e.append('逻辑：已固化章混进了增量计数')
    # ④ 旧 R413 形态必须能复现「第一段后 100%」
    legacy_after = per_chapter_done(sealed, done, 0, legacy=True)
    if min(max(legacy_after.get(0, 0) / totals[0], 0.0), 1.0) != 1.0:
        e.append('逻辑：旧灌满行为没复现')
    # ⑤ 占位帧不记日志 / clamp
    if overlay_frame(0, -1.0)[1]:
        e.append('逻辑：total<=0 仍写诊断日志')
    if overlay_frame(26678, -1.0)[0] != 0:
        e.append('逻辑：-1f 仍记成 bottom=-100')
    if overlay_frame(26678, -1.0)[2] != 1:
        e.append('逻辑：单球哨兵没标成 rings=1')
    if overlay_frame(26678, 0.81)[2] != 2:
        e.append('逻辑：双球没标成 rings=2')
    if overlay_frame(26678, 0.81)[0] != 81:
        e.append('逻辑：正常章进度被改坏')
    # ⑥ 空 detail 前导分隔符
    if kv_line('', {'book': 'abc'}, legacy=True).startswith(' ·'):
        pass  # 旧行为确实有前导，确认复现
    else:
        e.append('逻辑：旧拼接没复现前导分隔符')
    if kv_line('', {'book': 'abc'}) != 'book=abc':
        e.append('逻辑：空 detail 仍带前导分隔符')
    if kv_line('正在生成', {'done': '3'}) != '正在生成 · done=3':
        e.append('逻辑：非空 detail 的旧口径被改坏')
    if kv_line('', {}) != '':
        e.append('逻辑：空 detail 空 fields 不该凭空多字符')
    # ⑦ fixture：修后每一帧都不许再出现坏值
    for ts, done_n, total_n, bottom in FIXTURE_BAD:
        _, logged, _ = overlay_frame(total_n, -1.0 if bottom == -100 else bottom / 100.0)
        if bottom == -100 and logged and total_n > 0:
            # clamp 后日志里的数字必须是 0，不是 -100
            if overlay_frame(total_n, -1.0)[0] != 0:
                e.append('逻辑：fixture %s 仍产出 -100' % ts)
    # ⑧ R415 漏网：11:39:45.668 / 08:30:01.484 等「total>0 但 done=0」帧
    if opening_emit_done(203, 26678, legacy=True) != 0:
        e.append('逻辑：旧 741 硬 0 没复现')
    if opening_emit_done(203, 26678) != 203:
        e.append('逻辑：R415 占位帧没带恢复真值')
    if opening_emit_done(0, 26678) != 0:
        e.append('逻辑：全新书不该凭空多段')
    if opening_emit_done(99999, 26678) != 26678:
        e.append('逻辑：seedDone 没夹到 total 内')
    # ⑨ fixture：修后每次续跑开局 total>0 的帧，done 必须等于恢复值，不许 0
    for ts, done_n, total_n, bottom in FIXTURE_BAD:
        if total_n > 0 and done_n > 0:
            # 修后坏帧里的 done=0 会被 seedDone 顶掉
            seed = {1: 0, 2: 33, 3: 96, 4: 203}.get(
                {'01': 1, '06': 2, '08': 3, '11': 4}[ts[:2]], 0)
            if seed and opening_emit_done(seed, total_n) == 0:
                e.append('逻辑：fixture %s 开局仍归零' % ts)
    return e


BAD = [
    ('start 不带恢复真值', lambda d: d.update(producer=d['producer'].replace(
        'val prior = _state.value', 'val prior = Progress()', 1))),
    ('seedDone 被写死 0', lambda d: d.update(producer=d['producer'].replace(
        'val seedDone = prior.done.coerceAtLeast(0)', 'val seedDone = 0', 1))),
    ('begin 不透传 seed', lambda d: d.update(producer=d['producer'].replace(
        'begin(c, book.title, seedDone, seedTotal)', 'begin(c, book.title)', 1))),
    ('begin 不接 seed', lambda d: d.update(service=d['service'].replace(
        'fun begin(c: Context, title: String, done: Int = 0, total: Int = 0): Boolean',
        'fun begin(c: Context, title: String): Boolean', 1))),
    ('begin 又硬清零', lambda d: d.update(service=d['service'].replace(
        'lastDone = done.coerceAtLeast(0)', 'lastDone = 0', 1))),
    ('emit 退回取章号最大章', lambda d: d.update(producer=d['producer'].replace(
        'if (v < 1f && k < chIdx) { chIdx = k; chPct = v }',
        'if (k > chIdx) { chIdx = k; chPct = v }', 1))),
    ('emit 又漏 -1f 哨兵', lambda d: d.update(producer=d['producer'].replace(
        'if (chPct < 0f && chapterProgress.isNotEmpty()) chPct = 1f', '', 1))),
    ('total<=0 仍画浮窗', lambda d: d.update(producer=d['producer'].replace(
        'if (total > 0) appCtx?.let { ctx ->', 'appCtx?.let { ctx ->', 1))),
    ('Overlay 不 clamp', lambda d: d.update(overlay=d['overlay'].replace(
        'val chapterPctNow = rawPct.coerceIn(0f, 1f)',
        'val chapterPctNow = target.chapterPct', 1))),
    ('Overlay 占位帧仍记日志', lambda d: d.update(overlay=d['overlay'].replace(
        'if (totalNow > 0 && sig != lastPctLog)', 'if (sig != lastPctLog)', 1))),
    # R417 · Producer 现在有两处同源判定（开局 restoredChapter + 循环
    # chapterSnapshot）。单处替换另一处仍合规 → 假绿，改全量替换。
    ('已固化章又进增量', lambda d: d.update(producer=d['producer'].replace(
        'ci !in sealedChapters)', 'true)'))),
    ('restoredChapter 不标固化章', lambda d: d.update(producer=d['producer'].replace(
        'if (ch in sealedChapters) 1f', '0f'))),
    ('sealedChapters 被摘', lambda d: d.update(producer=d['producer'].replace(
        'val sealedChapters = doneChapterFiles.keys.toSet()', '', 1))),
    ('转码心跳 BEGIN 被摘', lambda d: d.update(ops=d['ops'].replace(
        '"CHAPTER_ENCODE_BEGIN"', '"GONE"', 1))),
    ('转码心跳 PULSE 被摘', lambda d: d.update(ops=d['ops'].replace(
        '"CHAPTER_ENCODE_PULSE"', '"GONE"', 1))),
    ('心跳节流被摘（变每行刷屏）', lambda d: d.update(ops=d['ops'].replace(
        'if (now - lastPulse >= 30_000L)', 'if (true)', 1))),
    ('心跳不再透传停止判定', lambda d: d.update(ops=d['ops'].replace(
        'stopCheck?.invoke() == true', 'false'))),
    ('转码 END 被摘', lambda d: d.update(ops=d['ops'].replace(
        '"CHAPTER_ENCODE_END"', '"GONE"', 1))),
    ('空 detail 前导分隔符回潮', lambda d: d.update(eventlog=d['eventlog'].replace(
        'detail.isBlank() -> kvRaw', 'detail.isBlank() -> " · " + kvRaw', 1))),
    ('非空 detail 拼接被改坏', lambda d: d.update(eventlog=d['eventlog'].replace(
        'else -> " · $kvRaw"', 'else -> kvRaw', 1))),
    ('新增电池权限', lambda d: d.update(manifest=d['manifest'].replace(
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.INTERNET" />\n'
        '<uses-permission android:name="android.permission.BATTERY_STATS" />', 1))),
    ('现行清单漏登记', lambda d: d.update(r135=d['r135'].replace('"r414_verify.py"', '', 1))),
    ('CI 漏挂正向', lambda d: d.update(ci=d['ci'].replace(
        'run: python3 scripts/r414_verify.py\n', 'run: echo skip\n', 1))),
    ('CI 漏挂反向', lambda d: d.update(ci=d['ci'].replace(
        'python3 scripts/r414_verify.py --selftest', '', 1))),
    ('欠账未登记', lambda d: d.update(status=d['status'].replace('R414 日志综合修复', ''))),
    ('链路符号丢失', lambda d: d.update(**{
        'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('741 占位帧又硬写 0', lambda d: d.update(producer=d['producer'].replace(
        'emit("正在生成声音", seedDone, annotated.size, gateFails,',
        'emit("正在生成声音", 0, annotated.size, gateFails,', 1))),
    ('741 不传恢复态逐章进度', lambda d: d.update(producer=d['producer'].replace(
        'chapterProgress = seedState.chapterProgress)', 'chapterProgress = emptyMap())', 1))),
    ('seedDone 被写死 0', lambda d: d.update(producer=d['producer'].replace(
        'val seedDone = seedState.done.coerceIn(0, annotated.size)', 'val seedDone = 0', 1))),
    ('emit 空表又装作 0%', lambda d: d.update(producer=d['producer'].replace(
        'if (chPct < 0f && chapterProgress.isNotEmpty()) chPct = 1f',
        'if (chPct < 0f) chPct = 0f', 1))),
    ('单球哨兵又画下球', lambda d: d.update(overlay=d['overlay'].replace(
        'OverlaySource.PRODUCE && rawPct >= 0f',
        'OverlaySource.PRODUCE && chapterPctNow >= 0f', 1))),
    ('Overlay 不 clamp 日志', lambda d: d.update(overlay=d['overlay'].replace(
        'val chapterPctNow = rawPct.coerceIn(0f, 1f)',
        'val chapterPctNow = rawPct', 1))),
    ('rings 标识被摘', lambda d: d.update(overlay=d['overlay'].replace(
        '"rings=${if (rawPct >= 0f) 2 else 1} " +', '', 1))),
    ('功能守恒基线被改', lambda d: d.update(baseline='{}')),
]


def check(s):
    e = []
    for k in FILES:
        if not s.get(k):
            e.append('缺文件: ' + k)
    if e:
        return e
    prod, svc, ov, ops, el = (s['producer'], s['service'], s['overlay'],
                              s['ops'], s['eventlog'])
    man, r135, ci, status = s['manifest'], s['r135'], s['ci'], s['status']

    # ① 开局真值
    for tok in ['val prior = _state.value',
                'val seedDone = prior.done.coerceAtLeast(0)',
                'val seedTotal = prior.total.coerceAtLeast(0)',
                'chapterProgress = prior.chapterProgress)',
                'begin(c, book.title, seedDone, seedTotal)']:
        if tok not in prod:
            e.append('开局真值缺: ' + tok[:60])
    if 'Progress(true, "正在准备", 0, 0, 0, "正在启动…")' in prod:
        e.append('start() 又硬清零回潮')
    for tok in ['fun begin(c: Context, title: String, done: Int = 0, total: Int = 0): Boolean',
                'lastDone = done.coerceAtLeast(0)',
                'lastTotal = total.coerceAtLeast(0)']:
        if tok not in svc:
            e.append('begin seed 缺: ' + tok[:60])

    # ② 章进度单一口径
    for tok in ['if (v < 1f && k < chIdx) { chIdx = k; chPct = v }',
                'if (chPct < 0f && chapterProgress.isNotEmpty()) chPct = 1f',
                'if (total > 0) appCtx?.let { ctx ->']:
        if tok not in prod:
            e.append('下球口径缺: ' + tok[:60])
    if 'for ((k, v) in chapterProgress) if (k > chIdx)' in prod:
        e.append('下球回潮「章号最大章」')
    for tok in ['val seedState = _state.value',
                'val seedDone = seedState.done.coerceIn(0, annotated.size)',
                'emit("正在生成声音", seedDone, annotated.size, gateFails,',
                'chapterProgress = seedState.chapterProgress)',
                'if (chPct < 0f && chapterProgress.isNotEmpty()) chPct = 1f']:
        if tok not in prod:
            e.append('R415 开局真值缺: ' + tok[:60])
    if 'emit("正在生成声音", 0, annotated.size, gateFails,' in prod:
        e.append('741 又硬写 0 回潮')
    for tok in ['val rawPct = target.chapterPct',
                'val chapterPctNow = rawPct.coerceIn(0f, 1f)',
                'OverlaySource.PRODUCE && rawPct >= 0f)',
                '"rings=${if (rawPct >= 0f) 2 else 1} " +',
                'if (totalNow > 0 && sig != lastPctLog)']:
        if tok not in ov:
            e.append('Overlay 双保险缺: ' + tok[:60])

    # ③ R413 回归修复
    for tok in ['val sealedChapters = doneChapterFiles.keys.toSet()',
                'ci !in sealedChapters)',
                'if (ch in sealedChapters) 1f']:
        if tok not in prod:
            e.append('已固化章隔离缺: ' + tok[:60])

    # ④ 转码心跳
    for tok in ['"CHAPTER_ENCODE_BEGIN"', '"CHAPTER_ENCODE_PULSE"',
                '"CHAPTER_ENCODE_END"', 'if (now - lastPulse >= 30_000L)',
                'stopCheck?.invoke() == true',
                'toM4a(c, wav, m4a, pulseCheck)']:
        if tok not in ops:
            e.append('转码心跳缺: ' + tok[:60])
    if 'if (true)' in ops:
        e.append('心跳节流被写成恒真（会刷屏）')

    # ⑤ 空 detail 拼接
    for tok in ['joinToString(" · ")', 'detail.isBlank() -> kvRaw',
                'else -> " · $kvRaw"']:
        if tok not in el:
            e.append('EventLog 拼接缺: ' + tok[:60])

    # ⑥ 登记与守恒
    if '"r414_verify.py"' not in r135:
        e.append('现行清单漏登记 r414')
    if 'run: python3 scripts/r414_verify.py\n' not in ci:
        e.append('CI 漏挂 r414 正向')
    if 'python3 scripts/r414_verify.py --selftest' not in ci:
        e.append('CI 漏挂 r414 反向')
    if 'R414 日志综合修复' not in status:
        e.append('STATUS 没登记 R414')
    if '1.1.67' not in s['changelog']:
        e.append('CHANGELOG 没写 1.1.67 段')
    if 'BATTERY' in man.upper():
        e.append('多了电池权限')
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
    print('R414 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)


def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        print('R414 FAIL')
        for x in e:
            print(' -', x)
        return 1
    print('R414 PASS · 开局真值 / 章进度单一口径 / 转码心跳 / 空字段拼接')
    print('  4 次启动 fixture 全对照 · 功能守恒零差异 · 链路符号全在')
    return 0


if __name__ == '__main__':
    sys.exit(main())
