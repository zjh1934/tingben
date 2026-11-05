#!/usr/bin/env python3
"""R47 · 抽屉收窄 + v4 预览 ⑥ 真落地（滑块双模 / 参数人话名）。

硬约束：不碰 MixerCommit / MixerDraft / Prefs 写入口 / native / 4B / 主链 / Gradle。
每条替换断言 old 唯一；任一不符整体不写盘。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
SPEC = ROOT / "app/src/main/java/com/tingben/app/core/ParamSpec.kt"
COMMON = ROOT / "scripts/r45_common.py"
V46 = ROOT / "scripts/r46_verify.py"
V45 = ROOT / "scripts/r45_verify.py"
APKV = ROOT / "scripts/r46_apk_verify.py"
CI = ROOT / ".github/workflows/ci.yml"


def sub(path, pairs):
    s = path.read_text(encoding="utf-8")
    orig = s
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path.name} 第 {i} 条替换命中 {n} 次（应为 1），整体不写盘")
        s = s.replace(old, new, 1)
    if s == orig:
        sys.exit(f"{path.name} 没有任何变化")
    path.write_text(s, encoding="utf-8")
    print(f"{path.name}: {len(pairs)} 条替换完成，{len(orig)} → {len(s)} 字符")


# ── ① 抽屉：320dp 官方默认 → 240dp（7 个短标签够用），条目同步收紧 ──
sub(CHROME, [
    (" * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器、不碰任何数据层。",
     """ * R47 · 抽屉收窄：M3 ModalDrawerSheet 默认 320dp 对 7 个三五字标签过宽，
 * 用 requiredWidth(240.dp) 覆盖（社区常用 250dp / M2 上限 280dp 之间取值），
 * 条目字号、padding 同步收紧；左滑手势与返回键优先行为一行不动。
 *
 * 本文件零业务逻辑：不认识 Screen 枚举、不碰 refresh 计数器、不碰任何数据层。"""),
    ("    ModalDrawerSheet(drawerContainerColor = surface) {",
     """    ModalDrawerSheet(
        modifier = Modifier.requiredWidth(240.dp),
        drawerContainerColor = surface
    ) {"""),
    ("                .padding(vertical = 18.dp)",
     "                .padding(vertical = 12.dp)"),
    ("                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),",
     "                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),"),
    ("                fontSize = 14.sp,", "                fontSize = 13.sp,"),
    ("        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)",
     "        modifier = Modifier.padding(horizontal = 10.dp, vertical = 0.dp)"),
])

# ── ② v4 预览 ⑥：滑块 + 精确值框共用一个值；官方字段名仍只在 ⓘ 里 ──
slider_block = """        // R47 · v4 预览 ⑥ 落地：滑块与精确值框是同一个值，改动只进草稿。
        // 滑块只覆盖常用区间（seed 无上界时取 2000）；任意合法值仍可直接在框里填。
        val sliderLo = (spec.min ?: 0.0).toFloat()
        val sliderHi = (spec.max ?: if (spec.key == "seed") 2000.0 else 1000.0).toFloat()
        val intLike = spec.key == "seed" || spec.key == "maxNewTokens" ||
            spec.key == "topK" || spec.key == "subTopK"
        val sliderVal = (input.toFloatOrNull() ?: cur.toFloatOrNull() ?: sliderLo)
            .coerceIn(sliderLo, sliderHi)
        Slider(
            value = sliderVal,
            onValueChange = { v ->
                val s = if (intLike) v.toLong().toString()
                else "%.2f".format(v).trimEnd('0').trimEnd('.')
                onInput(s)
            },
            valueRange = sliderLo..sliderHi,
            modifier = Modifier.fillMaxWidth()
        )
"""
sub(MAIN, [
    ('        HelpNote("参数说明", "${spec.hint} · 官方默认 ${spec.def}")',
     '        HelpNote("参数说明", "${spec.hint} · 官方默认 ${spec.def} · 字段 ${spec.official}")'),
    ("""        OutlinedTextField(
            value = input, onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("精确值 ${spec.rangeLabel}${spec.unit}", fontSize = 9.5.sp) },""",
     slider_block + """        OutlinedTextField(
            value = input, onValueChange = onInput,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("精确值 ${spec.rangeLabel}${spec.unit}", fontSize = 9.5.sp) },"""),
])

# ── ③ 参数显示名改人话（唯一来源仍是 ParamSpec.label，英文字段在 official）──
sub(SPEC, [
    ('"seed（随机种子）"', '"采样种子"'),
    ('"max_new_tokens（单次音频上限）"', '"单次音频长度上限"'),
    ('"主 temperature"', '"主·随机度"'),
    ('"主 top_k"', '"主·候选范围"'),
    ('"主 top_p"', '"主·核采样"'),
    ('"repetition_penalty"', '"重复惩罚"'),
    ('"副 temperature"', '"副·随机度"'),
    ('"副 top_k"', '"副·候选范围"'),
    ('"副 top_p"', '"副·核采样"'),
])

# ── ④ 同步既有验证脚本（不是放水：旧字面量已不存在，换成等价新形态）──
sub(COMMON, [
    ("""    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    return [x.rstrip() for x in t.splitlines()""",
     """    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    # R47 · ParamRow 允许改（滑块双模），由 r47_verify.py 做逐行对拍；
    # 其余 App() 之外的业务代码仍与 HEAD 逐行守恒。
    a = t.find("private fun ParamRow(")
    b = t.find("/** R41 · 共用音色选择器", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    return [x.rstrip() for x in t.splitlines()"""),
])
sub(V46, [
    ('    "ModalDrawerSheet(drawerContainerColor = surface)",',
     '    "requiredWidth(240.dp)",\n    "fontSize = 13.sp",'),
])
sub(V45, [
    ('    "app/src/main/java/com/tingben/app/core/ParamSpec.kt",\n',
     '    # R47 · ParamSpec 只改 9 个显示名，由 r47_verify.py 逐字段对拍（key/official/def/min/max 不变）\n'),
])
sub(APKV, [
    ('    b"RefreshStrip",\n',
     '    b"RefreshStrip",\n    # R47 · 抽屉收窄与 v4 ⑥ 人话参数名\n'
     '    "主·随机度".encode(), "副·候选范围".encode(), "采样种子".encode(),\n'),
])
sub(CI, [
    ("      - name: JDK 17\n",
     "      - name: R47 抽屉收窄与预览落地验证（正向 / 反向注入 / 守恒）\n"
     "        run: python3 scripts/r47_verify.py\n\n"
     "      - name: JDK 17\n"),
])
print("R47 应用完成")
