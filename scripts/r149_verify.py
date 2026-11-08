# -*- coding: utf-8 -*-
"""R149 · G 批「任务入口层」守门：任务页重做 + 开软件确认弹窗。

用户实证（第一份真机日志后的使用感受）：
  · 全本任务失败后「不知道怎么删任务」「任务页只有一个巨大的继续按钮」
  · 「开软件若有没跑完的任务，希望弹窗：查看 / 继续 / 暂时不继续」

纠正后按纠正做的：
  · 弹窗只由 Producer.hasResumable 放行过的任务触发；手动停 / 失败 /
    模型没齐 / 切章口径变过 / 书删了 / 队列暂停 一律不弹也不跑
  · 「暂时不继续」本次冷启动不再反复弹（rememberSaveable）
  · 删任务只删记录 + 出队列，已做好的音频不删，走二次确认
  · 顺手修多任务时「听成品」用全局 taskBookId 跳错书

用法：python3 scripts/r149_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": APP / "core" / "Pipeline.kt",
    "main": APP / "MainActivity.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "pipe": [
        "fun hasResumable(c: Context): TaskInfo?",
        "if (TaskResume.queuePaused(c)) { queuePaused = true; return null }",
        "if (TaskResume.autoResumeBlockReason(c, t) != null) return null",
        "BookStore.list(c).none { it.id == t.bookId }",
    ],
    "main": [
        "var resumeAsk by remember { mutableStateOf<TaskInfo?>(null) }",
        "var resumeSkipped by rememberSaveable { mutableStateOf(false) }",
        "Producer.hasResumable(ctx)",
        'EventLog.ui("RESUME_ASK_SHOW"',
        'EventLog.ui("RESUME_ASK_VIEW"',
        'EventLog.ui("RESUME_ASK_CONTINUE"',
        'EventLog.ui("RESUME_ASK_LATER"',
        "private fun ResumeAskDialog(",
        '"暂时不继续"', '"查看任务 ›"', '"继续制作"',
        "var confirmDelete by remember { mutableStateOf<TaskInfo?>(null) }",
        "confirmDelete?.let { t ->",
        'EventLog.ui("TASK_DELETE"',
        '"删除任务"',
        "TaskResume.dequeue(c, t.bookId)",
        "只删《${t.title}》这条任务记录",
        "onAudio: (String) -> Unit",
        "onAudio = { id -> openBookCol(id, 2)",
        'EventLog.ui("TASK_RESUME"',
        'EventLog.ui("TASK_PLAY"',
        '"打开这本书 ›"',
        '"听成品 ›"',
    ],
    "ci": ["python3 scripts/r149_verify.py --selftest"],
    "r135": ['"r149_verify.py"'],
}

BAN = {
    "main": [
        '"继续制作 · 接着已做好的块跑"',
        '"去「我的音频」试听 ›"',
        '"看实时进度 ›"',
        "snapshotFlow { windowVisible }.first { it }\n        val hint = Producer.autoResume(ctx)",
        "onAudio: () -> Unit, onLibrary: () -> Unit",
        "onAudio = { openBookCol(taskBookId, 2)",
        "Text(\"看章节 ›\", fontSize = 12.sp, color = Primary)",
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


def has_resumable(busy, paused, queue, active_phase, block_reason, book_exists):
    """复刻 Producer.hasResumable 的分支顺序。返回 (弹不弹, 命中哪条)。"""
    if busy:
        return False, "busy"
    if paused:
        return False, "队列暂停"
    if queue:
        return True, "队列待做"
    if active_phase is None:
        return False, "没有未完成任务"
    if active_phase == "done":
        return False, "已完成"
    if block_reason:
        return False, "守卫拦下：" + block_reason
    if not book_exists:
        return False, "书已删"
    return True, "崩溃中断可续跑"


def logic_cases(f):
    bad = []
    cases = [
        # name, busy, paused, queue, phase, block, book, want
        ("崩溃中断 → 弹", False, False, [], "interrupted", None, True, True),
        ("队列里有待做 → 弹", False, False, ["A"], "stopped", "手动停", True, True),
        ("手动停止 → 不弹", False, False, [], "stopped", "上次是你手动停的", True, False),
        ("失败中断 → 不弹", False, False, [], "interrupted", "没做完", True, False),
        ("模型没齐 → 不弹", False, False, [], "interrupted", "模型没齐", True, False),
        ("切章规则变过 → 不弹", False, False, [], "interrupted", "分章规则变过", True, False),
        ("书已删 → 不弹", False, False, [], "interrupted", None, False, False),
        ("队列暂停 → 不弹", False, True, ["A"], "interrupted", None, True, False),
        ("正在制作 → 不弹", True, False, ["A"], "interrupted", None, True, False),
        ("任务已完成 → 不弹", False, False, [], "done", None, True, False),
        ("啥都没有 → 不弹", False, False, [], None, None, True, False),
    ]
    for name, busy, paused, q, phase, block, book, want in cases:
        got, why = has_resumable(busy, paused, q, phase, block, book)
        if got != want:
            bad.append("%s: 弹=%s 期望 %s（%s）" % (name, got, want, why))
        else:
            print("  %-22s → %s" % (name, why))

    # 「暂时不继续」必须跨旋转保存，否则转屏又弹一次
    if "var resumeSkipped by remember { mutableStateOf(false) }" in f["main"]:
        bad.append("resumeSkipped 没用 rememberSaveable，转屏会重复弹")

    # 弹窗必须三个出口都在，少一个用户就被迫做决定
    for b in ('RESUME_ASK_VIEW', 'RESUME_ASK_CONTINUE', 'RESUME_ASK_LATER'):
        if b not in f["main"]:
            bad.append("弹窗缺出口：" + b)

    # 删除不能是「直接删」，必须有二次确认
    if "AlertDialog(" not in f["main"] or "confirmDelete?.let" not in f["main"]:
        bad.append("删任务没走二次确认")

    print("守卫逻辑用例 %s" % ("通过" if not bad else "有失败"))
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
        ("hasResumable 被摘", "pipe", "fun hasResumable(c: Context): TaskInfo?", ""),
        ("守卫被摘：手动停也会弹", "pipe",
         "if (TaskResume.autoResumeBlockReason(c, t) != null) return null", ""),
        ("守卫被摘：书删了也弹", "pipe",
         "BookStore.list(c).none { it.id == t.bookId }", "false"),
        ("队列暂停不同步内存", "pipe",
         "if (TaskResume.queuePaused(c)) { queuePaused = true; return null }", ""),
        ("恢复旧自动跑", "main", "Producer.hasResumable(ctx)", "Producer.autoResume(ctx)"),
        ("弹窗 state 被摘", "main", "var resumeAsk by remember { mutableStateOf<TaskInfo?>(null) }", ""),
        ("查看任务出口被摘", "main", 'EventLog.ui("RESUME_ASK_VIEW"', ""),
        ("暂时不继续出口被摘", "main", 'EventLog.ui("RESUME_ASK_LATER"', ""),
        ("继续出口被摘", "main", 'EventLog.ui("RESUME_ASK_CONTINUE"', ""),
        ("弹窗组件被摘", "main", "private fun ResumeAskDialog(", ""),
        ("转屏重复弹", "main", "var resumeSkipped by rememberSaveable { mutableStateOf(false) }",
         "var resumeSkipped by remember { mutableStateOf(false) }"),
        ("巨大按钮回潮", "main", '"打开这本书 ›"', '"继续制作 · 接着已做好的块跑"'),
        ("删任务被摘", "main", 'EventLog.ui("TASK_DELETE"', ""),
        ("删任务不出队列", "main", "TaskResume.dequeue(c, t.bookId)", ""),
        ("二次确认被摘", "main", "confirmDelete?.let { t ->", ""),
        ("onAudio 又用全局 id", "main", "onAudio = { id -> openBookCol(id, 2)",
         "onAudio = { openBookCol(taskBookId, 2)"),
        ("过时音频页文案回潮", "main", '"听成品 ›"', '"去「我的音频」试听 ›"'),
        ("CI 漏挂 r149", "ci", "python3 scripts/r149_verify.py --selftest", ""),
        ("现行清单漏 r149", "r135", '"r149_verify.py"', ""),
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
        print("R149 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R149 任务入口层（任务页重做 / 删除任务 / 开软件确认弹窗 / 守卫口径）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
