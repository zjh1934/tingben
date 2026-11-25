#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R390 · 1.1.52 真机截图实证的三处问题，本轮一次性修完并守死。

① 设置页下**所有**二级页都叠了两条标题：主顶栏「设置」大标题 + 页内
   「‹ 请求文本」大标题，白占一整行。改成 App 层持有 settingsPage，
   主顶栏唯一一层「返回箭头 + 当前页名」，返回箭头先回设置首页。
② 请求文本页把两个渠道「中间劈开左右并排」，右列 0 条时半屏空着很难看。
   改成整宽选项卡：点一下或左右滑切换，当前渠道铺满横屏。
③ 说明文字占大半个卡片、主次不分。改成页面底部两行微字：不折叠、不藏
   小问号，放在不显眼位置，列表才是重点。

纯静态语义判据；沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r390_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / ui_sources.UI
FILES = {
    # R206 · MainActivity.kt 必须走统一读取层，不许退回直读单文件。
    'main': ui_sources.source(APP),
    'settings': ROOT / 'app/src/main/java/com/tingben/app/SettingsUi.kt',
    'shell': ROOT / 'app/src/main/java/com/tingben/app/Shell.kt',
    'chrome': ROOT / 'app/src/main/java/com/tingben/app/AppChrome.kt',
    'archive': ROOT / 'app/src/main/java/com/tingben/app/ArchiveUi.kt',
    'r135': ROOT / 'scripts/r135_verify.py',
    'ci': ROOT / '.github/workflows/ci.yml',
}

def sources():
    return {k: v.read_text(encoding='utf-8') for k, v in FILES.items()}

def code_lines(text):
    """滤掉注释行再查「禁用串」：修复说明里会提到旧形态，那是解释不是回潮。"""
    return '\n'.join(l for l in text.splitlines()
                     if not l.lstrip().startswith(('//', '*', '/*', '*/')))

def check(s):
    e = []
    m, st, sh, ch, a = s['main'], s['settings'], s['shell'], s['chrome'], s['archive']
    mc, stc, ac = code_lines(m), code_lines(st), code_lines(a)

    # ① 顶栏只留一层：状态上提、标题按子页、返回先回设置首页
    if 'var settingsPage by rememberSaveable { mutableStateOf("") }' not in m:
        e.append('App 层没有 settingsPage 状态（顶栏没法按子页写标题）')
    if 'Screen.SETTINGS -> chrome.copy(' not in m or \
            'settingsPageTitle(settingsPage)' not in m:
        e.append('主顶栏没按当前设置子页显示页名')
    if 'screen == Screen.SETTINGS && settingsPage.isNotEmpty()' not in mc or \
            'settingsPage = ""' not in mc:
        e.append('返回箭头没做「二级页先回设置首页」')
    if 'BackHandler(enabled = canGoBack(screen) || nav.canPop()) { backAction() }' not in m:
        e.append('系统返回键没走同一个出口')
    if 'page = settingsPage' not in m or 'onPageChange = { settingsPage = it }' not in m:
        e.append('SettingsScreen 没接上子页状态与上报回调')
    if mc.count('settingsPage = ""') < 4:
        e.append('设置入口 / 返回 / 重置不全（至少 4 处）')
    if 'page: String,' not in st or 'onPageChange: (String) -> Unit' not in st:
        e.append('SettingsScreen 签名没接子页状态')
    if 'var page by remember' in st:
        e.append('设置页又自己藏了一份子页状态（应只由 App 层持有）')
    if 'SettingsSubHeader' in st or \
            'fontSize = HiTitleSp, fontWeight = FontWeight.Bold' in st:
        e.append('设置页又画了第二条大标题行（真机截图那个重复层级回潮）')
    if 'BackHandler(enabled = page.isNotEmpty()) { onPageChange("") }' not in st:
        e.append('系统返回键没先回设置首页')
    for k in ('onPageChange("appearance")', 'onPageChange("runtime")',
              'onPageChange("produce")', 'onPageChange("perms")',
              'onPageChange("files")', 'onPageChange("gateway")',
              'onPageChange("relay")', 'onPageChange("archive")',
              'onPageChange("dev")'):
        if k not in st:
            e.append('设置入口没统一上报：' + k)
    if 'Screen.SETTINGS -> ChromeSpec("", canBack = true)' not in sh:
        e.append('Shell.kt 还写着「设置」标题（会和主顶栏拼成两套口径）')
    if 'fontSize = HiTitleSp,\n                fontWeight = FontWeight.Bold' not in ch:
        e.append('主顶栏页面级标题被摘')

    # ② 请求文本页：整宽选项卡切换，不许左右并排回潮
    if 'var tab by remember { mutableStateOf(0) }' not in a:
        e.append('缺「当前渠道」tab 状态')
    for k in ('fun TabChip(', 'TabChip(Modifier.weight(1f), "跨网中继"',
              'TabChip(Modifier.weight(1f), "语音网关"',
              'fun goTab(next: Int)', 'detectDragGestures(',
              'onDragEnd = {', 'if (abs(dragX) > 60f)'):
        if k not in a:
            e.append('整宽选项卡缺：' + k)
    if 'fun ColumnScope.Channel(' in a or 'Channel("跨网中继"' in a:
        e.append('回潮成「中间劈开左右两列」（真机截图问题②）')
    if 'CardBox(Modifier.pointerInput(tab)' not in a:
        e.append('横滑切换没挂在列表主区上')

    # ③ 说明降级：不折叠不藏，放在列表之后，底部两行微字
    if '④ 说明降级' not in a or a.find('④ 说明降级') < a.find('② 主区'):
        e.append('说明文字没挪到列表之后（主次颠倒）')
    note = a.split('④ 说明降级', 1)[-1].split('if (askClear)', 1)[0]
    note_code = code_lines(note)
    if 'showHow' in ac or '"?"' in ac or 'AlertDialog(' in note_code:
        e.append('说明文字又藏进折叠 / 小问号 / 弹窗')
    if note.count('fontSize = HiMicroSp') < 2 or 'HiSectionSp' in note_code:
        e.append('说明没压成两行微字（仍是大段说明抢重点）')
    if 'fontSize = HiSectionSp, fontWeight = FontWeight.Bold' not in a:
        e.append('列表主区标题没提级（重点不突出）')

    # ④ 功能守恒：R387 的能力一条没删，R383 唯一滚动层不回潮
    for k in ('RequestArchive.DEFAULT_SHOW', '"一键复制全部"', '"多选"',
              '"删除选中"', '"一键清空全部文本"', '"确认清空"', '"复制"',
              '72.dp', 'AlertDialog('):
        if k not in a:
            e.append('功能守恒缺：' + k)
    for bad in ('verticalScroll(', 'LazyColumn', 'rememberScrollState'):
        if bad in ac:
            e.append('二级页自带滚动 → R383 闪退回潮：' + bad)
    # 旧口径只留最新
    for bad in ('两渠道分列', '左右两列分开', '两渠道左右分列'):
        if bad in st or bad in ac:
            e.append('旧「两列」口径没删净：' + bad)

    # ⑤ 挂 CI 与现行清单
    if '"r390_verify.py"' not in s['r135']:
        e.append('r135 现行清单漏登记 R390')
    if s['ci'].count('scripts/r390_verify.py') < 2:
        e.append('CI 没挂 R390 正向 + 反向两步')
    return e

def main():
    s = sources()
    if '--selftest' in sys.argv:
        cases = [
            ('App 层状态被摘', 'main',
             'var settingsPage by rememberSaveable { mutableStateOf("") }', ''),
            ('主顶栏标题回退写死设置', 'main',
             'title = if (settingsPage.isEmpty()) "设置" else settingsPageTitle(settingsPage))',
             'title = "设置")'),
            ('返回箭头直接弹回书架', 'main',
             'if (screen == Screen.SETTINGS && settingsPage.isNotEmpty()) {',
             'if (false) {'),
            ('设置页又藏一份状态', 'settings',
             'val missing = remember(refresh) { ModelStore.missing(c) }',
             'var page by rememberSaveable { mutableStateOf("") }\n    val missing = remember(refresh) { ModelStore.missing(c) }'),
            ('二级页大标题回潮', 'settings',
             'internal fun settingsPageTitle(page: String): String = when (page) {',
             '@Composable\ninternal fun SettingsSubHeaderX() {}\ninternal fun settingsPageTitle(page: String): String = when (page) {'),
            ('Shell 又写回设置标题', 'shell',
             'Screen.SETTINGS -> ChromeSpec("", canBack = true)',
             'Screen.SETTINGS -> ChromeSpec("设置", canBack = true)'),
            ('摘掉 tab 状态', 'archive',
             'var tab by remember { mutableStateOf(0) }', ''),
            ('摘掉语音网关选项卡', 'archive',
             'TabChip(Modifier.weight(1f), "语音网关"',
             'TabChip(Modifier.weight(1f), "网关"'),
            ('回潮左右并排两列', 'archive',
             'fun TabChip(', 'fun ColumnScope.Channel(\n        title: String'),
            ('说明文字藏回折叠', 'archive',
             '④ 说明降级', '④ 说明降级\n    var showHow by remember { mutableStateOf(false) }'),
            ('说明挪回列表前面', 'archive',
             '② 主区', '④ 说明降级'),
            ('二级页自带滚动', 'archive',
             'CardBox(Modifier.pointerInput(tab) {',
             'Column(Modifier.verticalScroll(rememberScrollState())) { }\n    CardBox(Modifier.pointerInput(tab) {'),
            ('摘掉一键清空确认', 'archive', '"确认清空"', '"清空"'),
        ]
        bad = 0
        for name, key, old, new in cases:
            s2 = dict(s)
            if old not in s2[key]:
                bad += 1; print('  注入锚失效', name); continue
            s2[key] = s2[key].replace(old, new, 1)
            if not check(s2):
                bad += 1; print('  漏放', name)
            else:
                print('  拦下', name)
        print('R390 反向注入 %d/%d 全拦' % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    e = check(s)
    print('R390 %s' % ('FAIL: ' + '; '.join(e) if e else
          'PASS · 唯一顶栏层级 / 整宽选项卡（点击+横滑）/ 说明降级 / 功能守恒'))
    return 1 if e else 0

if __name__ == '__main__':
    sys.exit(main())
