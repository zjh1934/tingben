# -*- coding: utf-8 -*-
"""R101 · 任务页 / 章节页第二三列同步 / 调音台采样分模式 验证。

只查字符串与结构，不假装证明真机手感。
用法：python3 scripts/r101_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "resume": APP / "core" / "TaskResume.kt",
    "pipeline": APP / "core" / "Pipeline.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}

def tasks(s):
    i = s.find("private fun TaskCenterScreen(")
    j = s.find("private fun fmtTime(", i + 1)
    return s[i:j] if i >= 0 else ""

def mixer(s):
    i = s.find("private fun MixerScreen(")
    j = s.find("private fun ModeTabs(", i + 1)
    return s[i:j] if i >= 0 else ""

POS = [
    ("hub", "chapterProgress: Map<Int, Float>, bookId: String"),
    ("hub", "runningBook == bookId"),
    ("hub", 'val diskKey = "${st.running}|${st.stage}|${st.done}"'),
    ("hub", "var disk by remember(book.id, refresh)"),
    ("hub", "AudioLibrary.playableChapters(c, book.id)"),
    ("hub", "st.running, task?.bookId, task?.only, st.chapterProgress, book.id, stalled)"),
    ("hub", "val barText = when {"),
    ("hub", 'st.stage == "完成" -> "做好了 · 合成列已更新，可播列已点亮"'),
    ("main", "val live by Producer.resume.collectAsState()"),
    ("main", "val t = live ?: saved"),
    ("main", '"custom_voice" -> "预设音色"'),
    ("main", '"base" -> "参考音频"'),
    ("main", ".coerceIn(0, total)"),
    ("main", "TaskResume.invalidationReason(c, it)"),
    ("main", 'Text("放弃这条记录", fontSize = 12.sp, color = Warn)'),
    ("resume", "val modeId: String = \"\""),
    ("resume", '.put("modeId", t.modeId)'),
    ("resume", 'o.optString("modeId", "")'),
    ("pipeline", "modeId = ModelStore.currentMode(c).id"),
    ("pipeline", '_state.value = Progress(true, "正在准备"'),
    ("main", "if (mode == ModelStore.Mode.VOICE_DESIGN) {"),
    ("main", "采样参数沿用全局设置"),
    ("ci", "r101_verify.py"),
]

BAN_ALL = [
    "showProgressHint",
]

BAN_TASKS = [
    '"直接朗读"',
    "if (!st.running) return@LaunchedEffect",
    "progress = { (if (st.running) st.done else real).toFloat() / total }",
]

BAN_HUB = [
    "if (showProgressHint || st.running)",
    "if (running && runningBook != null && inScope(ch.index))",
    "val finished = remember(book, refresh) {",
]

def check(f):
    errs = []
    for k, needle in POS:
        if needle not in f[k]:
            errs.append("缺正向: " + needle[:70])
    for b in BAN_ALL:
        for k in ("main", "hub", "resume", "pipeline"):
            if b in f[k]:
                errs.append("旧形态残留: " + b[:50])
    for b in BAN_TASKS:
        if b in tasks(f["main"]):
            errs.append("任务页旧形态: " + b[:50])
    for b in BAN_HUB:
        if b in f["hub"]:
            errs.append("章节页旧形态: " + b[:50])
    if tasks(f["main"]).count('Text("从头重做", fontSize = 12.sp)') != 2:
        errs.append("已完成分支缺「从头重做」")
    mx = mixer(f["main"])
    if "if (mode == ModelStore.Mode.VOICE_DESIGN) {" not in mx:
        errs.append("采样区缺模式门控")
    if mx.find('Text("采样参数 · 9 项"') < mx.find("if (mode == ModelStore.Mode.VOICE_DESIGN) {"):
        errs.append("九项采样画在模式门控之外")
    return errs

def main():
    f = load()
    errs = check(f)
    if errs:
        for e in errs:
            print(" -", e)
        print("R101 FAIL", len(errs))
        return 1
    print("R101 正向 %d 条 / 旧形态零残留 / 守恒：PASS" % len(POS))
    return 0

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        f = load()
        base = check(f)
        if base:
            print("R101 当前源码正向不合格：")
            for e in base:
                print(" -", e)
            sys.exit(1)
        injections = [
            ("跨书归属判断被删",
             lambda t: dict(t, hub=t["hub"].replace("runningBook == bookId", "true"))),
            ("做完重读磁盘被删",
             lambda t: dict(t, hub=t["hub"].replace("val diskKey = ", "val unusedKey = "))),
            ("常驻提示开关复活",
             lambda t: dict(t, hub=t["hub"] + "\nvar showProgressHint = false\n")),
            ("即时状态源被删",
             lambda t: dict(t, main=t["main"].replace(
                 "val live by Producer.resume.collectAsState()", ""))),
            ("进度防越界被删",
             lambda t: dict(t, main=t["main"].replace(".coerceIn(0, total)", ""))),
            ("模式名写死回旧档位",
             lambda t: dict(t, main=tasks(t["main"]).replace(
                 'val modeText = when (t?.modeId)', 'val modeText = "直接朗读" // when (t?.modeId)')
                 + t["main"][t["main"].find("private fun fmtTime("):])),
            ("模式落盘被删",
             lambda t: dict(t, resume=t["resume"].replace('.put("modeId", t.modeId)', ""))),
            ("开工即时置位被删",
             lambda t: dict(t, pipeline=t["pipeline"].replace(
                 '_state.value = Progress(true, "正在准备", 0, 0, 0, "正在启动…")', ""))),
            ("采样模式门控被删",
             lambda t: dict(t, main=t["main"].replace(
                 "if (mode == ModelStore.Mode.VOICE_DESIGN) {", "if (true) {", 1))),
            ("完成后重做入口被删",
             lambda t: dict(t, main=tasks(t["main"]).replace(
                 'Text("从头重做", fontSize = 12.sp)', 'Text("重做")', 1)
                 + t["main"][t["main"].find("private fun fmtTime("):])),
            ("CI 漏挂验证",
             lambda t: dict(t, ci=t["ci"].replace("r101_verify.py", "r101_missing.py"))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(f)):
                bad += 1
                print("   漏放:", name)
        print("R101 反向注入 %d 例，漏网 %d" % (len(injections), bad))
        sys.exit(1 if bad else 0)
    sys.exit(main())
