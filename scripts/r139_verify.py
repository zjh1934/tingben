# -*- coding: utf-8 -*-
"""R139 · 批次 C「主线程 IO / 内存上限 / 前台服务时序」验证。

四件落地：
  C1 制作准备页组合期不再同步读整本 + 切块（改 IO 异步 + 进度圈 + 错误态）
  C2 「清理分段音频」遍历删盘切 IO
  C3 文本字符数上限（BookStore / Importer / ChapterPlan 三道）+ 三处入口错误提示
  C4 自动续跑等 ON_RESUME 再起 FGS；FGS 起不来就停任务并告知，不静默假装跑

用法：python3 scripts/r139_verify.py [--selftest]
"""
import core_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "pipeline": core_sources.source(APP),
    "importer": APP / "core" / "Importer.kt",
    "plan": APP / "core" / "ChapterPlan.kt",
    "service": APP / "ProduceService.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "main": [
        "private data class ProduceLoad(",
        "var loadDone by remember(id, refresh)",
        "LaunchedEffect(id, refresh) {",
        "withContext(Dispatchers.IO)",
        "CircularProgressIndicator()",
        "loadError ?: \"这本书不在了\"",
        "val addRes = if (r == null) null else runCatching {",
        "withContext(Dispatchers.Main)",
        "val inputScope = rememberCoroutineScope()",
        "inputScope.launch {",
        "val clearScope = rememberCoroutineScope()",
        "clearScope.launch {",
        "androidx.lifecycle.compose.LocalLifecycleOwner.current",
        "LifecycleEventObserver",
        "Lifecycle.Event.ON_RESUME",
        # R159 · 续跑检查改成跟随 ON_RESUME 重判（原 snapshotFlow.first 只看一次，
        # 是「关闭后重新打开不一定弹」的根因之一）。守的是「等可见才跑」这件事。
        "LaunchedEffect(windowVisible) {",
        "LaunchedEffect(Unit)",
        "Producer.autoResume(ctx)",
        # R157 · BookStore.add 新增第 4 个 origin 参数（输入框建的书要单独归类导出），
        # 这里改成前缀判据，不再绑死参数个数；守恒的是「粘贴/分享会建书」这件事。
        "autoBookTitle(snapshot, \"粘贴的文字\")",
        "autoBookTitle(text, \"分享的文字\")",
    ],
    "hub": [
        "var loadError by remember(id, refresh, planTick)",
        "loadError ?: \"这本书不在了\"",
        "runCatching { ChapterPlan.refresh(c, book) }",
    ],
    "pipeline": [
        # R175 · 这两条也进 POS：selftest 的注入由 check() 判定，
        # 只放在 logic_cases 里抓不到「blocked 阶段被摘」。
        "abortBeforeStart(why)",
        'phase = "blocked"',
        "const val MAX_TEXT_CHARS = 6_000_000",
        "if (text.length > MAX_TEXT_CHARS)",
        "@Volatile var lastStartError: String? = null",
        "if (!ProduceService.begin(c, book.title)) {",
        "PRODUCE_FGS_BLOCKED",
        "系统没让制作服务启动，任务没有开始",
        "lastStartError = null\n        if (busy) return null",
        "if (lastStartError != null) return lastStartError",
    ],
    "importer": [
        "private fun checkText(text: String)",
        "BookStore.MAX_TEXT_CHARS",
        "checkText(r.first)",
        "checkText(text)",
    ],
    "plan": [
        "if (text.length > BookStore.MAX_TEXT_CHARS)",
    ],
    "service": [
        "fun begin(c: Context, title: String): Boolean",
        "返回 false = 系统不让起前台服务",
        "startForeground 失败，停止本次制作",
        "Producer.stop()\n            stopSelf()",
        "return START_NOT_STICKY",
    ],
    "ci": ["python3 scripts/r139_verify.py --selftest"],
}

# 旧形态零回潮
BAN = {
    "main": [
        "remember(book, refresh) { book?.let { ChapterPlan.resolve",
        "val split = remember(book, refresh)",
        "work.walkTopDown().filter { it.isFile && it.name.endsWith(\".wav\") }\n                            .forEach",
        "任务仍在内存协程里跑",
    ],
    "service": [
        "前台服务起不来，任务仍在内存协程里跑",
        "fun begin(c: Context, title: String) {",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace")
            for k, p in FILES.items()}


def logic_cases(f):
    bad = []
    m = re.search(r"const val MAX_TEXT_CHARS = ([\d_]+)", f["pipeline"])
    if not m:
        bad.append("没有文本字符数上限常量")
        return bad
    limit = int(m.group(1).replace("_", ""))
    if not (1_000_000 <= limit <= 12_000_000):
        bad.append("上限 %d 不在合理区间（100万~1200万字符）" % limit)
    # 上限下的最坏内存：UTF-16 约 2 字节/字符，切块平均 425 字符/段
    heap_mb = limit * 2 / 1024 / 1024
    chunks = limit // 425
    if heap_mb > 24 or chunks > 20000:
        bad.append("上限仍会造成内存放大：约 %.1fMB / %d 段" % (heap_mb, chunks))
    else:
        print("内存用例：上限 %d 字 → UTF-16 约 %.1fMB、约 %d 段，安全"
              % (limit, heap_mb, chunks))
    # 三道闸门都在
    for name, key, needle in [
        ("BookStore.add", "pipeline", "if (text.length > MAX_TEXT_CHARS)"),
        ("Importer", "importer", "private fun checkText(text: String)"),
        ("ChapterPlan.resolve", "plan",
         "if (text.length > BookStore.MAX_TEXT_CHARS)"),
    ]:
        if needle not in f[key]:
            bad.append("文本上限缺一道闸门：" + name)
    # FGS 失败路径必须「停任务」，不能只记日志
    bm = re.search(r"fun begin\(c: Context, title: String\): Boolean \{(.*?)\n        \}",
                   f["service"], re.S)
    if not bm or "false" not in bm.group(1) or "true" not in bm.group(1):
        bad.append("begin() 缺少「成功 true / 失败 false」两条返回路径")
    if "if (!ProduceService.begin(c, book.title))" not in f["pipeline"]:
        bad.append("Producer 没有消费 begin() 的失败返回值")
    # R175 · FGS 失败不再只设 _state，而是经 abortBeforeStart 落 blocked 阶段，
    # 避免 finally 误标「意外中断」反复自动重试。
    if "abortBeforeStart(why)" not in f["pipeline"] or \
            'phase = "blocked"' not in f["pipeline"]:
        bad.append("FGS 失败后没有把任务停在 blocked / 没开始")
    if "scope.launch {" not in f["pipeline"]:
        bad.append("制作协程入口丢了")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:72]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:72]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    logic_bad = len(logic_cases(f))
    orig = dict(f)
    cuts = [
        ("异步加载被摘", "main", "LaunchedEffect(id, refresh) {", ""),
        ("IO 切换被摘", "main", "withContext(Dispatchers.IO)", ""),
        ("加载进度圈被摘", "main", "CircularProgressIndicator()", ""),
        ("清理切 IO 被摘", "main", "clearScope.launch {", ""),
        ("文本上限常量被摘", "pipeline", "const val MAX_TEXT_CHARS = 6_000_000",
         "const val MAX_TEXT_CHARS = 0"),
        ("add 校验被摘", "pipeline", "if (text.length > MAX_TEXT_CHARS)", ""),
        ("Importer 校验被摘", "importer", "private fun checkText(text: String)", ""),
        ("ChapterPlan 校验被摘", "plan",
         "if (text.length > BookStore.MAX_TEXT_CHARS)", ""),
        ("ON_RESUME 等待被摘", "main", "LaunchedEffect(windowVisible) {", ""),
        ("生命周期观察被摘", "main", "Lifecycle.Event.ON_RESUME", ""),
        ("begin 不再返回 Boolean", "service",
         "fun begin(c: Context, title: String): Boolean",
         "fun begin(c: Context, title: String)"),
        ("FGS 失败不再停任务", "pipeline", "if (!ProduceService.begin(c, book.title)) {", ""),
        ("blocked 阶段被摘", "pipeline", 'phase = "blocked"', 'phase = "stopped"'),
        ("失败原因透出被摘", "pipeline", "PRODUCE_FGS_BLOCKED", ""),
        ("服务内 startForeground 保护被摘", "service",
         "startForeground 失败，停止本次制作", ""),
        ("CI 漏挂 r139", "ci", "python3 scripts/r139_verify.py --selftest", ""),
    ]
    bad = 0
    try:
        for name, key, needle, repl in cuts:
            x = dict(f)
            x[key] = f[key].replace(needle, repl)
            if not check(x):
                bad += 1
                print("  反向注入漏放:", name)
    finally:
        # R141 · 反向注入全程只改内存 dict（check(x) 不读盘），原来每跑一次
        # selftest 就把全部文件无条件写回 15 遍，纯属空转。改成「确实变了才写」，
        # 保留防误改的保险，但去掉 100+ 次无谓写盘。
        for k, v in orig.items():
            try:
                if FILES[k].read_text(encoding="utf-8", errors="replace") != v:
                    FILES[k].write_text(v, encoding="utf-8")
            except (OSError, UnicodeError):
                FILES[k].write_text(v, encoding="utf-8")
    total = len(cuts)
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R139 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R139 批次C：主线程 IO / 内存上限 / FGS 时序与失败可见：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
