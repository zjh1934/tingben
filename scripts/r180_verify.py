#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R180 · 「从装上到听上」核心闭环守门。

用户把 1.1.7 装上了，说先不反馈、核心的东西还没落地，让我自己找活。
实读主链后挑出四件真缺口，不是界面小修：

 A 建书 / 导入成功后屏幕一动不动，书悄悄进了抽屉。
   BookStore.add 本来就返回 Book，两处调用只拿它比 size。现在直接进这本书。
 B 新装用户打开只有输入框，模型缺件提示藏在设置页深处，点合成才撞墙。
   首屏给一行状态 + 「去导入 ›」，模型齐了整行消失；不写教程、不常驻。
 C 播放位置完全不落盘：听几小时的书退出再进得从头拖。
   新增 ListenState（AtomicJson），暂停 / 拖动 / 切曲 / 释放 / 换队列都存，
   播完整章清掉；目录页给「接着听 · mm:ss ›」。
 D 模型没齐时只显示「没开始」，不给出口。目录页可播汇总行给「去导入模型 ›」。

顺带把两处被新代码顶出的假红判据语义化（不是放宽）：
   · shell_semantics.home_is_input 原来用 [^)]* 扫 asHome=true，
     首页回调里多一个右括号就集体假红；改成先定位分支再在后面找。
   · r116 暂停注入原来绑死「fun pause() { 紧跟 abandonFocus()」，
     pause 开头多了 savePos() 后注入静默失效；改成只在函数体里删那一行。

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r180_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "queue": APP / "core/PlayQueue.kt",
    "listen": APP / "core/ListenState.kt",
    "shell": ROOT / "scripts/shell_semantics.py",
    "r116": ROOT / "scripts/r116_verify.py",
    "ci": ROOT / ".github/workflows/ci.yml",
}

MAIN = [
    'val modelMissing = remember(refresh) { ModelStore.missing(ctx).size }',
    'BookStore.add(ctx, r.title, r.text)',
    'EventLog.ui("BOOK_IMPORT_OPEN", "id=${added.id}")',
    'nav.push(Screen.CHAPTERS)',
    'onAdded = { id ->\n                        refresh++\n                        bookId = id',
    'onAdded(added.id)',
    'onAdded: (String) -> Unit',
    'modelMissing: Int = 0',
    'onModels: () -> Unit = {}',
    'if (modelMissing > 0)',
    'Text("去导入 ›"',
    'onOpenModels = {',
]
MAIN_BAN = [
    'BookStore.list(c).size > before',   # 建书不再只比数量
    'onAdded = { refresh++ }',            # 首页不再只刷书架
]
HUB = [
    'val resumePos = remember(refresh, finishedChapters)',
    'ListenState.get(c, "chapter:${book.id}")',
    'val needModels = remember(refresh) { ModelStore.missing(c).isNotEmpty() }',
    'fun playBook(atId: String?, fromMs: Long?)',
    'if (resumePos != null && resumePos.positionMs > 5000L) {',
    'Text("接着听 · %d:%02d ›".format(',
    'EventLog.ui("HUB_RESUME_LISTEN"',
    'Text("去导入模型 ›"',
    'onOpenModels: () -> Unit = {}',
    'Text("按顺序播放"',
]
QUEUE = [
    'private fun savePos()',
    'ListenState.save(ctx, queueId, tracks[i].id, ms, tracks[i].label)',
    'fun pause() {\n        savePos()',
    'ListenState.save(ctx, queueId, tracks[index].id, ms, tracks[index].label)',
    'savePos()   // 切到下一曲前先记下上一曲听到哪',
    'appCtx?.let { ListenState.clear(it, queueId) }',
    'savePos()   // 换队列 / 退出前，先把旧队列的位置留住',
]
LISTEN = [
    'object ListenState',
    'fun get(c: Context, queueId: String): Pos?',
    'fun save(c: Context, queueId: String, trackId: String, positionMs: Long, label: String)',
    'fun clear(c: Context, queueId: String)',
    'is AtomicJson.Read.Corrupt -> return   // 坏了绝不覆写',
    'AtomicJson.write(f, o.toString())',
    '试听队列',
]
SEMANTIC = {
    # 判据解绑：不能再依赖 [^)]*，也不能再绑死 pause 的前一行
    "shell": ['m = re.search(r"Screen\\.LIBRARY\\s*->\\s*InputReadScreen\\(", main)',
              '"asHome = true" in main[m.start():m.start() + 900]'],
    "r116": ['def _drop_abandon_in_pause',
             "t.find('    fun pause() {')"],
}


def checks(f):
    e = []
    if not (APP / "core/ListenState.kt").is_file():
        e.append("ListenState.kt 缺失（播放位置没落盘）")
        return e
    for key, needles in (("main", MAIN), ("hub", HUB), ("queue", QUEUE),
                         ("listen", LISTEN)):
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:62]))
    for s in MAIN_BAN:
        if s in f["main"]:
            e.append("旧形态回潮: " + s[:56])
    for key, needles in SEMANTIC.items():
        for s in needles:
            if s not in f[key]:
                e.append("判据没语义化[%s]: %s" % (key, s[:56]))
    # 定义 + 暂停 + 切曲前 + 换队列前，共 4 处（拖动走 ListenState.save 直写）
    if f["queue"].count("savePos()") < 4:
        e.append("播放位置落盘点不齐（暂停/切曲/释放/换队列）")
    if "python3 scripts/r180_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r180_verify --selftest")
    return e


def selftest():
    base = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
            for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("导入后不进目录页", "main",
         lambda t: t.replace('                            EventLog.ui("BOOK_IMPORT_OPEN", "id=${added.id}")',
                             '                            EventLog.ui("BOOK_IMPORT_OPEN", "none")')
         .replace('                            nav.push(Screen.CHAPTERS)\n', '')),
        ("建书回退到只刷书架", "main",
         lambda t: t.replace('onAdded = { id ->\n                        refresh++\n                        bookId = id\n                        taskBookId = id\n                        nav.push(Screen.CHAPTERS)\n                    }',
                             'onAdded = { _ -> refresh++ }')),
        ("首屏模型入口被摘", "main",
         lambda t: t.replace('if (modelMissing > 0) {', 'if (false) {')),
        ("首屏模型计数被摘", "main",
         lambda t: t.replace('val modelMissing = remember(refresh) { ModelStore.missing(ctx).size }',
                             'val modelMissing = 0')),
        ("建书不再传 id", "main",
         lambda t: t.replace('onAdded(added.id)', 'onAdded()')),
        ("接着听入口被摘", "hub",
         lambda t: t.replace('if (resumePos != null && resumePos.positionMs > 5000L) {',
                             'if (false) {')),
        ("断点位置不读盘", "hub",
         lambda t: t.replace('if (finishedChapters > 0) ListenState.get(c, "chapter:${book.id}") else null',
                             'null')),
        ("模型缺件出口被摘", "hub",
         lambda t: t.replace('Text("去导入模型 ›"', 'Text("按顺序播放"')),
        ("播放队列两入口不再共用构造", "hub",
         lambda t: t.replace('fun playBook(atId: String?, fromMs: Long?) {',
                             'fun unused(atId: String?, fromMs: Long?) {')),
        ("暂停不存位置", "queue",
         lambda t: t.replace('fun pause() {\n        savePos()', 'fun pause() {')),
        ("播完不清位置", "queue",
         lambda t: t.replace('appCtx?.let { ListenState.clear(it, queueId) }', '')),
        ("ListenState 损坏仍覆写", "listen",
         lambda t: t.replace('is AtomicJson.Read.Corrupt -> return   // 坏了绝不覆写',
                             'is AtomicJson.Read.Corrupt -> JSONObject()')),
        ("ListenState 不写盘", "listen",
         lambda t: t.replace('AtomicJson.write(f, o.toString())', 'Unit')),
        ("壳层判据退回 [^)]*", "shell",
         lambda t: t.replace('m = re.search(r"Screen\\.LIBRARY\\s*->\\s*InputReadScreen\\(", main)',
                             'm = re.search(r"Screen\\.LIBRARY\\s*->\\s*InputReadScreen\\([^)]*", main)')),
        ("r116 注入又绑前一行", "r116",
         lambda t: t.replace('def _drop_abandon_in_pause(t: str) -> str:',
                             'def _unused(t: str) -> str:')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r180_verify.py --selftest", "")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not checks(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
         for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R180 核心闭环")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R180 核心闭环：建书直达 / 首屏模型引导 / 断点续听 / 缺件出口：PASS")
