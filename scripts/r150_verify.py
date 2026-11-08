# -*- coding: utf-8 -*-
"""R150 · G2a「一书一页连贯骨架」守门。

用户实证（第一份真机日志后的使用感受）：
  · 「进度条、停止/继续应该设置到目录页的上面靠上面一些，
    整本进度或章节进度两条，右边一个停止或继续按键」
  · 「点这个章节它就折叠起来，切开的小片从章节下面直接展开，
    点一下试听，再点一下停；序号上不要盖播放/停止按钮」
  · 总纲：「不像在看一本有声书，像在不同卡片里切来切去」

落地（ChapterHub.kt）：
  · 顶部连贯条：整本进度 + 当前章进度（展开章时才显第二根）+
    单个按钮（运行→停止 / 停滞→继续），不再去任务页才能按
  · 第一列章节点击 = 就地展开/收起；阅读挪到行尾独立小按钮；长按仍是勾选
  · ChapterSegGrid：小段格子只显示序号，靠底色表达 未生成/已生成/正在播，
    点一下播、再点一下停，全走 PlayQueue.previewState（不占常驻条、
    不进播放器页、不进通知栏）
  · 段数据走 Workbench.segments + segChapters 按章过滤，IO 线程加载

用法：python3 scripts/r150_verify.py --selftest
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
    "queue": APP / "core" / "PlayQueue.kt",
}

POS = {
    "hub": [
        "var expandedChapter by remember(book.id)",
        "var hubSegs by remember(book.id)",
        "val chapterSegs = remember(expandedChapter, hubSegs, hubSegChapters)",
        "val pqSeg by PlayQueue.previewState.collectAsState()",
        "Workbench.segments(c, t) to Workbench.segChapters(c, t)",
        "hubSegs.filter { map[it.index] == ch }",
        "val bookRunning = st.running && task?.bookId == book.id",
        "val stalledTask = !st.running && task?.bookId == book.id",
        "val bookPct =", "val chPct =",
        "LinearProgressIndicator(progress = { bookPct }",
        "LinearProgressIndicator(progress = { chPct }",
        'EventLog.ui("HUB_STOP"', 'EventLog.ui("HUB_RESUME"',
        "Producer.stop()", "onResumeTask()",
        "第 ${chNow + 1} 章 ·",
        "expanded: Int?, onExpand: (Int) -> Unit,",
        "segs: List<SegRow>, pq: PlaybackState,",
        "onTap = { onExpand(r.index) }",
        'EventLog.ui("HUB_CH_EXPAND"',
        "TextButton(onClick = { onRead(r.index) }",
        "private fun ChapterSegGrid(",
        '"本章 ${segs.size} 小段 · 点一下试听，再点一下停"',
        "val onThis = playable && pq.trackId == segId",
        "val playing = onThis && pq.isPlaying",
        "PlayQueue.pausePreview()", "PlayQueue.resumePreview()",
        'PlayQueue.setTracks(', '"seg")',
        "clickable(enabled = playable)",
    ],
    "ci": ["python3 scripts/r150_verify.py --selftest"],
    "r135": ['"r150_verify.py"'],
    "queue": ["fun pausePreview()", "fun resumePreview()", "val previewState: StateFlow"],
}

BAN = {
    "hub": [
        '"正在制作 · ${st.done}/${st.total} 块"',
        "onTap = { onRead(r.index) }",
        '"这次没做完，已生成的段都留着"',
        "TextButton(onClick = onProgress) { Text(\"任务 ›\"",
    ],
}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def progress(done, total, running, task_done, task_total):
    """复刻顶部条的整本进度口径。"""
    t = max(total, task_total)
    d = (done if running else task_done)
    d = max(0, min(d, max(t, 1)))
    return d / t if t > 0 else 0.0, d, t


def preview_action(playable, on_this, playing):
    """复刻格子点击的四态路由。"""
    if not playable:
        return "不可点"
    if playing:
        return "暂停"
    if on_this:
        return "继续播"
    return "起播"


def logic_cases(f):
    bad = []
    # ① 进度口径
    cases = [
        ("运行中 30/100", 30, 100, True, 0, 0, 0.30),
        ("停滞用任务落盘值", 0, 0, False, 12, 40, 0.30),
        ("全 0 不除零", 0, 0, True, 0, 0, 0.0),
        ("done 超过 total 要夹住", 150, 100, True, 0, 0, 1.0),
        ("task_total 更大取大", 5, 10, False, 20, 50, 0.40),
    ]
    for name, d, t, run, td, tt, want in cases:
        pct, dd, tot = progress(d, t, run, td, tt)
        if abs(pct - want) > 1e-6 or not (0.0 <= pct <= 1.0):
            bad.append("%s: pct=%s 期望 %s" % (name, pct, want))
        else:
            print("  %-24s → %.0f%% (%d/%d)" % (name, pct * 100, dd, tot))

    # ② 试听状态机
    taps = [
        (False, False, False, "不可点"),
        (True, False, False, "起播"),
        (True, True, True, "暂停"),
        (True, True, False, "继续播"),
    ]
    for playable, on_this, playing, want in taps:
        got = preview_action(playable, on_this, playing)
        if got != want:
            bad.append("试听状态机 %s/%s/%s → %s 期望 %s" %
                       (playable, on_this, playing, got, want))
        else:
            print("  试听 %-8s → %s" % ("播放中" if playing else "已暂停", got))

    # ③ 段按章过滤：map 是 seg→chapter
    segs = [0, 1, 2, 3, 4, 5]
    seg_ch = {0: 0, 1: 0, 2: 1, 3: 1, 4: 2, 5: 2}
    for ch, want in ((0, [0, 1]), (1, [2, 3]), (2, [4, 5])):
        got = [i for i in segs if seg_ch.get(i) == ch]
        if got != want:
            bad.append("第 %d 章段过滤错：%s" % (ch, got))
        else:
            print("  第 %d 章 → 小段 %s" % (ch, got))

    # ④ 序号上不许盖按钮：格子内容里只允许一个 Text，不许 Icon/播放字符
    m = re.search(r"private fun ChapterSegGrid\(.*?\n\}\n\n@Composable\nprivate fun SynColumn",
                  f["hub"], re.S)
    if not m:
        bad.append("没圈出 ChapterSegGrid 函数体")
    else:
        body = m.group(0)
        for s in ("IconPlayPause(", "IconSkip(", '"▶"', '"⏸"', "播放 ›", "停止 ›"):
            if s in body:
                bad.append("小段序号上又盖了按钮/字符：" + s)
        if body.count("Text(\"${sg.index + 1}\"") != 1:
            bad.append("小段格子必须只显示一个序号 Text")

    # ⑤ 章展开必须走 IO，不能组合期读盘
    if "withContext(Dispatchers.IO)" not in f["hub"]:
        bad.append("段数据没走 IO 线程")

    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:75]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("展开状态被摘", "hub", "var expandedChapter by remember(book.id)", ""),
        ("一次读盘被摘", "hub", "var hubSegs by remember(book.id)", ""),
        ("段数据加载被摘", "hub", "Workbench.segments(c, t) to Workbench.segChapters(c, t)", ""),
        ("按章过滤被摘", "hub", "hubSegs.filter { map[it.index] == ch }", ""),
        ("试听状态被摘", "hub", "val pqSeg by PlayQueue.previewState.collectAsState()", ""),
        ("整本进度条被摘", "hub", "LinearProgressIndicator(progress = { bookPct }", ""),
        ("章节进度条被摘", "hub", "LinearProgressIndicator(progress = { chPct }", ""),
        ("停止按钮被摘", "hub", 'EventLog.ui("HUB_STOP"', ""),
        ("停止动作被摘", "hub", "Producer.stop()", ""),
        ("继续按钮被摘", "hub", 'EventLog.ui("HUB_RESUME"', ""),
        ("点章展开被摘", "hub", "onTap = { onExpand(r.index) }", "onTap = { onRead(r.index) }"),
        ("阅读入口被摘", "hub", "TextButton(onClick = { onRead(r.index) }", ""),
        ("展开格组件被摘", "hub", "private fun ChapterSegGrid(", ""),
        ("preview 路由被摘", "hub", "PlayQueue.pausePreview()", "PlayQueue.toggle()"),
        ("resumePreview 被摘", "hub", "PlayQueue.resumePreview()", ""),
        ("序号上盖按钮", "hub", '"本章 ${segs.size} 小段 · 点一下试听，再点一下停"',
         '"▶ 播放"'),
        ("旧文字条回潮", "hub", "val bookRunning = st.running && task?.bookId == book.id",
         '"正在制作 · ${st.done}/${st.total} 块"'),
        ("CI 漏挂 r150", "ci", "python3 scripts/r150_verify.py --selftest", ""),
        ("现行清单漏 r150", "r135", '"r150_verify.py"', ""),
        ("preview 通道被摘", "queue", "fun pausePreview()", ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R150 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R150 一书一页连贯骨架（双进度 / 单停止继续 / 章节就地展开 / preview 试听）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
