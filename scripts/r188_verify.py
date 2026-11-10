#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R188 · 自己接着找的两件。

A CI 失败日志回写目标：原来硬编码 branch='main'，rc 分支编译失败会把日志
   推到 main（run 146 实证）。改成 GITHUB_REF_NAME 写触发它的分支。
B R185 分套缓存漏了音频状态侧车：chapter_done.json（整章另存成品索引）
   留在根目录 → 换音色后续跑会复用旧音色成品，一本书混两种声音。
   现在把 chapter_done.json / redo.json 和 wav 一起随套搬动，切回再搬回；
   切分产物仍留根目录共享。顺带修 AudioOps forget 的脆弱写法。
R220 · 换套改成「复制留档 + 根目录认领保留」：已做好的整章成品索引不再
   从根目录消失，已合成的章不丢；切回原设置仍能逐字节还原。

selftest 全程纯内存注入 + tempfile 逻辑对拍，源文件零写入。
用法：python3 scripts/r188_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "cache": APP / "core/VoiceCache.kt",
    "ops": APP / "core/AudioOps.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

CACHE = [
    'private val SIDECARS = listOf("chapter_done.json", "redo.json")',
    'private fun sideFiles(dir: File): List<File>',
    'private fun hasAudioState(dir: File): Boolean',
    'rootWavs(dir).isNotEmpty() || sideFiles(dir).isNotEmpty()',
    'private fun moveSide(from: File, to: File): Boolean',
    '!hasAudioState(work) -> incoming',
    'val sides = sideFiles(work)',
    'if (src.isNotEmpty() || sides.isNotEmpty()) {',
    'sides.forEach { f -> copySide(f, File(bucket, f.name)) }',
    'targetSides.forEach { f -> moveSide(f, File(work, f.name)) }',
    'val alive = dirs.count { d ->',
    'others = alive',
]
OPS = [
    'val killedPaths = parts.map { it.absolutePath }',
    'AudioTree.forget(c, killedPaths)',
]
CI = [
    "branch = os.environ.get('GITHUB_REF_NAME') or 'main'",
    "'branch': branch}).encode()",
    'python3 scripts/r188_verify.py --selftest',
    'python3 scripts/r188_verify.py',
]
BAN = [
    "'branch': 'main'",
    'AudioTree.forget(c, parts.filter { !it.isFile }',
]


def checks(f):
    e = []
    for key, needles in (("cache", CACHE), ("ops", OPS), ("ci", CI)):
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:66]))
    for s in BAN:
        for key in ("ops", "ci"):
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:56]))
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("CI 又写死 main", "ci",
         lambda t: t.replace("'branch': branch}).encode()", "'branch': 'main'}).encode()")),
        ("CI 丢掉分支变量", "ci",
         lambda t: t.replace("          branch = os.environ.get('GITHUB_REF_NAME') or 'main'\n", "")),
        ("侧车列表被摘", "cache",
         lambda t: t.replace('    private val SIDECARS = listOf("chapter_done.json", "redo.json")',
                             '    private val SIDECARS = emptyList<String>()')),
        ("换套不留侧车副本", "cache",
         lambda t: t.replace('            sides.forEach { f -> copySide(f, File(bucket, f.name)) }\n', '')),
        ("切回不取侧车", "cache",
         lambda t: t.replace('            targetSides.forEach { f -> moveSide(f, File(work, f.name)) }\n', '')),
        ("只有侧车时不归档", "cache",
         lambda t: t.replace('if (src.isNotEmpty() || sides.isNotEmpty()) {',
                             'if (src.isNotEmpty()) {')),
        ("hasAudioState 不算侧车", "cache",
         lambda t: t.replace('        rootWavs(dir).isNotEmpty() || sideFiles(dir).isNotEmpty()',
                             '        rootWavs(dir).isNotEmpty()')),
        ("Alive 又只数 wav", "cache",
         lambda t: t.replace('        val alive = dirs.count { d ->\n'
                             '            rootWavs(d).isNotEmpty() || sideFiles(d).isNotEmpty()\n'
                             '        }',
                             '        val alive = counts.count { it > 0 }')),
        ("moveSide 被摘", "cache",
         lambda t: t.replace('    private fun moveSide(from: File, to: File): Boolean = try {',
                             '    private fun unusedMoveSide(from: File, to: File): Boolean = try {')),
        ("AudioOps 又删完再 filter", "ops",
         lambda t: t.replace('        val killedPaths = parts.map { it.absolutePath }\n'
                             '        val kept = deleteAll(parts)\n'
                             '        AudioTree.forget(c, killedPaths)',
                             '        val kept = deleteAll(parts)\n'
                             '        AudioTree.forget(c, parts.filter { !it.isFile }.map { it.absolutePath })')),
        ("CI 漏挂守门", "ci",
         lambda t: t.replace("python3 scripts/r188_verify.py --selftest", "")),
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
    bad += logic_check()
    return bad


def logic_check():
    """R220 · 按等价规则跑四组切换，验证侧车复制留档 + 根目录认领保留。"""
    import tempfile, shutil
    PREFIX, KEY = "_voice_", "voice_cache_key.txt"
    SIDES = ["chapter_done.json", "redo.json"]

    def wavs(d):
        return sorted(f.name for f in d.iterdir()
                      if f.is_file() and f.name.startswith("direct_") and f.name.endswith(".wav"))

    def sides(d):
        return [n for n in SIDES if (d / n).is_file()]

    def move(src, dst):
        if dst.exists():
            dst.unlink()
        src.rename(dst)

    def switch(work, key, legacy=None):
        kf = work / KEY
        cur = kf.read_text().strip() if kf.exists() else (
            legacy if legacy is not None else (key if (wavs(work) or sides(work)) else key))
        if cur == key:
            kf.write_text(key)
            return
        src, sd = wavs(work), sides(work)
        bucket = work / (PREFIX + cur)
        if src or sd:
            bucket.mkdir(exist_ok=True)
            # R220 · 复制留档，根目录那份认领保留
            for n in src:
                shutil.copyfile(work / n, bucket / n)
            for n in sd:
                shutil.copyfile(work / n, bucket / n)
        target = work / (PREFIX + key)
        if target.is_dir() and (wavs(target) or sides(target)):
            for n in src:
                (work / n).unlink()
            for n in sd:
                (work / n).unlink()
            for n in wavs(target):
                move(target / n, work / n)
            for n in sides(target):
                move(target / n, work / n)
        kf.write_text(key)

    bad = 0
    d = None
    try:
        # ① 换音色后已做好的整章成品索引与散段都认领保留，旧套另存副本
        d = pathlib.Path(tempfile.mkdtemp(prefix="r188_a_"))
        for i in range(5):
            (d / ("direct_%04d.wav" % i)).write_bytes(b"A" * 128)
        (d / "chapter_done.json").write_text('[{"index":0,"file":"/audio/chapter_old.wav"}]')
        switch(d, "keyA")
        switch(d, "keyB")
        assert sides(d) == ["chapter_done.json"], "R220：已做好的整章不能丢"
        assert sides(d / (PREFIX + "keyA")) == ["chapter_done.json"], "旧套副本要留住成品索引"
        assert len(wavs(d)) == 5, "R220：已做好的段认领保留，不从头重做"

        # ② 切回原套：成品索引逐字节回来，混合套也留着副本
        switch(d, "keyA")
        assert (d / "chapter_done.json").read_text() == '[{"index":0,"file":"/audio/chapter_old.wav"}]'
        assert len(wavs(d)) == 5
        assert sides(d / (PREFIX + "keyB")) == ["chapter_done.json"], "混合套副本该留着"

        # ③ 只有侧车、没有散段（整章都另存过）也必须留档 + 认领保留
        d2 = pathlib.Path(tempfile.mkdtemp(prefix="r188_b_"))
        (d2 / "chapter_done.json").write_text('[{"index":0,"file":"/audio/chapter_only.wav"}]')
        switch(d2, "keyA")
        switch(d2, "keyB")
        assert sides(d2) == ["chapter_done.json"], "只另存整章的书，已做好的章不能丢"
        assert sides(d2 / (PREFIX + "keyA")) == ["chapter_done.json"]
        shutil.rmtree(d2)

        # ④ 升级平滑：无 key 文件、三 stamp 与当前一致 → 认领，侧车原样留着
        d3 = pathlib.Path(tempfile.mkdtemp(prefix="r188_c_"))
        for i in range(3):
            (d3 / ("direct_%04d.wav" % i)).write_bytes(b"X" * 128)
        (d3 / "redo.json").write_text('["seg:2"]')
        switch(d3, "keyA", legacy="keyA")
        assert len(wavs(d3)) == 3 and sides(d3) == ["redo.json"]
        shutil.rmtree(d3)
        shutil.rmtree(d)
        d = None
        print("逻辑对拍 4/4：换套留住旧索引 / 切回逐字节恢复 / 仅侧车也留档 / 升级不动进度")
    except Exception as ex:
        bad += 1
        print("  逻辑对拍失败:", str(ex)[:110])
        try:
            if d: shutil.rmtree(d)
        except Exception:
            pass
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R188 整章成品索引随套 / CI 回写目标")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R188 整章成品认领保留 / 侧车复制留档 / CI 失败日志写触发分支：PASS")
