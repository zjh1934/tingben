# -*- coding: utf-8 -*-
"""R135 · 已退役页面的统一守门。

批次 6 把从 App() 不可达的 13 个死代码块物理删除了（旧「我的音频」页、
旧任务详情页，以及一批历史 UI 孤儿组件）。原先有十几个脚本专门验证这些页面，
验证对象已不存在 —— 继续让它们逐条对旧字符串，只会制造假红和拖慢每轮自检。

本模块把这些脚本统一改造成「防回潮」：只守一件事 —— 旧页符号不许再出现在可达源码里。
现行能力的守恒由 R131 / R132 / R133 / R134 / R135 五个现行验证承担。

用法（各脚本只留三行）：
    from retired_pages import run
    if __name__ == "__main__": sys.exit(run("R79"))
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys
import screen_enum as se

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

# 从 App() 做传递闭包算出的 13 个不可达顶层声明，以及旧页的特征串。
# 任何一个回来都说明有人把废弃 UI 又接回了主链。
DEAD_SYMBOLS = [
    "private fun AudioScreen(",
    "private fun TaskDetailScreen(",
    "private data class TaskDetailData(",
    "private fun buildTaskDetail(",
    "private fun playChapterAudio(",
    "private fun fmtTime(",
    "private data class AudioMenu(",
    "private fun GroupLabel(",
    "private fun SettingOption(",
    "private fun SettingSwitchRow(",
    "private fun SettingInfoRow(",
    "private fun DiagnosticsBlock(",
    "private fun SoundProbeBlock(",
]
DEAD_TRAITS = [
    'item(key = "audio_tree_summary")',
    'AudioMenu(2, s.bookId',
    'Text("▶ 整章"',
    'Text("第 ${i + 1} 章 · 音频操作"',  # 旧弹菜单标题；第三列已改为底部操作条
]

# 现行能力必须还在：防止「为了清死代码把活代码一起删了」
LIVE_SYMBOLS = [
    "private fun App(",
    "fun openBook(id: String?)",
    "internal fun ChapterHubScreen(",   # 在 ChapterHub.kt
    "internal fun PlayerSheet(",        # 在 PlayerSheet.kt
    "internal fun MiniPlayBar(",        # 在 ChapterHub.kt
    "internal fun SelectBox(",          # 在 MultiSelect.kt
    "object PlayQueue",                 # 在 core/PlayQueue.kt
]


def sources():
    out = {}
    for p in APP.rglob("*.kt"):
        out[p.name] = p.read_text(encoding="utf-8", errors="replace")
    # R206 · MainActivity 已按页分家，判据必须看合并视图而非骨架主文件
    out["MainActivity.kt"] = ui_sources.read(ROOT)
    return out


def check(files):
    errs = []
    main = files.get("MainActivity.kt", "")
    allkt = "\n".join(files.values())
    for s in DEAD_SYMBOLS:
        if s in allkt:
            errs.append("退役页符号回潮: " + s[:52])
    for s in DEAD_TRAITS:
        if s in allkt:
            errs.append("旧页特征串回潮: " + s[:52])
    for s in LIVE_SYMBOLS:
        if s not in allkt:
            errs.append("现行能力丢失: " + s[:52])
    # R195 · 不再锚整行字面量，按符号解析 + 退役零回潮
    if len(se.declared(main)) < 6:
        errs.append("导航枚举解析失败")
    errs += se.consistency(main)
    return errs


def run(name):
    files = sources()
    if "--selftest" in sys.argv:
        # 注入一个退役符号，守门必须拦下；注入现行能力缺失，也必须拦下
        bad = 0
        x = dict(files)
        x["MainActivity.kt"] = files["MainActivity.kt"] + "\n// private fun AudioScreen(\n"
        if not check(x):
            bad += 1
            print("  反向注入漏放: 旧页符号回潮")
        y = dict(files)
        y["PlayerSheet.kt"] = files.get("PlayerSheet.kt", "").replace(
            "internal fun PlayerSheet(", "internal fun PlayerSheetX(")
        if not check(y):
            bad += 1
            print("  反向注入漏放: 现行能力被删")
        print("反向注入 %d/2 全拦下" % (2 - bad))
        return 1 if bad else 0
    errs = check(files)
    if errs:
        print("%s FAIL %d" % (name, len(errs)))
        for e in errs:
            print(" -", e)
        return 1
    print("%s 已退役（验证对象于批次 6 物理删除）· 旧页零回潮 / 现行能力守恒：PASS" % name)
    return 0


def main():
    """批次 E：17 个旧页壳脚本原先在 CI 各起一次进程跑同一个 check()，
    语义完全重复。收敛成这一项，仍带反向注入，且覆盖全部退役符号。"""
    files = sources()
    errs = check(files)
    if errs:
        print("RETIRED FAIL %d" % len(errs))
        for e in errs:
            print(" -", e)
        return 1
    print("退役页统一守门：旧页符号 %d 项零回潮 / 旧特征串 %d 项零回潮 / "
          "现行能力 %d 项全在 / 导航枚举自洽" %
          (len(DEAD_SYMBOLS), len(DEAD_TRAITS), len(LIVE_SYMBOLS)))
    if "--selftest" in sys.argv:
        bad = 0
        x = dict(files)
        x["MainActivity.kt"] = files["MainActivity.kt"] + "\n// private fun AudioScreen(\n"
        if not check(x):
            bad += 1; print("  反向注入漏放: 旧页符号回潮")
        y = dict(files)
        y["PlayerSheet.kt"] = files.get("PlayerSheet.kt", "").replace(
            "internal fun PlayerSheet(", "internal fun PlayerSheetX(")
        if not check(y):
            bad += 1; print("  反向注入漏放: 现行能力被删")
        z = dict(files)
        z["MainActivity.kt"] = files["MainActivity.kt"].replace(
            "private enum class Screen {",
            "private enum class Screen { LIBRARY, AUDIO }")
        if not check(z):
            bad += 1; print("  反向注入漏放: 导航被改回 10 值")
        print("反向注入 %d/3 全拦下" % (3 - bad))
        return 1 if bad else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
