# -*- coding: utf-8 -*-
"""R138 · 批次 B「章节表与分章链路」验证。

四件落地：
  B1 编码/BOM 前置（Importer）
  B2 规则集自动选优 + 目录页簇过滤 + 字数兜底（TocRules）
  B3 章节表落盘，四个调用点统一（ChapterPlan）
  B4 删书全量清理（BookStore.purge）+ 旧断点安全迁移（TaskResume）

与别的静态脚本不同：logic_cases() 会**从 TocRules.kt 源码里抽出真实正则文本**
（还原 Kotlin raw string 的 ${'$'} 转义），用 Python 正则跑真实标题样本，
验证的是仓库里那套规则本身，不是另写一份替身。

用法：python3 scripts/r138_verify.py [--selftest]
"""
import core_sources
import base64
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "rules": APP / "core" / "TocRules.kt",
    "plan": APP / "core" / "ChapterPlan.kt",
    "importer": APP / "core" / "Importer.kt",
    "pipeline": core_sources.source(APP),
    "resume": APP / "core" / "TaskResume.kt",
    "hub": APP / "ChapterHub.kt",
    "main": APP / "MainActivity.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "rules": [
        "const val LEGACY = \"legacy_v2\"", "const val BY_SIZE = \"by_size\"",
        "const val SCHEMA = 3", "fun choose(text: String)",
        "fun dropTocCluster(", "fun bySize(text: String)",
        "fun splitMarks(text: String, ruleId: String)",
        "fun legacyCandidates(text: String)", "private val excludePattern",
        "TOC_CLUSTER_MIN", "TOC_HEAD_RATIO", "MAX_TITLE",
    ],
    "plan": [
        "data class ChapterPlanData(", "fun load(c: Context, bookId: String)",
        "private fun save(c: Context, bookId: String, plan: ChapterPlanData)",
        "AtomicJson.write(file(c, bookId)",
        "fun delete(c: Context, bookId: String)",
        "fun resolve(c: Context, book: Book): Pair<List<Chapter>, List<Chunk>>",
        "fun resolve(c: Context, book: Book, text: String)",
        "fun refresh(c: Context, book: Book, ruleId: String? = null)",
        "fun preview(c: Context, book: Book, ruleId: String?)",
        "private fun hasLegacyData(c: Context, bookId: String)",
        "fun currentRuleId(c: Context, bookId: String)",
        "TocRules.LEGACY else null",
    ],
    "importer": [
        "CodingErrorAction.REPORT", "private fun strictUtf8(bytes: ByteArray)",
        "private fun score(s: String): Double", "private fun plainFallback",
        'charset("UTF-16LE")', 'charset("UTF-16BE")', 'charset("UTF-32LE")',
        'charset("UTF-32BE")', "0xEF && b1 == 0xBB && b2 == 0xBF",
        'TocRules.choose(text).second.size', '"文本 · $n 章"',
    ],
    "pipeline": [
        "fun cutFrom(text: String, chapters: List<Chapter>): List<Chunk>",
        "fun fromMarks(text: String, marks: List<Pair<Int, String>>): List<Chapter>",
        "ChapterPlan.resolve(c, book, text)",
        "private fun purge(c: Context, id: String)",
        "ChapterPlan.delete(c, id)", "AudioLibrary.remove(c, it.id)",
        '"work/$id"', "TaskResume.remove(c, id); TaskResume.dequeue(c, id)",
        "BOOK_PURGE", "TaskResume.migrateSplit(c, t.bookId)",
    ],
    "resume": [
        "const val SPLIT_RULE = 3", "fun migrateSplit(c: Context, bookId: String): Boolean",
        "ChapterPlan.resolve(c, book)", "ChapterPlan.currentRuleId(c, t.bookId)",
        "rid == TocRules.LEGACY || rid == ChapterPlan.EPUB",
        "SPLIT_MIGRATE", '"这本书已从书架删掉，没法续跑"',
    ],
    "hub": [
        # I3 · 目录页一次读盘后复用 text，走 resolve 的带文本重载，
        # 既统一到章节表真值源，又不重复读 64MB。
        "ChapterPlan.resolve(c, b, text)", "BookStore.read(c, b)",
        "ChapterPlan.currentRuleId(c, book.id)",
        "ChapterPlan.preview(c, book, null)", "ChapterPlan.refresh(c, book)",
        "showResplit", "resplitBusy", "planTick", "重新分章",
        "章号会变，旧任务需重新确认；分段音频作废，成品仍可听；原书文字不动。",
        "已按${ChapterPlan.ruleName(rid)}重新分为 $n 章 · 旧任务请重新选章",
    ],
    "main": [
        "ChapterPlan.resolve(c, b)",
        "会一起删掉这本书的章节表、已做好的音频、分段缓存、制作任务",
    ],
    "ci": ["python3 scripts/r138_verify.py --selftest"],
}

# 四个调用点必须统一到 ChapterPlan.resolve；ChapterSplitter.split 只允许在
# ChapterPlan 的 EPUB 分支里出现一次（EPUB 走结构化分隔，不经过正则）
UNIFIED = [
    ("hub", "ChapterPlan.resolve(c, b, text)"),
    ("main", "ChapterPlan.resolve(c, b)"),
    ("pipeline", "ChapterPlan.resolve(c, book, text)"),
    ("resume", "ChapterPlan.resolve(c, book)"),
]


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace")
            for k, p in FILES.items()}


def kotlin_regexes(src):
    """抽出仓库里真实的 TocRule 正则文本，还原 ${'$'} 为字面 $。"""
    out = {}
    for m in re.finditer(
            r'TocRule\("([^"]+)",\s*"[^"]*",\s*Regex\("""(.*?)"""\)',
            src, re.S):
        out[m.group(1)] = m.group(2).replace("${'$'}", "$").strip()
    ex = re.search(r'private val excludePattern = Regex\("""(.*?)"""\)', src, re.S)
    if ex:
        out["_exclude"] = ex.group(1).replace("${'$'}", "$").strip()
    return out


def hit(pattern, line):
    try:
        return re.fullmatch(pattern, line) is not None
    except re.error:
        return False


def excluded(ex, line):
    return hit(ex, line) if ex else False


def logic_cases(f):
    """用仓库里的真实正则跑样本，验证「该认的认、不该认的不认」。"""
    r = kotlin_regexes(f["rules"])
    ex = r.get("_exclude")
    bad = []
    must = [
        ("cn_chapter", "第一章 山雨欲来", True),
        ("cn_chapter", "正文 第1章 归来", True),      # 1.1.2 整行 MISS
        ("cn_chapter", "正文 第一章 归来", True),
        ("cn_chapter", "第001回 初入江湖", True),
        ("cn_chapter", "第 一 章 归来", True),
        ("cn_chapter", "第三部分 说明", False),       # 计数词歧义
        ("cn_chapter", "第三节课 笔记", False),
        ("cn_chapter", "　第一章 缩进正文", False),   # 全角缩进视为正文
        ("cn_chapter", "第一章：他回来了。", False),  # 句末标点
        ("cn_numeric", "一、开端", True),              # 旧实现整本 1 章
        ("cn_numeric", "十二. 归来", True),
        ("numeric_sep", "1. 开端", True),
        ("numeric_sep", "001、开端", True),
        ("en_chapter", "Chapter 1 The Beginning", True),
        ("special", "楔子", True),
        ("special", "序章", True),
        ("wrapped", "【第1章】 归来", True),
    ]
    if len(r) < 8:
        bad.append("规则集抽取不全，只拿到 %d 条" % len(r))
        return bad
    for rid, line, want in must:
        got = hit(r.get(rid, "(?!x)x"), line) and not excluded(ex, line)
        if got != want:
            bad.append("规则 %s 对 %r 期望 %s 实得 %s" % (rid, line, want, got))
    print("规则样本 %d/%d 通过" % (len(must) - len(bad), len(must)))

    return bad


def whole_book_cases(f):
    """用仓库里的真实正则，复刻 choose 全流程跑 6 种整书样本。

    断言：带前置目录页的 5 种格式都恰好 6 章（目录页不产生空壳章、
    正文首章不被吞）；完全无标题的书走字数兜底，绝不退回「整本 1 章」。
    """
    r = kotlin_regexes(f["rules"])
    ex = r.get("_exclude")
    gap = int(re.search(r"private const val TOC_GAP = (\d+)",
                        f["rules"]).group(1))
    bad = []

    def scan(text, rid):
        pat = r.get(rid, "(?!x)x")
        out, off = [], 0
        for line in text.split("\n"):
            l = line.rstrip(" \t\r").lstrip("\ufeff")
            if l and len(l) <= 35 and not excluded(ex, l) and hit(pat, l):
                out.append((off, l))
            off += len(line) + 1
        return out

    def cluster(marks, text):
        if len(marks) < 3:
            return marks
        head = max(600, int(len(text) * 0.15))
        start, i = -1, 0
        while i < len(marks) - 1:
            between = text[marks[i][0]:marks[i + 1][0]]
            dense = between.count("\n") <= 1 or marks[i + 1][0] - marks[i][0] < gap
            if dense:
                if start < 0:
                    start = i
                i += 1
            else:
                if start >= 0 and i - start + 1 >= 3 and marks[start][0] < head:
                    return marks[:start] + marks[i + 1:]
                start, i = -1, i + 1
        if start >= 0 and len(marks) - start >= 3 and marks[start][0] < head:
            return marks[:start]
        return marks

    def dedup(marks):
        return [m for i, m in enumerate(marks) if not any(
            marks[j][1] == m[1] and marks[j][0] - m[0] > 1000
            for j in range(i + 1, len(marks)))]

    def choose(text):
        best, score, bm = None, 0, []
        for rid in r:
            if rid.startswith("_"):
                continue
            m = dedup(cluster(scan(text, rid), text))
            eff = 1 if m else 0
            for k in range(1, len(m)):
                if m[k][0] - m[k - 1][0] > 1000:
                    eff += 1
            if eff > score:
                score, best, bm = eff, rid, m
        if best and len(bm) >= 2:
            return best, bm
        return "by_size", [(i, "第 %d 节" % (n + 1))
                          for n, i in enumerate(range(0, len(text), 8000))]

    def book(fmt, n=6, body=900):
        cn = "零一二三四五六七八九十"
        parts = ["《测试书》", "作者：某人", "目录", ""] + [
            fmt.format(i=i, cn=cn[i]) for i in range(1, n + 1)]
        parts += ["", "======= 正文 =======", ""]
        for i in range(1, n + 1):
            parts.append(fmt.format(i=i, cn=cn[i]))
            parts.append(("他转身走了。" * 200)[:body])
            parts.append("")
        return "\n".join(parts)

    cases = [
        ("第X章 + 前置目录页", "第{i}章 归来"),
        ("正文 第X章", "正文 第{i}章 归来"),
        ("中文数字 一、二、", "{cn}、归来"),
        ("纯数字 1. 2.", "{i}. 归来"),
        ("Chapter N", "Chapter {i} The Beginning"),
        ("完全无标题（字数兜底）", None),
    ]
    for name, fmt in cases:
        text = book(fmt) if fmt else "他转身走了。" * 4000
        rid, marks = choose(text)
        want = 6 if fmt else None
        if want is not None and len(marks) != want:
            bad.append("整书 %s：选中 %s，得到 %d 章，期望 %d 章"
                       % (name, rid, len(marks), want))
        if want is None and len(marks) < 2:
            bad.append("整书 %s：无标题书退回整本 1 章，字数兜底失效" % name)
    print("整书样本 %d/%d 通过" % (len(cases) - len(bad), len(cases)))
    return bad


def bom_cases(f):
    """证明 BOM 分支真的拦住了 UTF-16LE → GB18030 的旧误判路径。"""
    src = f["importer"]
    bad = []
    text = "第一章 归来\n" + "他转身走了。" * 200
    b = text.encode("utf-16")  # 带 BOM 的 UTF-16LE
    if not (b[0] == 0xFF and b[1] == 0xFE):
        bad.append("样本不是 UTF-16LE BOM")
    if "b0 == 0xFF && b1 == 0xFE" not in src:
        bad.append("源码里没有 UTF-16LE BOM 判定")
    # 旧算法：按 UTF-8 数替换符，再选 GB18030（替换符更少）→ 乱码
    u8 = b.decode("utf-8", errors="replace")
    old_bad = u8.count("\ufffd")
    gbk = b.decode("gb18030", errors="replace")
    old_picked_gbk = gbk.count("\ufffd") < old_bad / 2
    if not old_picked_gbk:
        bad.append("旧误判样本不成立，用例失效")
    else:
        print("旧算法会误选 GB18030（替换符 %d → %d），新算法由 BOM 分支直接拦下"
              % (old_bad, gbk.count("\ufffd")))
    # 新算法：BOM 命中后按 UTF-16LE 解，结果应还原原文
    if "String(bytes, 2, bytes.size - 2, charset(\"UTF-16LE\"))" not in src:
        bad.append("BOM 分支没有按 UTF-16LE 解码")
    # UTF-8 BOM 必须跳过 3 字节
    if "String(bytes, 3, bytes.size - 3, Charsets.UTF_8)" not in src:
        bad.append("UTF-8 BOM 没有跳过 3 字节")
    print("编码/BOM 用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:70]))
    for key, needle in UNIFIED:
        if needle not in f[key]:
            e.append("调用点未统一[%s]: %s" % (key, needle))
    if "BookStore.read(c, b)" not in f["hub"]:
        e.append("目录页没复用同一次读盘（首章前诊断会多读一遍整本）")
    # ChapterSplitter.split 只能出现在 ChapterPlan 的 EPUB 分支
    if "ChapterSplitter.split(" in f["pipeline"]:
        e.append("制作运行又退回直接 split（会与目录页章号不一致）")
    if "ChapterSplitter.split(" in f["hub"] or "ChapterSplitter.split(" in f["main"]:
        e.append("UI 又退回直接 split")
    if "ChapterSplitter.split(text)" not in f["plan"]:
        e.append("EPUB 结构化分隔分支丢失")
    # 旧编码写法不能回来
    if 'val bad = utf8.count { it == \'\\uFFFD\' }' in f["importer"]:
        e.append("旧编码判定（只数替换符）回潮")
    # 旧删除只删原书的写法不能回来
    if "BOOK_PURGE" not in f["pipeline"]:
        e.append("删书全量清理丢失")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    logic_bad = len(logic_cases(f)) + len(whole_book_cases(f))
    bom_bad = len(bom_cases(f))
    orig = {k: v for k, v in f.items()}
    cuts = [
        ("规则集被摘", "rules", "fun choose(text: String)", "/* removed */"),
        ("目录页簇被摘", "rules", "fun dropTocCluster(", "/* removed */"),
        ("字数兜底被摘", "rules", "fun bySize(text: String)", "/* removed */"),
        ("老书兼容被摘", "plan", "private fun hasLegacyData", "/* removed */"),
        ("章节表落盘被摘", "plan", "AtomicJson.write(file(c, bookId)", "/* removed */"),
        ("BOM 判定被摘", "importer", "CodingErrorAction.REPORT", ""),
        ("严格 UTF-8 被摘", "importer", "private fun strictUtf8", "/* removed */"),
        ("TXT 章数被摘", "importer", "TocRules.choose(text).second.size", ""),
        ("删书全量清理被摘", "pipeline", "BOOK_PURGE", ""),
        ("迁移函数被摘", "resume", "fun migrateSplit", "/* removed */"),
        ("重新分章入口被摘", "hub", "重新分章", ""),
        ("预览被摘", "hub", "ChapterPlan.preview(c, book, null)", ""),
        ("CI 漏挂 r138", "ci", "python3 scripts/r138_verify.py --selftest", ""),
    ]
    bad = 0
    try:
        for name, key, needle, repl in cuts:
            x = dict(f)
            x[key] = f[key].replace(needle, repl)
            if not check(x):
                bad += 1
                print("  反向注入漏放:", name)
    finally:
        # R141 · 反向注入全程只改内存 dict（check(x) 不读盘），原来每跑一次
        # selftest 就把全部文件无条件写回 15 遍，纯属空转。改成「确实变了才写」，
        # 保留防误改的保险，但去掉 100+ 次无谓写盘。
        for k, v in orig.items():
            try:
                if FILES[k].read_text(encoding="utf-8", errors="replace") != v:
                    FILES[k].write_text(v, encoding="utf-8")
            except (OSError, UnicodeError):
                FILES[k].write_text(v, encoding="utf-8")
    total = len(cuts)
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad + logic_bad + bom_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f)
    e += logic_cases(f)
    e += whole_book_cases(f)
    e += bom_cases(f)
    if e:
        print("R138 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R138 批次B：规则集 / 章节表落盘 / 编码前置 / 删书清理 / 旧任务迁移：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
