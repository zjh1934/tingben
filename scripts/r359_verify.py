#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R359 · 播放器封面换成经典双八分音符（♫）。

守：
· PlayIcons.IconMusicNote 一处定义：两个实心符头 + 两根符干 + 一条斜横梁，
  纯 Canvas 自绘、随主题取色、不引图标库 / 不加资源文件。
· PlayerCover 改为柔底圆 + 居中音符，撤掉 R262「书页 + 声波」自绘图形；
  播放态只改柔底深浅，展开页 88.dp / 收起条 44.dp 两处调用守恒。
· 旧绘法（摊开的书 / 页上的字行 / bars 声波 / 180.dp 大封面）零回潮。
· 不新增权限、不改手势与播放能力；判据挂 CI 全套与 r135 清单。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r359_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "player": APP / "PlayerSheet.kt",
    "icons": APP / "PlayIcons.kt",
    "hub": APP / "ChapterHub.kt",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
    "r357": ROOT / "scripts/r357_verify.py",
}

POS = {
    "manifest": ['<uses-permission android:name="android.permission.INTERNET" />'],
    "player": [
        "R359 · 播放器封面换成经典双八分音符",
        "internal fun PlayerCover(tint: Color, coverSize: Dp, playing: Boolean = false)",
        "Box(Modifier.size(coverSize), contentAlignment = Alignment.Center)",
        "Canvas(Modifier.matchParentSize())",
        "if (playing) 0.14f else 0.10f",
        "IconMusicNote(tint, Modifier.size(coverSize * 0.54f))",
        "PlayerCover(palette.primary, 88.dp, st.isPlaying)",
    ],
    "icons": [
        "import androidx.compose.ui.geometry.Size",
        "internal fun IconMusicNote(tint: Color, modifier: Modifier = Modifier)",
        "val stem = (w * 0.062f)",
        "val beam = (w * 0.105f)",
        "drawOval(tint, topLeft = Offset(w * 0.16f, h * 0.62f)",
        "drawOval(tint, topLeft = Offset(w * 0.50f, h * 0.54f)",
        "size = Size(w * 0.26f, h * 0.18f)",
        "drawLine(tint, Offset(w * 0.40f, h * 0.20f), Offset(w * 0.74f, h * 0.12f)",
    ],
    "hub": ["PlayerCover(HubPrimary, 44.dp, st.isPlaying)"],
    "ci": ["python3 scripts/r359_verify.py --selftest"],
    "r135": ['"r359_verify.py"'],
}

BAN = {
    "player": [
        "摊开的书",
        "页上的字行",
        "bars.forEachIndexed",
        "书页 + 声波",
        "PlayerCover(palette.primary, 180.dp",
    ],
    "icons": ["摊开的书", "书页 + 声波"],
    "manifest": ["android.permission.READ_SMS"],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:64]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:64]))
    # 音符只能有一处定义，避免又各自画一套
    n = f["icons"].count("internal fun IconMusicNote(")
    if n != 1:
        e.append("IconMusicNote 定义数不对: %d" % n)
    # r357 判据须同步迁移：正向锚 + 反向注入各一处，少一处说明旧判据没跟上
    if f["r357"].count("IconMusicNote(tint, Modifier.size(coverSize * 0.54f))") < 2:
        e.append("r357 未迁移到经典音符判据（正向锚 / 反向注入缺一）")
    # 88dp / 44dp 两处调用都在，少一处就是撤了某一屏的封面
    if f["player"].count("PlayerCover(palette.primary, 88.dp") != 1 or \
            f["hub"].count("PlayerCover(HubPrimary, 44.dp") != 1:
        e.append("展开页 / 收起条封面调用不守恒")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = [
        ("音符图标定义被摘", "icons",
         "internal fun IconMusicNote(tint: Color, modifier: Modifier = Modifier)",
         "internal fun NothingHere(tint: Color, modifier: Modifier = Modifier)"),
        ("符头被摘", "icons",
         "drawOval(tint, topLeft = Offset(w * 0.16f, h * 0.62f)", ""),
        ("第二枚符头被摘", "icons",
         "drawOval(tint, topLeft = Offset(w * 0.50f, h * 0.54f)", ""),
        ("横梁被摘", "icons",
         "drawLine(tint, Offset(w * 0.40f, h * 0.20f), Offset(w * 0.74f, h * 0.12f)", ""),
        ("Size 导入被摘", "icons", "import androidx.compose.ui.geometry.Size", ""),
        ("封面不再用音符", "player",
         "IconMusicNote(tint, Modifier.size(coverSize * 0.54f))", ""),
        ("柔底圆被摘", "player", "Canvas(Modifier.matchParentSize())", ""),
        ("播放态深浅被摘", "player", "if (playing) 0.14f else 0.10f", "0.10f"),
        ("展开页封面被摘", "player",
         "PlayerCover(palette.primary, 88.dp, st.isPlaying)", ""),
        ("收起条封面被摘", "hub",
         "PlayerCover(HubPrimary, 44.dp, st.isPlaying)", ""),
        ("180dp 大封面回潮", "player",
         "PlayerCover(palette.primary, 88.dp, st.isPlaying)",
         "PlayerCover(palette.primary, 180.dp, st.isPlaying)"),
        ("CI 漏挂", "ci", "python3 scripts/r359_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r359_verify.py"', ""),
        ("r357 未迁移", "r357",
         "IconMusicNote(tint, Modifier.size(coverSize * 0.54f))", ""),
        ("网关 INTERNET 权限被摘", "manifest",
         '<uses-permission android:name="android.permission.INTERNET" />', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl, 1)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for k, olds in BAN.items():
        for old in olds:
            x = dict(f)
            x[k] = f[k] + "\n// " + old + "\n"
            if not check(x):
                bad += 1
                print("  旧形态注入漏放:", old[:44])
    total = len(cuts) + sum(len(v) for v in BAN.values())
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(load())
    if e:
        print("R359 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R359 播放器封面 = 经典双八分音符 / 旧书页声波零回潮：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
