#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/com/tingben/app'
QUEUE = (APP / 'core/PlayQueue.kt').read_text(encoding='utf-8')
AUDIO = (APP / 'core/AudioLibrary.kt').read_text(encoding='utf-8')
R66 = (ROOT / 'scripts/r66_verify.py').read_text(encoding='utf-8')


def check(queue=QUEUE, audio=AUDIO, r66=R66):
    e = []

    # R140 · 工程现在有两条播放通道（主播放 / 独立试听），全文件级正则会先
    # 撞上试听通道的同名结构。先圈定「主 open」函数体，再在里面做原来的判定。
    om = re.search(r'private fun open\(i: Int, overrideStartMs: Long\? = null\) \{([\s\S]*?)\n    \}', queue)
    main_open = om.group(1) if om else ""
    if not main_open:
        e.append('F-29 主播放 open 函数不存在')
    m = re.search(r'if \(!t\.file\.isFile\) \{([\s\S]*?)\n        \}', main_open)
    if not m:
        e.append('F-29 缺文件分支不存在')
    else:
        b = m.group(1)
        if not re.search(r'open\(i \+ 1\)\s*;?\s*return', b):
            e.append('F-29 跳下一首后没返回')
        if b.count('return') < 2 or not ('finish()' in b or 'ended = true' in b):
            e.append('F-29 最后一首没写终态并返回')
        fm = re.search(r'private fun finish\(\) \{([\s\S]*?)\n    \}', queue)
        if not fm or 'ended = true' not in fm.group(1) or 'abandonFocus()' not in fm.group(1):
            e.append('F-29 终态函数没收敛状态')
        em = re.search(r'setOnErrorListener \{[\s\S]*?\n                \}', main_open)
        if not em or 'else finish()' not in em.group(0):
            e.append('F-29 最后一首出错没收敛状态')
        if re.search(r'if \(i \+ 1 < tracks\.size\) open\(i \+ 1\)(?!\s*;)', b):
            e.append('F-29 仍是旧的单语句递归')

    p = re.search(r'fun pause\(\) \{([\s\S]*?)\n    \}', queue)
    if not p or 'abandonFocus()' not in p.group(1):
        e.append('F-30 暂停没归还音频焦点')

    rm = re.search(r'fun remove\(c: Context, id: String\): Boolean \{([\s\S]*?)\n    \}', audio)
    if not rm:
        e.append('F-31 remove 不存在')
    else:
        b = rm.group(1)
        if 'File(dir(c), "$id.wav")' not in b:
            e.append('F-31 兜底条目没按磁盘文件定位')
        # R260 · remove 现在只定位文件，删失败/移记录都收敛在 removeAt 里。
        # 判据跟过去，仍按语义判，不放宽。
        ra = re.search(r'private fun removeAt\(c: Context, all: List<AudioItem>, f: File\): Boolean \{([\s\S]*?)\n    \}', audio)
        if not ra:
            e.append('F-31 removeAt 不存在')
        else:
            rb = ra.group(1)
            if not re.search(r'!\w+\.delete\(\)[\s\S]{0,160}return false', rb):
                e.append('F-31 删失败没保留记录并返回失败')
            if 'all.filterNot { it.file.absolutePath == f.absolutePath }' not in rb:
                e.append('F-31 成功路径没按路径移除索引条目')

    if ('TaskResume.byBook(c, bookId)?.modeId' not in audio and
            'val madeMode = TaskResume.load(c)?.modeId' not in audio):
        e.append('F-32 成品模式没取本次任务模式')
    if 'mode = madeMode' not in audio:
        e.append('F-32 成品模式标签没用计算值')
    if 'mode = "直接朗读"' in audio:
        e.append('F-32 成品模式标签仍写死旧档位')
    if 'mode = "直接朗读"' in r66 or 'mode = madeMode' not in r66:
        e.append('F-32 r66 老断言没解绑成语义判据')
    return e


def _drop_abandon_in_pause(t: str) -> str:
    """只删 pause() 函数体里的 abandonFocus()，不绑它前面的行（R180）。"""
    i = t.find('    fun pause() {')
    if i < 0:
        return t
    j = t.find('\n    }', i)
    if j < 0:
        return t
    return t[:i] + t[i:j].replace('        abandonFocus()\n', '') + t[j:]


def main():
    errs = check()
    if errs:
        print('R116 FAIL:')
        for x in errs:
            print(' -', x)
        return 1
    print('R116 正向 4 条：PASS')

    INJ = [
        ('缺文件分支丢 return', lambda: dict(queue=QUEUE.replace(
            '{ open(i + 1); return }', '{ open(i + 1) }'))),
        # R180 · pause() 开头多了 savePos()，原来绑死「fun pause() { 紧跟
        # abandonFocus()」的替换串不再命中，注入静默失效反而报「没拦下」。
        # 改成只在 pause 函数体里删掉归还焦点那一行，不依赖它前面有什么。
        ('暂停不归还焦点', lambda: dict(queue=_drop_abandon_in_pause(QUEUE))),
        ('删失败仍移除索引', lambda: dict(audio=AUDIO.replace(
            'if (f.isFile && !f.delete()) {', 'if (false) {'))),
        ('成品模式写回旧档位', lambda: dict(audio=AUDIO.replace(
            'mode = madeMode,', 'mode = "直接朗读",'))),
        ('r66 老断言回潮', lambda: dict(r66=R66.replace(
            "if 'mode = madeMode' not in audio: e.append('成品模式没按本次模式登记')",
            'if \'mode = "直接朗读"\' not in audio: e.append(\'成品档位没固定\')'))),
    ]
    bad = []
    for name, fn in INJ:
        e = check(**fn())
        if e:
            print('  反向注入 拦下 ·', name)
        else:
            bad.append(name)
    if bad:
        print('R116 反向注入未拦下:', bad)
        return 1
    print('R116 反向注入 %d/%d 全拦下 · PASS' % (len(INJ), len(INJ)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
