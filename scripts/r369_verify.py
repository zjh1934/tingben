#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R369 · 合成链二次梳理守门（先收集问题、再读再思考、再手改代码）。

钉住本轮四处修复，防回潮：
  1) 点停止后拼接/转码还要跑四分钟 → toM4a / encodeChapter 可中断，
     且只自动路径带 stopCheck，手动「拼接并转码」「转 M4A」不受影响
  2) 自动路径「register → markDone → 才查登记结果」状态分家 → 登记成功才写章状态
  3) 自动路径 kept 恒返回总份数 → 与手动路径同口径，返回删失败数
  4) filesOf 回退按全桶扫描（多分桶混音）→ 收进单一分桶

纯静态语义判据，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r369_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'


def sources():
    return {
        'audioops': (APP / 'core/AudioOps.kt').read_text(encoding='utf-8'),
        'hub': (APP / 'ChapterHub.kt').read_text(encoding='utf-8'),
    }


def body(text, signature):
    """取一个函数体，避免命中同名的手动/自动两条路径。"""
    i = text.find(signature)
    if i < 0:
        return ''
    j = text.find('\n    }', i)
    return text[i:j] if j >= 0 else text[i:]


def check(s=None):
    s = s or sources()
    a, h = s['audioops'], s['hub']
    e = []
    tm = body(a, 'fun toM4a(')
    ec = body(a, 'private fun encodeChapter(')
    auto = body(a, 'fun mergeChapterAuto(')
    manual = body(a, 'fun mergeChapter(c: Context,')
    fo = body(h, 'fun filesOf(')

    # ① 转码可中断，且默认关闭（不误伤手动转 M4A）
    if 'stopCheck: (() -> Boolean)? = null' not in tm:
        e.append('toM4a 没有默认关闭的停止回调')
    if 'while (!outputDone && !stopped && guard++ < 2000000)' not in tm:
        e.append('转码循环没有停止条件')
    if 'if (stopCheck?.invoke() == true) { stopped = true; break }' not in tm:
        e.append('转码循环里没有停止检查')
    if 'if (stopped || !started || !inputDone || !outputDone || !validateM4a(out))' not in tm:
        e.append('中断后没走半成品删除出口')
    if '已停止，转码中断，内部 WAV 留着' not in tm:
        e.append('中断原因没说清，会被当成普通转码失败')

    # ② 拼接进入前也认一次停止；透传给转码；手动路径不带
    if 'stopCheck: (() -> Boolean)? = null' not in ec:
        e.append('encodeChapter 没有默认关闭的停止回调')
    if 'if (stopCheck?.invoke() == true) return null' not in ec:
        e.append('进入拼接前没认停止，仍会白跑一份四分钟')
    if 'toM4a(c, wav, m4a, pulseCheck)' not in ec:
        e.append('拼接没把停止判定透传给转码')
    if 'stopCheck = { Producer.isStoppingBook(bookId) }' not in auto:
        e.append('自动章拼接没传停止判定')
    if 'stopCheck' in manual:
        e.append('手动「拼接并转码」被停止判定误拦')

    # ③ 登记成功才写章状态；kept 与手动同口径
    i_reg = auto.find('if (!AudioLibrary.lastRegisterOk)')
    i_done = auto.find('AudioTree.markDone')
    if i_reg < 0 or i_done < 0 or i_reg > i_done:
        e.append('自动路径又把章状态写在登记结果检查之前')
    if 'return Result(true, enc.file, kept = kept)' not in auto:
        e.append('自动路径 kept 又写死总份数')
    if 'AudioLibrary.register(' not in auto or 'AudioLibrary.register(' not in manual:
        e.append('两条拼章路径的成品登记被摘')
    if a.count('Prefs.autoDeleteSegsAfterMerge(c)') < 2:
        e.append('R361 删小块开关没在两条路径都真读')

    # ④ 回退只认一个分桶
    if 'val oneKey = ownDir?.absolutePath' not in fo:
        e.append('filesOf 没按当前任务分桶收窄')
    if 'Workbench.workDir(c, t)' not in fo:
        e.append('filesOf 没取当前任务的工作目录')
    if 'candidates.filter { f -> oneKey != null && f.parentFile?.absolutePath == oneKey }' not in fo:
        e.append('filesOf 回退没有单桶过滤')
    if 'ch?.segments?.map { it.file }' in fo:
        e.append('filesOf 旧的全桶回退回潮（会混进两套声音）')
    return e


def selftest():
    base = sources()
    assert not check(base), '基线自己就红了，先修基线'
    INJ = [
        ('toM4a 默认回调被摘', 'audioops', 'stopCheck: (() -> Boolean)? = null', ''),
        ('转码循环停止条件被摘', 'audioops',
         'while (!outputDone && !stopped && guard++ < 2000000)',
         'while (!outputDone && guard++ < 2000000)'),
        ('转码循环里不查停止', 'audioops',
         'if (stopCheck?.invoke() == true) { stopped = true; break }', ''),
        ('中断后不删半成品', 'audioops',
         'if (stopped || !started || !inputDone || !outputDone || !validateM4a(out))',
         'if (!started || !inputDone || !outputDone || !validateM4a(out))'),
        ('中断原因被抹', 'audioops', '已停止，转码中断，内部 WAV 留着', '转码没完成，原文件留着'),
        ('拼接前不认停止', 'audioops', 'if (stopCheck?.invoke() == true) return null', ''),
        ('拼接不透传停止判定', 'audioops', 'toM4a(c, wav, m4a, pulseCheck)', 'toM4a(c, wav, m4a)'),
        ('自动路径不传停止判定', 'audioops',
         'stopCheck = { Producer.isStoppingBook(bookId) }', 'stopCheck = null'),
        ('手动拼章被误拦', 'audioops', 'fun mergeChapter(c: Context,',
         'fun mergeChapter(c: Context,'),
        ('@auto:登记结果检查被摘', 'audioops',
         'if (!AudioLibrary.lastRegisterOk)', 'if (false)'),
        ('@auto:kept 又写死份数', 'audioops',
         'return Result(true, enc.file, kept = kept)',
         'return Result(true, enc.file, kept = parts.size)'),
        ('@auto:两条路径少读开关', 'audioops', 'Prefs.autoDeleteSegsAfterMerge(c)', 'true'),
        ('filesOf 不取当前分桶', 'hub', 'val oneKey = ownDir?.absolutePath', 'val oneKey = ""'),
        ('filesOf 不取任务目录', 'hub', 'val ownDir = Workbench.workDir(c, t)', 'val ownDir = null'),
        ('单桶过滤被摘', 'hub',
         'candidates.filter { f -> oneKey != null && f.parentFile?.absolutePath == oneKey }',
         'candidates'),
        ('filesOf 全桶回退回潮', 'hub',
         'candidates.filter { f -> oneKey != null && f.parentFile?.absolutePath == oneKey }',
         'ch?.segments?.map { it.file }?.filter { f -> f.isFile && f.length() > 44L } ?: emptyList()'),
    ]
    bad = []
    for name, key, old, new in INJ:
        s = dict(base)
        if name.startswith('@auto:'):
            i = s[key].find('fun mergeChapterAuto(')
            j = s[key].find('\n    }', i)
            seg = s[key][i:j]
            if old not in seg:
                bad.append(name + '(注入未命中)')
                print('  注入没生效:', name)
                continue
            s[key] = s[key][:i] + seg.replace(old, new, 1) + s[key][j:]
        elif name == '手动拼章被误拦':
            i = s[key].find('fun mergeChapter(c: Context,')
            j = s[key].find('\n    }', i)
            s[key] = s[key][:j] + '\n        stopCheck = { Producer.isStoppingBook(bookId) }' + s[key][j:]
        else:
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
        print('R369 FAIL:')
        for x in e:
            print(' -', x)
        return 1
    print('R369 正向 19 条：PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
