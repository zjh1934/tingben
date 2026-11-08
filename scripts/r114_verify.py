# -*- coding: utf-8 -*-
"""R114 · 字号阶梯 / 制作准备页收敛 / 音频焦点 / 系统栏反色：正向、守恒、反向注入。"""
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
SCALE = {12, 13, 14, 15, 16, 18, 20}
SP = re.compile(r'(\d+(?:\.\d+)?)\.sp')


def txt(root, key):
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
    ck(all(v >= 12 for v in sizes), 'F-08 仍有小于 12sp 的字')
    ck(all(v.is_integer() and int(v) in SCALE for v in sizes), 'F-08 字号不在统一阶梯内')
    ck(len(SCALE) == 7, 'F-08 阶梯定义被改动')

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
    ck('ExpandGroup("① 做什么", "")' in main, 'F-28 第一分区丢了')
    ck('ExpandGroup("② 怎么念"' in main, 'F-28 念法分区丢了')
    ck('ExpandGroup("③ 怎么跑", "")' in main, 'F-28 跑法分区丢了')
    ck('原文整段送声音模型' in main, 'F-28 缺一行路径说明')
    ck('cfg = Prefs.defaultPerf(c)' in main, 'F-28 恢复默认入口丢了')
    ck('directRead = true' in main, '守恒：不是恒直接朗读')
    ck('OptionRow("朗读档位"' in main and 'OptionRow("段间静音"' in main,
       '守恒：真实念法 / 跑法选项被误删')
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
    ck('HubTitles = listOf("章节", "合成", "可播")' in hub, '守恒：三列目录被改')
    ck('android.permission.INTERNET' not in mf, '守恒：Manifest 出现 INTERNET')
    ck('scripts/r114_verify.py' in ci, 'CI 未挂 R114')
    return e


INJECT = [
    ('字号退回 9sp', 'main', 'Text("去听")', 'Text("去听", fontSize = 9.sp)'),
    ('字号脱阶', 'opts', 'fontSize = 12.sp', 'fontSize = 12.5.sp'),
    ('模式卡高度退回', 'main', 'Modifier.weight(1f).height(92.dp)',
     'Modifier.weight(1f).height(76.dp)'),
    ('恢复多档枚举', 'main', 'private fun ProduceScreen(',
     'private enum class ProduceMode { DIRECT, ANNOTATE }\nprivate fun ProduceScreen('),
    ('恢复折叠状态', 'opts', '    Card(\n',
     '    var open by remember { mutableStateOf(true) }\n    Card(\n'),
    ('恢复展开按钮', 'opts', 'if (subtitle.isNotBlank())',
     'Text("展开 ▼", fontSize = 12.sp)\n            if (subtitle.isNotBlank())'),
    ('恢复只读组', 'main', '        Spacer(Modifier.height(10.dp))\n\n        Button(',
     '        ExpandGroup("④ 本次制作 · 只读", "") { }\n        Spacer(Modifier.height(10.dp))\n\n        Button('),
    ('恢复假单选', 'main', 'Text("原文整段送声音模型"',
     'ProduceMode.values().forEach { }\n            Text("原文整段送声音模型"'),
    ('删路径说明', 'main', 'Text("原文整段送声音模型", fontSize = 13.sp, color = Sub)', ''),
    ('删恢复默认', 'main', 'cfg = Prefs.defaultPerf(c)', ''),
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
            f = r / REL[key]
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
