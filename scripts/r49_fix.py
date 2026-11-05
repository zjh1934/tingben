#!/usr/bin/env python3
"""R49 首轮本地复跑暴露的 5 处订正（都是验证脚本/文案口径，不是业务代码回退）。

1. r45_verify 查精确字面量 "⟳ 换一个" → 新文案改回该字面量
2. r45_common.canon 的 AnchorBar 剥离 end marker 在 R49 里被替换掉了 → 换稳定 marker
3. r42_verify 仍断言吸顶大卡 CardBox 存在 → v5 已换成细状态条，订正为 0
4. r47_verify 的「滑块直写 Prefs」注入串按旧缩进写 → 对齐新 ParamRow 缩进
5. r49_verify：AB 切片 marker、ParamSpec 跨行解析、HEAD 落后一轮的守恒口径
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
P = lambda p: ROOT / p


def sub(path, pairs):
    s = path.read_text(encoding="utf-8")
    orig = s
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path.name} 第 {i} 条命中 {n} 次（应为 1）")
        s = s.replace(old, new, 1)
    if s == orig:
        sys.exit(f"{path.name} 没有变化")
    path.write_text(s, encoding="utf-8")
    print(path.name, "订正", len(pairs), "条")


# ① 文案回到 R45 已验的字面量
sub(P("app/src/main/java/com/tingben/app/MainActivity.kt"), [
    ('Text("⟳ 换一个固定种子（仅草稿）", fontSize = 10.5.sp, color = Primary)',
     'Text("⟳ 换一个", fontSize = 10.5.sp, color = Primary)'),
])

# ② canon：R49 把「R41 · 九项紧凑行」注释换掉了，换成仍在文件里的稳定 marker
sub(P("scripts/r45_common.py"), [
    ('''    a = t.find("private fun AnchorBar(")
    b = t.find("/** R41 · 九项紧凑行", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]''',
     '''    a = t.find("private fun AnchorBar(")
    b = t.find("/** R41 · 共用音色选择器", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]'''),
])

# ③ 吸顶大卡已删
sub(P("scripts/r42_verify.py"), [
    ('chk(MAIN.count("CardBox(Modifier.padding(horizontal = 16.dp") == 1, "吸顶状态条卡片异常")',
     'chk(MAIN.count("CardBox(Modifier.padding(horizontal = 16.dp") == 0,\n'
     '    "R49 回退：吸顶大卡没换成细状态条")'),
])

# ④ 注入串对齐新缩进
sub(P("scripts/r47_verify.py"), [
    ('''    "滑块直写Prefs": lambda c, m, s: (c, m.replace("onInput(s)\\n            },",
                                                "Prefs.setTtsKnobs(c, draft.knobs)\\n            },"), s),''',
     '''    "滑块直写Prefs": lambda c, m, s: (c, m.replace(
                                                "                    onInput(s)\\n                },",
                                                "                    onInput(s)\\n"
                                                "                    Prefs.setTtsKnobs(c, draft.knobs)\\n                },"), s),'''),
])

# ⑤ r49_verify 自身
sub(P("scripts/r49_verify.py"), [
    ('AB = between(MAIN, "private fun AnchorBar(", "/** R41 · 九项紧凑行")',
     'AB = between(MAIN, "private fun AnchorBar(", "private fun ParamRow(")'),
    ('    ab = between(m, "private fun AnchorBar(", "/** R41 · 九项紧凑行")',
     '    ab = between(m, "private fun AnchorBar(", "private fun ParamRow(")'),
    ('"⟳ 换一个固定种子（仅草稿）"', '"⟳ 换一个"'),
    ('''rows = re.findall(r'ParamSpec\\("([^"]+)",\\s*"([^"]+)",\\s*"([^"]+)",\\s*"([^"]+)",[^\\)]*?(?:min = ([-\\d.]+))?[^\\)]*?(?:max = ([-\\d.]+))?\\)', block)
parsed = []
for line in block.splitlines():
    if "ParamSpec(" not in line:
        continue
    q = re.findall(r'"(?:[^"\\\\]|\\\\.)*"', line)
    if len(q) >= 4:
        mn = re.search(r"min = ([-\\d.]+)", line)
        mx = re.search(r"max = ([-\\d.]+)", line)
        parsed.append((q[0][1:-1], q[1][1:-1], q[3][1:-1],
                       float(mn.group(1)) if mn else None,
                       float(mx.group(1)) if mx else None))''',
     '''flat = re.sub(r"\\s+", " ", block)
parsed = []
for seg in flat.split("ParamSpec(")[1:]:
    q = re.findall(r'"(?:[^"\\\\]|\\\\.)*"', seg)
    if len(q) < 4:
        continue
    mn = re.search(r"min = ([-\\d.]+)", seg)
    mx = re.search(r"max = ([-\\d.]+)", seg)
    parsed.append((q[0][1:-1], q[1][1:-1], q[3][1:-1],
                   float(mn.group(1)) if mn else None,
                   float(mx.group(1)) if mx else None))'''),
    ('''        "app/src/main/java/com/tingben/app/core/ParamSpec.kt",
        "app/src/main/java/com/tingben/app/core/MixerDraft.kt",''',
     '''        "app/src/main/java/com/tingben/app/core/MixerDraft.kt",'''),
    ('''        "app/src/main/java/com/tingben/app/AppChrome.kt",
        "app/src/main/cpp/tingben_native.cpp",''',
     '''        "app/src/main/cpp/tingben_native.cpp",'''),
    ('''    for p in KEEP:
        h = head(p)
        chk(h is not None and h == (ROOT / p).read_bytes(), "守恒文件被改动: " + p)''',
     '''    for p in KEEP:
        h = head(p)
        chk(h is not None and h == (ROOT / p).read_bytes(), "守恒文件被改动: " + p)
    # HEAD 比工作区落后一轮（R47 的 ParamSpec / AppChrome 尚未进 HEAD），
    # 这两个文件改走语义守恒，不拿旧 HEAD 做逐字节比。
    chrome = read("app/src/main/java/com/tingben/app/AppChrome.kt")
    chk("requiredWidth(240.dp)" in chrome, "R47 抽屉收窄被改坏")
    for label in ["采样种子", "单次音频长度上限", "主·随机度", "主·候选范围", "主·核采样",
                  "重复惩罚", "副·随机度", "副·候选范围", "副·核采样"]:
        chk('"%s"' % label in SPEC, "9 项人话参数名缺: " + label)'''),
])
print("R49 订正完成")
