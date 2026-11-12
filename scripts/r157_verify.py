# -*- coding: utf-8 -*-
"""R157 · 第二份真机日志实证修复 + 导出目录分层。

用户贴了 1.1.4 的诊断摘要（Xiaomi / Android 16 / 怨气撞铃 · 9/88）。
逐条查证后落四件事，其中一条是**订正我上一轮的过度承诺**：

  ① SiteNoise 漏网（最要紧）
     日志 TTS_INPUT 段 1 仍念「...』 / 免费章节 / 　　内容还在处理中,请稍后重1前言」。
     R148 汇报写「站点广告不再被念」是错的 —— 那只覆盖了第一份日志那一种
     下载站模板。两个真因：噪声行用**全角空格**缩进，Kotlin `\\s` 默认不含
     U+3000；「免费章节 / 内容还在处理中」是另一套防盗版占位页。
     现在前缀显式认全角空格 / BOM / NBSP，并补齐模板。整行保守过滤不变，
     章标题粘进正文行尾（「…仅此而2第①章」）不处理 —— 正文一个字不改优先。

  ② 「继续制作」看起来从头重跑
     日志里每轮继续都从 TTS_INPUT 段 1/88 打起，但代码里前 9 段走的是
     「已有音频」跳过分支。日志和前台通知错打在跳过段上，造成误导
     （用户之前报过「愣住、不知道后面进哪张」）。现在跳过只记 TTS_SKIP，
     touch / TTS_INPUT / 参数计算都只给真正要合成的段。

  ③ 摘要里的历史报错冒充当前故障
     13:51「制作记录写入失败：segmap.json」其实是 1.1.3 的旧错
     （R148 已修：103 章目录名超长），但「所有报错/警告」跨版本不标版本，
     读起来像 1.1.4 还在出错。现在按 SESSION_START 给每条标注产生时版本。

  ④ 导出目录分层（用户新想法，采纳）
     导入的书按书名各建一个文件夹；输入框 / 分享文字建的书统一进
     `输入朗读/` 再按书名分；同名书追加 `_2`。SAF 先查再建同名目录，
     重复导出不会生成「书名 (1)」副本。给 Book 加 origin 字段（老数据零迁移）。

用法：python3 scripts/r157_verify.py --selftest
"""
import ui_sources
import core_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "noise": APP / "core" / "SiteNoise.kt",
    "pipe": core_sources.source(APP),
    "log": APP / "diag" / "EventLog.kt",
    "export": APP / "core" / "AudioExport.kt",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "noise": [
        "R157 纠正",
        "过度承诺",
        "全角空格",
        'private const val WS = "[\\\\s\\\\u3000\\\\uFEFF\\\\u00A0]*"',
        "免费章节|VIP章节|试读章节|本章未完|内容加载中",
        "内容还在处理中|内容正在处理|正在加载中|请稍后重试",
        "[.。…]{2,}",
        "已知限制",
        "正文一个字不改",
        "fun clean(text: String): String",
        "fun droppedCount(text: String): Int",
    ],
    "pipe": [
        # ① SiteNoise 接线不变
        "SiteNoise.clean(text)",
        # ② 跳过段日志分离
        'val already = wav.exists() && wav.length() > 44',
        "val skip = (covered || already) && !redoSeg",
        'EventLog.log(EventLog.Kind.PIPELINE, "TTS_SKIP"',
        # R278 · 逐条「已有音频，跳过」刷屏改按窗口汇总，守恒信息一条不少
        '"跳过 $n 段已有音频',
        "if (skipWav + skipChapter >= 500) flushSkip()",
        "try { flushSkip() } catch (_: Throwable) { }",
        "var lastParams: SynthInput.Params? = null",
        "SynthInput.params(c, built.first, style), Prefs.ttsKnobs(c)",
        "if (skip) {",
        # ④ Book.origin
        'val sha: String = "", val origin: String = "file"',
        'o.optString("origin", "file")',
        '.put("origin", b.origin)',
        'origin: String = "file"',
        'Book(id, title, f.absolutePath, text.length, sha, origin)',
    ],
    "log": [
        "R157 · 第二份真机日志实证",
        'Regex("version=([0-9][^\\\\s·|]*)")',
        'ver = m?.groupValues?.get(1) ?: "旧版本"',
        'out += "[$ver] $l"',
        "旧版本的报错不代表当前还在出错",
    ],
    "export": [
        'const val INPUT_DIR = "输入朗读"',
        "data class Item(val dir: String, val name: String, val file: File)",
        'b.origin == "input"',
        '"$INPUT_DIR/"',
        'if (n > 1) "${base}_$n"',
        # R308 · 章成品是 M4A 了，导出名按真实扩展名，不再写死 .wav。
        'val ext = a.file.extension.ifBlank { "wav" }',
        '"%03d_%s.%s".format(ch + 1, safeName(t), ext)',
        "fun ensureDir(rel: String): Uri?",
        "private fun findChildDir(",
        "DocumentsContract.Document.MIME_TYPE_DIR, part",
        "buildChildDocumentsUriUsingTree(tree, parentId)",
    ],
    "main": [
        'autoBookTitle(snapshot, "粘贴的文字")',
        'autoBookTitle(text, "分享的文字")',
        "导出位置",
        "「输入朗读」",
        "AudioExport.exportAll(c, tree)",
    ],
    "ci": ["python3 scripts/r157_verify.py --selftest"],
    "r135": ['"r157_verify.py"'],
}

BAN = {
    "export": [
        # 旧行为：所有书平铺、文件名里塞书名
        '"%s_第%02d章.wav".format(safeName(b.title), ch + 1)',
    ],
    "pipe": [
        # R278 · 续跑时逐段写盘，1.1.31 实测把详细日志撑到 1MB
        '"段 ${i + 1}/${toSpeak.size} 已有音频，跳过"',
    ],
}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def code_only(s):
    return "\n".join(
        line for line in s.splitlines()
        if not line.strip().startswith(("//", "/*", "*")))


def loop_body(p):
    """圈出合成循环里 segStart → 合并分支这一段。"""
    a = p.find("val segStart = wavs.size")
    if a < 0:
        return ""
    b = p.find("val chapterSnapshot =", a)
    return p[a:b] if b > a else ""


def site_rules(noise_src):
    """复刻 Kotlin 的 WS 模板插值，得到 Python 正则列表。

    R168 起源码统一写 ${WS}（裸 $WS 后接中文会被 Kotlin 吞成标识符）；
    两种写法都在这里展开，历史注入用例仍能把裸写法注回来。
    """
    ws = r"[\s\u3000\uFEFF\u00A0]*"
    return [r.replace("${WS}", ws).replace("$WS", ws)
            for r in re.findall(r'Regex\("""(.*?)"""\)', noise_src, re.S)]


def collect_layout(books):
    """复刻 AudioExport.collect 的目录规则：book = (title, origin, chapters)。"""
    out, used = [], {}
    for title, origin, chapters in books:
        if not chapters:
            continue
        prefix = "输入朗读/" if origin == "input" else ""
        base = title
        key = prefix + base
        n = used.get(key, 0) + 1
        used[key] = n
        d = prefix + (base if n == 1 else "%s_%d" % (base, n))
        for ch in chapters:
            out.append((d, "%03d_第 %d 章.wav" % (ch + 1, ch + 1)))
    return out


def label_errors(detail_lines):
    """复刻 EventLog.allErrors 的版本标注。"""
    ver, out = "旧版本", []
    for l in detail_lines:
        if "| UI | SESSION_START |" in l:
            m = re.search(r"version=([0-9][^\s·|]*)", l)
            ver = m.group(1) if m else "旧版本"
            continue
        if " | ERROR | " in l or " | CRASH | " in l or " | WARN | " in l:
            out.append("[%s] %s" % (ver, l))
    return out


def logic_cases(f):
    bad = []
    # ① 站点噪声：日志真实片段要删，正文一行都不能动
    rules = [re.compile(r) for r in site_rules(f["noise"])]
    noise = [
        "　　...』", "免费章节", "　　内容还在处理中,请稍后重1前言",
        "------章节内容开始", "下载和分享更多电子书请访问 https://ixdzs8.com",
        "//ixdzs8.tw,E-mail:support@ixdzs.com", "support@ixdzs.com",
        "爱下电子书Txt", "『状态:已完结』", "　　请稍后重",
        "　　内容正在处理中，请稍候", "  ...』",
    ]
    body = [
        "　　平时没什么其它爱好，闲暇时",
        "　　觉得很有意思，于是就写了，娱人娱己，仅此而2第①章",
        "　　晚上七时许，飞机抵达兰州上空，拉起",
        "“一个人出来旅游？”那人追问。",
        "她没说会不会更改计划，拎着包直接上楼了。",
        "第①章 归来", "E-mail 发我一下", "请先稍后重试一下这个按钮",
    ]
    for x in noise:
        if not any(r.match(x) for r in rules):
            bad.append("噪声漏删: " + repr(x))
    for x in body:
        if any(r.match(x) for r in rules):
            bad.append("正文误删: " + repr(x))
    if len(rules) < 12:
        bad.append("规则条数不足：%d" % len(rules))
    if not bad:
        print("  站点噪声 %d 条全删 / 正文 %d 条全留 ✓" % (len(noise), len(body)))

    # ② 跳过段不再打 TTS_INPUT / 不再 touch 前台通知
    lb = code_only(loop_body(f["pipe"]))
    if not lb:
        bad.append("没圈出合成循环体")
    else:
        i_skip = lb.find('"TTS_SKIP"')
        i_else = lb.find("} else {", lb.find("if (skip) {"))
        i_touch = lb.find("ProduceService.touch(c.applicationContext,")
        i_input = lb.find('"TTS_INPUT"')
        if not (0 <= i_skip < i_else < i_touch < i_input):
            bad.append("日志/通知仍打在跳过段上：skip=%d else=%d touch=%d input=%d"
                       % (i_skip, i_else, i_touch, i_input))
        elif "lastParams: SynthInput.Params? = null" not in f["pipe"]:
            bad.append("参数没有可空兜底")
        else:
            print("  跳过段只记 TTS_SKIP，touch / TTS_INPUT 只在真合成段 ✓")

    # ③ 历史报错按版本标注
    detail = [
        "11-08 13:51:42.361 | UI | SESSION_START | 启动 · version=1.1.3",
        "11-08 13:51:42.361 | CRASH | INFO | UI_ERROR | 文件：制作记录写入失败：segmap.json",
        "11-08 17:16:21.027 | UI | SESSION_START | 启动 · version=1.1.4",
        "11-08 18:04:30.456 | PIPELINE | WARN | QUEUE_PAUSE | 队列停在原地",
    ]
    got = label_errors(detail)
    if len(got) != 2 or not got[0].startswith("[1.1.3]") or not got[1].startswith("[1.1.4]"):
        bad.append("报错版本标注错：%r" % got)
    else:
        print("  历史报错标 [1.1.3]、当前报错标 [1.1.4] ✓")

    # ④ 导出目录分层 + 同名防重 + 空书不建目录
    books = [
        ("怨气撞铃", "file", [0, 1]),
        ("粘贴的文字", "input", [0]),
        ("粘贴的文字", "input", [0]),   # 同名第二本
        ("没有成品的书", "file", []),
    ]
    got = collect_layout(books)
    want = [
        ("怨气撞铃", "001_第 1 章.wav"), ("怨气撞铃", "002_第 2 章.wav"),
        ("输入朗读/粘贴的文字", "001_第 1 章.wav"),
        ("输入朗读/粘贴的文字_2", "001_第 1 章.wav"),
    ]
    if got != want:
        bad.append("导出目录分层错：%r" % (got,))
    else:
        print("  导出目录：按书名分 / 输入朗读单独一层 / 同名加 _2 / 空书不建 ✓")

    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:75]))
    for key, needles in BAN.items():
        for s in needles:
            if s in code_only(f[key]):
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    # 行为判据
    rules = [re.compile(r) for r in site_rules(f["noise"])]
    if not any(r.match("免费章节") for r in rules):
        e.append("「免费章节」整行没被过滤")
    if not any(r.match("　　...』") for r in rules):
        e.append("全角空格缩进的噪声没被过滤")
    if any(r.match("　　平时没什么其它爱好，闲暇时") for r in rules):
        e.append("站点过滤误删正文")
    lb = code_only(loop_body(f["pipe"]))
    if not lb:
        e.append("没圈出合成循环体")
    else:
        i_skip = lb.find('"TTS_SKIP"')
        i_else = lb.find("} else {", lb.find("if (skip) {"))
        i_touch = lb.find("ProduceService.touch(c.applicationContext,")
        i_input = lb.find('"TTS_INPUT"')
        if not (0 <= i_skip < i_else < i_touch < i_input):
            e.append("跳过段仍打 TTS_INPUT / 更新前台通知")
    if 'out += "[$ver] $l"' not in f["log"]:
        e.append("报错没按版本标注")
    if 'const val INPUT_DIR = "输入朗读"' not in f["export"]:
        e.append("输入框来源没单独一层")
    if 'if (n > 1) "${base}_$n"' not in f["export"]:
        e.append("同名书会互相覆盖")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("全角空格前缀被摘", "noise",
         'private const val WS = "[\\\\s\\\\u3000\\\\uFEFF\\\\u00A0]*"', ""),
        ("免费章节规则被摘", "noise",
         "免费章节|VIP章节|试读章节|本章未完|内容加载中", ""),
        ("占位页规则被摘", "noise",
         "内容还在处理中|内容正在处理|正在加载中|请稍后重试", ""),
        ("省略号行规则被摘", "noise", "[.。…]{2,}", ""),
        ("clean 被摘", "noise", "fun clean(text: String): String", ""),
        ("站点清洗接线被摘", "pipe", "SiteNoise.clean(text)",
         "TextSplitter.split(text)"),
        ("skip 判定被摘", "pipe", "val skip = (covered || already) && !redoSeg", ""),
        ("TTS_SKIP 被摘", "pipe", '"TTS_SKIP"', '"TTS_INPUT"'),
        ("跳过汇总被摘", "pipe", '"跳过 $n 段已有音频', '"段 1 已有音频"'),
        ("日志又打回跳过段", "pipe",
         "ProduceService.touch(c.applicationContext,", ""),
        ("可空参数兜底被摘", "pipe",
         "var lastParams: SynthInput.Params? = null", ""),
        ("参数兜底重算被摘", "pipe",
         "SynthInput.params(c, built.first, style), Prefs.ttsKnobs(c)", ""),
        ("origin 字段被摘", "pipe",
         'val sha: String = "", val origin: String = "file"', ""),
        ("老数据兜底被摘", "pipe", 'o.optString("origin", "file")', ""),
        ("origin 没落盘", "pipe", '.put("origin", b.origin)', ""),
        ("粘贴不标 input", "main",
         'autoBookTitle(snapshot, "粘贴的文字")', ""),
        ("分享不标 input", "main",
         'autoBookTitle(text, "分享的文字")', ""),
        ("版本正则被摘", "log", 'Regex("version=([0-9][^\\\\s·|]*)")', ""),
        ("报错版本标注被摘", "log", 'out += "[$ver] $l"', ""),
        ("摘要说明被摘", "log", "旧版本的报错不代表当前还在出错", ""),
        ("输入朗读层被摘", "export", 'const val INPUT_DIR = "输入朗读"', ""),
        ("按书分目录被摘", "export", 'b.origin == "input"', ""),
        ("同名防重被摘", "export", 'if (n > 1) "${base}_$n"', ""),
        ("导出扩展名写死回潮", "export",
         '"%03d_%s.%s".format(ch + 1, safeName(t), ext)',
         '"%03d_%s.wav".format(ch + 1, safeName(t))'),
        ("扩展名取值被摘", "export",
         'val ext = a.file.extension.ifBlank { "wav" }', ""),
        ("Item 退回两参", "export",
         "data class Item(val dir: String, val name: String, val file: File)",
         "data class Item(val name: String, val file: File)"),
        ("子目录创建被摘", "export",
         "DocumentsContract.Document.MIME_TYPE_DIR, part", ""),
        ("同名目录复用被摘", "export",
         "buildChildDocumentsUriUsingTree(tree, parentId)", ""),
        ("导出能力入口被摘", "main", "AudioExport.exportAll(c, tree)", ""),
        ("CI 漏挂 r157", "ci", "python3 scripts/r157_verify.py --selftest", ""),
        ("现行清单漏 r157", "r135", '"r157_verify.py"', ""),
    ]
    bad = 0
    # R268 · 同名探测串在目录层与文件层各一处，只摘第一处判据仍成立
    # R278 · TTS_SKIP 有「逐条 dropped」与「窗口汇总」两处，只摘第一处
    # 会漏掉另一处、判据仍绿，必须全量替换才算「机制被摘」。
    FULL = {"同名目录复用被摘", "TTS_SKIP 被摘"}
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = (f[key].replace(needle, repl) if name in FULL
                  else f[key].replace(needle, repl, 1))
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R157 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R157 第二份真机日志实证（站点噪声漏网 / 跳过段日志 / 报错版本标注 / 导出目录分层）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
