#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R408 · 版本号入口补全守门（三路调研综合定案）。

守：点版本号 = 进「关于听本」页；检查更新只读 jsdelivr 公开 atom、
按点分段比数值、24h 节流；外链失败走复制/重试/分享/诊断兜底面板；
诊断与复制内容严格白名单（零正文 / 零 Token / 零文件 / 零设备标识）；
构建字段在编译期固定；随包发版说明离线可读；零新增依赖与权限。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 rc / main 两次 CI 负责。
用法：python3 scripts/r408_verify.py [--selftest]
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
FILES = {
    'chrome': APP / 'AppChrome.kt',
    'about': APP / 'AboutUi.kt',
    'checker': APP / 'core/UpdateChecker.kt',
    'gradle': ROOT / 'app/build.gradle.kts',
    'publish': ROOT / 'scripts/publish_apk.py',
    'assetgen': ROOT / 'scripts/make_changelog_asset.py',
    'ci': ROOT / '.github/workflows/ci.yml',
    'r135': ROOT / 'scripts/r135_verify.py',
    'r357': ROOT / 'scripts/r357_verify.py',
    'baseline': ROOT / 'contracts/ui_function_baseline.json',
    'manifest': ROOT / 'app/src/main/AndroidManifest.xml',
    'status': ROOT / 'STATUS.md',
}
CHAIN = {
    APP / 'core/Producer.kt': ['yieldToRemoteIfNeeded', 'spaceStopped', 'PRODUCE_RESUME'],
    APP / 'relay/RelayService.kt': ['RELAY_RESUME_OUT', 'RELAY_DEL', 'synthOk'],
    APP / 'relay/RelayClient.kt': ['fun statOut(', 'fun putObj('],
    APP / 'core/SynthArbiter.kt': ['setRemoteBatch(', 'remoteBatchDone('],
    APP / 'core/TaskResume.kt': ['fun recoverScope('],
    APP / 'gateway/GatewaySynth.kt': ['fun speak('],
    APP / 'diag/EventLog.kt': ['RETENTION_MS'],
}

def sources():
    d = {k: (v.read_text(encoding='utf-8', errors='replace') if v.exists() else '')
         for k, v in FILES.items()}
    for p, toks in CHAIN.items():
        d['chain:' + str(p.relative_to(ROOT))] = p.read_text(encoding='utf-8', errors='replace') if p.exists() else ''
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

# ── 纯内存逻辑：复刻 UpdateChecker.compare 与三份调研里的关键判定 ──────
def cmp_version(a, b, legacy=False):
    """legacy = 字符串比较（研究员 B 点名的坑：1.1.9 > 1.1.62 会误报）。"""
    if legacy: return -1 if a < b else (1 if a > b else 0)
    def parts(s):
        t = s.strip().lstrip('vV').split('-')[0].split('.')
        if not t or any(x.strip() == '' for x in t): return None
        try: return [int(x) for x in t]
        except ValueError: return None
    x, y = parts(a), parts(b)
    if x is None or y is None: return -2
    for i in range(max(len(x), len(y))):
        xi = x[i] if i < len(x) else 0
        yi = y[i] if i < len(y) else 0
        if xi != yi: return -1 if xi < yi else 1
    return 0

def state_of(current, latest, failed=False, skipped=False, legacy=False):
    """五态；失败必须给中文可重试文案，不许静默。"""
    if failed: return '网络开小差了，点此重试' if not legacy else ''
    c = cmp_version(current, latest, legacy=legacy)
    if c == -2: return '更新信息格式异常，可去项目主页查看'
    if c >= 0: return '已是最新版本'
    if skipped: return '已是最新版本'
    return '发现新版本 v' + latest

def privacy_ok(text, legacy=False):
    """复制/诊断白名单：黑名单一个字都不许出现。"""
    if legacy: return True
    ban = ['Token:', 'Android ID:', 'IMEI:', '序列号:', 'SSID:', 'OAID:',
           'BookStore', '书名:', '文件路径:', '鉴权头:', 'ghp_']
    return not any(x in text for x in ban)

def fallback_exits(exits, legacy=False):
    return True if legacy else {'重试打开', '分享链接', '复制诊断信息'} <= set(exits)

def logic():
    e = []
    if cmp_version('1.1.9', '1.1.62', legacy=True) < 0:
        e.append('逻辑：旧字符串比较没复现误报')
    if cmp_version('1.1.9', '1.1.62') >= 0: e.append('逻辑：1.1.9 没判成比 1.1.62 旧')
    if cmp_version('v1.1.62', '1.1.62') != 0: e.append('逻辑：v 前缀没剥掉')
    if cmp_version('1.1.62-hotfix', '1.1.63') >= 0: e.append('逻辑：后缀没剥掉')
    if cmp_version('1.1.x', '1.1.62') != -2: e.append('逻辑：格式异常没走 -2')
    if state_of('1.1.62', '1.1.9', legacy=True) == '已是最新版本':
        e.append('逻辑：字符串比较会误报新版本')
    if state_of('1.1.62', '1.1.9') != '已是最新版本': e.append('逻辑：低版本没判已是最新')
    if '发现新版本' not in state_of('1.1.62', '1.1.64'): e.append('逻辑：新版本没弹')
    if state_of('1.1.62', '1.1.64', skipped=True) != '已是最新版本':
        e.append('逻辑：跳过此版本没生效')
    if state_of('1.1.62', '1.1.64', failed=True) != '网络开小差了，点此重试':
        e.append('逻辑：失败没给中文兜底')
    if '格式异常' not in state_of('1.1.62', 'x.y.z'):
        e.append('逻辑：格式异常没单独给文案')
    good = '听本 1.1.62 (162) debug\ngit:ab12cd3 build:2026-11-26\nAndroid 14 (SDK 34) · Xiaomi 2311DRK48C\nABI:arm64-v8a · 更新源:jsdelivr\n不含书名 / 文件名 / 朗读正文 / Token / 设备标识'
    if not privacy_ok(good): e.append('逻辑：白名单样本被误判')
    bad = good + '\nToken: ghp_xxx\n书名: 红楼梦\nAndroid ID: 123'
    if privacy_ok(bad): e.append('逻辑：黑名单漏放')
    if fallback_exits(['重试打开', '分享链接']): e.append('逻辑：少一个出口没被拦')
    if not fallback_exits(['重试打开', '分享链接', '复制诊断信息']):
        e.append('逻辑：三出口齐却被判不全')
    return e

BAD = [
    ('版本号退回纯外链', lambda d: d.update(chrome=d['chrome'].replace(
        '.clickable { showAbout = true }', '.clickable { }', 1))),
    ('关于页入口被摘', lambda d: d.update(chrome=d['chrome'].replace(
        'if (showAbout) AboutScreen(ctx) { showAbout = false }', '', 1))),
    ('读屏文案被摘', lambda d: d.update(chrome=d['chrome'].replace(
        'contentDescription = "关于听本，版本 "', 'contentDescription = ""', 1))),
    ('死路 toast 回潮', lambda d: d.update(chrome=d['chrome'].replace(
        '// R408 起点版本号进', '// 没找到浏览器，可手动打开 github.com\n            // R408 起点版本号进', 1))),
    ('检查更新能力被摘', lambda d: d.update(about=d['about'].replace(
        'UpdateChecker.check(c, force)', 'UpdateChecker.State.Latest("x", false)', 1))),
    ('五态类被摘', lambda d: d.update(checker=d['checker'].replace(
        'data class Failed(val reason: String)', '', 1))),
    ('版本比较退化成字符串', lambda d: d.update(checker=d['checker'].replace(
        'if (xi != yi) return if (xi < yi) -1 else 1',
        'return a.compareTo(b)', 1))),
    ('24h 节流被摘', lambda d: d.update(checker=d['checker'].replace(
        'THROTTLE_MS = 24L', 'THROTTLE_MS = 0L', 1))),
    ('单源被换成 raw', lambda d: d.update(checker=d['checker'].replace(
        'https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom',
        'https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/updates.atom', 1))),
    ('请求带了 Token', lambda d: d.update(checker=d['checker'].replace(
        '"TingBen/${BuildConfig.VERSION_NAME} (Android; update-check)")',
        '"TingBen/${BuildConfig.VERSION_NAME} (Android; update-check)")\n            conn.setRequestProperty("Authorization", "token " + RelayPrefs.token(c))', 1))),
    ('日志写了响应体', lambda d: d.update(checker=d['checker'].replace(
        '"code=$code ms=$ms cache=$cache latest=$latest src=$CURRENT_SOURCE")',
        '"code=$code body=$body")', 1))),
    ('兜底面板被摘', lambda d: d.update(about=d['about'].replace(
        '这台设备暂时打不开网页', '', 1))),
    ('兜底少一个出口', lambda d: d.update(about=d['about'].replace(
        'AboutAction("复制诊断信息"', 'AboutAction(""'))),
    ('完整链接不可选中', lambda d: d.update(about=d['about'].replace(
        'SelectionContainer {\n                                Text(url', 'Text(url', 1))),
    ('Snackbar 退回 Toast', lambda d: d.update(about=d['about'].replace(
        'Snackbar(modifier = Modifier.align(Alignment.BottomCenter)',
        'android.widget.Toast.makeText(c, snack, 1).show()\n                    Box(modifier = Modifier.align(Alignment.BottomCenter)', 1))),
    ('诊断塞进设备标识', lambda d: d.update(about=d['about'].replace(
        'append("不含书名 / 文件名 / 朗读正文 / Token / 设备标识")',
        'append(Build.SERIAL).append(" Android ID").append(" Token")', 1))),
    ('诊断白名单被删', lambda d: d.update(about=d['about'].replace(
        'private fun diagnostics(c: Context): String', 'private fun diagnosticsGone(c: Context): String', 1))),
    ('构建时间用运行时钟', lambda d: d.update(gradle=d['gradle'].replace(
        'buildConfigField("String", "BUILD_TIME"',
        '// MISSING buildConfigField(', 1))),
    ('commit 注入被摘', lambda d: d.update(gradle=d['gradle'].replace(
        'buildConfigField("String", "GIT_HASH"', '// MISSING buildConfigField(', 1))),
    ('随包说明没挂 CI', lambda d: d.update(ci=d['ci'].replace(
        'python3 scripts/make_changelog_asset.py', 'echo skip', 1))),
    ('随包说明回潮成 .md', lambda d: d.update(about=d['about'].replace(
        'c.assets.open("changelog.txt")', 'c.assets.open("CHANGELOG.md")', 1))),
    ('闸门被放宽', lambda d: d.update(ci=d['ci'].replace(
        r'\.(md|py|log|jpeg|jpg)$', r'\.(py|log|jpeg|jpg)$', 1))),
    ('atom 不带本版说明', lambda d: d.update(publish=d['publish'].replace(
        '<content type="text">%s</content>', '', 1))),
    ('引入 WebView', lambda d: d.update(about=d['about'].replace(
        'import androidx.compose.material3.Surface',
        'import androidx.compose.material3.Surface\nimport android.webkit.WebView\n// WebView(c)', 1))),
    ('彩蛋动合成链', lambda d: d.update(about=d['about'].replace(
        'EventLog.log(EventLog.Kind.UI, "ABOUT_EASTER_EGG"',
        'ClipSynth.synthesize(c, "版本号", "", "")\n                                        EventLog.log(EventLog.Kind.UI, "ABOUT_EASTER_EGG"', 1))),
    ('r357 没迁移', lambda d: d.update(r357=d['r357'].replace(
        'R408 起版本号点击进', '旧口径：版本号跳仓库', 1))),
    ('现行清单漏登记', lambda d: d.update(r135=d['r135'].replace('"r408_verify.py"', '', 1))),
    ('CI 漏挂正向', lambda d: d.update(ci=d['ci'].replace(
        'run: python3 scripts/r408_verify.py\n', 'run: echo skip\n', 1))),
    ('CI 漏挂反向', lambda d: d.update(ci=d['ci'].replace(
        'python3 scripts/r408_verify.py --selftest', '', 1))),
    ('链路符号', lambda d: d.update(**{
        'chain:app/src/main/java/com/tingben/app/relay/RelayService.kt': '/* gone */'})),
    ('功能守恒旧页被改', lambda d: d.update(baseline='{}')),
    ('欠账未登记', lambda d: d.update(status=d['status'].replace('真 MP3', ''))),
    ('APK 路径空安全被摘', lambda d: d.update(about=d['about'].replace(
        'p.applicationInfo?.sourceDir ?: throw IllegalStateException("no apk path")',
        'p.applicationInfo.sourceDir', 1))),
]

def check(s):
    e = []
    for k in FILES:
        if not s.get(k): e.append('缺文件: ' + k)
    if e: return e
    chrome, about, ck, grad, pub = s['chrome'], s['about'], s['checker'], s['gradle'], s['publish']
    ci, r135, r357, bl, man, status = s['ci'], s['r135'], s['r357'], s['baseline'], s['manifest'], s['status']
    code_only = lambda x: '\n'.join(
        l for l in x.splitlines() if not l.strip().startswith(('//', '*', '/*')))
    ck_code, about_code = code_only(ck), code_only(about)
    # ① 入口：版本号 -> 关于页，旧死路零残留
    for tok in ['var showAbout by remember { mutableStateOf(false) }',
                '.clickable { showAbout = true }',
                'if (showAbout) AboutScreen(ctx) { showAbout = false }',
                'contentDescription = "关于听本，版本 "',
                'RepoLinkGlyph(primary)']:
        if tok not in chrome: e.append('抽屉入口缺: ' + tok[:58])
    if '没找到浏览器' in chrome: e.append('抽屉又出现死路提示')
    if 'Intent(Intent.ACTION_VIEW' in chrome: e.append('抽屉又变回纯外链')
    if chrome.count('Modifier.weight(1f).height(44.dp)') != 2:
        e.append('抽屉两枚 44dp 等宽令牌被改')
    # ② 关于页六块
    for tok in ['"检查更新"', '"版本信息"', '"诊断信息"', '"项目主页"',
                '"发版说明"', '"开源组件"', '关于听本', 'statusBarsPadding()',
                'navigationBarsPadding()', 'verticalScroll(rememberScrollState())',
                'heightIn(min = 48.dp)', 'contentDescription = "返回"']:
        if tok not in about: e.append('关于页缺: ' + tok[:58])
    if '没找到浏览器' in about: e.append('关于页出现死路提示')
    if 'WebView(' in about or 'CustomTabsIntent' in about:
        e.append('关于页引了 WebView / Custom Tabs（调研明确不做）')
    if 'ClipSynth' in about or 'SynthArbiter' in about:
        e.append('关于页碰了合成链（彩蛋只该切静态可见性）')
    # ③ 兜底面板五要素
    for tok in ['这台设备暂时打不开网页', 'SelectionContainer {',
                'AboutAction("重试打开"', 'AboutAction("分享链接"',
                'AboutAction("复制诊断信息"', 'Snackbar(']:
        if tok not in about: e.append('兜底面板缺: ' + tok[:58])
    if 'createChooser' not in about: e.append('分享没包 createChooser')
    # ④ 隐私白名单
    for ban in ['Build.SERIAL', 'ANDROID_ID', 'IMEI', 'OAID', 'getImei', 'SSID',
                'BookStore', 'RelayPrefs.token']:
        if ban in ck_code or ban in about_code:
            e.append('隐私黑名单命中: ' + ban)
    for tok in ['不含书名 / 文件名 / 朗读正文 / Token / 设备标识',
                '不含任何书名、文件、朗读正文、Token 与设备标识']:
        if tok not in about: e.append('隐私承诺文案缺: ' + tok[:48])
    # ⑤ 检查更新
    for tok in ['const val FEED = "https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom"',
                'fun compare(a: String, b: String): Int',
                'return -2', 'THROTTLE_MS = 24L', 'cached(c)', 'skipped(c)',
                'UPDATE_CHECK', 'Xml.newPullParser()', 'rel") == "enclosure"',
                'connectTimeout', 'readTimeout', 'setRequestProperty("User-Agent"']:
        if tok not in ck: e.append('更新检查缺: ' + str(tok)[:58])
    for ban in ['raw.githubusercontent.com', 'api.github.com',
                'setRequestProperty("Referer"', 'setRequestProperty("Authorization"',
                'RelayPrefs.token', 'getDeviceId', 'getSubscriberId']:
        if ban in ck_code: e.append('更新请求带了不该带的: ' + ban)
    for tok in ['data class Failed(val reason: String)',
                'if (xi != yi) return if (xi < yi) -1 else 1',
                '"code=$code ms=$ms cache=$cache latest=$latest src=$CURRENT_SOURCE"']:
        if tok not in ck: e.append('更新检查关键实现缺: ' + tok[:58])
    if 'UpdateChecker.check(c, force)' not in about:
        e.append('关于页没真的调检查更新')
    if about.count('SelectionContainer {') < 2:
        e.append('完整链接与项目地址没都做成可选中')
    if 'Snackbar(' not in about: e.append('复制反馈没用 Snackbar')
    if 'private fun diagnostics(c: Context): String' not in about:
        e.append('诊断白名单构造函数被删')
    # 真编译反馈：applicationInfo 可空，静态守门提前拦（CI 才报太慢）
    if 'p.applicationInfo?.sourceDir ?: throw IllegalStateException' not in about:
        e.append('APK 路径没做空安全（CI 必报 Only safe (?.) calls）')
    if 'File(p.applicationInfo.sourceDir)' in about:
        e.append('APK 路径回潮成非空安全调用')
    if 'run: python3 scripts/r408_verify.py\n' not in ci:
        e.append('CI 没挂 r408 正向单行')
    if 'R408 起版本号点击进' not in r357:
        e.append('r357 没按新形态迁移（注释锚）')
    if 'stateFrom(c, info, false, !force)' not in ck:
        e.append('手动检查没绕过跳过此版本')
    if 'State.Failed' not in ck or '网络开小差了，点此重试' not in ck:
        e.append('失败没给中文可重试文案')
    # ⑥ 构建期字段 / 随包说明 / 发版侧
    grad_code = '\n'.join(l for l in grad.splitlines() if not l.strip().startswith(('//', '*')))
    for tok in ['buildConfigField("String", "GIT_HASH"',
                'buildConfigField("String", "BUILD_TIME"',
                'GITHUB_SHA', 'import java.time.Instant', 'Instant.now().toString()']:
        if tok not in grad_code: e.append('构建字段缺: ' + tok[:58])
    if 'Instant.now().toString()' in grad_code and 'buildConfigField("String", "BUILD_TIME"' not in grad_code:
        e.append('构建时间没写进 BuildConfig')
    # 构建脚本里 Instant.now() 是构建期固定值；App 运行时不得再取时钟。
    if 'System.currentTimeMillis()' in grad_code: e.append('构建时间用了运行时钟')
    if 'BuildConfig.GIT_HASH' not in about or 'BuildConfig.BUILD_TIME' not in about:
        e.append('关于页没读构建字段')
    if '<content type="text">%s</content>' not in pub: e.append('atom 没带本版说明')
    if 'python3 scripts/make_changelog_asset.py' not in ci:
        e.append('CI 没挂随包发版说明生成脚本')
    if not s.get('assetgen'): e.append('缺 make_changelog_asset.py')
    ag = s.get('assetgen', '')
    if 'app/src/main/assets/changelog.txt' not in ag or 'CHANGELOG.md' not in ag:
        e.append('随包说明生成脚本没写 changelog.txt')
    if 'DEV_MARKS' not in ag:
        e.append('随包说明没剔除开发者复盘段')
    if 'c.assets.open("changelog.txt")' not in about:
        e.append('关于页没读随包 changelog.txt')
    # R142 闸门一条不放宽：.md 等开发者资料仍禁止进 APK
    if r'\.(md|py|log|jpeg|jpg)$' not in ci or 'APK 里混进了开发者资料' not in ci:
        e.append('APK 闸门被放宽（不许靠改闸门绕过 .md 禁令）')
    if 'changelogSections(c)' not in about: e.append('关于页没读随包说明')
    # ⑦ 守恒：零新权限 / 零新依赖 / 老页面控件零差异 / 链路符号
    if 'android.permission.WEBVIEW' in man or 'android.permission.READ_PHONE_STATE' in man:
        e.append('新增了不该有的权限')
    if 'androidx.browser' in grad or 'aboutlibraries' in grad.lower():
        e.append('引了调研明确不用的依赖')
    if '"r408_verify.py"' not in r135: e.append('现行清单漏登记 r408')
    if 'python3 scripts/r408_verify.py' not in ci or \
            'python3 scripts/r408_verify.py --selftest' not in ci:
        e.append('CI 漏挂 r408 正反向')
    if '.clickable { showAbout = true }' not in r357: e.append('r357 没按新形态迁移')
    for p, toks in CHAIN.items():
        tt = s.get('chain:' + str(p.relative_to(ROOT)), '')
        for k in toks:
            if k not in tt: e.append('链路符号丢失: %s -> %s' % (p.name, k))
    try: old = json.loads(bl)['files']
    except Exception as ex: e.append('基线不可读: %s' % ex); old = {}
    live = live_counts()
    for f, cnt in old.items():
        if f not in live: e.append('功能守恒: 文件消失 ' + f); continue
        for k, v in cnt.items():
            if live[f].get(k) != v:
                e.append('功能守恒: %s 的 %s %s→%s' % (f, k, v, live[f].get(k)))
    for need in ['长文本分段', '真 MP3', '厂商清理', '细粒度 PAT']:
        if need not in status: e.append('STATUS 未登记欠账: ' + need)
    e += logic()
    return e

def selftest():
    base = sources(); ok = 0
    for name, mut in BAD:
        d = dict(base); mut(d)
        if check(d): ok += 1
        else: print('  反向注入漏放:', name)
    print('R408 反向注入 %d/%d 全拦' % (ok, len(BAD)))
    return ok == len(BAD)

def main():
    if '--selftest' in sys.argv: return 0 if selftest() else 1
    e = check(sources())
    if e:
        for x in e: print('RED:', x)
        print('R408: %d 处红' % len(e)); return 1
    print('R408 PASS · 版本号进关于页 / 五态更新 / 兜底三出口 / 隐私白名单 / 构建期字段')
    print('  逻辑用例全绿 · 功能守恒 %d 个老 kt 文件零差异 · 链路 %d 文件全在' %
          (len(json.loads(sources()['baseline'])['files']), len(CHAIN)))
    return 0

if __name__ == '__main__': sys.exit(main())
