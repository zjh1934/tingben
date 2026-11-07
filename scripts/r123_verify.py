#!/usr/bin/env python3
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "atomic": APP / "core" / "AtomicJson.kt",
    "audio": APP / "core" / "AudioLibrary.kt",
    "ops": APP / "core" / "AudioOps.kt",
    "resume": APP / "core" / "TaskResume.kt",
    "tree": APP / "core" / "AudioTree.kt",
    "pipe": APP / "core" / "Pipeline.kt",
    "main": APP / "MainActivity.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = [
    ("atomic", "fun delete(f: File) {"),
    ("atomic", 'f.name + ".part"'),
    ("atomic", 'f.name + ".bak"'),
    ("resume", "AtomicJson.delete(listFile(c))"),
    ("resume", "AtomicJson.delete(file(c))"),
    ("audio", 'Regex("""^chapter_([0-9a-fA-F]{8})_(\\d{3})_[0-9a-fA-F]{8}$""")'),
    ("audio", "scopeLabel(setOf(ch))"),
    ("audio", "f.lastModified(), f.length(), setOf(ch), true"),
    ("audio", "if (!writeIndex(c, all.filter { it.id != id })) return false"),
    ("ops", 'reason = "整章已拼好，但成品登记没成功'),
    ("ops", "fun deleteMerged(c: Context, file: File): Boolean ="),
    ("ops", "AudioLibrary.remove(c, file.nameWithoutExtension)"),
    ("pipe", "AudioTree.forget(c, killed.filter { !it.isFile }.map { it.absolutePath })"),
    ("pipe", "chunks.size != parts.size"),
    ("tree", "val out = mutableMapOf<Int, String>()"),
    ("main", "文件会重新出现在列表里"),
    ("ci", "r123_verify.py"),
]

ZERO = [
    ("atomic", "return part.renameTo(f)"),
    ("resume", "listFile(c).delete()"),
    ("resume", "file(c).delete()"),
    ("resume", "part(c).delete()"),
    ("resume", "private fun part(c: Context)"),
    ("audio", "文件删不掉，记录已保留"),
    ("audio", "return writeIndex(c, all.filter { it.id != id })"),
    ("ops", "成品登记没成功，原来的小块都留着"),
    ("ops", "if (file.isFile && !file.delete()) return false"),
    ("tree", 'return@associate 0 to ""'),
    ("main", "个删不掉，记录已保留"),
]

KEEP = [
    ("atomic", "fun read(f: File): Read"),
    ("atomic", "fun write(f: File, s: String): Boolean"),
    ("audio", "fun register(c: Context, bookId: String"),
    ("audio", "fun list(c: Context): List<AudioItem>"),
    ("audio", "fun latestByChapter(c: Context, bookId: String)"),
    ("audio", "fun chapterStartMs(c: Context, item: AudioItem, chapterIndex: Int)"),
    ("ops", "fun mergeChapter("),
    ("ops", "AudioTree.markDone(it, chapter, dst)"),
    ("ops", "val kept = deleteAll(parts)"),
    ("resume", "fun save(c: Context, t: TaskInfo)"),
    ("resume", "fun load(c: Context): TaskInfo?"),
    ("tree", "fun load(c: Context): List<BookAudio>"),
    ("tree", "fun chapterTracks(c: Context, ch: ChapterAudio)"),
]


def body(src, sig):
    m = re.search(re.escape(sig) + r"[\s\S]*?\{([\s\S]*?)\n    \}", src)
    return m.group(1) if m else ""


def check(f):
    e = []
    for k, s in POS:
        if s not in f[k]:
            e.append("缺正向 %s: %s" % (k, s[:60]))
    for k, s in ZERO:
        if s in f[k]:
            e.append("旧形态残留 %s: %s" % (k, s[:60]))
    for k, s in KEEP:
        if s not in f[k]:
            e.append("守恒丢失 %s: %s" % (k, s[:60]))

    w = body(f["atomic"], "fun write(f: File, s: String): Boolean")
    if "if (!part.renameTo(f))" not in w or not re.search(
            r"if \(!part\.renameTo\(f\)\)[\s\S]{0,120}part\.delete\(\)", w):
        e.append("原子写 rename 失败没清 .part")
    if not re.search(r"catch \(_: Throwable\) \{ part\.delete\(\); return false \}", w):
        e.append("原子写异常分支没清 .part")
    d = body(f["atomic"], "fun delete(f: File)")
    if not all(x in d for x in ('f.name + ".part"', 'f.name + ".bak"', "it.isFile")):
        e.append("delete 没清全三件套")

    rm = body(f["resume"], "fun remove(c: Context, bookId: String)")
    if rm.count("AtomicJson.delete(") < 2:
        e.append("删除任务没清干净三件套")

    ar = body(f["audio"], "fun remove(c: Context, id: String): Boolean")
    iw = ar.find("writeIndex(")
    idel = ar.find("f.delete()")
    if iw < 0 or idel < 0 or iw >= idel:
        e.append("音频删除仍先删文件后写索引")

    mc = body(f["ops"], "fun mergeChapter(")
    m = re.search(r"if \(!AudioLibrary\.lastRegisterOk\) \{([\s\S]*?)\n        \}", mc)
    if not m or "dst.delete()" in m.group(1) or "Result(true, dst" not in m.group(1):
        e.append("登记失败仍删掉拼好的整章成品")

    ls = body(f["audio"], "fun list(c: Context): List<AudioItem>")
    if "chapter_" not in ls or "setOf(ch), true" not in ls:
        e.append("磁盘兜底没恢复章成品归属")

    ct = body(f["tree"], "private fun readChapterTitles(dir: File): Map<Int, String>?")
    if "continue" not in ct or "mutableMapOf" not in ct:
        e.append("章标题坏元素仍会覆盖第 0 章")

    dm = f["ops"].split("fun deleteMerged(c: Context, file: File): Boolean =", 1)[-1].split("\n\n", 1)[0]
    if "file.delete()" in dm or "AudioLibrary.remove(" not in dm:
        e.append("删整章成品仍绕过统一落盘顺序")
    if f["pipe"].count("AudioTree.forget(c, killed.filter { !it.isFile }") < 3:
        e.append("作废重算没清干净块的自定义名与移动记录")
    if "chunks.size != parts.size" not in f["pipe"]:
        e.append("拼接仍会静默跳过读不出来的段")
    return e


def txt(p):
    return p.read_text(encoding="utf-8", errors="replace")


def main():
    f = {k: txt(p) for k, p in FILES.items()}
    errs = check(f)
    if "--selftest" in sys.argv:
        inj = [
            ("原子写失败留 .part", lambda t: dict(t, atomic=t["atomic"].replace(
                "if (!part.renameTo(f)) { part.delete(); return false }",
                "return part.renameTo(f)"))),
            ("原子写异常不清残", lambda t: dict(t, atomic=t["atomic"].replace(
                "catch (_: Throwable) { part.delete(); return false }",
                "catch (_: Throwable) { return false }"))),
            ("delete 漏清 bak", lambda t: dict(t, atomic=t["atomic"].replace(
                'File(f.parentFile, f.name + ".bak")).forEach {',
                'File(f.parentFile, f.name + ".bakx")).forEach {'))),
            ("删任务复活", lambda t: dict(t, resume=t["resume"].replace(
                "AtomicJson.delete(listFile(c))", "listFile(c).delete()"))),
            ("删任务漏清 active", lambda t: dict(t, resume=t["resume"].replace(
                "AtomicJson.delete(file(c))", "file(c).delete()"))),
            ("章成品掉进无主组", lambda t: dict(t, audio=t["audio"].replace(
                "scopeLabel(setOf(ch))", '"整本（旧版）"'))),
            ("章成品不标已知章", lambda t: dict(t, audio=t["audio"].replace(
                "setOf(ch), true))", "emptySet(), false))"))),
            ("删除又先删文件", lambda t: dict(t, audio=t["audio"].replace(
                "if (!writeIndex(c, all.filter { it.id != id })) return false",
                "if (!f.isFile || !f.delete()) return false"))),
            ("登记失败删成品", lambda t: dict(t, ops=t["ops"].replace(
                'return Result(true, dst, kept = parts.size,',
                'dst.delete(); return Result(false, reason = "x",'))),
            ("章标题坏元素覆盖", lambda t: dict(t, tree=t["tree"].replace(
                "val o = a.optJSONObject(k) ?: continue",
                'val o = a.optJSONObject(k) ?: return@associate 0 to ""'))),
            ("删整章成品又先删文件", lambda t: dict(t, ops=t["ops"].replace(
                "fun deleteMerged(c: Context, file: File): Boolean =\n        AudioLibrary.remove(c, file.nameWithoutExtension)",
                "fun deleteMerged(c: Context, file: File): Boolean {\n        if (file.isFile && !file.delete()) return false\n        return AudioLibrary.remove(c, file.nameWithoutExtension)\n    }"))),
            ("作废重算漏清元数据", lambda t: dict(t, pipe=t["pipe"].replace(
                "AudioTree.forget(c, killed.filter { !it.isFile }.map { it.absolutePath })",
                "Unit", 1))),
            ("拼接又静默丢坏段", lambda t: dict(t, pipe=t["pipe"].replace(
                "if (chunks.isEmpty() || chunks.size != parts.size)",
                "if (chunks.isEmpty())"))),
            ("守恒函数被删", lambda t: dict(t, audio=t["audio"].replace(
                "fun latestByChapter(c: Context, bookId: String)",
                "fun latestByChapterX(c: Context, bookId: String)"))),
            ("CI 漏挂", lambda t: dict(t, ci=t["ci"].replace(
                "r123_verify.py", "r123_missing.py"))),
        ]
        bad = 0
        for name, mutate in inj:
            if not check(mutate(f)):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(inj) - bad, len(inj)))
    if errs:
        print("R123 FAIL", len(errs))
        for x in errs:
            print(" -", x)
        return 1
    print("R123 落盘一致性与音频归位 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条：PASS"
          % (len(POS), len(ZERO), len(KEEP)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
