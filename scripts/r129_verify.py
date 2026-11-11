# -*- coding: utf-8 -*-
"""R129 · 制作任务队列验证。

守：忙时入队不拒绝 / 完成才自动接下一个 / 停止暂停整个队列 /
失败不自动跳过 / 暂停状态跨进程落盘 / 开软件队列优先自动跑 / 界面看得到队列。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r129_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "resume": APP / "core" / "TaskResume.kt",
    "pipe": core_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
    "r114": ROOT / "scripts/r114_verify.py",
}

POS = {
    "resume": [
        'private fun queueFile(c: Context) = File(c.filesDir, "queue.json")',
        "fun queue(c: Context): List<TaskInfo>",
        "fun enqueue(c: Context, t: TaskInfo)",
        "fun dequeue(c: Context, bookId: String)",
        "fun queueSize(c: Context): Int = queue(c).size",
        "fun queuePaused(c: Context): Boolean",
        "fun setQueuePaused(c: Context, v: Boolean)",
        'phase = "queued"',
    ],
    "pipe": [
        "@Volatile private var queuePaused = false",
        "fun queueState(c: Context): Pair<Int, Boolean>",
        "fun enqueueOrStart(c: Context, book: Book",
        "private fun pumpQueue(c: Context): String?",
        "fun resumeQueue(c: Context): String?",
        "scope.launch { pumpQueue(c.applicationContext) }",
        'if (p.stage == "完成" || current?.phase == "done")',
        "queuePaused = true",
        "TaskResume.setQueuePaused(c.applicationContext, true)",
        "QUEUE_PAUSE",
        "QUEUE_SKIP",
        "QUEUE_RESUME",
        "val q = pumpQueue(c.applicationContext)",
    ],
    "hub": [
        "Producer.enqueueOrStart(c, book, selected, null, Prefs.defaultPerf(c))",
    ],
    "main": [
        'item(key = "tasks_queue")',
        "TaskResume.queue(c)",
        "Producer.queueState(c)",
        # R243 · 按钮砍掉，只留暂停提示；恢复走自动续跑，底层 resumeQueue 仍在 pipe
        "队列已暂停",
        "nav.push(Screen.TASKS)",
    ],
    "ci": ["python3 scripts/r129_verify.py --selftest"],
    "r114": ["基准树只建一次", "f.write_text(s, encoding='utf-8')"],
}

BAN = {
    "hub": [
        "还有一本在制作，等它做完或先停止，一次只跑一本",
        "Producer.start(c, book, selected, directRead = true, config = ProduceConfig())",
    ],
    "main": [
        "继续队列 ›",
        'Text("继续队列"',
        'title = "我的书"',
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:66]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:66]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "队列文件被摘": ("resume", "fun enqueue(c: Context, t: TaskInfo)"),
        "暂停不落盘": ("resume", "fun setQueuePaused(c: Context, v: Boolean)"),
        "入队口被摘": ("pipe", "fun enqueueOrStart(c: Context, book: Book"),
        "调度器被摘": ("pipe", "private fun pumpQueue(c: Context): String?"),
        "完成后不泵下一个": ("pipe", 'if (p.stage == "完成" || current?.phase == "done")'),
        "停止不暂停队列": ("pipe", "QUEUE_PAUSE"),
        "开软件不优先跑队列": ("pipe", "val q = pumpQueue(c.applicationContext)"),
        "恢复队列入口被摘": ("pipe", "fun resumeQueue(c: Context): String?"),
        "目录页改回拒绝": ("hub", "Producer.enqueueOrStart(c, book, selected, null, Prefs.defaultPerf(c))"),
        "任务页队列卡被摘": ("main", 'item(key = "tasks_queue")'),
        "r114 回到每例复制整树": ("r114", "基准树只建一次"),
        "r114 不还原文件": ("r114", "f.write_text(s, encoding='utf-8')"),
        "主页任务回目录页": ("main", "nav.push(Screen.TASKS)"),
    }
    bad = 0
    for name, (k, needle) in cuts.items():
        x = dict(f)
        # 全局替换：QUEUE_PAUSE 这类标记在 finally 与 pumpQueue 里各出现一次，
        # 只删一处时另一处仍能让检查通过，会漏放真实回退。
        x[k] = f[k].replace(needle, "/* removed */")
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for k, olds in BAN.items():
        for old in olds:
            x = dict(f)
            x[k] = f[k] + chr(10) + "// " + old.splitlines()[0] + chr(10)
            if not check(x):
                bad += 1
                print("  旧形态注入漏放:", old[:44])
    total = len(cuts) + sum(len(v) for v in BAN.values())
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R129 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R129 任务队列 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
