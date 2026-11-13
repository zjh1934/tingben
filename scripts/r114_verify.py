# -*- coding: utf-8 -*-
"""R114 · 字号阶梯 / 制作准备页收敛 / 音频焦点 / 系统栏反色：正向、守恒、反向注入。

R277 按现状改准：R221 砍「② 怎么念」、R231 加拼章分区、R245/R253/R264/R276
之后副标题普遍用 11.sp、小标签 10.sp、三列目录早已合并、制作章恒定展开。
旧判据 6 条绑死已删形态，正向恒红，反向注入因此永远「被拦下」= 假绿。
本轮把 6 条按现行形态改准、旧串一律列 BAN 防回潮；焦点/主题/指令/提交
等真守恒一条没放宽，注入从 22 例补到 28 例，正向绿了注入才真的有效。
"""
import ui_sources
import core_sources
import pathlib, re, shutil, sys, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = 'app/src/main/java/com/tingben/app'
REL = {
    'main': APP + '/MainActivity.kt',
    'opts': APP + '/ProduceOptions.kt',
    'queue': APP + '/core/PlayQueue.kt',
    'theme': APP + '/Theme.kt',
    'config': APP + '/core/ProduceConfig.kt',
    'pipe': APP + '/core/Pipeline.kt',
    'hub': APP + '/ChapterHub.kt',
    'manifest': 'app/src/main/AndroidManifest.xml',
    'ci': '.github/workflows/ci.yml',
}
# R277 · 现行全仓实测 {10,11,12,13,14,15,16,17,18,20}；下限 10 不是删守恒，
# 注入「退回 9sp」「脱阶 12.5sp」两条仍必须拦下（见 INJECT）。
SCALE = {10, 11, 12, 13, 14, 15, 16, 17, 18, 20}
MIN_SP = min(SCALE)
SP = re.compile(r'(\d+(?:\.\d+)?)\.sp')


def txt(root, key):
    if REL[key].endswith('Pipeline.kt'):
        return core_sources.read(root)
    if key == 'main':          # R206 · MainActivity 走合并视图
        return ui_sources.read(root)
    p = root / REL[key]
    return p.read_text(encoding='utf-8') if p.exists() else ''


def all_kt(root):
    base = root / 'app/src/main'
    return '\n'.join(p.read_text(encoding='utf-8', errors='replace')
                     for p in sorted(base.rglob('*.kt')) if p.is_file())


def check(root):
    e = []
    def ck(ok, msg):
        if not ok:
            e.append(msg)
    main, opts, queue, theme = (txt(root, k) for k in ('main', 'opts', 'queue', 'theme'))
    config, pipe, hub = (txt(root, k) for k in ('config', 'pipe', 'hub'))
    mf, ci = txt(root, 'manifest'), txt(root, 'ci')
    kt = all_kt(root)

    sizes = [float(m.group(1)) for m in SP.finditer(kt)]
    ck(bool(sizes), '没有扫到任何 .sp 字号')
    ck(all(v >= MIN_SP for v in sizes), 'F-08 仍有小于 %dsp 的字' % MIN_SP)
    ck(all(v.is_integer() and int(v) in SCALE for v in sizes), 'F-08 字号不在统一阶梯内')
    ck(len(SCALE) == 10, 'F-08 阶梯定义被改动')

    ck('enum class ProduceMode' not in main, 'F-28 单值假枚举没删')
    ck('defaultOpen' not in opts and 'defaultOpen' not in main, 'F-28 折叠默认态没删')
    ck('var open by remember' not in opts, 'F-28 折叠状态仍在')
    ck('展开 ▼' not in opts and '收起 ▲' not in opts, 'F-28 展开按钮没删')
    ck('④ 本次制作' not in main and '仅本次' not in main, 'F-28 旧分区话术残留')
    ck('默认锚点' not in main and '采样种子' not in main, 'F-28 只读信息组没删')
    ck('ProduceMode.values()' not in main and 'RadioButton(selected' not in main,
       'F-28 只有一个选项的假单选仍在')
    ck('Modifier.weight(1f).height(92.dp)' in main,
       'F-08 三张模式卡没给 12sp 三行留出高度，会裁字')
    ck('ExpandGroup("这本书", "")' in main, 'F-28 第一分区丢了')
    # R221 砍「② 怎么念」、编号顺延；R231 加「③ 拼成这一章」。
    # 旧两条绑死已删形态、正向恒红，按现行分区改准，旧串列 BAN 防回潮。
    ck('ExpandGroup("② 怎么念"' not in main, 'F-28 已砍念法分区回潮')
    ck('SettingsSection("制作设置", "合成", collapsible = false)' in main, 'F-28 跑法分区丢了')
    ck('OptionRow("自动拼成整章"' in main, 'F-28 拼章分区丢了（R231）')
    ck('ExpandGroup("③ 怎么跑", "")' not in main, 'F-28 旧编号回潮')
    # R321 · R318 把这句换成「参数在设置页 · 勾章在目录页」，旧锚恒红；
    # 按现状迁移，仍要求这一行路径说明存在，注入「删路径说明」照旧拦。
    ck('参数在设置页 · 勾章在目录页' in main, 'F-28 缺一行路径说明')
    ck('Prefs.setDefaultPerf(c, cfg)' in main, 'F-28 设置落盘丢了')
    ck('directRead = true' in main, '守恒：不是恒直接朗读')
    ck('OptionRow("段间静音"' in main and 'OptionRow("分段失败重做"' in main,
       '守恒：真实跑法选项被误删')
    ck('OptionRow("朗读档位"' not in main, '守恒：已砍朗读档位回潮（R221）')
    used_opt = set(re.findall(r'\bOpt[A-Z]\w*', opts))
    defined_opt = set(re.findall(r'private val (Opt\w+):', opts))
    ck(used_opt <= defined_opt, 'F-28 颜色符号有引用却没定义：%s' % sorted(used_opt - defined_opt))

    ck('AudioFocusRequest' in queue, 'F-26 缺焦点请求')
    ck('requestAudioFocus(req)' in queue, 'F-26 没申请焦点')
    ck('abandonAudioFocusRequest' in queue, 'F-26 没归还焦点')
    ck('IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)' in queue,
       'F-26 缺拔耳机广播注册')
    ck('RECEIVER_NOT_EXPORTED' in queue, 'F-26 广播没设非导出')
    ck('AUDIOFOCUS_LOSS_TRANSIENT' in queue, 'F-26 短暂失焦没处理')
    ck('return@setOnPreparedListener' in queue, 'F-26 prepared 时没校验焦点')
    ck(queue.count('if (!ensureFocus())') >= 2, 'F-26 续播与开播两条路径没都校验')
    ck(queue.count('pause()') >= 4, 'F-26 失焦/拔耳机没走同一暂停入口')
    ck('PlayQueue.attach(this)' in main, 'F-26 Activity 没挂监听')

    ck('WindowInsetsControllerCompat' in theme, 'F-27 缺系统栏控制器')
    ck('DisposableEffect(dark)' in theme, 'F-27 主题切换没同步')
    ck('isAppearanceLightStatusBars = !dark' in theme, 'F-27 状态栏图标没反色')
    ck('isAppearanceLightNavigationBars = !dark' in theme, 'F-27 导航栏图标没反色')
    ck('TingbenTheme {' in main, '守恒：主题包裹被改')

    ck('fun styleInstruct()' in config, '守恒：语气指令拼装被删')
    ck('config.styleInstruct()' in pipe, '守恒：语气指令没进合成链路')
    ck('config.gapMs' in pipe, '守恒：段间静音没进合并')
    ck('commitAll()' in main, '守恒：唯一提交出口被改')
    # R163 三列已合并成单页、R264 制作章恒定展开，旧 HubTitles 恒红。
    ck('val liveChapter: Int? = if (bookRunning) makingChapter else null' in hub,
       '守恒：制作章恒定展开被改（R264 派生值）')
    ck('HubTitles = listOf(' not in hub, '守恒：三列目录回潮（R163 已合并）')
    ck('android.permission.INTERNET' not in mf, '守恒：Manifest 出现 INTERNET')
    ck('scripts/r114_verify.py' in ci, 'CI 未挂 R114')
    return e


INJECT = [
    ('字号退回 9sp', 'main', 'Text("去听")', 'Text("去听", fontSize = 9.sp)'),
    ('字号脱阶', 'opts', 'fontSize = 12.sp', 'fontSize = 12.5.sp'),
    ('模式卡高度退回', 'main', 'Modifier.weight(1f).height(92.dp)',
     'Modifier.weight(1f).height(76.dp)'),
    # 锚别用 `private fun ProduceScreen(`：它在合并视图里紧跟 @Composable，
    # 插一行会把 PARTS 的 start 锚劈开、write_text 整树写回被跳过，注入假生效。
    ('恢复多档枚举', 'main', 'OptionRow("段间静音"',
     'enum class ProduceMode { DIRECT, ANNOTATE }\n    OptionRow("段间静音"'),
    ('恢复折叠状态', 'opts', '    Card(\n',
     '    var open by remember { mutableStateOf(true) }\n    Card(\n'),
    ('恢复展开按钮', 'opts', 'if (subtitle.isNotBlank())',
     'Text("展开 ▼", fontSize = 12.sp)\n            if (subtitle.isNotBlank())'),
    ('恢复只读组', 'main', '        Button(onClick = onBack,',
     '        ExpandGroup("④ 本次制作 · 只读", "") { }\n        Button(onClick = onBack,'),
    ('恢复假单选', 'main', 'Text("参数在设置页 · 勾章在目录页"',
     'ProduceMode.values().forEach { }\n            Text("参数在设置页 · 勾章在目录页"'),
    ('删路径说明', 'main',
     'Text("参数在设置页 · 勾章在目录页",\n            fontSize = 11.sp, color = Sub)', ''),
    ('删恢复默认', 'main', 'Prefs.setDefaultPerf(c, cfg)', ''),
    ('删颜色定义', 'opts', 'private val OptPrimary: Color get() = palette.primary\n', ''),
    ('去掉焦点申请', 'queue', 'am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED',
     'true'),
    ('去掉焦点归还', 'queue', '?.abandonAudioFocusRequest(req)', ''),
    ('去掉拔耳机', 'queue', 'IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)',
     'IntentFilter("x")'),
    ('去掉播放前校验', 'queue', 'if (!ensureFocus()) { pause(); return@setOnPreparedListener }', ''),
    ('Activity 不挂监听', 'main', 'PlayQueue.attach(this)', ''),
    ('去掉状态栏反色', 'theme', 'controller.isAppearanceLightStatusBars = !dark', ''),
    ('去掉导航栏反色', 'theme', 'controller.isAppearanceLightNavigationBars = !dark', ''),
    ('主题变化不同步', 'theme', 'DisposableEffect(dark)', 'DisposableEffect(Unit)'),
    ('删语气指令', 'config', 'fun styleInstruct()', 'fun styleInstructGone()'),
    ('删除唯一提交', 'main', 'commitAll()', ''),
    ('CI 摘掉 R114', 'ci', 'python3 scripts/r114_verify.py', ''),
    # R277 · 旧 6 条正向恒红时，下面这些「回潮」注入永远被误判成拦下。
    # 正向改绿后才真的验证得到，逐条补上、一条不放宽。
    ('念法分区回潮', 'main', 'SettingsSection("制作设置", "合成", collapsible = false)',
     'ExpandGroup("② 怎么念", "") { }\n    SettingsSection("制作设置", "合成", collapsible = false)'),
    ('跑法分区被摘', 'main', 'SettingsSection("制作设置", "合成", collapsible = false)',
     'ExpandGroup("X 怎么跑", "")'),
    ('拼章分区被摘', 'main', 'OptionRow("自动拼成整章"',
     'ExpandGroup("X 拼成这一章"'),
    ('朗读档位回潮', 'main', 'OptionRow("段间静音"',
     'OptionRow("朗读档位", "", 0) { }\n    OptionRow("段间静音"'),
    ('恒定展开被摘', 'hub', 'val liveChapter: Int? = if (bookRunning) makingChapter else null',
     'val liveChapter: Int? = null'),
    ('三列目录回潮', 'hub', 'val liveChapter: Int? = if (bookRunning) makingChapter else null',
     'HubTitles = listOf("章节", "合成", "可播")\nval liveChapter: Int? = if (bookRunning) makingChapter else null'),
]


def selftest():
    # 原来每个注入用例都新建临时目录并复制整棵 Kotlin 树（22 次全盘拷贝），
    # 既慢又偶发 Stale file handle 假红。改成：基准树只建一次，
    # 每例只改一个文件、检查完立刻写回原文，下一例仍基于同一棵干净基准。
    # 断言语义完全不变，仍是「基准 + 单一破坏」。
    errs = []
    with tempfile.TemporaryDirectory() as d:
        r = pathlib.Path(d)
        for rel in REL.values():
            p = ROOT / rel
            if not p.exists():
                continue
            q = r / rel
            q.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, q)
        (r / 'app/src/main/java/com/tingben/app/core').mkdir(parents=True, exist_ok=True)
        for p in (ROOT / 'app/src/main').rglob('*.kt'):
            q = r / p.relative_to(ROOT)
            q.parent.mkdir(parents=True, exist_ok=True)
            if not q.exists():
                shutil.copy2(p, q)
        for name, key, old, new in INJECT:
            f = ui_sources.source(r) if key == 'main' else r / REL[key]
            s = f.read_text(encoding='utf-8')
            if old not in s:
                errs.append('注入锚点不存在：' + name)
                continue
            f.write_text(s.replace(old, new), encoding='utf-8')
            try:
                if not check(r):
                    errs.append('没拦下：' + name)
            finally:
                f.write_text(s, encoding='utf-8')
    return errs


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        bad = selftest()
        print('反向注入 %d 例，漏拦 %d' % (len(INJECT), len(bad)))
        for x in bad:
            print('  ', x)
        sys.exit(1 if bad else 0)
    e = check(ROOT)
    print('R114 正向 %d 条失败' % len(e))
    for x in e:
        print('  ', x)
    sys.exit(1 if e else 0)
