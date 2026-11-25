#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R404 · 1.1.57 第二份真机日志（tingben_diag_20261126_011033.md）守门。

日志实证（用户明确「新包没装，手机上还是上一个版本」，环境块 version=1.1.57）：
  · 00:58~01:01 停止制作留下的 g_cancel → 五次 MIXER_TRIAL_EMPTY code=-5，
    1.1.58 已在 ClipSynth 清标志（本守门验它没被摘）。
  · 01:01:59 SwipeUpClean 冷启动后进程级标志自然清零，合成恢复正常；
    01:02~01:08 同一条 id=f14a2e4869334e68 每轮 MIXER_TRIAL + RELAY_SYNTH
    成功（50~60 秒！），紧接着 RELAY_POLL_ERR HTTP 422 → 请求文件不删 →
    下一轮又重做同一条，ARCHIVE_ADD 从 92 涨到 100 封顶。
  · 根因：GitHub Contents API 更新已存在文件必须带 sha，旧 putObj 一律裸 PUT；
    out/status 一旦写过，再写就 422，异常被当成「断网轮询失败」。

纯静态 + 纯内存逻辑用例；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r404_verify.py [--selftest]
"""
import pathlib, sys, json, re

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'client': APP / 'relay/RelayClient.kt',
    'service': APP / 'relay/RelayService.kt',
    'archive': APP / 'core/RequestArchive.kt',
    'resume': APP / 'core/TaskResume.kt',
    'clip': APP / 'core/ClipSynth.kt',
    'arbiter': APP / 'core/SynthArbiter.kt',
    'ci': ROOT / '.github/workflows/ci.yml',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}
CHAIN = {
    APP / 'relay/RelayClient.kt': ['fun putObj(', 'fun headSha(', 'fun deleteQueue(',
                                   'fun putDone(', 'fun putFailed(', 'fun putBusy('],
    APP / 'relay/RelayService.kt': ['RELAY_REQ', 'RELAY_SYNTH', 'RELAY_PUT_DONE',
                                    'RELAY_DEL', 'RELAY_POLL_ERR', 'client.putBusy(',
                                    'RequestArchive.add(this, RequestArchive.SRC_RELAY, text)'],
    APP / 'gateway/GatewaySynth.kt': ['ClipSynth.synthesize', 'longTaskBusy()'],
    APP / 'core/ClipSynth.kt': ['NativeEngine.nativeCancelReset()', 'MIXER_TRIAL_EMPTY'],
    APP / 'core/TaskResume.kt': ['fun recoverScope(', 'fun realProgress('],
    APP / 'core/Producer.kt': ['TASK_RECOVER_SCOPE', 'fun restore('],
    APP / 'ChapterHub.kt': ['HUB_START_GUARD', 'task?.phase in listOf('],
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

# ── 纯内存逻辑：复现日志里的 GitHub / 轮询 / 存档 / 自愈行为 ──────────
def sim_put(path_exists, sha_supplied, sha_correct=True, attempt=1):
    """GitHub Contents API PUT：已存在且没带对 sha → 422。"""
    if path_exists and (sha_supplied is None or not sha_correct):
        return 422
    return 201 if not path_exists else 200

def sim_old_put(path_exists):
    return 422 if path_exists else 201

def sim_loop(out_exists, status_exists, fixed_client):
    """一轮中继：合成成功 → 回写 out+status → 删 queue。返回 (done, deleted, err)。"""
    if fixed_client:
        a = sim_put(out_exists, 'sha' if out_exists else None)
        b = sim_put(status_exists, 'sha' if status_exists else None)
    else:
        a, b = sim_old_put(out_exists), sim_old_put(status_exists)
    if a >= 400 or b >= 400:
        return False, False, max(a, b)
    return True, True, 0

def sim_cooldown(items, cooldown, now):
    """回写失败冷却：请求留着，但本轮不重做合成。"""
    ready = [n for n in items if cooldown.get(n, 0) <= now]
    return ready

def sim_archive(entries, source, text, now, window=600):
    """同一来源同一文本在窗口内只留一条。返回 (是否新增, 条数)。"""
    for s, t, ts in entries:
        if s == source and t == text and now - ts < window:
            return False, len(entries)
    entries.append((source, text, now))
    return True, len(entries)

def sim_recover(dirs, only, whole_wav, total=26678):
    """R404 自愈：按磁盘分桶成品比，整本桶很小且旧桶明显更多才认误开。"""
    if only is not None:
        return None
    best, best_score, best_segs = None, 0, 0
    for name, segs, wav, done in dirs:
        if name == 'all' or name.startswith('h') or not all(c.isdigit() or c == '_' for c in name):
            continue
        score = wav + done
        if score > best_score:
            best_score, best, best_segs = score, {int(x) for x in name.split('_') if x.isdigit()}, segs
    if best is None or (best_score == 0 and best_segs == 0):
        return None
    if whole_wav > 32 or best_score < whole_wav * 2:
        return None
    return dict(only=best, total=best_segs or total, done=0, phase='stopped')

def logic():
    e = []
    # ① 日志里的 422：out/status 已存在，旧客户端每轮必 422
    if sim_old_put(True) != 422 or sim_old_put(False) != 201:
        e.append('逻辑：旧版裸 PUT 的 422 没复现')
    if sim_put(True, None) != 422 or sim_put(False, None) != 201:
        e.append('逻辑：GitHub sha 语义不对')
    if sim_put(True, 'sha') != 200:
        e.append('逻辑：带 sha 更新没成功')
    if sim_put(True, 'stale', False) != 422:
        e.append('逻辑：sha 过期没被拒')
    # ② 残留场景：日志里 out/status 已写、queue 没删
    ok, deleted, err = sim_loop(True, True, False)
    if ok or deleted or err != 422:
        e.append('逻辑：旧版残留场景应回写 422 且不删请求')
    ok, deleted, err = sim_loop(True, True, True)
    if not ok or not deleted or err != 0:
        e.append('逻辑：新版带 sha 应一轮闭环并删请求')
    ok, deleted, err = sim_loop(False, False, True)
    if not ok or not deleted:
        e.append('逻辑：全新请求回归')
    # ③ 冷却：请求留着，本轮不重做
    items = ['f14a2e4869334e68.json']
    cooldown = {'f14a2e4869334e68.json': 5000}
    if sim_cooldown(items, cooldown, 1000) != []:
        e.append('逻辑：冷却期内不该重做合成')
    if sim_cooldown(items, cooldown, 6000) != items:
        e.append('逻辑：冷却过了该继续补做')
    if items != ['f14a2e4869334e68.json']:
        e.append('逻辑：冷却不该把请求文件删掉')
    # ④ 存档去重：日志里 92→100 条封顶
    ents = []
    added, n = sim_archive(ents, 'relay', '同一句', 1000)
    if not added or n != 1:
        e.append('逻辑：第一条该留')
    added, n = sim_archive(ents, 'relay', '同一句', 1010)
    if added or n != 1:
        e.append('逻辑：10 分钟内重复不该入档')
    added, n = sim_archive(ents, 'relay', '另一句', 1011)
    if not added or n != 2:
        e.append('逻辑：不同文本该留')
    added, n = sim_archive(ents, 'gateway', '同一句', 1012)
    if not added or n != 3:
        e.append('逻辑：不同渠道不该互相去重')
    added, n = sim_archive(ents, 'relay', '同一句', 1000 + 601)
    if not added or n != 4:
        e.append('逻辑：过了窗口该重新留')
    # ⑤ 4/26678 自愈（这份日志运行快照的实证）
    dirs = [('all', 26678, 0, 0), ('2_3_4_5_6_7_8_9_10_11', 656, 34, 55)]
    r = sim_recover(dirs, None, 4)
    if r is None or r['total'] != 656 or r['only'] != set(range(2, 12)):
        e.append('逻辑：4/26678 没自愈回 10 章 656 段')
    if sim_recover(dirs, None, 0) is None:
        e.append('逻辑：0/26678 也该自愈')
    if sim_recover(dirs, None, 200) is not None:
        e.append('逻辑：整本真跑 200 段不该被抢')
    if sim_recover(dirs, None, 60) is not None:
        e.append('逻辑：旧桶不占明显多数不该被抢')
    if sim_recover(dirs, {1, 2}, 4) is not None:
        e.append('逻辑：已有明确选章不该被改写')
    if sim_recover([('all', 26678, 0, 0)], None, 0) is not None:
        e.append('逻辑：没有旧桶不该凭空造断点')
    return e

def check(s):
    e = []
    for k in FILES:
        if k not in s:
            e.append('缺文件: ' + k)
    if e:
        return e
    cl, sv, ac, rs, cp, ab = (s['client'], s['service'], s['archive'],
                             s['resume'], s['clip'], s['arbiter'])
    # ① 回写带 sha
    for tok in ['private fun headSha(path: String): String?',
                'if (e.code == 404) null else throw e',
                '.apply { if (sha != null) put("sha", sha) }',
                'if (e.code != 422 && e.code != 409) throw e',
                'if (e.code != 422 && e.code != 409) throw e']:
        if tok not in cl:
            e.append('回写带 sha 缺: ' + tok[:60])
    if cl.count('sha = headSha(path)') < 2:
        e.append('并发重试的重取 sha 被摘')
    # ② 冷却 + 日志正名（RELAY_POLL_ERR 仍在，不是删掉）
    for tok in ['RELAY_PUT_ERR', 'val putCooldownUntil = HashMap<String, Long>()',
                'putCooldownUntil[currentName!!] = until', 'val ready = items.filterNot',
                'phase = "put"', 'phase = "del"', '请求留着排队 · 稍后重试（不是断网）',
                'RELAY_POLL_ERR']:
        if tok not in sv:
            e.append('回写失败处理缺: ' + tok[:60])
    if sv.count('client.deleteQueue(item.name, reqSha)') != 2:
        e.append('正文即用即删的两处出口被改动')
    # ③ 存档去重（调用点保持 1 处，不破坏 r387）
    for tok in ['private const val DEDUPE_MS = 10 * 60 * 1000L',
                'all.any { it.source == source && it.text == text',
                'if (n == null) return']:
        if tok not in ac:
            e.append('存档去重缺: ' + tok[:60])
    if s['chain:app/src/main/java/com/tingben/app/relay/RelayService.kt'].count(
            'RequestArchive.add(this, RequestArchive.SRC_RELAY, text)') != 1:
        e.append('中继留存写入点被改动')
    # ④ 4/26678 自愈边界
    for tok in ['if (t.only != null || t.phase == "done") return null',
                'fun wavOf(d: File): Int', 'wholeWav > 32 || bestScore < wholeWav * 2']:
        if tok not in rs:
            e.append('自愈边界缺: ' + tok[:60])
    # ⑤ 上一轮的修复仍在（用户装的就是没修的 1.1.57）
    if 'NativeEngine.nativeCancelReset()' not in cp:
        e.append('R403 清 g_cancel 被摘')
    if 'fun tryAcquire(' not in ab:
        e.append('R401 合成仲裁被摘')
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
    ('headSha 被摘', lambda d: d.update(client=d['client'].replace(
        'private fun headSha(path: String): String?', 'private fun headShaGone(', 1))),
    ('404 不再当新建', lambda d: d.update(client=d['client'].replace(
        'if (e.code == 404) null else throw e', 'throw e', 1))),
    ('PUT 又不带 sha 了', lambda d: d.update(client=d['client'].replace(
        '.apply { if (sha != null) put("sha", sha) }', '', 1))),
    ('并发重试被摘', lambda d: d.update(client=d['client'].replace(
        'sha = headSha(path)', 'sha = null', 1))),
    ('回写失败不再区分', lambda d: d.update(service=d['service'].replace(
        'RELAY_PUT_ERR', 'RELAY_POLL_ERR', 1))),
    ('冷却被摘', lambda d: d.update(service=d['service'].replace(
        'val ready = items.filterNot', 'val ready = items.filter', 1))),
    ('失败仍每轮重做', lambda d: d.update(service=d['service'].replace(
        'putCooldownUntil[currentName!!] = until', '', 1))),
    ('删请求出口被摘', lambda d: d.update(service=d['service'].replace(
        'client.deleteQueue(item.name, reqSha)', '', 1))),
    ('存档去重被摘', lambda d: d.update(archive=d['archive'].replace(
        'if (n == null) return', '', 1))),
    ('去重窗被清零', lambda d: d.update(archive=d['archive'].replace(
        'private const val DEDUPE_MS = 10 * 60 * 1000L',
        'private const val DEDUPE_MS = 0L', 1))),
    ('自愈又退回 done>0 放行', lambda d: d.update(resume=d['resume'].replace(
        'if (wholeWav > 32 || bestScore < wholeWav * 2) return null',
        'if (t.done > 0) return null', 1))),
    ('清 g_cancel 被摘', lambda d: d.update(clip=d['clip'].replace(
        'try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }', '', 1))),
    ('链路符号', lambda d: d.update(**{'chain:app/src/main/java/com/tingben/app/relay/RelayClient.kt': '/* gone */'})),
    ('功能守恒', lambda d: d.update(baseline='{}')),
]

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d): ok += 1
        else: print('  反向注入漏放:', name)
    print('R404 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv:
        return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e: print('RED:', x)
        print('R404: %d 处红' % len(e))
        return 1
    print('R404 PASS · 回写带 sha / 422 冷却与正名 / 存档去重 / 4段自愈 / 老修复守恒')
    print('  逻辑用例全绿 · 功能守恒 %d 个 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__':
    sys.exit(main())
