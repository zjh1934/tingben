#!/usr/bin/env python3
"""R60 · P0 换模型 11 处联动验证（R57 逐项签）。

只做源码级断言 + 反向注入；真编译由 CI 的 assembleDebug 负责。
双页形态由 r54_verify.py 负责，本脚本只管「换个权重不是换个文件名」。
"""
import json, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
PIPE = ROOT / "app/src/main/java/com/tingben/app/core/Pipeline.kt"
PREFS = ROOT / "app/src/main/java/com/tingben/app/core/Prefs.kt"
CHROME = ROOT / "app/src/main/java/com/tingben/app/AppChrome.kt"
MANIFEST = ROOT / "contracts/model-manifest.json"

main = MAIN.read_text(encoding="utf-8")
pipe = PIPE.read_text(encoding="utf-8")
prefs = PREFS.read_text(encoding="utf-8")
chrome = CHROME.read_text(encoding="utf-8")
man = json.loads(MANIFEST.read_text(encoding="utf-8"))
errs = []


def need(c, m):
    if not c:
        errs.append(m)


NEW = "huihui-qwen3-4b-instruct-2507-abliterated-q4_k_m.gguf"
OLD = "mlabonne_Qwen3-4B-abliterated-Q4_K_M.gguf"
MIRROR = "https://hf-mirror.com/kuronekkko/Huihui-Qwen3-4B-Instruct-2507-abliterated-Q4_K_M-GGUF/resolve/main/" + NEW

# ── 1) 11 处联动逐项 ────────────────────────────────────────────────────
llm_slot = next((m for m in man["models"] if m["slot"] == "llm"), None)
need(llm_slot is not None, "① manifest 没有 llm 槽位")
if llm_slot:
    need(llm_slot.get("file") == NEW, "① manifest 文件名没换")
    need("kuronekkko" in llm_slot.get("repo", ""), "① repo 没换")
    need(MIRROR in llm_slot.get("links", []), "① 国内直链没换")
    need("仅非思考" in llm_slot.get("thinking", ""), "① thinking 口径没改")
    need(llm_slot.get("size_bytes") is None and llm_slot.get("sha256") is None,
         "① 未实测的字节数/sha 被写成假值（R57 硬约束）")
    need("不写" in llm_slot.get("verification", "") or "实测" in llm_slot.get("verification", ""),
         "① 没写明待实测口径")
    need(llm_slot.get("legacy", {}).get("file") == OLD, "① 旧权重兼容说明丢了")
need("baseline" in man, "① 没登记 A/B 回退基线")
need(any("Thinking-2507" in x.get("file", "") or "thinking-2507" in x.get("file", "")
         for x in man.get("excluded", [])), "① Thinking-2507 弃用说明被删")

need(MIRROR in main, "② 模型页复制下载地址没换（别复制成 Thinking 版）")
need(OLD not in main.split("// ── ⑤b 模型页")[1][:4000], "② 模型页还留着旧权重名")
need('const val LLM = "%s"' % NEW in pipe, "③ ModelStore.LLM 没换")
need('const val LLM_LEGACY = "%s"' % OLD in pipe, "③ 旧文件名白名单兼容没留")
need('"instruct-2507"' in pipe and '"4b-abliterated"' in pipe, "③ 导入白名单 hint 不齐")

bp = pipe[pipe.index("fun buildPrompt("):pipe.index("fun extractThinking(")]
need('plus(if (thinking)' not in bp, "④ prompt 里还塞 /no_think 软开关")
need('"/no_think"' not in bp and '"/think"' not in bp, "④ 思考指令没删干净")
need("<|im_start|>system" in bp and "<|im_start|>assistant" in bp,
     "⑤ 手写 ChatML 不合规")
need("GGUF 自带 chat_template" in bp, "⑤ 没写明模板按官方对齐")

need("val temp = Prefs.temperature(c)" in pipe, "⑥ 温度仍被 thinkOn 覆盖")
need("if (thinkOn) 0.6f" not in pipe, "⑥ 深度档 0.6 覆盖残留")
need('"m2507/"' in pipe and '"annotated"' in pipe, "⑦ 缓存没并成一套 / 没作废旧权重分桶")
need('if (think) "think"' not in pipe, "⑦ bucket 仍按 think 分两套")
need("fun thinking(c: Context)" in prefs and "fun setThinking(c: Context" in prefs,
     "⑧ KEY_THINKING 兼容层被删（旧 shared_prefs 会异常）")
need("Prefs.setThinking(" not in main, "⑧ 界面还在写思考开关")
need("fun extractThinking(raw: String)" in pipe, "⑨ 思考剥离防御被删")
need("if (thinkOn) {\n" not in pipe, "⑨ 标注循环仍主动写 ThinkingStore")
need("THINK_TRUNCATED" not in pipe, "⑨ 思考截断重试分支残留")
need('NAV_LLM to "语言模型"' in chrome and "Screen.LLM -> LlmSettingsScreen" in main,
     "⑩ 双页没接进抽屉与分发")
need('"MODEL_ID"' in pipe and "ModelStore.llm(c)" in pipe, "⑪ 诊断没写实际权重")
need("object AbGate" in pipe and "AbGate.record(" in pipe and "AbGate.begin()" in pipe,
     "⑪ JSON 通过率四项统计入口没接")
need("AbGate.summary()" in main, "⑪ 诊断摘要没带 A/B 口径")
for k in ["完整 JSON", "三字段齐备", "中文 instruct 有效", "gate 重试"]:
    need(k in pipe, "⑪ A/B 缺口径: " + k)

# ── 2) 旧权重假开关零残留 ────────────────────────────────────────────────
for b in ["thinking=$thinkOn", '"think" to thinkOn', 'SettingSwitchRow("思考过程"',
          'Text("思考过程", fontWeight', "THINK_TAIL_NOT_CLOSED",
          "开（会明显变慢）", "关（推荐）"]:
    need(b not in main, "界面残留旧思考态: " + b)
need("bartowski/mlabonne" not in main, "旧权重下载直链残留")
need("bartowski/mlabonne" not in MANIFEST.read_text(encoding="utf-8"),
     "manifest 残留旧 repo")

# ── 3) 守恒（一行不动的主链） ────────────────────────────────────────────
prompt = (ROOT / "app/src/main/assets/prompt_zh.txt").read_text(encoding="utf-8")
need("---SEPARATOR---" in prompt, "prompt_zh.txt v2 被改")
adoption = (ROOT / "app/src/main/java/com/tingben/app/core/Adoption.kt").read_text(encoding="utf-8")
need("fun gate(" in pipe, "Annotator 逐字 gate 被动")
splitter = (ROOT / "app/src/main/java/com/tingben/app/core/TextSplitter.kt").read_text(encoding="utf-8")
need("object TextSplitter" in splitter, "TextSplitter 被动")
native = (ROOT / "app/src/main/cpp/tingben_native.cpp").read_text(encoding="utf-8")
need("llama_sampler_init_greedy" in native or "top_p(0.9f" in native,
     "native 采样链被改（守恒要求不动）")
need("MixerCommit.commit(" in main, "调音台唯一提交出口被改")
need("frontend" not in man.get("runtime_policy", ""), "runtime_policy 被改")
need("android.permission.INTERNET" not in
     (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8"),
     "Manifest 混进 INTERNET 权限")

# ── 4) 反向注入 ──────────────────────────────────────────────────────────
def baseline(m, p, pr, ch, manifest):
    e = []
    bp = p[p.index("fun buildPrompt("):p.index("fun extractThinking(")]
    if 'plus(if (thinking)' in bp or '"/no_think"' in bp:
        e.append("软开关回潮")
    if 'const val LLM = "%s"' % NEW not in p: e.append("权重常量被改")
    if "val temp = Prefs.temperature(c)" not in p: e.append("温度覆盖回潮")
    if '"m2507/" + (if (direct) "direct" else "annotated")' not in p:
        e.append("缓存代际作废丢了")
    if '"MODEL_ID"' not in p: e.append("MODEL_ID 丢了")
    if "AbGate.record(" not in p: e.append("A/B 统计丢了")
    if MIRROR not in m: e.append("国内直链丢了")
    if 'SettingSwitchRow("思考过程"' in m: e.append("思考开关回潮")
    if "NAV_LLM" not in ch or "Screen.LLM -> LlmSettingsScreen" not in m:
        e.append("双页导航断了")
    if "fun thinking(c: Context)" not in pr: e.append("KEY_THINKING 兼容层被删")
    slot = next((x for x in manifest["models"] if x["slot"] == "llm"), {})
    if slot.get("file") != NEW: e.append("manifest 文件名被改")
    if slot.get("sha256") is not None: e.append("写了未实测的 sha 假值")
    return e


base = baseline(main, pipe, prefs, chrome, man)
need(not base, "基线自身失败: " + str(base))

INJECT = {
    "恢复软开关": lambda m, p, pr, ch, mf: (m, p.replace(
        '            // P0 · 2507 是「仅非思考」独立权重',
        '            .plus(if (thinking) "\\n/think" else "\\n/no_think")\n'
        '            // P0 · 2507 是「仅非思考」独立权重'), pr, ch, mf),
    "换回旧权重": lambda m, p, pr, ch, mf: (m, p.replace(
        'const val LLM = "%s"' % NEW, 'const val LLM = "%s"' % OLD), pr, ch, mf),
    "温度覆盖回潮": lambda m, p, pr, ch, mf: (m, p.replace(
        "val temp = Prefs.temperature(c)", "val temp = if (thinkOn) 0.6f else Prefs.temperature(c)"), pr, ch, mf),
    "缓存两套回潮": lambda m, p, pr, ch, mf: (m, p.replace(
        '"m2507/" + (if (direct) "direct" else "annotated")',
        '(if (direct) "direct" else if (think) "think" else "fast")'), pr, ch, mf),
    "删 MODEL_ID": lambda m, p, pr, ch, mf: (m, p.replace('"MODEL_ID"', '"MODEL_ID_OFF"'), pr, ch, mf),
    "删 A/B 统计": lambda m, p, pr, ch, mf: (m, p.replace("AbGate.record(", "AbGate.recordDISABLED("), pr, ch, mf),
    "恢复思考开关": lambda m, p, pr, ch, mf: (m.replace(
        'SettingInfoRow("标注方式", "上层", "想用就用",',
        'SettingSwitchRow("思考过程", false) { }\n            '
        'SettingInfoRow("标注方式", "上层", "想用就用",'), p, pr, ch, mf),
    "删抽屉独立项": lambda m, p, pr, ch, mf: (m.replace(
        "Screen.LLM -> LlmSettingsScreen", "Screen.LLM -> SettingsScreen"), p, pr, ch, mf),
    "manifest 写假 sha": lambda m, p, pr, ch, mf: (m, p, pr, ch,
        {**mf, "models": [{**x, "sha256": "deadbeef" * 8} if x["slot"] == "llm" else x
                          for x in mf["models"]]}),
    "旧直链回潮": lambda m, p, pr, ch, mf: (m.replace(MIRROR,
        "https://hf-mirror.com/bartowski/mlabonne_Qwen3-4B-abliterated-GGUF/resolve/main/" + OLD),
        p, pr, ch, mf),
}
for name, fn in INJECT.items():
    args = fn(main, pipe, prefs, chrome, man)
    if args == (main, pipe, prefs, chrome, man):
        errs.append("反向注入锚点失效: " + name)
        continue
    got = baseline(*args)
    need(bool(got), "反向注入没拦下: " + name)
    print("  反向注入 %-16s %s" % (name, "拦下" if got else "!! 没拦住 !!"))

if errs:
    print("R60 换模型联动验证 FAIL")
    for e in errs:
        print("  - " + e)
    sys.exit(1)
print("R60 换模型联动验证 PASS：11 处逐项 / 旧形态零残留 / 守恒 / 反向注入 %d 例"
      % len(INJECT))
