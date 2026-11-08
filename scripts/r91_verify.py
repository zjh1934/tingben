# -*- coding: utf-8 -*-
"""R91 · 分段看台交互定案验证：格子即播放开关 / 独立播放键与解释文字零残留 / 守恒 / 反向注入。

只守交互结构与文案口径，不守画法。
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"
INDEX = ROOT / "真机截图/INDEX.md"

def check(files):
    e = []
    m, ci, idx = files["main"], files["ci"], files["index"]

    for s in [
        # R134：格子从 clickable 换成点击试听 / 长按进多选的手势
        '.pointerInput(sg.index, segMulti, segChecked,',
        'if (playing) PlayQueue.pause()',
        'else if (onThis) PlayQueue.resume()',
        'PlayQueue.setTracks(listOf(Track(wav, label, id = segId)), "seg")',
        'PlayQueue.playFrom(0, id = "seg")',
        'val playing = onThis && pqSeg.isPlaying',
        'val onThis = playable && pqSeg.trackId == segId',
    ]:
        if s not in m:
            e.append("格子两态缺失: " + s[:48])

    BAN = [
        'segSel',
        '点格子看正文',
        '绿=已出声',
        '送进声音模型的正文',
        '这次还没落盘分段记录',
        '（这一段没有落盘文字）',
        'Text(if (playing) "⏸" else "▶", fontSize = 13.sp',
        '· 第 ${sg.block + 1} 块',
    ]
    for s in BAN:
        if s in m:
            e.append("旧形态残留: " + s[:40])

    KEEP = [
        'Text("分段看台 · ${segs.size} 段"',
        'segs.chunked(8)',
        'val pqSeg by PlayQueue.state.collectAsState()',
        'val steps = listOf("拆章", "生成声音", "整理音频", "完成")',
    ]
    for s in KEEP:
        if s not in m:
            e.append("守恒丢失: " + s[:48])

    if '点格子即播 / 再点停' not in idx:
        e.append("索引没记新交互")
    if '看台 8 格展开正文' in idx:
        e.append("索引仍写展开正文")
    if 'r91_verify.py' not in ci:
        e.append("CI 未挂 r91_verify")
    return e

def txt(p):
    return p.read_text(encoding="utf-8", errors="replace")

def main():
    files = {"main": txt(MAIN), "ci": txt(CI), "index": txt(INDEX)}
    errs = check(files)
    if "--selftest" in sys.argv:
        injections = [
            ("退回选中展开态",
             lambda t: dict(t, main=t["main"].replace(
                 'var showCompare by remember { mutableStateOf(false) }',
                 'var segSel by remember { mutableStateOf<Int?>(null) }\n    var showCompare by remember { mutableStateOf(false) }'))),
            ("恢复独立播放键",
             lambda t: dict(t, main=t["main"].replace(
                 '        Spacer(Modifier.height(18.dp))',
                 '        Text(if (playing) "⏸" else "▶", fontSize = 13.sp)\n        Spacer(Modifier.height(18.dp))'))),
            ("恢复图例解释文字",
             lambda t: dict(t, main=t["main"].replace(
                 '            if (segs.isEmpty()) {',
                 '            Text("绿=已出声 · 蓝=正在合成 · 灰=未开始 · 点格子看正文")\n            if (segs.isEmpty()) {'))),
            ("恢复展开正文标题",
             lambda t: dict(t, main=t["main"].replace(
                 '            if (segs.isEmpty()) {',
                 '            Text("第 1 段 · 送进声音模型的正文")\n            if (segs.isEmpty()) {'))),
            ("格子手势不再带播放与勾选态",
             lambda t: dict(t, main=t["main"].replace(
                 '.pointerInput(sg.index, segMulti, segChecked,', '.pointerInput(Unit) {'))),
            ("再点变成重播而非停",
             lambda t: dict(t, main=t["main"].replace(
                 'if (playing) PlayQueue.pause()', 'if (playing) PlayQueue.playFrom(0)'))),
            ("丢掉播放状态来源",
             lambda t: dict(t, main=t["main"].replace(
                 'val pqSeg by PlayQueue.state.collectAsState()', ''))),
            ("删掉看台板块",
             lambda t: dict(t, main=t["main"].replace(
                 'Text("分段看台 · ${segs.size} 段"', 'Text("进度"'))),
            ("索引退回旧口径",
             lambda t: dict(t, index=t["index"].replace('点格子即播 / 再点停', '看台 8 格展开正文'))),
            ("CI 漏挂验证",
             lambda t: dict(t, ci=t["ci"].replace('r91_verify.py', 'r91_missing.py'))),
        ]
        bad = 0
        for name, mutate in injections:
            if not check(mutate(files)):
                bad += 1
                errs.append("反向注入漏放: " + name)
        print("反向注入 %d/%d 全拦下" % (len(injections) - bad, len(injections)))
    if errs:
        print("R91 FAIL", len(errs))
        [print(" -", x) for x in errs]
        return 1
    print("R91 格子即播停 / 独立播放键零残留 / 解释文字零残留 / 守恒：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
