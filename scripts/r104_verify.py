# -*- coding: utf-8 -*-
"""R104 · F-02 索引原子写 验证。

只查字符串与结构，不假装证明真机手感。
用法：python3 scripts/r104_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "atomic": CORE / "core" / "AtomicJson.kt",
    "audio": CORE / "core" / "AudioLibrary.kt",
    "seg": CORE / "core" / "SegPlan.kt",
    "preset": CORE / "core" / "MixerPresets.kt",
    "resume": CORE / "core" / "TaskResume.kt",
    "main": CORE / "MainActivity.kt",
    "pipe": CORE / "core" / "Pipeline.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}

POS = [
    ("atomic", "object AtomicJson {"),
    ("atomic", "sealed class Read"),
    ("atomic", "data class Corrupt(val reason: String)"),
    ("atomic", "o.fd.sync()"),
    ("atomic", 'f.name + ".bak"'),
    ("atomic", 'return Read.Corrupt("索引损坏且没有可用的上一次记录")'),
    ("atomic", "if (!legal(s)) return false"),
    ("atomic", "return part.renameTo(f)"),
    ("audio", "@Volatile var readError: String? = null"),
    ("audio", "@Volatile var writeError: String? = null"),
    ("audio", "val body = when (val r = AtomicJson.read(index(c)))"),
    ("audio", "is AtomicJson.Read.Corrupt ->"),
    ("audio", "readError = \"音频记录损坏，已按文件恢复\""),
    ("audio", "val ok = AtomicJson.write(index(c), a.toString())"),
    ("audio", "writeError = if (ok) null else \"音频记录写入失败，旧记录已保留\""),
    ("audio", "val items = (readIndex(c) ?: mutableListOf()).toMutableList()"),
    ("audio", "fun remove(c: Context, id: String): Boolean"),
    ("audio", "writeError = \"音频记录损坏，本次成品已保留但未登记\""),
    ("seg", "val ok = AtomicJson.write(f, s)"),
    ("seg", "AtomicJson.read(File(work, MAP))"),
    ("seg", "AtomicJson.read(File(work, REDO))"),
    ("seg", "AtomicJson.read(File(work, FLAG.format(block)))"),
    ("preset", "AtomicJson.read(file(c))"),
    ("preset", "return AtomicJson.write(file(c), root.toString(1))"),
    ("resume", "AtomicJson.write(file(c), o.toString())"),
    ("resume", "AtomicJson.read(file(c))"),
    ("main", "var tree by remember { mutableStateOf(emptyList<BookAudio>()) }"),
    ("main", 'item(key = "audio_tree_summary")'),
    ("main", "val failed = AudioOps.deleteAll(t.files)"),
    ("pipe", "val regWarn = AudioLibrary.writeError"),
    ("pipe", "if (regWarn != null) \"音频已生成，但记录没写上：$regWarn"),
    ("ci", "r104_verify.py"),
]

BAN_ALL = [
    'if (!tmp.renameTo(f)) { f.writeText(a.toString()); tmp.delete() }',
    'if (!tmp.renameTo(file(c))) { file(c).writeText(root.toString(1)); tmp.delete() }',
    'if (!p.renameTo(f)) { f.writeText(s); p.delete() }',
    'if (!part(c).renameTo(file(c))) {',
    '} catch (_: Throwable) { mutableListOf() }',
    'tmp.writeText(a.toString())',
    'p.writeText(s)',
    'part(c).writeText(o.toString())',
    'part(c) = File(c.filesDir, "mixer_presets.json.part")',
]

KEEP = [
    ("audio", "fun register(c: Context, bookId: String, bookTitle: String"),
    ("audio", "fun list(c: Context): List<AudioItem>"),
    ("audio", "fun latestByChapter(c: Context, bookId: String)"),
    ("audio", "fun playableChapters(c: Context, bookId: String)"),
    ("audio", "fun chapterStartMs(c: Context, item: AudioItem, chapterIndex: Int)"),
    ("audio", "fun durationMsOfBytes(bytes: Long)"),
    ("seg", "fun writeMap(work: File, segs: List<SegMeta>)"),
    ("seg", "fun readMap(work: File): List<SegMeta>?"),
    ("seg", "fun pending(work: File): Set<String>"),
    ("preset", "fun save(c: Context, p: MixerPreset): Boolean"),
    ("preset", "fun rename(c: Context, id: String, raw: String): Boolean"),
    ("preset", "fun delete(c: Context, id: String): Boolean"),
    ("resume", "fun save(c: Context, t: TaskInfo)"),
    ("resume", "fun load(c: Context): TaskInfo?"),
    ("main", '共 ${tree.size} 本 · $chCount 章'),
    ("main", '共 ${tree.size} 本'),
    ("pipe", '"做好了，可以去「我的音频」听"'),
]

def check(f):
    errs = []
    for k, needle in POS:
        if needle not in f[k]:
            errs.append("缺正向: " + needle[:70])
    for b in BAN_ALL:
        for k in ("audio", "seg", "preset", "resume"):
            if b in f[k]:
                errs.append("旧形态残留: " + b[:60])
    for k, needle in KEEP:
        if needle not in f[k]:
            errs.append("守恒缺失: " + needle[:60])
    for k, at in (("audio", 1), ("seg", 1), ("preset", 1), ("resume", 1)):
        if f[k].count("AtomicJson.write(") < at:
            errs.append("%s 没用统一原子写" % k)
    for k in ("audio", "seg", "preset", "resume"):
        if "AtomicJson.read(" not in f[k]:
            errs.append("%s 没用统一安全读" % k)
    return errs

def selftest():
    f = load()
    base = check(f)
    if base:
        print("基线自身就不合格：", *base, sep="\n  ")
        return 1
    cases = [
        ("atomic", "o.fd.sync()", "o.flush()"),
        ("atomic", "if (!legal(s)) return false", "if (legal(s)) return false"),
        ("audio", "val body = when (val r = AtomicJson.read(index(c)))",
                  "val body = index(c).readText()"),
        ("audio", "is AtomicJson.Read.Corrupt ->", "is AtomicJson.Read.Missing ->"),
        ("audio", "val ok = AtomicJson.write(index(c), a.toString())",
                  "index(c).writeText(a.toString()); val ok = true"),
        ("audio", "val items = (readIndex(c) ?: mutableListOf()).toMutableList()",
                  "val items = readIndex(c).toMutableList()"),
        ("audio", "fun remove(c: Context, id: String): Boolean",
                  "fun remove(c: Context, id: String)"),
        ("seg", "val ok = AtomicJson.write(f, s)", 'p.writeText(s); val ok = true'),
        ("preset", "return AtomicJson.write(file(c), root.toString(1))",
                   'file(c).writeText(root.toString(1)); return true'),
        ("resume", "AtomicJson.write(file(c), o.toString())",
                   'part(c).writeText(o.toString())'),
        ("main", "var tree by remember { mutableStateOf(emptyList<BookAudio>()) }",
                 "val indexError = null"),
        ("main", 'item(key = "audio_tree_summary")', 'item(key = "audio_none")'),
        ("pipe", "val regWarn = AudioLibrary.writeError", "val regWarn = null"),
        ("ci", "r104_verify.py", "r104_verify_old.py"),
    ]
    bad = 0
    for k, old, new in cases:
        g = dict(f)
        if old not in f[k]:
            print("注入源缺失:", old[:50]); bad += 1; continue
        g[k] = f[k].replace(old, new, 1)
        if check(g) == []:
            print("反向注入没被拦下:", old[:60]); bad += 1
    print("反向注入 %d/%d 拦下" % (len(cases) - bad, len(cases)))
    return 1 if bad else 0

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    errs = check(load())
    if errs:
        print("R104 自检失败：", *errs, sep="\n  ")
        sys.exit(1)
    print("R104 正向 %d 条 / 零残留 %d 条 / 守恒 %d 条 全过" %
          (len(POS), len(BAN_ALL), len(KEEP)))
