#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R361 · 章拼好转码后，中间小块是否自动删除，由设置开关决定且真的生效。

新规则优先：过去「章固化＝小块必删」写死在 AudioOps 两条拼章成功路径里，
现在只认 Prefs.autoDeleteSegsAfterMerge(c)。关＝小块文件与显示名都留着，
可在设置页「音频文件」里导出 / 删除；开＝沿用 1.1.44 行为（默认 true）。

守恒一条不许动：内部 WAV 成品、转码校验、markDone、清重做标记、
续跑不回头，全部与开关无关。本判据纯内存注入，源文件零写入。
用法：python3 scripts/r361_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "prefs": APP / "core/Prefs.kt",
    "ops": APP / "core/AudioOps.kt",
    "ui": APP / "SettingsUi.kt",
    "hub": APP / "ChapterHub.kt",
    "producer": APP / "core/Producer.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
}

POS = {
    "prefs": [
        'KEY_AUTO_DELETE_SEGS = "autoDeleteSegsAfterMerge"',
        "fun autoDeleteSegsAfterMerge(c: Context) =",
        "sp(c).getBoolean(KEY_AUTO_DELETE_SEGS, true)",
        "fun setAutoDeleteSegsAfterMerge(c: Context, v: Boolean) =",
        "sp(c).edit().putBoolean(KEY_AUTO_DELETE_SEGS, v).apply()",
    ],
    "ops": [
        "val autoDel = Prefs.autoDeleteSegsAfterMerge(c)",
        "val kept = if (autoDel) deleteAll(parts) else parts.size",
        "if (autoDel) AudioTree.forget(c, killedPaths)",
        "if (enc.converted) enc.wav.delete()",
        "AudioTree.markDone(it, chapter, enc.file)",
        "if (!marked)",
        '"CHAPTER_MERGE_CLEAN"',
        "按设置保留",
        "clearRedoOfParts(parts)",
    ],
    "ui": [
        "var autoDelSegs by remember { mutableStateOf(Prefs.autoDeleteSegsAfterMerge(c)) }",
        'OptionRow("拼好后删除中间小块"',
        "Prefs.setAutoDeleteSegsAfterMerge(c, it)",
        "autoDelSegs = true",
        "Prefs.setAutoDeleteSegsAfterMerge(c, true)",
    ],
    "hub": [
        "val keepSegs = !Prefs.autoDeleteSegsAfterMerge(c)",
        "val keepSegsHere = keepSegs && hasDone && readyHere > 0",
        '"章音频已生成 · 中间小块按设置留着，可在「音频文件」里导出或删除"',
    ],
    "ci": [
        "python3 scripts/r361_verify.py",
        "python3 scripts/r361_verify.py --selftest",
    ],
    "r135": ['"r361_verify.py"'],
}

BAN = {
    "ops": [
        # 旧规则：无条件删小块 + 无条件清显示名，不许再和新开关并存
        "        val kept = deleteAll(parts)\n        AudioTree.forget(c, killedPaths)",
        "R255 · 用户口径：整章既已拼好，中间态小块就该清掉，别占着私有目录。",
    ],
    "producer": [
        "「已固化」＝拼好转码、小块已清、chapter_done.json 记着它",
        "拼了就清小块、旧声音会被固化进成品",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for k, needles in POS.items():
        for n in needles:
            if n not in f[k]:
                e.append("R361 %s 缺: %s" % (k, n[:64]))
    for k, needles in BAN.items():
        for n in needles:
            if n in f[k]:
                e.append("R361 %s 旧规则回潮: %s" % (k, n[:56]))
    a = f["ops"]
    # 两条拼章成功路径（手动 / 自动章边界）都必须读开关、都按开关删、都按开关 forget
    for s, want in [("val autoDel = Prefs.autoDeleteSegsAfterMerge(c)", 2),
                    ("val kept = if (autoDel) deleteAll(parts) else parts.size", 2),
                    ("if (autoDel) AudioTree.forget(c, killedPaths)", 2),
                    ("if (enc.converted) enc.wav.delete()", 2),
                    ("        clearRedoOfParts(parts)", 2)]:
        if a.count(s) != want:
            e.append("R361 条数不对(%s): %d/%d" % (s[:36], a.count(s), want))
    # 顺序：先写章状态 / 兜底，再动小块（R255/R231 不放宽）
    body = a[a.find("fun mergeChapterAuto("):a.find("    fun mergedTemp(")]
    i_mark = body.find("AudioTree.markDone")
    i_keep = body.find("if (!marked)")
    i_del = body.find("deleteAll(")
    if not (0 <= i_mark < i_keep < i_del):
        e.append("R361 顺序错：没先写章状态/没兜底就动小块")
    # 内部 WAV 不受开关约束：它必须出现在 autoDel 读取之前且不带开关条件
    if "if (autoDel && enc.converted)" in a or "if (autoDel) enc.wav.delete()" in a:
        e.append("R361 内部 WAV 被开关误管（与小块两回事）")
    return e


def selftest():
    f = load()
    base = check(f)
    if base:
        print("正向不绿，先修实现：")
        for x in base:
            print(" -", x)
        return 1
    print("正向 0 失败")
    cases = [
        ("开关默认改成关", "prefs",
         "sp(c).getBoolean(KEY_AUTO_DELETE_SEGS, true)",
         "sp(c).getBoolean(KEY_AUTO_DELETE_SEGS, false)"),
        ("开关键被摘", "prefs",
         'KEY_AUTO_DELETE_SEGS = "autoDeleteSegsAfterMerge"', ""),
        ("落盘函数被摘", "prefs",
         "sp(c).edit().putBoolean(KEY_AUTO_DELETE_SEGS, v).apply()", ""),
        ("手动路径不认开关", "ops",
         "val kept = if (autoDel) deleteAll(parts) else parts.size",
         "val kept = deleteAll(parts)"),
        ("自动路径不读开关", "ops",
         "val autoDel = Prefs.autoDeleteSegsAfterMerge(c)", ""),
        ("forget 又被无条件执行", "ops",
         "if (autoDel) AudioTree.forget(c, killedPaths)",
         "AudioTree.forget(c, killedPaths)"),
        ("内部 WAV 被开关误管", "ops",
         "if (enc.converted) enc.wav.delete()",
         "if (autoDel && enc.converted) enc.wav.delete()"),
        ("清重做标记被摘", "ops", "        clearRedoOfParts(parts)", ""),
        ("日志不按开关说话", "ops", "按设置保留", "清掉"),
        ("旧无条件删除整体回潮", "ops",
         "        val autoDel = Prefs.autoDeleteSegsAfterMerge(c)\n"
         "        val kept = if (autoDel) deleteAll(parts) else parts.size\n"
         "        if (autoDel) AudioTree.forget(c, killedPaths)",
         "        val kept = deleteAll(parts)\n"
         "        AudioTree.forget(c, killedPaths)"),
        ("旧注释回潮", "ops", "R361 · 新规则优先",
         "R255 · 用户口径：整章既已拼好，中间态小块就该清掉，别占着私有目录。\n     * R361 · 新规则优先"),
        ("设置项被摘", "ui", 'OptionRow("拼好后删除中间小块"', ""),
        ("设置项不落盘", "ui", "Prefs.setAutoDeleteSegsAfterMerge(c, it)", ""),
        ("恢复默认不复位", "ui", "Prefs.setAutoDeleteSegsAfterMerge(c, true)", ""),
        ("目录页状态行被摘", "hub",
         '"章音频已生成 · 中间小块按设置留着，可在「音频文件」里导出或删除"', ""),
        ("目录页不读开关", "hub",
         "val keepSegs = !Prefs.autoDeleteSegsAfterMerge(c)", ""),
        ("CI 漏挂守门", "ci", "python3 scripts/r361_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r361_verify.py"', ""),
    ]
    bad = 0
    for name, key, old, new in cases:
        v = dict(f)
        v[key] = v[key].replace(old, new)
        if v[key] == f[key]:
            print("  注入没生效:", name)
            bad += 1
            continue
        if not check(v):
            print("  反向注入漏放:", name)
            bad += 1
    print("反向注入 %d 组，漏放 %d" % (len(cases), bad))
    return 1 if bad else 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    e = check(load())
    for x in e:
        print(" -", x)
    print("R361: %s" % ("PASS" if not e else "FAIL %d" % len(e)))
    return 1 if e else 0


if __name__ == "__main__":
    sys.exit(main())
