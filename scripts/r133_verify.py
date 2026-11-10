# -*- coding: utf-8 -*-
"""R133 · 统一播放器 / 两态隔离 / 真图标验证。

守：底部常驻条点开全屏播放器页 / 通知点击回跳展开 / 三处共用 PlayQueue.state /
小段 WAV 与调音台试听不占常驻条、不进播放器页、不进通知栏 /
播放控制全部是代码绘制图标而非字符 / 选章只有一份。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r133_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "queue": SRC / "core/PlayQueue.kt",
    "notif": SRC / "core/PlayNotif.kt",
    "main": ui_sources.source(SRC),
    "hub": SRC / "ChapterHub.kt",
    "player": SRC / "PlayerSheet.kt",
    "icons": SRC / "PlayIcons.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

POS = {
    "queue": [
        "val preview: Boolean = false",
        'private fun isPreview(id: String): Boolean = id == "seg" || id == "trial"',
        "if (s.preview) { clearNotif(); return }",
        "preview = isPreview(id)",
        "preview = isPreview(queueId)",
        "preview = isPreview(q)",
    ],
    "notif": [
        'if (s.label.isEmpty() || s.preview) { cancel(c); return }',
        '.putExtra("open_player", true)',
    ],
    "main": [
        'i.getBooleanExtra("open_player", false)',
        '"|player:"',
        'raw == "player:" -> { playerOpen = true }',
        "var playerOpen by rememberSaveable { mutableStateOf(false) }",
        "onExpand = { playerOpen = true",
        "if (playerOpen) {",
        "PlayerSheet(ctx, miniBook ?: bookId,",
    ],
    "hub": [
        "if (st.preview) return",
        "onRead: () -> Unit",
        ".clickable { onExpand() }",
        "IconSkip(false, HubPrimary",
        "IconSkip(true, HubPrimary",
        "IconPlayPause(st.isPlaying, palette.onPrimary",
    ],
    "player": [
        "internal fun PlayerSheet(",
        "val st by PlayQueue.state.collectAsState()",
        "LaunchedEffect(st.preview) { if (st.preview) onClose() }",
        "PlayQueue.tick()",
        "PlayQueue.seekTo(",
        "PlayQueue.setSpeed(v)",
        "IconPlayPause(st.isPlaying, palette.card",
        "IconSkip(false, palette.primary",
        "IconSkip(true, palette.primary",
        "IconClose(palette.sub",
        "IconChevronDown(palette.primary",
        "IconQueue(palette.primary",
        'PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)',
    ],
    "icons": [
        "internal fun IconPlayPause(",
        "internal fun IconSkip(",
        "internal fun IconClose(",
        "internal fun IconChevronDown(",
        "internal fun IconQueue(",
        "drawPath(",
        "drawLine(",
        "StrokeCap.Round",
    ],
    "ci": ["python3 scripts/r133_verify.py --selftest"],
}

BAN = {
    # 播放控制不许退回字符图标（制作任务的停止/继续按钮不在此列，语义不同）
    "hub": [
        'Text("‹", fontSize = 16.sp)',
        'Text(if (st.isPlaying) "⏸" else "▶", fontSize = 14.sp',
        'Text("›", fontSize = 16.sp)',
        "var showList by remember { mutableStateOf(false) }",
    ],
    "player": [
        'Text("⏸"',
        'Text("▶"',
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:66]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态残留[%s]: %s" % (key, s[:66]))
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "preview 字段被摘": ("queue", "val preview: Boolean = false"),
        "两态判定被摘": ("queue", 'private fun isPreview(id: String): Boolean = id == "seg" || id == "trial"'),
        "通知不再跳过试听": ("queue", "if (s.preview) { clearNotif(); return }"),
        "setTracks 不标 preview": ("queue", "preview = isPreview(id)"),
        "通知层不拦 preview": ("notif", 'if (s.label.isEmpty() || s.preview) { cancel(c); return }'),
        "通知点击不回跳": ("notif", '.putExtra("open_player", true)'),
        "通知意图不解析": ("main", 'i.getBooleanExtra("open_player", false)'),
        "player 分支被摘": ("main", 'raw == "player:" -> { playerOpen = true }'),
        "播放器浮层被摘": ("main", "if (playerOpen) {"),
        "常驻条不展开": ("main", "onExpand = { playerOpen = true"),
        "常驻条不隔离试听": ("hub", "if (st.preview) return"),
        "条幅不可点": ("hub", ".clickable { onExpand() }"),
        "上一首退回字符": ("hub", "IconSkip(false, HubPrimary"),
        # I2 · 图标前景改成随主题取色（深色下白字在浅紫主色底上只有 1.99:1）。
        # 这里守「仍是代码绘制图标且随主题取色」，不守写死的白字。
        "播放键退回字符": ("hub", "IconPlayPause(st.isPlaying, palette.onPrimary"),
        "播放器页不跟状态": ("player", "val st by PlayQueue.state.collectAsState()"),
        "播放器页不拦试听": ("player", "LaunchedEffect(st.preview) { if (st.preview) onClose() }"),
        "进度不能拖": ("player", "PlayQueue.seekTo("),
        "倍速被摘": ("player", "PlayQueue.setSpeed(v)"),
        "选章没搬进播放器页": ("player", 'PlayQueue.playFrom(list.indexOfFirst { it.first == idx }, id = q)'),
        "图标库缺绘制": ("icons", "drawPath("),
        "播放图标被摘": ("icons", "internal fun IconPlayPause("),
    }
    bad = 0
    for name, (k, needle) in cuts.items():
        x = dict(f)
        x[k] = f[k].replace(needle, "/* removed */")
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    for k, olds in BAN.items():
        for old in olds:
            x = dict(f)
            x[k] = f[k] + chr(10) + "// " + old + chr(10)
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
        print("R133 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R133 播放器统一 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
