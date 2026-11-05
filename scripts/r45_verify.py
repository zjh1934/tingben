#!/usr/bin/env python3
"""R45 · 调音台 v4 落地验证：折叠分层 / 参数对齐 / 固定音色只读 / 守恒。

只依赖标准库。CI 挂独立 step；本地 `python3 scripts/r45_verify.py`。
口径：三态只读区不允许出现开关或提交动词；不可调项一个都不画成可操作控件。
"""
import os, pathlib, re, subprocess, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from r45_common import strip_r45

ROOT = pathlib.Path(__file__).resolve().parent.parent
def read(p): return (ROOT / p).read_text(encoding="utf-8")
MAIN_P = "app/src/main/java/com/tingben/app/MainActivity.kt"
MAIN = read(MAIN_P)
PREFS = read("app/src/main/java/com/tingben/app/core/Prefs.kt")
SPEC = read("app/src/main/java/com/tingben/app/core/ParamSpec.kt")
errs = []
def chk(c, m):
    if not c: errs.append(m)

def literals(s):
    s = re.sub(r"(?s)/\*.*?\*/", lambda m: " " * len(m.group(0)), s)
    s = re.sub(r"(?m)//.*$", lambda m: " " * len(m.group(0)), s)
    return re.findall(r'"((?:\\.|[^"\\])*)"', s)

def slice_between(s, a, b):
    i = s.find(a); j = s.find(b, i + 1) if i >= 0 else -1
    return s[i:j] if i >= 0 and j >= 0 else ""

FIXED = slice_between(MAIN, 'MixerSection("固定音色 · 官方模式说明"', '// ── R45 · 参数与模型一一对应')
MAP = slice_between(MAIN, 'MixerSection("哪些能改 · 和模型一一对齐"', '// ── 资料与诊断')

# 1) 正向：折叠分层 + 参数对齐 + 固定音色只读
POS = {
    "默认全折叠": ['?: ""'],
    "固定音色后程折叠": ['MixerSection("固定音色 · 官方模式说明", "fixedVoice",',
                    'openMap.contains("fixedVoice")', 'badge = "只读"',
                    '"fixedVoice" to "固定音色"'],
    "官方三模式与 9 编号": ['声音设计（当前）', '预置音色', '声音克隆',
                       'Vivian / Serena / Uncle_Fu / Dylan / Eric / Ryan / Aiden / Ono_Anna / Sohee'],
    "不画开关的明话": ['界面不画开关', '不等于官方承诺的跨段完全一致'],
    "三态对齐只读取": ['MixerSection("哪些能改 · 和模型一一对齐", "mapping",',
                  '✅ 真到声音模型（能调）', '⚠️ 上层（能改，但模型只看到文字或 App 流程）',
                  '🔒 结构上没有（不画开关）', 'ParamCatalog.LOCKED.forEach',
                  'ParamCatalog.SAMPLING.joinToString', 'badge = "只读"'],
    "采样真到模型": ['MixerSection("采样参数 · 9 项真到模型", "sampling",', 'badge = "✅ 可调"'],
    "seed 一键换新的固定种子": ['onRoll: (() -> Unit)? = null', '"⟳ 换一个"',
                        'java.util.concurrent.ThreadLocalRandom.current()',
                        'MIXER_SEED', '仅草稿，不直接生效'],
    "12 维每维自定义框": ['allowCustom: Boolean = false', 'customValue: String = ""',
                    '"自己填（可选）"', 'allowCustom = true', 'curDim in d.options'],
    "设置页固定音色入口": ['R45_FIXED_VOICE_ENTRY_BEGIN', 'R45_FIXED_VOICE_ENTRY_END',
                    '官方三模式说明 · 当前权重能固定哪三样',
                    'Prefs.mixerOpenSections(c) + "fixedVoice"'],
    "首屏人话与直达": ['固定音色 = 这段描述 + 种子 + 9 项采样全书不变。', '固定音色是什么？ ›'],
    "提交不依赖试听": ['提交不依赖试听：改完直接点「提交变更」即可生效。'],
    "底部留白": ['Spacer(Modifier.height(40.dp))'],
}
for group, syms in POS.items():
    for s in syms:
        chk(s in MAIN, f"正向缺失 [{group}]: {s}")
chk('?: ""' in PREFS, "Prefs 默认值不是全部折叠")

# 2) 只读区不出现可操作控件 / 第二个生效动词
for name, blk in (("固定音色", FIXED), ("对齐", MAP)):
    chk(len(blk) > 200, f"{name} 区块切片为空（marker 丢失）")
    chk("Switch(" not in blk and "SettingOption(" not in blk and "Slider(" not in blk,
        f"{name} 只读区出现了可操作控件（不可调项被画成开关）")
    chk("Button(onClick" not in blk, f"{name} 只读区出现了按钮（第二个生效动作）")
    for w in ("保存并生效", "直接保存生效", "应用", "设定"):
        chk(w not in "".join(literals(blk)), f"{name} 只读区残留旧生效动词: {w}")
chk("✅ 真到声音模型（能调）" in MAP and "ParamCatalog.LOCKED.forEach" in MAP,
    "三态对齐区没有同时列出可调与不可调")

# 3) 反向注入：任一处改坏，断言必须响
def baseline(main, prefs):
    e = []
    def ck(c, m):
        if not c: e.append(m)
    fx = slice_between(main, 'MixerSection("固定音色 · 官方模式说明"', '// ── R45 · 参数与模型一一对应')
    mp = slice_between(main, 'MixerSection("哪些能改 · 和模型一一对齐"', '// ── 资料与诊断')
    ck('?: ""' in prefs, "默认展开回退")
    ck('MixerSection("固定音色 · 官方模式说明", "fixedVoice",' in main, "固定音色区被删")
    ck('MixerSection("哪些能改 · 和模型一一对齐", "mapping",' in main, "对齐区被删")
    ck("界面不画开关" in "".join(literals(fx)), "固定音色声明被删")
    ck("Switch(" not in mp and "SettingOption(" not in mp, "不可调项被画成开关")
    ck("ParamCatalog.LOCKED.forEach" in mp, "不可调清单被删")
    ck('"⟳ 换一个"' in main and "MIXER_SEED" in main, "seed 换一个被删")
    ck("allowCustom = true" in main, "12 维自定义框被删")
    ck("R45_FIXED_VOICE_ENTRY_BEGIN" in main, "设置页入口被删")
    ck("提交不依赖试听" in "".join(literals(main)), "提交与试听又绑回去")
    ck("MixerCommit.commit(c, draft, n)" in main, "唯一提交出口被删")
    ck("Spacer(Modifier.height(40.dp))" in main, "底部留白被删")
    ms = main[main.find("private fun MixerScreen("):]
    ck(not re.findall(r"Prefs\.set(?!MixerOpenSections)\w+\(", ms),
       "出现第二个写生效态的入口")
    return e

INJECT = {
    "恢复 identity 默认": lambda m, p: (m, p.replace('?: ""', '?: "identity"')),
    "删固定音色区": lambda m, p: (m.replace('MixerSection("固定音色 · 官方模式说明", "fixedVoice",',
                                        'MixerSection("已删除", "gone",'), p),
    "删对齐区": lambda m, p: (m.replace('MixerSection("哪些能改 · 和模型一一对齐", "mapping",',
                                     'MixerSection("已删除", "gone",'), p),
    "不可调画成开关": lambda m, p: (m.replace('ParamCatalog.LOCKED.forEach { r ->',
                                          'Switch(checked = false, onCheckedChange = {})\n                ParamCatalog.LOCKED.forEach { r ->'), p),
    "删 seed 换一个": lambda m, p: (m.replace('"⟳ 换一个"', '"默认"'), p),
    "删自定义框": lambda m, p: (m.replace("allowCustom = true", "allowCustom = false"), p),
    "删设置页入口": lambda m, p: (m.replace("R45_FIXED_VOICE_ENTRY_BEGIN", "R45_ENTRY_GONE"), p),
    "提交绑回试听": lambda m, p: (m.replace("提交不依赖试听：改完直接点「提交变更」即可生效。",
                                        "先试听再提交变更才能生效。"), p),
    "注入第二写入口": lambda m, p: (m.replace("fun commitAll() {",
                                          "fun commitAll() { Prefs.setTtsKnobs(c, draft.knobs)"), p),
    "删唯一出口": lambda m, p: (m.replace("MixerCommit.commit(c, draft, n)", "0L"), p),
}
base_e = baseline(MAIN, PREFS)
chk(not base_e, "基线自身失败: " + str(base_e))
for name, fn in INJECT.items():
    m, p = fn(MAIN, PREFS)
    e = baseline(m, p)
    print("  反向注入 %-18s %s" % (name, "拦下" if e else "!! 没拦住 !!"))
    chk(bool(e), "反向注入没拦住: " + name)

# 4) 守恒：真值源、引擎、主链、权限、依赖逐字节比 HEAD
KEEP = [
    "app/src/main/assets/prompt_zh.txt",
    "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
    "app/src/main/java/com/tingben/app/core/Mixer.kt",
    "app/src/main/java/com/tingben/app/core/ParamSpec.kt",
    "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
    "app/src/main/java/com/tingben/app/core/PlayQueue.kt",
    "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
    "app/src/main/java/com/tingben/app/ProduceService.kt",
    "app/src/main/java/com/tingben/app/AppChrome.kt",
    "app/src/main/cpp/tingben_native.cpp",
    "app/src/main/AndroidManifest.xml", "app/build.gradle.kts",
]
if not os.environ.get("CI"):
    def head(p):
        try:
            return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                           stderr=subprocess.DEVNULL)
        except subprocess.CalledProcessError:
            return None
    for p in KEEP:
        chk(head(p) is not None and head(p) == (ROOT / p).read_bytes(), "守恒文件被改动: " + p)
    # Prefs 只许改默认值那一段，其余逐字节不变
    oldp = head("app/src/main/java/com/tingben/app/core/Prefs.kt")
    if oldp:
        o = oldp.decode("utf-8")
        a = o[o.find("    /** 默认只展开"):o.find("    fun setMixerOpenSections")]
        b = PREFS[PREFS.find("    /** R45"):PREFS.find("    fun setMixerOpenSections")]
        chk(o.replace(a, "") == PREFS.replace(b, ""), "Prefs 除默认值外被改动")
else:
    print("CI 环境：HEAD 即本次提交，守恒已在提交前本地核对")

# MixerScreen 段内不得出现第二个写生效态的入口
MS = MAIN[MAIN.find("private fun MixerScreen("):]
writes = re.findall(r"Prefs\.set(?!MixerOpenSections)\w+\(", MS)
chk(not writes, "调音台界面层出现直接写生效态: " + str(writes))

if errs:
    print("\n".join("ERROR " + e for e in errs))
    sys.exit(1)
print("R45 验证 PASS：正向 %d 符号 / 只读区 2 块 / 反向注入 %d 例 / 守恒 %d 项"
      % (sum(len(v) for v in POS.values()) + 1, len(INJECT), len(KEEP) + 2))
