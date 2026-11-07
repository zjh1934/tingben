# -*- coding: utf-8 -*-
"""R67 · P1a 死代码物理清理验证。
零残留 / 旧数据兼容 / 守恒 / 反向注入，四组一起过才算绿。
用法：python3 scripts/r67_verify.py [--selftest]
"""
import json, re, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/tingben/app'
PIPE = (J / 'core/Pipeline.kt').read_text(encoding='utf-8')
PREFS = (J / 'core/Prefs.kt').read_text(encoding='utf-8')
MAIN = (J / 'MainActivity.kt').read_text(encoding='utf-8')
HUB = (J / 'ChapterHub.kt').read_text(encoding='utf-8')
EVENT = (J / 'diag/EventLog.kt').read_text(encoding='utf-8')
CHROME = (J / 'AppChrome.kt').read_text(encoding='utf-8')
AUDIO = (J / 'core/AudioLibrary.kt').read_text(encoding='utf-8')
ALL = '\n'.join(p.read_text(encoding='utf-8') for p in J.rglob('*.kt'))
GRADLE = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
MANIFEST = json.loads((ROOT / 'contracts/model-manifest.json').read_text(encoding='utf-8'))
ANDROID = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

GONE = ['AuditLog.beginRun', 'RawDump.begin', 'AUDIT_RUN_BEGIN', 'AbGate',
        'PROMPT_VERSION', 'ENGINE_INFO', 'writeAuditSummary', 'KEY_THINKING',
        'setThinking', 'Prefs.thinking', 'AnnotatedPane', 'AuditLog.crashTail',
        'AUDIT_SUMMARY']
COMPAT = ['AnnCache.dirFor', 'TaskResume.load', 'chapterIndices',
          'Producer.start', 'index.json', 'PlayQueue']
KEEP = ['HubTitles = listOf("章节", "合成", "可播")',
        'val steps = listOf("拆章", "生成声音", "整理音频", "完成")',
        'run(c, book, onlyChapters, true, thinkOverride, config)',
        'commitAll()', 'DismissibleNavigationDrawer', 'qt_tts_params',
        'navigationBarsPadding']

def check(pipe=None, prefs=None, main=None, hub=None, event=None, chrome=None,
          audio=None, allkt=None, gradle=None, manifest=None, android=None):
    pipe = PIPE if pipe is None else pipe
    prefs = PREFS if prefs is None else prefs
    main = MAIN if main is None else main
    hub = HUB if hub is None else hub
    event = EVENT if event is None else event
    chrome = CHROME if chrome is None else chrome
    audio = AUDIO if audio is None else audio
    allkt = ALL if allkt is None else allkt
    gradle = GRADLE if gradle is None else gradle
    manifest = MANIFEST if manifest is None else manifest
    android = ANDROID if android is None else android
    e = []
    for g in GONE:
        if g in allkt: e.append('P1a 旧形态残留: ' + g)
    if 'val gateFails = 0' not in pipe: e.append('Pipeline 缺单一路径常量')
    for k in COMPAT:
        if k not in allkt and k != 'qt_tts_params': e.append('旧数据兼容项缺失: ' + k)
    if 'chapterIndices' not in audio: e.append('AudioLibrary 缺旧成品索引兼容')
    for k in KEEP:
        if k not in allkt: e.append('守恒项缺失: ' + k)
    if len(re.findall(r'NAV_\w+ = \d', chrome)) != 6: e.append('抽屉不是 6 项')
    slots = [x.get('slot') for x in manifest.get('models', [])]
    want_slots = {'tts_tokenizer', 'tts_talker_voice_design', 'tts_talker_custom_voice', 'tts_talker_base'}
    if set(slots) != want_slots or len(slots) != 4: e.append('模型清单不是 1 底座 + 3 模式: ' + str(slots))
    if 'android.permission.INTERNET' in android: e.append('出现 INTERNET 权限')
    if 'versionName = "1.1.0+"' not in gradle: e.append('版本号没停在 1.1.0')
    return e

def main():
    errs = check()
    if errs:
        print('R67 FAIL:'); [print(' -', x) for x in errs]; return 1
    print('R67 零残留 / 旧数据兼容 / 守恒：PASS')
    INJ = [
        ('审计前置回潮', lambda: check(pipe=PIPE.replace('EventLog.resetEngine()',
            'AuditLog.beginRun(c, linkedMapOf())\n        EventLog.resetEngine()'),
            allkt=ALL.replace('EventLog.resetEngine()', 'AuditLog.beginRun(c, linkedMapOf())\n        EventLog.resetEngine()'))),
        ('AbGate 回潮', lambda: check(allkt=ALL + '\nobject AbGate {}\n')),
        ('思考设置键回潮', lambda: check(prefs=PREFS + '\nprivate const val KEY_THINKING = "thinking"\n',
            allkt=ALL + '\nprivate const val KEY_THINKING = "thinking"\n')),
        ('AnnotatedPane 回潮', lambda: check(allkt=ALL + '\nprivate fun AnnotatedPane() {}\n')),
        ('三列退回四列', lambda: check(hub=HUB.replace('listOf("章节", "合成", "可播")',
            'listOf("原始", "标注", "合成", "可播")'),
            allkt=ALL.replace('listOf("章节", "合成", "可播")', 'listOf("原始", "标注", "合成", "可播")'))),
        ('四步退回五步', lambda: check(allkt=ALL.replace(
            'listOf("拆章", "生成声音", "整理音频", "完成")',
            'listOf("拆章", "理解文字", "生成声音", "整理音频", "完成")'))),
        ('恒直接朗读被改', lambda: check(pipe=PIPE.replace(
            'run(c, book, onlyChapters, true, thinkOverride, config)',
            'run(c, book, onlyChapters, directRead, thinkOverride, config)'),
            allkt=ALL.replace('run(c, book, onlyChapters, true, thinkOverride, config)',
            'run(c, book, onlyChapters, directRead, thinkOverride, config)'))),
        ('旧成品兼容被删', lambda: check(audio=AUDIO.replace('chapterIndices', 'chapterIdx'),
            allkt=ALL.replace('chapterIndices', 'chapterIdx'))),
        ('INTERNET 权限回潮', lambda: check(android=ANDROID + '\n<uses-permission android:name="android.permission.INTERNET" />')),
        ('版本号退回', lambda: check(gradle=GRADLE.replace('1.1.0+', '1.0.0+'))),
    ]
    bad = []
    for name, fn in INJ:
        if not fn(): bad.append(name)
        else: print('  反向注入 拦下 ·', name)
    if bad:
        print('R67 反向注入未拦下:', bad); return 1
    print('R67 反向注入 10/10 全拦下 · PASS')
    return 0

if __name__ == '__main__':
    if '--selftest' in sys.argv:
        e = check(allkt=ALL + '\nobject AbGate {}\n')
        print('自检注入是否报错:', bool(e), e[:2]); sys.exit(0 if e else 1)
    sys.exit(main())
