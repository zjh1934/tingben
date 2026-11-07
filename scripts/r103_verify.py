# -*- coding: utf-8 -*-
"""R103 · F-01 播放身份稳定 id + F-10 格子同源 验证。

只查字符串与结构，不假装证明真机手感。
用法：python3 scripts/r103_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "queue": APP / "core" / "PlayQueue.kt",
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def grid(s):
    i = s.find("private fun ProgressScreen(")
    j = s.find("private fun TaskCenterScreen(", i + 1)
    return s[i:j] if i >= 0 else ""


# ① 正向：本轮修复必须真的在源码里
POS = [
    ("queue", 'val id: String = ""'),
    ("queue", 'val trackId: String = ""'),
    ("queue", 'trackId = t.id'),
    ("queue", 'trackId = "")'),
    ("main", 'val segId = sg.wav?.let { "seg:" + it.absolutePath } ?: ""'),
    ("main", 'val playable = sg.hasAudio && segId.isNotEmpty()'),
    ("main", 'val onThis = playable && pqSeg.trackId == segId'),
    ("main", '.clickable(enabled = playable)'),
    ("main", 'Track(wav, label, id = segId)'),
    ("main", 'if (playable)'),
    ("main", 'id = "audio:${it.id}:${it.file.absolutePath}"'),
    ("main", 'pq.trackId.isNotEmpty() && pq.trackId == tid'),
    ("main", 'pq.trackId == "audio:${item.id}:${item.file.absolutePath}"'),
    ("main", 'id = "trial:${f.absolutePath}"'),
    ("hub", 'id = "chapter:${a.file.absolutePath}"'),
    ("hub", 'id = "chapter:${x.file.absolutePath}"'),
    ("ci", "r103_verify.py"),
]

# ② 零残留：旧形态不准回来
BAN_ALL = [
    "pqSeg.label == label",
    "clickable(enabled = sg.wav != null)",
    "Track(sg.wav!!, label)",
    "if (sg.wav != null)\n                                    Text(",
    "val on = idx == pq.index",
    "items.getOrNull(pq.index)",
    '"${a.title} · 第 ${j + 1} 章"',
]

# ③ 守恒：播放器 API 与既有能力一个不少
KEEP = [
    ("queue", "fun setTracks(list: List<Track>)"),
    ("queue", "fun playFrom(i: Int, startMs: Long? = null)"),
    ("queue", "fun toggle()"),
    ("queue", "fun pause()"),
    ("queue", "fun resume()"),
    ("queue", "fun previous()"),
    ("queue", "fun next()"),
    ("queue", "fun seekTo(ms: Long)"),
    ("queue", "fun setSpeed(f: Float)"),
    ("queue", "fun tick()"),
    ("queue", "fun release()"),
    ("queue", "val startMs: Long = 0L"),
    ("queue", "setOnCompletionListener"),
    ("main", '"拆章", "生成声音", "整理音频", "完成"'),
    ("main", "val steps = listOf"),
    ("hub", "HubTitles"),
]


def check(f):
    errs = []
    for k, needle in POS:
        if needle not in f[k]:
            errs.append("缺正向: " + needle[:70])
    for b in BAN_ALL:
        for k in ("queue", "main", "hub"):
            if b in f[k]:
                errs.append("旧形态残留: " + b[:50])
    for k, needle in KEEP:
        if needle not in f[k]:
            errs.append("守恒缺失: " + needle[:60])
    g = grid(f["main"])
    for needle in ('pqSeg.trackId == segId', 'clickable(enabled = playable)',
                   'Track(wav, label, id = segId)', 'val playable = sg.hasAudio'):
        if needle not in g:
            errs.append("看台内缺失: " + needle)
    # 看台里不得再出现第二套可播判定
    if "sg.wav != null" in g:
        errs.append("看台里仍有 wav != null 的第二套判定")
    return errs


def selftest():
    f = load()
    base = check(f)
    if base:
        print("基线自身就不合格：", *base, sep="\n  ")
        return 1
    # 反向注入：把每个关键修复逐个改回旧形态，check 必须报错
    cases = [
        ("queue", 'val id: String = ""', 'val id: String'),
        ("queue", 'val trackId: String = ""', 'val label2: String = ""'),
        ("queue", 'trackId = t.id', 'trackId = ""'),
        ("main", 'val onThis = playable && pqSeg.trackId == segId',
                 'val onThis = pqSeg.label == label'),
        ("main", '.clickable(enabled = playable)', '.clickable(enabled = sg.wav != null)'),
        ("main", 'Track(wav, label, id = segId)', 'Track(sg.wav!!, label)'),
        ("main", 'if (playable)\n                                    Text(',
                 'if (sg.wav != null)\n                                    Text('),
        ("main", 'pq.trackId.isNotEmpty() && pq.trackId == tid', 'idx2 == pq.index'),
        ("hub", 'id = "chapter:${x.file.absolutePath}"', ''),
        ("ci", 'r103_verify.py', 'r103_verify_old.py'),
    ]
    bad = 0
    for k, old, new in cases:
        g = dict(f)
        if old not in f[k]:
            print("注入源缺失:", old[:50]); bad += 1; continue
        g[k] = f[k].replace(old, new, 1)
        if check(g) == []:
            print("反向注入没被拦下:", old[:60]); bad += 1
    print("反向注入 %d/%d 拦下" % (len(cases) - bad, len(cases)))
    return 1 if bad else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    errs = check(load())
    if errs:
        print("R103 自检失败：", *errs, sep="\n  ")
        sys.exit(1)
    print("R103 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条 全过" %
          (len(POS), len(BAN_ALL), len(KEEP)))
