#!/usr/bin/env python3
"""R36 · 调音台重设计验证：正向符号 / 守恒 / 反向注入 / 预设 JSON 往返对拍。

按长期记忆 playbook：给自检补规则必须做反向验证（注入违规必须 FAIL，删掉必须 OK），
且断言要匹配定义/调用形态，不能只查裸字面量。
只做静态与逻辑对拍，不替代 CI 真编译。
"""
import pathlib, re, sys, json, shutil, tempfile, subprocess, os

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/tingben/app"
main = (SRC / "MainActivity.kt").read_text(encoding="utf-8")
mixer = (SRC / "core/Mixer.kt").read_text(encoding="utf-8")
presets = (SRC / "core/MixerPresets.kt").read_text(encoding="utf-8")
casts = (SRC / "core/CastPresets.kt").read_text(encoding="utf-8")
prefs = (SRC / "core/Prefs.kt").read_text(encoding="utf-8")
all_src = main + mixer + presets + casts + prefs

def check(cond, msg, errs):
    if not cond: errs.append(msg)

def run_asserts(text, errs):
    # 正向：折叠分组必须真有定义与状态保持，不能只是标题文字
    check(re.search(r"private fun MixerSection\(", text) is not None,
          "缺 MixerSection 定义形态", errs)
    check("rememberSaveable(key)" in text, "折叠状态没用 rememberSaveable", errs)
    # 预设 CRUD 必须有真实调用点
    for call in ["MixerPresetStore.save(c, p)", "MixerPresetStore.delete(c, p.id)",
                 "MixerPresetStore.list(c)", "MixerPresetStore.find(c, bPresetId)"]:
        check(call in text, "缺预设调用点 " + call, errs)
    # 12 维 / 9 采样 / 5 表演 / 角色 / 日志，逐项核对「能控的通通都要控」
    check("VoiceDimension.entries.forEach" in text, "12 维不是逐维可编辑", errs)
    for s in ["seed", "max_new_tokens（单次音频上限）", "主 temperature", "主 top_k",
              "主 top_p", "repetition_penalty", "副 temperature", "副 top_k", "副 top_p"]:
        check('SettingOption("' + s + '"' in text, "采样缺项 " + s, errs)
    for s in ["朗读档位", "语速倾向", "情绪强度", "段间静音", "失败重做"]:
        check('SettingOption("' + s + '"' in text, "表演层缺项 " + s, errs)
    for s in ["MIXER_PERF", "MIXER_DIM", "MIXER_CAST",
              "MIXER_AB", "当前生效配置", "A/B 试听", "12 维",
              "恢复上游默认值", "复制进诊断日志", "预设库", "角色预设"]:
        check(s in text, "缺 R36 符号 " + s, errs)
    for call in ["CastPresets.seedIfEmpty(c)", "CastPresets.add(c, newCastName, newCastGrid)",
                 "CastPresets.applyGrid(c, gridTargetId, p.id)", "VoiceCast.setOverride(c, editCastId"]:
        check(call in text, "缺角色预设调用 " + call, errs)
    check("Mixer.coverage()" in text, "缺参数覆盖核对表", errs)
    # 作用域：制作页新任务读长期默认，续跑仍用书签里的 config
    check("t.config else Prefs.defaultPerf(c)" in text, "新任务没读默认表演层", errs)
    check("t?.config ?: ProduceConfig()" in text, "续跑被默认表演层污染", errs)
    # 守恒：导航骨架、单一真值源
    check("Screen.MIXER -> MixerScreen" in text, "调音台没接进导航", errs)
    check("onMixer = { screen = Screen.MIXER }" in text, "设置页入口丢了", errs)
    check("声音采样（进阶）" not in text, "设置页又画了第二遍 9 项采样", errs)
    check("putKnobs(" not in text, "设置页还留着第二套写入口", errs)
    check("var knobs" not in all_src, "出现第二份 TtsKnobs 真值源", errs)
    # A/B 不许写回长期默认
    ab = text[text.find("fun synth("):text.find("fun play(")]
    check("Prefs.setTtsKnobs" not in ab, "A/B 试听污染了长期默认", errs)

errs = []
run_asserts(main, errs)
# Mixer.kt 数据层
for s in ["val options: List<String>", "fun coverage()", "fun logPreset(", "Triple<String, String, String>",
          "MIXER_PRESET", "MIXER_PROFILE"]:
    check(s in mixer, "Mixer.kt 缺 " + s, errs)
for s in ["PRESET_SCHEMA", "fun safeName(", "fun builtins()", "fun save(c: Context, p: MixerPreset)",
          "fun delete(c: Context, id: String)", "fun exists(", ".part"]:
    check(s in presets, "MixerPresets.kt 缺 " + s, errs)
for s in ["fun seedIfEmpty(", "setCastSeeded", "BUILTIN", "descriptionOf("]:
    check(s in casts, "CastPresets.kt 缺 " + s, errs)
for s in ["KEY_DEFAULT_PERF", "KEY_CAST_SEEDED", "fun defaultPerf(", "fun setDefaultPerf("]:
    check(s in prefs, "Prefs.kt 缺 " + s, errs)
if errs:
    print("正向 FAIL:"); print("\n".join("  " + e for e in errs)); sys.exit(1)

# ── 反向注入：每类违规必须真的被拦下 ──
INJECT = [
    ("删折叠定义", lambda t: t.replace("private fun MixerSection(", "private fun GoneSection(")),
    ("删状态保持", lambda t: t.replace("rememberSaveable(key)", "remember(key)")),
    ("删预设保存", lambda t: t.replace("MixerPresetStore.save(c, p)", "true")),
    ("恢复第二套采样", lambda t: t.replace("音频模型 · 全部参数", "声音采样（进阶）")),
    ("删 max_new_tokens", lambda t: t.replace('SettingOption("max_new_tokens（单次音频上限）"',
                                              'SettingOption("gone"')),
    ("删角色 seed", lambda t: t.replace("CastPresets.seedIfEmpty(c)", "false")),
    ("A/B 写回默认", lambda t: t.replace('abText, instruct, null, "chinese"',
                                          'Prefs.setTtsKnobs(c, k); abText, instruct, null, "chinese"')),
]
script = pathlib.Path(__file__).resolve()
tmp = pathlib.Path(tempfile.mkdtemp(prefix="r36_"))
rev = 0
if os.environ.get("R36_NO_REVERSE") == "1":
    print("R36 静态自检通过（子进程，跳过反向注入）")
    sys.exit(0)
for name, mutate in INJECT:
    d = tmp / name.replace(" ", "_")
    (d / "app/src/main/java/com/tingben/app").mkdir(parents=True)
    (d / "app/src/main/java/com/tingben/app/core").mkdir(parents=True)
    for rel, content in [("MainActivity.kt", main), ("core/Mixer.kt", mixer),
                         ("core/MixerPresets.kt", presets), ("core/CastPresets.kt", casts),
                         ("core/Prefs.kt", prefs)]:
        (d / "app/src/main/java/com/tingben/app" / rel).write_text(
            mutate(content) if rel == "MainActivity.kt" else content, encoding="utf-8")
    (d / "scripts").mkdir()
    shutil.copy(script, d / "scripts/r36_verify.py")
    env = dict(os.environ); env["R36_NO_REVERSE"] = "1"
    r = subprocess.run([sys.executable, str(d / "scripts/r36_verify.py")],
                       capture_output=True, text=True, cwd=d, env=env, timeout=30)
    ok = r.returncode != 0
    print("反向注入 %-16s" % name, "被拦下 ✓" if ok else "漏拦 ✗")
    rev += 1 if ok else 0
shutil.rmtree(tmp, ignore_errors=True)
if rev != len(INJECT):
    sys.exit("反向验证 %d/%d，断言有假绿" % (rev, len(INJECT)))

# ── 预设 JSON 往返对拍（Python 模拟 Kotlin 逻辑，不是 Kotlin 编译） ──
def safe_name(raw):
    s = re.sub(r'[\\/:*?"|]+', "", raw.strip())
    s = re.sub(r"\s+", "", s).strip("._")
    return s or "untitled"

# MixerPresetStore.save 先 safeName；MixerPreset.fromJson 缺字段一律回 TtsDefaults / ProduceConfig 默认。
# 对拍必须模拟这两步，否则「坏数据宽容」「同名覆盖」用例会拿未清洗的初值自己否掉自己。
KNOB_DEF = dict(seed=42, maxNewTokens=2048, temperature=0.9, topK=50, topP=1.0,
                repetitionPenalty=1.05, subTemperature=0.9, subTopK=50, subTopP=1.0)
PERF_DEF = dict(schema=1)

def roundtrip(p):
    saved = dict(p)
    saved["name"] = safe_name(saved["name"])          # 模拟 save()
    o = json.loads(json.dumps(saved, ensure_ascii=False))
    o["knobs"] = {**KNOB_DEF, **o["knobs"]}           # 模拟 fromJson 的 optXxx(name, default)
    o["perf"] = {**PERF_DEF, **o["perf"]}
    assert o["name"] == safe_name(o["name"]), "名字清洗不一致"
    assert set(o["knobs"]) == {"seed", "maxNewTokens", "temperature", "topK", "topP",
                               "repetitionPenalty", "subTemperature", "subTopK", "subTopP"}, "9 项不齐"
    assert o["perf"]["schema"] == 1, "表演层 schema 丢失"
    assert isinstance(o["fields"], dict), "12 维字段丢失"
    return o

CASES = [
    ("正常 9 项 + 3 维", {"name": " 我的预设 ", "knobs": dict(seed=42, maxNewTokens=2048,
        temperature=0.6, topK=50, topP=1.0, repetitionPenalty=1.05,
        subTemperature=0.6, subTopK=50, subTopP=1.0),
        "perf": dict(schema=1, style="DRAMA", pace="SLOW", intensity="FULL", gapMs=600, gateRetries=2),
        "fields": {"GENDER": "女声", "PITCH": "中高", "TIMBRE": "温润"}}),
    ("名字含非法字符", {"name": "A/B:C*?|", "knobs": dict(seed=7, maxNewTokens=1024,
        temperature=0.0, topK=0, topP=0.8, repetitionPenalty=1.2,
        subTemperature=0.0, subTopK=0, subTopP=0.8), "perf": dict(schema=1),
        "fields": {}}),
    ("空名兜底", {"name": "  ", "knobs": dict(seed=-1, maxNewTokens=3072,
        temperature=1.1, topK=20, topP=0.95, repetitionPenalty=1.0,
        subTemperature=1.1, subTopK=20, subTopP=0.95), "perf": dict(schema=1),
        "fields": {"PERSONA": "儒雅"}}),
    ("坏 perf 宽容", {"name": "旧数据", "knobs": dict(seed=42), "perf": {}, "fields": {}}),
    ("同名覆盖", {"name": "我的预设", "knobs": dict(seed=1234), "perf": dict(schema=1), "fields": {}}),
    ("未知枚举", {"name": "迁移", "knobs": dict(seed=42),
        "perf": dict(schema=1, style="NO_SUCH_STYLE"), "fields": {}}),
]
pairs = 0
seen = {}
for name, p in CASES:
    o = roundtrip(p)
    seen[o["name"]] = o  # 同名覆盖：后者替换前者
    pairs += 1
assert "我的预设" in seen and seen["我的预设"]["knobs"]["seed"] == 1234, "同名覆盖没生效"
assert safe_name("A/B:C*?|") == "ABC", "非法字符清洗不对"
print("预设往返对拍: %d 组一致 / 0 不一致（同名覆盖 1 例）" % pairs)
print("R36 静态自检通过：正向断言全过、反向注入 %d/%d、对拍 %d 组" %
      (rev, len(INJECT), pairs))
