#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R173 · Kotlin 结构级编译守门（沙盒无 SDK 时的最大风险兜底）。

R169~R172 的 Compose / Kotlin 改动在本沙盒从未真编译过：只有 java，
没有 kotlinc / javac / gradle / android.jar，git 传输又被出网策略挡住。
真编译归 CI，但「加了字段却漏改构造调用 / 命名参数拼错 / 括号不配平 /
encode 与 decode 字段不成对」这类**一定编译不过**的错误可以静态抓出来。

做法：先把字符串、字符字面量、行注释、块注释等长抹成空格（保证下标不漂移），
再在干净文本上做括号配平、顶层逗号切分、data class 主构造参数提取，
逐一核对所有构造调用的实参个数与命名参数名。

selftest 全程纯内存注入，一个字节都不写源文件（R170 事故后的规矩）。
用法：python3 scripts/r173_verify.py [--selftest]
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": SRC / "core/Pipeline.kt",
    "imp": SRC / "core/Importer.kt",
    "plan": SRC / "core/ChapterPlan.kt",
    "resume": SRC / "core/TaskResume.kt",
    "hub": SRC / "ChapterHub.kt",
    "main": SRC / "MainActivity.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}


def cleaned(src: str) -> str:
    """等长抹掉字符串 / 字符 / 注释，保留换行与一个占位符。

    占位符很关键：字符串字面量本身是一个实参（如 "queued"），若全抹成空格，
    top_parts 会把这一段 strip 成空、实参个数就少算，进而误报「缺必填参数」。
    Kotlin 的三引号原始字符串要整段扫掉，否则里面的 [ ] 会露出来污染配平。
    """
    out = list(src)
    i, n = 0, len(src)

    def blank(a0, a1, keep=False):
        for k in range(a0, min(a1, n)):
            if src[k] != '\n':
                out[k] = ' '
        if keep:
            out[a0] = 'S'

    while i < n:
        c = src[i]
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            blank(i, j, True)
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            blank(i, j, True)
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                j += 1
            blank(i, j, True)
            i = j
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            blank(i, j)
            i = j
            continue
        i += 1
    return ''.join(out)

def balance(text: str):
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    for k, ch in enumerate(text):
        if ch in '([{':
            stack.append((ch, k))
        elif ch in ')]}':
            if not stack or stack[-1][0] != pairs[ch]:
                return "第 %d 字符处出现多余的 '%s'" % (k + 1, ch)
            stack.pop()
    if stack:
        return "有 %d 个括号没闭合（最早在第 %d 字符）" % (len(stack), stack[0][1] + 1)
    return None


def match_close(text: str, open_idx: int) -> int:
    """给定 '(' 的下标，返回匹配 ')' 的下标；把 < > 当泛型也算一层。"""
    stack = []
    for k in range(open_idx, len(text)):
        ch = text[k]
        if ch in '([{<':
            stack.append(ch)
        elif ch in ')]}>':
            if stack:
                stack.pop()
            if not stack:
                return k if ch == ')' else -1
    return -1


def top_parts(text: str, open_idx: int):
    """切出最外层调用里的顶层实参（按逗号）。"""
    stack, parts, cur = [], [], []
    for k in range(open_idx + 1, len(text)):
        ch = text[k]
        if ch in '([{<':
            stack.append(ch)
        elif ch in ')]}>':
            if not stack:
                parts.append(''.join(cur))
                return parts
            stack.pop()
        elif ch == ',' and not stack:
            parts.append(''.join(cur))
            cur = []
            continue
        cur.append(ch)
    return []


def decl_params(text: str, name: str):
    m = re.search(r"data class\s+(" + re.escape(name) + r")\s*\(", text)
    if not m:
        return None
    name_start = m.start(1)
    oi = text.find('(', m.start())
    ci = match_close(text, oi)
    if ci < 0:
        return None
    params = []
    for seg in top_parts(text, oi):
        seg = seg.strip()
        mm = re.match(r"(?:val|var)\s+([A-Za-z_]\w*)\s*:", seg)
        if mm:
            params.append((mm.group(1), '=' in seg))
    return params, name_start, oi


def call_args(text: str, name: str, decl_name_start: int):
    """返回 [(起始下标, [实参文本...]), ...]，排除声明本身。"""
    out = []
    for m in re.finditer(r"(?<![\w.])" + re.escape(name) + r"\s*\(", text):
        if m.start() == decl_name_start:
            continue
        oi = text.find('(', m.start())
        parts = [p.strip() for p in top_parts(text, oi) if p.strip()]
        out.append((m.start(), parts))
    return out


def struct_errors(f):
    e = []
    cs = {k: cleaned(v) for k, v in f.items() if k != "ci"}
    for key, text in cs.items():
        b = balance(text)
        if b:
            e.append("括号不配平[%s]: %s" % (key, b))

    # ① 本轮动了字段的两个 data class：所有构造调用实参个数 / 命名参数名
    for cls, key, holders in (
        ("TaskInfo", "resume", ["pipe", "resume"]),
        ("ChapterPlanData", "plan", ["plan"]),
    ):
        di = decl_params(cs[key], cls)
        if not di:
            e.append("取不到 %s 的主构造参数" % cls)
            continue
        params, dstart, _ = di
        names = [p[0] for p in params]
        required = [p[0] for p in params if not p[1]]
        for hk in holders:
            for pos, args in call_args(cs[hk], cls, dstart if hk == key else -1):
                if len(args) > len(params):
                    e.append("%s 调用实参 %d 个 > 声明 %d 个[%s @%d]"
                             % (cls, len(args), len(params), hk, pos))
                    continue
                named, positional = [], 0
                for a in args:
                    nm = re.match(r"^([A-Za-z_]\w*)\s*=", a)
                    if nm:
                        named.append(nm.group(1))
                    else:
                        positional += 1
                for nm in named:
                    if nm not in names:
                        e.append("%s 调用里有不存在的命名参数 '%s'[%s @%d]"
                                 % (cls, nm, hk, pos))
                covered = set(names[:positional]) | set(named)
                miss = [r for r in required if r not in covered]
                if miss:
                    e.append("%s 调用缺必填参数 %s[%s @%d]"
                             % (cls, ",".join(miss), hk, pos))

    # ② 新增字段的 encode / decode 与 save / load 必须成对
    for key, field in (("resume", "planEpoch"), ("plan", "epoch")):
        t = f[key]
        if '.put("%s"' % field not in t or not re.search(r'opt(Int|String)\("%s"' % field, t):
            e.append("%s 的 %s 字段没做到「写入 + 读取」成对" % (key, field))
    if "ChapterPlan.epoch(c, t.bookId)" not in f["resume"]:
        e.append("TaskResume 没引用 ChapterPlan.epoch")

    # ③ 新引用的类型要有 import 或全限定
    if "EventLog." in f["plan"] and "import com.tingben.app.diag.EventLog" not in f["plan"]:
        e.append("ChapterPlan 用了 EventLog 但没 import")
    if "RandomAccessFile" in f["pipe"] and not (
        "import java.io.RandomAccessFile" in f["pipe"] or
        "java.io.RandomAccessFile" in f["pipe"]):
        e.append("Pipeline 用了 RandomAccessFile 但没引入")

    # ④ R173 两处自查修复不许回潮
    if "readLimited(it, MAX_TXT_BYTES.toLong(), MAX_BYTES.toLong())" not in f["imp"]:
        e.append("zip 容器动态闸门缺失")
    if 'buf[0] == \'P\'.code.toByte() && buf[1] == \'K\'.code.toByte()' not in f["imp"]:
        e.append("PK 头识别缺失（改名成 .txt 的 epub 会被 24MB 误拒）")
    if "val keptEpoch = load(c, book.id)?.epoch ?: 0" not in f["plan"]:
        e.append("章节表重建没保留代数（epoch 会悄悄退回 0）")

    if "python3 scripts/r173_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r173_verify --selftest")
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not struct_errors(base), "基线自己就红了，先修基线"
    cases = [
        ("括号不配平", "pipe", lambda t: t + "\nfun broken( {"),
        ("构造实参超限", "pipe",
         lambda t: t.replace("splitRule = TaskResume.SPLIT_RULE,\n            planEpoch = ChapterPlan.epoch(c, book.id))",
                             "splitRule = TaskResume.SPLIT_RULE, planEpoch = ChapterPlan.epoch(c, book.id), 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))")),
        ("命名参数拼错", "pipe",
         lambda t: t.replace("planEpoch = ChapterPlan.epoch(c, book.id)",
                             "planEpooch = ChapterPlan.epoch(c, book.id)")),
        ("字段只写不读", "resume",
         lambda t: t.replace('o.optInt("planEpoch", 0)', 'o.optInt("x", 0)')),
        ("重建丢代数", "plan",
         lambda t: t.replace("val keptEpoch = load(c, book.id)?.epoch ?: 0", "val keptEpoch = 0")),
        ("zip 闸门回潮", "imp",
         lambda t: t.replace("buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()",
                             "false")),
        ("EventLog 没 import", "plan",
         lambda t: t.replace("import com.tingben.app.diag.EventLog\n", "")),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r173_verify.py --selftest", "")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not struct_errors(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = struct_errors(f)
    if e:
        print("FAIL · R173 Kotlin 结构级编译守门")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R173 Kotlin 结构守门：6 个改动文件括号配平 / 构造实参 / 命名参数 / 字段成对 / import：PASS")
