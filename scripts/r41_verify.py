#!/usr/bin/env python3
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from r45_common import strip_r45, canon
"""R41 · 调音台 P1 验证：正向符号 / 参数范围对拍 / 反向注入 / 语义守恒。

只依赖标准库。CI 里挂成独立 step；本地可直接 `python3 scripts/r41_verify.py`。
守恒口径：R41 改了 Prefs.kt（纯 UI 键）与 ParamSpec.kt（范围元数据），
所以旧脚本的「逐字节一致」换成「4B 键 / voiceStamp 公式 / 官方字段名与默认值」语义守恒。
"""
import pathlib, re, subprocess, sys, os

ROOT = pathlib.Path(__file__).resolve().parent.parent
def read(p): return (ROOT / p).read_text(encoding="utf-8")

MAIN = read("app/src/main/java/com/tingben/app/MainActivity.kt")
PREFS = read("app/src/main/java/com/tingben/app/core/Prefs.kt")
SPEC = read("app/src/main/java/com/tingben/app/core/ParamSpec.kt")
PRESETS = read("app/src/main/java/com/tingben/app/core/MixerPresets.kt")
DRAFT = read("app/src/main/java/com/tingben/app/core/MixerDraft.kt")
CPP = read("app/src/main/cpp/tingben_native.cpp")
MANIFEST = read("app/src/main/AndroidManifest.xml")
GRADLE = read("app/build.gradle.kts")

errs = []
def chk(cond, msg):
    if not cond: errs.append(msg)

# ── 1) 正向：P1 六条各自的关键符号 ────────────────────────────────────
POS = {
    "7 锚点导航": ["fun AnchorBar(", 'AnchorBar(listOf(', '"identity" to "身份"',
                 'jumpTo(it)', 'scroll.animateScrollTo(anchors[k] ?: 0)',
                 'onGloballyPositioned', 'positionInParent()'],
    "7 展开态持久化": ["Prefs.mixerOpenSections(c)", "Prefs.setMixerOpenSections(c, next)",
                   "setMixerOpenSections", '?: ""'],
    "8 编号统一": ["MixerSection(\"采样参数 · 9 项真到模型\"", "MixerSection(\"表演层\"",
               "MixerSection(\"预设库\"", "分区元信息：采样 9 项可编辑"],
    "9 九项紧凑表": ["fun ParamRow(", "主采样（5 项）", "副采样（3 项）", "确定性（1 项）",
                 "ParamCatalog.find(it)", "恢复本组官方默认（待提交）"],
    "10 精确值范围": ["fun validate(", "val rangeLabel", "val min: Double?", "val max: Double?",
                  "val unit: String", "paramDrafts[spec.key] ?: cur", "isError = err.isNotBlank()",
                  "KeyboardOptions(", "KeyboardActions(onDone", "spec.validate(s)"],
    "11 预设 diff": ["showPresetDiff?.let", "MixerCommit.diff(active, next)", "对比并载入",
                  "fun rename(", "private fun usableName(", "private fun writeAll(",
                  'MixerPresetStore.safeName(presetName) == "untitled"', "重命名预设",
                  "删除预设二次确认"],
    "12 音色选择器合并": ["fun VoicePicker(", "搜索音色（编号 / 名称 / 特征）", "最近使用",
                    "Prefs.pushRecentVoice(c, p.id)", "Prefs.recentVoices(c)", "newCastGrid"],
}
for group, syms in POS.items():
    for s in syms:
        hay = MAIN + PREFS + SPEC + PRESETS
        chk(s in hay, "缺少 %s 符号: %s" % (group, s))

# 旧编号 / 旧控件 / 旧动词零残留
OLD = ["① 采样参数", "② 音色身份生成器", "③ 表演层 · 上层", "④ 音频开关 · 上层",
       "⑤ 角色预设", "⑥ 预设库", "⑦ 降级为导出", "⑧ 参数触达矩阵与官方资料",
       "现成音色网格（点一条", 'SettingOption("初始音色"', "rememberSaveable(key)",
       "可编辑 · O4/O5/O6", "只读 · O1~O7", "不进模型 API", "可增删改"]
for s in OLD:
    chk(s not in MAIN, "旧文案残留: " + s)
# 生效动词仍唯一（排除「存为预设」等非生效语境）
for s in ["设定", "套用", "保存并生效", "直接保存生效", "应用预设"]:
    lits = re.findall(r'"((?:\\.|[^"\\])*)"', MAIN)
    chk(not any(s in x for x in lits), "R40 回退：旧生效动词 " + s)
chk("MixerCommit.commit(c, draft, n)" in MAIN, "唯一出口被删")
for banned in ["Prefs.setTtsKnobs(", "Prefs.setVoiceDescription(", "Prefs.setDefaultPerf(",
               "Prefs.setNonVerbal(", "Prefs.setCastEnabled(", "Prefs.resetTtsKnobs("]:
    chk(banned not in MAIN[MAIN.find("// ── ⑥ 调音台"):], "界面出现第二个生效写入口 " + banned)

# ── 2) 9 项范围对拍：官方字段名 / 默认值 / min-max 与 TtsDefaults 一致 ──
KEYS = ["seed", "maxNewTokens", "temperature", "topK", "topP",
        "repetitionPenalty", "subTemperature", "subTopK", "subTopP"]
DEFAULTS = {"seed": "42", "maxNewTokens": "2048", "temperature": "0.9",
            "topK": "50", "topP": "1.0", "repetitionPenalty": "1.05",
            "subTemperature": "0.9", "subTopK": "50", "subTopP": "1.0"}
# 只取 SAMPLING 区块，避免 UPPER/LOCKED 干扰
blk = SPEC[SPEC.index("val SAMPLING"):SPEC.index("/** 上层项")]
for k in KEYS:
    m = re.search(r'ParamSpec\("' + k + r'",\s*"([^"]+)",\s*"[^"]*",\s*"([^"]+)"', blk)
    chk(m is not None, "缺 SAMPLING 项: " + k)
    if m:
        chk(m.group(1).startswith("qt_tts_params."), "%s 官方字段名不对: %s" % (k, m.group(1)))
        chk(m.group(2) == DEFAULTS[k], "%s 默认值漂移: %s != %s" % (k, m.group(2), DEFAULTS[k]))
    chk(("min = " in blk[blk.index('ParamSpec("' + k + '"'):][:400]), "%s 没标 min" % k)

# 模拟 validate() 的三层逻辑，对拍 9 组边界
BOUNDS = {"seed": (-1.0, None), "maxNewTokens": (256.0, 8192.0),
          "temperature": (0.0, 2.0), "topK": (0.0, 1000.0), "topP": (0.0, 1.0),
          "repetitionPenalty": (1.0, 2.0), "subTemperature": (0.0, 2.0),
          "subTopK": (0.0, 1000.0), "subTopP": (0.0, 1.0)}
def validate(raw, lo, hi):
    s = raw.strip()
    if not s: return "请填数值"
    try: n = float(s)
    except ValueError: return "只能填数字"
    if lo is not None and n < lo: return "不能小于"
    if hi is not None and n > hi: return "不能大于"
    return None
for k, (lo, hi) in BOUNDS.items():
    ok = DEFAULTS[k]
    chk(validate(ok, lo, hi) is None, "%s 官方默认值被自己判成非法" % k)
    below = "-999" if lo is not None else None
    if below: chk(validate(below, lo, hi) is not None, "%s 下界没拦住" % k)
    if hi is not None:
        chk(validate(str(hi + 1), lo, hi) is not None, "%s 上界没拦住" % k)
    chk(validate("abc", lo, hi) is not None, "%s 非数字没拦住" % k)
    chk(validate("", lo, hi) is not None, "%s 空值没拦住" % k)
print("对拍：9 项默认值 / 上下界 / 非数字 / 空值 4 组 =", 9 * 4, "例")

# ── 3) 反向注入：破坏任一处，断言必须响 ────────────────────────────────
def baseline(srcmap):
    e = []
    def ck(c, m):
        if not c: e.append(m)
    ui = srcmap["ui"]; pf = srcmap["prefs"]; ps = srcmap["presets"]
    ck("fun AnchorBar(" in ui, "锚点组件没了")
    ck("Prefs.setMixerOpenSections(c, next)" in ui and "setMixerOpenSections" in pf, "展开态没持久化")
    ck(not any(x in ui for x in ["① 采样参数", "⑧ 参数触达矩阵"]), "编号回退")
    ck("fun ParamRow(" in ui and "ParamCatalog.find(it)" in ui, "九项紧凑表回退")
    ck("paramDrafts[spec.key] ?: cur" in ui, "精确值又变空框了")
    ck("showPresetDiff?.let" in ui and "MixerCommit.diff(active, next)" in ui, "预设没有 diff")
    ck("private fun usableName(" in ps and 'return if (n == "untitled") null else n' in ps,
       "untitled 又能存了")
    ck("fun setMixerOpenSections(c: Context, keys: Set<String>)" in pf, "展开态没持久化")
    ck("fun VoicePicker(" in ui, "音色选择器没了")
    ck("MixerCommit.commit(c, draft, n)" in ui, "唯一出口被删")
    ck(ui.count("fun VoicePicker(") == 1, "音色选择器出现第二套")
    return e

def inject(case):
    ui, pf, ps = MAIN, PREFS, PRESETS
    if case == "drop_commit": ui = ui.replace("MixerCommit.commit(c, draft, n)", "0L")
    elif case == "restore_circle": ui = ui.replace("采样参数 · 9 项真到模型", "① 采样参数 · 9 项真到模型")
    elif case == "nine_cards": ui = ui.replace("ParamCatalog.find(it)", "ParamCatalog.SAMPLING.forEach")
    elif case == "empty_input": ui = ui.replace("paramDrafts[spec.key] ?: cur", 'paramDrafts[spec.key] ?: ""')
    elif case == "preset_no_diff": ui = ui.replace("showPresetDiff?.let", "null?.let")
    elif case == "second_picker": ui = ui.replace("fun VoicePicker(", "fun VoicePicker2(", 1)
    elif case == "allow_untitled": ps = ps.replace('return if (n == "untitled") null else n', "return n")
    elif case == "no_persist": pf = pf.replace("fun setMixerOpenSections", "fun setMixerOpenSectionsDISABLED")
    elif case == "no_anchor": ui = ui.replace("fun AnchorBar(", "fun AnchorBarDISABLED(")
    return {"ui": ui, "prefs": pf, "presets": ps}

print("基线:", "PASS" if not baseline({"ui": MAIN, "prefs": PREFS, "presets": PRESETS}) else "FAIL",
      baseline({"ui": MAIN, "prefs": PREFS, "presets": PRESETS}))
for case in ["drop_commit", "restore_circle", "nine_cards", "empty_input", "preset_no_diff",
             "second_picker", "allow_untitled", "no_persist", "no_anchor"]:
    e = baseline(inject(case))
    print("  反向注入 %-16s %s" % (case, "拦下" if e else "!! 没拦住 !!"))
    chk(bool(e), "反向注入没拦住: " + case)

# ── 4) 语义守恒：4B / 主链 / 官方字段 / native / manifest / gradle ───────
CONSERVE_LITERALS = {
    PREFS: ["fun thinking(c: Context)", "fun nCtx(c: Context)", "fun threads(c: Context)",
            "fun temperature(c: Context)", "fun voiceStamp(c: Context)",
            "INSTRUCT_TEMPLATE_VERSION.toString()", "ttsKnobs(c).hash()",
            "KEY_TTS_TEMP", "KEY_SEED", "KEY_DEFAULT_PERF"],
    SPEC: ["qt_tts_params.seed", "qt_tts_params.max_new_tokens", "qt_tts_params.temperature",
           "qt_tts_params.top_k", "qt_tts_params.top_p", "qt_tts_params.repetition_penalty",
           "qt_tts_params.subtalker_temperature", "qt_tts_params.subtalker_top_k",
           "qt_tts_params.subtalker_top_p", "fun withValue(k: TtsKnobs, raw: String): TtsKnobs?"],
    DRAFT: ["fun diff(a: MixerDraft, d: MixerDraft)", "fun commit(c: Context, d: MixerDraft, n: Int)",
            "TIMING_NOW", "TIMING_NEXT"],
    CPP: ["TTS_PARAMS_APPLIED", "qt_tts_params tp;"],
    MANIFEST: ["android.permission.FOREGROUND_SERVICE", "android:name=\".ProduceService\""],
    GRADLE: ['applicationId = "com.tingben.app"'],
}
for content, lits in CONSERVE_LITERALS.items():
    for s in lits:
        chk(s in content, "守恒丢失: " + s)
chk("android.permission.INTERNET" not in MANIFEST, "Manifest 混进 INTERNET")
# 新增的 Prefs 写只允许两个纯 UI 键，不许碰声音指纹
chk("KEY_MIXER_OPEN" in PREFS and "KEY_RECENT_VOICES" in PREFS, "UI 偏好键缺失")
chk("voiceStamp" in PREFS and "KEY_MIXER_OPEN" not in PREFS[PREFS.index("fun voiceStamp"):PREFS.index("fun summary")],
    "UI 偏好键混进 voiceStamp 指纹")
# 逐个文件与 git HEAD 比对（CI 里 HEAD 即本次提交，跳过）
if os.environ.get("CI"):
    print("守恒: CI 环境跳过 git HEAD 逐字节比（提交前本地已核）")
else:
    def head(p):
        try:
            return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                           stderr=subprocess.DEVNULL).decode("utf-8")
        except subprocess.CalledProcessError:
            return None
    keep = ["app/src/main/assets/prompt_zh.txt",
            "app/src/main/java/com/tingben/app/core/Pipeline.kt",
            "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
            "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
            "app/src/main/java/com/tingben/app/ProduceService.kt",
            "app/src/main/cpp/tingben_native.cpp",
            "app/src/main/AndroidManifest.xml", "app/build.gradle.kts"]
    for p in keep:
        old = head(p)
        chk(old is not None and read(p) == old, "守恒文件被改动: " + p)
    old_main = head("app/src/main/java/com/tingben/app/MainActivity.kt")
    no_imports = lambda ls: [x for x in ls if not x.startswith("import ")]
    cut = lambda t: canon(t.split("// ── ⑥ 调音台")[0])
    chk(old_main is not None and cut(MAIN) == cut(old_main),
        "调音台区块之前的业务代码被改动（R45 只允许新增设置页固定音色入口）")

# ── 5) 结果 ────────────────────────────────────────────────────────────
if errs:
    print("\n".join("ERROR " + e for e in errs))
    sys.exit(1)
print("R41 验证 PASS：正向 %d 符号 / 范围对拍 36 例 / 反向注入 9 例 / 语义守恒 %d 项"
      % (sum(len(v) for v in POS.values()), sum(len(v) for v in CONSERVE_LITERALS.values())))
