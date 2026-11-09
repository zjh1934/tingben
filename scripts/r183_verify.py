#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R183 · 真机日志（用户装 1.1.7 后导出，2026-11-09 14:12）实证四件。

日志里三个硬错误（segmap 写失败 / free=0 / 试听 code=-2）都是 1.1.3~1.1.4
时代，R148/R165 已修，不重复做。这轮守的是当前版本仍欠的四件：

 A 试听失败甩裸错误码「没生成声音，code=-2」（用户连试 4 次），正式合成
   也是「引擎返回 -2」→ 共用 SynthErrors 中文原因 + 下一步。
 B 每段实测 50~220 秒，用户等 4 分 23 秒只出 1 段就点了停止，界面只有
   「已生成 1/88」→ 进度带「按当前速度还需约 X 小时 Y 分」。
 C/D 原为「重做预告 / 三处作废先收路径再删」，R185 之后改为音色分套缓存
   （切换只搬不删、切回零重算），本守门的对应判据同步换成新语义，未放宽。

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r183_verify.py [--selftest]
"""
import core_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "pipe": core_sources.source(APP),
    "err": APP / "core/SynthErrors.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}
ERR = [
    'object SynthErrors',
    'fun reason(code: Int, wrote: Boolean): String',
    'fun explain(code: Int, wrote: Boolean): String',
    'code == -1 -> "本地引擎还没加载"',
    'code == -10 -> "音频写不进磁盘"',
    'code == -11 -> "引擎返回的音频格式不对"',
    'code < 0 -> "模型没算出声音，多半是内存不够或模型文件没齐"',
    '关掉别的应用腾内存',
]
MAIN = [
    'SynthErrors.explain(code, f.exists() && f.length() > 44)',
    'mutableStateOf(VoiceCache.Summary(0, 0, 0, 0L))',
    'Producer.voiceCacheSummary(c, b.id, only, cfg)',
    'cacheInfo = info',
    'Text("每段约 1–3 分钟，块越多等得越久"',
    'if (cacheInfo.others > 0) {',
    '切回原来的设置就能接着用',
]
PIPE = [
    'private fun formatEta(ms: Long): String',
    '(ms / 60000L).coerceAtLeast(1L)',
    # R185 取代：制作前不再预告「会重做」，改说「这套/其他套各做了几段」；
    # 三处删除式作废也换成一次归档切换，一个字节都不删。
    'fun voiceCacheSummary(',
    'VoiceCache.keyOf(Prefs.voiceStamp(c),',
    'VoiceCache.summary(work, key)',
    'val cacheKey = VoiceCache.keyOf(voiceStamp, modeStamp, presetStamp)',
    'val sw = VoiceCache.switchTo(work, cacheKey)',
    '"VOICE_CACHE_SWITCH",',
    '        try { File(work, "voice_stamp.txt").writeText(voiceStamp) }',
    'var segCostSum = 0L',
    'val segT0 = System.currentTimeMillis()',
    'segCostSum += (System.currentTimeMillis() - segT0).coerceAtLeast(0L)',
    'if (eta > 0L) " · 按当前速度还需约 ${formatEta(eta)}" else ""',
    'SynthErrors.reason(lastCode, false)',
]
BAN = [
    '没生成声音，code=$code',
    '引擎返回 $lastCode',
    'AudioTree.forget(c, killed.filter { !it.isFile }',
    'killed.forEach { it.delete() }',
    'filter { !it.isFile }',
    '设置变了，已做好的 $staleSegs 段会重做',
]


def checks(f):
    e = []
    if not (APP / "core/SynthErrors.kt").is_file():
        e.append("SynthErrors.kt 缺失（试听失败又只剩裸错误码）")
        return e
    for key, needles in (("err", ERR), ("main", MAIN), ("pipe", PIPE)):
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:66]))
    for s in BAN:
        for key in ("main", "pipe"):
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:56]))
    if '"VOICE_CACHE_SWITCH",' not in f["pipe"]:
        e.append("R185 换套不再留归档日志（成果可能被静默删掉）")
    if "python3 scripts/r183_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r183_verify --selftest")
    return e


def selftest():
    base = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
            for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("试听退回裸错误码", "main",
         lambda t: t.replace('SynthErrors.explain(code, f.exists() && f.length() > 44)',
                             '"没生成声音，code=$code"')),
        ("正式合成退回裸错误码", "pipe",
         lambda t: t.replace('SynthErrors.reason(lastCode, false)',
                             '"引擎返回 $lastCode"')),
        ("错误映射删掉 -1", "err",
         lambda t: t.replace('code == -1 -> "本地引擎还没加载"',
                             'code == -1 -> "错误"')),
        ("错误映射删掉内存提示", "err",
         lambda t: t.replace('关掉别的应用腾内存', '再试一次')),
        ("ETA 拼接被摘", "pipe",
         lambda t: t.replace('if (eta > 0L) " · 按当前速度还需约 ${formatEta(eta)}" else ""',
                             '""')),
        ("段耗时不再累计", "pipe",
         lambda t: t.replace('                    segCostSum += (System.currentTimeMillis() - segT0).coerceAtLeast(0L)\n', '')),
        ("formatEta 删掉", "pipe",
         lambda t: t.replace('    private fun formatEta(ms: Long): String {',
                             '    private fun unusedEta(ms: Long): String {')),
        ("缓存摘要恒返回空", "pipe",
         lambda t: t.replace('        VoiceCache.summary(work, key)',
                             '        VoiceCache.Summary(0, 0, 0, 0L)')),
        ("换套调用被摘", "pipe",
         lambda t: t.replace('val sw = VoiceCache.switchTo(work, cacheKey)',
                             'val sw = VoiceCache.Switch(0, 0, cacheKey)')),
        ("归档日志被摘", "pipe",
         lambda t: t.replace('"VOICE_CACHE_SWITCH",', '            "X",')),
        ("作废又删 wav", "pipe",
         lambda t: t.replace('        val sw = VoiceCache.switchTo(work, cacheKey)',
                             '        val killed = work.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") } ?: emptyList()\n'
                             '        killed.forEach { it.delete() }\n'
                             '        val sw = VoiceCache.switchTo(work, cacheKey)')),
        ("制作页不显示其他套提示", "main",
         lambda t: t.replace('            if (cacheInfo.others > 0) {', '            if (false) {')),
        ("制作页不算缓存摘要", "main",
         lambda t: t.replace('        cacheInfo = info', '        Unit')),
        ("量级文案被删", "main",
         lambda t: t.replace('            Text("每段约 1–3 分钟，块越多等得越久", fontSize = 11.sp, color = Sub)\n', '')),
        ("forget 旧形态回潮", "pipe",
         lambda t: t.replace('        val sw = VoiceCache.switchTo(work, cacheKey)',
                             '        AudioTree.forget(c, killed.filter { !it.isFile }.map { it.absolutePath })\n'
                             '        val sw = VoiceCache.switchTo(work, cacheKey)')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r183_verify.py --selftest", "")),
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
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: (p.read_text(encoding="utf-8") if p.is_file() else "")
         for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R183 真机实证四件")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R183 真机实证：中文错误原因 / 进度 ETA / 旧段重做预告 / 作废语义：PASS")
