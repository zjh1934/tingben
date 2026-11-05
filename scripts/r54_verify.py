#!/usr/bin/env python3
"""R54/P0 · 设置页 / 语言模型设置页 v8 验证。

用户批准 v8 12 屏预览后落的双页：设置页只管软件，语言模型另开独立页。
本脚本只做源码级形态断言与反向注入；真编译由 CI 的 assembleDebug 负责。
R60 换模型 11 处联动另由 r60_verify.py 负责。
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
s = MAIN.read_text(encoding="utf-8")
errs = []


def need(cond, msg):
    if not cond:
        errs.append(msg)


def between(text, a, b):
    i = text.index(a)
    j = text.index(b, i + 1)
    return text[i:j]


# 只取两页函数体，避免把别处的同名字符串算成残留
SET = between(s, "private fun SettingsScreen(", "private fun DiagnosticsBlock(")
DIAG = between(s, "private fun DiagnosticsBlock(", "private fun SoundProbeBlock(")
PROBE = between(s, "private fun SoundProbeBlock(", "private fun LlmSettingsScreen(")
LLM = between(s, "private fun LlmSettingsScreen(", "// ── ⑤b 模型页")

# ── 1) 设置页正向：四分区、默认态、入口、时机提示 ──────────────────────
SECTIONS = ['SettingsSection("外观与显示", "软件"',
            'SettingsSection("数据与日志", "本机"',
            'SettingsSection("后台与关于", "系统"',
            'SettingsSection("更多", "独立页"']
for x in SECTIONS:
    need(x in SET, "设置页缺分区: " + x)
need([SET.index(x) for x in SECTIONS] == sorted(SET.index(x) for x in SECTIONS),
     "设置页分区顺序不对")
need("var moreOpen by rememberSaveable { mutableStateOf(true) }" in SET,
     "「更多」不是默认展开（语言模型入口要一眼看到）")
need("TextButton(onClick = onLlm, Modifier.fillMaxWidth())" in SET, "缺语言模型设置入口")
need("DiagnosticsBlock(c, refresh)" in SET and "SoundProbeBlock(c, refresh)" in SET,
     "诊断 / 试音能力没搬进数据与日志")
need(SET.count("改动立即保存 · 对所有新任务生效 · 正在跑的任务不受影响") == 1,
     "时机提示不是恰好一句")
need("SettingInfoRow(" in s[s.index("private fun SettingInfoRow("):s.index("private fun SettingsScreen(")],
     "缺只读信息行组件")
need("@Suppress(\"UNUSED_PARAMETER\")" in s[s.index("/**\n * P0 · 设置页只管软件"):s.index("private fun SettingsScreen(")],
     "零调用参数没标 Suppress")

# ── 2) 语言模型页正向：只读卡 / 已移除说明 / 主层 / 高级 / 诊断 ──────────
need("Huihui Qwen3-4B-Instruct-2507 abliterated" in LLM, "只读模型卡没按新权重重画")
need("仅非思考（权重决定）" in LLM, "权重口径没写清")
need("换模型后已移除" in LLM, "缺橙色已移除说明")
for x in ['SettingsSection("标注与上层", "长期默认"',
          'SettingsSection("高级", "按需"',
          'SettingsSection("诊断与评测", "按需"']:
    need(x in LLM, "语言模型页缺分区: " + x)
need("var upperOpen by rememberSaveable { mutableStateOf(true) }" in LLM,
     "标注与上层不是默认展开")
need(LLM.count('SettingOption("') == 3, "高级里三项到引擎参数不齐（上下文/线程/随机度）")
need("实际采样链" in LLM and "top_p 0.9 · seed 42" in LLM, "实际采样链只读行丢了")
need("Annotator.templates(c).first" in LLM, "系统提示词只读全文没接 assets")
need("AbGate.summary()" in LLM, "JSON 通过率 A/B 入口没进诊断")
need("Prefs.setNCtx(c," in LLM and "Prefs.setThreads(c," in LLM and
     "Prefs.setTemperature(c," in LLM, "三项参数没真写 Prefs")
need("Prefs.setNonVerbal(c, it)" in LLM and "Prefs.setCastEnabled(c, it)" in LLM,
     "两个上层开关没真写 Prefs")

# ── 3) 零残留：两项都不许出现的旧形态 ──────────────────────────────────
BANNED = ["🎛 调音台", "固定音色", "音色 12 维 · 采样 9 项", "去书架制作",
          "提交变更", "①", "②", "SettingSwitchRow(\"思考过程\"",
          "快速 / 深度", "onMixer()", "onLibrary()", "onCast()",
          "GroupLabel(", "MixerDraft", "qt_tts_params"]
for b in BANNED:
    need(b not in SET, "设置页残留旧形态: " + b)
for b in ["SettingSwitchRow(\"思考过程\"", "Prefs.setThinking", "/no_think",
          "SettingOption(\"快速", "SettingSwitchRow(\"快速",
          "top_k", "min_p", "YaRN", "n_gpu_layers",
          "提交变更", "MixerDraft", "调音台"]:
    need(b not in LLM, "语言模型页出现假开关/跨层: " + b)
need("SettingSwitchRow(\"思考过程\"" not in s, "全文件仍有思考开关")
need('"语言模型" -> "https://hf-mirror.com/bartowski/' not in s, "旧权重直链没换")
need("huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf" in s, "新权重直链没落地")
need("Screen.LLM -> LlmSettingsScreen" in s, "新页没接进导航")
need('NAV_LLM to "语言模型"' in (ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt").read_text(encoding="utf-8"),
     "抽屉没有独立目的地")

# ── 4) 能力守恒：诊断 / 试音 / 二次确认一个没丢 ──────────────────────────
for x in ["buildDiag(false)", "buildDiag(true)", "AuditLog.summaryText(c)",
          "RawDump.latest(c)", "AlertDialog(", "清理制作过程的分段音频"]:
    need(x in DIAG, "诊断能力丢失: " + x)
for x in ["ttsSynthesize(h, text, instruct, null, \"chinese\"",
          "probe_emote.wav", "MediaPlayer()"]:
    need(x in PROBE, "真合成试音能力丢失: " + x)

# ── 5) 反向注入：任一处改坏，断言必须响 ────────────────────────────────
def baseline(text):
    e = []
    setb = between(text, "private fun SettingsScreen(", "private fun DiagnosticsBlock(")
    llm = between(text, "private fun LlmSettingsScreen(", "// ── ⑤b 模型页")
    diag = between(text, "private fun DiagnosticsBlock(", "private fun SoundProbeBlock(")
    if 'SettingsSection("外观与显示", "软件"' not in setb: e.append("设置页首分区丢了")
    if 'SettingsSection("更多", "独立页"' not in setb: e.append("更多分区丢了")
    if "TextButton(onClick = onLlm, Modifier.fillMaxWidth())" not in setb:
        e.append("语言模型入口丢了")
    if 'SettingSwitchRow("思考过程"' in llm:
        e.append("思考开关回潮")
    if "换模型后已移除" not in llm: e.append("已移除说明丢了")
    if "AbGate.summary()" not in llm: e.append("A/B 入口丢了")
    if llm.count('SettingOption("') != 3: e.append("高级三项参数不齐")
    if "AlertDialog(" not in diag: e.append("清理缓存二次确认丢了")
    if "Screen.LLM -> LlmSettingsScreen" not in text: e.append("新页导航断了")
    if '"语言模型" -> "https://hf-mirror.com/bartowski/' in text: e.append("旧直链回潮")
    if "huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf" not in text:
        e.append("新权重直链丢了")
    if "Annotator.templates(c).first" not in llm: e.append("系统提示词只读全文被改成可编辑")
    if "只读：改它会联动 9 处" not in llm: e.append("提示词只读说明丢了")
    if 'SettingSwitchRow("思考过程"' in text: e.append("全文件出现思考开关")
    return e


base = baseline(s)
need(not base, "基线自身失败: " + str(base))
INJECT = {
    "删语言模型入口": lambda t: t.replace("TextButton(onClick = onLlm, Modifier.fillMaxWidth())",
                                    "TextButton(onClick = {})"),
    "恢复思考开关": lambda t: t.replace('SettingInfoRow("标注方式", "上层", "想用就用",',
                                  'SettingSwitchRow("思考过程", false) { }\n            SettingInfoRow("标注方式", "上层", "想用就用",'),
    "恢复调音台入口": lambda t: t.replace('SettingsSection("更多", "独立页"',
                                   'SettingsSection("🎛 调音台", "独立页"'),
    "删清理确认": lambda t: t.replace("AlertDialog(", "AlertDialogDISABLED("),
    "删 A/B 入口": lambda t: t.replace("Text(AbGate.summary(), fontSize = 11.sp, color = Sub)",
                                  "Text(\"\", fontSize = 11.sp)"),
    "高级少一项": lambda t: t.replace('SettingOption("运算线程"', 'SettingOptionDISABLED("运算线程"'),
    "系统提示词可编辑": lambda t: t.replace("Annotator.templates(c).first", "Prefs.voiceDescription(c)"),
    "新页导航断开": lambda t: t.replace("Screen.LLM -> LlmSettingsScreen", "Screen.LLM -> SettingsScreen"),
    "恢复旧直链": lambda t: t.replace("huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf",
                                 "mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf"),
    "删已移除说明": lambda t: t.replace('Text("换模型后已移除"', 'Text(""'),
}
for name, fn in INJECT.items():
    inj = fn(s)
    if inj == s:
        errs.append("反向注入锚点失效: " + name)
        continue
    got = baseline(inj)
    need(bool(got), "反向注入没拦下: " + name)
    print("  反向注入 %-16s %s" % (name, "拦下" if got else "!! 没拦住 !!"))

# ── 6) 守恒：主链文件一行不动 ────────────────────────────────────────────
prefs = (ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt").read_text(encoding="utf-8")
need("fun thinking(c: Context)" in prefs and "fun setThinking(c: Context" in prefs,
     "KEY_THINKING 兼容层被删（旧 shared_prefs 会异常）")
need("0 = 贪心，输出 JSON 最稳" in prefs, "温度默认值被改")
prompt = (ROOT / "app/src/main/assets/prompt_zh.txt").read_text(encoding="utf-8")
need("---SEPARATOR---" in prompt, "prompt_zh.txt v2 被改")
need("qt_tts_params" not in LLM, "语言模型页混进 TTS 参数")

if errs:
    print("R54/P0 双页验证 FAIL")
    for e in errs:
        print("  - " + e)
    sys.exit(1)
print("R54/P0 双页验证 PASS：设置页 4 分区 / 语言模型页 3 分区 / 零残留 / "
      "反向注入 %d 例 / 能力守恒" % len(INJECT))
