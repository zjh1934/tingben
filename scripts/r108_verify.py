# -*- coding: utf-8 -*-
"""R108 / F-19 验证：native 停止标志不再逐段复位 + 任务级复位闭合 + 缓存解耦。

用法：
  python3 scripts/r108_verify.py            # 正向 / 零残留 / 守恒
  python3 scripts/r108_verify.py --selftest # 再加反向注入（CI 用这个）
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CPP = ROOT / 'app/src/main/cpp/tingben_native.cpp'
M = ROOT / 'app/src/main/java/com/tingben/app/MainActivity.kt'
PL = ROOT / 'app/src/main/java/com/tingben/app/core/Pipeline.kt'
CI = ROOT / '.github/workflows/ci.yml'

cpp = CPP.read_text(encoding='utf-8')
main = M.read_text(encoding='utf-8')
pipe = PL.read_text(encoding='utf-8')
ci = CI.read_text(encoding='utf-8')


def check(src, label, needles, absent=()):
    bad = ['缺 ' + s for s in needles if s not in src]
    bad += ['不该出现 ' + s for s in absent if s in src]
    return ['%s %s' % (label, b) for b in bad]


def reset_lines(s):
    # 只认行首的「代码复位」；中文注释里提到这个写法不算数
    return [i + 1 for i, l in enumerate(s.splitlines())
            if re.match(r'^\s*g_cancel\.store\(false\);', l)]


def code_only(s):
    return '\n'.join(l for l in s.splitlines() if not l.lstrip().startswith('//'))


def tts_body(s):
    a = s.index('Java_com_tingben_app_engine_NativeEngine_ttsSynthesizeRaw')
    b = s.index('Java_com_tingben_app_engine_NativeEngine_ttsSetVoiceRef')
    return s[a:b]


def trial_body(s):
    return s[s.index('private fun MixerScreen('):]


def cache_block(s):
    a = s.index('- name: 原生引擎缓存')
    b = s.index('- name: 检查缓存完整性')
    return s[a:b]


def core_errors(a, b, c, d):
    """四个文件的核心判据；任何一条不满足就返回非空。"""
    errs = []
    # 1) 全文件只允许一处复位，且必须落在 nativeCancelReset 函数体内
    lines = reset_lines(a)
    if len(lines) != 1:
        errs.append('C++ 复位次数应为 1，实为 %d' % len(lines))
    else:
        fn = a[:a.index('Java_com_tingben_app_engine_NativeEngine_nativeCancelReset')].count('\n') + 1
        if not fn <= lines[0] <= fn + 6:
            errs.append('复位不在 nativeCancelReset 函数体内')
    if re.search(r'^\s*g_cancel\.store\(false\);', tts_body(a), re.M):
        errs.append('TTS 路径仍在逐段复位')
    if 'tp.cancel   = ttsCancelCb' not in a:
        errs.append('TTS 取消回调丢失')
    # 2) 任务级复位：正式制作 + 每次试听各一处
    if 'NativeEngine.nativeCancelReset()' not in c:
        errs.append('Pipeline 缺整次制作复位')
    t = trial_body(b)
    th = t[t.index('fun trial(label: String'):t.index('val myToken = trialToken + 1')]
    if 'NativeEngine.nativeCancelReset()' not in th:
        errs.append('试听缺任务级复位')
    if 'if (Producer.isBusy())' not in th:
        errs.append('试听未拦制作忙')
    if 'if (busyTrial) return' not in th:
        errs.append('试听未拦连点')
    if 'DisposableEffect(Unit)' not in t or 'onDispose {' not in t:
        errs.append('离页取消块丢失')
    # 3) 段重试前必须拦停止
    if not re.search(r'for \(attempt in 0\.\.1\) \{\s*// R108[\s\S]{0,240}?if \(cancelRequested\)', c):
        errs.append('段重试前未检查 cancelRequested')
    # 4) CI 缓存键与 JNI 桥解耦，且有回退键
    blk = cache_block(d)
    if 'tingben_native.cpp' in blk or 'CMakeLists.txt' in blk:
        errs.append('缓存键仍绑定 JNI 桥')
    if 'restore-keys:' not in blk or 'tingben-native-v1-' not in blk:
        errs.append('缓存缺回退键')
    return errs


errs = core_errors(cpp, main, pipe, ci)

# ── 零残留 / 守恒 ──────────────────────────────────────────────────────
errs += check(cpp, 'C++/注释', ['R108 · F-19：不在这里复位'])
errs += check(code_only(tts_body(cpp)), 'C++/TTS 段', [], absent=['g_cancel.store(false)'])
errs += check(main, '守恒/界面', [
    '试听草稿', '试听已生效', 'commitAll(', 'Screen.MIXER -> MixerScreen',
    'ModelStore.Mode.BASE ->', 'ModelStore.Mode.CUSTOM_VOICE ->',
    'ModelStore.Mode.VOICE_DESIGN ->',
])
errs += check(pipe, '守恒/制作', [
    'fun stop()', 'NativeEngine.nativeCancel()', 'fun start(',
    'ttsLoadGated(', 'ttsFreeGated(',
])
errs += check(ci, 'CI', ['python3 scripts/r108_verify.py --selftest', '交叉编译两个推理引擎'])

m = re.search(r'private enum class Screen \{([^}]*)\}', main)
if not m or len([x for x in m.group(1).split(',') if x.strip()]) != 9:
    errs.append('Screen 枚举不是 9 值')


# ── 反向注入：把修好的形态改回坏的，核心判据必须报错 ──────────────────
if '--selftest' in sys.argv:
    cases = [
        ('复位加回 TTS', lambda a, b, c, d: (
            a.replace('    // R108 · F-19：不在这里复位', '    g_cancel.store(false);\n    // R108 · F-19：不在这里复位', 1), b, c, d)),
        ('试听撤掉任务级复位', lambda a, b, c, d: (
            a, b.replace('        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }\n        trialJob?.cancel()',
                         '        trialJob?.cancel()', 1), c, d)),
        ('Pipeline 撤掉整次复位', lambda a, b, c, d: (
            a, b, c.replace('        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }', '', 1), d)),
        ('重试前撤掉停止检查', lambda a, b, c, d: (
            a, b, c.replace('                        if (cancelRequested) {\n'
                            '                            _state.value = halted(i, annotated.size, gateFails + synthFails)\n'
                            '                            return\n'
                            '                        }\n', '', 1), d)),
        ('缓存键重新绑 JNI 桥', lambda a, b, c, d: (
            a, b, c, d.replace("hashFiles('scripts/build_native.sh', 'app/src/main/cpp/llama.h', 'app/src/main/cpp/qwen.h')",
                               "hashFiles('scripts/build_native.sh', 'app/src/main/cpp/tingben_native.cpp')", 1))),
        ('撤掉回退键', lambda a, b, c, d: (
            a, b, c, d.replace('          restore-keys: |\n'
                               '            tingben-native-v2-\n'
                               '            tingben-native-v1-\n', '', 1))),
        ('试听撤掉制作忙拦截', lambda a, b, c, d: (
            a, b.replace('if (Producer.isBusy()) {', 'if (false) {'), c, d)),
    ]
    ok = 0
    for label, fn in cases:
        if core_errors(*fn(cpp, main, pipe, ci)):
            ok += 1
        else:
            errs.append('反向注入没拦下：' + label)
    print('反向注入 %d/%d 拦下' % (ok, len(cases)))

if errs:
    for e in errs:
        print('  x', e)
    print('R108 F-19 验证失败：%d 条' % len(errs))
    sys.exit(1)
print('R108 F-19 验证通过：正向 / 零残留 / 守恒全绿')
