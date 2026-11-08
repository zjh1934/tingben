# -*- coding: utf-8 -*-
"""R153 · I 批收口守门：调音台布局 / 深色配色 / 首章前文本诊断。

用户实证（真机使用感受）：
  · 「试听文本和试听两个按钮是在采样参数的上面」「刷新键太小根本点不到」
    「种子 -1 是不是完全随机、上千种调整是真的吗」
  · 「开深色模式以后，字体颜色和底色感觉就不好看」
  · 自己排的「TXT 页眉并进首章」→ 纠正为不并入

纠正后按纠正做的：
  · I3 不把书前内容并进首章：用户日志明确抱怨过「开头把下载网址、
    邮箱、章节内容开始当正文念了」，并进去会让 TTS 念广告；
    改首章 start 还会挪动全部章区间，毁掉已生成音频的章起点索引。
    保持「不进任何章」，只把静默丢弃变成一行可见诊断。
  · I2 不靠感觉调色，用 WCAG 对比度逐对验算；新增 onWarn 前景色，
    清掉全部硬编码 Color.White。

用法：python3 scripts/r153_verify.py --selftest
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "hub": APP / "ChapterHub.kt",
    "theme": APP / "Theme.kt",
    "plan": APP / "core" / "ChapterPlan.kt",
    "pipe": APP / "core" / "Pipeline.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "main": [
        "// I1 · 用户实证「试听文本和试听两个按钮在采样参数上面」。",
        "Box(Modifier.size(40.dp)",
        '.clickable { onValue(spec.def) },',
        'if (spec.key == "seed") {',
        '"· -1 = 每次随机；固定一个数只让同一段话每次念得一样，不换音色"',
        'OutlinedTextField(',
        'Text("试听 · 已保存"', 'Text("试听 · 当前"',
        'Text("采样参数 · 9 项"',
        'Text("卸载模型", fontSize = 12.sp, color = palette.onWarn)',
        # J1 看图修复
        '.background(PrimarySoft, RoundedCornerShape(10.dp))',
        'Text("↺", fontSize = 17.sp, color = Primary)',
        'modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)',
    ],
    "hub": [
        "var front by remember(id, refresh, planTick)",
        "Triple(", "ChapterPlan.frontMatter(text, s.first)",
        "ChapterPlan.resolve(c, b, text)",
        "front?.let { fi ->",
        '"书前 ${fi.chars} 字是下载/出版信息，不纳入朗读（不是漏读）"',
        'Text("停止", fontSize = 12.sp, color = palette.onWarn)',
        "IconPlayPause(st.isPlaying, palette.onPrimary",
        "color = if (playing) palette.onPrimary",
        # J1 看图修复：未生成格子不再是 18% 透明
        "private fun segCellColor(",
        "0.46f else 0.48f",
        "segCellColor(playable, playing)",
        "segCellColor(playable, playing, segMulti, checked)",
    ],
    "theme": [
        "val onWarn: Color",
        "onWarn = Color(0xFFFFFFFF)",
        "onWarn = Color(0xFF1A1206)",
        "bg = Color(0xFF0E1117), card = Color(0xFF222936)",
        "primarySoft = Color(0xFF313C63), sub = Color(0xFFA8B2C6)",
        "line = Color(0xFF3B455A)", "grey = Color(0xFF7E8AA0)",
    ],
    "plan": [
        "data class FrontInfo(val chars: Int, val noisy: Boolean)",
        "private val siteNoise = Regex(",
        "fun frontMatter(text: String, chapters: List<Chapter>): FrontInfo",
        "val end = chapters.firstOrNull()?.start ?: 0",
        "coerceAtMost(4096)",
        "siteNoise.containsMatchIn(head)",
    ],
    "pipe": [
        "Chapter(i, m.second, m.first, e)",
    ],
    "ci": ["python3 scripts/r153_verify.py --selftest"],
    "r135": ['"r153_verify.py"'],
}

BAN = {
    "main": [
        "Color.White)",
        'Text("↺", fontSize = 12.sp, color = Sub,',
        "modifier = Modifier.clickable { onValue(spec.def) }.padding(4.dp)",
    ],
    "hub": ["Color.White)", "text.substring(0, 0)",
            "base.copy(alpha = 0.18f)"],
    "plan": [
        "Chapter(i, m.second, 0",  # 首章 start 改成 0 = 并进页眉，会毁章索引
        "text.replace(siteNoise",  # 不许改正文
    ],
    "pipe": ["Chapter(i, m.second, 0"],
}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


# ---- WCAG 相对亮度与对比度（I2 的客观依据） ----
def lum(h):
    h = h.lstrip("#")
    rgb = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    def f(c):
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = [f(c) for c in rgb]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    x, y = sorted((lum(a), lum(b)), reverse=True)
    return (x + 0.05) / (y + 0.05)


def dark_pal(theme):
    """从 Theme.kt 的 DarkPal 解析色值，守门直接读真实代码而不是抄一份常量。"""
    m = re.search(r"internal val DarkPal = Pal\((.*?)\n\)", theme, re.S)
    if not m:
        return None
    return dict(re.findall(r"(\w+)\s*=\s*Color\(0xFF([0-9A-Fa-f]{6})\)", m.group(1)))


def mixer_order(main):
    """调音台里试听区必须在采样参数之前。"""
    a = main.find("private fun MixerScreen(")
    b = main.find("private fun ParamLine(", a)
    body = main[a:b if b > a else len(main)]
    i_trial = body.find('label = { Text("试听文本"')
    i_buttons = body.find('Text("试听 · 已保存"')
    i_params = body.find('Text("采样参数 · 9 项"')
    return i_trial, i_buttons, i_params


def cell_visible(gray, card, alpha):
    """未生成格子底色叠在卡片上的对比度（J1 修复前后客观差距）。"""
    g = rgb(gray)
    c = rgb(card)
    mix = "#" + "".join("%02X" % int(g[i] * alpha + c[i] * (1 - alpha))
                        for i in range(3))
    return contrast(mix, card)


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def front_matter(text_len, first_start, head):
    """复刻 frontMatter 的判定：不改正文、只读前 4096 字判噪声。"""
    if first_start <= 0:
        return 0, False
    noise = re.compile(
        r"https?://|www\.|@[\w.-]+\.[A-Za-z]{2,}|下载|更多电子书|章节内容开始|"
        r"最新网址|请收藏|电子书|TXT下载|论坛|QQ群|微信公众号", re.I)
    head = head[:min(first_start, text_len, 4096)]
    return first_start, bool(noise.search(head))


def logic_cases(f):
    bad = []
    # ① 调音台顺序
    a, b, c = mixer_order(f["main"])
    if min(a, b, c) < 0:
        bad.append("调音台三处锚点没圈全")
    elif not (a < b < c):
        bad.append("试听区没排在采样参数之前：trial=%d buttons=%d params=%d" % (a, b, c))
    else:
        print("  调音台顺序：试听文本 → 双试听按钮 → 九项采样参数 ✓")
    # 刷新键热区必须 >= 40dp，seed 说明必须在 ParamLine 里
    pl = f["main"][f["main"].find("private fun ParamLine("):]
    if "Box(Modifier.size(40.dp)" not in pl:
        bad.append("刷新键热区没扩到 40dp")
    if 'if (spec.key == "seed")' not in pl or "每次随机" not in pl:
        bad.append("seed 说明没上屏")

    # ② 深色对比度：直接读 Theme.kt 的真实色值
    d = dark_pal(f["theme"])
    if not d:
        bad.append("没解析出 DarkPal")
    else:
        pairs = [
            ("正文/底", "text", "bg", 4.5), ("正文/卡", "text", "card", 4.5),
            ("次要/底", "sub", "bg", 4.5), ("次要/卡", "sub", "card", 4.5),
            ("主色/卡", "primary", "card", 4.5),
            ("主色/柔底", "primary", "primarySoft", 4.5),
            ("次要/柔底", "sub", "primarySoft", 4.5),
            ("警示/卡", "warn", "card", 4.5), ("完成/卡", "good", "card", 3.0),
            ("灰字/卡", "grey", "card", 3.0),
            ("深字/警示底", "onWarn", "warn", 4.5),
            ("深字/主色底", "onPrimary", "primary", 4.5),
            ("卡/底", "card", "bg", 1.25),
            ("柔底/卡", "primarySoft", "card", 1.25),
            ("分隔线/卡", "line", "card", 1.20),
        ]
        for name, x, y, need in pairs:
            if x not in d or y not in d:
                bad.append("DarkPal 缺色 %s/%s" % (x, y))
                continue
            got = contrast("#" + d[x], "#" + d[y])
            if got < need:
                bad.append("深色对比不足 %s: %.2f < %.2f" % (name, got, need))
            else:
                print("  深色 %-14s %.2f ≥ %.2f ✓" % (name, got, need))
    # 旧色值不许回潮
    for old in ("0xFF12151C", "0xFF1C212C", "0xFF2A3350", "0xFF9AA3B5"):
        if old in f["theme"]:
            bad.append("旧深色值回潮：" + old)

    # ②b J1 看图修复：未生成格子必须看得见
    for name, gray, card, old_a, new_a in [
        ("浅色", "#B9BFCB", "#FFFFFF", 0.18, 0.48),
        ("深色", "#7E8AA0", "#222936", 0.18, 0.46),
    ]:
        before = cell_visible(gray, card, old_a)
        after = cell_visible(gray, card, new_a)
        if not (after > before and after >= 1.28):
            bad.append("%s 未生成格子仍不可见：%.2f → %.2f" % (name, before, after))
        else:
            print("  %s 未生成格子/卡片 %.2f → %.2f ✓" % (name, before, after))

    # ③ 首章前文本：只读诊断，不改章区间
    cases = [
        ("正常书无页眉", 0, "", 0, False),
        ("站点页眉", 320, "最新网址 www.xxx.com 下载更多电子书", 320, True),
        ("邮箱广告", 180, "联系 a@b.com 章节内容开始", 180, True),
        ("纯出版信息", 90, "作者：某某  出版社：某某出版社", 90, False),
    ]
    for name, start, head, want_chars, want_noisy in cases:
        chars, noisy = front_matter(200000, start, head)
        if (chars, noisy) != (want_chars, want_noisy):
            bad.append("首章前诊断 %s → %s/%s 期望 %s/%s" %
                       (name, chars, noisy, want_chars, want_noisy))
        else:
            print("  首章前 %-12s → %d 字 · %s" %
                  (name, chars, "站点信息" if noisy else "普通出版信息"))
    # 首章 start 不许被改成 0
    if "Chapter(i, m.second, m.first, e)" not in f["pipe"]:
        bad.append("fromMarks 改了首章 start，会毁已有音频章索引")
    # 只读：frontMatter 里不许出现对 text 的写操作
    m = re.search(r"fun frontMatter\((.*?)\n    \}", f["plan"], re.S)
    if m and ("replace(" in m.group(1) or "removePrefix" in m.group(1)):
        bad.append("frontMatter 改了正文")
    if "Triple(" not in f["hub"] or "BookStore.read(c, b)" not in f["hub"]:
        bad.append("首章前诊断没复用同一次 IO 读盘")

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
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    # 顺序与热区属于「行为」，必须在 check() 里守，反向注入才拦得住。
    a, b, c = mixer_order(f["main"])
    if min(a, b, c) < 0:
        e.append("调音台三处锚点没圈全")
    elif not (a < b < c):
        e.append("试听区没排在采样参数之前")
    pl = f["main"][f["main"].find("private fun ParamLine("):]
    if "Box(Modifier.size(40.dp)" not in pl:
        e.append("ParamLine 的刷新键热区没扩到 40dp")
    if ".background(PrimarySoft, RoundedCornerShape(10.dp))" not in pl:
        e.append("刷新键没有可见的圆底，仍像空心 checkbox")
    if "padding(top = 2.dp, bottom = 8.dp)" not in pl:
        e.append("seed 说明没和滑块拉开间距")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("试听区挪回采样参数下面", "main", None,
         lambda t: t.replace('label = { Text("试听文本"',
                             'label = { Text("参数占位"')
                    .replace('Text("采样参数 · 9 项"',
                             'label = { Text("试听文本" })\n            Text("采样参数 · 9 项"')),
        ("双试听按钮被摘", "main", 'Text("试听 · 已保存"', ""),
        ("采样参数标题被摘", "main", 'Text("采样参数 · 9 项"', ""),
        ("刷新键热区被摘", "main", None,
         lambda t: (lambda i: t[:i] + t[i:].replace("Box(Modifier.size(40.dp)",
                     "Box(Modifier.size(20.dp)", 1))(t.find("private fun ParamLine("))),
        ("刷新键退回小热区", "main", 'Text("↺", fontSize = 17.sp, color = Primary)',
         'Text("↺", fontSize = 12.sp, color = Sub,\n                modifier = Modifier.clickable { onValue(spec.def) }.padding(4.dp))'),
        ("seed 说明被摘", "main", 'if (spec.key == "seed") {', ""),
        ("onWarn 字段被摘", "theme", "val onWarn: Color", ""),
        ("深色 onWarn 被摘", "theme", "onWarn = Color(0xFF1A1206)", ""),
        ("深色卡片回潮", "theme", "card = Color(0xFF222936)",
         "card = Color(0xFF1C212C)"),
        ("深色柔底回潮", "theme", "primarySoft = Color(0xFF313C63)",
         "primarySoft = Color(0xFF2A3350)"),
        ("深色灰字回潮", "theme", "grey = Color(0xFF7E8AA0)",
         "grey = Color(0xFF6B7280)"),
        ("硬编码白字回潮", "main", 'Text("卸载模型", fontSize = 12.sp, color = palette.onWarn)',
         'Text("卸载模型", fontSize = 12.sp, color = Color.White)'),
        ("停止按钮白字回潮", "hub",
         'Text("停止", fontSize = 12.sp, color = palette.onWarn)',
         'Text("停止", fontSize = 12.sp, color = Color.White)'),
        ("FrontInfo 被摘", "plan",
         "data class FrontInfo(val chars: Int, val noisy: Boolean)", ""),
        ("噪声识别被摘", "plan", "private val siteNoise = Regex(", ""),
        ("frontMatter 被摘", "plan",
         "fun frontMatter(text: String, chapters: List<Chapter>): FrontInfo", ""),
        ("上限保护被摘", "plan", "coerceAtMost(4096)", ""),
        ("诊断行被摘", "hub", '"书前 ${fi.chars} 字是下载/出版信息', ""),
        ("复用读盘被摘", "hub", "ChapterPlan.resolve(c, b, text)", ""),
        ("首章 start 被改成 0", "pipe", "Chapter(i, m.second, m.first, e)",
         "Chapter(i, m.second, 0, e)"),
        ("格子色函数被摘", "hub", "private fun segCellColor(", ""),
        ("未生成格子退回 18%", "hub", "0.46f else 0.48f",
         "0.18f else 0.18f"),
        ("第一列格子没用公共色", "hub", "segCellColor(playable, playing)", ""),
        ("第二列格子没用公共色", "hub",
         "segCellColor(playable, playing, segMulti, checked)", ""),
        # 这个串在设置页主题分段器也出现一次，注入必须只改 ParamLine 段，
        # 否则删掉的是别处的那一处，本条永远拦不住。
        ("刷新键退回空心框", "main", None,
         lambda t: (lambda i: t[:i] + t[i:].replace(
             ".background(PrimarySoft, RoundedCornerShape(10.dp))", "", 1))
                    (t.find("private fun ParamLine("))),
        ("seed 说明又压滑块", "main",
         "modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)", ""),
        ("CI 漏挂 r153", "ci", "python3 scripts/r153_verify.py --selftest", ""),
        ("现行清单漏 r153", "r135", '"r153_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        if callable(repl):
            x[key] = repl(f[key])
        else:
            x[key] = f[key].replace(needle, repl, 1)
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
        print("R153 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R153 I 批收口（调音台顺序 / 深色对比度 / 首章前文本只诊断不并入）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
