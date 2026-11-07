# -*- coding: utf-8 -*-
"""R76 · 幽灵页与无意义项清理验证。
正向：Screen 9 值 / 抽屉 6 项 / 角色页与角色 UI 零残留 / 主链守恒。
R77 订正：角色表已从底层物理删除（不再作为守恒项），
旧 WAV 兼容改由「双指纹迁移」承担；相应的零残留断言同步换成新形态。
反向注入：旧形态塞回去必须拦下。
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/tingben/app"
NL = chr(10)
MAIN = (KT / "MainActivity.kt").read_text(encoding="utf-8")
CHROME = (KT / "AppChrome.kt").read_text(encoding="utf-8")
ALLKT = NL.join(p.read_text(encoding="utf-8") for p in KT.rglob("*.kt"))

WANT = {"LIBRARY", "PRODUCE", "PROGRESS", "TASKS", "AUDIO",
        "SETTINGS", "MODELS", "MIXER", "CHAPTERS"}

BAN_MAIN = ["Screen.CAST", "NAV_CAST", "CastScreen(", "角色音色", "角色预设",
            "角色映射", "给角色单独指定音色", "改动立即保存 · 对所有新任务生效",
            "confirmDeleteId", "editCastId", "gridTargetId", "castTick",
            "unbound", "MIXER_CAST", "onGoCast", "onCast"]
BAN_CHROME = ["NAV_CAST", "角色音色"]

BAN_ALLKT = ["VoiceCast.", "CastPresets.", "Prefs.castEnabled", "setCastEnabled",
             "VOICE_UNMAPPED", "KEY_CAST_ENABLED"]

KEEP = ["Prefs.legacyVoiceStamp(c)",
        "old != voiceStamp && old != legacyStamp",
        "VoiceProfile.buildWithReport(globalVoice,",
        'HubTitles = listOf("章节", "合成", "可播")',
        'val steps = listOf("拆章", "生成声音", "整理音频", "完成")',
        "commitAll()", "DismissibleNavigationDrawer", "PlayQueue",
        "run(c, book, onlyChapters, true, thinkOverride, config)"]

def check(main=None, chrome=None, allkt=None):
    m = MAIN if main is None else main
    ch = CHROME if chrome is None else chrome
    ak = ALLKT if allkt is None else allkt
    e = []
    em = re.search(r"private enum class Screen \{([^}]*)\}", m)
    got = set(re.findall(r"[A-Z_]+", em.group(1))) if em else set()
    if got != WANT:
        e.append("Screen 不是 9 值: " + str(got ^ WANT))
    navs = re.findall(r"NAV_\w+ = \d", ch)
    if len(navs) != 6:
        e.append("抽屉不是 6 项: %d" % len(navs))
    for s in BAN_MAIN:
        if s in m:
            e.append("幽灵页/角色 UI 残留: " + s)
    for s in BAN_CHROME:
        if s in ch:
            e.append("抽屉旧项残留: " + s)
    for s in BAN_ALLKT:
        if s in ak:
            e.append("底层角色表残留: " + s)
    for s in KEEP:
        if s not in ak:
            e.append("守恒项丢失: " + s)
    if e:
        raise AssertionError("; ".join(e))
    return len(WANT), len(BAN_MAIN) + len(BAN_CHROME)

def selftest():
    caught = 0
    INJ = [
        ("CAST 枚举回潮", lambda: check(main=MAIN.replace(
            "MODELS, MIXER, CHAPTERS }", "MODELS, CAST, MIXER, CHAPTERS }"))),
        ("抽屉旧项回潮", lambda: check(chrome=CHROME.replace(
            '    NAV_MODELS to "模型",',
            '    NAV_MODELS to "模型",' + NL + '    NAV_CAST to "角色音色",'))),
        ("角色页回潮", lambda: check(main=MAIN + NL + "private fun CastScreen() {}" + NL)),
        ("角色预设回潮", lambda: check(main=MAIN + NL + 'MixerSection("角色预设")' + NL)),
        ("评审腔话术回潮", lambda: check(main=MAIN + NL +
            'Text("改动立即保存 · 对所有新任务生效 · 正在跑的任务不受影响")' + NL)),
        ("角色表回潮", lambda: check(allkt=ALLKT + NL + "VoiceCast.hash(c)" + NL)),
        ("删掉双指纹迁移", lambda: check(allkt=ALLKT.replace(
            "old != voiceStamp && old != legacyStamp", "@@BROKEN@@"))),
        ("删掉直接朗读入口", lambda: check(allkt=ALLKT.replace(
            "run(c, book, onlyChapters, true, thinkOverride, config)", "@@BROKEN@@"))),
        ("抽屉变成 7 项", lambda: check(chrome=CHROME + NL + "internal const val NAV_EXTRA = 6" + NL)),
    ]
    for name, fn in INJ:
        try:
            fn()
        except AssertionError:
            caught += 1
        else:
            raise AssertionError("[自检] 注入后没拦下：" + name)
    print("selftest ok · 反向注入 %d/%d 全拦下" % (caught, len(INJ)))

if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
    else:
        a, b = check()
        print("r76_verify ok · Screen %d 值 · 抽屉 6 项 · %d 类旧形态零残留 · 守恒 %d 项全在"
              % (a, b, len(KEEP)))
