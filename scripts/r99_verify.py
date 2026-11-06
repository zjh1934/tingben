# -*- coding: utf-8 -*-
"""R99 · 调音台收口验证：折叠整套退场 / 预设音色加一行自定义 / 待提交话术清零 /
试听建议归并 / 主链守恒。

只查字符串与结构，不假装证明真机手感。
用法：python3 scripts/r99_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "draft": APP / "core" / "MixerDraft.kt",
    "prefs": APP / "core" / "Prefs.kt",
    "qwen": ROOT / "app/src/main/cpp/qwen.h",
    "ci": ROOT / ".github/workflows/ci.yml",
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def mixer(s):
    i = s.find("private fun MixerScreen(")
    return s[i:] if i >= 0 else ""


# ① 正向：本轮四件事必须真的在源码里
POS = [
    # 去折叠：采样与试听常驻
    ("main", 'Text("采样参数 · 9 项"'),
    ("main", 'Text("试听", fontSize = 12.sp'),
    ("main", "ParamCatalog.SAMPLING.forEach { spec ->"),
    ("main", 'Text("全部恢复默认"'),
    # 自定义单独一行
    ("main", 'Text("自定义", fontSize = 12.sp'),
    ("main", "value = draft.cvStyle"),
    ("main", "叠加在所选音色上"),
    # 待提交话术清零 + 一枚保存
    ("main", 'Text("保存", fontSize = 11.sp)'),
    ("main", 'Text("改动还没保存"'),
    ("main", 'Text("离开会丢掉这次改动。"'),
    ("draft", 'const val TIMING_NOW = "保存后新合成起生效'),
    # 试听建议归并：可改文本 / 两态一行 / 结果只留已播放 / 不必先保存
    ("main", "value = trialText, onValueChange = { trialText = it }"),
    ("main", 'trial("草稿", draft, "mixer_trial_draft.wav")'),
    ("main", 'trial("已生效", active, "mixer_trial_active.wav")'),
    ("main", '"已播放"'),
    # 守恒
    ("main", "MixerCommit.commit(c, draft, n)"),
    ("main", "ModeTabs(mode, installed) { switchMode(it) }"),
    ("qwen", "instruct is the style instruction string; required for"),
    ("ci", "r99_verify.py"),
]

# ② 零残留：只在 MixerScreen 段内查，别误伤别页合法文案
BAN_MIXER = [
    "MixerFold(", "samplingOpen", "trialOpen", "showDiff",
    "待提交", "提交变更", "查看差异", "丢弃待提交", "有未提交变更",
    '"收起"', '"展开 ›"', "差异弹层", "9 项真到模型",
    "试听未提交参数", "试听已生效参数", "已自动播放", "字节，点右边试听",
]

# ③ 全局零残留：这几句在哪个文件都不该出现
BAN_ALL = ["提交 ${changes.size} 项", "念法 / 情绪（可留空）", "默认折叠 ──"]


def check(f):
    e = []
    for key, lit in POS:
        if lit not in f[key]:
            e.append("正向缺失[%s]: %s" % (key, lit))
    m = mixer(f["main"])
    for lit in BAN_MIXER:
        if lit in m:
            e.append("旧形态残留[mixer]: %s" % lit)
    for lit in BAN_ALL:
        for key in ("main", "draft"):
            if lit in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, lit))
    # 9 项采样不许被折叠成唯一入口；ParamLine 仍常驻
    if m.count("ParamLine(spec = spec") != 1:
        e.append("9 项采样没按 ParamCatalog 单一目录常驻")
    # 自定义念法仍走唯一提交，不能新建第二份生效态
    d = f["draft"]
    if "put(\"预设音色念法\", a.cvStyle, d.cvStyle, TIMING_NOW)" not in d:
        e.append("自定义念法没进 diff")
    if "Prefs.setCvStyle(c, d.cvStyle)" not in d:
        e.append("自定义念法没走唯一出口")
    vt = f["prefs"].split("fun voiceStamp(c: Context): String = sha(listOf(", 1)[-1].split(").joinToString", 1)[0]
    if "cvStyle" in vt:
        e.append("voiceStamp 掺入 cvStyle，旧 WAV 会被误删")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cases = {
        "把折叠放回来": lambda x: x.replace('Text("采样参数 · 9 项"',
                                       'MixerFold("采样参数", "9 项", samplingOpen, {}) {'),
        "把待提交话术放回来": lambda x: x.replace('Text("保存", fontSize = 11.sp)',
                                          'Text("提交变更", fontSize = 11.sp)'),
        "删掉自定义行": lambda x: x.replace('Text("自定义", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)',
                                     'Text("念法 / 情绪（可留空）")'),
        "删掉试听常驻": lambda x: x.replace('Text("试听", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text)',
                                     'MixerFold("试听", "", trialOpen, {}) {'),
        "删掉唯一出口": lambda x: x.replace("MixerCommit.commit(c, draft, n)", "0L"),
        "删掉自定义落盘": lambda x: x.replace("Prefs.setCvStyle(c, d.cvStyle)", "Unit"),
    }
    bad = 0
    for name, fn in cases.items():
        g = dict(f)
        if name == "删掉自定义落盘":
            g["draft"] = fn(f["draft"])
        elif name == "删掉唯一出口":
            g["main"] = fn(f["main"])
        else:
            g["main"] = fn(f["main"])
        hit = check(g)
        print("  %-22s %s" % (name, "拦下" if hit else "没拦下（漏网）"))
        if not hit:
            bad += 1
    return bad


def main():
    if "--selftest" in sys.argv:
        bad = selftest()
        print("R99 反向注入 %d 例，漏网 %d" % (6, bad))
        return 1 if bad else 0
    e = check(load())
    if e:
        print("R99 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R99 正向 %d / 零残留 %d 全过" % (len(POS), len(BAN_MIXER) + len(BAN_ALL)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
