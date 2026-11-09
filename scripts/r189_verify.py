#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R189 · 自己接着扫的两处真问题 + 重构轮第一块。

A clearDone 跨套污染：AudioTree.clearDone 递归找 chapter_done.json 时会走进
   R188 搬进 _voice_xxx/ 的归档套，删当前套某章音频把旧套同章记录也删了，
   切回旧音色后成品还在、索引没了 → 重复合成重复拼。
B 三处 AudioTree.forget 仍是「删完再 filter !isFile」的脆弱写法。
C r135 CURRENT_VERIFIERS 补齐到 r189，并加自动防漏（CI 里现行批次未登记即红）。

selftest 纯内存注入，源文件零写入。
用法：python3 scripts/r189_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

FILES = {
    "tree": APP / "core/AudioTree.kt",
    "hub": APP / "ChapterHub.kt",
    "main": ui_sources.source(APP),
    "r135": ROOT / "scripts/r135_verify.py",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r137": ROOT / "scripts/r137_verify.py",
}

TREE = [
    '归档套里的 chapter_done.json 属于另一套音色',
    'it.parentFile?.name?.startsWith("_voice_") != true',
]
HUB = [
    'val killedPaths = files.map { it.absolutePath }',
    'val killedPaths = segs.map { it.absolutePath }',
    'AudioTree.forget(c, killedPaths)',
]
# R195 · MainActivity 里的删段弹窗随独立进度页删净；现行实现在 ChapterHub，
# 由上面的 HUB 块按符号守。MAIN 不再绑死已删文件。
MAIN = []
R135 = [
    '"r169_verify.py", "r170_verify.py", "r171_verify.py"',
    '"r185_verify.py", "r188_verify.py",',
    '"r189_verify.py"',
    '现行守门漏登记 CURRENT_VERIFIERS',
    '新增守门忘登记',
]
CI = [
    'python3 scripts/r189_verify.py --selftest',
    'python3 scripts/r189_verify.py',
]
R137 = [
    'def check(sources=None):',
    '纯内存，源文件零写入',
    'v = dict(base)',
]
BAN = [
    'filter { !it.isFile }',
    'path.write_text(mutate(',   # R191 · 502 事故的直接形态
]


def checks(f):
    e = []
    for key, needles in (("tree", TREE), ("hub", HUB), ("main", MAIN),
                         ("r135", R135), ("ci", CI), ("r137", R137)):
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:66]))
    for s in BAN:
        for key in ("tree", "hub", "main", "r137"):
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:56]))
    # 清单必须真的包含 CI 里所有现行批次守门
    live = set(__import__("re").findall(
        r'scripts/(r(?:1[6-9]\d|[2-9]\d\d)_verify\.py)', f["ci"]))
    declared = set(__import__("re").findall(r'"(r\d+_verify\.py)"', f["r135"]))
    gap = sorted(live - declared - {"r135_verify.py"})
    if gap:
        e.append("清单仍有漏登记: " + ", ".join(gap))
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("clearDone 又把归档套算进去", "tree",
         lambda t: t.replace(' &&\n                        it.parentFile?.name?.startsWith("_voice_") != true }',
                             ' }')),
        ("clearDone 只留 isFile 判据", "tree",
         lambda t: t.replace('                .filter { it.isFile && it.name == "chapter_done.json" &&\n'
                             '                        it.parentFile?.name?.startsWith("_voice_") != true }\n'
                             '                .forEach { f ->\n',
                             '                .filter { it.isFile && it.name == "chapter_done.json" }.forEach { f ->\n')),
        ("ChapterHub 单章删又删完再 filter", "hub",
         lambda t: t.replace('                            val killedPaths = files.map { it.absolutePath }\n'
                             '                            val failed = AudioOps.deleteAll(files)\n'
                             '                            AudioTree.forget(c, killedPaths)',
                             '                            val failed = AudioOps.deleteAll(files)\n'
                             '                            AudioTree.forget(c,\n'
                             '                                files.filter { !it.isFile }.map { it.absolutePath })')),
        ("ChapterHub 整章删又删完再 filter", "hub",
         lambda t: t.replace('                            val killedPaths = segs.map { it.absolutePath }\n'
                             '                            failed += AudioOps.deleteAll(segs)\n'
                             '                            merged?.let { if (!AudioOps.deleteMerged(c, it)) failed++ }\n'
                             '                            AudioTree.forget(c, killedPaths)',
                             '                            failed += AudioOps.deleteAll(segs)\n'
                             '                            merged?.let { if (!AudioOps.deleteMerged(c, it)) failed++ }\n'
                             '                            AudioTree.forget(c, segs.filter { !it.isFile }.map { it.absolutePath })')),
        # R195 · MainActivity 删段弹窗随独立进度页删净，此例退役
        ("清单删掉 r188 登记", "r135",
         lambda t: t.replace(', "r188_verify.py"', '', 1)),
        ("清单删掉整批补齐", "r135",
         lambda t: __import__("re").sub(
             r'           # R189 · 补齐.*?\n(?:           "r\d+_verify\.py"[^\n]*\n)+',
             '           "aoci_check.py"]\n', t, flags=__import__("re").S)),
        ("防漏检查被摘", "r135",
         lambda t: t.replace('    missing = sorted(live - set(CURRENT_VERIFIERS) - {"r135_verify.py"})\n'
                             '    if missing:\n'
                             '        errs.append("现行守门漏登记 CURRENT_VERIFIERS: " + ", ".join(missing))',
                             '    missing = []')),
        ("CI 出现未登记的新守门", "ci",
         lambda t: t.replace('      - name: R188 侧车随套\n',
                             '      - name: 新守门\n        run: python3 scripts/r199_verify.py\n'
                             '      - name: R188 侧车随套\n')),
        ("CI 漏挂 r189", "ci",
         lambda t: t.replace("python3 scripts/r189_verify.py --selftest", "")),
        ("r137 退回写盘注入", "r137",
         lambda t: t.replace('def check(sources=None):', 'def check():', 1)),
        ("r137 又出现 write_text 注入", "r137",
         lambda t: t.replace("    for name, key, mutate in cases:",
                             "    for name, key, mutate in cases:" + chr(10) +
                             "        path.write_text(mutate(", 1)),
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
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R189 归档套隔离 / 先收路径 / 守门清单")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R189 归档套不被误改 / forget 先收路径 / 现行守门清单零漏登记：PASS")
