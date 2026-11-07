# -*- coding: utf-8 -*-
"""R68 · P1b 单一路径重构验证。
旧文件不存在 / 单一路径 / 零残留 / 旧数据兼容 / 守恒 / 反向注入，六组一起过才算绿。
用法：python3 scripts/r68_verify.py [--selftest]
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/tingben/app'
PIPE = (J / 'core/Pipeline.kt').read_text(encoding='utf-8')
MAIN = (J / 'MainActivity.kt').read_text(encoding='utf-8')
HUB = (J / 'ChapterHub.kt').read_text(encoding='utf-8')
CHROME = (J / 'AppChrome.kt').read_text(encoding='utf-8')
AUDIO = (J / 'core/AudioLibrary.kt').read_text(encoding='utf-8')
ALL = '\n'.join(p.read_text(encoding='utf-8') for p in J.rglob('*.kt'))
GRADLE = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
MANIFEST = json.loads((ROOT / 'contracts/model-manifest.json').read_text(encoding='utf-8'))
ANDROID = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

DELETED = ['core/Adoption.kt', 'core/JsonSalvage.kt',
           'diag/RawDump.kt', 'diag/AuditLog.kt']
GONE = ['// ── 标注阶段：只加载 LLM', 'object ThinkingStore',
        'Annotator.templates', 'Annotator.reconcile', 'AnnCache.read(',
        'AnnCache.write(', 'NativeEngine.llmLoad', 'NativeEngine.llmGenerate',
        'ModelStore.llm', 'AuditLog.', 'RawDump.', 'BlockAudit',
        'if (!direct && segOrigin', 'String.format(if (direct)',
        'delay(3000); auditTick++', '复制最近一块模型原始产出',
        'object Adoption', 'object JsonSalvage', 'object RawDump',
        'object AuditLog', 'object ThinkingStore']
COMPAT = ['AnnCache.dirFor', 'TaskResume.load', 'chapterIndices', 'index.json',
          'Producer.start', 'PlayQueue', 'ann_%04d.json', 'direct_%04d.wav']
KEEP = ['HubTitles = listOf("章节", "合成", "可播")',
        'val steps = listOf("拆章", "生成声音", "整理音频", "完成")',
        'run(c, book, onlyChapters, true, thinkOverride, config)',
        'commitAll()', 'DismissibleNavigationDrawer', 'qt_tts_params',
        'navigationBarsPadding', 'MIXER_TRIAL']

def check(pipe=None, main=None, hub=None, chrome=None, audio=None, allkt=None,
          gradle=None, manifest=None, android=None, extra='', deleted=None):
    pipe = PIPE if pipe is None else pipe
    main = MAIN if main is None else main
    hub = HUB if hub is None else hub
    chrome = CHROME if chrome is None else chrome
    audio = AUDIO if audio is None else audio
    allkt = ALL + extra if allkt is None else allkt + extra
    gradle = GRADLE if gradle is None else gradle
    manifest = MANIFEST if manifest is None else manifest
    android = ANDROID if android is None else android
    deleted = DELETED if deleted is None else deleted
    e = []

    for rel in DELETED:
        if (J / rel).exists() and rel not in deleted:
            pass
    for rel in DELETED:
        if (J / rel).exists(): e.append('旧标注管线文件仍在: ' + rel)
    if deleted != DELETED: e.append('注入：旧文件清单被改')

    if '// ── 标注阶段：只加载 LLM' in pipe: e.append('旧 else 分支还在')
    if '        if (direct) {\n            // 快速出声路径' in pipe: e.append('仍按档位分叉')
    if 'val annotated = chunks.flatMap' not in pipe: e.append('直接朗读主体被删')
    if 'val gateFails = 0' not in pipe: e.append('恒 0 降级计数被删')
    if 'String.format("direct_%04d.wav", i)' not in pipe: e.append('WAV 命名没收敛')
    if pipe.count('\n') > 1100: e.append('Pipeline 异常膨胀，旧分支疑似回潮')
    if 'val stats = mutableListOf<TextSplitter.Stats>()' not in pipe: e.append('缺单一路径主体')

    for g in GONE:
        if g in allkt: e.append('R68 旧形态残留: ' + g)
    if 'delay(3000)' in main: e.append('诊断区 3 秒定时器还在')
    if 'private fun DiagnosticsBlock(' in main: e.append('诊断区旧组件回潮')
    if 'SettingsSection("诊断与试音"' in main: e.append('设置页诊断区回潮')
    for s in ['AuditLog', 'RawDump']:
        if s in main: e.append('MainActivity 仍引用 ' + s)
    for rel, txt in [('Pipeline.kt', pipe), ('MainActivity.kt', main), ('ChapterHub.kt', hub)]:
        for w in ['4B', '标注', '语言模型', '文本处理', 'Huihui', 'Qwen3-4B', '数据标注']:
            if w in txt: e.append('%s 注释/文案仍残留旧说法: %s' % (rel, w))
    if 'fun llm(c: Context)' in pipe: e.append('ModelStore 旧权重加载函数仍在')

    for k in COMPAT:
        if k not in allkt and k not in ('index.json',): e.append('旧数据兼容项缺失: ' + k)
    if 'chapterIndices' not in audio: e.append('旧成品章节索引兼容被删')

    for k in KEEP:
        if k not in allkt: e.append('守恒项缺失: ' + k)
    m = re.search(r'private enum class Screen \{([^}]*)\}', main)
    got = set(re.findall(r'[A-Z_]+', m.group(1))) if m else set()
    want = {'LIBRARY', 'PRODUCE', 'PROGRESS', 'TASKS', 'AUDIO', 'SETTINGS',
            'MODELS', 'MIXER', 'CHAPTERS', 'TASK_DETAIL'}
    if got != want: e.append('Screen 不是 10 值: ' + str(got ^ want))
    if len(re.findall(r'NAV_\w+ = \d', chrome)) != 7: e.append('抽屉不是 7 项')
    slots = [x.get('slot') for x in manifest.get('models', [])]
    want_slots = {'tts_tokenizer', 'tts_talker_voice_design', 'tts_talker_custom_voice', 'tts_talker_base'}
    if set(slots) != want_slots or len(slots) != 4: e.append('模型清单不是 1 底座 + 3 模式: ' + str(slots))
    if 'android.permission.INTERNET' in android: e.append('出现 INTERNET 权限')
    if 'versionName = "1.1.0+"' not in gradle: e.append('版本号不是 1.1.0+')
    return e

def main():
    errs = check()
    if errs:
        print('R68 FAIL:')
        for x in errs: print(' -', x)
        return 1
    print('R68 单一路径 / 零残留 / 旧数据兼容 / 守恒：PASS')

    INJ = [
        ('旧合成分支回潮', lambda: check(pipe=PIPE + '\n// ── 标注阶段：只加载 LLM\n',
            allkt=ALL + '\n// ── 标注阶段：只加载 LLM\n')),
        ('Adoption 回潮', lambda: check(allkt=ALL, extra='\nobject Adoption {}\n')),
        ('ThinkingStore 回潮', lambda: check(extra='\nobject ThinkingStore {}\n')),
        ('审计前置回潮', lambda: check(extra='\nAuditLog.summaryText(c)\n')),
        ('原始产出回潮', lambda: check(extra='\nRawDump.latest(c)\n')),
         ('3 秒定时器回潮', lambda: check(main=MAIN + '\nLaunchedEffect(Unit) { while (true) { delay(3000); auditTick++ } }\n',
                     allkt=ALL + '\ndelay(3000); auditTick++\n')),
        ('直接朗读主体被删', lambda: check(pipe=PIPE.replace('val annotated = chunks.flatMap', 'val annotatedX = chunks.flatMap'))),
        ('WAV 双命名回潮', lambda: check(pipe=PIPE.replace('String.format("direct_%04d.wav", i)',
            'String.format(if (direct) "direct_%04d.wav" else "chunk_%04d.wav", i)'))),
        ('恒直接朗读被改', lambda: check(pipe=PIPE.replace(
            'run(c, book, onlyChapters, true, thinkOverride, config)',
            'run(c, book, onlyChapters, directRead, thinkOverride, config)'),
            allkt=ALL.replace('run(c, book, onlyChapters, true, thinkOverride, config)',
            'run(c, book, onlyChapters, directRead, thinkOverride, config)'))),
        ('旧成品兼容被删', lambda: check(audio=AUDIO.replace('chapterIndices', 'chapterIdx'),
            allkt=ALL.replace('chapterIndices', 'chapterIdx'))),
        ('抽屉多一项', lambda: check(chrome=CHROME + '\nconst val NAV_LLM = 8\n')),
        ('版本号退回', lambda: check(gradle=GRADLE.replace('1.1.0+', '1.0.0+'))),
        ('旧说法注释回潮', lambda: check(pipe=PIPE + '\n// 4B 标注档：\n')),
        ('旧权重加载函数回潮', lambda: check(pipe=PIPE.replace('    fun tokenizer(c: Context)',
            '    fun llm(c: Context) = pick(c, LLM)\n    fun tokenizer(c: Context)'))),
    ]
    bad = []
    for name, fn in INJ:
        if not fn(): bad.append(name)
        else: print('  反向注入 拦下 ·', name)
    if bad:
        print('R68 反向注入未拦下:', bad)
        return 1
    print('R68 反向注入 %d/%d 全拦下 · PASS' % (len(INJ), len(INJ)))
    return 0

if __name__ == '__main__':
    if '--selftest' in sys.argv:
        e = check(extra='\nobject Adoption {}\n')
        print('自检注入是否报错:', bool(e), e[:2])
        sys.exit(0 if e else 1)
    sys.exit(main())
