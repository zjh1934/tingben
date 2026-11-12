# -*- coding: utf-8 -*-
"""R149 · 任务入口层 + 开软件续跑确认（R159 重写）。

用户实证（第一份真机日志后的使用感受）：
  · 全本任务失败后「不知道怎么删任务」「任务页只有一个巨大的继续按钮」
  · 「开软件若有没跑完的任务，希望弹窗：查看 / 继续 / 暂时不继续」

R159 用户新实证：「关闭以后重新打开应用，它不一定弹」「弹窗做的很丑，要重新设计」。
查实「不一定弹」有四条，全部修掉：
  ① LaunchedEffect(Unit) 只在首次组合跑一次 → 退后台再回前台不重判
  ② resumeSkipped 用 rememberSaveable 跨实例残留 → 按返回退出再进（进程没被杀）不弹
  ③ hasResumable 把手动停 / 失败 / 模型没齐 / 队列暂停全吞成 null → 静默不弹
  ④ resumeAsk 用普通 remember，转屏时弹窗凭空消失

纠正后按纠正做的：
  · pending() 只要存在未完成任务就返回，由 action 决定弹窗给什么出口；
    「放宽看得见」**不等于**「放宽自动跑」，autoResume 里的守卫原样保留
  · 每次 ON_RESUME 都重判；Activity 重建 = 全新状态 = 重新弹
  · 设置里「打开软件自动续跑」开关（默认关）与弹窗形成闭环：
    开 = 能自动跑的直接跑不弹，关 = 每次回前台问一句
  · 弹窗重设计：圆角卡 + 代码画图标 + 状态胶囊 + 真实进度条 + 整宽主按钮；
    R158b 硬口径，不写教程长句
  · 删任务只删记录 + 出队列，已做好的音频不删，走二次确认
  · 顺手修多任务时「听成品」用全局 taskBookId 跳错书

用法：python3 scripts/r149_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": core_sources.source(APP),
    "main": ui_sources.source(APP),
    "prefs": APP / "core" / "Prefs.kt",
    "icons": APP / "PlayIcons.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "pipe": [
        "enum class ResumeAction { AUTO, QUEUE, VIEW }",
        "data class PendingTask(",
        "fun pending(c: Context): PendingTask?",
        "fun unpauseQueue(c: Context)",
        "TaskResume.autoResumeBlockReason(c, t)",
        "BookStore.list(c).none { it.id == t.bookId }",
        "fun hasResumable(c: Context): TaskInfo? =",
        # 放宽「看得见」不许顺手放宽「自动跑」：守卫必须在 autoResume 里
        '"TASK_AUTO_RESUME_SKIP"',
        '"TASK_AUTO_RESUME"',
    ],
    "main": [
        # ① 每次回前台重判，不是一次性
        "LaunchedEffect(windowVisible) {",
        "Producer.pending(ctx)",
        "RESUME_ASK_SKIP",
        "RESUME_CHECK",
        "RESUME_AUTO",
        "RESUME_ASK_SHOW",
        # R284 · 用户主动点「继续制作」要清掉队列暂停标记，不然排队项永久卡死
        "Producer.unpauseQueue(ctx)",
        "QUEUE_UNPAUSE_BY_CONTINUE",
        # ② 三出口（主按钮 / 查看 / 稍后）
        "RESUME_ASK_PRIMARY",
        'EventLog.ui("RESUME_ASK_VIEW"',
        'EventLog.ui("RESUME_ASK_LATER"',
        # ③ 设置开关与闭环
        "AutoResumeRow(c)",
        "Prefs.autoResumeOnStart(ctx)",
        "Prefs.autoResumeOnStart(c)",
        "Prefs.setAutoResumeOnStart(c, it)",
        "RESUME_SETTING",
        # ④ 重设计：自定义卡 + 图标 + 进度条 + 状态胶囊 + 整宽主按钮
        "private fun ResumeAskDialog(",
        "IconResume(palette.primary",
        "RoundedCornerShape(24.dp)",
        "fillMaxWidth(frac)",
        "coerceIn(0f, 1f)",
        'Modifier.fillMaxWidth().height(46.dp)',
        "Producer.ResumeAction.QUEUE -> \"继续队列\"",
        "Producer.ResumeAction.VIEW -> \"查看任务\"",
        # 任务页老能力守恒
        "var confirmDelete by remember { mutableStateOf<TaskInfo?>(null) }",
        "confirmDelete?.let { t ->",
        'EventLog.ui("TASK_DELETE"',
        '"删除任务"',
        "TaskResume.dequeue(c, t.bookId)",
        "只删《${t.title}》这条任务记录",
        "onAudio = { id -> openBook(id)",
        'EventLog.ui("TASK_RESUME"',
        'EventLog.ui("TASK_PLAY_AUDIO", "kind=${a.source}")',
        '"打开这本书 ›"',
        '"播放 ›"',
    ],
    "prefs": [
        "KEY_AUTO_RESUME",
        "fun autoResumeOnStart(c: Context)",
        "fun setAutoResumeOnStart(c: Context, v: Boolean)",
        "getBoolean(KEY_AUTO_RESUME, false)",
    ],
    "icons": [
        "internal fun IconResume(",
        "drawCircle(tint",
    ],
    "ci": ["python3 scripts/r149_verify.py --selftest"],
    "r135": ['"r149_verify.py"'],
}

BAN = {
    "main": [
        '"继续制作 · 接着已做好的块跑"',
        '"去「我的音频」试听 ›"',
        '"看实时进度 ›"',
        "resumeSkipped",                       # 跨实例残留 → 「不一定弹」
        "onAudio: () -> Unit, onLibrary: () -> Unit",
        "onAudio = { openBookCol(taskBookId, 2)",
        'Text("看章节 ›", fontSize = 12.sp, color = Primary)',
        # R158b · 教程不进 UI
        "接着跑不会重算",
        "也可以先去任务页看看",
    ],
}


def code_only(s):
    """BAN 只判代码，不判注释 —— 注释里复盘旧 bug 是给我看的，不是给编译器的。"""
    return "\n".join(l for l in s.splitlines()
                     if not l.strip().startswith(("//", "/*", "*", " */")))


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def pending_action(busy, paused, queue, active_phase, block_reason, book_exists):
    """复刻 Producer.pending 的分支顺序（R284 迁移）。返回 (action, 命中哪条)。

    R284 · 真机快照「队列 1 项 · 暂停=true」+ active stopped 385/26678：
    旧实现队列非空就返回 done/total 皆 0 的排队项，把真正在做的那本藏掉。
    现在队列与未完成的本书并存时以 active 为弹窗主体；paused 或守卫命中
    一律 VIEW，自动续跑守卫一条没放宽。
    """
    if busy:
        return None, "正在制作"
    live = active_phase not in (None, "done") and book_exists
    if queue:
        if live:
            if paused:
                return "VIEW", "队列暂停 → 查看任务（用户点继续再续那本）"
            if block_reason:
                return "VIEW", "守卫拦下：" + block_reason
            return "AUTO", "排队中 · 继续制作"
        return ("QUEUE" if paused else "AUTO"), ("队列已暂停" if paused else "排队中")
    if active_phase is None:
        return None, "没有未完成任务"
    if active_phase == "done":
        return None, "已完成"
    if not book_exists:
        return None, "书已删"
    if block_reason:
        return "VIEW", "守卫拦下：" + block_reason
    return "AUTO", "崩溃中断可续跑"


def logic_cases(f):
    bad = []
    # name, busy, paused, queue, phase, block, book, want_action
    cases = [
        ("崩溃中断 → 继续制作", False, False, [], "interrupted", None, True, "AUTO"),
        # R284 · 旧期望 AUTO 是「队列非空就绕过守卫」，现按现状收紧为 VIEW
        ("队列里有待做 + 手动停 → 查看任务", False, False, ["A"], "stopped", "手动停", True, "VIEW"),
        # R284 · 旧期望 QUEUE 会拿 0/0 排队项盖住 active 真进度
        ("队列暂停 + 崩溃中断 → 查看任务", False, True, ["A"], "interrupted", None, True, "VIEW"),
        ("手动停止 → 查看任务", False, False, [], "stopped", "上次是你手动停的", True, "VIEW"),
        ("失败中断 → 查看任务", False, False, [], "interrupted", "没做完", True, "VIEW"),
        ("模型没齐 → 查看任务", False, False, [], "interrupted", "模型没齐", True, "VIEW"),
        ("切章规则变过 → 查看任务", False, False, [], "interrupted", "分章规则变过", True, "VIEW"),
        ("书已删 → 不弹", False, False, [], "interrupted", None, False, None),
        ("正在制作 → 不弹", True, False, ["A"], "interrupted", None, True, None),
        ("任务已完成 → 不弹", False, False, [], "done", None, True, None),
        ("啥都没有 → 不弹", False, False, [], None, None, True, None),
        # R284 新增：诊断快照原场景与两个边界
        ("快照：暂停队列 + stopped 385/26678", False, True, ["A"], "stopped", "手动停", True, "VIEW"),
        ("只有排队项无本书 → 仍留队列出口", False, True, ["A"], None, None, True, "QUEUE"),
        ("书已删但队列在 → 不拿旧任务盖队列", False, False, ["A"], "interrupted", None, False, "AUTO"),
    ]
    for name, busy, paused, q, phase, block, book, want in cases:
        got, why = pending_action(busy, paused, q, phase, block, book)
        if got != want:
            bad.append("%s: action=%s 期望 %s（%s）" % (name, got, want, why))
        else:
            print("  %-24s → %s" % (name, why))

    # 「不一定弹」的四个根因不许回潮
    # 一次性检查的判据由下面「没跟随 ON_RESUME 重判」覆盖，不重复判。
    if "resumeSkipped" in code_only(f["main"]):
        bad.append("resumeSkipped 跨实例记忆回潮（按返回退出再进会不弹）")
    if "LaunchedEffect(windowVisible)" not in f["main"]:
        bad.append("没跟随 ON_RESUME 重判")

    # 放宽「看得见」不许顺手放宽「自动跑」
    ar = f["pipe"][f["pipe"].find("fun autoResume(c: Context)"):]
    if "autoResumeBlockReason" not in ar:
        bad.append("autoResume 守卫被摘：手动停的任务会被自动跑起来")
    if "TASK_AUTO_RESUME_SKIP" not in ar:
        bad.append("自动续跑被拦时不写日志，真机排查不到原因")

    # 弹窗三出口 + 重设计要素
    for b in ("RESUME_ASK_PRIMARY", "RESUME_ASK_VIEW", "RESUME_ASK_LATER"):
        if b not in f["main"]:
            bad.append("弹窗缺出口：" + b)
    dlg = f["main"][f["main"].find("private fun ResumeAskDialog("):]
    for needle in ("IconResume(", "RoundedCornerShape(24.dp)",
                   "fillMaxWidth(frac)", "coerceIn(0f, 1f)"):
        if needle not in dlg:
            bad.append("弹窗重设计要素被摘：" + needle)
    # R158b · 弹窗里不许出现教程长句
    for t in re.findall(r'Text\(\s*"([^"]*)"', dlg):
        if len(re.findall(r"[\u4e00-\u9fff]", t)) >= 16:
            bad.append("弹窗又塞回教程式长文案：" + t[:30])

    # 删任务必须二次确认
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
            if s in code_only(f[key]):
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    # R284 · 清暂停必须内存态 + 落盘态一起清：pumpQueue 两处都判，
    # 只清一个等于排队项永远起不来。放 check 里，注入才能被拦下。
    uq = f["pipe"][f["pipe"].find("fun unpauseQueue(c: Context)"):]
    uq = uq[:uq.find("}")] if "}" in uq else uq
    if "queuePaused = false" not in uq or "TaskResume.setQueuePaused(c, false)" not in uq:
        e.append("unpauseQueue 没同时清内存态与落盘态，pumpQueue 仍会被挡")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("pending 被摘", "pipe", "fun pending(c: Context): PendingTask?", ""),
        ("动作枚举被摘", "pipe", "enum class ResumeAction { AUTO, QUEUE, VIEW }", ""),
        ("守卫被摘：手动停也能自动跑", "pipe", '"TASK_AUTO_RESUME_SKIP"', ""),
        ("书删了也弹", "pipe", "BookStore.list(c).none { it.id == t.bookId }", "false"),
        ("回前台重判被摘", "main", "LaunchedEffect(windowVisible) {",
         "LaunchedEffect(Unit) {"),
        ("跨实例记忆回潮", "main", "LaunchedEffect(windowVisible) {",
         "LaunchedEffect(windowVisible) { var resumeSkipped = true"),
        ("待处理查询被摘", "main", "Producer.pending(ctx)", "null"),
        ("开关被摘", "main", "Prefs.autoResumeOnStart(ctx)", "false"),
        ("开关落盘被摘", "prefs", "fun setAutoResumeOnStart(c: Context, v: Boolean)", ""),
        ("开关默认变开", "prefs", "getBoolean(KEY_AUTO_RESUME, false)",
         "getBoolean(KEY_AUTO_RESUME, true)"),
        ("R284 主动继续不清暂停", "main", "Producer.unpauseQueue(ctx)", ""),
        ("R284 清暂停只改磁盘不动内存", "pipe",
         "fun unpauseQueue(c: Context) {\n        queuePaused = false\n        TaskResume.setQueuePaused(c, false)",
         "fun unpauseQueue(c: Context) {\n        TaskResume.setQueuePaused(c, false)"),
        ("主出口被摘", "main", 'EventLog.ui("RESUME_ASK_PRIMARY"', ""),
        ("查看任务出口被摘", "main", 'EventLog.ui("RESUME_ASK_VIEW"', ""),
        ("稍后出口被摘", "main", 'EventLog.ui("RESUME_ASK_LATER"', ""),
        ("弹窗组件被摘", "main", "private fun ResumeAskDialog(", ""),
        ("代码图标被摘", "main", "IconResume(palette.primary", ""),
        ("图标定义被摘", "icons", "internal fun IconResume(", ""),
        ("进度条被摘", "main", "fillMaxWidth(frac)", ""),
        ("三态按钮被摘", "main", 'Producer.ResumeAction.VIEW -> "查看任务"', ""),
        ("教程长句回潮", "main", '"稍后"',
         '"稍后 · 接着跑不会重算已做好的部分，也可以先去任务页看看"'),
        ("巨大按钮回潮", "main", '"打开这本书 ›"', '"继续制作 · 接着已做好的块跑"'),
        ("删任务被摘", "main", 'EventLog.ui("TASK_DELETE"', ""),
        ("删任务不出队列", "main", "TaskResume.dequeue(c, t.bookId)", ""),
        ("二次确认被摘", "main", "confirmDelete?.let { t ->", ""),
        ("onAudio 又用全局 id", "main", "onAudio = { id -> openBook(id)",
         "onAudio = { openBookCol(taskBookId, 2)"),
        ("过时音频页文案回潮", "main", '"播放 ›"', '"去「我的音频」试听 ›"'),
        ("CI 漏挂 r149", "ci", "python3 scripts/r149_verify.py --selftest", ""),
        ("任务页音频播放日志被摘", "main", 'EventLog.ui("TASK_PLAY_AUDIO"', ""),
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
    print("R149 任务入口层（R159 续跑弹窗 / 开关闭环 / 守卫口径 / 删除任务）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
