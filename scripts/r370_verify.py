#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R370 · 全站 UI 视觉层级统一守门。

这一轮把「每一个页面」的标题 / 分区 / 正文 / 注释尺寸收进 Theme.kt 的
Hi* 令牌：以前同一个层级在每个页面各写一个数字，差 2~3sp；现在共用
顶栏、抽屉、卡片、设置分组、任务分区、目录收尾条、多选 chip 与播放器，
全站一层层排下来，功能与逻辑一条不动。

钉住三件事：
  ① 层级令牌与统一 Typography 在，MaterialTheme 真的接上
  ② 共用外壳 / 卡片 / 分组 / chip 都改用令牌，不再各写各的数字
  ③ 主页、输入朗读、调音、模型、这本书、续做、目录、任务、设置、
     书架、播放器、描述预设都吃到令牌（每个页面至少一处）

纯静态语义判据，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r370_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'

NAMES = [
    'Theme.kt', 'AppChrome.kt', 'Shell.kt', 'SettingsUi.kt', 'TaskUi.kt',
    'MultiSelect.kt', 'ChapterHub.kt', 'InputReadUi.kt', 'MixerUi.kt',
    'LibraryUi.kt', 'ResumeUi.kt', 'PlayerSheet.kt', 'ProduceUi.kt',
    'ModelUi.kt', 'DescPresetUi.kt',
]


def sources():
    return {n: (APP / n).read_text(encoding='utf-8') for n in NAMES}


def check(s=None):
    s = s or sources()
    e = []
    t, ch, sh, set_, task, multi, hub = (
        s['Theme.kt'], s['AppChrome.kt'], s['Shell.kt'], s['SettingsUi.kt'],
        s['TaskUi.kt'], s['MultiSelect.kt'], s['ChapterHub.kt'])

    # ① 令牌与统一排版
    for k in ['internal val HiPagePad = 16.dp',
              'internal val HiCardPad = 16.dp',
              'internal val HiCardRadius = 18.dp',
              'internal val HiChipRadius = 14.dp',
              'internal val HiSectionGap = 14.dp',
              'internal val HiTitleSp = 18.sp',
              'internal val HiSectionSp = 14.sp',
              'internal val HiBodySp = 14.sp',
              'internal val HiCaptionSp = 12.sp',
              'internal val HiMicroSp = 11.sp',
              'internal const val HiHairAlpha = 0.55f',
              'private val AppTypography = Typography(']:
        if k not in t:
            e.append('层级令牌缺: ' + k[:52])
    if 'MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)' not in t:
        e.append('统一排版没接进 MaterialTheme')
    if 'bodyLarge = TextStyle(fontSize = HiBodySp)' not in t:
        e.append('正文层级没用令牌')

    # ② 共用外壳 / 卡片 / 分组 / chip
    if 'fontSize = HiTitleSp,\n                fontWeight = FontWeight.Bold' not in ch:
        e.append('顶栏标题没提到页面级')
    if 'palette.line.copy(alpha = HiHairAlpha)' not in ch:
        e.append('顶栏与内容之间的发丝线被摘')
    if 'Text("＋ 导入一本书", fontSize = HiSectionSp)' not in ch:
        e.append('抽屉导入按钮没用分区级')
    if 'RoundedCornerShape(HiCardRadius)' not in sh or \
            'Modifier.padding(HiCardPad)' not in sh:
        e.append('全站卡片容器没用层级令牌')
    # R390 · 删掉设置页第二条标题行后，HiSectionGap 少了一处；按新形态迁移，
    # 分组令牌仍必须齐全，并加一条更严的：不许再出现第二条页面级标题。
    if set_.count('padding(bottom = HiSectionGap)') < 1 or \
            'RoundedCornerShape(HiCardRadius)' not in set_ or \
            'height = 16.dp' not in set_:
        e.append('设置分组没用层级令牌')
    if 'fontSize = HiSectionSp, fontWeight = FontWeight.Bold' not in set_:
        e.append('设置分组标题没提级')
    if 'fontSize = HiTitleSp,\n                fontWeight = FontWeight.Bold' not in ch:
        e.append('主顶栏页面级标题被摘（R390 唯一落点）')
    if 'SettingsSubHeader' in set_ or \
            'fontSize = HiTitleSp, fontWeight = FontWeight.Bold' in set_:
        e.append('设置页又画了第二条页面级标题（顶栏应只留一层）')
    if 'fontSize = HiCaptionSp, fontWeight = FontWeight.Bold' not in task:
        e.append('任务分区小标题没用注释级')
    if multi.count('.clip(RoundedCornerShape(HiChipRadius))') < 2:
        e.append('动作 chip 圆角没统一到令牌')
    if 'import androidx.compose.ui.unit.sp' in multi:
        e.append('MultiSelect 又留下孤儿 sp import（r136 会红）')
    if 'Text(status, fontSize = HiCaptionSp' not in hub or \
            'Text(label, fontSize = HiSectionSp' not in hub:
        e.append('目录页收尾条状态与按钮没分层')

    # ③ 每个页面至少一处吃到令牌
    per_page = {
        'InputReadUi.kt': 'Text("输入朗读", fontSize = HiTitleSp',
        'MixerUi.kt': 'Text("调音台", fontSize = HiTitleSp',
        'LibraryUi.kt': 'Text(b.title, fontSize = HiSectionSp',
        'ResumeUi.kt': 'Text("上次没做完", fontSize = HiTitleSp',
        'PlayerSheet.kt': 'Text("播放队列 · ${list.size} 条", fontSize = HiSectionSp',
        'ProduceUi.kt': 'fontSize = HiSectionSp, color = Sub',
        'ModelUi.kt': 'fontSize = HiMicroSp, color = Sub',
        'DescPresetUi.kt': 'fontSize = HiSectionSp)',
    }
    for name, needle in per_page.items():
        if needle not in s[name]:
            e.append('页面没吃到层级令牌: ' + name)

    # 守恒：视觉改动不许顺手摘功能
    for name, needle in [
        ('AppChrome.kt', 'internal fun AppTopBar('),
        ('AppChrome.kt', 'internal fun AppDrawerSheet('),
        ('Shell.kt', 'internal fun CardBox('),
        ('SettingsUi.kt', 'internal fun SettingsSection('),
        ('TaskUi.kt', 'internal fun SectionLabel('),
        ('MultiSelect.kt', 'internal fun ActionChip('),
        ('ChapterHub.kt', 'private fun MergeChapterFooter('),
    ]:
        if needle not in s[name]:
            e.append('守恒：共用组件被摘 ' + needle[:44])
    return e


def selftest():
    base = sources()
    assert not check(base), '基线自己就红了，先修基线'
    INJ = [
        ('标题令牌被摘', 'Theme.kt', 'internal val HiTitleSp = 18.sp', ''),
        ('圆角令牌被摘', 'Theme.kt', 'internal val HiCardRadius = 18.dp', ''),
        ('发丝线透明度被摘', 'Theme.kt', 'internal const val HiHairAlpha = 0.55f', ''),
        ('统一排版被摘', 'Theme.kt', 'private val AppTypography = Typography(', ''),
        ('排版没接 MaterialTheme', 'Theme.kt',
         'MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)',
         'MaterialTheme(colorScheme = scheme, content = content)'),
        ('正文层级回硬编码', 'Theme.kt',
         'bodyLarge = TextStyle(fontSize = HiBodySp)', 'bodyLarge = TextStyle(fontSize = 14.sp)'),
        ('顶栏标题回 16sp', 'AppChrome.kt',
         'fontSize = HiTitleSp,\n                fontWeight = FontWeight.Bold',
         'fontSize = 16.sp,\n                fontWeight = FontWeight.SemiBold'),
        ('顶栏发丝线被摘', 'AppChrome.kt', 'palette.line.copy(alpha = HiHairAlpha)', ''),
        ('抽屉导入回 13sp', 'AppChrome.kt',
         'Text("＋ 导入一本书", fontSize = HiSectionSp)',
         'Text("＋ 导入一本书", fontSize = 13.sp)'),
        ('卡片圆角回 20dp', 'Shell.kt', 'RoundedCornerShape(HiCardRadius)',
         'RoundedCornerShape(20.dp)'),
        ('卡片内距回 18dp', 'Shell.kt', 'Modifier.padding(HiCardPad)',
         'Modifier.padding(18.dp)'),
        ('设置分组锚条回 15dp', 'SettingsUi.kt', 'height = 16.dp', 'height = 15.dp'),
        ('设置分组间距回 12dp', 'SettingsUi.kt', 'padding(bottom = HiSectionGap)',
         'padding(bottom = 12.dp)'),
        ('任务分区回 11sp', 'TaskUi.kt',
         'fontSize = HiCaptionSp, fontWeight = FontWeight.Bold',
         'fontSize = 11.sp, fontWeight = FontWeight.Bold'),
        ('chip 圆角回 12dp', 'MultiSelect.kt',
         '.clip(RoundedCornerShape(HiChipRadius))', '.clip(RoundedCornerShape(12.dp))'),
        ('孤儿 sp import 回潮', 'MultiSelect.kt',
         'import androidx.compose.ui.unit.dp',
         'import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp'),
        ('目录收尾条不分层', 'ChapterHub.kt',
         'Text(status, fontSize = HiCaptionSp', 'Text(status, fontSize = 12.sp'),
        ('输入朗读标题回 16sp', 'InputReadUi.kt',
         'Text("输入朗读", fontSize = HiTitleSp', 'Text("输入朗读", fontSize = 16.sp'),
        ('调音台标题回 15sp', 'MixerUi.kt',
         'Text("调音台", fontSize = HiTitleSp', 'Text("调音台", fontSize = 15.sp'),
        ('书架书名回 15sp', 'LibraryUi.kt',
         'Text(b.title, fontSize = HiSectionSp', 'Text(b.title, fontSize = 15.sp'),
        ('续做标题回 16sp', 'ResumeUi.kt',
         'Text("上次没做完", fontSize = HiTitleSp', 'Text("上次没做完", fontSize = 16.sp'),
        ('播放器队列回 13sp', 'PlayerSheet.kt',
         'Text("播放队列 · ${list.size} 条", fontSize = HiSectionSp',
         'Text("播放队列 · ${list.size} 条", fontSize = 13.sp'),
        ('这本书页回硬编码', 'ProduceUi.kt', 'fontSize = HiSectionSp, color = Sub',
         'fontSize = 13.sp, color = Sub'),
        ('模型页回 11sp', 'ModelUi.kt', 'fontSize = HiMicroSp, color = Sub',
         'fontSize = 11.sp, color = Sub'),
        ('预设弹窗回 14sp', 'DescPresetUi.kt', 'fontSize = HiSectionSp)',
         'fontSize = 14.sp)'),
    ]
    bad = []
    for name, key, old, new in INJ:
        s = dict(base)
        s[key] = s[key].replace(old, new, 1)
        if s[key] == base[key]:
            bad.append(name)
            print('  注入没生效:', name)
            continue
        if not check(s):
            bad.append(name)
            print('  反向注入漏放:', name)
        else:
            print('  反向注入 拦下 ·', name)
    print('反向注入 %d/%d 全拦下 · PASS' % (len(INJ) - len(bad), len(INJ)))
    return 1 if bad else 0


def main():
    if '--selftest' in sys.argv:
        return selftest()
    e = check()
    if e:
        print('R370 FAIL:')
        for x in e:
            print(' -', x)
        return 1
    print('R370 正向：全站层级令牌 / 每个页面 / 守恒 PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
