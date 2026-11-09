# -*- coding: utf-8 -*-
"""R136 · 模型页一屏装得下 / 孤儿 import 零残留 验证。

R170 事故收口：原来 selftest 反复原地覆写 157KB 的 MainActivity.kt，靠 finally
恢复。一次沙盒 502 把进程砍在循环中途，finally 没跑，源码里留下一行 `Unit`
（切模式调用被替换），下一轮全套直接假红。现在 selftest 全程只在内存里造变体，
**一个字节都不写源文件**，从根上杜绝「自检把代码改坏」。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r136_verify.py [--selftest]
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
MAIN = APP / "MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"

MODEL_ANCHORS = [
    "ModelStore.files(c)", "ModelStore.missing(c)", "ModelStore.installedModes(c)",
    "ModelStore.currentMode(c)", "ModelStore.modeFiles(c)", "ModelStore.tokenizer(c)",
    "ModelStore.TOKENIZER", "ModelStore.delete(c, baseFile)",
    "ModelStore.delete(c, f)", "ModelStore.Mode.values()",
    "ModelStore.Mode.VOICE_DESIGN", "Prefs.setModelMode(c, mode.id)",
    "Prefs.setModelMode(c, ModelStore.Mode.VOICE_DESIGN.id)",
    "copyUrl(cnUrl(ModelStore.TOKENIZER))", "copyUrl(hfUrl(ModelStore.TOKENIZER))",
    "copyUrl(cnUrl(mode.file))", "copyUrl(hfUrl(mode.file))",
    "hf-mirror.com", "huggingface.co",
    "onPick", "onLibrary", "onChanged", "st.running", "importing == null",
    "files.size", "missing.joinToString",
]

ONE_SCREEN = [
    "var expanded by remember { mutableStateOf<String?>(null) }",
    "fun toggle(key: String)",
    'if (expanded == "base")',
    "val open = expanded == mode.id",
    "Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp))",
]

IMPORT_SCOPE = ["MainActivity.kt", "MultiSelect.kt", "PlayerSheet.kt",
                "PlayIcons.kt", "ChapterHub.kt"]


def read_sources():
    return {n: (APP / n).read_text(encoding="utf-8", errors="replace")
            for n in IMPORT_SCOPE}


def model_body(main):
    a = main.index("private fun ModelScreen(")
    b = main.find("\n@OptIn(ExperimentalLayoutApi::class)", a)
    return main[a:b if b > 0 else len(main)]


def orphan_imports(sources):
    out = []
    for name in IMPORT_SCOPE:
        s = sources[name]
        body = "\n".join(l for l in s.splitlines() if not l.startswith("import "))
        for imp in re.findall(r"^import\s+([\w\.]+)", s, re.M):
            leaf = imp.split(".")[-1]
            if not re.search(r"\b" + re.escape(leaf) + r"\b", body):
                out.append(name + ": " + imp)
    return out


def check(main=None, ci=None, sources=None):
    e = []
    src = sources if sources is not None else read_sources()
    main = main if main is not None else src["MainActivity.kt"]
    ci = ci if ci is not None else CI.read_text(encoding="utf-8", errors="replace")
    mb = model_body(main)

    if "verticalScroll" in mb:
        e.append("模型页仍是长滚动页（用户要求一屏装得下）")
    for s in ONE_SCREEN:
        if s not in mb:
            e.append("一屏布局缺失: " + s[:60])
    for s in MODEL_ANCHORS:
        if s not in mb:
            e.append("模型页功能丢失: " + s[:60])
    for o in orphan_imports(src):
        e.append("孤儿 import: " + o)
    if "python3 scripts/r136_verify.py --selftest" not in ci:
        e.append("CI 未挂 r136_verify")
    return e


def selftest():
    # R170 · 全程内存变体，绝不写源文件。
    base = read_sources()
    orig = base["MainActivity.kt"]
    ci_orig = CI.read_text(encoding="utf-8")
    assert not check(sources=base, ci=ci_orig), "基线自己就红了，先修基线"

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
    for name, mutate in cases:
        variant = dict(base)
        variant["MainActivity.kt"] = mutate(orig)
        assert variant["MainActivity.kt"] != orig, "注入没生效: " + name
        if not check(sources=variant, ci=ci_orig):
            bad += 1
            print("  反向注入漏放:", name)
    if not check(sources=base,
                 ci=ci_orig.replace("python3 scripts/r136_verify.py --selftest", "")):
        bad += 1
        print("  反向注入漏放: CI 漏挂")

    total = len(cases) + 1
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (total - bad, total))
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
