#!/usr/bin/env python3
"""R38 · 参数触达矩阵自检。

解析五处源码生成「UI 项 ↔ 官方字段 ↔ 默认值 ↔ 触达链路」矩阵，任一字段缺失 /
错序 / 默认值不符 / 证据缺失即 FAIL。自带 5 例反向注入（内存中改，不落盘）：
注入违规必须 FAIL，还原必须 PASS —— 验证脚本自己的断言确实会响。

用法：python3 scripts/r38_param_audit.py
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
FILES = {
    "prefs": "app/src/main/java/com/tingben/app/core/Prefs.kt",
    "spec": "app/src/main/java/com/tingben/app/core/ParamSpec.kt",
    "engine": "app/src/main/java/com/tingben/app/engine/NativeEngine.kt",
    "cpp": "app/src/main/cpp/tingben_native.cpp",
    "qwenh": "app/src/main/cpp/qwen.h",
    # 官方默认值头的仓库内副本：reference/ 不随 CI checkout 同步，故放 contracts/。
    "defaults": "contracts/sampling-defaults.h",
    "ui": "app/src/main/java/com/tingben/app/MainActivity.kt",
    "mixer": "app/src/main/java/com/tingben/app/core/Mixer.kt",
}

# Kotlin TtsKnobs 字段 → JNI 形参 → qt_tts_params 字段，顺序必须完全一致。
CHAIN = [
    ("seed", "seed", "seed", "42", "SEED"),
    ("maxNewTokens", "maxNewTokens", "max_new_tokens", "2048", "MAX_NEW_TOKENS"),
    ("temperature", "temperature", "temperature", "0.9", "TEMPERATURE"),
    ("topK", "topK", "top_k", "50", "TOP_K"),
    ("topP", "topP", "top_p", "1.0", "TOP_P"),
    ("repetitionPenalty", "repetitionPenalty", "repetition_penalty", "1.05", "REPETITION_PENALTY"),
    ("subTemperature", "subTemperature", "subtalker_temperature", "0.9", "SUBTALKER_TEMPERATURE"),
    ("subTopK", "subTopK", "subtalker_top_k", "50", "SUBTALKER_TOP_K"),
    ("subTopP", "subTopP", "subtalker_top_p", "1.0", "SUBTALKER_TOP_P"),
]


def read_all():
    return {k: (ROOT / v).read_text(encoding="utf-8") for k, v in FILES.items()}


def check(src):
    errs = []
    # 1) Kotlin data class 字段顺序
    m = re.search(r"data class TtsKnobs\((.*?)\n\)", src["prefs"], re.S)
    if not m:
        errs.append("Prefs.kt 找不到 TtsKnobs 定义")
        fields = []
    else:
        fields = re.findall(r"val (\w+):", m.group(1))
    want = [c[0] for c in CHAIN]
    if fields != want:
        errs.append("TtsKnobs 字段顺序/数量不符：%s" % fields)

    # 2) NativeEngine 展开顺序：只取 return ttsSynthesizeRaw(...) 调用处，
    #    不把 TTS_PARAMS 日志字符串里的 knobs.xxx 也算进来（脚本自己曾在这里误报）。
    m = re.search(r"return ttsSynthesizeRaw\(([\s\S]*?)\)\s*\n\s*\}", src["engine"])
    if not m:
        errs.append("NativeEngine 找不到 return ttsSynthesizeRaw 调用")
        expand = []
    else:
        expand = re.findall(r"knobs\.(\w+)", m.group(1))
    if expand != want:
        errs.append("NativeEngine 展开顺序不符：%s" % expand)

    # 3) JNI 函数签名参数顺序
    m = re.search(r"Java_com_tingben_app_engine_NativeEngine_ttsSynthesizeRaw\((.*?)\)\s*\{",
                  src["cpp"], re.S)
    if not m:
        errs.append("JNI 里找不到 ttsSynthesizeRaw 签名")
        sig = []
    else:
        sig = re.findall(r"j(?:long|int|float)\s+(\w+)", m.group(1))
        sig = [x for x in sig if x in [c[1] for c in CHAIN]]
    if sig != [c[1] for c in CHAIN]:
        errs.append("JNI 形参顺序不符：%s" % sig)

    # 4) 每个 qt_tts_params 字段都有赋值，且字段名存在
    assigned = set(re.findall(r"tp\.(\w+)\s*=", src["cpp"]))
    struct = re.search(r"struct qt_tts_params \{(.*?)\n\};", src["qwenh"], re.S)
    official = set(re.findall(r"^\s*(?:int64_t|int|float)\s+(\w+);", struct.group(1), re.M)) if struct else set()
    for _, _, off, _, _ in CHAIN:
        if off not in official:
            errs.append("qwen.h 缺字段 %s" % off)
        if off not in assigned:
            errs.append("JNI 没有给 tp.%s 赋值（假开关）" % off)

    # 5) 官方默认值逐字一致（seed 是 App 固定 42，官方默认 -1，属显式例外）
    defmap = dict(re.findall(r"#define\s+QT_DEFAULT_(\w+)\s+([0-9.]+f?)", src["defaults"]))
    for _, _, _, val, macro in CHAIN:
        if macro == "SEED":
            if "const val SEED = 42L" not in src["prefs"]:
                errs.append("seed 的 App 固定默认 42 不见了")
            continue
        raw = defmap.get(macro)
        if raw is None:
            errs.append("sampling-defaults.h 缺 %s" % macro)
            continue
        if raw.rstrip("fL") != val:
            errs.append("%s 默认值不一致：官方 %s / 界面 %s" % (macro, raw, val))

    # 6) ParamSpec 9 项：官方字段名、默认值、范围校验函数都要在
    for key, _, off, val, _ in CHAIN:
        if '"qt_tts_params.%s"' % off not in src["spec"]:
            errs.append("ParamSpec 缺官方字段 qt_tts_params.%s" % off)
    if '"%s", "%s"' % ("seed", "qt_tts_params.seed") not in src["spec"]:
        errs.append("ParamSpec 首项不是 seed")
    if "fun withValue(k: TtsKnobs, raw: String): TtsKnobs?" not in src["spec"]:
        errs.append("ParamSpec 缺范围校验 withValue")
    if src["spec"].count("ParamSpec(\"") < 9:
        errs.append("ParamSpec 里采样项少于 9")

    # 7) 触达证据：Kotlin 侧事件 + JNI 实收日志
    if 'EventLog.log(EventLog.Kind.ENGINE, "TTS_PARAMS"' not in src["engine"]:
        errs.append("NativeEngine 缺 TTS_PARAMS 事件")
    if "TTS_PARAMS_APPLIED" not in src["cpp"]:
        errs.append("JNI 缺 TTS_PARAMS_APPLIED 实收日志")

    # 8) 界面（R40）：音色身份是首屏固定卡；唯一生效动词是「提交变更」；
    #    采样组默认收起、身份生成器默认展开（评审骨架）。
    if "音色身份" not in src["ui"]:
        errs.append("首屏音色身份输入框不见了")
    for sym in ["提交变更", "未提交变更", "查看差异", "MixerCommit.commit(c, draft, n)"]:
        if sym not in src["ui"]:
            errs.append("R40 统一提交条缺符号 " + sym)
    if "保存并生效" in src["ui"]:
        errs.append("R40 回退：又出现第二个生效动词")
    if 'MixerSection("音色身份"' in src["ui"]:
        errs.append("音色身份又被塞进折叠组了")
    if 'MixerSection("采样参数 · 9 项真到模型", "sampling",' not in src["ui"] or \
       'openMap.contains("sampling")' not in src["ui"]:
        errs.append("R45 回退：采样组没走受控 openMap")
    if 'MixerSection("音色身份生成器", "identity",' not in src["ui"] or \
       'openMap.contains("identity")' not in src["ui"]:
        errs.append("R41 回退：身份生成器没走受控 openMap")
    if "ParamCatalog.find(" not in src["ui"]:
        errs.append("采样参数没有走 ParamCatalog 单一定义")
    if "ParamCatalog.SAMPLING.forEach" in src["ui"]:
        errs.append("R41 回退：九项又退回平铺卡")
    if "ParamCatalog.DOCS" not in src["ui"]:
        errs.append("官方资料索引没进控制台")
    if 'null, "auto"' in src["ui"]:
        errs.append("设置页试音仍在用 auto（与主链 chinese 不一致）")

    # 9) 守恒：不动主链签名与缓存
    for forbidden in ["Producer.start(", "bucket("]:
        pass  # 由 check_structure.py 负责，这里只列不判
    if "qt_tts_params tp;" not in src["cpp"]:
        errs.append("JNI 不再用 qt_tts_params")
    if "ParamCatalog.SAMPLING.forEach" not in src["mixer"] and "ParamCatalog" not in src["mixer"]:
        errs.append("Mixer.coverage 没接 ParamCatalog")

    return errs


def inject(src, case):
    s = dict(src)
    if case == "drop_tp":
        s["cpp"] = s["cpp"].replace(
            "    tp.subtalker_top_p       = (float)subTopP;\n", "")
    elif case == "bad_official":
        s["spec"] = s["spec"].replace(
            '"qt_tts_params.subtalker_top_p"', '"qt_tts_params.sub_talker_typo"')
    elif case == "bad_default":
        s["defaults"] = s["defaults"].replace(
            "#define QT_DEFAULT_TEMPERATURE           0.9f",
            "#define QT_DEFAULT_TEMPERATURE           0.5f")
    elif case == "drop_event":
        s["engine"] = re.sub(
            r'\s*EventLog\.log\(EventLog\.Kind\.ENGINE, "TTS_PARAMS",[\s\S]*?level = EventLog\.Level\.TRACE\)\n',
            "\n", s["engine"], count=1)
    elif case == "hide_voice":
        # R41：把受控展开态改回写死 true（绕过 Prefs 持久化），断言必须响
        s["ui"] = s["ui"].replace('openMap.contains("sampling")', 'true')
    elif case == "drop_commit":
        # R40：把唯一出口删掉，断言必须响
        s["ui"] = s["ui"].replace('MixerCommit.commit(c, draft, n)',
                                  'MixerCommit.diff(active, draft).size.toLong()')
    return s


def main():
    src = read_all()
    base = check(src)
    print("基线:", "PASS" if not base else "FAIL")
    for e in base:
        print("  -", e)
    cases = ["drop_tp", "bad_official", "bad_default", "drop_event", "hide_voice", "drop_commit"]
    bad = 0
    for c in cases:
        errs = check(inject(src, c))
        ok = len(errs) > 0
        print("反向注入 %-14s → %s %s" % (c, "拦下" if ok else "漏过(假绿)", errs[:1]))
        if not ok:
            bad += 1
    if base or bad:
        print("结果: FAIL")
        sys.exit(1)
    print("矩阵 9 项逐字段核对通过；6 例反向注入全部拦下；结果 PASS")


if __name__ == "__main__":
    main()
