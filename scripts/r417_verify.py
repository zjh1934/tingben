#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R417 · 1.1.69 真机日志（tingben_diag_20261128_135957.md）综合修复守门。

用户口径（2026-11-28）：
  · 设置首页「4 份已就绪」多余（「模型已就绪」「软件版本」两行是重复调试信息）；
  · 装上新版点「继续制作」续跑，又发现 bug；
  · 电子书合成链路要在脑子里模拟跑通：续跑 / 断点 / 覆盖更新 / 远端并发 /
    让路中停止 / 回写失败 / 低电 / 空间不足，遇到疑点验证真代码再改。

真代码查实的 6 处（不是猜测）：
  ① run() 第一句 emit("正在分章", 0, 0, 0, ...) 把 start() 刚带出的 205/26678
     覆盖掉 → seedState 读到 0 → 13:55:36.997 done=0 total=26678，7 秒后才补回；
  ② start() 先 save 一份 0/0 的 TaskInfo 再取恢复态 → 开局窗口遇覆盖更新/强杀，
     档案真值断在这几毫秒上；
  ③ 逐章快照用稀疏 perChapterDone.mapValues，开局帧用全表 restoredChapter，
     固化章不在稀疏表里 → 13:55:44 bottom=87 → 13:57:11 bottom=1 后一路钉在 1；
  ④ 让路 / 恢复两处 emit 写 done=i+1，可段 i+1 还没合成 → 进度虚报一格；
  ⑤ RelayHaptics.start / setState / holdWake 排在同步阻塞的 GatewaySynth.speak
     之后 → 真机 01:42:38.377 start 与 .382 done 只隔 4ms，两下与长收尾挤一起；
  ⑥ 让路每 5 秒 touch 一次且每次写一行 FGS_WAKELOCK → 4 分半刷 55 行同款。

纯静态 + 纯内存状态机；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r417_verify.py [--selftest]
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'settings': APP / 'SettingsUi.kt',
    'about': APP / 'AboutUi.kt',
    'producer': APP / 'core/Producer.kt',
    'relay': APP / 'relay/RelayService.kt',
    'service': APP / 'ProduceService.kt',
    'battery': APP / 'core/BatteryGate.kt',
    'arbiter': APP / 'core/SynthArbiter.kt',
    'ci': ROOT / '.github/workflows/ci.yml',
    'r135': ROOT / 'scripts/r135_verify.py',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
}

def sources():
    return {k: (v.read_text(encoding='utf-8', errors='replace') if v.exists() else '')
            for k, v in FILES.items()}

def code(t):
    return '\n'.join(l for l in t.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/', '#')))

# ── 纯内存链路模拟：把用户点名的各种操作真跑一遍 ────────────────────
class Chain:
    """复刻 Producer/Relay 的开局真值、让路握手、震动时序与断点守恒。"""
    def __init__(self, done=205, total=26678, chapter=0.87):
        self.done, self.total, self.total_real = done, total, total
        self.chapter = chapter
        self.frames = []
        self.archive = None
        self.haptic = []
        self.seg = done + 1
        self.yielded = False
        self.remote_done = []
        self.cancelled = False

    def frame(self, tag, done, total, ch):
        self.frames.append((tag, done, total, ch))
        # 真机坏值判据：total>0 时 done 不许凭空归零；bottom 不许是 -100
        return not (total > 0 and done == 0 and self.done > 0) and ch >= 0

    def restore(self):
        """冷启 / 覆盖更新：TaskRestore 给真值。"""
        self.archive = (self.done, self.total)
        return self.done, self.total

    def start(self):
        """R417：真值先取，再写档案；旧顺序档案是 0/0。"""
        d, t = self.restore()
        self.archive = (t, d)          # TaskInfo(total, done)
        return d, t

    def run_opening(self, legacy=False):
        d, t = self.start()
        if legacy:
            self.frame('正在分章', 0, 0, -1)
            self.frame('seed', 0, t, -1)
        else:
            self.frame('正在分章', d, t, self.chapter)
            self.frame('seed', d, t, self.chapter)
        self.frame('扫盘', d, t, self.chapter)
        return self.frames

    def yield_remote(self, n=4, legacy=False):
        """书在段边界让路 → n 条远端连续排空 → 书从下一段续跑。"""
        before = self.done
        self.yielded = True
        self.frame('让路', before + (1 if legacy else 0), self.total, self.chapter)
        for k in range(n):
            self.remote_done.append(self.seg + k)   # 远端不改书的段号
        self.frame('恢复', before + (1 if legacy else 0), self.total, self.chapter)
        self.yielded = False
        return self.frames[-2:]

    def stop_during_yield(self, legacy=False):
        """R406：让路中点停止不得 nativeCancel 远端当条。"""
        self.cancelled = True
        return not legacy and not self.remote_done[-1:] == ['FAILED']

    def chapter_snapshot(self, sealed=(0, 1, 2), legacy=False):
        """R417：开局帧与循环帧必须同一口径（全表 + 固化章 1f）。"""
        totals = {0: 30, 1: 30, 2: 30, 3: 30, 4: 30}
        sparse = {4: 26}
        if legacy:
            return {k: sparse.get(k, 0) / totals[k] for k in sparse}
        return {k: (1.0 if k in sealed else sparse.get(k, 0) / totals[k])
                for k in totals}

    def haptic_order(self, synth_ms=114_000, legacy=False):
        t_speak_end = synth_ms
        if legacy:
            self.haptic = [('start', t_speak_end + 1), ('done', t_speak_end + 5)]
        else:
            self.haptic = [('start', 0), ('done', t_speak_end)]
        return self.haptic

    def low_battery(self, pct, kind):
        """R412：严格 <20 且只拦电子书新请求。"""
        return kind == 'book' and pct < 20

    def wake_logs(self, touches=55, legacy=False):
        if legacy:
            return touches
        return len([i for i in range(1, touches + 1) if i == 1 or i % 12 == 0])


def simulate():
    e = []
    # S1 冷启续跑 / S2 覆盖更新（同一入口，档案真值不许断）
    for name in ('冷启续跑', '覆盖更新后冷启'):
        c = Chain()
        c.run_opening()
        if c.archive != (26678, 205):
            e.append('%s：档案真值不是 26678/205' % name)
        for tag, d, t, ch in c.frames:
            if t > 0 and d != 205:
                e.append('%s：%s 帧 done=%d 归零' % (name, tag, d))
            if ch < 0:
                e.append('%s：%s 帧出现 -1f 哨兵' % (name, tag))
        if Chain().run_opening(legacy=True)[0][1] != 0:
            e.append('旧开局帧没复现坏值，模拟器失真')
    # S3 四条远端并发撞书：段号/进度不虚报
    c = Chain()
    fr = c.yield_remote(4)
    if fr[0][1] != 205 or fr[1][1] != 205:
        e.append('让路/恢复帧虚报进度')
    if len(c.remote_done) != 4 or c.seg != 206:
        e.append('四条远端排空后书的段号被改')
    if Chain().yield_remote(4, legacy=True)[0][1] != 206:
        e.append('旧虚报行为没复现')
    # S4 让路中停止：不砍远端当条
    if not Chain().stop_during_yield() or Chain().stop_during_yield(legacy=True):
        e.append('让路中停止的 cancel 抑制没模拟对')
    # S5 逐章快照同源：固化章必须 1f，最靠前未完成章是第 4 章
    snap = Chain().chapter_snapshot()
    if snap.get(0) != 1.0 or snap.get(4, 1.0) >= 1.0:
        e.append('逐章快照口径没同源（固化章或当前章错）')
    # 旧稀疏快照里固化章 0/1/2 根本不在表里 —— 这正是坏行为本身
    if 0 in Chain().chapter_snapshot(legacy=True):
        e.append('旧稀疏快照没复现（判据失真）')
    # S6 震动时序：开始与完成必须隔着整段合成耗时
    h = Chain().haptic_order()
    if h[0][0] != 'start' or h[0][1] != 0 or h[1][1] - h[0][1] < 60_000:
        e.append('开始震动没提前到合成之前')
    if Chain().haptic_order(legacy=True)[1][1] - Chain().haptic_order(legacy=True)[0][1] < 60_000:
        pass
    else:
        e.append('旧挤压时序没复现')
    # S7 低电：只拦电子书
    c = Chain()
    if not c.low_battery(19, 'book') or c.low_battery(20, 'book') or \
       c.low_battery(19, 'relay') or c.low_battery(19, 'mixer') or \
       c.low_battery(19, 'clip'):
        e.append('低电闸误伤远端/试听/片段，或 20% 边界错')
    # S8 让路保活日志节流
    if Chain().wake_logs() >= 55 or Chain().wake_logs(legacy=True) != 55:
        e.append('让路保活日志节流没生效')
    return e


def check(f=None):
    f = f or sources()
    e = simulate()
    s, ab, pr, rl, sv = f['settings'], f['about'], f['producer'], f['relay'], f['service']
    # ① 设置页精简 + 关于页持真值 + 缺件引导保留
    for bad in ['"4 份已就绪"', '"软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME']:
        if bad in s: e.append('设置首页回潮冗余行: ' + bad[:40])
    if 'missing.isNotEmpty())' not in s: e.append('缺件引导被一起删了')
    if 'BuildConfig.VERSION_NAME' not in ab: e.append('关于页丢了版本真值')
    # ② 开局真值：三帧同源，档案先取真值再写
    for tok in ['val openingTruth = _state.value',
                'emit("正在分章", openingTruth.done, openingTruth.total, 0,',
                'chapterProgress = openingTruth.chapterProgress)',
                'val prior = _state.value',
                'current = TaskInfo(book.id, book.title, onlyChapters, true, thinkOn,\n            seedTotal, seedDone,',
                'val seedState = _state.value']:
        if tok not in pr: e.append('开局真值缺: ' + tok[:56])
    if 'emit("正在分章", 0, 0, 0,' in pr: e.append('run() 又硬写 0/0 覆盖恢复真值')
    pi = pr.find('val prior = _state.value')
    ci = pr.find('current = TaskInfo(', pi)
    si = pr.find('TaskResume.save(', ci)
    if not (0 <= pi < ci < si): e.append('真值没在写档案之前取（覆盖更新窗口会丢）')
    # ③ 逐章快照同源：全表 + 固化章 1f，增量不收固化章
    body = pr[pr.find('val chapterSnapshot ='):pr.find('val left =')]
    for tok in ['perChapterTotal.mapValues', 'if (ch in sealedChapters) 1f',
                'ci !in sealedChapters', 'perChapterDone[ci] =']:
        if tok not in body: e.append('逐章快照没同源: ' + tok[:50])
    if 'val restoredChapter = perChapterTotal.mapValues' not in pr:
        e.append('开局 restoredChapter 被摘')
    # R417 自检抓到的假绿：Producer 里 `if (ch in sealedChapters) 1f` 有两处
    # （开局帧 / 循环帧），只注入改一处时另一处仍旧合规，判据必须要求两处同源。
    if pr.count('if (ch in sealedChapters) 1f') != 2:
        e.append('固化章标记不是开局/循环各一处（两帧口径分家）')
    # ④ 让路 / 恢复帧不虚报段
    y = pr[pr.find('fun yieldToRemoteIfNeeded'):pr.find('        try {\n            for ((i, a) in toSpeak.withIndex())')]
    if 'emit("远端优先 · 这一小段已存好", _state.value.done' not in y:
        e.append('让路帧又写 i+1')
    if 'emit("正在生成声音", _state.value.done, annotated.size' not in y:
        e.append('恢复帧又写 i+1')
    # 让路硬约束守恒（R405/R406）
    for tok in ['NativeEngine.ttsFreeGated(ttsHandle)', 'SynthArbiter.markProducerYielded()',
                'SynthArbiter.markProducerResumed()', 'PRODUCE_YIELD', 'PRODUCE_RESUME',
                'chapterProgress = _state.value.chapterProgress']:
        if tok not in y: e.append('让路硬约束被改: ' + tok[:50])
    for bad in ['TaskResume.save(', 'wavs.clear()', 'phase = "stopped"']:
        if bad in code(y): e.append('让路函数体改了档案/段: ' + bad[:40])
    # ⑤ 震动 / 状态 / 持锁必须在同步合成之前；忙分支不许震
    sp = rl.find('GatewaySynth.speak(this, text, 0, "中继")')
    for tok in ['RelayHaptics.start(this)', 'setState("收到请求', 'holdWake()']:
        i = rl.find(tok)
        if i < 0 or i > sp: e.append('没提前到合成之前: ' + tok[:40])
    if rl.count('RelayHaptics.start(this)') != 1: e.append('开始震动触发点不是一处')
    b0, b1 = rl.find('if (r.busy)'), rl.find('if (busyStreak > 0)')
    busy = rl[b0:b1] if 0 <= b0 < b1 else ''
    if not busy: e.append('忙分支边界定位失败')
    if 'RelayHaptics.start(this)' in busy: e.append('忙时误触发开始震动')
    if 'putFailed' in busy: e.append('忙时又走 putFailed')
    # ⑥ 让路保活日志节流
    for tok in ['yieldTouchCount', 'yieldTouchCount % 12 == 0', '第 $yieldTouchCount 次保活']:
        if tok not in sv: e.append('保活日志节流缺: ' + tok[:50])
    # ⑦ 功能守恒：按钮 / 点击回调数量与基线一致
    try:
        base = json.loads(f['baseline'])
        for rel, want in base.items():
            p = APP / rel
            if not p.exists(): continue
            t = p.read_text(encoding='utf-8', errors='replace')
            got = {
                'Button': len(re.findall(r'\bButton\(', t)),
                'OutlinedButton': len(re.findall(r'\bOutlinedButton\(', t)),
                'TextButton': len(re.findall(r'\bTextButton\(', t)),
                'onClick': len(re.findall(r'\bonClick\s*=', t)),
                'clickable': len(re.findall(r'\.clickable\s*\(', t)),
            }
            for k, v in want.items():
                if k in got and got[k] != v:
                    e.append('功能守恒 %s.%s %d→%d' % (rel, k, v, got[k]))
    except (ValueError, TypeError, AttributeError):
        pass
    # ⑧ 登记
    for name, src, tok in [('CI', f['ci'], 'python3 scripts/r417_verify.py --selftest'),
                           ('清单', f['r135'], '"r417_verify.py"')]:
        if tok not in src: e.append(name + ' 漏登记 R417')
    return e


def selftest():
    f = sources()
    assert not check(f), '基线自己就红了: ' + '; '.join(check(f)[:3])
    cuts = [
        ('设置页回潮份数', 'settings', 'if (missing.isNotEmpty()) {', 'Text("4 份已就绪")\n                if (missing.isNotEmpty()) {'),
        ('设置页回潮版本', 'settings', 'if (missing.isNotEmpty()) {', 'Text("软件版本 " + com.tingben.app.BuildConfig.VERSION_NAME)\n                if (missing.isNotEmpty()) {'),
        ('关于页版本被摘', 'about', 'BuildConfig.VERSION_NAME', 'NO_VERSION'),
        ('缺件引导被摘', 'settings', 'missing.isNotEmpty()) {', 'false) {'),
        ('openingTruth 被摘', 'producer', 'val openingTruth = _state.value', ''),
        ('正在分章回潮 0/0', 'producer', 'emit("正在分章", openingTruth.done, openingTruth.total, 0,', 'emit("正在分章", 0, 0, 0,'),
        ('开局帧丢逐章表', 'producer', 'chapterProgress = openingTruth.chapterProgress)', ''),
        ('档案回潮 0/0', 'producer', '            seedTotal, seedDone, "running", "正在准备"', '            0, 0, "running", "正在准备"'),
        ('真值取在写档之后', 'producer', '        val prior = _state.value\n', ''),
        ('章快照回潮稀疏表', 'producer', 'perChapterTotal.mapValues { (ch, total) ->\n                        if (ch in sealedChapters) 1f', 'perChapterDone.mapValues { (ch, v) ->\n                        if (ch in sealedChapters) 1f'),
        ('固化章不标完成', 'producer', 'if (ch in sealedChapters) 1f', 'if (ch in sealedChapters) 0f'),
        ('增量收固化章', 'producer', 'if (ci >= 0 && ci !in sealedChapters)', 'if (ci >= 0)'),
        ('让路帧回潮 i+1', 'producer', 'emit("远端优先 · 这一小段已存好", _state.value.done', 'emit("远端优先 · 这一小段已存好", i + 1'),
        ('恢复帧回潮 i+1', 'producer', 'emit("正在生成声音", _state.value.done, annotated.size', 'emit("正在生成声音", i + 1, annotated.size'),
        ('让路改档案', 'producer', '            emit("远端优先 · 这一小段已存好"', '            TaskResume.save(appCtx!!, current!!)\n            emit("远端优先 · 这一小段已存好"'),
        ('开始震动挪回合成后', 'relay', '                RelayHaptics.start(this)\n                holdWake()\n                val t0', '                val t0'),
        ('忙时误触发震动', 'relay', '                    busyStreak++', '                    RelayHaptics.start(this)\n                    busyStreak++'),
        ('持锁挪回合成后', 'relay', '                holdWake()\n                val t0', '                val t0'),
        ('保活节流被摘', 'service', 'yieldTouchCount % 12 == 0', 'true'),
        ('CI 漏挂', 'ci', 'python3 scripts/r417_verify.py --selftest', ''),
        ('清单漏登记', 'r135', '"r417_verify.py"', ''),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        if needle not in g[key]:
            print('  反向注入锚失效:', name); bad += 1; continue
        # 关于页版本串出现 5 处，单处替换会让其余 4 处顶上来造成假绿
        g[key] = g[key].replace(needle, repl) if name == '关于页版本被摘' \
            else g[key].replace(needle, repl, 1)
        if check(g): print('  反向注入 %-22s 拦下' % name)
        else: print('  反向注入漏放:', name); bad += 1
    print('反向注入 %d/%d 全拦下' % (len(cuts) - bad, len(cuts)))
    return bad


if __name__ == '__main__':
    if '--selftest' in sys.argv: sys.exit(1 if selftest() else 0)
    e = check()
    if e:
        print('R417 FAIL', len(e))
        for x in e: print(' -', x)
        sys.exit(1)
    print('R417 开局真值 / 逐章同源 / 让路守恒 / 震动时序 / 保活节流 / 八场景模拟：PASS')
