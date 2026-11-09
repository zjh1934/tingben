# -*- coding: utf-8 -*-
"""R127 · 制作任务链自动续跑验证。

守四件事：
1. 用户主动停止的任务不被误标成「中断」而自动重跑；
2. 失败 / 没做完的任务不反复自动重试；
3. 切章规则升级前的旧任务不盲目自动续跑：老书章节表锁 legacy_v2 时可安全
   迁移续跑；用户真的换过规则的书必须手动确认（R138 批次 B）；
4. 模型没齐 / 调音改过 / 书被删 都不自动跑，只有进程被杀或崩溃留下的
   interrupted 任务才自动接上，且必须在 Activity 可见之后启动。

沙盒无 Android SDK，静态绿 ≠ 编译过；真编译由 CI 负责。
用法：python3 scripts/r127_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "resume": APP / "core" / "TaskResume.kt",
    "pipe": core_sources.source(APP),
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "resume": [
        "const val SPLIT_RULE = ",
        "val splitRule: Int = 1",
        '.put("splitRule", t.splitRule)',
        'o.optInt("splitRule", 1)',
        "fun autoResumeBlockReason(c: Context, t: TaskInfo): String?",
        'if (t.phase == "stopped")',
        'if (t.stage in listOf("没完成", "出错了", "已停止"))',
        "if (t.splitRule != SPLIT_RULE)",
        "这本书已从书架删掉，没法续跑",
        "ModelStore.synthesisBlockReason(c)?.let",
        "invalidationReason(c, t)?.let",
    ],
    "pipe": [
        "fun autoResume(c: Context): String?",
        "if (busy) return null",
        'if (t.phase == "done") return null',
        "TaskResume.autoResumeBlockReason(c, t)",
        '"TASK_AUTO_RESUME"',
        "splitRule = TaskResume.SPLIT_RULE",
        'val stopped = t.copy(phase = "stopped"',
        "current = stopped",
        'markInterrupted(if (s in listOf("没完成", "出错了", "已停止")) s else "中断")',
    ],
    "main": [
        "Producer.autoResume(ctx)",
        "LaunchedEffect(Unit)",
    ],
    "ci": ["python3 scripts/r127_verify.py --selftest"],
}

BAN = {
    "pipe": [
        'current?.let { TaskResume.save(c, it.copy(phase = "stopped",',
        'markInterrupted("中断")',
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:64]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:64]))
    # 版本下限：R138 之后章节表落盘，schema 至少 3；回退到 1/2 视为旧口径回潮
    m = re.search(r"const val SPLIT_RULE = (\d+)", f["resume"])
    if not m:
        e.append("切章规则常量没了")
    elif int(m.group(1)) < 3:
        e.append("切章规则版本回退到 %s（旧口径无落盘章节表）" % m.group(1))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "用户停止被当中断自动跑": ("resume", 'if (t.phase == "stopped")'),
        "失败态被反复自动重试": ("resume", 'if (t.stage in listOf("没完成", "出错了", "已停止"))'),
        "旧切章任务被自动续跑": ("resume", "if (t.splitRule != SPLIT_RULE)"),
        "书删了还自动跑": ("resume", "这本书已从书架删掉，没法续跑"),
        "模型没齐也自动跑": ("resume", "ModelStore.synthesisBlockReason(c)?.let"),
        "调音改过也自动跑": ("resume", "invalidationReason(c, t)?.let"),
        "守卫函数被摘掉": ("pipe", "TaskResume.autoResumeBlockReason(c, t)"),
        "新任务不记切章规则": ("pipe", "splitRule = TaskResume.SPLIT_RULE"),
        "停止态不回写内存": ("pipe", "current = stopped"),
        "界面不触发自动续跑": ("main", "Producer.autoResume(ctx)"),
        "切章规则版本回退": ("resume", "const val SPLIT_RULE = %s" %
            re.search(r"const val SPLIT_RULE = (\d+)", f["resume"]).group(1),
            "const val SPLIT_RULE = 2"),
    }
    bad = 0
    for name, case in cuts.items():
        k, needle = case[0], case[1]
        repl = case[2] if len(case) > 2 else "/* removed */"
        x = dict(f)
        # 全局替换：同一守卫串在多处合法出现时，只删一处会漏放真实回退。
        x[k] = f[k].replace(needle, repl)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for old in BAN["pipe"]:
        x = dict(f)
        x["pipe"] = f["pipe"] + "\n// " + old + "\n"
        if not check(x):
            bad += 1
            print("  旧形态注入漏放:", old[:40])
    total = len(cuts) + len(BAN["pipe"])
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R127 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R127 自动续跑守卫 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
