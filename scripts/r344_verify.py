# -*- coding: utf-8 -*-
"""R344 · 采样参数只在「描述造音色」模式露出：形态守门 + 链路守恒。

用户口径（第二轮选了第二种模式）：调音台的采样参数这一块只有第一种
合成模式有，「预设音色」「参考音频」两种模式整块不显示。R326 曾把九项
无条件放开给三种模式（为修「切过去调这九项」会偷偷改全局模式的坑），
本轮按用户新口径收回界面露出。

守恒一条不许动：底层九项仍是全局参数、三种模式都原样传给引擎（R222），
合成 / 保存设置 / 试听 / 音色指纹 / 断点续跑一律不碰；模式页签三分支、
试听区排在九项之前（r153）、九项走 ParamCatalog 单一目录（r98/r99）全留。

用法：python3 scripts/r344_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "mixer": APP / "MixerUi.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
}

GUARD = "if (mode == ModelStore.Mode.VOICE_DESIGN) {"

POS = {
    "mixer": [
        "R344 · 用户口径：采样参数这一块只在「描述造音色」模式里露出",
        GUARD,
        "when (mode) {",
        "ModelStore.Mode.VOICE_DESIGN -> VoiceDesignPane(",
        "ModelStore.Mode.CUSTOM_VOICE -> CustomVoicePane(",
        "ModelStore.Mode.BASE -> BasePane(",
        'Text("采样参数 · 9 项"',
        "ParamCatalog.SAMPLING.forEach { spec ->",
        "ParamLine(spec = spec, cur = spec.read(draft.knobs))",
        'Text("全部恢复默认"',
    ],
    "ci": ["python3 scripts/r344_verify.py --selftest"],
}

BAN = {
    "mixer": [
        'Text("采样参数三种模式共用"',
        "切过去调这九项",
    ],
}

# 这四枚锚必须整组落在 VOICE_DESIGN 条件块内，条件块外一个都不许有
BLOCK_ANCHORS = [
    'Text("采样参数 · 9 项"',
    "ParamCatalog.SAMPLING.forEach { spec ->",
    "ParamLine(spec = spec, cur = spec.read(draft.knobs))",
    'Text("全部恢复默认"',
]


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def block_span(s):
    """从条件行的 { 起做花括号配对（跳过字符串），返回 (start, end)。"""
    start = s.find(GUARD)
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
                    return start, i
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
    span = block_span(m)
    if span is None:
        e.append("九项采样没被 VOICE_DESIGN 条件整块包住")
    else:
        a, b = span
        body = m[a:b]
        outside = m[:a] + m[b:]
        for w in BLOCK_ANCHORS:
            if w not in body:
                e.append("九项锚跑到条件块外: %s" % w[:48])
            if w in outside:
                e.append("条件块外仍有九项渲染: %s" % w[:48])
    return e


MUTANTS = [
    ("条件整块被摘", "mixer", GUARD, ""),
    ("条件反选成非模式一", "mixer", GUARD,
     "if (mode != ModelStore.Mode.VOICE_DESIGN) {"),
    ("条件改恒真", "mixer", GUARD, "if (true) {"),
    ("九项目录被摘", "mixer", "ParamCatalog.SAMPLING.forEach { spec ->",
     "listOf<ParamSpec>().forEach { spec ->"),
    ("九项搬出条件块", "mixer", "Spacer(Modifier.height(14.dp))",
     "ParamCatalog.SAMPLING.forEach { spec -> }\n            Spacer(Modifier.height(14.dp))"),
    ("标题搬出条件块", "mixer", "Spacer(Modifier.height(14.dp))",
     'Text("采样参数 · 9 项", fontSize = 12.sp)\n            Spacer(Modifier.height(14.dp))'),
    ("ParamLine 被摘", "mixer",
     "ParamLine(spec = spec, cur = spec.read(draft.knobs))", ""),
    ("恢复默认被摘", "mixer", 'Text("全部恢复默认"', ""),
    ("模式二分支被摘", "mixer", "ModelStore.Mode.CUSTOM_VOICE -> CustomVoicePane(", ""),
    ("模式三分支被摘", "mixer", "ModelStore.Mode.BASE -> BasePane(", ""),
    ("旧副标题回潮", "mixer", "Spacer(Modifier.height(14.dp))",
     'Text("采样参数三种模式共用", color = Sub)\n            Spacer(Modifier.height(14.dp))'),
    ("会改模式的旧入口回潮", "mixer", "Spacer(Modifier.height(14.dp))",
     "// 切过去调这九项\n            Spacer(Modifier.height(14.dp))"),
    ("CI 未挂本判据", "ci", "python3 scripts/r344_verify.py --selftest", ""),
]


def selftest():
    base = load()
    assert not check(base), "正向不该红：%s" % check(base)
    for name, key, old, new in MUTANTS:
        f = dict(base)
        f[key] = f[key].replace(old, new, 1)
        assert check(f), "反向注入没拦下：%s" % name
    for old_form in BAN["mixer"]:
        f = dict(base)
        f["mixer"] = base["mixer"] + "\n// " + old_form + "\n"
        assert check(f), "旧形态回潮没拦下：%s" % old_form[:40]
    print("R344 selftest: 正向通过，反向注入 %d 组 + 回潮 %d 组全拦下"
          % (len(MUTANTS), len(BAN["mixer"])))


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    e = check(load())
    if e:
        print("R344 红：")
        for x in e:
            print(" -", x)
        sys.exit(1)
    print("R344 绿：采样参数仅在描述造音色模式露出，三模式分支与九项链路守恒")


if __name__ == "__main__":
    main()
