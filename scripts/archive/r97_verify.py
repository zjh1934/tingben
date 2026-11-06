# -*- coding: utf-8 -*-
"""R97 · 调音台文案与控件密度收敛：新形态 / 旧话术零残留 / 守恒 / 反向注入。"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"


def mixer(s):
    return s[s.index("private fun MixerScreen("):]


def literals(s):
    return re.findall(r'"((?:\\.|[^"\\])*)"', s)


MUST = [
    'Text("‹ 返回"', 'Text("差异", fontSize = 11.sp)',
    'Text("提交 ${changes.size} 项"',
    'Text(mode.label, fontSize = 11.sp, color = Sub)',
    'label = { Text("声音描述 · 想要的嗓子"',
    'Text("24kHz 单声道 WAV · 最长 30 秒"',
    'MixerSection("采样参数", "sampling",', 'badge = "9 项"',
    'Text("恢复本组默认"', 'Text("全部恢复默认"',
    'MixerSection("试听", "trial", trialOpen, { trialOpen = it })',
    '.height(34.dp)', '"试听草稿"', '"试听已生效"',
    'trialMsg = if (p != null) "已播放"',
    'Text("丢弃待提交"', 'title = { Text("差异", fontSize = 14.sp) }',
    'horizontal = 4.dp, vertical = 0.dp',
    'MixerCommit.commit(c, draft, n)', 'BackHandler(enabled = dirty)',
    'Text("提交变更", fontSize = 12.sp)', 'mixer_trial_draft.wav', 'mixer_trial_active.wav',
    'ModelStore.Mode.CUSTOM_VOICE -> {', 'ModelStore.Mode.BASE -> {',
    '读取模型自带的音色表', '导入一小段人声', 'ParamCatalog.find(',
]

GONE = [
    "当前模式 · 只读", "一段声音描述 + 9 项采样", "送进模型 · 可随便改",
    "这段描述和下面 9 项采样同时生效", "每次合成会重新采样音色",
    "改动先进入待提交", "9 项真到模型", "✅ 可调", "改完直接提交，不必先试听",
    "试听未提交参数", "试听已生效参数", "丢弃全部待提交", "查看差异",
    "提交不依赖试听", "当前生效 / 待提交差异", "出声了 ✓", "已自动播放",
]


def check(main_src, ci_src):
    e = []
    mb = mixer(main_src)
    # ParamRow 定义在 MixerScreen 之前，数值框修复要查全文
    if 'Modifier.width(72.dp).height(40.dp)' not in main_src:
        e.append("数值框未放宽到 40dp")
    if 'lineHeight = 13.sp' not in main_src:
        e.append("数值框行高未收敛")
    for s in MUST:
        if s not in mb:
            e.append("新形态缺失: " + s[:52])
    ui = literals(mb)
    for g in GONE:
        if any(g in x for x in ui):
            e.append("旧话术回潮: " + g)
    if 'Text("已生效", fontSize = 11.sp)' in mb:
        e.append("顶栏生效态回潮")
    if "TimingHint(" in mb:
        e.append("重复时机提示回潮")
    if "r97_verify.py" not in ci_src:
        e.append("CI 未挂 r97_verify")
    return e


def load():
    return MAIN.read_text(encoding="utf-8"), CI.read_text(encoding="utf-8")


def main():
    m, c = load()
    base_errs = check(m, c)
    if base_errs:
        print("FAIL · 基线未通过，反向注入结果不可信")
        for x in base_errs: print(" -", x)
        return 1
    if "--selftest" in sys.argv:
        cases = [
            ("恢复顶栏状态话术", lambda t: t.replace('Spacer(Modifier.weight(1f))',
                'Text("已生效", fontSize = 11.sp); Spacer(Modifier.weight(1f))')),
            ("恢复时机提示", lambda t: t + "\nTimingHint(TIMING_NOW)\n"),
            ("恢复解释段落", lambda t: t + '\nText("这段描述和下面 9 项采样同时生效、互不覆盖。")\n'),
            ("恢复整宽试听按钮", lambda t: t.replace('.height(34.dp)', '')),
            ("恢复两态 badge", lambda t: t.replace(
                'MixerSection("试听", "trial", trialOpen, { trialOpen = it })',
                'MixerSection("试听", "trial", trialOpen, { trialOpen = it }, badge = "两态")')),
            ("恢复字节数回执", lambda t: t.replace('trialMsg = if (p != null) "已播放"',
                'trialMsg = "${File(p).length()} 字节，已自动播放"')),
            ("删唯一提交", lambda t: t.replace("MixerCommit.commit(c, draft, n)", "0L")),
            ("删离开确认", lambda t: t.replace("BackHandler(enabled = dirty)", "BackHandler(enabled = false)")),
            ("删数值框修复", lambda t: t.replace('Modifier.width(72.dp).height(40.dp)',
                                           'Modifier.width(72.dp).height(38.dp)')),
            ("CI 摘掉本验证", None),
        ]
        bad = 0
        for name, fn in cases:
            mm, cc = (fn(m), c) if fn else (m, "")
            if not check(mm, cc):
                bad += 1
                print("反向注入未拦下:", name)
        print("反向注入 %d/%d 拦下" % (len(cases) - bad, len(cases)))
        return 1 if bad else 0
    errs = check(m, c)
    if errs:
        print("FAIL")
        for x in errs:
            print(" -", x)
        return 1
    print("OK · R97 正向全过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
