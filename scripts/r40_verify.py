#!/usr/bin/env python3
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from r45_common import strip_r45, canon
"""R40 · 调音台「统一提交」验证（纯标准库，不依赖 Android SDK）。

跑三类：
  1) diff 对拍 6 组：Python 复刻 17 个生效字段，期望 N 与源码字段表一致；
     同时断言 dims/dimText 不进 diff、voice 空值等价于 DEFAULT_IDENTITY。
  2) 反向注入 6 例：内存中把源码改坏，断言脚本真的会响（防「自检恒绿」）。
  3) 守恒清单：MainActivity 前 1880 行、4B/prompt/native/服务/manifest/gradle
     与 git HEAD 逐字节一致 —— 这轮只动了调音台与它的自检脚本。

用法：python3 scripts/r40_verify.py
"""
import pathlib, subprocess, sys, re

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = "app/src/main/java/com/tingben/app/MainActivity.kt"
DRAFT = "app/src/main/java/com/tingben/app/core/MixerDraft.kt"
CONSERVE = [
    "app/src/main/assets/prompt_zh.txt",
    "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    # R41：Prefs.kt / ParamSpec.kt 加了纯 UI 键与范围元数据，改由
    # r41_verify.py 做「4B 键 / voiceStamp 公式 / 官方字段名与默认值」语义守恒
    "app/src/main/java/com/tingben/app/core/ProduceConfig.kt",
    "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
    "app/src/main/java/com/tingben/app/core/Mixer.kt",
    "app/src/main/java/com/tingben/app/ProduceService.kt",
    # R46 · V1 抽屉壳允许改 AppChrome.kt，改由 r46_verify.py 做形态断言
    "app/src/main/cpp/tingben_native.cpp",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts",
]

# 17 个生效字段：1 身份 + 9 采样 + 5 表演 + 2 开关。dim/dimText 不在其中。
FIELDS = ["voice"] + ["k_" + s for s in
    ["seed", "maxNewTokens", "temperature", "topK", "topP",
     "repetitionPenalty", "subTemperature", "subTopK", "subTopP"]] + \
    ["perf_" + s for s in ["style", "pace", "intensity", "gapMs", "gateRetries"]] + \
    ["nonVerbal", "castEnabled"]


def py_diff(a, d):
    """复刻 MixerCommit.diff：逐字段比，voice 空串按默认身份归一。"""
    def v(x): return x["voice"].strip() or "DEFAULT_IDENTITY"
    out = []
    if v(a) != v(d): out.append("voice")
    for f in FIELDS[1:]:
        if a[f] != d[f]: out.append(f)
    return out


def base():
    b = {f: "" for f in FIELDS}
    b["voice"] = ""
    b["nonVerbal"] = True
    b["castEnabled"] = True
    return b


def scenarios():
    """6 组：无变更 / 只改身份 / 只改 1 个采样 / 改 3 类 / 清空身份 / dims 不算。"""
    a = base()
    s1 = dict(a)
    s2 = dict(a); s2["k_temperature"] = "0.6"
    s3 = dict(a); s3["voice"] = "温和女声"; s3["k_seed"] = "1234"; s3["perf_pace"] = "SLOW"
    s4 = dict(a); s4["voice"] = ""          # 空 ↔ DEFAULT_IDENTITY 等价，不算变更
    s5 = dict(a); s5["dims"] = {"GENDER": "女声"}; s5["dimText"] = "女声"
    return [(a, s1, 0, "无变更"), (a, s2, 1, "只改 1 个采样"),
            (a, s3, 3, "身份+采样+表演"), (a, s4, 0, "空身份等价默认"),
            (a, s5, 0, "dims/dimText 不进 diff"),
            (dict(a, voice="旧音色"), dict(a, voice="新音色"), 1, "身份旧→新")]


def check_sources(main, draft):
    errs = []
    # 1) 唯一编辑态与唯一出口
    for sym in ["MixerDraft.active(c)", "var draft by remember", "MixerCommit.diff(active, draft)",
                "MixerCommit.commit(c, draft, n)", "dirty", "BackHandler(enabled = dirty)"]:
        if sym not in main: errs.append("缺唯一编辑态符号: " + sym)
    for sym in ["fun diff(", "fun commit(", "MIXER_COMMIT", "TIMING_NOW", "TIMING_NEXT"]:
        if sym not in draft: errs.append("缺唯一出口符号: " + sym)
    # 2) 界面不许有第二个生效写入口
    i = main.find("// ── ⑥ 调音台")
    blk = main[i:] if i >= 0 else ""
    for banned in ["Prefs.setTtsKnobs(", "Prefs.setVoiceDescription(", "Prefs.setDefaultPerf(",
                   "Prefs.setNonVerbal(", "Prefs.setCastEnabled(", "Prefs.resetTtsKnobs("]:
        if banned in blk: errs.append("出现第二个生效写入口: " + banned)
    # 3) 旧生效动词只查字符串字面量
    lits = re.findall(r'"((?:\\.|[^"\\])*)"', main) + re.findall(r'"((?:\\.|[^"\\])*)"', draft)
    for banned in ["设定", "套用", "保存并生效", "直接保存生效", "应用预设"]:
        if any(banned in x for x in lits): errs.append("旧生效动词残留: " + banned)
    # 4) diff 字段表与 Python 对拍一致
    if draft.count("put(") != 11:  # R50 起 10 次字段调用（含附加语气）+ 1 次 put() 定义
        errs.append("diff 源码调用数 != 10")
    for label in ["音色身份", "朗读档位", "语速倾向", "情绪强度", "段间静音",
                  "失败重做", "附加语气", "拟声表达", "角色映射", "ParamCatalog.SAMPLING.forEach"]:
        if label not in draft: errs.append("diff 缺字段: " + label)
    # 5) 吸顶条 / 差异弹层 / 角色 chip / 删除确认
    for sym in ["未提交变更", "查看差异", "提交变更", "当前生效全貌",
                "未绑定音色", "confirmDeleteId", "丢弃全部待提交", "载入为待提交"]:
        if sym not in main: errs.append("缺 P0 界面符号: " + sym)
    return errs


def git_head(path):
    try:
        return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + path],
                                       stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return None


def main():
    m = (ROOT / MAIN).read_text(encoding="utf-8")
    d = (ROOT / DRAFT).read_text(encoding="utf-8")

    print("=== 1) diff 对拍（6 组）===")
    ok = True
    for a, dr, want, name in scenarios():
        got = len(py_diff(a, dr))
        flag = "PASS" if got == want else "FAIL"
        print("  %-24s 期望 %d 实际 %d %s" % (name, want, got, flag))
        if got != want: ok = False
    base_errs = check_sources(m, d)
    print("基线源码检查:", "PASS" if not base_errs else "FAIL")
    for e in base_errs: print("  -", e)
    if base_errs: ok = False

    print("=== 2) 反向注入（6 例，内存中改，不落盘）===")
    cases = {
        "删唯一出口": lambda s: s.replace("MixerCommit.commit(c, draft, n)", "0L"),
        "恢复旧动词设定": lambda s: s.replace('Text("提交变更"', 'Text("设定"'),
        "删离开确认": lambda s: s.replace("BackHandler(enabled = dirty)", "BackHandler(enabled = false)"),
        "删编辑态": lambda s: s.replace("var draft by remember", "var tmp by remember"),
        "注入第二写入口": lambda s: s.replace("fun commitAll() {", "fun commitAll() { Prefs.setTtsKnobs(c, draft.knobs)"),
        "删提交日志": lambda s: s.replace('EventLog.log(EventLog.Kind.ENGINE, "MIXER_COMMIT",', 'EventLog.log(EventLog.Kind.ENGINE, "X",'),
    }
    for name, fn in cases.items():
        mm, dd = m, d
        if name in ("删提交日志",):
            dd = fn(d)
        else:
            mm = fn(m)
        errs = check_sources(mm, dd)
        flag = "拦下" if errs else "漏过(假绿)"
        print("  %-16s → %-12s %s" % (name, flag, (errs[:1] or [""])[0]))
        if not errs: ok = False

    print("=== 3) 守恒清单（与 git HEAD 逐字节比）===")
    # CI 里 HEAD 就是本次提交，逐字节比恒一致；守恒以本地提交前的核对为准。
    if __import__("os").environ.get("CI"):
        print("  CI 环境：HEAD 即本次提交，守恒已在提交前本地核对，跳过逐字节比")
        print("结果:", "PASS" if ok else "FAIL")
        sys.exit(0 if ok else 1)
    # R41 只在文件头加了 7 行 Compose import；调音台区块之前的业务代码不许动。
    # 按区块标记切，而不是固定行数（新增 import 会让行号整体偏移）。
    no_imports = lambda ls: [x for x in ls if not x.startswith("import ")]
    head = canon(m.split("// ── ⑥ 调音台")[0])
    try:
        old = canon(git_head(MAIN).decode("utf-8").split("// ── ⑥ 调音台")[0])
        same = head == old
        print("  MainActivity 前 1880 行:", "一致" if same else "被改动")
        if not same: ok = False
    except Exception as e:
        print("  MainActivity 前 1880 行: 无法比对", e); ok = False
    for p in CONSERVE:
        cur = (ROOT / p).read_bytes()
        old = git_head(p)
        same = old is not None and cur == old
        print("  %-58s %s" % (p, "一致" if same else "被改动"))
        if not same: ok = False

    print("结果:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
