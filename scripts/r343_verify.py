# -*- coding: utf-8 -*-
"""R343 · 中间态章「没拼 / 没转码之前一直能展开」：形态守门 + 链路守恒。

用户实证：某一章的小块重做过、或没重做完成，制作走到下一章之后，那一章
在目录页就点不开，藏在里面的小块、重做、收尾条「拼接并转码」、导出合成
入口全找不到。根因是展开入口只认 AudioTree 段 WAV 份数（mergeReady）——
重做中的段旧 WAV 已删、新 WAV 还没落时份数为 0，章行不给回看标签，
章名又按 R264 不承担展开，于是整章被锁死。

R343 把「中间态」判定换成：还没固化成章音频，且这一章已经动过（逐章进度
起来了 / 有实存小块 / 有没消化完的重做标记）。中间态章章名短按即展开，
章行恒给回看入口，收尾条与导出菜单在份数为 0 时也原样在、写明在等什么。

守恒一条不许动：拼章闸仍是 readyHere > 0 && redoHere == 0 && !busyHere，
自动开关 force=false，shownChapter 仍只展开一章，R342 的回看锚全留着。

用法：python3 scripts/r343_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": APP / "ChapterHub.kt",
    "producer": APP / "core" / "Producer.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
}

POS = {
    "hub": [
        # 中间态判定：不是只看 mergeReady
        "R343 · 「中间态」＝还没拼成 / 没转码成章音频",
        "fun redoOf(i: Int): Int = hubByChapter[i].orEmpty()",
        "val unmergedChapters by remember(book.id)",
        "val started = (r.synthesized ?: 0f) > 0f",
        "if (!hasDone && (started || (mergeReady[r.index] ?: 0) > 0 || redoOf(r.index) > 0))",
        # 章行恒给展开入口 + 点章名即展开
        "r.index in unmergedChapters && r.index != shownChapter ->",
        '"待重做 ${redoOf(r.index)} 段 · 展开"',
        '"展开这一章的小块"',
        "onTapName = if (r.index in unmergedChapters &&",
        "onTapName: (() -> Unit)? = null",
        "onTap = { onTapName?.invoke() }",
        # 收尾条与导出菜单在份数为 0 时也原样在
        "val unmergedHere = r.index in unmergedChapters",
        '"小块还在重做中，补齐后才能拼这一章"',
        "readyHere > 0 || hasDone || busyHere || unmergedHere",
        "val unmergedHere = i != null && i in unmergedChapters",
        "enabled = pending > 0 || unmergedHere",
        '"这一章的小块还在重做中 · 展开去处理"',
        # 固化成章音频后自动收起回看态
        "LaunchedEffect(peekChapter, localTick, diskNow)",
        # 守恒锚：一个都不许少
        "val shownChapter: Int? = peekChapter ?: liveChapter",
        "peekChapter = if (backToLive) null else r.index",
        '"这一章有 $pending 段小块 · 展开去拼接并转码"',
        "if (r.index == shownChapter) {",
        "val canMerge = readyHere > 0 && redoHere == 0 && !busyHere",
        "peekChapter = i",
    ],
    "producer": ["gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = false)"],
    "ci": ["python3 scripts/r343_verify.py --selftest"],
}

BAN = {
    "hub": [
        # 旧形态回潮：只看段 WAV 份数 / 份数为 0 就不给入口
        "(mergeReady[r.index] ?: 0) > 0 && r.index != shownChapter",
        "enabled = pending > 0,",
        "if (readyHere > 0 || hasDone || busyHere) {",
        "detectTapGestures(onLongPress = { onLong() })",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(files):
    e = []
    for k, words in POS.items():
        for w in words:
            if w not in files.get(k, ""):
                e.append("缺锚[%s]: %s" % (k, w[:64]))
    for k, words in BAN.items():
        for w in words:
            if w in files.get(k, ""):
                e.append("旧形态回潮[%s]: %s" % (k, w[:64]))
    return e


MUTANTS = [
    ("中间态判定只剩段份数", "r.index in unmergedChapters && r.index != shownChapter ->",
     "(mergeReady[r.index] ?: 0) > 0 && r.index != shownChapter ->"),
    ("进度起步不认", "val started = (r.synthesized ?: 0f) > 0f", "val started = false"),
    ("重做标记不认", "redoOf(r.index) > 0))", "false))"),
    ("章名短按被摘", "onTap = { onTapName?.invoke() }", "onTap = null"),
    ("章名传参被摘", "onTapName = if (r.index in unmergedChapters &&", ""),
    ("收尾条条件退回旧", "readyHere > 0 || hasDone || busyHere || unmergedHere",
     "readyHere > 0 || hasDone || busyHere"),
    ("重做中状态说明被摘", '"小块还在重做中，补齐后才能拼这一章"', ""),
    ("导出菜单 enabled 退回旧", "enabled = pending > 0 || unmergedHere",
     "enabled = pending > 0,"),
    ("导出菜单中间态文案被摘", '"这一章的小块还在重做中 · 展开去处理"', ""),
    ("固化后收起被摘", "LaunchedEffect(peekChapter, localTick, diskNow)", ""),
    ("拼章闸被放开", "val canMerge = readyHere > 0 && redoHere == 0 && !busyHere",
     "val canMerge = true"),
    ("R342 回看锁死回潮", "val shownChapter: Int? = peekChapter ?: liveChapter",
     "val shownChapter: Int? = if (bookRunning) liveChapter else (peekChapter ?: liveChapter)"),
    ("回看点击被吞", "peekChapter = if (backToLive) null else r.index",
     "if (!bookRunning) { peekChapter = r.index }"),
    ("导出展开入口被摘", '"这一章有 $pending 段小块 · 展开去拼接并转码"', ""),
    ("自动收尾抢拼回潮", "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = false)",
     "gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = true)"),
    ("CI 未挂本判据", "python3 scripts/r343_verify.py --selftest", ""),
]


def selftest():
    base = load()
    assert not check(base), "正向不该红：%s" % check(base)
    for name, old, new in MUTANTS:
        f = dict(base)
        key = "producer" if "force = false)" in old else ("ci" if "ci.yml" in old or "scripts/r343" in old else "hub")
        f[key] = f[key].replace(old, new, 1)
        e = check(f)
        assert e, "反向注入没拦下：%s" % name
    for old_form in BAN["hub"]:
        f = dict(base)
        f["hub"] = base["hub"] + "\n// " + old_form + "\n"
        assert check(f), "旧形态回潮没拦下：%s" % old_form[:40]
    print("R343 selftest: 正向通过，反向注入 %d 组 + 回潮 %d 组全拦下"
          % (len(MUTANTS), len(BAN["hub"])))


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    e = check(load())
    if e:
        print("R343 红：")
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R343 绿：中间态章恒可展开，拼章闸与 R342 回看链路守恒")


if __name__ == "__main__":
    main()
