#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R172 · 发版前四件（审查报告 P0 + P1-2/3/4）验证。

  1. 拼接成品改流式：不再把全书分段 PCM 一次性读进堆（长书必 OOM）
  2. books.json / pins.json 走 AtomicJson：索引损坏时拒绝覆写，不抹掉旧书
  3. 导入闸门前置到读字节阶段 + OOM 捕获覆盖解码 + 编码探测只采样
  4. 重新分章换表同时作废旧任务 / 队列 / 分段与章号索引，用 epoch 拦续跑

R170 事故后规矩：selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r172_verify.py [--selftest]
"""
import ui_sources
import core_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": core_sources.source(SRC),
    "imp": SRC / "core/Importer.kt",
    "plan": SRC / "core/ChapterPlan.kt",
    "resume": SRC / "core/TaskResume.kt",
    "hub": SRC / "ChapterHub.kt",
    "main": ui_sources.source(SRC),
    "ci": ROOT / ".github/workflows/ci.yml",
}

PIPE_MUST = [
    # ① 流式拼接
    "private fun dataSpan(f: File): Pair<Long, Long>?",
    "java.io.RandomAccessFile(f, \"r\")",
    "private fun writePcm(",
    "private fun copyRaw(",
    "os.write(header(total.toInt()))",
    "if (total <= 0L || total > Int.MAX_VALUE - 36L)",
    '"MERGE_TOO_LARGE"',
    '"MERGE_DONE"',
    # ② 书架原子写
    "AtomicJson.read(meta(c))",
    '"BOOK_INDEX_CORRUPT"',
    '"BOOK_INDEX_RECOVERED"',
    '"BOOK_WRITE_BLOCKED"',
    "val ok = AtomicJson.write(meta(c), a.toString())",
    "AtomicJson.write(pinFile(c), a.toString())",
    '"书架索引没写进磁盘，已取消导入，旧书没动"',
    # ④ 建任务带章节表代数
    "planEpoch = ChapterPlan.epoch(c, book.id)",
    '"《${fixed.title}》重新分过章，旧任务不能接着用；请重新选章制作。"',
]
PIPE_BAN = [
    "val chunks = parts.mapNotNull",
    "private fun pcmOf(",
    "chunks.forEachIndexed",
    "chunks.sumOf",
    "meta(c).writeText(a.toString())",
    "pinFile(c).writeText(a.toString())",
]

IMP_MUST = [
    "const val MAX_TXT_BYTES = 24 * 1024 * 1024",
    "MAX_TXT_BYTES.toLong()",
    "readLimited(it, MAX_TXT_BYTES.toLong(), MAX_BYTES.toLong())",
    "buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()",
    'if (n >= 2 &&',
    "if (total > limit)",
    "checkText(r.first)",
    "catch (e: OutOfMemoryError)",
    "ENC_SAMPLE",
    "bytes.copyOf(n)",
    '"手机内存不够读这本书，关掉别的应用再试"',
]
IMP_BAN = [
    "private fun readLimited(s: InputStream): ByteArray",
    "if (total > MAX_BYTES)",
    "var best = String(bytes, Charsets.UTF_8)",
]

PLAN_MUST = [
    "val epoch: Int = 0",
    'o.optInt("epoch", 0)',
    '.put("epoch", plan.epoch)',
    "fun epoch(c: Context, bookId: String): Int",
    "private fun invalidateForResplit",
    'phase = "reconfirm"',
    "TaskResume.dequeue(c, bookId)",
    "work.deleteRecursively()",
    '"RESPLIT_INVALIDATE"',
    "nextEpoch",
    "        invalidateForResplit(c, book.id)",
    "chapters, nextEpoch))",
]

RESUME_MUST = [
    "val planEpoch: Int = 0",
    '.put("planEpoch", t.planEpoch)',
    'o.optInt("planEpoch", 0)',
    "t.planEpoch != ChapterPlan.epoch(c, t.bookId)",
    't.phase == "reconfirm"',
]

UI_MUST = {
    "hub": ['t.phase == "reconfirm" -> "需重新确认"',
            't.phase != "reconfirm"'],
    "main": ['t.phase == "reconfirm" -> "需重新确认"',
             't.phase != "reconfirm"'],
}


def merger_body(t):
    a = t.find("object WavMerger {")
    b = t.find("\ndata class Progress(", a)
    return t[a:b] if a >= 0 and b > a else ""


def read_body(t):
    a = t.find("    fun read(c: Context, uri: Uri, mimeHint: String? = null): Result {")
    b = t.find("    private fun checkText(text: String)", a)
    return t[a:b] if a >= 0 and b > a else ""


def checks(f):
    e = []
    for s in PIPE_MUST:
        if s not in f["pipe"]:
            e.append("主链能力缺失: " + s[:64])
    for s in PIPE_BAN:
        if s in f["pipe"]:
            e.append("主链旧形态回潮: " + s[:60])
    if f["pipe"].count("if (indexCorrupt(c))") < 2:
        e.append("书架损坏时的写入 / 导入双闸门不齐")
    mb = merger_body(f["pipe"])
    if not mb or "f.readBytes()" in mb or "copyOfRange(i + 8" in mb:
        e.append("拼接器仍在整段 readBytes / 一次性驻留全书 PCM")
    if "private fun dataSpan" not in mb or "writePcm(" not in mb:
        e.append("拼接器没走「扫头 + 逐段流式写」")
    for s in IMP_MUST:
        if s not in f["imp"]:
            e.append("导入能力缺失: " + s[:60])
    for s in IMP_BAN:
        if s in f["imp"]:
            e.append("导入旧形态回潮: " + s[:60])
    rb = read_body(f["imp"])
    if not rb or rb.find("catch (e: OutOfMemoryError)") < rb.find("plainToText(bytes)"):
        e.append("OOM 捕获没包住整本解码段")
    for s in PLAN_MUST:
        if s not in f["plan"]:
            e.append("章节表能力缺失: " + s[:60])
    for s in RESUME_MUST:
        if s not in f["resume"]:
            e.append("任务失效能力缺失: " + s[:60])
    for key, needles in UI_MUST.items():
        for s in needles:
            if s not in f[key]:
                e.append("界面守恒缺失[%s]: %s" % (key, s[:56]))
    if "python3 scripts/r172_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r172_verify --selftest")
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("拼接退回全量驻留", "pipe",
         lambda t: t.replace("private fun dataSpan(f: File): Pair<Long, Long>?",
                             "private fun noDataSpan(f: File): Pair<Long, Long>?")),
        ("拼接不流式写了", "pipe",
         lambda t: t.replace("private fun writePcm(", "private fun noWritePcm(")),
        ("去掉 WAV 长度上限", "pipe",
         lambda t: t.replace("if (total <= 0L || total > Int.MAX_VALUE - 36L)",
                             "if (false)")),
        ("书架退回裸写", "pipe",
         lambda t: t.replace("val ok = AtomicJson.write(meta(c), a.toString())",
                             "meta(c).writeText(a.toString()); val ok = true")),
        ("损坏时仍允许覆写", "pipe",
         lambda t: t.replace("    private fun save(c: Context, books: List<Book>): Boolean {\n        if (indexCorrupt(c))",
                             "    private fun save(c: Context, books: List<Book>): Boolean {\n        if (false)")),
        ("导入闸门退回 64MB", "imp",
         lambda t: t.replace("MAX_TXT_BYTES.toLong()", "MAX_BYTES.toLong()")),
        ("zip 容器误拒回归", "imp",
         lambda t: t.replace("buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()",
                             "false")),
        ("去掉读字节闸门", "imp",
         lambda t: t.replace("if (total > limit)", "if (false)")),
        ("OOM 不覆盖解码", "imp",
         lambda t: t.replace("catch (e: OutOfMemoryError)", "catch (e: RuntimeException)")),
        ("编码探测又跑全文", "imp",
         lambda t: t.replace("bytes.copyOf(n)", "bytes")),
        ("重新分章不作废", "plan",
         lambda t: t.replace("        invalidateForResplit(c, book.id)", "")),
        ("章节表代数不递增", "plan",
         lambda t: t.replace("chapters, nextEpoch))", "chapters, 0))")),
        ("任务不带代数", "pipe",
         lambda t: t.replace("planEpoch = ChapterPlan.epoch(c, book.id)", "planEpoch = 0")),
        ("失效检查被摘", "resume",
         lambda t: t.replace("t.planEpoch != ChapterPlan.epoch(c, t.bookId)", "false")),
        ("reconfirm 仍能一键续跑", "hub",
         lambda t: t.replace('t.phase != "reconfirm"', "true")),
        ("任务页 badge 不认 reconfirm", "main",
         lambda t: t.replace('t.phase == "reconfirm" -> "需重新确认"',
                             't.phase == "reconfirm" -> "已中断"')),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r172_verify.py --selftest", "")),
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
        print("FAIL · R172")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R172 四件：流式拼接 / 书架原子写 / 导入闸门前置 / 重新分章作废：PASS")
