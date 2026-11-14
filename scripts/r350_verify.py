# -*- coding: utf-8 -*-
"""R350 · 模式二「预设音色」九个音色平铺、中文名按模型官方音色表译。

用户口径：九个音色铺开就行，不要再按「男声 / 女声 / 青年音 / 少年音」分组；
之前那批中文名译得不准，要按 Qwen3-TTS CustomVoice 官方音色表的描述重译。

守恒：送进引擎的仍是模型自带英文名（speaker 字段），只在界面显示中文；
九项采样仍只在「描述造音色」模式露出（R344）；三模式分支、试听区、
风格描述与断点续跑一条不动。

用法：python3 scripts/r350_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "desc": APP / "DescPresetUi.kt",
    "mixer": APP / "MixerUi.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

# 官方音色表九个 speaker 的中文短名（保留最能区分的特征）
CN = [
    '"vivian" -> "明亮女声"',
    '"serena" -> "温暖女声"',
    '"uncle_fu" -> "醇厚男声"',
    '"dylan" -> "京味男声"',
    '"eric" -> "成都男声"',
    '"ryan" -> "动感男声"',
    '"aiden" -> "美式男声"',
    '"ono_anna" -> "日系女声"',
    '"sohee" -> "韩语女声"',
]
OFFICIAL = [
    '"vivian" -> "Vivian · 明亮、略带锋芒的年轻女声"',
    '"serena" -> "Serena · 温暖、柔和的年轻女声"',
    '"uncle_fu" -> "Uncle_Fu · 低沉醇厚的成熟男声"',
    '"dylan" -> "Dylan · 清亮自然的京味年轻男声"',
    '"eric" -> "Eric · 略带沙哑的活泼成都男声"',
    '"ryan" -> "Ryan · 节奏感强的动感男声"',
    '"aiden" -> "Aiden · 阳光清澈的美式男中音"',
    '"ono_anna" -> "Ono_Anna · 轻快俏皮的日系女声"',
    '"sohee" -> "Sohee · 温暖富有情感的韩语女声"',
]

POS = {
    "desc": [
        "R350 · 第二种模式的音色表是模型自带英文名",
        "internal fun speakerOfficial(name: String): String",
    ] + CN + OFFICIAL,
    "mixer": [
        "R350 · 九个音色按模型音色表原样平铺",
        "speakerList.forEach { name ->",
        "selected = draft.speaker == name,",
        "onClick = { onDraft(draft.copy(speaker = name)) },",
        "label = { Text(speakerCn(name), fontSize = 12.sp) }",
        'Text("官方音色描述：" + speakerOfficial(draft.speaker)',
        # R344 守恒：九项采样仍锁在模式一
        "if (mode == ModelStore.Mode.VOICE_DESIGN) {",
        "ModelStore.Mode.CUSTOM_VOICE -> CustomVoicePane(",
    ],
    "ci": ["python3 scripts/r350_verify.py --selftest"],
    "r135": ['"r350_verify.py"'],
}

BAN = {
    "desc": [
        '"serena" -> "温柔女声"',
        '"vivian" -> "明快女声"',
        '"uncle_fu" -> "沉稳男声"',
        '"dylan" -> "青年男声"',
        '"eric" -> "清朗男声"',
        '"ryan" -> "磁性男声"',
        '"aiden" -> "少年音"',
        '"ono_anna" -> "知性女声"',
        '"sohee" -> "软萌女声"',
    ],
    "mixer": [
        "val groups = linkedMapOf<String, MutableList<String>>()",
        '"女声" in cn',
        '"男声" in cn',
        '"少年" in cn',
        '"其它音色"',
        "groups.forEach",
        "九种音色按「女声 / 男声 / 少年音」分组",
        "draft.copy(speaker = speakerCn(",
        # R158：教程式长说明不进 UI，译名依据由「官方音色描述」单行给
        'Text("中文名按模型官方音色表译；送进引擎仍用原名"',
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def pane_span(s):
    """取 CustomVoicePane 函数体（花括号配对，跳过字符串）。"""
    start = s.find("internal fun CustomVoicePane(")
    if start < 0:
        return None
    open_at = s.find("{", start)
    depth, i, in_str = 0, open_at, False
    while i < len(s):
        ch = s[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
        else:
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return open_at, i
        i += 1
    return None


def check(files):
    e = []
    for k, words in POS.items():
        for w in words:
            if w not in files.get(k, ""):
                e.append("缺锚[%s]: %s" % (k, w[:64]))
    for k, words in BAN.items():
        for w in words:
            if w in files.get(k, ""):
                e.append("旧形态回潮[%s]: %s" % (k, w[:64]))
    m = files.get("mixer", "")
    span = pane_span(m)
    if span is None:
        e.append("找不到 CustomVoicePane 函数体")
    else:
        a, b = span
        body = m[a:b]
        if body.count("FlowRow(") != 1:
            e.append("九音色没平铺进同一个 FlowRow（分组或拆行回潮）")
        if "speakerList.forEach { name ->" not in body:
            e.append("平铺循环不在 CustomVoicePane 内")
    return e


MUTANTS = [
    ("分组逻辑回潮", "mixer", "R350 · 九个音色按模型音色表原样平铺",
     "val groups = linkedMapOf<String, MutableList<String>>()\n        // R350 · 九个音色按模型音色表原样平铺"),
    ("平铺循环被摘", "mixer", "speakerList.forEach { name ->",
     "listOf<String>().forEach { name ->"),
    ("拆分多个 FlowRow", "mixer", "FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),",
     "FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),\n            verticalArrangement = Arrangement.spacedBy(4.dp)) { }\n        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),"),
    ("送引擎改传中文名", "mixer", "onClick = { onDraft(draft.copy(speaker = name)) },",
     "onClick = { onDraft(draft.copy(speaker = speakerCn(name))) },"),
    ("官方描述行被摘", "mixer", 'Text("官方音色描述：" + speakerOfficial(draft.speaker)', ""),
    ("官方描述函数被摘", "desc", "internal fun speakerOfficial(name: String): String", ""),
    ("aiden 退回少年音", "desc", '"aiden" -> "美式男声"', '"aiden" -> "少年音"'),
    ("eric 退回清朗男声", "desc", '"eric" -> "成都男声"', '"eric" -> "清朗男声"'),
    ("ono_anna 退回知性女声", "desc", '"ono_anna" -> "日系女声"', '"ono_anna" -> "知性女声"'),
    ("sohee 退回软萌女声", "desc", '"sohee" -> "韩语女声"', '"sohee" -> "软萌女声"'),
    ("ryan 退回磁性男声", "desc", '"ryan" -> "动感男声"', '"ryan" -> "磁性男声"'),
    ("dylan 退回青年男声", "desc", '"dylan" -> "京味男声"', '"dylan" -> "青年男声"'),
    ("vivian 退回明快女声", "desc", '"vivian" -> "明亮女声"', '"vivian" -> "明快女声"'),
    ("uncle_fu 退回沉稳男声", "desc", '"uncle_fu" -> "醇厚男声"', '"uncle_fu" -> "沉稳男声"'),
    ("serena 退回温柔女声", "desc", '"serena" -> "温暖女声"', '"serena" -> "温柔女声"'),
    ("京味官方描述被改", "desc", '"dylan" -> "Dylan · 清亮自然的京味年轻男声"',
     '"dylan" -> "Dylan · 青年男声"'),
    ("九项采样搬出模式一", "mixer", "if (mode == ModelStore.Mode.VOICE_DESIGN) {", "if (true) {"),
    ("模式二分支被摘", "mixer", "ModelStore.Mode.CUSTOM_VOICE -> CustomVoicePane(", ""),
    ("CI 未挂本判据", "ci", "python3 scripts/r350_verify.py --selftest", ""),
    ("r135 清单漏挂", "r135", '"r350_verify.py"', ""),
]


def selftest():
    base = load()
    assert not check(base), "正向不该红：%s" % check(base)
    for name, key, old, new in MUTANTS:
        f = dict(base)
        f[key] = f[key].replace(old, new, 1)
        assert check(f), "反向注入没拦下：%s" % name
    n = 0
    for key, words in BAN.items():
        for w in words:
            f = dict(base)
            f[key] = base[key] + "\n// " + w + "\n"
            assert check(f), "旧形态回潮没拦下[%s]: %s" % (key, w[:40])
            n += 1
    print("R350 selftest: 正向通过，反向注入 %d 组 + 回潮 %d 组全拦下"
          % (len(MUTANTS), n))


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    e = check(load())
    if e:
        print("R350 红：")
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R350 绿：九音色平铺不分组、译名按官方音色表、原名照旧送引擎")


if __name__ == "__main__":
    main()
