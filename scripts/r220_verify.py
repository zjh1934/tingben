#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R220 · 真机「每动一次调音台就要全部消失」+ 目录页小段批次守门。

  ① 换音色/调音台：旧套留副本归档，根目录已生成段认领为新套起点，
     点继续只把剩下的段按新参数合成；续跑判据不再把调音台改动算失效。
  ② 小段编号每章从 1 起（文件段号仍整本连续）。
  ③ 没做过也没在做的章，点章名不展开；展开顶部零留白。
用法：python3 scripts/r220_verify.py [--selftest]
"""
import pathlib, sys, json
ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
CORE = APP / "core"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "hub": rd(APP / "ChapterHub.kt"),
        "vc": rd(CORE / "VoiceCache.kt"),
        "resume": rd(CORE / "TaskResume.kt"),
        "producer": rd(CORE / "Producer.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    hb, vc, rs, pr = f["hub"], f["vc"], f["resume"], f["producer"]
    # ① 不丢成果：复制归档 + 认领保留 + 续跑不拦
    for k in ["private fun copyWav(from: File, to: File)",
              "private fun copySide(from: File, to: File)",
              "src.forEach { f -> if (copyWav(f, File(bucket, f.name))) archived++ }",
              "restored = src.size",
              "MAX_KEEP = 24"]:
        if k not in vc: e.append("音色套缺: " + k[:56])
    if "src.forEach { f -> if (moveWav(f, File(bucket, f.name))) archived++ }" in vc:
        e.append("旧套又被搬走（会把进度清零）")
    for k in ["VOICE_CHANGED_KEEP", "MODE_CHANGED_KEEP",
              "oldVoice != Prefs.legacyVoiceStamp(c)",
              "voiceFile.writeText(currentVoice)",
              "调过调音台不再拦"]:
        if k not in rs: e.append("续跑判据缺: " + k[:50])
    for k in ["\"改过调音台设置，会切到这套音色的缓存",
              "\"换过模式或音色输入，会切到这套音色的缓存"]:
        if k in rs: e.append("调音台改动又把进度归零: " + k[:44])
    if "已做好的不重做，后面按新参数继续" not in pr:
        e.append("Producer 没说清后续按新参数")
    # ② 章内段号
    for k in ["val chapterBase = remember(chapterSegs)",
              "chapterSegs.minOfOrNull { it.index } ?: 0",
              "row = row, pq = pqSeg, base = chapterBase,",
              "row: List<SegRow>, pq: PlaybackState, base: Int = 0",
              'Text("${sg.index - base + 1}"',
              'Track(w, "第 ${i - chapterBase + 1} 段"']:
        if k not in hb: e.append("章内段号缺: " + k[:56])
    if 'Text("${sg.index + 1}"' in hb:
        e.append("又显示整本连续大段号")
    # ③ R264 · 制作中的章恒定展开，不再有「展开/收起」；小块区只在 bookRunning 渲染
    for k in ["val liveChapter: Int? = if (bookRunning) makingChapter else null",
              "val bookRunning = st.running && task?.bookId == book.id",
              "if (r.index == shownChapter) {",
              "Box(Modifier.fillMaxWidth().background(HubSoft)) {",
              "top = 0.dp, bottom = 2.dp"]:
        if k not in hb: e.append("恒定展开/零留白缺: " + k[:56])
    for k in ["val generated = segsHere.any { it.hasAudio }",
              "if (generated || making) {", "val hasSegs = map != null"]:
        if k in hb: e.append("R264 旧展开形态回潮: " + k[:56])
    # ④ 登记
    if "python3 scripts/r220_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r220")
    if '"r220_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r220")
    from r45_common import scope_indexed
    if not scope_indexed(f["config"], "scripts/r220_verify.py"):
        e.append("新守门没登记进受管根")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("又搬走旧套", "vc",
         "src.forEach { f -> if (copyWav(f, File(bucket, f.name))) archived++ }",
         "src.forEach { f -> if (moveWav(f, File(bucket, f.name))) archived++ }"),
        ("不认领旧段", "vc", "restored = src.size", ""),
        ("复制函数被摘", "vc", "private fun copyWav(from: File, to: File)", ""),
        ("套数回退 12", "vc", "MAX_KEEP = 24", "MAX_KEEP = 12"),
        ("调音台又算失效", "resume", "VOICE_CHANGED_KEEP",
         '"改过调音台设置，会切到这套音色的缓存"'),
        ("续跑兼容读被摘", "resume", "oldVoice != Prefs.legacyVoiceStamp(c)", ""),
        ("迁移写回被摘", "resume", "voiceFile.writeText(currentVoice)", ""),
        ("Producer 日志回退", "producer", "已做好的不重做，后面按新参数继续", ""),
        ("章内起始被摘", "hub", "val chapterBase = remember(chapterSegs)", ""),
        ("又显示大段号", "hub", 'Text("${sg.index - base + 1}"',
         'Text("${sg.index + 1}"'),
        ("网格没接 base", "hub", "row = row, pq = pqSeg, base = chapterBase,", ""),
        ("制作外仍渲染小块区", "hub",
         "val liveChapter: Int? = if (bookRunning) makingChapter else null",
         "val liveChapter: Int? = makingChapter"),
        ("旧展开形态回潮", "hub", "if (r.index == shownChapter) {",
         "if (generated || making) { if (r.index == shownChapter) {"),
        ("顶部又留白", "hub", "top = 0.dp, bottom = 2.dp",
         "vertical = 8.dp"),
        ("展开底色被摘", "hub", "Box(Modifier.fillMaxWidth().background(HubSoft)) {",
         "Column(Modifier.fillMaxWidth()) {"),
        ("CI 漏挂", "ci", "python3 scripts/r220_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r220_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        # R287 · 展开底色在 head item 与每行 item 各出现一次，只摘第一处
        # 另一处仍能让检查通过 → 必须全量替换，注入才真的生效。
        g[key] = (f[key].replace(needle, repl) if name == "展开底色被摘"
                  else f[key].replace(needle, repl, 1))
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R220 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R220 调音台不丢成果 / 章内段号 / 空章不展开 / 展开零留白：PASS")
