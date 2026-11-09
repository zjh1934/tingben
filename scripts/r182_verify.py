#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R182 · 自己找的第二批核心活（发完 1.1.8 后接着干）。

1 断点续听只补了「暂停/拖动/切曲/释放」四个落盘点，听一半被系统回收、
   直接划掉进程就全丢。tick() 里按 20 秒节流补写，磁盘开销可忽略。
2 来电 / 导航播报短暂抢焦点后不续播：原来三种 loss 一律 pause()，
   pause() 会放弃焦点请求，系统的 AUDIOFOCUS_GAIN 再也不会回调回来。
   改成 transient 暂停但保留焦点，GAIN 时自动 resume；永久 LOSS 仍彻底暂停。
3 播放器选章时 bookId 为空会拼出 "chapter:" 这个废队列 id，断点存进去
   永远读不回来；优先沿用当前队列 id。
4 仓库外 rc_push.py --status 只认本候选 run（本文件可选校验，CI 无此文件跳过）。

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r182_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "queue": APP / "core/PlayQueue.kt",
    "player": APP / "PlayerSheet.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}
TOOL = pathlib.Path("/data/user_persistent_data/听本/rc_push.py")   # 仓库外，可选

QUEUE = [
    '@Volatile private var pausedByTransient = false',
    'private fun pauseKeepFocus()',
    'AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,',
    'AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {',
    'pauseKeepFocus()          // 故意不 abandonFocus，才能收到 GAIN',
    'AudioManager.AUDIOFOCUS_GAIN -> {',
    'pausedByTransient = false\n                    resume()',
    'AudioManager.AUDIOFOCUS_LOSS -> {\n                pausedByTransient = false\n                pause()\n            }',
    'private val POS_PERSIST_INTERVAL = 20_000L',
    'if (_state.value.isPlaying) savePos()',
    'fun setTracks(list: List<Track>, id: String = "") {\n        if (isPreview(id)) { previewSetTracks(list, id); return }\n        lastPosPersistMs = 0L',
    'fun release() {\n        pausedByTransient = false',
]
# 必须仍在：手动暂停照旧归还焦点（r116 老契约不破）
QUEUE_KEEP = ['    fun pause() {\n        savePos()\n        abandonFocus()']
PLAYER = [
    'st.queueId.startsWith("chapter:") &&',
    'st.queueId.length > 8) st.queueId',
]
TOOL_CHECK = ['def last_run(branch=None, sha=None):',
              '不拿旧 run 冒充结论']


def checks(f, tool_text=None):
    e = []
    for s in QUEUE + QUEUE_KEEP:
        if s not in f["queue"]:
            e.append("能力缺失[queue]: " + s[:68])
    for s in PLAYER:
        if s not in f["player"]:
            e.append("能力缺失[player]: " + s[:68])
    if 'python3 scripts/r182_verify.py --selftest' not in f["ci"]:
        e.append("CI 未挂 r182_verify --selftest")
    # 短暂失焦必须走保留焦点的暂停，不能退回 pause()
    body = f["queue"]
    i = body.find('private val focusListener')
    j = body.find('fun attach(', i)
    seg = body[i:j] if i >= 0 and j > i else ""
    if seg and ('transient' not in seg.lower() or 'AUDIOFOCUS_GAIN' not in seg):
        e.append("焦点监听没区分 transient 与永久 LOSS")
    if tool_text is not None:
        for s in TOOL_CHECK:
            if s not in tool_text:
                e.append("发版工具状态判据没修[rc_push]: " + s[:56])
    return e


def selftest():
    base = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
            for k, p in FILES.items()}
    tool = TOOL.read_text(encoding="utf-8") if TOOL.is_file() else None
    assert not checks(base, tool), "基线自己就红了，先修基线"
    cases = [
        ("tick 不再定时落盘", "queue",
         lambda t: t.replace('            if (_state.value.isPlaying) savePos()\n', '')),
        ("节流间隔写成 0（每帧写盘）", "queue",
         lambda t: t.replace('private val POS_PERSIST_INTERVAL = 20_000L',
                             'private val POS_PERSIST_INTERVAL = 0L')),
        ("transient 又调 pause 放弃焦点", "queue",
         lambda t: t.replace('                pauseKeepFocus()          // 故意不 abandonFocus，才能收到 GAIN',
                             '                pause()')),
        ("GAIN 不续播", "queue",
         lambda t: t.replace('                    pausedByTransient = false\n                    resume()',
                             '                    pausedByTransient = false')),
        ("永久 LOSS 也当 transient", "queue",
         lambda t: t.replace('            AudioManager.AUDIOFOCUS_LOSS -> {\n                pausedByTransient = false\n                pause()\n            }',
                             '            AudioManager.AUDIOFOCUS_LOSS -> {\n                pauseKeepFocus()\n            }')),
        ("setTracks 不重置节流窗口", "queue",
         lambda t: t.replace('        if (isPreview(id)) { previewSetTracks(list, id); return }\n        lastPosPersistMs = 0L',
                             '        if (isPreview(id)) { previewSetTracks(list, id); return }')),
        ("release 不清 transient 标记", "queue",
         lambda t: t.replace('    fun release() {\n        pausedByTransient = false\n',
                             '    fun release() {\n')),
        ("手动暂停不再归还焦点", "queue",
         lambda t: t.replace('    fun pause() {\n        savePos()\n        abandonFocus()',
                             '    fun pause() {\n        savePos()')),
        ("选章又拼废队列 id", "player",
         lambda t: t.replace('                                val q = if (st.queueId.startsWith("chapter:") &&\n                                    st.queueId.length > 8) st.queueId\n                                else "chapter:" + bookId.orEmpty()',
                             '                                val q = "chapter:" + bookId.orEmpty()')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r182_verify.py --selftest", "")),
        ("发版工具 status 又拿旧 run", None,
         lambda t: t.replace('def last_run(branch=None, sha=None):',
                             'def last_run():')),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        tool_v = tool
        if key is None:
            if tool is None:
                continue
            tool_v = mut(tool)
            assert tool_v != tool, "注入没生效: " + name
        else:
            v[key] = mut(base[key])
            assert v[key] != base[key], "注入没生效: " + name
        if not checks(v, tool_v):
            bad += 1
            print("  反向注入漏放:", name)
    total = len(cases) if tool is not None else len(cases) - 1
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (total - bad, total))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
         for k, p in FILES.items()}
    tool = TOOL.read_text(encoding="utf-8") if TOOL.is_file() else None
    e = checks(f, tool)
    if e:
        print("FAIL · R182 收听连续性")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R182 收听连续性：定时落盘 / 短暂失焦续播 / 队列 id 不串：PASS")
