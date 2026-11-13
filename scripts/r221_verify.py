#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R221 · 「② 怎么念」真砍 + 进度不丢守门。

真砍 = 不只删 UI，写死三句枚举预设彻底不再进 instruct；
保留 extraInstruct（自填附加语气）与 gapMs/gateRetries（怎么跑）。
audioStamp() 公式故意不动：音色指纹不变，旧套分段不归档、已做进度不丢。

用法：python3 scripts/r221_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
CORE = APP / "core"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "produce": rd(APP / "ProduceUi.kt") + "\n" + rd(APP / "SettingsUi.kt"),
        "cfg": rd(CORE / "ProduceConfig.kt"),
        "draft": rd(CORE / "MixerDraft.kt"),
        "presets": rd(CORE / "MixerPresets.kt"),
        "mixer": rd(CORE / "Mixer.kt"),
        "spec": rd(CORE / "ParamSpec.kt"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

BAN = ['ExpandGroup("② 怎么念"', 'OptionRow("朗读档位"',
       'OptionRow("语速倾向"', 'OptionRow("情绪强度"']

def check(f):
    e = []
    pr, cfg, dr, ps, mx = f["produce"], f["cfg"], f["draft"], f["presets"], f["mixer"]
    # ① 制作设置页：只剩「做什么 + 怎么跑」
    for k in ['ExpandGroup("这本书", "")', 'SettingsSection("制作设置", "合成", collapsible = false)',
              'OptionRow("段间静音"', 'OptionRow("分段失败重做"']:
        if k not in pr: e.append("制作设置页缺: " + k[:52])
    for k in BAN:
        if k in pr: e.append("三档 UI 回潮: " + k[:44])
    # ② 底层：风格层只认自填附加语气
    if "fun styleInstruct(): String = extraInstruct.trim()" not in cfg:
        e.append("styleInstruct 又拼回写死预设")
    if "style.instruct" in cfg or "pace.instruct" in cfg or "intensity.instruct" in cfg:
        e.append("枚举 instruct 又被拼进 prompt")
    if 'fun summary(): String = "段间静音' not in cfg:
        e.append("summary 又列三档 label")
    # ③ 关键：音色缓存指纹公式不许动 —— 动了旧套就归档、进度归零
    if "style.name, pace.name, intensity.name, extraInstruct," not in cfg:
        e.append("audioStamp 公式被改：已做好的段会被归档、进度归零")
    if "TextSplitter.SPLIT_EPOCH.toString()" not in cfg:
        e.append("audioStamp 丢了切分代际")
    # ④ 其余四处零残留
    for name, src in [("调音台 diff", dr), ("内置预设", ps)]:
        for k in ['put("朗读档位"', "ReadingStyle.DRAMA", "Intensity.FULL"]:
            if k in src: e.append(name + " 回潮: " + k[:44])
    if 'cfg.summary().substringBefore(" · 段间")' in mx:
        e.append("调音台快照又读三档 summary")
    if "三档写死预设已停用" not in f["spec"]:
        e.append("参数目录没同步停用说明")
    # ⑤ 登记
    if "python3 scripts/r221_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r221")
    if '"r221_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r221")
    from r45_common import scope_indexed
    if not scope_indexed(f["config"], "scripts/r221_verify.py"):
        e.append("新守门没登记进受管根")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("怎么念整块回潮", "produce", 'SettingsSection("制作设置", "合成", collapsible = false)',
         'ExpandGroup("② 怎么念"'),
        ("朗读档位 UI 回潮", "produce", 'ExpandGroup("这本书", "")',
         'OptionRow("朗读档位"'),
        ("怎么跑被摘", "produce", 'SettingsSection("制作设置", "合成", collapsible = false)', ""),
        ("styleInstruct 又拼预设", "cfg",
         "fun styleInstruct(): String = extraInstruct.trim()",
         "fun styleInstruct(): String = style.instruct"),
        ("summary 又列三档", "cfg", 'fun summary(): String = "段间静音',
         'fun summary(): String = "${style.label}'),
        ("audioStamp 动了进度会丢", "cfg",
         "style.name, pace.name, intensity.name, extraInstruct,", ""),
        ("切分代际被摘", "cfg", "TextSplitter.SPLIT_EPOCH.toString()", ""),
        ("diff 三档回潮", "draft", 'put("段间静音"', 'put("朗读档位"'),
        ("戏剧预设又塞枚举", "presets", "ProduceConfig(), true),\n        MixerPreset(\"builtin_exact\"",
         "ProduceConfig(style = ReadingStyle.DRAMA), true),\n        MixerPreset(\"builtin_exact\""),
        ("快照又读三档", "mixer", "无附加语气", 'cfg.summary().substringBefore(" · 段间")'),
        ("目录说明没同步", "spec", "三档写死预设已停用", ""),
        ("CI 漏挂", "ci", "python3 scripts/r221_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r221_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl, 1)
        if check(g): print("  反向注入 %-24s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R221 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R221 怎么念真砍 / 三档零回潮 / 音色指纹不变（进度不丢）：PASS")
