#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R368 · 音频合成链真机日志复盘守门。

真机 1.1.45 诊断摘要里的四件事，都在这里钉住，防止回潮：
  1) 点停止后转码仍把章固化、清掉 55 个中间小块
  2) FGS_FINISH done=6/656（把 wavs.size 当已完成段数）
  3) HUB_LISTEN 后 PLAY_OPEN_ERR what=-38（坏文件交给 MediaPlayer）
  4) 制作中整章重做删成品、一次标 55 段，与主循环抢目录
附带：续跑逐段回放 OVERLAY_PCT、音色套「认领 N 段」误导口径。

纯静态语义判据，沙盒无 Android SDK，静态绿 != 真编译绿。
用法：python3 scripts/r368_verify.py [--selftest]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'


def sources():
    return {
        'producer': (APP / 'core/Producer.kt').read_text(encoding='utf-8'),
        'audioops': (APP / 'core/AudioOps.kt').read_text(encoding='utf-8'),
        'hub': (APP / 'ChapterHub.kt').read_text(encoding='utf-8'),
        'queue': (APP / 'core/PlayQueue.kt').read_text(encoding='utf-8'),
    }


def body(text, signature):
    """取一个顶层函数的函数体，避免命中同名结构（如手动/自动两条拼章路径）。"""
    i = text.find(signature)
    if i < 0:
        return ''
    j = text.find('\n    }', i)
    return text[i:j] if j >= 0 else text[i:]


def check(s=None):
    s = s or sources()
    p, a, h, q = s['producer'], s['audioops'], s['hub'], s['queue']
    e = []

    # ① 停止收尾真值：一律以 emit 维护的累计段数为准，不认调用方传的 wavs.size
    hb = body(p, 'private fun halted(')
    if 'val done = _state.value.done.coerceIn' not in hb:
        e.append('停止收尾又把文件个数当已完成段数')
    if 'fun isStoppingBook(bookId: String): Boolean' not in p or \
            'cancelRequested && current?.bookId == bookId' not in p:
        e.append('停止标志没精确到当前这本书')

    # ② 续跑开局一次到位，跳过段不再逐条回放进度
    if 'val restoredDone = (toSpeak.size - pending).coerceIn' not in p or \
            'emit("正在生成声音", restoredDone' not in p:
        e.append('续跑没把进度开局设到真实已完成段数')
    if 'if (!skip) emit("正在生成声音", i + 1, annotated.size,' not in p:
        e.append('跳过已有段仍逐段 emit（OVERLAY_PCT 噪声回潮）')
    if 'maxOf(_state.value.done, i + 1)' not in p:
        e.append('手工删除段会把累计进度倒退回段号')
    if 'Progress(false, "没完成", _state.value.done, annotated.size,' not in p:
        e.append('空间不足收尾仍用文件个数')
    if 'sw.archived > 0 || sw.restored > 0' in p or '散段认领' not in p:
        e.append('音色套日志仍把散段份数写成总进度或整条省略')

    # ③ 自动章边界：转码途中点停止，不登记、不删小块；手动路径不受影响
    auto = body(a, 'fun mergeChapterAuto(')
    manual = body(a, 'fun mergeChapter(c: Context,')
    if 'if (Producer.isStoppingBook(bookId)) {' not in auto or \
            'CHAPTER_MERGE_HOLD' not in auto:
        e.append('自动章边界没有登记前停止守卫')
    # R369 · 拼接/转码途中也要能被打断，否则点停止仍要等四分钟才收尾
    if 'stopCheck = { Producer.isStoppingBook(bookId) }' not in auto:
        e.append('自动章拼接没把停止判定透传给拼接转码')
    if 'enc.file.delete()' not in auto or 'enc.wav.delete()' not in auto:
        e.append('停止时未登记成品没清掉，会与 chapter_done 分家')
    if 'Producer.isStoppingBook' in manual:
        e.append('停止守卫误拦了用户手动「拼接并转码」')

    # ④ 目录页与播放入口
    if 'if (Producer.isBusyForBook(book.id)) return@LaunchedEffect' not in h:
        e.append('收尾窗口仍会触发目录页回溯补拼')
    if 'ch?.merged?.takeIf { it.isFile && it.length() > 44L }' not in h or \
            'if (segs.isEmpty()) return null' not in h:
        e.append('章行试听仍会拿坏文件 / 空列表去拼临时文件')
    if '?.takeIf { it.file.isFile && it.file.length() > 44L }' not in h:
        e.append('章行试听没先验成品完整头')
    if 'if (busyHere && AudioLibrary.hasChapterAudio(c, book.id, ch))' not in h:
        e.append('制作中仍能整章重做已出成品的章')
    if 'if (!t.file.isFile || t.file.length() <= 44L)' not in q:
        e.append('播放入口没拦 0 字节 / 未写完文件（-38 回潮）')
    return e


def selftest():
    base = sources()
    assert not check(base), '基线自己就红了，先修基线'
    INJ = [
        ('收尾退回 wavs.size', 'producer', 'val done = _state.value.done.coerceIn', ''),
        ('按书停止判定被摘', 'producer', 'fun isStoppingBook(bookId: String): Boolean', 'fun isStoppingBookX(bookId: String): Boolean'),
        ('开局恢复 emit 回潮', 'producer', 'emit("正在生成声音", restoredDone', 'emit("正在生成声音", 0'),
        ('跳过段又逐段刷进度', 'producer', 'if (!skip) emit("正在生成声音", i + 1, annotated.size,', 'emit("正在生成声音", i + 1, annotated.size,'),
        ('删除段进度倒退', 'producer', 'maxOf(_state.value.done, i + 1)', 'i + 1'),
        ('空间收尾用文件数', 'producer', 'Progress(false, "没完成", _state.value.done, annotated.size,', 'Progress(false, "没完成", wavs.size, annotated.size,'),
        ('音色套日志回旧口径', 'producer', '散段认领', '认领'),
        ('自动拼章登记前守卫被摘', 'audioops', 'if (Producer.isStoppingBook(bookId)) {', 'if (false) {'),
        ('自动拼接转码不可中断', 'audioops', 'stopCheck = { Producer.isStoppingBook(bookId) }', 'stopCheck = null'),
        ('未登记成品留着', 'audioops', 'runCatching { if (enc.converted) enc.file.delete() }', ''),
        ('手动拼章被误拦', 'audioops', 'fun mergeChapter(c: Context,', 'fun mergeChapter(c: Context,'),
        ('收尾窗口回溯补拼', 'hub', 'if (Producer.isBusyForBook(book.id)) return@LaunchedEffect', ''),
        ('坏成品又只判 isFile', 'hub', 'ch?.merged?.takeIf { it.isFile && it.length() > 44L }', 'ch?.merged?.takeIf { it.isFile }'),
        ('空源又去拼临时文件', 'hub', 'if (segs.isEmpty()) return null', ''),
        ('章行试听不验完整头', 'hub', '?.takeIf { it.file.isFile && it.file.length() > 44L }', ''),
        ('制作中整章重做', 'hub', 'if (busyHere && AudioLibrary.hasChapterAudio(c, book.id, ch))', 'if (false)'),
        ('播放入口退回只判 isFile', 'queue', 'if (!t.file.isFile || t.file.length() <= 44L)', 'if (!t.file.isFile)'),
    ]
    # 「手动拼章被误拦」是特殊注入：往手动函数体里塞停止标志
    bad = []
    for name, key, old, new in INJ:
        s = dict(base)
        if name == '手动拼章被误拦':
            i = s[key].find('fun mergeChapter(c: Context,')
            j = s[key].find('\n    }', i)
            s[key] = s[key][:j] + '\n        Producer.isStoppingBook(bookId)' + s[key][j:]
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
        print('R368 FAIL:')
        for x in e:
            print(' -', x)
        return 1
    print('R368 正向 15 条：PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
