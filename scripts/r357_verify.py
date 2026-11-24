#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R357 · 播放器展开页重设计 + 抽屉设置/版本号同一行各半宽。

守：
· 播放器：小封面卡 / 细轨实心 thumb / 总时长 / 顶栏队列入口带条数 /
  模式与五档倍速并成等宽小 chip 工具条 / 主控制行三枚等距 /
  上扒跟手与下滑淡出守恒 / 右下浮胶囊与顶栏废话零回潮。
· 抽屉：设置与版本号同一行、各占一半宽；版本号点击用外部浏览器打开
  项目仓库主页，先 resolveActivity、打不开给中文兜底；
  外链图标代码自绘；R375 起 INTERNET 仅用于用户手动开启的本机网关，App 仍零出站、零上传。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r357_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "player": APP / "PlayerSheet.kt",
    "chrome": APP / "AppChrome.kt",
    "manifest": ROOT / "app/src/main/AndroidManifest.xml",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
}

POS = {
    "manifest": ['<uses-permission android:name="android.permission.INTERNET" />'],
    "player": [
        "PlayerCover(palette.primary, 88.dp, st.isPlaying)",
        "IconMusicNote(tint, Modifier.size(coverSize * 0.54f))",
        "SliderDefaults.colors(",
        "thumbColor = palette.primary",
        "inactiveTrackColor = palette.grey.copy(alpha = 0.55f)",
        "Text(mmss(dur), fontSize = 12.sp, color = palette.sub)",
        "播放队列 · ${list.size} 条",
        'Text(if (showList) "收起列表" else "播放队列 · ${list.size} 条",',
        "IconQueue(palette.primary",
        "ModeChip(modeOrd, modeLabel(modeOrd))",
        "Box(Modifier.weight(1f).height(44.dp)",
        "PlayQueue.setSpeed(v)",
        "IconPlayPause(st.isPlaying, palette.card",
        "val chrome = chromeAlpha(pullFraction)",
    ],
    "chrome": [
        "private fun RepoLinkGlyph(tint: Color)",
        "RepoLinkGlyph(primary)",
        "val repo = \"https://github.com/zjh1934/tingben\"",
        "Intent(Intent.ACTION_VIEW, Uri.parse(repo))",
        "Intent.FLAG_ACTIVITY_NEW_TASK",
        "i.resolveActivity(ctx.packageManager)",
        "Toast.makeText(ctx,",
        "Text(\"v\" + com.tingben.app.BuildConfig.VERSION_NAME,",
        "UiIconGlyph(UiIcon.SETTINGS, primary, 18.dp)",
        ".clickable { onSettings() }",
        "horizontalArrangement = Arrangement.spacedBy(8.dp)",
    ],
    "ci": ["python3 scripts/r357_verify.py --selftest"],
    "r135": ['"r357_verify.py"'],
}

BAN = {
    "player": [
        "PlayerCover(palette.primary, 180.dp",
        "摊开的书",
        "bars.forEachIndexed",
        'Text("正在听"',
        "当前播放的音频列表",
        '"-" + mmss(left)',
    ],
    "chrome": [
        'Text("听本 · " + com.tingben.app.BuildConfig.VERSION_NAME',
        'Text("›", fontSize = 16.sp, color = sub)',
    ],
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
    # 两枚等宽按钮：少一枚就说明又退回上下两行
    n = f["chrome"].count("Modifier.weight(1f).height(44.dp)")
    if n != 2:
        e.append("抽屉底部两枚等宽按钮数不对: %d" % n)
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = [
        ("仓库地址被摘", "chrome",
         'val repo = "https://github.com/zjh1934/tingben"', 'val repo = ""'),
        ("版本号不再跳仓库", "chrome",
         "Intent(Intent.ACTION_VIEW, Uri.parse(repo))", "Intent(Intent.ACTION_VIEW)"),
        ("防崩解析被摘", "chrome",
         "i.resolveActivity(ctx.packageManager)", "true"),
        ("外链图标调用被摘", "chrome", "RepoLinkGlyph(primary)", ""),
        ("外链图标定义被摘", "chrome",
         "private fun RepoLinkGlyph(tint: Color)", "private fun NothingHere()"),
        ("版本号按钮被摘", "chrome",
         'Text("v" + com.tingben.app.BuildConfig.VERSION_NAME,', ""),
        ("设置仍可点被摘", "chrome", ".clickable { onSettings() }", ""),
        ("两枚等宽退回一枚", "chrome",
         "Modifier.weight(1f).height(44.dp)", "Modifier.width(120.dp).height(44.dp)"),
        ("网关 INTERNET 权限被摘", "manifest",
         '<uses-permission android:name="android.permission.INTERNET" />', ""),
        ("CI 漏挂", "ci", "python3 scripts/r357_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r357_verify.py"', ""),
        ("经典音符被摘", "player",
         "IconMusicNote(tint, Modifier.size(coverSize * 0.54f))", ""),
        ("细轨被摘", "player", "SliderDefaults.colors(", ""),
        ("倍速能力被摘", "player", "PlayQueue.setSpeed(v)", ""),
        ("手势淡出被摘", "player", "val chrome = chromeAlpha(pullFraction)", ""),
        ("顶栏队列被摘", "player",
         'Text(if (showList) "收起列表" else "播放队列 · ${list.size} 条",', ""),
        ("主播放键被摘", "player", "IconPlayPause(st.isPlaying, palette.card", ""),
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
        print("R357 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R357 播放器重设计 / 抽屉同行双按钮 / 版本号跳仓库：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
