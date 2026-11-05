#!/usr/bin/env python3
"""R41a 两态试听验证：正向 / 两态对拍 / 反向注入 / 守恒。纯标准库。"""
import pathlib, subprocess, sys, os, re

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = "app/src/main/java/com/tingben/app/MainActivity.kt"
CONSERVE = [
    "app/src/main/assets/prompt_zh.txt",
    "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    # R41：Prefs.kt 加了纯 UI 键，改由 r41_verify.py 做语义守恒
    "app/src/main/java/com/tingben/app/core/ProduceConfig.kt",
    "app/src/main/java/com/tingben/app/core/VoiceProfile.kt",
    "app/src/main/java/com/tingben/app/core/PlayQueue.kt",
    "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
    "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
    "app/src/main/java/com/tingben/app/ProduceService.kt",
    "app/src/main/cpp/tingben_native.cpp",
    "app/src/main/AndroidManifest.xml",
    "app/build.gradle.kts",
]

POS = [
    "试听未提交参数", "试听已生效参数", "试听文本", '"MIXER_TRIAL",',
    '"MIXER_TRIAL_FAIL"', "fun trial(", "VoiceProfile.build(",
    "src.perf.styleInstruct()", "src.knobs", "PlayQueue.setTracks(",
    "PlayQueue.playFrom(0)", "‹ 返回", 'trial("未提交参数", draft',
    'trial("已生效参数", active', "trialMsg", "canTrial",
]
OLD = ["A/B 试听", "合成 A", "▶ 听 A", "MIXER_AB", "bPresetId",
       "对比用文本", "B 用哪组预设", "fileA", "abMsg", "fun synth("]


def check_sources(m):
    e = []
    for s in POS:
        if s not in m: e.append("缺新符号: " + s)
    for s in OLD:
        if s in m: e.append("旧 A/B 残留: " + s)
    # 两个按钮必须都在吸顶状态条那一张 CardBox 内（返回也在其中）
    i = m.find("吸顶状态条")
    j = m.find("Column(Modifier.weight(1f).verticalScroll", i)
    bar = m[i:j] if i >= 0 and j > i else ""
    for s in ["‹ 返回", "提交变更", "试听未提交参数", "试听已生效参数"]:
        if s not in bar: e.append("不在同一吸顶模块: " + s)
    # 试听不许写回长期默认 / 不许出现第二个生效动词
    k = m.find("fun trial(")
    p = m.find("    val autoText = VoicePreset", k)
    trial = m[k:p] if k >= 0 and p > k else ""
    for s in ["Prefs.setTtsKnobs", "Prefs.setVoiceDescription", "MixerCommit.commit"]:
        if s in trial: e.append("试听越过唯一出口: " + s)
    # CI run 50 真编译抓到、静态自检没抓到的两类错误，补成硬断言
    # 纯注释行先屏蔽：注释里举例说明这个坑时，不该把自己拦下（playbook 已踩过）。
    code = "\n".join("" if ln.lstrip().startswith("//") else ln for ln in m.splitlines())
    if re.search(r"\$[A-Za-z_][A-Za-z0-9_]*[\u4e00-\u9fff]", code):
        e.append("字符串模板 $变量 后紧跟中文，Kotlin 会当成标识符")
    if not (0 <= m.find("fun play(") < m.find("fun trial(")):
        e.append("play 必须定义在 trial 之前（局部函数不许前向引用）")
    # 全页唯一生效动词仍是提交变更（旧动词不许回来）
    for s in ["保存并生效", "直接保存生效", "套用", "应用预设", 'Text("设定"']:
        if s in m: e.append("旧生效动词残留: " + s)
    return e


def build(voice, style, block="平稳、清晰地叙述，句间自然停顿。"):
    """复刻 VoiceProfile.build：身份 + 风格层 + 本段情绪。"""
    g = (voice.strip() or "清晰自然的中文普通话叙述声，音区适中，无明显口音").rstrip("。，")
    r = style.strip().rstrip("。，")
    b = block.strip().rstrip("。，")
    out = g + "。"
    if r: out += "\n[风格层] " + r
    if b: out += "\n[本段情绪] " + b
    return out.strip()


def main():
    m = (ROOT / MAIN).read_text(encoding="utf-8")
    ok = True
    print("=== 1) 正向 ===")
    errs = check_sources(m)
    print("  ", "PASS" if not errs else "FAIL")
    for x in errs: print("   -", x)
    if errs: ok = False

    print("=== 2) 两态对拍（防 A vs A）===")
    active = ("清晰自然的中文普通话叙述声，音区适中，无明显口音",
              "整体保持平稳叙述的节奏，不刻意演戏；语速自然适中；情绪按文本自然表达")
    cases = [
        ("只改身份", ("温和女声，音区中高", active[1])),
        ("只改风格", (active[0], "按广播剧方式演绎；整体语速偏慢；情绪可以更饱满")),
        ("身份+风格", ("低沉男声", "对白部分语气更分明")),
    ]
    for name, d in cases:
        a = build(*active); b = build(*d)
        same = a == b
        print("  %-12s %s" % (name, "不同 ✓" if not same else "相同 ✗"))
        if same: ok = False

    inj = {
        "删草稿试听": lambda s: s.replace('trial("未提交参数", draft', 'trial("未提交参数", active'),
        "两态同参": lambda s: s.replace('trial("已生效参数", active', 'trial("已生效参数", draft'),
        "恢复MIXER_AB": lambda s: s.replace('"MIXER_TRIAL",', '"MIXER_AB",'),
        "手搓MediaPlayer": lambda s: s.replace("PlayQueue.setTracks(", "MediaPlayer().setDataSource("),
        "删试听日志": lambda s: s.replace('"MIXER_TRIAL",', '"NO_LOG",'),
        "试听写回默认": lambda s: s.replace('trialText, instruct, null, "chinese"',
                                            'Prefs.setTtsKnobs(c, src.knobs); trialText, instruct, null, "chinese"'),
        "模板漏花括号": lambda s: s.replace('"正在用${label}合成', '"正在用$label合成'),
    }
    print("=== 3) 反向注入（%d 例）===" % len(inj))
    for name, fn in inj.items():
        e = check_sources(fn(m))
        print("  %-16s %s" % (name, "拦下 ✓" if e else "漏过 ✗"))
        if not e: ok = False

    print("=== 4) 守恒（与 git HEAD 逐字节比）===")
    if os.environ.get("CI"):
        print("  CI：HEAD 即本次提交，已提交前核对，跳过")
    else:
        def head(p):
            try:
                return subprocess.check_output(["git", "-C", str(ROOT), "show", "HEAD:" + p],
                                               stderr=subprocess.DEVNULL)
            except subprocess.CalledProcessError:
                return None
        # R41 只在文件头加了 7 行 Compose import；按区块标记切，避开行号偏移。
        no_imports = lambda ls: [x for x in ls if not x.startswith("import ")]
        cur = no_imports(m.split("// ── ⑥ 调音台")[0].splitlines())
        old = head(MAIN)
        same = old is not None and cur == no_imports(
            old.decode("utf-8").split("// ── ⑥ 调音台")[0].splitlines())
        print("  MainActivity 前 1880 行:", "一致" if same else "被改动")
        if not same: ok = False
        for p in CONSERVE:
            h = head(p)
            if h is None:
                print("  %-58s %s" % (p, "HEAD 无（后续轮新增，跳过）"))
                continue
            same = h == (ROOT / p).read_bytes()
            print("  %-58s %s" % (p, "一致" if same else "被改动"))
            if not same: ok = False
    print("结果:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
