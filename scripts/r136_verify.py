# -*- coding: utf-8 -*-
"""R136 · 模型页一屏装得下 / 孤儿 import 零残留 验证。

背景：这是用户最早一条需求，被 8 行排期漏掉了 —— 核查发现 ModelScreen 仍是
`verticalScroll` 长滚动页。本轮改成默认一屏：细节收进一次只展开一项的展开区，
功能一个不删。同时清掉批次 6 物理删 747 行后留下的 10 个孤儿 import。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r136_verify.py [--selftest]
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
MAIN = APP / "MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"

# 模型页必须保留的能力（防「为了塞进一屏把功能删了」）
MODEL_ANCHORS = [
    "ModelStore.files(c)", "ModelStore.missing(c)", "ModelStore.installedModes(c)",
    "ModelStore.currentMode(c)", "ModelStore.modeFiles(c)", "ModelStore.tokenizer(c)",
    "ModelStore.TOKENIZER", "ModelStore.delete(c, baseFile)",
    "ModelStore.delete(c, f)", "ModelStore.Mode.values()",
    "ModelStore.Mode.VOICE_DESIGN", "Prefs.setModelMode(c, mode.id)",
    "Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)",
    # 精确到四个调用点：泛化前缀会让「摘掉某一处」被另一处掩盖，注入拦不住
    "copyUrl(cnUrl(ModelStore.TOKENIZER))", "copyUrl(hfUrl(ModelStore.TOKENIZER))",
    "copyUrl(cnUrl(mode.file))", "copyUrl(hfUrl(mode.file))",
    "hf-mirror.com", "huggingface.co",
    "onPick", "onLibrary", "onChanged", "st.running", "importing == null",
    "files.size", "missing.joinToString",
]

# 一屏守门
ONE_SCREEN = [
    "var expanded by remember { mutableStateOf<String?>(null) }",
    "fun toggle(key: String)",
    'if (expanded == "base")',
    "val open = expanded == mode.id",
    "Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp))",
]

# 只查本轮范围与现行新增文件；Theme.kt / core/Pipeline.kt 的历史孤儿 import
# 属无关守恒文件，不动也不查（避免误报触发逐字节对拍脚本）
IMPORT_SCOPE = ["MainActivity.kt", "MultiSelect.kt", "PlayerSheet.kt",
                "PlayIcons.kt", "ChapterHub.kt"]


def model_body(main):
    a = main.index("private fun ModelScreen(")
    b = main.find("\n@OptIn(ExperimentalLayoutApi::class)", a)
    return main[a:b if b > 0 else len(main)]


def orphan_imports():
    out = []
    for name in IMPORT_SCOPE:
        s = (APP / name).read_text(encoding="utf-8", errors="replace")
        body = "\n".join(l for l in s.splitlines() if not l.startswith("import "))
        for imp in re.findall(r"^import\s+([\w\.]+)", s, re.M):
            leaf = imp.split(".")[-1]
            if not re.search(r"\b" + re.escape(leaf) + r"\b", body):
                out.append(name + ": " + imp)
    return out


def check():
    e = []
    main = MAIN.read_text(encoding="utf-8", errors="replace")
    ci = CI.read_text(encoding="utf-8", errors="replace")
    mb = model_body(main)

    if "verticalScroll" in mb:
        e.append("模型页仍是长滚动页（用户要求一屏装得下）")
    for s in ONE_SCREEN:
        if s not in mb:
            e.append("一屏布局缺失: " + s[:60])
    for s in MODEL_ANCHORS:
        if s not in mb:
            e.append("模型页功能丢失: " + s[:60])
    for o in orphan_imports():
        e.append("孤儿 import: " + o)
    if "python3 scripts/r136_verify.py --selftest" not in ci:
        e.append("CI 未挂 r136_verify")
    return e


def selftest():
    assert not check(), "基线自己就红了，先修基线"
    original = MAIN.read_text(encoding="utf-8")
    ci_original = CI.read_text(encoding="utf-8")
    cases = [
        ("整页滚动回来", lambda s: s.replace(
            "Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp))",
            "Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp))")),
        ("展开态被摘", lambda s: s.replace(
            "var expanded by remember { mutableStateOf<String?>(null) }", "")),
        ("复制地址功能被摘", lambda s: s.replace('copyUrl(cnUrl(mode.file))', 'Unit')),
        ("删除重试被摘", lambda s: s.replace("ModelStore.delete(c, f)", "Unit")),
        ("切模式被摘", lambda s: s.replace("Prefs.setModelMode(c, mode.id)", "Unit")),
        ("孤儿 import 回来", lambda s: s.replace(
            "import android.content.Context\n",
            "import android.content.Context\nimport android.media.MediaPlayer\n")),
    ]
    bad = 0
    try:
        for name, mutate in cases:
            MAIN.write_text(mutate(original), encoding="utf-8")
            if not check():
                bad += 1
                print("  反向注入漏放:", name)
        MAIN.write_text(original, encoding="utf-8")
        CI.write_text(ci_original.replace(
            "python3 scripts/r136_verify.py --selftest", ""), encoding="utf-8")
        if not check():
            bad += 1
            print("  反向注入漏放: CI 漏挂")
    finally:
        MAIN.write_text(original, encoding="utf-8")
        CI.write_text(ci_original, encoding="utf-8")
    total = len(cases) + 1
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check()
    if e:
        print("R136 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R136 模型页一屏 / 功能 %d 项守恒 / 孤儿 import 0：PASS" % len(MODEL_ANCHORS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
