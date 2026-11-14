# -*- coding: utf-8 -*-
"""R339 · 重新分章「预览」与「应用」必须同一条分章口径。

真机形态：EPUB 书在 ChapterPlan.build() 里走 CHAPTER_SEP 结构化分隔，
旧 preview() 却自己再跑一遍 TocRules.splitMarks 按 TXT 正则扫章 ——
弹窗显示「识别 7 章 / 按字数分节」+ 正则命中的标题样例，点「应用」
后 Toast 变成「EPUB 内置目录 42 章」。用户是看着错的预览按的确认，
而弹窗后果说明写着「分段音频作废」。TXT 书两边恰好同数，所以一直没露。

现在 preview 直接复用 build 的真实章节表（章数 / 规则名 / 标题样例
与点应用后完全一致），规则集本身一字未改。

用法：python3 scripts/r339_verify.py [--selftest]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "plan": APP / "core" / "ChapterPlan.kt",
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
}

POS = {
    "plan": [
        "private fun ruleFor(text: String, ruleId: String?): String",
        "val rid = ruleFor(text, forcedRule)",
        "return EPUB to chapters",
        "val (rid, chapters) = build(text, ruleFor(text, ruleId))",
        "val titles = chapters.map { it.title }",
        "return rid to (chapters.size to shown)",
        "fun refresh(c: Context, book: Book, ruleId: String? = null)",
        "fun preview(c: Context, book: Book, ruleId: String?)",
        "EPUB -> \"EPUB 内置目录\"",
    ],
    "hub": [
        "ChapterPlan.preview(c, book, null)",
        "ChapterPlan.refresh(c, book)",
    ],
    "ci": ["python3 scripts/r339_verify.py --selftest"],
}

BAN = {
    # 旧 preview 的四行：自己选规则、自己扫 marks、用 marks 的标题与章数
    "plan": [
        "val rid = ruleId ?: TocRules.choose(text).first",
        "build(text, ruleId ?: TocRules.choose(text).first)",
        "val marks = TocRules.splitMarks(text, rid)",
        "val titles = marks.map { it.second }",
        "return rid to (marks.size to shown)",
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def preview_body(plan):
    """只截 preview 函数体，避免全文件 BAN 把 build 里合法的 splitMarks 误伤。"""
    m = re.search(r"fun preview\(.*?\n    \}", plan, re.S)
    return m.group(0) if m else ""


def logic_cases(f):
    """用真实样本证明：旧预览口径对 EPUB 给出的章数与实际应用不同。"""
    sep = "\uE000"
    bad = []

    # 三章 EPUB：结构化分隔段数 = 3
    text = "\n\n".join(
        sep + "第%d章 标题%d\n" % (i, i) + "他转身走了。" * 20
        for i in range(1, 4))

    def build_epub_count(t):
        # 复刻 ChapterSplitter.split 的 EPUB 分支：split(SEP) 后丢空段
        return len([s for s in t.split(sep) if s.strip()])

    def old_preview_count(t):
        # 复刻旧 preview：对含 SEP 的文本按 TXT 规则选优，命中不足 2 走字数兜底。
        # 短样本里正则命中的独占标题行只有 0~1 条，choose 退化成按字数分节。
        return max(1, (len(t) + 7999) // 8000)

    real, old = build_epub_count(text), old_preview_count(text)
    if real != 3:
        bad.append("结构化分隔章数复刻不对：%d" % real)
    if old == real:
        bad.append("样本没复现旧 bug：旧预览 %d 章与实际 %d 章相同，用例失效" % (old, real))
    else:
        print("EPUB 回归样本：旧预览会给 %d 章，实际应用是 %d 章" % (old, real))

    body = preview_body(f["plan"])
    if not body:
        bad.append("抽不到 preview 函数体")
    else:
        if "build(" not in body:
            bad.append("preview 没有复用 build，仍是两套口径")
        if "TocRules.splitMarks" in body:
            bad.append("preview 又退回自己扫 marks")
        if "chapters.size" not in body:
            bad.append("preview 的章数不是真实章节表的 size")
        if "chapters.map { it.title }" not in body:
            bad.append("preview 的标题样例不是真实章节标题")
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:66]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:66]))
    body = preview_body(f["plan"])
    if "build(text, ruleFor(text, ruleId))" not in body:
        e.append("preview 没走统一规则选择")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    bad = 0
    cuts = {}
    for i, needle in enumerate(POS["plan"]):
        cuts["plan正向%d" % i] = ("plan", needle, "/* removed */")
    cuts.update({
        "hub预览被摘": ("hub", "ChapterPlan.preview(c, book, null)", "/* removed */"),
        "hub应用被摘": ("hub", "ChapterPlan.refresh(c, book)", "/* removed */"),
        "CI 漏挂 r339": ("ci", "python3 scripts/r339_verify.py --selftest", ""),
        "预览改回自己扫 marks": ("plan", "val titles = chapters.map { it.title }",
                              "val titles = marks.map { it.second }\n        val marks = TocRules.splitMarks(text, rid)"),
        "章数改回 marks": ("plan", "return rid to (chapters.size to shown)",
                         "return rid to (marks.size to shown)"),
    })
    for name, (k, needle, repl) in cuts.items():
        x = dict(f)
        x[k] = f[k].replace(needle, repl)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for k, olds in BAN.items():
        for old in olds:
            x = dict(f)
            x[k] = f[k] + "\n// " + old + "\n"
            if not check(x):
                bad += 1
                print("  旧形态注入漏放:", old[:44])
    total = len(cuts) + sum(len(v) for v in BAN.values())
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R339 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R339 重新分章预览/应用同口径：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
