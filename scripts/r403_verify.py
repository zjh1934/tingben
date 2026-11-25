#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R403 · 1.1.57 真机诊断日志（tingben_diag_20261126_003934.md）三处根因守门。

日志实证：
  · 00:38:20 覆盖安装冷启动 TASK_RESTORE phase=stopped real=89/656；
  · 00:38:44 点主按钮却走 HUB_START ch=0 → PRODUCE_MODE「整本」→
    6042 块 → 26678 段、原文 2243544 字、VOICE_CACHE_SWITCH 认领 0 份、0/26678；
  · 22:21 停止制作留下进程级 g_cancel，22:39~00:30 五次网关/中继合成
    全部 MIXER_TRIAL_EMPTY code=-5(QT_STATUS_CANCELLED) → RELAY_FAIL → 删请求。

纯静态 + 纯内存逻辑用例；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r403_verify.py [--selftest]
"""
import pathlib, sys, re, json

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'hub': APP / 'ChapterHub.kt',
    'resume': APP / 'core/TaskResume.kt',
    'producer': APP / 'core/Producer.kt',
    'clip': APP / 'core/ClipSynth.kt',
    'arbiter': APP / 'core/SynthArbiter.kt',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'core/Producer.kt': ['fun isBusy()', 'fun restore(', 'PRODUCE_MODE'],
    APP / 'core/ClipSynth.kt': ['fun synthesize(', 'NativeEngine.nativeCancelReset()'],
    APP / 'relay/RelayService.kt': ['RELAY_DEL', 'RELAY_SYNTH', 'RELAY_PUT_DONE'],
    APP / 'gateway/GatewaySynth.kt': ['ClipSynth.synthesize', 'longTaskBusy()'],
    APP / 'core/SynthArbiter.kt': ['fun tryAcquire(', 'fun awaitAndAcquire('],
}

def sources():
    d = {k: v.read_text(encoding='utf-8') for k, v in FILES.items() if v.exists()}
    for p in CHAIN:
        if p.exists():
            d['chain:' + str(p.relative_to(ROOT))] = p.read_text(encoding='utf-8')
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

# ── 纯内存逻辑用例：复现日志里的状态判定 ──────────────────────────────
PHASES = {'stopped', 'interrupted', 'blocked', 'reconfirm'}
STAGES = {'没完成', '已停止', '出错了'}

def stalled(stage, phase, running=False, same_book=True):
    """R403 后的目录页断点判定；旧版只认 stage。"""
    return (not running) and same_book and (phase in PHASES or stage in STAGES)

def old_stalled(stage, phase, running=False, same_book=True):
    return (not running) and same_book and stage in STAGES

def start_allowed(only, phase, same_book=True):
    """R403 第二道闸：没勾章 + 同书有未完成档案不许开整本。"""
    pending = same_book and phase != 'done'
    return not (only is None and pending)

def sim_recover(dirs, total_now, only_now, whole_wav=0):
    """复现 R404 后的 recoverScope：按磁盘分桶成品比，不再看 t.done。"""
    if only_now is not None:
        return None
    best, best_score, best_segs = None, 0, 0
    for name, segs, wav, done in dirs:
        if name == 'all' or name.startswith('h') or not all(c.isdigit() or c == '_' for c in name):
            continue
        only = {int(x) for x in name.split('_') if x.isdigit()}
        if not only:
            continue
        score = wav + done
        if score > best_score:
            best_score, best, best_segs = score, only, segs
    if best is None or (best_score == 0 and best_segs == 0):
        return None
    if whole_wav > 32 or best_score < whole_wav * 2:
        return None
    return dict(only=best, total=best_segs or total_now, done=0, phase='stopped')

def sim_cancel(stop_called, reset_before_short):
    """g_cancel 进程级标志：停止置位，只有 reset 才清。"""
    g = stop_called
    if reset_before_short:
        g = False
    return -5 if g else 0

def logic():
    e = []
    # ① 冷启动 restore 的 stage 是「上次中断」：旧判定 False（正是本次懵逼），新判定 True
    if old_stalled('上次中断', 'stopped'):
        e.append('逻辑：旧判定不该放行「上次中断」')
    if not stalled('上次中断', 'stopped'):
        e.append('逻辑：冷启动断点没被认出来')
    if not stalled('已停止', 'stopped'):
        e.append('逻辑：热启动断点回归')
    if not stalled('上次中断', 'interrupted'):
        e.append('逻辑：崩溃中断断点没认出来')
    if stalled('上次中断', 'done'):
        e.append('逻辑：已完成任务被当成断点')
    if stalled('上次中断', 'stopped', running=True):
        e.append('逻辑：运行中不该显示继续')
    if stalled('上次中断', 'stopped', same_book=False):
        e.append('逻辑：别书的档案串进来了')
    # ② 防覆盖闸
    if start_allowed(None, 'stopped'):
        e.append('逻辑：没勾章 + 有断点竟然放行整本重开')
    if not start_allowed({2, 3, 4}, 'stopped'):
        e.append('逻辑：勾了章反而被拦')
    if not start_allowed(None, 'done'):
        e.append('逻辑：已完成任务后开新整本被误拦')
    # ③ 误开整本自愈
    dirs = [('all', 26678, 0, 0), ('2_3_4_5_6_7_8_9_10_11', 656, 34, 55), ('h9f3c', 0, 0, 0)]
    r = sim_recover(dirs, 26678, None, 0)
    if r is None or r['only'] != set(range(2, 12)) or r['total'] != 656:
        e.append('逻辑：旧 10 章分桶没被找回')
    # R404 · 01:10 日志里误开的整本已跑到 4/26678，仍要能回到 89/656
    r4 = sim_recover(dirs, 26678, None, 4)
    if r4 is None or r4['total'] != 656:
        e.append('逻辑：整本桶已做 4 段时旧断点没被找回')
    if sim_recover(dirs, 26678, {1, 2}, 4) is not None:
        e.append('逻辑：已有明确选章不该被改写')
    if sim_recover(dirs, 26678, None, 200) is not None:
        e.append('逻辑：整本真跑很久不该被抢')
    if sim_recover(dirs, 26678, None, 60) is not None:
        e.append('逻辑：旧桶不占明显多数不该被抢')
    if sim_recover([('all', 26678, 0, 0)], 26678, None, 0) is not None:
        e.append('逻辑：没有旧桶不该凭空造断点')
    # ④ -5 残留
    if sim_cancel(True, False) != -5:
        e.append('逻辑：停止后不清标志应复现 -5')
    if sim_cancel(True, True) != 0:
        e.append('逻辑：短任务清标志后应能合成')
    if sim_cancel(False, False) != 0:
        e.append('逻辑：没停过不该误报 -5')
    return e

def check(s):
    e = []
    for k in FILES:
        if k not in s:
            e.append('缺文件: ' + k)
    if e:
        return e
    h, rs, pr, cl, ab, ci = (s['hub'], s['resume'], s['producer'],
                             s['clip'], s['arbiter'], s['ci'])
    # ① 断点判定：必须认档案 phase，且保留旧 stage 集合兜底
    for tok in ['task?.phase in listOf("stopped", "interrupted", "blocked", "reconfirm")',
                'listOf("没完成", "已停止", "出错了")']:
        if tok not in h:
            e.append('断点判定缺: ' + tok[:56])
    # ② 防覆盖闸
    for tok in ['HUB_START_GUARD', 'val pending = task?.takeIf {',
                'if (only == null && pending != null) {',
                '这本书还有上次没做完的', '要整本重做请先勾选章']:
        if tok not in h:
            e.append('防覆盖闸缺: ' + tok[:56])
    # ③ 自愈
    for tok in ['fun recoverScope(c: Context, t: TaskInfo): TaskInfo?',
                'if (t.only != null || t.phase == "done") return null',
                'fun wavOf(d: File): Int', 'wholeWav > 32 || bestScore < wholeWav * 2',
                'v11/direct', 'SegPlan.readMap(d)?.size', 'AudioTree.readDone(d).size',
                't.copy(only = pick, total = total, done = 0', 'phase = "stopped"']:
        if tok not in rs:
            e.append('recoverScope 缺: ' + tok[:56])
    for tok in ['TaskResume.recoverScope(c, raw)', 'TASK_RECOVER_SCOPE',
                'TaskResume.save(c, recovered)']:
        if tok not in pr:
            e.append('restore 没接自愈: ' + tok[:56])
    # ④ 清取消残留
    if 'NativeEngine.nativeCancelReset()' not in cl:
        e.append('ClipSynth 没清 g_cancel 残留')
    if 'QT_STATUS_CANCELLED' not in cl and 'code=-5' not in cl:
        e.append('ClipSynth 没写清 -5 根因')
    # ⑤ 上一轮仲裁仍在（短任务与长任务互斥是 reset 安全的前提）
    for tok in ['fun tryAcquire(', 'fun awaitAndAcquire(', 'fun producerBusy()']:
        if tok not in ab:
            e.append('合成仲裁被摘（reset 不再安全）: ' + tok[:48])
    # ⑥ 守恒
    for p, toks in CHAIN.items():
        t = s.get('chain:' + str(p.relative_to(ROOT)))
        if t is None:
            e.append('链路文件消失: ' + p.name); continue
        for k in toks:
            if k not in t:
                e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try:
        bl = json.loads(s['baseline'])['files']
    except Exception as ex:
        e.append('基线不可读: %s' % ex); bl = {}
    live = live_counts()
    for f, cnt in bl.items():
        if f not in live:
            e.append('功能守恒: 文件消失 ' + f); continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    e += logic()
    return e

BAD = [
    ('断点判定退回只认 stage', lambda d: d.update(hub=d['hub'].replace(
        'task?.phase in listOf("stopped", "interrupted", "blocked", "reconfirm") ||\n            ', '', 1))),
    ('防覆盖闸被摘', lambda d: d.update(hub=d['hub'].replace(
        'HUB_START_GUARD', 'HUB_START', 1))),
    ('防覆盖闸不拦了', lambda d: d.update(hub=d['hub'].replace(
        'if (only == null && pending != null) {', 'if (false) {', 1))),
    ('自愈函数被摘', lambda d: d.update(resume=d['resume'].replace(
        'fun recoverScope(c: Context, t: TaskInfo): TaskInfo?', 'fun recoverScopeGone(', 1))),
    ('自愈抢走真跑起来的整本', lambda d: d.update(resume=d['resume'].replace(
        'if (wholeWav > 32 || bestScore < wholeWav * 2) return null',
        'if (false) return null', 1))),
    ('自愈不认旧桶段数', lambda d: d.update(resume=d['resume'].replace(
        'SegPlan.readMap(d)?.size', '0', 1))),
    ('restore 没接自愈', lambda d: d.update(producer=d['producer'].replace(
        'TaskResume.recoverScope(c, raw)', 'null', 1))),
    ('自愈没写回档案', lambda d: d.update(producer=d['producer'].replace(
        'TaskResume.save(c, recovered)', '', 1))),
    ('清取消残留被摘', lambda d: d.update(clip=d['clip'].replace(
        'try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }', '', 1))),
    ('合成仲裁被摘', lambda d: d.update(arbiter=d['arbiter'].replace('fun tryAcquire(', 'fun gone(', 1))),
    ('链路符号', lambda d: d.update(**{'chain:app/src/main/java/com/tingben/app/core/ClipSynth.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline='{}')),
]

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d): ok += 1
        else: print('  反向注入漏放:', name)
    print('R403 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e: print('RED:', x)
        print('R403: %d 处红' % len(e))
        return 1
    print('R403 PASS · 冷启动断点判定 / 防覆盖闸 / 误开整本自愈 / g_cancel 残留')
    print('  逻辑用例全绿（含 R404 的 4/26678 自愈边界）· 功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
