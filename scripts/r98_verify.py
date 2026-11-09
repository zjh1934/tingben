# -*- coding: utf-8 -*-
"""R98 · 调音台整页重写验证：三模式并列 / 数值完整可见 / 输入互不覆盖 / 旧形态零残留 / 反向注入。

不守旧画法：R47/R92/R93/R97 四个绑死旧 ParamRow / 旧 MixerScreen 形态的脚本已归档。
这里守的是「这次重写到底改了什么」以及「没有把不该动的东西带坏」。
用法：python3 scripts/r98_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": ui_sources.source(APP),
    "draft": APP / "core" / "MixerDraft.kt",
    "prefs": APP / "core" / "Prefs.kt",
    "synth": APP / "core" / "SynthInput.kt",
    "resume": APP / "core" / "TaskResume.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

def load():
    return {k: p.read_text() for k, p in FILES.items()}

def mixer(s):
    i = s.find("private fun MixerScreen(")
    return s[i:] if i >= 0 else ""

POS = [
    ("main", "ModeTabs(mode, installed) { switchMode(it) }"),
    ("main", "ModelStore.Mode.values().forEach { m ->"),
    ("main", "fun switchMode(m: ModelStore.Mode)"),
    ("main", "Prefs.setModelMode(c, m.id)"),
    ("main", "Producer.isBusy()"),
    ("main", "VoiceDesignPane(draft) { draft = it }"),
    ("main", "CustomVoicePane("),
    ("main", "BasePane("),
    ("main", "value = draft.cvStyle"),
    ("main", "value = draft.refText"),
    ("main", "draft.speaker == name"),
    ("draft", "val cvStyle: String = \"\""),
    ("draft", "cvStyle = Prefs.cvStyle(c)"),
    ("draft", "put(\"预设音色念法\", a.cvStyle, d.cvStyle, TIMING_NOW)"),
    ("draft", "Prefs.setCvStyle(c, d.cvStyle)"),
    ("prefs", "fun cvStyle(c: Context): String"),
    ("synth", "Prefs.cvStyle(c).trim()"),
    ("resume", "Prefs.cvStyle(c), VoiceRef.fingerprint(c)"),
    ("main", "private fun ParamLine("),
    ("main", "ParamCatalog.SAMPLING.forEach { spec ->"),
    ("main", "Text(cur, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primary"),
    ("main", ".clickable { buf = cur; edit = true }"),
    ("main", "modifier = Modifier.padding(start = 20.dp, end = 10.dp)"),
    ("main", "fun commitAll()"),
    ("main", "MixerCommit.commit(c, draft, n)"),
    ("main", "trial(\"未保存\", draft, trialText, \"mixer_trial_draft.wav\")"),
    ("main", "trial(\"已保存\", active, savedTrialText, \"mixer_trial_saved.wav\")"),
    ("main", "val txt = src.refText.takeIf { it.isNotBlank() }"),
    ("main", "NativeEngine.ttsSetVoiceRef(h, VoiceRef.pcm(c), txt)"),
]

BAN = [
    ("main", "MixerSection("),
    ("main", "HelpNote("),
    ("main", "ParamRow("),
    ("main", "恢复默认描述"),
    ("main", "采样顺序"),
    ("main", "主采样（5 项）"),
    ("main", "副采样（3 项）"),
    ("main", "恢复本组默认"),
    ("main", "9 项真到模型"),
    ("main", "Modifier.width(72.dp).height(40.dp)"),
    ("main", "试听未提交参数"),
    ("main", "试听已生效参数"),
    ("main", "303404 字节"),
    ("main", "查看差异"),
    ("main", "丢弃全部待提交"),
    ("main", "已生效 v"),
    ("synth", "Prefs.voiceDescription(c).trim()"),
]

def check(f):
    e = []
    for key, lit in POS:
        if lit not in f[key]:
            e.append("正向缺失[%s]: %s" % (key, lit))
    for key, lit in BAN:
        if lit in f[key]:
            e.append("旧形态残留[%s]: %s" % (key, lit))
    vt = f["prefs"].split("fun voiceStamp(c: Context): String = sha(listOf(", 1)[-1].split(").joinToString", 1)[0]
    if "cvStyle" in vt:
        e.append("voiceStamp 掺入 cvStyle，会把所有旧 WAV 误删")
    if "ModelStore.Mode.BASE -> SynthInput.Params(null, null)" not in mixer(f["main"]):
        e.append("base 仍在传文字指令")
    if "r98_verify.py" not in f["ci"]:
        e.append("CI 没挂 r98_verify")
    return e

def run():
    f = load()
    e = check(f)
    if e:
        print("R98 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R98 正向 %d / 零残留 %d 全过" % (len(POS), len(BAN)))
    return 0

def selftest():
    """反向注入：把这次重写逐项改坏，脚本必须响。"""
    f = load()
    cases = [
        ("删三模式并列", "main", "ModeTabs(mode, installed) { switchMode(it) }", ""),
        ("删模式点即切", "main", "Prefs.setModelMode(c, m.id)", ""),
        ("数值框退回裁字形态", "main", "private fun ParamLine(", "private fun ParamRow("),
        ("恢复旧分区控件", "main", "private fun MixerScreen(", "MixerSection(\"采样参数\""),
        ("描述与念法重新共用", "draft", "val cvStyle: String = \"\"", ""),
        ("custom_voice 借回描述", "synth", "Prefs.cvStyle(c).trim()", "Prefs.voiceDescription(c).trim()"),
        ("旧 WAV 误删", "prefs", "voiceDescription(c),\n        ttsKnobs(c).hash()",
         "voiceDescription(c),\n        cvStyle(c),\n        ttsKnobs(c).hash()"),
        ("CI 漏挂", "ci", "r98_verify.py", ""),
    ]
    bad = 0
    for name, key, old, new in cases:
        g = dict(f)
        g[key] = f[key].replace(old, new, 1)
        errs = check(g)
        ok = bool(errs)
        print("  注入 %-18s → %s" % (name, "拦下" if ok else "漏过"))
        if not ok:
            bad += 1
    print("反向注入 %d/%d 拦下" % (len(cases) - bad, len(cases)))
    return 1 if bad else 0

if __name__ == "__main__":
    rc = run()
    if "--selftest" in sys.argv:
        rc |= selftest()
    sys.exit(rc)
