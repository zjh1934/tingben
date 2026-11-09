# -*- coding: utf-8 -*-
"""R148 · 1.1.3 真机日志实证修复的守门。

日志（tingben_diag_20261108_141354.md）坐实的八件事：
  1. 全本 103 章被拒 + segmap.json 写失败 ← 同一个根因：
     bucket(only) 拼出 304 字符 > 单目录名 255 字节，mkdirs 静默失败，
     work.usableSpace 返回 0 → free=0 → 误判「存储不够」
  2. need 估出 61GB（60 秒兜底 + 分段/合并双算）
  3. 点停止 14:01:28 → FGS_FINISH 14:02:47，79 秒无反馈
  4. 81.9 万字切 9137 段（平均 88 字），每段 54~209 秒
  5. 段 1~4 把下载站页眉、URL、E-mail 当正文念出来
  6. 导完模型被 `screen = Screen.LIBRARY` 强行拽回书架
  7. 目录页调音 push 到独立页 → 回来勾选全丢、两个返回键
  8. savedCol 跨会话记住列 → 删书重导直接落第二列

用法：python3 scripts/r148_verify.py [--selftest]
"""
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": APP / "core" / "Pipeline.kt",
    "split": APP / "core" / "TextSplitter.kt",
    "noise": APP / "core" / "SiteNoise.kt",
    "hub": APP / "ChapterHub.kt",
    "main": APP / "MainActivity.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "pipe": [
        "private const val DIRECT_PREFIX = \"v11/direct/\"",
        "java.security.MessageDigest.getInstance(\"SHA-256\")",
        "if (raw.length <= 64)", "hex.take(20)",
        "c.filesDir.usableSpace", "24_000L * 2L * 12L",
        "var spaceStopped = false", "STORAGE_PARTIAL_STOP",
        "空间用完了，已做好", "if (newlyWritten >= budgetSegs)",
        "var newlyWritten = 0", "newlyWritten++",
        "已停止，正在收尾当前这一小段", "try { wav.delete() }",
        "SiteNoise.clean(text)",
    ],
    "split": ["const val TARGET_CHARS = 240", "const val SOFT_CAP = 420"],
    "noise": ["object SiteNoise", "fun clean(text: String)", "章节内容开始",
              "下载和分享更多电子书", "fun droppedCount"],
    "hub": ["var expandedChapter by remember(book.id)"],
    "main": [
        "var chapterMixerOpen by rememberSaveable",
        "onMixer = { chapterMixerOpen = true",
        "if (chapterMixerOpen) {", "MixerDialog(ctx, onClose = {",
        "if (onClose == null) {", "模型都齐了，回书架导入一本书就能开始",
    ],
    "ci": ["python3 scripts/r148_verify.py --selftest"],
}

BAN = {
    "pipe": [
        "24_000L * 2L * 60L", "if (needBytes > freeBytes)",
        "minOf(work.usableSpace, AudioLibrary.dir(c).usableSpace)",
        "存储空间不够，这次大约要", "halted(annotated.size, annotated.size",
    ],
    "split": ["const val TARGET_CHARS = 110"],
    "hub": ["var savedCol by rememberSaveable(book.id)"],
    "main": [
        "onMixer = { drawerReturn = Screen.CHAPTERS; nav.push(Screen.MIXER) }",
        "screen = Screen.LIBRARY\n                        Toast.makeText(ctx, \"模型都齐了",
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


def bucket_of(only):
    """复刻 Kotlin bucket()，验证任意章数都不会撑爆目录名。"""
    if only is None:
        return "v11/direct/all"
    raw = "_".join(str(i) for i in sorted(only))
    if len(raw) <= 64:
        return "v11/direct/" + raw
    h = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:20]
    return "v11/direct/h" + h


def logic_cases(f):
    bad = []
    # ① 单目录名上限 255 字节：103 章是日志里被拒的那次
    for n, only in ((103, set(range(103))), (500, set(range(500))),
                    (1000, {i * 3 for i in range(1000)})):
        name = bucket_of(only).split("/")[-1]
        if len(name.encode("utf-8")) > 255:
            bad.append("%d 章目录名仍超 255 字节：%d" % (n, len(name)))
        else:
            print("  %4d 章 → 目录名 %d 字节（安全）" % (n, len(name.encode("utf-8"))))
    # 少章保持可读 + 同组章稳定
    if bucket_of({0, 1}) != "v11/direct/0_1":
        bad.append("少章没保持可读命名，已有目录会找不到")
    if bucket_of(set(range(103))) != bucket_of(set(range(103))):
        bad.append("同一组章映射到不同目录，断点会丢")
    if bucket_of(set(range(103))) == bucket_of(set(range(102))):
        bad.append("不同章组撞了同一个目录")

    # ② 站点广告整行过滤：噪声要删、正文要留
    noise = [
        "下载和分享更多电子书请访问，简体:https://ixdzs8.com",
        "//ixdzs8.tw,E-mail:support@ixdzs.com",
        "------章节内容开始", "请稍后重1前言", "爱下电子书Txt",
        "『状态:已完结』", "『怨气撞铃/作者:尾鱼』", "support@ixdzs.com",
    ]
    body = [
        "他给我发了个链接 https://example.com 让我看看",
        "平时没什么其它爱好，闲暇时间里喜欢背着包一个人远行",
        "「状态：还行吧」她笑着说",
    ]
    import re
    # R157 · SiteNoise 用 `private const val WS = "[\\s\\u3000\\uFEFF\\u00A0]*"`
    # 做 Kotlin 字符串模板插值（全角空格必须显式认）。脚本复刻正则时先把
    # `$WS` 展开成等价的 Python 字符类，否则所有规则都匹配不上。
    # R168 起源码统一写 ${WS}；两种写法都展开，反向注入仍可注回裸写法。
    ws_py = r"[\s\u3000\uFEFF\u00A0]*"
    rules = [r.replace("${WS}", ws_py).replace("$WS", ws_py)
             for r in re.findall(r'Regex\("""(.*?)"""\)', f["noise"], re.S)]
    if len(rules) < 8:
        bad.append("SiteNoise 规则太少：%d 条" % len(rules))
    else:
        comp = [re.compile(r) for r in rules]
        for l in noise:
            if not any(r.match(l) for r in comp):
                bad.append("噪声没被规则命中: " + l[:30])
        for l in body:
            if any(r.match(l) for r in comp):
                bad.append("正文被误删: " + l[:30])

    # ③ 切段阈值确实变大了（防「改了注释没改常量」）
    import re as _re
    t = _re.search(r"TARGET_CHARS = (\d+)", f["split"])
    s = _re.search(r"SOFT_CAP = (\d+)", f["split"])
    if not t or int(t.group(1)) < 200:
        bad.append("TARGET_CHARS 没真正调大")
    if not s or int(s.group(1)) <= int(t.group(1) if t else 0):
        bad.append("SOFT_CAP 必须大于 TARGET_CHARS")

    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:70]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:60]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("bucket 退回拼接", "pipe", "if (raw.length <= 64)", ""),
        ("bucket 去掉哈希", "pipe", "java.security.MessageDigest.getInstance(\"SHA-256\")", ""),
        ("空间改回 60 秒", "pipe", "24_000L * 2L * 12L", "24_000L * 2L * 60L"),
        ("空间改回一刀切", "pipe", "var spaceStopped = false", "if (needBytes > freeBytes)"),
        ("可用空间改回子目录", "pipe", "c.filesDir.usableSpace",
         "minOf(work.usableSpace, AudioLibrary.dir(c).usableSpace)"),
        ("停止立即反馈被摘", "pipe", "已停止，正在收尾当前这一小段", ""),
        ("丢弃当前段被摘", "pipe", "try { wav.delete() }", ""),
        ("切段阈值回退", "split", "const val TARGET_CHARS = 240", "const val TARGET_CHARS = 110"),
        ("站点清洗接线被摘", "pipe", "SiteNoise.clean(text)", "TextSplitter.split(text)"),
        ("站点清洗对象被摘", "noise", "object SiteNoise", "object Noise"),
        ("模型导入又跳书架", "main", "模型都齐了，回书架导入一本书就能开始",
         "screen = Screen.LIBRARY"),
        ("调音又跳独立页", "main", "onMixer = { chapterMixerOpen = true",
         "onMixer = { drawerReturn = Screen.CHAPTERS; nav.push(Screen.MIXER) }"),
        ("调音弹层被摘", "main", "if (chapterMixerOpen) {", ""),
        ("列记忆又跨会话", "hub", "var expandedChapter by remember(book.id)",
         "var savedCol by rememberSaveable(book.id)"),
        ("顶栏又双出口", "main", "if (onClose == null) {", ""),
        ("CI 漏挂 r148", "ci", "python3 scripts/r148_verify.py --selftest", ""),
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
        print("R148 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R148 真机日志实证修复（全本被拒/存储误判/停止延迟/切段过碎/站点广告/模型跳页/调音丢勾选/列记忆）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
