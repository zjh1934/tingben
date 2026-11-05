#!/usr/bin/env python3
"""R42 · 调音台 P2 验证：可见性切片 / 375px 源码断言 / 反向注入 / 语义守恒。

只依赖标准库。CI 里挂成独立 step；本地 `python3 scripts/r42_verify.py`。
核心口径：开发者信息只在 AdvancedDrawer 函数体的字符串字面量里出现，
普通视图（MAIN 去掉该函数体）三类词零命中 —— 注释不算 UI 文案。
"""
import os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
def read(p): return (ROOT / p).read_text(encoding="utf-8")

MAIN_P = "app/src/main/java/com/tingben/app/MainActivity.kt"
MAIN = read(MAIN_P)
MIXER = read("app/src/main/java/com/tingben/app/core/Mixer.kt")
SPEC = read("app/src/main/java/com/tingben/app/core/ParamSpec.kt")
DRAFT = read("app/src/main/java/com/tingben/app/core/MixerDraft.kt")
CPP = read("app/src/main/cpp/tingben_native.cpp")
MANIFEST = read("app/src/main/AndroidManifest.xml")
GRADLE = read("app/build.gradle.kts")

errs = []
def chk(cond, msg):
    if not cond: errs.append(msg)

def strip_comments(s):
    s = re.sub(r"(?s)/\*.*?\*/", lambda m: " " * len(m.group(0)), s)
    s = re.sub(r"(?m)//.*$", lambda m: " " * len(m.group(0)), s)
    return s

def literals(s):
    return re.findall(r'"((?:\\.|[^"\\])*)"', strip_comments(s))

def slice_fn(src, start, end):
    a = src.find(start); b = src.find(end, a + 1)
    if a < 0 or b < 0: return ""
    return src[a:b]

ADV = slice_fn(MAIN, "private fun AdvancedDrawer(", "// R42_ADVANCED_END")
CHIP = slice_fn(MAIN, "private fun ChipRow(", "/** R42 · 375px")
SECT = slice_fn(MAIN, "private fun MixerSection(", "/** R42 · help 说明")
SWITCH = slice_fn(MAIN, "private fun SwitchRow(", "/**\n * R42 · 开发者资料")
DIMS = slice_fn(MAIN, "VoiceDimension.entries.forEach { d ->", "Spacer(Modifier.height(6.dp))")
REST = MAIN.replace(ADV, "") if ADV else MAIN

# ── 1) 正向符号 ───────────────────────────────────────────────────────
POS = {
    "13 开发者信息收抽屉": ["private fun AdvancedDrawer(", "高级 · 开发者资料",
                     "AdvancedDrawer(rows, stamp)", "fun HelpNote(", "怎么用这份控制台",
                     "qt_tts_params → qt_synthesize", "官方资料（按名称自行查"],
    "14 375px 自动换行": ["private fun ChipRow(", "@OptIn(ExperimentalLayoutApi::class)",
                     "FlowRow(", "FilterChip(", "@Preview(widthDp = 375",
                     "horizontalArrangement = Arrangement.spacedBy(6.dp)",
                     "VoiceDimension.BREATH.options", "ChipRow(d.label, d.hint, d.options"],
    "15 开关文字与 info": ["private fun SwitchRow(", 'Text(if (checked) "开" else "关"',
                     'HelpNote("什么时候生效", timing)', "SwitchRow(\"拟声表达\"", "SwitchRow(\"角色映射\""],
    "16 文案与视觉": ["HorizontalDivider(color = Line)", "HelpNote(\"可选值\", hint)",
                 'HelpNote("参数说明",', 'HelpNote("采样顺序", Mixer.CHAIN)',
                 'Text("现成音色",', "color = Sub)"],
    "17 时机提示收口": ["TimingHint(TIMING_NOW)", "TimingHint(TIMING_NEXT)"],
}
for group, syms in POS.items():
    for s in syms:
        chk(s in MAIN, "缺少 %s 符号: %s" % (group, s))

# ── 2) 可见性：普通视图不许出现开发者信息（验收第 5 条） ────────────────
BANNED = ["qt_tts_params", ".py", "§", "O5:308", "qwen.h:133", "指纹", "compute worker"]
vis = literals(REST)
for w in BANNED:
    chk(not any(w in x for x in vis), "普通视图外泄开发者信息: " + w)
chk(any("qt_tts_params" in x for x in literals(ADV)), "高级抽屉里也没了触达链路")
chk(any("分区元信息：采样 9 项可编辑" in x for x in literals(ADV)), "分区元信息没进高级抽屉")
chk("096b_personas.py" not in literals(MAIN).__str__(), "普通视图还留着 .py 出处")
chk("不会自己生效" not in literals(MAIN).__str__(), "第二套时机说法没删干净")
# .py 只躺在 ParamSpec.UPPER 的 hint 数据里；覆盖表必须只渲染 label，否则外泄。
chk('add(Triple("上层 · 不进模型 API", it.label, "上层"))' in MIXER,
    "覆盖表把含 .py 出处的 hint 渲染进界面了")
chk('096b_personas.py' in SPEC and '096b_personas.py' not in MAIN,
    ".py 出处不在数据层或混进了界面源码")

# ── 3) 375px：12 维块 + ChipRow 内都不许横向藏选项 ─────────────────────
chk(bool(ADV) and bool(CHIP) and bool(SECT) and bool(DIMS), "函数切片失败")
chk("FlowRow(" in CHIP and "FilterChip(" in CHIP, "ChipRow 没用官方 FlowRow/FilterChip")
chk("horizontalScroll" not in CHIP, "ChipRow 又用横向滚动藏选项")
chk("ChipRow(" in DIMS and "SettingOption(" not in DIMS, "12 维没换成 ChipRow")
chk("horizontalScroll" not in DIMS, "12 维块里出现横向滚动")
chk("HorizontalDivider" in SECT and "CardBox(" not in SECT, "分区仍用嵌套阴影卡片")
chk("CardBox(" not in MAIN[MAIN.find("查看差异："):] if "查看差异：" in MAIN else True,
    "差异弹层仍有阴影卡")
chk("CardBox(Modifier.padding(vertical = 3.dp))" not in MAIN, "逐项对比卡片没换分割线")
# 吸顶条唯一一张卡是强调区，允许保留
chk(MAIN.count("CardBox(Modifier.padding(horizontal = 16.dp") == 1, "吸顶状态条卡片异常")

# ── 4) 对拍：12 维与表演层都走同一 ChipRow，长选项确实存在 ──────────────
dim_block = MIXER[MIXER.index("enum class VoiceDimension"):MIXER.index("/**\n * 一条「解剖学优先」")]
labels = re.findall(r'^\s*[A-Z_]+\("([^"]+)",\s*"([^"]+)",\s*listOf\(([^)]*)\)',
                    dim_block, re.M)
chk(len(labels) == 12, "12 维枚举不是 12 项: %d" % len(labels))
long_opts = [o.strip().strip('"') for _, _, opts in labels
             for o in opts.split(",") if len(o.strip().strip('"')) >= 6]
chk(len(long_opts) >= 3, "没有会触发窄屏溢出的长选项")
chip_calls = MAIN.count("ChipRow(")
# 1 定义 + 2 Preview 预览 + 1 个 12 维循环调用 + 5 个表演层调用
chk(chip_calls == 9, "ChipRow 出现数应为 9（定义 1 / 预览 2 / 循环 1 / 表演 5），实际 %d" % chip_calls)
hints = MAIN.count("TimingHint(")
chk(hints >= 9, "时机提示没复用收口（含定义应 >=9，实际 %d）" % hints)
print("对拍：12 维 / 长选项 %d 个 / ChipRow 调用 %d 处 / TimingHint %d 处"
      % (len(long_opts), chip_calls, hints))

# ── 5) 反向注入：任一处被改坏，断言必须响 ───────────────────────────────
def baseline(ui, spec):
    e = []
    def ck(c, m):
        if not c: e.append(m)
    adv = slice_fn(ui, "private fun AdvancedDrawer(", "// R42_ADVANCED_END")
    chip = slice_fn(ui, "private fun ChipRow(", "/** R42 · 375px")
    sect = slice_fn(ui, "private fun MixerSection(", "/** R42 · help 说明")
    dims = slice_fn(ui, "VoiceDimension.entries.forEach { d ->", "Spacer(Modifier.height(6.dp))")
    rest = ui.replace(adv, "") if adv else ui
    for w in BANNED:
        ck(not any(w in x for x in literals(rest)), "普通视图外泄 " + w)
    ck("FlowRow(" in chip and "FilterChip(" in chip and "horizontalScroll" not in chip,
       "ChipRow 不是 FlowRow 自动换行")
    ck("ChipRow(" in dims and "SettingOption(" not in dims, "12 维回退 SettingOption")
    ck("HorizontalDivider" in sect and "CardBox(" not in sect, "分区回退阴影卡")
    ck('Text(if (checked) "开" else "关"' in ui, "开关丢了开/关文字")
    ck("private fun HelpNote(" in ui and "private fun AdvancedDrawer(" in ui, "help/高级抽屉被删")
    ck("@Preview(widthDp = 375" in ui, "375px 证据被删")
    ck(ui.count("TimingHint(") >= 9, "时机提示没复用")
    ck("MixerCommit.commit(c, draft, n)" in ui, "唯一提交出口被删")
    ck("qt_tts_params.seed" in spec, "官方字段目录被破坏")
    return e

INJECT = {
    "泄露字段名": lambda s, m: (s.replace('HelpNote("怎么用这份控制台",',
        'Text("UI → qt_tts_params → qt_synthesize", fontSize = 10.sp)\n                HelpNote("怎么用这份控制台",'), m),
    "12维回退SettingOption": lambda s, m: (s.replace("ChipRow(d.label, d.hint, d.options",
        "SettingOption(d.label, d.options"), m),
    "ChipRow横向滚动": lambda s, m: (s.replace("FlowRow(\n            modifier = Modifier.fillMaxWidth(),",
        "Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())"), m),
    "分区回退阴影": lambda s, m: (s.replace("Column(mod.fillMaxWidth()) {\n        HorizontalDivider(color = Line)",
        "CardBox(mod) {\n        Spacer(Modifier.height(2.dp))"), m),
    "删开/关文字": lambda s, m: (s.replace('Text(if (checked) "开" else "关", fontSize = 11.sp,',
        'Text("", fontSize = 11.sp,'), m),
    "删HelpNote": lambda s, m: (s.replace("private fun HelpNote(", "private fun HelpNoteDISABLED("), m),
    "删375预览": lambda s, m: (s.replace("@Preview(widthDp = 375", "@Preview(widthDp = 600"), m),
    "删高级抽屉": lambda s, m: (s.replace("private fun AdvancedDrawer(", "private fun AdvancedDrawerDISABLED("), m),
    "删时机复用": lambda s, m: (s.replace("TimingHint(TIMING_NOW)",
        "Spacer(Modifier.height(1.dp))", 1), m),
    "删唯一出口": lambda s, m: (s.replace("MixerCommit.commit(c, draft, n)", "0L"), m),
}
base_e = baseline(MAIN, SPEC)
print("基线:", "PASS" if not base_e else "FAIL " + str(base_e))
chk(not base_e, "基线自身失败: " + str(base_e))
for name, fn in INJECT.items():
    u, m = fn(MAIN, SPEC)
    e = baseline(u, m)
    print("  反向注入 %-20s %s" % (name, "拦下" if e else "!! 没拦住 !!"))
    chk(bool(e), "反向注入没拦住: " + name)

# ── 6) 守恒：主链 / 真值源 / 官方字段 / native / manifest / gradle ───────
CONSERVE = {
    DRAFT: ["fun diff(", "fun commit(", "TIMING_NOW", "TIMING_NEXT"],
    SPEC: ["qt_tts_params.seed", "qt_tts_params.subtalker_top_p", "val DOCS"],
    MIXER: ["fun coverage()", "const val CHAIN", "fun snapshot("],
    CPP: ["TTS_PARAMS_APPLIED", "qt_tts_params tp;"],
    MANIFEST: ["android.permission.FOREGROUND_SERVICE", 'android:name=".ProduceService"'],
    GRADLE: ['applicationId = "com.tingben.app"'],
}
for content, lits in CONSERVE.items():
    for s in lits:
        chk(s in content, "守恒丢失: " + s)
chk("android.permission.INTERNET" not in MANIFEST, "Manifest 混进 INTERNET")
for banned in ["material-icons-extended", "compose.animation", "accompanist"]:
    chk(banned not in GRADLE, "为 P2 引了新依赖 " + banned)

# 旧断言仍成立（不靠改旧脚本放过）
for s in ["设定", "套用", "保存并生效", "直接保存生效", "应用预设"]:
    chk(not any(s in x for x in literals(MAIN)), "旧生效动词回退: " + s)
for s in ["恢复本组官方默认（待提交）", "分区元信息：采样 9 项可编辑",
          "参数触达与官方资料", "官方资料（按名称自行查"]:
    chk(any(s in x for x in literals(MAIN)), "旧断言依赖的字面量被删: " + s)

if not os.environ.get("CI"):
    KEEP = ["app/src/main/assets/prompt_zh.txt",
            "app/src/main/java/com/tingben/app/core/Pipeline.kt",
            "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
            "app/src/main/java/com/tingben/app/core/ParamSpec.kt",
            "app/src/main/java/com/tingben/app/core/Mixer.kt",
            "app/src/main/java/com/tingben/app/core/Prefs.kt",
            "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
            "app/src/main/java/com/tingben/app/ProduceService.kt",
            "app/src/main/java/com/tingben/app/AppChrome.kt",
            "app/src/main/cpp/tingben_native.cpp",
            "app/src/main/AndroidManifest.xml", "app/build.gradle.kts"]
    def head(p):
        try:
            return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                           stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            return None
    for p in KEEP:
        chk(head(p) == (ROOT / p).read_bytes(), "守恒文件被改动: " + p)
    old = head(MAIN_P)
    if old:
        noimp = lambda t: [x for x in t.split("// ── ⑥ 调音台")[0].splitlines()
                           if not x.startswith("import ")]
        chk(noimp(MAIN) == noimp(old.decode("utf-8")), "调音台之前的业务代码被改动")
else:
    print("CI 环境：HEAD 即本次提交，守恒已在提交前本地核对，跳过逐字节比")

if errs:
    print("\n".join("ERROR " + e for e in errs))
    sys.exit(1)
print("R42 验证 PASS：正向 %d 符号 / 可见性 %d 词 / 对拍 12 维 / 反向注入 %d 例 / 守恒 %d 项"
      % (sum(len(v) for v in POS.values()), len(BANNED), len(INJECT),
         sum(len(v) for v in CONSERVE.values())))
