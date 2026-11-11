#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R219 · 真机反馈批次守门。

  ① 大块读不全 → 回到 110/260 细切，SPLIT_EPOCH 进音色键与续跑判据，
     旧分段绝不按段号复用。
  ② 点进度条滚到正在制作的章；没小段的章点章名不反应。
  ③ 顶栏三按钮右对齐、进度条加高内嵌文字、废话清掉、左侧常驻勾、
     展开头部零留白。
用法：python3 scripts/r219_verify.py [--selftest]
"""
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
CORE = APP / "core"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "hub": rd(APP / "ChapterHub.kt"),
        "split": rd(CORE / "TextSplitter.kt"),
        "cfg": rd(CORE / "ProduceConfig.kt"),
        "producer": rd(CORE / "Producer.kt"),
        "resume": rd(CORE / "TaskResume.kt"),
        "merged": ui_sources.read(ROOT),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    hb, sp, cfg, pr, rs = f["hub"], f["split"], f["cfg"], f["producer"], f["resume"]
    mg = f["merged"]
    # ① 细切与代际防串
    for k in ["const val TARGET_CHARS = 110", "const val SOFT_CAP = 260",
              "const val SPLIT_EPOCH = 3"]:
        if k not in sp: e.append("切分缺: " + k)
    if "TextSplitter.SPLIT_EPOCH.toString()" not in cfg:
        e.append("音色缓存键没带切分代际")
    if 'File(work, "split_stamp.txt").writeText' not in pr:
        e.append("制作没落 split_stamp")
    for k in ['val splitFile = File(w, "split_stamp.txt")',
              "oldSplit != TextSplitter.SPLIT_EPOCH.toString()",
              "切分粒度改过"]:
        if k not in rs: e.append("续跑没拦旧分段: " + k[:50])
    # ② 进度条 / 滚动 / 空章
    for k in ["val chapterListState = rememberLazyListState()",
              "val makingChapter = remember(st.chapterProgress, task, ui.size)",
              "chapterListState.animateScrollToItem(makingChapter)",
              "state = chapterListState",
              '.clickable { scrollToMaking() }',
              "LinearProgressIndicator(progress = { chapterPctNow }",
              "height(22.dp)", "height(18.dp)"]:
        if k not in hb: e.append("进度/滚动缺: " + k[:56])
    for k in ['"可播 $finishedChapters/${ui.size} 章"',
              '"${chapters.size} 章 · ${ChapterPlan.ruleName(curRule)}"',
              "Text(heartbeat",
              '"整本 ${(bookPct * 100).toInt()}% · 本章']:
        if k in hb: e.append("废话回潮: " + k[:44])
    if hb.count("trackColor = HubLine") < 2:
        e.append("两条进度条底色不全")
    # ③ R226 · 三按钮上移系统顶栏，与书名同一排；按符号定位、不绑颜色常量
    for k in ["trailing = when (screen)",
              'IconButton(onClick = { hubStop() }',
              'IconButton(onClick = { hubResume() }',
              'hubTasksTick++',
              'IconButton(onClick = { nav.push(Screen.PRODUCE) }',
              "fun hubStop()", "fun hubResume()",
              "tasksRequest = hubTasksTick"]:
        if k not in mg: e.append("顶栏缺: " + k[:56])
    # ④ 空章不展开 / 左侧勾 / 零留白
    for k in ["val generated = segsHere.any { it.hasAudio }",
              "if (generated || making) {",
              "Box(Modifier.size(34.dp).clickable { onToggle(r.index) }",
              "multiMode = next.isNotEmpty()",
              "if (!multi) return",
              "padding(horizontal = 16.dp, vertical = 2.dp)"]:
        if k not in hb: e.append("章行/展开缺: " + k[:56])
    # ⑤ 登记
    if "python3 scripts/r219_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r219")
    if '"r219_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r219")
    if "r219_verify.py" not in f["config"]:
        e.append("新守门没登记进受管根")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("又切回大块", "split", "const val TARGET_CHARS = 110",
         "const val TARGET_CHARS = 240"),
        ("代际被摘", "split", "const val SPLIT_EPOCH = 3", ""),
        ("音色键漏代际", "cfg", "TextSplitter.SPLIT_EPOCH.toString()", ""),
        ("split_stamp 不写", "producer", 'File(work, "split_stamp.txt").writeText', ""),
        ("续跑不拦旧段", "resume", 'val splitFile = File(w, "split_stamp.txt")', ""),
        ("点条不滚动", "hub", "chapterListState.animateScrollToItem(makingChapter)", ""),
        ("列表没接 state", "hub", "state = chapterListState", ""),
        ("进度条底色被摘", "hub", "trackColor = HubLine", ""),
        ("本章进度条被摘", "hub", "LinearProgressIndicator(progress = { chapterPctNow }", ""),
        ("废话回潮", "merged", "fun hubStop()", 'Text("可播 3/10 章")'),
        ("顶栏按钮不齐", "merged", "trailing = when (screen)", ""),
        ("停止按钮被摘", "merged", 'IconButton(onClick = { hubStop() }', ""),
        ("空章又弹块", "hub", "if (generated || making) {", "if (true) {"),
        ("左侧勾被摘", "hub", "Box(Modifier.size(34.dp).clickable { onToggle(r.index) }", ""),
        ("点勾不进多选", "hub", "multiMode = next.isNotEmpty()", ""),
        ("展开头部留白回潮", "hub", "if (!multi) return", ""),
        ("CI 漏挂", "ci", "python3 scripts/r219_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r219_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R219 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R219 细切回退 / 防串 / 点条滚动 / 顶栏三按钮 / 左侧常驻勾：PASS")
