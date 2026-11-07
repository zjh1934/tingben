# -*- coding: utf-8 -*-
"""R105 / F-03 验证：试听取消链路 + 全局单 loader。

用法：
  python3 scripts/r105_verify.py            # 正向 / 零残留 / 守恒
  python3 scripts/r105_verify.py --selftest # 再加反向注入（CI 用这个）
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
M = ROOT / 'app/src/main/java/com/tingben/app/MainActivity.kt'
N = ROOT / 'app/src/main/java/com/tingben/app/engine/NativeEngine.kt'
PL = ROOT / 'app/src/main/java/com/tingben/app/core/Pipeline.kt'
CI = ROOT / '.github/workflows/ci.yml'

main = M.read_text(encoding='utf-8')
native = N.read_text(encoding='utf-8')
pipe = PL.read_text(encoding='utf-8')
ci = CI.read_text(encoding='utf-8')

def check(src, label, needles, absent=()):
    bad = ['缺 ' + s for s in needles if s not in src]
    bad += ['不该出现 ' + s for s in absent if s in src]
    return ['%s %s' % (label, b) for b in bad]

errs = []

errs += check(native, 'NativeEngine', [
    'private val loadGate = Any()',
    '@Volatile private var loadBusy = false',
    'fun tryBeginTtsLoad(): Boolean',
    'fun endTtsLoad()',
    'fun ttsLoadGated(',
    'fun ttsFreeGated(',
    'if (!tryBeginTtsLoad()) return 0L',
    'finally { endTtsLoad() }',
])

for name, src in (('MainActivity', main), ('Pipeline', pipe)):
    errs += check(src, name + '/gated', [
        'NativeEngine.ttsLoadGated(',
        'NativeEngine.ttsFreeGated(',
    ], absent=['NativeEngine.ttsLoad(', 'NativeEngine.ttsFree('])

n_bare = len(re.findall(r'(?<!Gated)\bttsLoad\(', native))
if n_bare != 2:
    errs.append('NativeEngine 裸 ttsLoad 数不对：%d' % n_bare)

trial = main[main.index('private fun MixerScreen('):]
errs += check(trial, 'trial', [
    'var trialJob by remember { mutableStateOf<Job?>(null) }',
    'var trialToken by remember { mutableStateOf(0L) }',
    'DisposableEffect(Unit)', 'onDispose {', 'trialJob?.cancel()',
    'fun stopTrial()',
    'trialToken++',
    'NativeEngine.nativeCancel()',
    'val myToken = trialToken + 1',
    'trialToken = myToken',
    'trialJob?.cancel()',
    'ensureActive()',
    'if (myToken != trialToken || !isActive) return@withContext',
    'trialJob = job',
    'Text("停止", fontSize = 12.sp)',
])
if trial.count('ensureActive()') < 4:
    errs.append('ensureActive 检查点不足 4 处')

errs += check(trial, '旧形态', [], absent=[
    'val h = NativeEngine.ttsLoad(tk.absolutePath',
    'finally { NativeEngine.ttsFree(h) }',
])

errs += check(main, '守恒/界面', [
    '试听 · 当前', '试听 · 已保存',
    'commitAll(',
    'PlayQueue.setTracks(',
    'Screen.MIXER -> MixerScreen',
])
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import nav_semantics as _nav
if _nav.global_back(main) is None:
    errs.append('全局回退处理器丢失')
errs += check(trial, '守恒/三模式', [
    'ModelStore.Mode.BASE ->',
    'ModelStore.Mode.CUSTOM_VOICE ->',
    'ModelStore.Mode.VOICE_DESIGN ->',
    'src.knobs',
])
errs += check(ci, 'CI', ['python3 scripts/r105_verify.py --selftest'])

m = re.search(r'private enum class Screen \{([^}]*)\}', main)
if not m or len([x for x in m.group(1).split(',') if x.strip()]) != 10:
    errs.append('Screen 枚举不是 10 值')

def violates(a, b, c):
    bad = []
    t2 = a[a.index('private fun MixerScreen('):]
    for s in ('val myToken = trialToken + 1',
              'if (myToken != trialToken || !isActive) return@withContext',
              'DisposableEffect(Unit)', 'onDispose {', 'trialJob?.cancel()',
              'if (busyTrial) stopTrial()',
              'fun stopTrial()'):
        if s not in t2:
            bad.append('trial 缺 ' + s[:36])
    if 'fun ttsLoadGated(' not in b or 'tryBeginTtsLoad' not in b:
        bad.append('单 loader 丢失')
    for name, src in (('main', a), ('pipeline', c)):
        if 'NativeEngine.ttsLoad(' in src or 'NativeEngine.ttsFree(' in src:
            bad.append(name + ' 出现裸装载/释放')
    return bad

if '--selftest' in sys.argv:
    cases = [
        ('撤掉 token 校验', lambda a, b, c: (
            a.replace('if (myToken != trialToken || !isActive) return@withContext',
                      'if (false) return@withContext', 1), b, c)),
        ('撤掉单 loader', lambda a, b, c: (
            a, b.replace('fun ttsLoadGated(', 'fun ttsLoadGated_DISABLED(', 1), c)),
        ('装载退回裸调用', lambda a, b, c: (
            a.replace('NativeEngine.ttsLoadGated(', 'NativeEngine.ttsLoad('), b, c)),
        ('释放退回裸调用', lambda a, b, c: (
            a.replace('NativeEngine.ttsFreeGated(', 'NativeEngine.ttsFree('), b, c)),
        ('撤掉离页取消', lambda a, b, c: (
            a.replace('trialJob?.cancel()', 'Unit'), b, c)),
        ('撤掉停止入口', lambda a, b, c: (
            a.replace('if (busyTrial) stopTrial()', 'if (false) Unit', 1), b, c)),
    ]
    ok = 0
    for label, fn in cases:
        if violates(*fn(main, native, pipe)):
            ok += 1
        else:
            errs.append('反向注入没拦下：' + label)
    print('反向注入 %d/%d 拦下' % (ok, len(cases)))

if errs:
    for e in errs:
        print('  x', e)
    print('R105 F-03 验证失败：%d 条' % len(errs))
    sys.exit(1)
print('R105 F-03 验证通过：正向 / 零残留 / 守恒全绿')
