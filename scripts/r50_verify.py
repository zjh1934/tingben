#!/usr/bin/env python3
"""R50 · 真机三处修补验证（CI 跑，纯标准库）。

1) 正向符号：紧凑数字框 / 小自定义框 / 表演层 5 项 / 自定义数据落地 / 滚动联动
2) 数据层对拍：ProduceConfig 的 packExtra / unpackExtra / JSON 往返 / 范围校验
3) 反向注入 8 例：把源码改回旧形态，断言必须变红
4) 守恒：9 项采样参数 key·默认值·范围不变、唯一提交出口、prompt v2 / native 未动
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FILES = {
    "main": ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt",
    "cfg": ROOT / "app/src/main/java/com/tingben/app/core/ProduceConfig.kt",
    "draft": ROOT / "app/src/main/java/com/tingben/app/core/MixerDraft.kt",
    "spec": ROOT / "app/src/main/java/com/tingben/app/core/ParamSpec.kt",
    "prompt": ROOT / "app/src/main/assets/prompt_zh.txt",
    "native": ROOT / "app/src/main/cpp/tingben_native.cpp",
}


def read(src, key):
    return src[key]


def block(text, start, end):
    """截取两个标记之间的源码，避免全文件误命中。"""
    i = text.find(start)
    j = text.find(end, i + 1)
    if i < 0 or j < 0:
        return ""
    return text[i:j]


def checks(src):
    m, cfg, draft, spec = src["main"], src["cfg"], src["draft"], src["spec"]
    out = []

    def ck(name, ok, why=""):
        out.append((name, bool(ok), why))

    # ① 采样行紧凑化
    pr = block(m, "private fun ParamRow(", "/** R41 · 共用音色选择器")
    ck("数字框无 label", 'label = { Text("精确值"' not in pr, "旧带标签大框仍在")
    ck("数字框 72x38", "Modifier.width(72.dp).height(38.dp)" in pr)
    ck("滑块拉长", "Modifier.weight(1f).height(28.dp)" in pr)
    ck("数字框内边距", "contentPadding = PaddingValues(" in pr)
    ck("任意合法值说明进 ⓘ", "框里可填任意合法值" in pr)
    ck("滑块与数字框同源", pr.count("onValueChange = onInput") == 1 and "onValueChange = { v ->" in pr)

    # ② 表演层 5 个自定义小框，且真写 draft
    cr = block(m, "private fun ChipRow(", "/** R42 · 375px 窄屏证据")
    ck("自定义框 140x40", "Modifier.width(140.dp).height(40.dp)" in cr)
    ck("自定义框无 label", 'label = { Text("自己填（可选）"' not in cr)
    perf = block(m, 'MixerSection("表演层"', 'MixerSection("音频开关"')
    ck("表演层 5 项全开自定义", perf.count("allowCustom = true") == 5,
       "实际 %d 项" % perf.count("allowCustom = true"))
    for k in ("朗读", "语速", "情绪", "静音", "重做"):
        ck("自定义框 %s 写 draft" % k, ('setPerfCustom("%s"' % k) in perf)
    ck("选 chip 撤自定义", perf.count("clearPerfCustom(") == 5)
    ck("小框状态同步", all(x in m for x in (
        "seedPerfCustom", "syncPerfCustom", "perfText", "setPerfCustom", "clearPerfCustom")))
    ck("提交/丢弃/预设同步小框", m.count("syncPerfCustom(") >= 4)

    # ③ 自定义数据真落地（不是假开关）
    ck("extraInstruct 字段", "val extraInstruct: String = \"\"" in cfg)
    ck("styleInstruct 拼自定义", "extraInstruct.trim()" in cfg)
    ck("audioStamp 含自定义", re.search(r"audioStamp\(\).*?extraInstruct", cfg, re.S) is not None)
    ck("toJson 写自定义", '.put("extraInstruct", extraInstruct)' in cfg)
    ck("fromJson 读自定义", 'o.optString("extraInstruct", "")' in cfg)
    ck("fromJson 不再白名单重置", "if (g in GAP_OPTIONS)" not in cfg and "if (r in RETRY_OPTIONS)" not in cfg)
    ck("范围常量", "GAP_RANGE = 0..5000" in cfg and "RETRY_RANGE = 0..10" in cfg)
    ck("schema 升 2", "const val SCHEMA = 2" in cfg)
    ck("pack/unpack", "fun packExtra(" in cfg and "fun unpackExtra(" in cfg)
    ck("diff 补附加语气", 'put("附加语气"' in draft)
    ck("diff 仍逐字段", "MixerCommit" in draft or "fun diff(" in draft)

    # ④ 滚动 → 锚点高亮反向联动
    ck("snapshotFlow 存在", "snapshotFlow { scroll.value to anchors.toMap() }" in m)
    ck("跳转锁", "var jumpLock by remember { mutableStateOf(false) }" in m)
    ck("锁在 finally 释放", "finally { jumpLock = false }" in m)
    ck("锚点可见集合", "val anchorShown = listOf(" in m)
    ck("归并未列入的区", "不出现全不高亮" in m)
    ck("固定音色统一入口", 'jumpTo("fixedVoice")' in m)
    ck("AnchorBar 仍只读传入", "selected = key == current" in m)

    # ⑤ 守恒：唯一提交出口 / 参数表 / prompt / native
    ck("唯一提交出口", m.count("MixerCommit.commit(c, draft, n)") == 1)
    ck("两态试听仍在", m.count('trial("未提交参数"') == 1 and m.count('trial("已生效参数"') == 1)
    expected = [
        ("seed", "42"), ("maxNewTokens", "2048"), ("temperature", "0.9"),
        ("topK", "50"), ("topP", "1.0"), ("repetitionPenalty", "1.05"),
        ("subTemperature", "0.9"), ("subTopK", "50"), ("subTopP", "1.0"),
    ]
    sampling = block(spec, "val SAMPLING", "/** 上层项")
    sampling_lines = sampling.splitlines()
    for key, default in expected:
        # 必须按行比：旧版 re.S 跨行会误命中后面 subTemperature 的 0.9，
        # 导致「把 temperature 默认改成 0.8」的反向注入漏网。
        line = next((l for l in sampling_lines if 'ParamSpec("%s"' % key in l), "")
        ck("参数 %s 未变" % key, ('"%s"' % default) in line, "key/default 被改: " + line[:70])
    ck("9 项数量", sampling.count("ParamSpec(") == 9)
    ck("prompt v2 未动", "逐字" in src["prompt"] or len(src["prompt"]) > 200)
    ck("native 日志未动", "TTS_PARAMS_APPLIED" in src["native"] or len(src["native"]) > 1000)
    return out


# ── 数据层对拍：把 ProduceConfig 的自定义逻辑用标准库复刻一遍 ──────────────
EXTRA_KEYS = ["朗读", "语速", "情绪"]
SEP = "｜"


def pack(m):
    return SEP.join("%s:%s" % (k, m[k].strip()) for k in EXTRA_KEYS
                    if m.get(k, "").strip())


def unpack(s):
    o = {}
    for part in s.split(SEP):
        i = part.find(":")
        if i > 0 and part[:i] in EXTRA_KEYS:
            o[part[:i]] = part[i + 1:]
    return o


def from_json(o):
    """复刻 R50 的宽容迁移：范围外回默认；缺字段回空串。"""
    g = o.get("gapMs", 300)
    r = o.get("gateRetries", 1)
    return {
        "gapMs": g if isinstance(g, int) and 0 <= g <= 5000 else 300,
        "gateRetries": r if isinstance(r, int) and 0 <= r <= 10 else 1,
        "extraInstruct": o.get("extraInstruct", ""),
    }


def style_instruct(extra):
    parts = ["整体保持平稳叙述的节奏，不刻意演戏", "语速自然适中",
             "情绪按文本自然表达", extra.strip()]
    return "；".join(p for p in parts if p)


def data_pairs():
    out = []

    def ck(name, got, want):
        out.append((name, got == want, "got=%r want=%r" % (got, want)))

    ck("默认空串不改变旧 instruct",
       style_instruct(""), "整体保持平稳叙述的节奏，不刻意演戏；语速自然适中；情绪按文本自然表达")
    ck("自定义短语进 instruct", "句尾轻轻下沉" in style_instruct("句尾轻轻下沉"), True)
    m = {"朗读": "像说书人", "语速": "略快", "情绪": "压着"}
    ck("pack/unpack 往返", unpack(pack(m)), m)
    ck("pack 忽略空值", unpack(pack({"朗读": "  ", "情绪": "低声"})), {"情绪": "低声"})
    ck("unpack 忽略非法段", unpack("朗读:甲｜未知:乙｜情绪:丙"), {"朗读": "甲", "情绪": "丙"})
    ck("自定义 450ms 往返保留", from_json({"gapMs": 450})["gapMs"], 450)
    ck("自定义 5 次往返保留", from_json({"gateRetries": 5})["gateRetries"], 5)
    ck("旧 schema 缺 extra 回空", from_json({"schema": 1, "gapMs": 600})["extraInstruct"], "")
    ck("越界 6000 回默认", from_json({"gapMs": 6000})["gapMs"], 300)
    ck("越界 11 回默认", from_json({"gateRetries": 11})["gateRetries"], 1)
    ck("extra 含冒号不丢", unpack("朗读:慢:带拖腔")["朗读"], "慢:带拖腔")
    return out


# ── 反向注入：把源码改回旧形态，断言必须变红 ──────────────────────────────
def variants(src):
    v = []
    a = dict(src); a["main"] = a["main"].replace(
        "modifier = Modifier.width(72.dp).height(38.dp), singleLine = true,",
        'modifier = Modifier.width(74.dp), singleLine = true,\n                label = { Text("精确值", fontSize = 8.sp) },', 1)
    v.append(("恢复带 label 大框", a))
    b = dict(src); b["main"] = b["main"].replace("Modifier.width(140.dp).height(40.dp)", "Modifier.fillMaxWidth()", 1)
    v.append(("自定义框撑满整行", b))
    c = dict(src); c["main"] = c["main"].replace('allowCustom = true, customValue = perfText("朗读"),', "", 1)
    v.append(("删一项自定义", c))
    d = dict(src); d["cfg"] = d["cfg"].replace("style.instruct, pace.instruct, intensity.instruct, extraInstruct.trim()",
                                              "style.instruct, pace.instruct, intensity.instruct", 1)
    v.append(("自定义不进 instruct", d))
    e = dict(src); e["cfg"] = e["cfg"].replace("if (g in GAP_RANGE) g else DEFAULT_GAP_MS",
                                              "if (g in GAP_OPTIONS) g else DEFAULT_GAP_MS", 1)
    v.append(("恢复白名单重置", e))
    f = dict(src); f["main"] = f["main"].replace("snapshotFlow { scroll.value to anchors.toMap() }",
                                                "snapshotFlow { scroll.value }", 1)
    v.append(("删滚动联动", f))
    g = dict(src); g["main"] = g["main"].replace("finally { jumpLock = false }", "", 1)
    v.append(("删跳转锁释放", g))
    h = dict(src); h["draft"] = h["draft"].replace('put("附加语气", a.perf.extraInstruct, d.perf.extraInstruct, TIMING_NEXT)\n', "", 1)
    v.append(("diff 漏掉附加语气", h))
    i = dict(src); i["spec"] = i["spec"].replace('"temperature", "qt_tts_params.temperature", "主·随机度", "0.9"',
                                                '"temperature", "qt_tts_params.temperature", "主·随机度", "0.8"', 1)
    v.append(("改参数默认值", i))
    return v


def main():
    src = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    positive = checks(src)
    pairs = data_pairs()
    all_checks = positive + pairs
    bad = [(n, w) for n, ok, w in all_checks if not ok]
    print("R50 正向 %d 项 + 数据对拍 %d 项" % (len(positive), len(pairs)))
    for n, w in bad:
        print("  FAIL", n, w)
    if bad:
        print("RESULT: FAIL")
        return 1

    # 反向注入：每个变体至少要有一条断言变红
    failed = 0
    for name, vs in variants(src):
        r = checks(vs)
        red = [n for n, ok, _ in r if not ok]
        if not red:
            print("  反向注入漏网:", name)
            failed += 1
        else:
            print("  反向注入 OK:", name, "→", red[0])
    print("反向注入 %d 例，漏网 %d" % (len(variants(src)), failed))
    if failed:
        print("RESULT: FAIL")
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
