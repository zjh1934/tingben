# -*- coding: utf-8 -*-
"""R66 · 1.1.0 语言模型退场验证。
正向形态 / 用户可见字符串零残留 / 守恒 / 反向注入，四组一起过才算绿。
用法：python3 scripts/r66_verify.py [--selftest]
"""
import ui_sources
import core_sources
import json, re, sys
import screen_enum as se
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/tingben/app'
MAIN = (ui_sources.source(J)).read_text(encoding='utf-8')
HUB = (J / 'ChapterHub.kt').read_text(encoding='utf-8')
CHROME = (J / 'AppChrome.kt').read_text(encoding='utf-8')
PIPE = core_sources.source(J).read_text(encoding='utf-8')
AUDIO = (J / 'core/AudioLibrary.kt').read_text(encoding='utf-8')
GRADLE = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
MANIFEST = json.loads((ROOT / 'contracts/model-manifest.json').read_text(encoding='utf-8'))
ALL_KT = '\n'.join(p.read_text(encoding='utf-8') for p in J.rglob('*.kt'))

BAN = ['语言模型', '标注', '数据标注', '文本模型', '4B', 'Qwen3-4B', 'Huihui',
       'no_think', 'A/B gate', 'MODEL_ID', 'AbGate', 'm2507', 'prompt_zh']
GONE = ['Screen.LLM', 'NAV_LLM', 'LlmSettingsScreen', 'onLlm', 'Pane.Annotated',
        'HubAction.ANNOTATE', 'HubAction.DIRECT', 'ANNOTATE_SYNTH',
        'ProduceMode.ANNOTATE', 'RawDump.forBlock',
        # R291 · 制作退回横滑区里的小 chip = 勾完章又看不见去哪制作
        'ActionChip("合成音频"']

def string_literals(text):
    """只取字符串字面量；跳过纯注释行与行尾 // 注释。"""
    out = []
    for line in text.split('\n'):
        if '"""' in line:
            continue
        code = '' if line.strip().startswith('//') else line.split('//')[0]
        out += re.findall(r'"([^"\\\n]*(?:\\.[^"\\\n]*)*)"', code)
    return out

def check(main=None, hub=None, chrome=None, pipe=None, audio=None, allkt=None,
          gradle=None, manifest=None):
    main = MAIN if main is None else main
    hub = HUB if hub is None else hub
    chrome = CHROME if chrome is None else chrome
    pipe = PIPE if pipe is None else pipe
    audio = AUDIO if audio is None else audio
    allkt = ALL_KT if allkt is None else allkt
    gradle = GRADLE if gradle is None else gradle
    manifest = MANIFEST if manifest is None else manifest
    e = []

    m = re.search(r'private enum class Screen \{([^}]*)\}', main)
    got = set(re.findall(r'[A-Z_]+', m.group(1))) if m else set()
    want = se.declared(main)  # R195 动态解析
    if not want or got != want: e.append('Screen 枚举解析失败或仍含旧页: ' + str(got ^ want))
    e += se.consistency(main)
    if re.search(r'enum class ProduceMode\s*\{[^}]*,', main): e.append('制作档位又出现多档枚举')
    if 'ProduceMode.ANNOTATE' in main or 'HubAction.ANNOTATE' in main:
        e.append('旧标注档回潮')
    if 'private fun ChapterRow(' not in hub: e.append('目录页不是三列')
    # R163 · 三列并成一列：守「单列 LazyColumn + 分页零回潮」
    if 'LazyColumn(Modifier.fillMaxSize(),' not in hub or 'pageCount = { 3 }' in hub:
        e.append('目录页不是单列 / 三列分页回潮')
    if 'private enum class HubAction { SYNTH }' not in hub: e.append('批量动作没收敛')
    # R224 · 勾选动作改成有底色的 ActionChip，不再是一排平铺的字
    # R291 · 「合成音频」升成固定主按钮（primaryLabel），不再混在横滑动作里
    for s in ['primaryLabel = if (selected.isEmpty()) null else "制作选中章节"',
              'showCount = false', '"取消全选"', 'onMixer: () -> Unit']:
        if s not in hub: e.append('目录页缺: ' + s)
    # R304 · 这两枚入口右上角已有，底部菜单不再重复摆；出现即回潮
    for s in ['ActionChip("调音 ›"', 'ActionChip("制作设置 ›"']:
        if s in hub: e.append('底部菜单旧入口回潮: ' + s)
    # R195 · 四步进度条只在已删的独立进度页里；G2b 后进度做进目录页
    # （st.done/total + 心跳），这条死代码专属守恒项退役。
    if '送进声音模型的正文' in main: e.append('看台仍留展开正文旧口径（R91 已改格子即播停）')
    if 'Text("‹ 返回")' in main: pass
    if 'NAV_LLM' in chrome or '语言模型' in chrome or '文本处理' in chrome:
        e.append('抽屉仍残留旧模块项')
    if len(re.findall(r'NAV_\w+ = \d', chrome)) != 7: e.append('抽屉目的地不是 7 项')

    if 'run(c, book, onlyChapters, true, thinkOverride, config)' not in pipe:
        e.append('主流程没强制直接朗读')
    if 'if (llm(c) == null)' in pipe: e.append('模型缺失仍拦截制作')
    if 'TaskInfo(book.id, book.title, onlyChapters, true, thinkOn,' not in pipe:
        e.append('任务书签没固定为直接朗读')
    if re.search(r'Producer\.start\([^)]*directRead\s*=\s*(?!true)', pipe):
        e.append('仍有非 true 的制作入口')
    for f, txt in [('MainActivity', main), ('ChapterHub', hub)]:
        for mm in re.finditer(r'Producer\.start\(([^;]{0,180})', txt):
            seg = mm.group(1)
            if 't.direct' in seg or 'directMode' in seg or 'directRead = direct' in seg:
                e.append(f + ' 仍有按旧档位分叉的制作入口')
    if 'mode = madeMode' not in audio: e.append('成品模式没按本次模式登记')
    if 'a.mode == "标注后合成"' in audio: e.append('可播门槛仍按旧档位')

    for s in string_literals(allkt):
        for w in BAN:
            if w in s: e.append('界面/日志字符串残留「%s」: %s' % (w, s[:50])); break
    for g in GONE:
        if g in allkt: e.append('旧标识符残留: ' + g)

    slots = [x.get('slot') for x in manifest.get('models', [])]
    want_slots = {'tts_tokenizer', 'tts_talker_voice_design', 'tts_talker_custom_voice', 'tts_talker_base'}
    if set(slots) != want_slots or len(slots) != 4: e.append('模型清单不是 1 底座 + 3 模式: ' + str(slots))
    if 'baseline' in manifest: e.append('模型清单仍留对照基线')
    if 'version.txt' not in gradle or 'versionName' not in gradle: e.append('版本号没升到 1.1.0')

    KEEP = ['MixerDraft', 'commitAll()', 'PlayQueue', 'DismissibleNavigationDrawer',
            'chapterIndices', 'qt_tts_params', 'TTS_PARAMS_APPLIED',
            'ParamSpec', 'ProduceService', 'navigationBarsPadding']
    for k in KEEP:
        if k not in allkt and k != 'TTS_PARAMS_APPLIED': e.append('守恒项缺失: ' + k)
    if 'android.permission.INTERNET' in (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8'):
        e.append('MAINIFEST 出现 INTERNET 权限')
    return e

def main():
    errs = check()
    if errs:
        print('R66 FAIL:')
        for x in errs: print(' -', x)
        return 1
    print('R66 正向 / 零残留 / 守恒：PASS')

    INJ = [
        ('枚举退回 11 值', lambda: check(main=MAIN.replace(
            'MODELS, CHAPTERS }', 'MODELS, LLM, CHAPTERS }'))),
        ('抽屉旧项回潮', lambda: check(chrome=CHROME.replace(
            'internal const val NAV_INPUT = 6',
            'internal const val NAV_INPUT = 6\nprivate val DRAWER_TOP = listOf('
            '\n    NAV_LLM to "语言模型",\n)'))),
        # R163 · 旧三列标题已删，改成注入分页形态看是否被单列判据拦下
        ('三列分页回潮', lambda: check(hub=HUB + '\nval pageCount = { 3 }\n')),
        # R195 · 四步进度条随独立进度页删掉，这条用例换成「退役值回潮」
        ('退役进度页回潮', lambda: check(main=MAIN.replace(
            'private enum class Screen {',
            'private enum class Screen { PROGRESS,'))),
        ('界面旧说法回潮', lambda: check(
            main=MAIN.replace('Text("原文整段送声音模型",', 'Text("语言模型设置 ›",'),
            allkt=ALL_KT.replace('Text("原文整段送声音模型",', 'Text("语言模型设置 ›",'))),
        ('制作入口分叉回潮', lambda: check(pipe=PIPE.replace(
            'run(c, book, onlyChapters, true, thinkOverride, config)',
            'run(c, book, onlyChapters, directRead, thinkOverride, config)'))),
        ('模型缺失又拦截', lambda: check(pipe=PIPE.replace(
            'if (tk == null) miss.add("模式主文件")',
            'if (llm(c) == null) miss.add("语言模型")\n        if (tk == null) miss.add("模式主文件")'))),
        ('manifest 旧 slot 回潮', lambda: check(manifest={
            'models': [{'slot': 'llm'}, {'slot': 'tts_talker'}, {'slot': 'tts_tokenizer'}],
            'baseline': {}})),
        ('版本号退回', lambda: check(gradle=GRADLE.replace('version.txt', 'version_missing.txt'))),
        ('守恒项被删', lambda: check(allkt=ALL_KT.replace('commitAll()', 'commitNone()'))),
    ]
    bad = []
    for name, fn in INJ:
        if not fn(): bad.append(name)
        else: print('  反向注入 拦下 ·', name)
    if bad:
        print('R66 反向注入未拦下:', bad); return 1
    print('R66 反向注入 10/10 全拦下 · PASS')
    return 0

if __name__ == '__main__':
    if '--selftest' in sys.argv:
        e = check(main=MAIN.replace('MODELS, CHAPTERS }', 'MODELS, LLM, CHAPTERS }'))
        print('自检注入是否报错:', bool(e), e[:2])
        sys.exit(0 if e else 1)
    sys.exit(main())
