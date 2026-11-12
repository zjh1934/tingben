#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R223 · 默认青年女声音色身份守门。

查证结论：采样参数不决定性别/年龄（只管抽签随机度与长度），
「青年女声」只能由 instruct 身份层给；现有 9 项采样默认已等于
官方 / 社区常用起点，锁死不许当成女声开关乱改。

同时守「旧进度可回退」：sp 里从没写过 voiceDesc → 新默认女声；
用户在调音台点「清空」→ 显式写 "" → 回落 DEFAULT_IDENTITY 中性串、
voiceStamp 回到旧值 → VoiceCache 认领回升级前那套。

用法：python3 scripts/r223_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "prefs": rd(APP / "core/Prefs.kt"),
        "profile": rd(APP / "core/VoiceProfile.kt"),
        "mixer": rd(APP / "core/Mixer.kt"),
        "draft": rd(APP / "core/MixerDraft.kt"),
        "producer": rd(APP / "core/Producer.kt"),
        "clip": rd(APP / "core/ClipSynth.kt"),
        "ui": rd(APP / "MixerUi.kt"),
        "spec": rd(APP / "core/ParamSpec.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

# 现有 9 项默认 = 官方 evaluate 用 max_new_tokens=2048 + checkpoint generate_config
# 默认值（HF T1 评测 / faster-qwen3-tts 教程 / 项目 qwen.h 契约三处一致）
KNOBS = ["const val MAX_NEW_TOKENS = 2048", "const val TEMPERATURE = 0.9f",
         "const val TOP_K = 50", "const val TOP_P = 1.0f",
         "const val REPETITION_PENALTY = 1.05f",
         "const val SUBTALKER_TEMPERATURE = 0.9f",
         "const val SUBTALKER_TOP_K = 50", "const val SUBTALKER_TOP_P = 1.0f",
         "const val SEED = 42L"]
NEUTRAL = "清晰自然的中文普通话叙述声，音区适中，无明显口音"

def check(f):
    e = []
    pr, pf = f["prefs"], f["profile"]
    # ① 默认身份确实是青年女声、且是有声书向而非广告腔
    di = pr.find("const val DEFAULT_VOICE")
    dv = pr[di:di + 320] if di >= 0 else ""
    if not dv: e.append("缺 DEFAULT_VOICE")
    for k in ["青年女声", "20–25", "普通话", "吐字清晰", "适合长篇有声书朗读"]:
        if k not in dv: e.append("默认女声描述缺维度: " + k)
    for k in ["中年", "男声", "儿童"]:
        if k in dv: e.append("默认身份串性别/年龄跑偏: " + k)
    # ② contains 语义：区分「从没设置」与「显式清空」，旧进度可回退
    if "if (s.contains(KEY_VOICE_DESC))" not in pr: e.append("voiceDescription 丢了 contains 判断")
    if "else DEFAULT_VOICE" not in pr: e.append("从没设置过时没给新默认")
    if 's.getString(KEY_VOICE_DESC, "") ?: ""' not in pr:
        e.append("显式清空后没回落到空串")
    if ('const val DEFAULT_IDENTITY = "' + NEUTRAL + '"') not in pf:
        e.append("中性兜底串被改，清空后切不回旧套")
    vi = pr.find("fun voiceStamp(c: Context)")
    vblk = pr[vi:vi + 260] if vi >= 0 else ""
    if "voiceDescription(c)" not in vblk:
        e.append("voiceStamp 没继续用 voiceDescription（换默认后指纹不跟着变会混音）")
    # ③ 采样 9 项默认锁死：不决定女声，不许被当成女声开关乱改
    for k in KNOBS:
        if k not in pr: e.append("采样默认被改（会让旧套归档）: " + k)
    # ④ 所有读取点都走同一个真源
    for name, src in [("Producer", f["producer"]),
                      ("Mixer", f["mixer"]), ("MixerDraft", f["draft"])]:
        if "Prefs.voiceDescription(c)" not in src:
            e.append(name + " 绕过了 voiceDescription 真源")
    # 试听走 MixerDraft.active(c) 间接读，不许自己硬编码身份
    if "MixerDraft.active(c)" not in f["clip"]:
        e.append("试听绕过了 MixerDraft.active 间接真源")
    # ⑤ 调音台给出回退说明（且不触发 r158 长文案判据）
    if "留空 = 旧默认中性声，可切回旧音频" not in f["ui"]:
        e.append("调音台没说清清空=切回旧音频")
    if 'onDraft(draft.copy(voice = ""))' not in f["ui"]:
        e.append("清空入口被摘，旧进度回不去")
    if "青年女声（R223 默认）" not in f["spec"]:
        e.append("参数目录没标默认身份")
    # ⑥ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r223_verify.py --selftest"),
                              ("清单", f["r135"], '"r223_verify.py"'),
                              ("受管根", f["config"], "r223_verify.py")]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "scripts/r223_verify.py"):
                e.append(name + " 漏登记 r223")
        elif needle not in src: e.append(name + " 漏登记 r223")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("默认串被删", "prefs", "const val DEFAULT_VOICE = ", ""),
        ("女声维度被删", "prefs", "青年女声，约 20–25 岁", "中年男声，约 20–25 岁"),
        ("有声书取向被删", "prefs", "适合长篇有声书朗读", ""),
        ("contains 判断被摘", "prefs", "if (s.contains(KEY_VOICE_DESC))", "if (true)"),
        ("新默认没返回", "prefs", "else DEFAULT_VOICE", 'else ""'),
        ("清空回落被摘", "prefs", 's.getString(KEY_VOICE_DESC, "") ?: ""', '"女声"'),
        ("中性兜底被改", "profile", NEUTRAL, "随便一个声音"),
        ("voiceStamp 换源", "prefs",
         "        voiceDescription(c),\n        ttsKnobs(c).hash()",
         '        "固定",\n        ttsKnobs(c).hash()'),
        ("采样默认乱改", "prefs", "const val TEMPERATURE = 0.9f", "const val TEMPERATURE = 0.6f"),
        ("seed 被改", "prefs", "const val SEED = 42L", "const val SEED = 0L"),
        ("Producer 绕真源", "producer", "Prefs.voiceDescription(c)", '"硬编码"'),
        ("试听绕真源", "clip", "MixerDraft.active(c)", '"硬编码"'),
        ("清空入口被摘", "ui", 'onDraft(draft.copy(voice = ""))', ""),
        ("回退说明被摘", "ui", "留空 = 旧默认中性声，可切回旧音频", ""),
        ("目录没标默认", "spec", "青年女声（R223 默认）", "（必填）"),
        ("CI 漏挂", "ci", "python3 scripts/r223_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r223_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        # R242 · Producer 里真源串出现 2 处，只换第一处会恒不红（漏放）；
        # 全部注入改全量替换，纯内存，源文件零写入。
        g = dict(f); g[key] = f[key].replace(needle, repl)
        if check(g): print("  反向注入 %-22s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R223 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R223 默认青年女声 / 采样默认锁死 / 清空可回退旧进度：PASS")
