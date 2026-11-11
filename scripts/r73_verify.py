# -*- coding: utf-8 -*-
"""R73 · P2 软件设置验证。
正向符号 / 旧假行零残留 / 守恒 / 反向注入，四组一起过才算绿。
用法：python3 scripts/r73_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
J = ROOT / 'app/src/main/java/com/tingben/app'
CI = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
THEME = (J / 'Theme.kt').read_text(encoding='utf-8')
MAIN = (ui_sources.source(J)).read_text(encoding='utf-8')
HUB = (J / 'ChapterHub.kt').read_text(encoding='utf-8')
OPTS = (J / 'ProduceOptions.kt').read_text(encoding='utf-8')
CHROME = (J / 'AppChrome.kt').read_text(encoding='utf-8')
PREFS = (J / 'core/Prefs.kt').read_text(encoding='utf-8')
NIGHT = (ROOT / 'app/src/main/res/values-night/themes.xml').read_text(encoding='utf-8')
ALLKT = '\n'.join(ui_sources.view_for(p, ROOT) for p in J.rglob('*.kt'))
MANIFEST = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')

NEED = [
    ('两套色板', 'Theme.kt', 'internal val LightPal = Pal('),
    ('暗色板', 'Theme.kt', 'internal val DarkPal = Pal('),
    ('snapshot 状态', 'Theme.kt', 'mutableStateOf(false)'),
    ('palette getter', 'Theme.kt', 'internal val palette: Pal get()'),
    ('跟随系统判定', 'Theme.kt', 'UI_MODE_NIGHT_MASK'),
    ('三档语义', 'Theme.kt', 'internal fun applyAppTheme('),
    ('MaterialTheme 包裹', 'Theme.kt', 'MaterialTheme(colorScheme = scheme'),
    ('Activity 套主题', 'MainActivity.kt', 'TingbenTheme {'),
    ('启动时读主题', 'MainActivity.kt', 'applyAppTheme(this, Prefs.themeMode(this))'),
    ('8 色 getter', 'MainActivity.kt', 'private val Bg: Color get() = palette.bg'),
    ('主题三档按钮', 'MainActivity.kt', 'listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)'),
    ('主题写 Prefs', 'MainActivity.kt', 'Prefs.setThemeMode(c, m)'),
    ('主题即时生效', 'MainActivity.kt', 'applyAppTheme(c, m)'),
    ('设置页不折叠', 'MainActivity.kt', 'collapsible = false'),
    ('输入朗读页', 'MainActivity.kt', 'private fun InputReadScreen('),
    ('试听文本可保存', 'MainActivity.kt', 'Prefs.setTrialText(c, trialText)'),
    # R242 · R238 把「本机数据」+「成品文件」两张同 badge 碎卡合并成一张，
    # 旧标题已删、判据恒红；按现状迁移，仍要求一张「本机」卡常驻，不放宽。
    ('本机数据常驻', 'MainActivity.kt', 'SettingsSection("文件与数据", "本机", collapsible = false)'),
    ('两枚试听按钮', 'MainActivity.kt', 'Text("试听 · 已保存"'),
    ('主题键', 'Prefs.kt', 'KEY_THEME_MODE = "themeMode"'),
    ('字号键', 'Prefs.kt', 'KEY_FONT_SCALE = "readerFontScale"'),
    ('主题读写', 'Prefs.kt', 'fun setThemeMode(c: Context, v: Int)'),
    ('字号读写', 'Prefs.kt', 'fun setReaderFontScale(c: Context, v: Float)'),
    ('诊断快照含主题', 'Prefs.kt', 'append("主题 ")'),
    ('诊断快照含字号', 'Prefs.kt', 'append(" · 正文字号 ")'),
    ('Hub 色 getter', 'ChapterHub.kt', 'private val HubBg: Color get() = palette.bg'),
    ('看书页读字号', 'ChapterHub.kt', 'mutableStateOf(Prefs.readerFontScale(c))'),
    ('A- 写回', 'ChapterHub.kt', 'Prefs.setReaderFontScale(c, size)'),
    ('Opt 色 getter', 'ProduceOptions.kt', 'private val OptCard: Color get() = palette.card'),
    ('右上角齿轮', 'AppChrome.kt', 'private fun GearIcon('),
    ('values-night', 'values-night', 'android:windowLightStatusBar">false'),
    ('CI 挂验证', 'ci.yml', 'python3 scripts/r73_verify.py'),
]

GONE = [
    ('设置页正文字号已移除', 'MainActivity.kt', 'Text("正文字号", fontSize = 13.sp'),
    ('诊断与试音已移除', 'MainActivity.kt', 'SettingsSection("诊断与试音"'),
    ('后台与关于已移除', 'MainActivity.kt', 'SettingsSection("后台与关于"'),
    ('只读信息组已移除', 'MainActivity.kt', '④ 本次制作'),
    ('折叠展开按钮已移除', 'ProduceOptions.kt', '展开 ▼'),
    ('假主题行', 'MainActivity.kt', 'SettingInfoRow("主题", "待新增"'),
    ('假字号行', 'MainActivity.kt', 'SettingInfoRow("字号", "待新增"'),
    ('待新增', 'MainActivity.kt', '"待新增"'),
    ('尚未实现', 'MainActivity.kt', '尚未实现'),
    ('仅非思考诊断', 'Prefs.kt', '仅非思考（权重决定）'),
    ('上下文窗口', 'MainActivity.kt', '上下文窗口" to "${Prefs.nCtx(c)}'),
    ('推理线程', 'MainActivity.kt', '推理线程" to "${Prefs.threads(c)}'),
    ('思考过程残留', 'MainActivity.kt', '不适用 · 新权重仅非思考'),
    ('硬编码正文色', 'MainActivity.kt', 'Color(0xFF20242E)'),
    ('硬编码分割线', 'AppChrome.kt', 'Color(0xFFE7EAF2)'),
    ('旧硬编码 Bg', 'MainActivity.kt', 'private val Bg = Color('),
    ('旧硬编码 HubBg', 'ChapterHub.kt', 'private val HubBg = Color('),
    ('旧硬编码 OptCard', 'ProduceOptions.kt', 'private val OptCard = Color('),
]

KEEP = [
    ('Screen 枚举声明', 'ALLKT', 'private enum class Screen {'),
    ('抽屉推走', 'ALLKT', 'DismissibleNavigationDrawer'),
    ('三列目录', 'ChapterHub.kt', 'private fun ChapterRow('),
    ('唯一提交', 'ALLKT', 'commitAll()'),
    ('恒直接朗读', 'ALLKT', 'Producer.start(c, book, sel, directRead = true'),
    ('悬浮条', 'ALLKT', 'PlayQueue'),
    ('底部安全区', 'ALLKT', 'navigationBarsPadding()'),
    ('采样真到引擎', 'ALLKT', 'qt_tts_params'),
    ('前台服务', 'ALLKT', 'ProduceService'),
    ('无 INTERNET', 'ALLKT', 'android.permission.INTERNET'),
]

def check(theme=None, main=None, hub=None, opts=None, chrome=None, prefs=None,
          night=None, ci=None, allkt=None, manifest=None, extra=''):
    theme = THEME if theme is None else theme
    main = MAIN if main is None else main
    hub = HUB if hub is None else hub
    opts = OPTS if opts is None else opts
    chrome = CHROME if chrome is None else chrome
    prefs = PREFS if prefs is None else prefs
    night = NIGHT if night is None else night
    ci = CI if ci is None else ci
    allkt = ALLKT + extra if allkt is None else allkt + extra
    manifest = MANIFEST if manifest is None else manifest
    pool = {
        'Theme.kt': theme, 'MainActivity.kt': main, 'ChapterHub.kt': hub,
        'ProduceOptions.kt': opts, 'AppChrome.kt': chrome, 'Prefs.kt': prefs,
        'values-night': night, 'ci.yml': ci, 'ALLKT': allkt,
    }
    e = []
    for label, src, needle in NEED:
        which = pool.get(src) if isinstance(src, str) else src
        if needle not in which:
            e.append(f'正向缺失[{label}]')
    for label, src, needle in GONE:
        which = pool.get(src) if isinstance(src, str) else src
        if needle in which:
            e.append(f'旧形态残留[{label}]')
    for label, src, needle in KEEP:
        which = pool.get(src) if isinstance(src, str) else src
        if label == '无 INTERNET':
            if needle in manifest: e.append('守恒失败[Manifest 出现 INTERNET]')
        elif needle not in which:
            e.append(f'守恒失败[{label}]')
    n = sum(x.count(': Color get() = palette.') for x in (main, hub, opts))
    if n < 21: e.append(f'颜色 getter 机制被删，实际 {n}')
    if not all(': Color get() = palette.' in x for x in (main, hub, opts)):
        e.append('颜色 getter 未覆盖全部界面层')
    return e

def selftest():
    cases = [
        ('删掉 TingbenTheme', {'main': MAIN.replace('TingbenTheme {', '')}),
        ('删掉主题三档', {'main': MAIN.replace('listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2)', '')}),
        ('删掉输入朗读页', {'main': MAIN.replace('private fun InputReadScreen(', 'private fun InputReadScreenGone(')}),
        ('诊断折叠回潮', {'main': MAIN + '\nSettingsSection("诊断与试音", "按需", diagOpen) {\n}\n'}),
        ('正文字号回潮', {'main': MAIN + '\nText("正文字号", fontSize = 13.sp)\n'}),
        ('后台与关于回潮', {'main': MAIN + '\nSettingsSection("后台与关于", "系统", backOpen) {\n}\n'}),
        ('恢复假主题行', {'main': MAIN + '\nSettingInfoRow("主题", "待新增", "跟随系统", "深色模式尚未实现")'}),
        ('恢复仅非思考', {'prefs': PREFS + '\n// 仅非思考（权重决定）'}),
        ('删掉 Prefs 键', {'prefs': PREFS.replace('KEY_THEME_MODE = "themeMode"', '')}),
        ('删掉 Hub getter', {'hub': HUB.replace('private val HubBg: Color get() = palette.bg', 'private val HubBg = Color(0xFFF4F6FB)')}),
        ('删掉 values-night', {'night': NIGHT.replace('>false<', '>true<')}),
        ('删掉 CI step', {'ci': CI.replace('python3 scripts/r73_verify.py', '')}),
    ]
    bad = []
    for name, kw in cases:
        if not check(**kw): bad.append(name + ' 未被拦下')
    print('R73 反向注入', len(cases) - len(bad), '/', len(cases), '拦下')
    for b in bad: print('  ✗', b)
    return not bad

if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(0 if selftest() else 1)
    errs = check()
    if errs:
        for x in errs: print(' ✗', x)
        print('R73 结果: FAIL')
        sys.exit(1)
    print('R73 结果: PASS · 正向 %d 项 / 零残留 %d 项 / 守恒 %d 项 / 颜色 getter 机制成立' %
          (len(NEED), len(GONE), len(KEEP)))
