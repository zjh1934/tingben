# -*- coding: utf-8 -*-
"""R112 · F-09 章节页高频进度订阅收尾：索引不随每段重建 / 磁盘读防抖 / 守恒 / 反向注入。"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
HUB = ROOT / "app/src/main/java/com/tingben/app/ChapterHub.kt"
CI = ROOT / ".github/workflows/ci.yml"

POS = [
    'val diskKey = "',
    "var disk by remember(book.id, refresh) {",
    "LaunchedEffect(book.id, refresh, diskKey, localTick) {",
    "if (disk != null) delay(350)",
    "disk = withContext(Dispatchers.IO) {",
    "AudioLibrary.latestByChapter(c, book.id) to AudioLibrary.playableChapters(c, book.id)",
    "val ui by remember(chapters, disk, task)",
    "derivedStateOf",
    "st.chapterProgress",
    'var text by remember(book) { mutableStateOf<String?>(null) }',
    "withContext(Dispatchers.IO) { BookStore.read(c, book) }",
    "if (t == null) {",
    "val body = remember(i, t)",
]

BAN = [
    "var disk by remember(book.id, refresh, diskKey)",
    "remember(book) { BookStore.read",
]


def disk_key_body(s):
    parts = s.split('val diskKey = "', 1)
    return parts[1].split('"', 1)[0] if len(parts) > 1 else None

def check(f):
    e = []
    key = disk_key_body(f["hub"])
    if key is None:
        e.append("章节页没有磁盘读 key")
    elif "st.done" in key:
        e.append("磁盘读 key 仍随每段 done 变化")
    elif "running" not in key or "stage" not in key:
        e.append("磁盘读 key 缺完成/阶段维度")
    for s in POS:
        if s not in f["hub"]:
            e.append("缺正向: " + s[:60])
    for s in BAN:
        if s in f["hub"]:
            e.append("旧形态残留: " + s[:56])
    if "r112_verify.py" not in f["ci"]:
        e.append("CI 未挂 r112_verify")
    return e


def txt(p):
    return p.read_text(encoding="utf-8", errors="replace")


def main():
    f = {"hub": txt(HUB), "ci": txt(CI)}
    errs = check(f)
    if "--selftest" in sys.argv:
        injections = [
            ("索引退回每段重建",
             lambda t: dict(t, hub=t["hub"].replace(
                 "var disk by remember(book.id, refresh)",
                 "var disk by remember(book.id, refresh, diskKey)"))),
            ("读盘 key 又逐段变化",
             lambda t: dict(t, hub=t["hub"].replace(
                 'val diskKey = "${!st.running}|${st.stage}"',
                 'val diskKey = "${st.running}|${st.stage}|${st.done}"'))),
            ("撤掉磁盘读防抖",
             lambda t: dict(t, hub=t["hub"].replace("if (disk != null) delay(350)", ""))),
            ("退回组合期同步读整本",
             lambda t: dict(t, hub=t["hub"].replace(
                 "disk = withContext(Dispatchers.IO) {",
                 "disk = run {"))),
            ("丢掉逐章进度源",
             lambda t: dict(t, hub=t["hub"].replace("st.chapterProgress", "emptyMap()"))),
            ("看书页退回组合期同步读整本",
             lambda t: dict(t, hub=t["hub"].replace(
                 'var text by remember(book) { mutableStateOf<String?>(null) }',
                 'val text = remember(book) { BookStore.read(c, book) }'))),
            ("CI 漏挂验证",
             lambda t: dict(t, ci=t["ci"].replace("r112_verify.py", "r112_missing.py"))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(f)):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(injections) - bad, len(injections)))
    if errs:
        print("R112 FAIL", len(errs))
        [print(" -", x) for x in errs]
        return 1
    print("R112 高频进度不重建索引 / 磁盘读防抖 / 守恒：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
