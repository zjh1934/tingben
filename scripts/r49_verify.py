#!/usr/bin/env python3
"""R49 · 调音台 v5 重设计验证：正向 8 条 / 反向注入 / 9 项参数对拍 / 守恒。

CI 挂独立 step；本地 `python3 scripts/r49_verify.py`，`CI=true` 时跳过 git HEAD 逐字节比。
只核界面层形态与守恒，不重复实现业务逻辑。
"""
import os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
P_MAIN = "app/src/main/java/com/tingben/app/MainActivity.kt"
P_SPEC = "app/src/main/java/com/tingben/app/core/ParamSpec.kt"


def read(p):
    return (ROOT / p).read_text(encoding="utf-8")


MAIN = read(P_MAIN)
SPEC = read(P_SPEC)
errs = []


def chk(ok, msg):
    if not ok:
        errs.append(msg)


def between(s, a, b):
    i = s.find(a)
    j = s.find(b, i + 1) if i >= 0 else -1
    return s[i:j] if i >= 0 and j > i else ""


TOP = between(MAIN, "// ── 吸顶状态条", "        AnchorBar(listOf(")
TRIAL = between(MAIN, 'MixerSection("试听", "trial"', "// ── R49 · 三态分区")
ID = between(MAIN, "// ── R49 · v5 第 3 条", "            // ── 采样参数：")
PR = between(MAIN, "private fun ParamRow(", "/** R41 · 共用音色选择器")
AB = between(MAIN, "private fun AnchorBar(", "private fun ParamRow(")
MIX = MAIN[MAIN.find("private fun MixerScreen("):]


def baseline(m):
    e = []

    def ck(ok, msg):
        if not ok:
            e.append(msg)

    top = between(m, "// ── 吸顶状态条", "        AnchorBar(listOf(")
    trial = between(m, 'MixerSection("试听", "trial"', "// ── R49 · 三态分区")
    ident = between(m, "// ── R49 · v5 第 3 条", "            // ── 采样参数：")
    pr = between(m, "private fun ParamRow(", "/** R41 · 共用音色选择器")
    ab = between(m, "private fun AnchorBar(", "private fun ParamRow(")
    mix = m[m.find("private fun MixerScreen("):]

    # 1) 细状态条：只留返回 / 状态 / 查看差异 / 提交，试听不在其中
    for s in ["‹ 返回", "提交变更", "查看差异", "未提交 ${changes.size} 项"]:
        ck(s in top, "细状态条缺: " + s)
    ck("试听未提交参数" not in top and "CardBox(" not in top, "试听又塞回吸顶大卡")

    # 2) 试听折叠区：两态 + 试听文本 + 提交不依赖试听
    for s in ['MixerSection("试听", "trial"', "openMap.contains(\"trial\")",
              "试听未提交参数", "试听已生效参数", "试听文本",
              "改完直接提交，不必先试听。"]:
        ck(s in trial, "两态试听折叠区缺: " + s)

    # 3) 锚点：FlowRow 自动换行 + 当前区高亮 + 固定音色直达
    for s in ["private fun AnchorBar(", "FlowRow(", "FilterChip(",
              "selected = key == current", "current: String"]:
        ck(s in ab, "锚点组件缺: " + s)
    ck("horizontalScroll" not in ab, "锚点又用横向滚动藏选项")
    ck('"fixedVoice" to "固定音色"' in m, "固定音色锚点被删")
    ck("), activeAnchor) { jumpTo(it) }" in m, "锚点没传当前区")
    ck("if (k !in openMap) toggleSection(k, true)" in m, "锚点点击没先展开目标区")

    # 4) 身份卡：摘要 / 展开 / 逐维 diff，不再两遍全文
    for s in ["当前生效 · 全书固定（只读）", "展开全文 ›", "编辑描述 ›",
              "改动 ${vdiff.size} 处", "voiceFieldDiff(",
              "固定音色 = 这段描述 + 种子 + 9 项采样全书不变。", "固定音色是什么？ ›"]:
        ck(s in ident, "身份卡缺: " + s)
    ck("Text(active.voice.trim().ifBlank { DEFAULT_IDENTITY }," not in ident,
       "身份卡又把已生效全文直铺两遍")

    # 5) ParamRow：一行一项，只有 1 个精确值框，R47 滑块双模仍在
    for s in ["Modifier.width(84.dp)", "Modifier.weight(1f).height(30.dp)",
              "Modifier.width(74.dp)", "精确值", "Slider(",
              "val sliderLo = (spec.min ?: 0.0).toFloat()",
              "valueRange = sliderLo..sliderHi", "onInput(s)", "FilterChip(",
              "字段 ${spec.official}", "HelpNote(", "⟳ 换一个"]:
        ck(s in pr, "紧凑参数行缺: " + s)
    ck(pr.count("OutlinedTextField(") == 1, "又画了第二个精确值输入框")
    ck("horizontalScroll" not in pr, "参数行出现横向滚动")

    # 6) 三态分区徽标
    ck(m.count('badge = "上层"') == 3, "上层徽标不是 3 处")
    for s in ["辅助设置 · 上层（能改，但不是模型字段）", 'badge = "两态"',
              'badge = "配置"', 'badge = "只读"', 'badge = "✅ 可调"']:
        ck(s in m, "三态分区缺: " + s)

    # 7) 底部三层留白
    ck("Spacer(Modifier.height(40.dp))" in m
       and "// R49 · v5 第 7 条：补 8dp" in m
       and "Spacer(Modifier.height(8.dp))" in m,
       "底部 48dp 留白被删")

    # 8) 唯一出口 / 提交不依赖试听 / 参数一个不删
    ck("提交不依赖试听：改完直接点「提交变更」即可生效。" in m, "差异屏没写清提交不依赖试听")
    ck("MixerCommit.commit(c, draft, n)" in mix, "唯一提交出口被删")
    for banned in ["Prefs.setTtsKnobs(", "Prefs.setVoiceDescription(", "Prefs.setDefaultPerf(",
                   "Prefs.setNonVerbal(", "Prefs.setCastEnabled("]:
        ck(banned not in mix, "界面出现第二个生效写入口 " + banned)
    for s in ["ParamCatalog.find(it)", "主采样（5 项）", "副采样（3 项）",
              "确定性（1 项）", "恢复本组官方默认（待提交）"]:
        ck(s in m, "9 项分组/单一目录被改: " + s)
    ck(m.count("TimingHint(") >= 9, "时机提示复用被删")
    return e


base_e = baseline(MAIN)
chk(not base_e, "基线自身失败: " + str(base_e))

# ── 反向注入：任一处被改坏，断言必须响 ────────────────────────────────
INJECT = {
    "试听塞回大卡": lambda s: s.replace("            Button(onClick = { commitAll() }, enabled = dirty) {",
                                    '            Text("试听未提交参数")\n            Button(onClick = { commitAll() }, enabled = dirty) {'),
    "锚点改回横向滚动": lambda s: s.replace("    FlowRow(\n        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),",
                                     "    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {"),
    "删当前区高亮": lambda s: s.replace("                selected = key == current,", "                selected = false,"),
    "身份两遍全文": lambda s: s.replace("                val vdiff = voiceFieldDiff(",
                                   '                Text(active.voice.trim().ifBlank { DEFAULT_IDENTITY },\n                val vdiff = voiceFieldDiff('),
    "删逐维diff": lambda s: s.replace('Text("改动 ${vdiff.size} 处", fontSize = 11.sp, fontWeight = FontWeight.Bold)',
                                  'Text("无改动", fontSize = 11.sp)'),
    "画第二个精确值框": lambda s: s.replace("            TextButton(onClick = onReset, modifier = Modifier.width(28.dp)) {",
                                     '            OutlinedTextField(value = input, onValueChange = {}, singleLine = true)\n            TextButton(onClick = onReset, modifier = Modifier.width(28.dp)) {'),
    "提交绑回试听": lambda s: s.replace("提交不依赖试听：改完直接点「提交变更」即可生效。",
                                   "先试听再提交变更才能生效。"),
    "删底部8dp": lambda s: s.replace(
        "            // R49 · v5 第 7 条：补 8dp 覆盖三键 48dp / 手势白条 24dp\n"
        "            Spacer(Modifier.height(8.dp))", ""),
    "删固定音色锚点": lambda s: s.replace('"fixedVoice" to "固定音色", ', ""),
    "界面直写生效态": lambda s: s.replace("    fun commitAll() {",
                                    "    fun commitAll() {\n        Prefs.setTtsKnobs(c, draft.knobs)"),
}
for name, fn in INJECT.items():
    e = baseline(fn(MAIN))
    print("  反向注入 %-18s %s" % (name, "拦下" if e else "!! 没拦住 !!"))
    chk(bool(e), "反向注入没拦住: " + name)

# ── 9 项参数与模型一一对齐（R48 §4 原样）─────────────────────────────
EXPECT = [
    ("seed", "qt_tts_params.seed", "42", -1.0, None),
    ("maxNewTokens", "qt_tts_params.max_new_tokens", "2048", 256.0, 8192.0),
    ("temperature", "qt_tts_params.temperature", "0.9", 0.0, 2.0),
    ("topK", "qt_tts_params.top_k", "50", 0.0, 1000.0),
    ("topP", "qt_tts_params.top_p", "1.0", 0.0, 1.0),
    ("repetitionPenalty", "qt_tts_params.repetition_penalty", "1.05", 1.0, 2.0),
    ("subTemperature", "qt_tts_params.subtalker_temperature", "0.9", 0.0, 2.0),
    ("subTopK", "qt_tts_params.subtalker_top_k", "50", 0.0, 1000.0),
    ("subTopP", "qt_tts_params.subtalker_top_p", "1.0", 0.0, 1.0),
]
block = between(SPEC, "val SAMPLING: List<ParamSpec> = listOf(", "    /** 上层项")
flat = re.sub(r"\s+", " ", block)
parsed = []
for seg in flat.split("ParamSpec(")[1:]:
    q = re.findall(r'"(?:[^"\\]|\\.)*"', seg)
    if len(q) < 4:
        continue
    mn = re.search(r"min = ([-\d.]+)", seg)
    mx = re.search(r"max = ([-\d.]+)", seg)
    parsed.append((q[0][1:-1], q[1][1:-1], q[3][1:-1],
                   float(mn.group(1)) if mn else None,
                   float(mx.group(1)) if mx else None))
chk(len(parsed) == 9, "SAMPLING 不是 9 项（实际 %d）" % len(parsed))
for i, (exp, got) in enumerate(zip(EXPECT, parsed), 1):
    chk(exp[0] == got[0] and exp[1] == got[1] and exp[2] == got[2]
        and exp[3] == got[3] and exp[4] == got[4],
        "第 %d 项参数与官方表不一致: %s" % (i, got))
chk("ParamKind.ENGINE" in SPEC and "ParamKind.UPPER" in SPEC and "ParamKind.LOCKED" in SPEC,
    "三态枚举缺失")

# ── 守恒：真值源 / 引擎 / 主链 / 权限 / 依赖逐字节比 HEAD ──────────────
def head(p):
    try:
        return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                       stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return None


if not os.environ.get("CI"):
    KEEP = [
        "app/src/main/assets/prompt_zh.txt",
        "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
        "app/src/main/java/com/tingben/app/core/Mixer.kt",
        "app/src/main/java/com/tingben/app/core/Prefs.kt",
        "app/src/main/java/com/tingben/app/core/Pipeline.kt",
        "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
        "app/src/main/java/com/tingben/app/core/PlayQueue.kt",
        "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
        "app/src/main/java/com/tingben/app/ProduceService.kt",
        "app/src/main/cpp/tingben_native.cpp",
        "app/src/main/AndroidManifest.xml", "app/build.gradle.kts",
    ]
    for p in KEEP:
        h = head(p)
        chk(h is not None and h == (ROOT / p).read_bytes(), "守恒文件被改动: " + p)
    # HEAD 比工作区落后一轮（R47 的 ParamSpec / AppChrome 尚未进 HEAD），
    # 这两个文件改走语义守恒，不拿旧 HEAD 做逐字节比。
    chrome = read("app/src/main/java/com/tingben/app/AppChrome.kt")
    chk("requiredWidth(240.dp)" in chrome, "R47 抽屉收窄被改坏")
    for label in ["采样种子", "单次音频长度上限", "主·随机度", "主·候选范围", "主·核采样",
                  "重复惩罚", "副·随机度", "副·候选范围", "副·核采样"]:
        chk('"%s"' % label in SPEC, "9 项人话参数名缺: " + label)
else:
    print("CI 环境：HEAD 即本次提交，守恒已在提交前本地核对")

if errs:
    print("\n".join("ERROR " + e for e in errs))
    sys.exit(1)
print("R49 验证 PASS：正向 8 条 / 反向注入 %d 例 / 9 项参数对拍 / 三态枚举 / 守恒" % len(INJECT))
