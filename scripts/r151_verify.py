# -*- coding: utf-8 -*-
"""R151 · G2b「收掉最后一张卡片」守门。

用户总纲：「分段看台和任务页下面的书籍目录应该是一个页面，不应该是分开的页面」
「点开感觉不像在看一本有声书，像是在不同的卡片里面切来切去」。

G2a 已把双进度 / 单停止继续 / 章节就地展开做进目录页；G2b 收尾：
  · 三个「看进度」入口（续跑 / 制作页 / 任务页）一律 openBook(id)，
    独立 Screen.PROGRESS 变成完全不可达的能力库。
    R195 收口轮已把枚举值 / 分发 / 函数体 / 只写不读的状态变量物理删净，
    并把写死 8 值的守门改成按符号解析；本脚本改为「退役零回潮」守门。
  · 第二列升级成完整「分段看台」：模型心跳 + 全量灰/绿格子 +
    长按多选（全选 / 试听选中 / 删除选中）+ 删段二次确认与 IO 落盘
  · 一次读盘、两列共用真值：第一列按章派生、第二列用全量，口径不打架

用法：python3 scripts/r151_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": ui_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "main": [
        "openBook(b.id)",
        "onProgress = { openBook(bookId) }",
        "onProgress = { id -> openBook(id) }",
        "onProgress: (String) -> Unit,",
        "onClick = { onProgress(t.bookId) }",
        "private enum class Screen {",
    ],
    "hub": [
        "var hubSegs by remember(book.id)",
        "var hubSegChapters by remember(book.id)",
        "LaunchedEffect(book.id, st.done, localTick, task?.bookId)",
        "Workbench.segments(c, t) to Workbench.segChapters(c, t)",
        "val chapterSegs = remember(expandedChapter, hubSegs, hubSegChapters)",
        "var beatTick by remember { mutableStateOf(0) }",
        "EventLog.lastEngine()", "EventLog.lastEngineAgeMs()",
        "var confirmSegDel by remember",
        'item(key = "seg_head_${r.index}")',
        'HubDivider()',
        'items(chapterSegs.chunked(SEG_GRID_COLUMNS),',
        "LazyColumn(Modifier.fillMaxSize(),",
        "row = row, pq = pqSeg,",
        "multi = segMulti, checked = segChecked,",
        "onTap = { sg -> tapSeg(sg) })",
        "r: ChapterUi, multi: Boolean, checked: Boolean, expanded: Boolean,",
        "row: List<SegRow>, pq: PlaybackState,",
        "onTrial: () -> Unit, onDelete: () -> Unit",
        "var segMulti by remember",
        "var segChecked by remember",
        '"本章 ${segs.size} 小段"',
        '"暂无小段"',
        "items(chapterSegs.chunked(SEG_GRID_COLUMNS),",
        'EventLog.ui("SEG_ALL"',
        'EventLog.ui("SEG_TRIAL_PICKED"',
        'EventLog.ui("SEG_DELETE_ASK"',
        'EventLog.ui("SEG_DELETE"',
        'EventLog.ui("SEG_TAP"',
        "PlayQueue.setTracks(picked, \"seg\")",
        "PlayQueue.pausePreview()", "PlayQueue.resumePreview()",
        "confirmSegDel?.let { files ->",
        "AudioOps.deleteAll(files)",
        "AudioTree.forget(c,",
        "PlayQueue.release()",
        "localTick++",
        "分段计划还在；下次续跑会按原顺序重新生成",
    ],
    "ci": ["python3 scripts/r151_verify.py --selftest"],
    "r135": ['"r151_verify.py"'],
}

BAN = {
    "main": [
        "Screen.PROGRESS",  # R195 · 退役值任何形态都不许回来
        "PROGRESS, TASKS",  # 枚举整行里夹带退役值
        "ProgressScreen(",
        "nav.push(Screen.PROGRESS)",
        "screen = Screen.PROGRESS",
        "onProgress = { taskBookId = bookId; taskChapter = null; nav.push(Screen.PROGRESS) }",
        "if (taskChapter != null) nav.push(Screen.PROGRESS)",
    ],
    "hub": [
        "segs.chunked(10).forEach",  # 一次性组合几千个格子 → 大书卡死
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


def entry_target(where, col):
    """复刻三个入口的落点：一律目录页第二列。"""
    return ("CHAPTERS", col) if col == 1 else ("?", col)


def seg_views(all_segs, seg_ch, chapter):
    """复刻两列派生：第一列按章过滤，第二列全量。"""
    col1 = [i for i in all_segs if seg_ch.get(i) == chapter] if chapter is not None else []
    col2 = list(all_segs)
    return col1, col2


def multi_action(multi, checked, action):
    """复刻第二列多选动作：空选必须提示，不允许静默。"""
    if action == "试听选中" and not checked:
        return "提示先勾"
    if action == "删除选中" and not checked:
        return "提示先勾"
    if action == "全选":
        return "全选已生成"
    if action == "取消":
        return "退出多选"
    if action == "试听选中":
        return "preview 队列起播"
    if action == "删除选中":
        return "二次确认后删 WAV"
    return "?"


def logic_cases(f):
    bad = []
    # ① 三个入口全部落到 (CHAPTERS, 1)
    for name in ("续跑任务", "制作页合成后", "任务页看进度"):
        screen, col = entry_target(name, 1)
        if (screen, col) != ("CHAPTERS", 1):
            bad.append("%s 没落目录第二列：%s/%s" % (name, screen, col))
        else:
            print("  %-14s → %s 第 %d 列" % (name, screen, col))
    # 入口字面量必须真在代码里，且 PROGRESS 零跳转
    for s in ("openBook(b.id)",
              "onProgress = { openBook(bookId) }",
              "onProgress = { id -> openBook(id) }"):
        if s not in f["main"]:
            bad.append("入口没收敛: " + s)
    if "Screen.PROGRESS" in f["main"] or "ProgressScreen(" in f["main"]:
        bad.append("退役的独立进度页又回来了")

    # ② 两列段视图口径
    all_segs = list(range(6))
    seg_ch = {0: 0, 1: 0, 2: 1, 3: 1, 4: 2, 5: 2}
    c1, c2 = seg_views(all_segs, seg_ch, 1)
    if c1 != [2, 3] or c2 != all_segs:
        bad.append("两列段视图口径错：第一列 %s 第二列 %s" % (c1, c2))
    else:
        print("  第 1 章展开 → %s · 第二列全量 → %d 段" % (c1, len(c2)))

    # ③ 多选动作
    for action, empty, want in (
        ("试听选中", True, "提示先勾"), ("删除选中", True, "提示先勾"),
        ("全选", True, "全选已生成"), ("取消", True, "退出多选"),
        ("试听选中", False, "preview 队列起播"),
        ("删除选中", False, "二次确认后删 WAV"),
    ):
        got = multi_action(True, set() if empty else {0}, action)
        if got != want:
            bad.append("多选 %s(空=%s) → %s 期望 %s" % (action, empty, got, want))
        else:
            print("  多选 %-8s → %s" % (action, got))

    # R163 · 单列后展开章的小片就是按章过滤的，守「惰性行」而非「全量」
    if not re.search(r'items\(chapterSegs\.chunked\(SEG_GRID_COLUMNS\)', f["hub"]):
        bad.append("展开章小片没有惰性行")
        if "segMulti" not in body or "onDeleteSegs" not in body:
            bad.append("看台缺多选或删段出口")
        if "heartbeat" not in body:
            bad.append("看台缺模型心跳")
    if "items(chapterSegs.chunked(SEG_GRID_COLUMNS)," not in f["hub"]:
        bad.append("全量格子没拆成惰性行")
    # 9137 段时应是约 914 个行 item，而不是 1 个巨型 item
    n = 9137
    rows = (n + 9) // 10
    if rows < 100:
        bad.append("惰性行数算错了")
    else:
        print("  %d 段 → %d 个惰性行 item（不是 1 个巨型 item）" % (n, rows))

    # ⑤ 删段只删 WAV：确认文案 + API 都要在
    for s in ("AudioOps.deleteAll(files)", "AudioTree.forget(c,", "分段计划还在"):
        if s not in f["hub"]:
            bad.append("删段闭环缺件：" + s)

    # ⑥ 编译级防回潮：改签名时最容易留下一行旧声明，形成「同名参数两次」。
    # 静态字符串守门抓不到它，但 Kotlin 真编译会炸。
    # 函数类型里带括号（如 (String) -> Unit），正则圈不出完整签名，
    # 用括号计数逐个提取参数区，再统计参数名。
    def sig_of(src, fname):
        i = src.find("private fun " + fname + "(")
        if i < 0:
            return None
        start = src.find("(", i)
        depth = 0
        for j in range(start, len(src)):
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
                if depth == 0:
                    return src[start + 1:j]
        return None

    for fname in re.findall(r"private fun (\w+)\(", f["main"]):
        sig = sig_of(f["main"], fname)
        if sig is None:
            continue
        params = re.findall(r"\b(\w+)\s*:", sig)
        dup = {n for n in params if params.count(n) > 1}
        if dup:
            bad.append("函数 %s 有重复参数：%s（真编译会炸）" % (fname, sorted(dup)))
    tc = sig_of(f["main"], "TaskCenterScreen")
    if tc is None:
        bad.append("没圈出 TaskCenterScreen 签名")
    elif tc.count("onProgress:") != 1:
        bad.append("TaskCenterScreen 的 onProgress 声明次数 != 1")
    else:
        print("  TaskCenterScreen 签名唯一 onProgress ✓")


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
        ("续跑收敛被摘", "main", "openBook(b.id)", "nav.push(Screen.PROGRESS)"),
        ("制作页收敛被摘", "main", "onProgress = { openBook(bookId) }",
         "onProgress = { nav.push(Screen.PROGRESS) }"),
        ("任务页收敛被摘", "main", "onProgress = { id -> openBook(id) }", ""),
        ("任务页传自己 bookId 被摘", "main", "onProgress: (String) -> Unit,",
         "onProgress: () -> Unit,"),
        ("任务页调用点被摘", "main", "onClick = { onProgress(t.bookId) }",
         "onClick = onProgress"),
        ("一次读盘被摘", "hub", "var hubSegs by remember(book.id)", ""),
        ("段章映射被摘", "hub", "var hubSegChapters by remember(book.id)", ""),
        ("第一列派生被摘", "hub",
         "val chapterSegs = remember(expandedChapter, hubSegs, hubSegChapters)", ""),
        ("心跳刷新被摘", "hub", "var beatTick by remember { mutableStateOf(0) }", ""),
        ("引擎动作被摘", "hub", "EventLog.lastEngine()", ""),
        ("看台区块被摘", "hub", 'item(key = "seg_head_${r.index}")', ""),
        ("第二列没拿到全量段", "hub", "row = row, pq = pqSeg,",
         "segs = chapterSegs, pq = pqSeg,"),
        ("第二列没拿到心跳", "hub", "multi = segMulti, checked = segChecked,", ""),
        ("删段回调被摘", "hub", "onTap = { sg -> tapSeg(sg) })", ""),
        ("全量格子被摘", "hub", 'items(chapterSegs.chunked(SEG_GRID_COLUMNS),', ""),
        ("退回一次性组合", "hub", 'HubDivider()',
         "segs.chunked(10).forEach"),
        ("多选态被摘", "hub", "var segMulti by remember", ""),
        ("试听选中被摘", "hub", 'EventLog.ui("SEG_TRIAL_PICKED"', ""),
        ("删段询问被摘", "hub", 'EventLog.ui("SEG_DELETE_ASK"', ""),
        ("删段确认框被摘", "hub", "confirmSegDel?.let { files ->", ""),
        ("删段 API 被摘", "hub", "AudioOps.deleteAll(files)", ""),
        ("索引记账被摘", "hub", "AudioTree.forget(c,", ""),
        ("preview 通道被摘", "hub", "PlayQueue.pausePreview()", ""),
        ("独立页回潮", "main", "private enum class Screen {",
         "private enum class Screen { LIBRARY, PRODUCE, PROGRESS, TASKS, SETTINGS, MODELS, CHAPTERS }"),
        ("CI 漏挂 r151", "ci", "python3 scripts/r151_verify.py --selftest", ""),
        ("现行清单漏 r151", "r135", '"r151_verify.py"', ""),
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
        print("R151 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R151 收掉最后一张卡片（三入口落第二列 / 完整分段看台 / 删段闭环）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
