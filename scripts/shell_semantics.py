#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R162 · 导航壳语义判据（唯一真源）。

历史脚本把「抽屉 6 项 / DRAWER_TOP / NAV_MIXER to 调音台」当守恒锚点写死，
结果导航壳一改就集体假红。这里把判据收敛成「能力还在不在」：

  · 主页 = 输入框
  · 左边抽屉 = 我导进去的书（不再是功能菜单）
  · 右上角（仅首页）= ＋ 导入 / 齿轮设置；顶栏不放品牌名（R164）
  · 模型收进设置、调音台在合成前弹层、任务能力在书里第二列与开软件弹窗
  · 7 个 NAV_ id 只留给旧状态恢复兼容，界面一处都不渲染

各脚本 import 这里的 anchors() / checks() / injections()，不再各自绑字面量。
"""
from __future__ import annotations
import re

# 旧功能菜单的任何一项回到界面上都算回潮。
OLD_MENU_RE = re.compile(r'NAV_\w+\s+to\s+"')

# 新壳层的通用正向锚点：key 是文件名类别，value 是要出现的符号。
ANCHORS = {
    "chrome": [
        "internal fun AppDrawerSheet(",
        "internal fun AppTopBar(",
        "private fun GearIcon(",
        "showSettings: Boolean = false",
        "onSettings: () -> Unit",
        "if (spec.showSettings) {",
        "internal fun RefreshStrip(",
        "if (acc > 55f) onRefresh()",
    ],
    "main": [
        "DismissibleNavigationDrawer(",
        "AppDrawerSheet(",
        'title = "我的书"',
        "onSettings = {",
        "TOP_SETTINGS",
        "compact = true",
        "asHome = true",
        'Screen.LIBRARY  -> ChromeSpec("",',
        "chapterMixerOpen = true",
        # R165 · 第三份真机日志实证的五处修正
        "ChromeSpec(\"模型\", canBack = true)",
        "NotifPerm.granted(c)",
        "containerColor = if (granted) Good else Primary",
        "containerColor = if (notifOk) Good else Primary",
        "modifier = Modifier.weight(1f).height(38.dp)",
        "shape = RoundedCornerShape(12.dp)",
        "listOf(src.speaker, Prefs.speakerName(c))",
        "\"MIXER_TRIAL_EMPTY\"",
        "EventLog.Kind.ENGINE, \"MIXER_TRIAL_FAIL\"",
    ],
}

# R165 · 撤销的形态与误标级别，不许回潮。
BANNED = {
    "main": [
        'Text("去书架 ›"',
        'EventLog.Kind.CRASH, "MIXER_TRIAL_FAIL"',
    ],
}


def old_menu_items(chrome: str) -> list:
    """界面里重新出现功能菜单项（NAV_X to "标签"）的列表，正常应为空。"""
    return OLD_MENU_RE.findall(chrome)


def nav_ids_present(chrome: str) -> bool:
    """7 个 NAV_ 常量留着给旧状态恢复与历史索引兼容，不许整批删掉。"""
    return len(re.findall(r"internal const val NAV_\w+ = \d", chrome)) == 7


def drawer_is_booklist(main: str, chrome: str) -> bool:
    return ('title = "我的书"' in main and "compact = true" in main
            and "internal fun AppDrawerSheet(" in chrome
            and not old_menu_items(chrome))


def home_is_input(main: str) -> bool:
    # R180 · 原来用 `[^)]*` 扫到 asHome=true，但 R180 的首页分支里
    # onAdded 回调新增了 nav.push(...) 的右括号，正则被提前截断 → 集体假红。
    # 改成「先定位首页分支，再在它后面一小段里找 asHome=true」，
    # 参数里有多少括号都不影响，注入把 InputReadScreen 换掉仍会被拦下。
    m = re.search(r"Screen\.LIBRARY\s*->\s*InputReadScreen\(", main)
    if not m:
        return False
    return "asHome = true" in main[m.start():m.start() + 900]


def topbar_has_settings(main: str, chrome: str) -> bool:
    return "onSettings = {" in main and "GearIcon(" in chrome


def topbar_scope(main: str) -> list:
    """R164 · 顶栏品牌名零回潮；齿轮 / ＋ 只允许出现在 LIBRARY 分支。"""
    m = re.search(r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}",
                  main, re.S)
    if not m:
        return ["找不到 chromeOf 顶栏规格表"]
    body, e = m.group(1), []
    if 'ChromeSpec("听本"' in body:
        e.append("顶栏品牌名「听本」回潮")
    branches = re.findall(
        r"Screen\.([A-Z_]+)\s*->\s*ChromeSpec\((.*?)\)\s*(?=\n\s*Screen\.|\Z)", body, re.S)
    for name, args in branches:
        if name != "LIBRARY" and ("showSettings = true" in args or "showImport = true" in args):
            e.append("二级页 %s 又挂了齿轮 / ＋" % name)
    lib = [a for n, a in branches if n == "LIBRARY"]
    if not lib or "showSettings = true" not in lib[0] or "showImport = true" not in lib[0]:
        e.append("首页丢了齿轮 / ＋")
    return e


def mixer_is_overlay(main: str) -> bool:
    """调音台主路径是合成前弹层，不再 push 独立页。"""
    if "MixerDialog(" not in main or "chapterMixerOpen" not in main:
        return False
    hits = [m.group(1) for m in
            re.finditer(r"onMixer\s*=\s*\{([^}]*)\}", main, re.S)]
    return any("chapterMixerOpen" in h for h in hits)


def models_in_settings(main: str) -> bool:
    return bool(re.search(r"Screen\.SETTINGS\s*->\s*SettingsScreen\([^)]*onModels", main, re.S))


def checks(main: str, chrome: str) -> list:
    """一次性核完新壳层语义，返回错误列表（空即通过）。"""
    e = []
    for k, needles in ANCHORS.items():
        src = main if k == "main" else chrome
        for s in needles:
            if s not in src:
                e.append("新壳层缺符号[%s]: %s" % (k, s[:52]))
    for k, needles in BANNED.items():
        src = main if k == "main" else chrome
        for s in needles:
            if s in src:
                e.append("R165 撤销形态回潮[%s]: %s" % (k, s[:52]))
    if "MIXER_TRIAL_EMPTY" in main and "speaker=${v.speaker" not in main:
        e.append("试听空结果没记 speaker 状态，下次日志还是定性不了")
    if old_menu_items(chrome):
        e.append("抽屉功能菜单回潮: %d 项" % len(old_menu_items(chrome)))
    if not nav_ids_present(chrome):
        e.append("NAV_ 兼容常量被删，旧状态恢复会失效")
    if not drawer_is_booklist(main, chrome):
        e.append("抽屉不再是书列表")
    if not home_is_input(main):
        e.append("主页不再是输入框")
    if not topbar_has_settings(main, chrome):
        e.append("右上角齿轮设置丢失")
    e.extend(topbar_scope(main))
    if not mixer_is_overlay(main):
        e.append("调音台没做成合成前弹层")
    if not models_in_settings(main):
        e.append("模型没收进设置页")
    return e


def injections():
    """供各脚本复用的反向注入：[(名字, 改哪个文件, 替换函数)]。"""
    return [
        ("旧功能菜单回潮", "chrome",
         lambda t: t.replace('internal const val NAV_INPUT = 6',
                             'internal const val NAV_INPUT = 6\n'
                             'private val DRAWER_TOP = listOf(\n'
                             '    NAV_MIXER to "调音台",\n)')),
        ("主页变回书架", "main",
         lambda t: t.replace('Screen.LIBRARY -> InputReadScreen(ctx,',
                             'Screen.LIBRARY -> LibraryScreen(ctx,')),
        ("齿轮消失", "chrome",
         lambda t: t.replace('        if (spec.showSettings) {', '        if (false) {')),
        ("品牌名回潮", "main",
         lambda t: t.replace('Screen.LIBRARY  -> ChromeSpec("", TAB_LIBRARY,',
                             'Screen.LIBRARY  -> ChromeSpec("听本", TAB_LIBRARY,')),
        ("二级页齿轮回潮", "main",
         lambda t: t.replace('Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY, canBack = true)',
                             'Screen.CHAPTERS -> ChromeSpec("目录", TAB_LIBRARY, '
                             'showSettings = true, canBack = true)')),
        ("模型页去书架按钮回潮", "main",
         lambda t: t.replace('            Row(verticalAlignment = Alignment.CenterVertically) {\n'
                             '                Spacer(Modifier.weight(1f))',
                             '            Row(verticalAlignment = Alignment.CenterVertically) {\n'
                             '                TextButton(onClick = onLibrary) {\n'
                             '                    Text("去书架 ›", fontSize = 12.sp, color = Primary)\n'
                             '                }\n'
                             '                Spacer(Modifier.weight(1f))')),
        ("试听失败又记成崩溃", "main",
         lambda t: t.replace('EventLog.Kind.ENGINE, "MIXER_TRIAL_FAIL"',
                             'EventLog.Kind.CRASH, "MIXER_TRIAL_FAIL"')),
        ("预设音色试听丢 speaker 兜底", "main",
         lambda t: t.replace('listOf(src.speaker, Prefs.speakerName(c))',
                             'listOf(src.speaker)')),
        ("抽屉丢了书列表", "main",
         lambda t: t.replace('                    compact = true,', '                    compact = false,')),
        ("调音台退回独立页", "main",
         lambda t: re.sub(r"onMixer\s*=\s*\{[^}]*\}",
                          "onMixer = { nav.push(Screen.MIXER) }", t)),
    ]
