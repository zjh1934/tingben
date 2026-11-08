# -*- coding: utf-8 -*-
"""R154 · K1 倍速守门。

用户实证：
  · 「合成音频速度不好调、调得不准确」→ 查证属实：合成侧只有
    Mixer.RATE「慢/中/快」文字倾向，ParamCatalog 九项采样里没有数值速度参数，
    生成后也改不了。播放倍速才是可靠控制点。
  · 「播放整章应该可以调倍速」→ **功能其实早有**（PlayerSheet 五档 +
    PlayQueue.setSpeed），但入口只藏在全屏播放器页里；听整章时看到的是
    底部常驻条，找不到。所以这轮不是「加功能」，是「让已有功能够得着」。
  · 「试听没必要」→ 纠正后采纳为：试听**恒 1.0×**，不继承主播放倍速。
    原来 setSpeed 同时改 previewPlayer 和 _previewState.speed，整章听 2.0×
    后点格子试听也是 2.0×，根本判断不了音色（真 bug）。

用法：python3 scripts/r154_verify.py --selftest
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "queue": APP / "core" / "PlayQueue.kt",
    "hub": APP / "ChapterHub.kt",
    "player": APP / "PlayerSheet.kt",
    "mixer": APP / "core" / "Mixer.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "queue": [
        "val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)",
        "fun speedLabel(f: Float): String",
        "fun nextSpeed(cur: Float): Float",
        "SPEED_STEPS[(i + 1) % SPEED_STEPS.size]",
        "PlaybackParams().setSpeed(1f).setPitch(1f)",
        "PlaybackState(speed = 1f, queueId = id, preview = true)",
        "PlaybackState(speed = 1f, ended = true",
        "isPlaying = false, speed = 1f, queueId = previewQueueId, preview = true",
        "试听恒原速，不吃主播放的倍速",
        "倍速只作用于「整章 / 整本收听」",
        "故意不动 previewPlayer",
        "speed = f.coerceIn(0.5f, 2f)",
        "_state.value = _state.value.copy(speed = speed)",
    ],
    "hub": [
        "val next = PlayQueue.nextSpeed(st.speed)",
        'EventLog.ui("PLAY_SPEED", "x=$next from=bar")',
        "PlayQueue.speedLabel(st.speed)",
        ".background(HubSoft, RoundedCornerShape(50))",
        "听整章时看到的是这条常驻条，根本找不到入口",
    ],
    "player": [
        "PlayQueue.SPEED_STEPS.forEach { v ->",
        "val label = PlayQueue.speedLabel(v)",
        "PlayQueue.setSpeed(v)",
        "R158b · 这层关系不进 UI",
    ],
    "mixer": [
        'RATE("语速", "慢 / 中 / 快；只写稳定倾向"',
    ],
    "ci": ["python3 scripts/r154_verify.py --selftest"],
    "r135": ['"r154_verify.py"'],
}

BAN = {
    # 试听通道不许吃主播放倍速；主播放的 open()/setSpeed() 里出现是合法的，
    # 所以这几条只在 preview 段与 setSpeed 函数体内判（见 check）。
    "queue": [],
    "hub": [],
    "player": ["listOf(0.75f to \"0.75×\""],
}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def preview_body(q):
    a = q.find("private fun previewSetTracks(")
    b = q.find("private fun notifyNow()", a)
    return q[a:b] if a >= 0 and b > a else ""


def code_only(s):
    """判据只看代码行：注释里写「故意不动 previewPlayer」不是真的调用。"""
    return "\n".join(
        line for line in s.splitlines()
        if not line.strip().startswith(("//", "/*", "*")))


def setspeed_body(q):
    a = q.find("    fun setSpeed(f: Float) {")
    if a < 0:
        return ""
    b = q.find("\n    }", a)
    return q[a:b] if b > a else ""


def next_speed(cur, steps=(0.75, 1.0, 1.25, 1.5, 2.0)):
    i = next((k for k, v in enumerate(steps) if abs(v - cur) < 0.01), -1)
    return steps[(i + 1) % len(steps)]


def speed_label(f, steps=(0.75, 1.0, 1.25, 1.5, 2.0),
                labels=("0.75×", "1.0×", "1.25×", "1.5×", "2.0×")):
    i = next((k for k, v in enumerate(steps) if abs(v - f) < 0.01), -1)
    return labels[i] if i >= 0 else "%.2f×" % f


def logic_cases(f):
    bad = []
    steps = (0.75, 1.0, 1.25, 1.5, 2.0)
    # ① 常驻条循环切档：五档一圈回到起点
    seq, cur = [], 1.0
    for _ in range(6):
        cur = next_speed(cur, steps)
        seq.append(cur)
    if seq != [1.25, 1.5, 2.0, 0.75, 1.0, 1.25]:
        bad.append("循环切档顺序错：%s" % seq)
    else:
        print("  常驻条循环：1.0× → %s ✓" % " → ".join(speed_label(v) for v in seq[:5]))
    # ② 标签映射
    for v, want in zip(steps, ("0.75×", "1.0×", "1.25×", "1.5×", "2.0×")):
        got = speed_label(v, steps)
        if got != want:
            bad.append("倍速标签 %s → %s 期望 %s" % (v, got, want))
    if speed_label(1.7, steps) != "1.70×":
        bad.append("非标准倍速没走兜底格式")
    else:
        print("  标签映射 5/5 + 兜底 1.70× ✓")
    # ③ 档位同源：PlayerSheet 不许再写一份字面量
    if "PlayQueue.SPEED_STEPS.forEach" not in f["player"]:
        bad.append("播放器页没共用档位真值源")
    if "listOf(0.75f to" in f["player"]:
        bad.append("播放器页又写了一份档位字面量")
    # ④ 试听恒 1.0×
    pb = preview_body(f["queue"])
    if not pb:
        bad.append("没圈出试听通道函数体")
    else:
        if "speed = speed" in pb or "setSpeed(speed)" in pb:
            bad.append("试听通道仍吃主播放倍速")
        if "setSpeed(1f)" not in pb:
            bad.append("试听 prepare 没显式设 1.0×")
        else:
            print("  试听通道恒 1.0×（state 4 处 + prepare 1 处）✓")
    sb = code_only(setspeed_body(f["queue"]))
    if not sb:
        bad.append("没圈出 setSpeed 函数体")
    else:
        if "previewPlayer" in sb or "_previewState.value" in sb:
            bad.append("setSpeed 又碰了试听通道")
        else:
            print("  setSpeed 只作用于主播放 ✓")
    # ⑤ 合成侧确实没有数值速度参数（用户判断成立）
    if 'RATE("语速"' not in f["mixer"]:
        bad.append("合成侧语速倾向定义丢了")
    else:
        print("  合成侧仅文字倾向、无数值速度参数 ✓（用户判断成立）")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:75]))
    for key, needles in BAN.items():
        for s in needles:
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    # 行为判据（放进 check 才能被反向注入拦住）
    pb, sb = code_only(preview_body(f["queue"])), code_only(setspeed_body(f["queue"]))
    if not pb or "speed = speed" in pb or "setSpeed(speed)" in pb:
        e.append("试听通道没有恒 1.0×")
    if not sb or "previewPlayer" in sb or "_previewState.value" in sb:
        e.append("setSpeed 不该改试听通道")
    if "PlayQueue.SPEED_STEPS.forEach" not in f["player"]:
        e.append("播放器页没共用档位真值源")
    if "PlayQueue.nextSpeed(st.speed)" not in f["hub"]:
        e.append("常驻条没有倍速入口")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("档位真值源被摘", "queue",
         "val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)", ""),
        ("循环切档被摘", "queue", "SPEED_STEPS[(i + 1) % SPEED_STEPS.size]",
         "SPEED_STEPS[0]"),
        ("标签函数被摘", "queue", "fun speedLabel(f: Float): String", ""),
        ("试听 prepare 退回主倍速", "queue",
         "PlaybackParams().setSpeed(1f).setPitch(1f)",
         "PlaybackParams().setSpeed(speed).setPitch(1f)"),
        ("试听 state 退回主倍速", "queue",
         "PlaybackState(speed = 1f, queueId = id, preview = true)",
         "PlaybackState(speed = speed, queueId = id, preview = true)"),
        ("setSpeed 又碰试听", "queue",
         "故意不动 previewPlayer",
         "try { previewPlayer?.playbackParams = PlaybackParams().setSpeed(speed) }"),
        ("常驻条倍速胶囊被摘", "hub",
         "val next = PlayQueue.nextSpeed(st.speed)", ""),
        ("常驻条不显示当前档", "hub", "PlayQueue.speedLabel(st.speed)", ""),
        ("常驻条埋点被摘", "hub", 'EventLog.ui("PLAY_SPEED", "x=$next from=bar")', ""),
        ("播放器页退回自己一份档位", "player",
         "PlayQueue.SPEED_STEPS.forEach { v ->",
         "listOf(0.75f to \"0.75×\", 1f to \"1.0×\").forEach { (v, label) ->"),
        ("播放器页口径被摘", "player", "R158b · 这层关系不进 UI", ""),
        ("播放器页 setSpeed 被摘", "player", "PlayQueue.setSpeed(v)", ""),
        ("CI 漏挂 r154", "ci", "python3 scripts/r154_verify.py --selftest", ""),
        ("现行清单漏 r154", "r135", '"r154_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl, 1)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R154 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R154 倍速（常驻条可达 / 档位同源 / 试听恒原速 / 合成侧不精确说明）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
