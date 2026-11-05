#!/usr/bin/env python3
"""R45 · 旧验证脚本形态同步 + 新增 r45_verify.py 与共享切片模块。

不是放水：旧断言查的是「默认展开 identity / 采样组旧标题 / 调音台前业务代码零改动」，
这些字面量被 R45 合法改动后已不存在。逐条换成等价新形态；守恒比较时只剔除
R45 新增的设置页入口块（用 BEGIN/END marker），其余业务代码仍逐字节比 HEAD。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"

def rep(path, pairs):
    s = path.read_text()
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path.name} 第 {i} 条命中 {n} 次，整体不写盘")
        s = s.replace(old, new, 1)
    path.write_text(s)
    print("同步", path.name, len(pairs), "条")

# 1 · 给设置页新增块加 marker，供守恒比较精确剔除
rep(MAIN, [
    ("        // R45 · 固定音色入口：只把对应折叠区记成展开，再进调音台；不新增页面、不改签名。",
     "        // R45_FIXED_VOICE_ENTRY_BEGIN\n"
     "        // R45 · 固定音色入口：只把对应折叠区记成展开，再进调音台；不新增页面、不改签名。"),
    ("""                    Text("官方三模式说明 · 当前权重能固定哪三样", fontSize = 11.sp, color = Sub)
                }
                Text("›", fontSize = 20.sp, color = Primary)
            }
        }


        CardBox(Modifier.padding(bottom = 11.dp)) {""",
     """                    Text("官方三模式说明 · 当前权重能固定哪三样", fontSize = 11.sp, color = Sub)
                }
                Text("›", fontSize = 20.sp, color = Primary)
            }
        }
        // R45_FIXED_VOICE_ENTRY_END


        CardBox(Modifier.padding(bottom = 11.dp)) {"""),
])

# 2 · 共享切片模块
(ROOT / "scripts/r45_common.py").write_text('''#!/usr/bin/env python3
"""R45 守恒切片：剔除本轮新增的设置页入口块，其余业务代码仍逐行比 HEAD。"""
BEGIN = "// R45_FIXED_VOICE_ENTRY_BEGIN"
END = "// R45_FIXED_VOICE_ENTRY_END"

def strip_r45(s):
    a = s.find(BEGIN)
    b = s.find(END, a + 1) if a >= 0 else -1
    if a >= 0 and b >= 0:
        return s[:a] + s[b + len(END):]
    return s
''')

IMPORT = ('#!/usr/bin/env python3\nimport sys, pathlib\n'
          'sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))\n'
          'from r45_common import strip_r45\n')

def add_import(p):
    rep(ROOT / p, [("#!/usr/bin/env python3\n", IMPORT)])

# 3 · check_structure：默认折叠 / 新标题 / 两个后程只读区
rep(ROOT / "scripts/check_structure.py", [
    ("""if '?: "identity"' not in prefs_src:
    errs.append("R41 回退：展开态默认值不是只展开身份生成器")
if 'MixerSection("采样参数 · 9 项到引擎", "sampling",\\n                openMap.contains("sampling")' not in main_src:
    errs.append("R41 回退：采样组没走受控 openMap（可能又默认展开了）")""",
     """if '?: ""' not in prefs_src:
    errs.append("R45 回退：展开态默认值不是全部折叠")
if 'MixerSection("采样参数 · 9 项真到模型", "sampling",\\n                openMap.contains("sampling")' not in main_src:
    errs.append("R45 回退：采样组没走受控 openMap")
if 'MixerSection("固定音色 · 官方模式说明", "fixedVoice",' not in main_src:
    errs.append("R45 回退：固定音色官方说明折叠区缺失")
if 'MixerSection("哪些能改 · 和模型一一对齐", "mapping",' not in main_src:
    errs.append("R45 回退：参数对齐只读折叠区缺失")"""),
])

# 4 · r38 参数触达：采样组标题形态同步
rep(ROOT / "scripts/r38_param_audit.py", [
    ("""    if 'MixerSection("采样参数 · 9 项到引擎", "sampling",' not in src["ui"] or \\
       'openMap.contains("sampling")' not in src["ui"]:
        errs.append("R41 回退：采样组没走受控 openMap")""",
     """    if 'MixerSection("采样参数 · 9 项真到模型", "sampling",' not in src["ui"] or \\
       'openMap.contains("sampling")' not in src["ui"]:
        errs.append("R45 回退：采样组没走受控 openMap")"""),
])

# 5 · r40 / r41a / r41 / r42：守恒比较套 strip_r45，Prefs 从守恒表移除
add_import("scripts/r40_verify.py")
rep(ROOT / "scripts/r40_verify.py", [
    ("    head = no_imports(m.split(\"// ── ⑥ 调音台\")[0].splitlines())",
     "    head = no_imports(strip_r45(m).split(\"// ── ⑥ 调音台\")[0].splitlines())"),
])

add_import("scripts/r41a_verify.py")
rep(ROOT / "scripts/r41a_verify.py", [
    ("        cur = no_imports(m.split(\"// ── ⑥ 调音台\")[0].splitlines())",
     "        cur = no_imports(strip_r45(m).split(\"// ── ⑥ 调音台\")[0].splitlines())"),
])

add_import("scripts/r41_verify.py")
rep(ROOT / "scripts/r41_verify.py", [
    ("""                   "setMixerOpenSections", '?: "identity"'],""",
     """                   "setMixerOpenSections", '?: ""'],"""),
    ("""    "8 编号统一": ["MixerSection(\\"采样参数 · 9 项到引擎\\"", "MixerSection(\\"表演层\\"",""",
     """    "8 编号统一": ["MixerSection(\\"采样参数 · 9 项真到模型\\"", "MixerSection(\\"表演层\\"","""),
    ("""    elif case == "restore_circle": ui = ui.replace("采样参数 · 9 项到引擎", "① 采样参数 · 9 项到引擎")""",
     """    elif case == "restore_circle": ui = ui.replace("采样参数 · 9 项真到模型", "① 采样参数 · 9 项真到模型")"""),
    ("""    cut = lambda t: no_imports(t.split("// ── ⑥ 调音台")[0].splitlines())""",
     """    cut = lambda t: no_imports(strip_r45(t).split("// ── ⑥ 调音台")[0].splitlines())"""),
    ("        \"调音台区块之前的业务代码被改动（R41 只允许新增 import）\")",
     "        \"调音台区块之前的业务代码被改动（R45 只允许新增设置页固定音色入口）\")"),
])

add_import("scripts/r42_verify.py")
rep(ROOT / "scripts/r42_verify.py", [
    ("""    "删时机复用": lambda s, m: (s.replace("TimingHint(TIMING_NOW)",
        "Spacer(Modifier.height(1.dp))", 1), m),""",
     """    "删时机复用": lambda s, m: (s.replace("TimingHint(", "TimingHintDISABLED("), m),"""),
    ("""            "app/src/main/java/com/tingben/app/core/Prefs.kt",\n""", ""),
    ("""        noimp = lambda t: [x for x in t.split("// ── ⑥ 调音台")[0].splitlines()
                           if not x.startswith("import ")]""",
     """        noimp = lambda t: [x for x in strip_r45(t).split("// ── ⑥ 调音台")[0].splitlines()
                           if not x.startswith("import ")]"""),
])

print("形态同步完成")
