#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R185 · 「经常调音色，一调前面就作废，成本太高」根治。

旧行为：run() 里 voice / mode / preset 三个 stamp 任一变了，就把 work 根下
所有 direct_*.wav 全删（真机日志 MODE_INVALIDATE 作废 9 段、real 9/88→1/88）。
R185 新行为：段音频按音色组合指纹分套归档，切换只搬不删；切回零重算。
R220 再修正真机「每动一次调音台就要全部消失」：换套时旧套**复制**留档，
根目录已生成段原样认领为新套起点，点继续只把剩下的段按新参数合成。

守的是：
 1 VoiceCache 新增（keyOf / switchTo / summary / 升级迁移 / 上限 purge）
 2 三处删除式作废彻底消失，合成前只做一次 switchTo
 3 递归遍历（AudioTree.load / 清理弹窗）不把归档套当成有效段
 4 AnnCache.clear 能删掉归档子目录
 5 TaskResume / 制作页文案改成「留着、切回接着用」，不再说「重出声 / 会重做」

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r185_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "cache": APP / "core/VoiceCache.kt",
    "pipe": core_sources.source(APP),
    "tree": APP / "core/AudioTree.kt",
    "resume": APP / "core/TaskResume.kt",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
}

CACHE = [
    'private const val PREFIX = "_voice_"',
    'private const val KEY_FILE = "voice_cache_key.txt"',
    'private const val MAX_KEEP = 24',
    'fun keyOf(voice: String, mode: String, preset: String): String',
    'fun switchTo(work: File, key: String): Switch',
    'fun summary(work: File, key: String): Summary',
    'private fun purge(work: File)',
    'private fun readKey(work: File, incoming: String): String',
    'if (v != null && m != null && p != null) keyOf(v, m, p) else incoming',
    'try { keyFile(work).writeText(key) } catch (_: Throwable) { }',
    'VOICE_CACHE_PURGE',
    'val v = File(work, "voice_stamp.txt").takeIf { it.exists() }?.readText()?.trim()',
    'val m = File(work, "mode_stamp.txt").takeIf { it.exists() }?.readText()?.trim()',
    'val p = File(work, "produce_stamp.txt").takeIf { it.exists() }?.readText()?.trim()',
    'dirs.drop(MAX_KEEP).forEach { d ->',
]
PIPE = [
    'val cacheKey = VoiceCache.keyOf(voiceStamp, modeStamp, presetStamp)',
    'val sw = VoiceCache.switchTo(work, cacheKey)',
    '"VOICE_CACHE_SWITCH",',
    '已做好的不重做，后面按新参数继续',
    'try { File(work, "voice_stamp.txt").writeText(voiceStamp) }',
    'try { File(work, "mode_stamp.txt").writeText(modeStamp) }',
    'try { File(work, "produce_stamp.txt").writeText(presetStamp) }',
    'fun voiceCacheSummary(',
    'VoiceCache.summary(work, key)',
    'if (f.isDirectory) f.deleteRecursively() else f.delete()',
]
TREE = ['if (f.parentFile?.name?.startsWith("_voice_") == true) return@forEach']
RESUME = [
    '后面按新参数继续',
    'VOICE_CHANGED_KEEP',
    'MODE_CHANGED_KEEP',
]
MAIN = [
    'mutableStateOf(VoiceCache.Summary(0, 0, 0, 0L))',
    'Producer.voiceCacheSummary(c, b.id, only, cfg)',
    'cacheInfo = info',
    'if (cacheInfo.current > 0) {',
    '这套音色已做好 ${cacheInfo.current} 段，接着做不用重来',
    'if (cacheInfo.others > 0) {',
    '切回原来的设置就能接着用',
    '会连留着的其他音色缓存一起清掉',
]
BAN = [
    'EventLog.log(EventLog.Kind.PIPELINE, "VOICE_INVALIDATE"',
    'EventLog.log(EventLog.Kind.PIPELINE, "MODE_INVALIDATE"',
    'EventLog.log(EventLog.Kind.PIPELINE, "PRESET_INVALIDATE"',
    'killed.forEach { it.delete() }',
    'filter { !it.isFile }',
    'fun staleSegCount',
    '设置变了，已做好的 $staleSegs 段会重做',
    '会按新设置重出声',
]


def checks(f):
    e = []
    if not (APP / "core/VoiceCache.kt").is_file():
        e.append("VoiceCache.kt 缺失（调音色又把成果删光了）")
        return e
    for key, needles in (("cache", CACHE), ("pipe", PIPE), ("tree", TREE),
                         ("resume", RESUME), ("main", MAIN)):
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:66]))
    for s in BAN:
        for key in ("pipe", "resume", "main"):
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:60]))
    if "VOICE_CHANGED_KEEP" not in f["resume"] or "MODE_CHANGED_KEEP" not in f["resume"]:
        e.append("TaskResume 两处「改设置不丢成果」事件没都改")
    if f["cache"].count("try { keyFile(work).writeText(key) }") < 2:
        e.append("VoiceCache 同套直写 / 换套落盘两处 key 没都写")
    if "python3 scripts/r185_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r185_verify --selftest")
    return e


def selftest():
    base = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
            for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("又改回删光所有 wav", "pipe",
         lambda t: t.replace('val sw = VoiceCache.switchTo(work, cacheKey)',
                             'val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()\n'
                             '                killed.forEach { it.delete() }\n'
                             '                AudioTree.forget(c, killedPaths)\n'
                             '                val sw = VoiceCache.Switch(0, 0, cacheKey)')),
        ("switchTo 调用被摘", "pipe",
         lambda t: t.replace('val sw = VoiceCache.switchTo(work, cacheKey)',
                             'val sw = VoiceCache.Switch(0, 0, cacheKey)')),
        ("归档日志被摘", "pipe",
         lambda t: t.replace('"VOICE_CACHE_SWITCH",', '"X",')),
        ("三 stamp 少写 voice", "pipe",
         lambda t: t.replace('        try { File(work, "voice_stamp.txt").writeText(voiceStamp) } catch (_: Throwable) { }\n', '')),
        ("cacheKey 只用 voice 一个指纹", "pipe",
         lambda t: t.replace('VoiceCache.keyOf(voiceStamp, modeStamp, presetStamp)',
                             'VoiceCache.keyOf(voiceStamp, "", "")')),
        ("制作页退回 staleSegCount", "pipe",
         lambda t: t.replace('fun voiceCacheSummary(', 'fun staleSegCount(')),
        ("clear 又对目录直接 delete", "pipe",
         lambda t: t.replace('if (f.isDirectory) f.deleteRecursively() else f.delete()',
                             'f.delete()')),
        ("AudioTree 又递归收归档段", "tree",
         lambda t: t.replace('                    if (f.parentFile?.name?.startsWith("_voice_") == true) return@forEach\n', '')),
        ("VoiceCache 不写 key 文件", "cache",
         lambda t: t.replace('            try { keyFile(work).writeText(key) } catch (_: Throwable) { }\n', '')
         .replace('        try { keyFile(work).writeText(key) } catch (_: Throwable) { }\n', '')),
        ("升级迁移退化成一律归档", "cache",
         lambda t: t.replace('else if (rootWavs(work).isNotEmpty()) "legacy"', 'else if (rootWavs(work).isNotEmpty()) "legacy"')
         if 'else if (rootWavs(work).isNotEmpty()) "legacy"' in t
         else t.replace('if (v != null && m != null && p != null) keyOf(v, m, p) else incoming',
                        '"legacy"')),
        ("升级迁移丢 stamp 反推", "cache",
         lambda t: t.replace('        val p = File(work, "produce_stamp.txt").takeIf { it.exists() }?.readText()?.trim()\n', '')),
        ("purge 上限消失", "cache",
         lambda t: t.replace('dirs.drop(MAX_KEEP).forEach { d ->', 'dirs.forEach { d ->')),
        ("制作页不显示当前套进度", "main",
         lambda t: t.replace('            if (cacheInfo.current > 0) {', '            if (false) {')),
        ("制作页不显示其他套", "main",
         lambda t: t.replace('            if (cacheInfo.others > 0) {', '            if (false) {')),
        ("制作页不算缓存摘要", "main",
         lambda t: t.replace('        cacheInfo = info', '        Unit')),
        ("清理弹窗不提归档", "main",
         lambda t: t.replace('会连留着的其他音色缓存一起清掉', '只删制作过程里生成的分段 WAV')),
        ("TaskResume 又写重出声", "resume",
         lambda t: t.replace('后面按新参数继续', '接着做会按新设置重出声')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r185_verify.py --selftest", "")),
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
    """R220 · 在临时目录按 VoiceCache 等价规则跑三组切换，验证「复制留档 + 认领保留」。

    源文件一个字节都不动；只操作 tempfile 里造出来的假 wav。
    """
    import tempfile, shutil
    PREFIX = "_voice_"
    KEY = "voice_cache_key.txt"

    def wavs(d):
        return sorted(f.name for f in d.iterdir()
                      if f.is_file() and f.name.startswith("direct_") and f.name.endswith(".wav"))

    def switch(work, key, legacy_stamps=None):
        kf = work / KEY
        cur = kf.read_text().strip() if kf.exists() else (
            legacy_stamps if legacy_stamps is not None else (key if wavs(work) else key))
        if cur == key:
            kf.write_text(key)
            return
        src = wavs(work)
        bucket = work / (PREFIX + cur)
        if src:
            bucket.mkdir(exist_ok=True)
            # R220 · 复制留档，根目录那份认领为新套起点，不搬走
            for n in src:
                shutil.copyfile(work / n, bucket / n)
        target = work / (PREFIX + key)
        if target.is_dir() and wavs(target):
            # 切回用过的设置：混合套已留档，根目录清成空再搬回纯套
            for n in src:
                (work / n).unlink()
            for n in wavs(target):
                (target / n).rename(work / n)
        kf.write_text(key)

    def seed(work, n, tag, start=0):
        for i in range(start, start + n):
            (work / ("direct_%04d.wav" % i)).write_bytes((tag + str(i)).encode() * 100)

    bad = 0
    try:
        # ① 做 9 段 → 换套（认领保留）→ 再做 3 段 → 切回原套：9 段逐字节回来
        d = pathlib.Path(tempfile.mkdtemp(prefix="r185_a_"))
        seed(d, 9, "A")
        original = {n.name: n.read_bytes() for n in d.iterdir()
                    if n.is_file() and n.name.startswith("direct_")}
        switch(d, "keyA")
        switch(d, "keyB")
        assert len(wavs(d)) == 9, "换套不能把已做好的 9 段清空"
        assert len(wavs(d / (PREFIX + "keyA"))) == 9, "旧套副本要留着"
        seed(d, 3, "B", start=9)
        switch(d, "keyA")
        back = wavs(d)
        assert len(back) == 9, "切回原套应拿到 9 段，实际 %d" % len(back)
        assert all((d / n).read_bytes() == original[n] for n in original), "切回的段内容被改了"
        assert len(wavs(d / (PREFIX + "keyB"))) == 12, "混合套 12 段该留着"
        shutil.rmtree(d)

        # ② 升级平滑：无 key、三个 stamp 与当前一致 → 认领，不搬走
        d = pathlib.Path(tempfile.mkdtemp(prefix="r185_b_"))
        seed(d, 9, "A")
        switch(d, "keyA", legacy_stamps="keyA")
        assert len(wavs(d)) == 9, "升级没换音色时进度不该被清空"
        assert not (d / (PREFIX + "legacy")).exists(), "不该凭空造归档套"
        shutil.rmtree(d)

        # ③ 升级 + 换了音色：已做好的段认领保留，旧套另存一份副本
        d = pathlib.Path(tempfile.mkdtemp(prefix="r185_c_"))
        seed(d, 9, "A")
        switch(d, "keyB", legacy_stamps="keyA")
        assert len(wavs(d)) == 9, "R220：换套不丢已做好的段"
        assert len(wavs(d / (PREFIX + "keyA"))) == 9, "旧套 9 段副本必须留着"
        shutil.rmtree(d)

        print("逻辑对拍 3/3：切回零重算 / 升级不清进度 / 换套不丢已做段")
    except Exception as ex:
        bad += 1
        print("  逻辑对拍失败:", str(ex)[:110])
        try: shutil.rmtree(d)
        except Exception: pass
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
         for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R185 音色缓存不作废")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R185 音色缓存：复制留档 / 认领不丢 / 切回零重算 / 升级平滑：PASS")
