# -*- coding: utf-8 -*-
"""本地改码轮验证：14 条交互改动 / 旧形态零残留 / 主链守恒 / 反向注入。

不假装证明真机手感；沙盒无 Android SDK，静态绿 ≠ 编译过。
用法：python3 scripts/r121_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "chrome": APP / "AppChrome.kt",
    "hub": APP / "ChapterHub.kt",
    "prefs": APP / "core/Prefs.kt",
    "param": APP / "core/ParamSpec.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "main": [
        "ActivityResultContracts.OpenMultipleDocuments()",
        "App 不联网：复制地址 → 浏览器下载 → 回来一次勾选 4 个文件导入。",
        "private fun InputReadScreen(",
        "NAV_INPUT",
        "if (tasks.isNotEmpty()) {",
        "items(books, key = { it.id }) { b ->",
        "collapsible = false",
        "Prefs.setTrialText(c, trialText)",
        'Text("保存试听文本"',
        'Text("试听 · 已保存"',
        'Text("试听 · 当前"',
        'Text("停止", fontSize = 12.sp)',
        "Modifier.padding(start = 32.dp, end = 24.dp)",
        'trial("已保存", active, savedTrialText, "mixer_trial_saved.wav")',
        'trial("未保存", draft, trialText, "mixer_trial_draft.wav")',
        "NativeEngine.ttsSynthesize(h, text",
    ],
    "chrome": [
        "internal const val NAV_INPUT = 6",
        'NAV_INPUT to "输入朗读"',
    ],
    "prefs": [
        "DEFAULT_TRIAL_TEXT",
        "fun trialText(c: Context): String",
        "fun setTrialText(c: Context, v: String)",
    ],
    "param": [
        "min = 64.0, max = 8192.0",
        "min = 0.0, max = 100.0",
        "O8 官方生成参数与范围",
    ],
    "hub": [
        "snapshotFlow { Pair(hScroll.isScrollInProgress, hScroll.value) }",
        "LaunchedEffect(pagePx)",
        "savedCol",
    ],
    "ci": ["python3 scripts/r121_verify.py --selftest"],
}

BAN = {
    "main": [
        "粘贴文字建书", "还没有任务", "还没有书", "去准备 ›",
        'Text("正文字号"', 'SettingsSection("诊断与试音"',
        'SettingsSection("后台与关于"', "var useActive",
        "重新导入某个文件 ›", 'trial("草稿"', 'trial("已生效"',
        "用当前草稿", "用已生效的设置",
    ],
    "param": ["min = 256.0", "max = 1000.0"],
}

KEEP = {
    "main": [
        "fun commitAll()", "ModeTabs(mode, installed)",
        "ParamCatalog.SAMPLING.forEach", "Screen.MIXER -> MixerScreen",
        "ModelStore.import(ctx, name, s)", "SettingsSection(\"通知与权限\"",
    ],
    "chrome": ['NAV_MIXER to "调音台"', 'NAV_MODELS to "模型"'],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:56]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:56]))
    for key, needles in KEEP.items():
        for s in needles:
            if s not in f[key]:
                e.append("守恒丢失[%s]: %s" % (key, s[:56]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cases = {
        "任务页空态回潮": lambda x: dict(x, main=x["main"].replace(
            "if (tasks.isNotEmpty()) {", "if (tasks.isEmpty()) {\nText(\"还没有任务\")")),
        "书架页空态回潮": lambda x: dict(x, main=x["main"] + '\n粘贴文字建书 ›\n'),
        "设置页恢复折叠": lambda x: dict(x, main=x["main"].replace(
            "collapsible = false", "collapsible = true")),
        "诊断与试音回潮": lambda x: dict(x, main=x["main"] + '\nSettingsSection("诊断与试音", "按需", diagOpen) {\n}\n'),
        "后台与关于回潮": lambda x: dict(x, main=x["main"] + '\nSettingsSection("后台与关于", "系统", backOpen) {\n}\n'),
        "试听文本不保存": lambda x: dict(x, main=x["main"].replace(
            "Prefs.setTrialText(c, trialText)", "0L")),
        "两枚试听被合并": lambda x: dict(x, main=x["main"].replace(
            'Text("试听 · 当前"', 'Text("试听"')),
        "抽屉删掉输入朗读": lambda x: dict(x, chrome=x["chrome"].replace(
            '    NAV_INPUT to "输入朗读",\n', "")),
        "模型退回单选": lambda x: dict(x, main=x["main"].replace(
            "ActivityResultContracts.OpenMultipleDocuments()",
            "ActivityResultContracts.OpenDocument()")),
        "采样范围退回": lambda x: dict(x, param=x["param"].replace(
            "min = 64.0, max = 8192.0", "min = 256.0, max = 8192.0")),
        "章节页吸附被删": lambda x: dict(x, hub=x["hub"].replace(
            "snapshotFlow { Pair(hScroll.isScrollInProgress, hScroll.value) }", "")),
        "滑条回到整宽": lambda x: dict(x, main=x["main"].replace(
            "Modifier.padding(start = 32.dp, end = 24.dp)", "Modifier")),
    }
    bad = 0
    for name, fn in cases.items():
        if not check(fn(f)):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cases) - bad, len(cases)))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R121 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R121 正向 %d / 零残留 %d / 守恒 %d 全过" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values()),
           sum(len(v) for v in KEEP.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
