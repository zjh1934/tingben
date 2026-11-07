# -*- coding: utf-8 -*-
"""R111 · F-24 播放队列归属 + F-22 时长口径标注：正向 / 零残留 / 守恒 / 反向注入。

R119：逐块时长行已收成小格子，时长口径守恒点落在播放卡 fmtTime(pq.positionMs)。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
QUEUE = SRC / "core/PlayQueue.kt"
MAIN = SRC / "MainActivity.kt"
HUB = SRC / "ChapterHub.kt"
CI = ROOT / ".github/workflows/ci.yml"

POS = {
    "queue": [
        'val queueId: String = ""',
        "private var queueId = \"\"",
        'fun setTracks(list: List<Track>, id: String = "")',
        "if (list == tracks && id == queueId) return",
        'fun playFrom(i: Int, startMs: Long? = null, id: String? = null)',
        "if (id != null && id != queueId) return",
        "queueId = id",
        "queueId = \"\"",
    ],
    "main": [
        'var tree by remember { mutableStateOf(emptyList<BookAudio>()) }',
        'LaunchedEffect(refresh, localTick) {',
        'PlayQueue.setTracks(listOf(Track(s.file, s.name, id = tid)), "seg")',
        'PlayQueue.playFrom(0, id = "seg")',
        'PlayQueue.setTracks(listOf(Track(wav, label, id = segId)), "seg")',
        'PlayQueue.playFrom(0, id = "seg")',
        'PlayQueue.setTracks(listOf(Track(f, "调音台试听", id = "trial:${f.absolutePath}")), "trial")',
        'PlayQueue.playFrom(0, id = "trial")',
        "· $chCount 章",
        'fmtTime(pq.durationMs)',
        'fmtTime(pq.positionMs)',
    ],
    "hub": [
        'val q = "chapter:${book.id}"',
        '), q)',
        '}, q)',
        'PlayQueue.playFrom(0, id = q)',
        'PlayQueue.playFrom(list.indexOfFirst { it.first == i }.coerceAtLeast(0), id = q)',
        'PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)',
    ],
}

BAN = {
    "main": [
        "PlayQueue.setTracks(items.map",
        'PlayQueue.setTracks(listOf(Track(wav, label, id = segId)))\n',
        "PlayQueue.playFrom(0)\n",
    ],
    "hub": [],
}

KEEP = {
    "queue": ["fun toggle()", "fun pause()", "fun resume()", "fun release()",
              "setOnCompletionListener", "val startMs: Long = 0L"],
    "main": ["共 ${tree.size} 本 · $chCount 章",
             "val segCount = tree.sumOf",
             "val pq by PlayQueue.state.collectAsState()"],
    "hub": ["internal fun ChapterHubScreen", "PlayQueue.release()"],
}


def check(f):
    e = []
    for k, needles in POS.items():
        for s in needles:
            if s not in f[k]:
                e.append("缺正向 %s: %s" % (k, s[:56]))
    for k, needles in BAN.items():
        for s in needles:
            if s in f[k]:
                e.append("旧形态残留 %s: %s" % (k, s[:44]))
    for k, needles in KEEP.items():
        for s in needles:
            if s not in f[k]:
                e.append("守恒丢失 %s: %s" % (k, s[:44]))
    if f["main"].count('PlayQueue.setTracks(listOf(Track(s.file, s.name, id = tid)), "seg")') < 1:
        e.append("音频页试听按钮没有先确认队列归属")
    if f["hub"].count("}, q)") < 3:
        e.append("章节页队列未全部带归属 id")
    if "r111_verify.py" not in f["ci"]:
        e.append("CI 未挂 r111_verify")
    return e


def txt(p):
    return p.read_text(encoding="utf-8", errors="replace")


def main():
    f = {"queue": txt(QUEUE), "main": txt(MAIN), "hub": txt(HUB), "ci": txt(CI)}
    errs = check(f)
    if "--selftest" in sys.argv:
        injections = [
            ("撤掉 playFrom 归属守卫",
             lambda t: dict(t, queue=t["queue"].replace(
                 "if (id != null && id != queueId) return", ""))),
            ("setTracks 不再记 id",
             lambda t: dict(t, queue=t["queue"].replace("queueId = id", ""))),
            ("音频页按钮不再重建队列",
             lambda t: dict(t, main=t["main"].replace(
                 'PlayQueue.setTracks(listOf(Track(s.file, s.name, id = tid)), "seg")',
                 'PlayQueue.setTracks(listOf(Track(s.file, s.name)))'))),
            ("看台单段队列退回无 id",
             lambda t: dict(t, main=t["main"].replace(
                 'PlayQueue.setTracks(listOf(Track(wav, label, id = segId)), "seg")',
                 'PlayQueue.setTracks(listOf(Track(wav, label, id = segId)))'))),
            ("章节页队列退回无 id",
             lambda t: dict(t, hub=t["hub"].replace('), q)', ')'))),
            ("时长退回无口径标注",
             lambda t: dict(t, main=t["main"].replace("· $chCount 章", ""))),
            ("CI 漏挂验证",
             lambda t: dict(t, ci=t["ci"].replace("r111_verify.py", "r111_missing.py"))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(f)):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(injections) - bad, len(injections)))
    if errs:
        print("R111 FAIL", len(errs))
        [print(" -", x) for x in errs]
        return 1
    print("R111 队列归属 / 时长口径 / 守恒：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
