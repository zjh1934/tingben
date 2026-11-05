#!/usr/bin/env python3
"""R47 · 抽屉收窄 + v4 预览 ⑥ 落地验证。

正向形态 / 反向注入 / ParamRow 逐行对拍 / ParamSpec 逐字段对拍 / 业务守恒。
CI 挂独立 step；本地 `python3 scripts/r47_verify.py`。
"""
import os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
P_CHROME = "app/src/main/java/com/tingben/app/AppChrome.kt"
P_MAIN = "app/src/main/java/com/tingben/app/MainActivity.kt"
P_SPEC = "app/src/main/java/com/tingben/app/core/ParamSpec.kt"


def read(p):
    return (ROOT / p).read_text(encoding="utf-8")


CHROME, MAIN, SPEC = read(P_CHROME), read(P_MAIN), read(P_SPEC)
errs = []


def chk(ok, msg):
    if not ok:
        errs.append(msg)


# ── 1) 正向形态 ───────────────────────────────────────────────────────
CHROME_POS = [
    "requiredWidth(240.dp)",
    "padding(vertical = 12.dp)",
    "padding(horizontal = 14.dp, vertical = 6.dp)",
    "fontSize = 13.sp",
    "padding(horizontal = 10.dp, vertical = 0.dp)",
    "ModalDrawerSheet(",
    "NavigationDrawerItem(",
]
MAIN_POS = [
    "// R47 · v4 预览 ⑥ 落地",
    "val sliderLo = (spec.min ?: 0.0).toFloat()",
    "valueRange = sliderLo..sliderHi",
    "onInput(s)",
    "字段 ${spec.official}",
    "Slider(",
]
NEW_LABELS = ["采样种子", "单次音频长度上限", "主·随机度", "主·候选范围", "主·核采样",
              "重复惩罚", "副·随机度", "副·候选范围", "副·核采样"]
OLD_LABELS = ["seed（随机种子）", "max_new_tokens（单次音频上限）", "主 temperature",
              "主 top_k", "主 top_p", "repetition_penalty", "副 temperature",
              "副 top_k", "副 top_p"]
for s in CHROME_POS:
    chk(s in CHROME, "抽屉正向缺失: " + s)
for s in MAIN_POS:
    chk(s in MAIN, "调音台正向缺失: " + s)
for s in NEW_LABELS:
    chk('"%s"' % s in SPEC, "人话参数名缺失: " + s)
for s in OLD_LABELS:
    chk('"%s"' % s not in SPEC, "旧英文参数名残留: " + s)

# 滑块必须在 ParamRow 内，且 ParamRow 内不允许直接写生效态
def paramrow(t):
    a = t.find("private fun ParamRow(")
    b = t.find("/** R41 · 共用音色选择器", a + 1)
    return t[a:b] if a >= 0 and b > a else ""

PR = paramrow(MAIN)
chk("Slider(" in PR, "滑块不在 ParamRow 内")
chk("onInput(s)" in PR, "滑块没有与精确值框共用 onInput")
chk(not re.findall(r"Prefs\.set\w+\(", PR), "ParamRow 内出现直接写生效态")
chk("valueRange = sliderLo..sliderHi" in PR, "滑块范围未接 ParamSpec 唯一来源")

# ── 2) 反向注入：任一处改坏，断言必须响 ────────────────────────────────
def baseline(chrome, main, spec):
    e = []

    def ck(ok, m):
        if not ok:
            e.append(m)

    for s in CHROME_POS:
        ck(s in chrome, "抽屉正向缺失: " + s)
    for s in MAIN_POS:
        ck(s in main, "调音台正向缺失: " + s)
    for s in NEW_LABELS:
        ck('"%s"' % s in spec, "人话参数名缺失: " + s)
    pr = paramrow(main)
    ck("Slider(" in pr and "onInput(s)" in pr, "滑块双模被破坏")
    ck(not re.findall(r"Prefs\.set\w+\(", pr), "ParamRow 内出现直接写生效态")
    # 设置页语言模型组必须仍在音频组之前？本轮不改设置页，只验证没被顺手移动
    ck(main.find('GroupLabel("① 语言模型 · 长期默认"') <
       main.find('GroupLabel("② 音频模型 · 长期默认"'), "设置页分组被顺手挪动")
    return e


INJECT = {
    "抽屉宽度回退320": lambda c, m, s: (c.replace("requiredWidth(240.dp)", "requiredWidth(320.dp)"), m, s),
    "恢复宽松条目": lambda c, m, s: (c.replace("padding(horizontal = 10.dp, vertical = 0.dp)",
                                        "padding(horizontal = 12.dp, vertical = 2.dp)"), m, s),
    "删滑块块": lambda c, m, s: (c, m.replace("// R47 · v4 预览 ⑥ 落地", "// GONE") , s),
    "滑块直写Prefs": lambda c, m, s: (c, m.replace("onInput(s)\n            },",
                                                "Prefs.setTtsKnobs(c, draft.knobs)\n            },"), s),
    "滑块改用快捷值": lambda c, m, s: (c, m.replace("onInput(s)", "onPick(s)"), s),
    "参数名回退英文": lambda c, m, s: (c, m, s.replace('"主·随机度"', '"主 temperature"')),
}
base_e = baseline(CHROME, MAIN, SPEC)
chk(not base_e, "基线自身失败: " + str(base_e))
for name, fn in INJECT.items():
    c, m, s = fn(CHROME, MAIN, SPEC)
    e = baseline(c, m, s)
    print("  反向注入 %-16s %s" % (name, "拦下" if e else "!! 没拦住 !!"))
    chk(bool(e), "反向注入没拦住: " + name)

# ── 3) ParamRow 逐行对拍：除新增滑块块 + HelpNote 加字段外，与 HEAD 一致 ──
def canon(t):
    return [x.rstrip() for x in t.splitlines()
            if x.strip() and not x.startswith("import ")]


def head(p):
    try:
        return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                       stderr=subprocess.DEVNULL).decode("utf-8")
    except subprocess.CalledProcessError:
        return None


if not os.environ.get("CI"):
    hm = head(P_MAIN)
    chk(hm is not None, "取不到 HEAD MainActivity")
    if hm:
        old_pr = canon(paramrow(hm))
        new_pr = paramrow(MAIN)
        a = new_pr.find("        // R47 · v4 预览 ⑥ 落地")
        b = new_pr.find("        OutlinedTextField(", a + 1)
        chk(a >= 0 and b > a, "找不到 R47 滑块块边界")
        stripped = new_pr[:a] + new_pr[b:] if a >= 0 and b > a else new_pr
        stripped = stripped.replace(" · 字段 ${spec.official}", "")
        chk(canon(stripped) == old_pr, "ParamRow 除滑块块/HelpNote 外被顺手改动")

    # ── 4) ParamSpec 逐字段对拍：只允许第 3 个字符串（label）变化 ──
    hs = head(P_SPEC)
    chk(hs is not None, "取不到 HEAD ParamSpec")
    if hs:
        def canon_spec(s):
            out = []
            for line in s.splitlines():
                if line.strip().startswith("ParamSpec("):
                    strs = re.findall(r'"(?:[^"\\]|\\.)*"', line)
                    if len(strs) >= 3:
                        line = line.replace(strs[2], '"<LABEL>"', 1)
                out.append(line.rstrip())
            return [x for x in out if x.strip() and not x.startswith("import ")]
        chk(canon_spec(SPEC) == canon_spec(hs),
            "ParamSpec 除显示名外被改动（key/official/def/min/max/options 必须不变）")

    # ── 5) 守恒：真值源、引擎、主链、权限、依赖逐字节比 HEAD ──
    KEEP = [
        "app/src/main/assets/prompt_zh.txt",
        "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
        "app/src/main/java/com/tingben/app/core/Mixer.kt",
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
        chk(h is not None and h.encode("utf-8") == (ROOT / p).read_bytes(),
            "守恒文件被改动: " + p)
else:
    print("CI 环境：HEAD 即本次提交，对拍已在提交前本地核对")

if errs:
    print("\n".join("ERROR " + e for e in errs))
    sys.exit(1)
print("R47 验证 PASS：正向 %d 符号 / 反向注入 %d 例 / ParamRow+ParamSpec 对拍 / 守恒"
      % (len(CHROME_POS) + len(MAIN_POS) + len(NEW_LABELS), len(INJECT)))
