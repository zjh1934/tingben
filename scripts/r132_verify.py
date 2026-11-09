# -*- coding: utf-8 -*-
"""R132 · 3c 收尾验证：章级操作进第三列 + 导航收敛 8 值。

守：第三列就地承接拼章 / 另存 WAV / 转 M4A / 删除（含确认框）/
删除后 localTick 触发重读 / 不在组合期同步读盘 /
Screen 枚举删掉 AUDIO 与 TASK_DETAIL / 分发与 chromeOf 无旧页分支 /
历史脚本的枚举期望同步降到 8 值。

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r132_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, sys
import screen_enum as se

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": ui_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "chrome": APP / "AppChrome.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "structure": ROOT / "scripts/check_structure.py",
}

POS = {
    "main": [
        "private enum class Screen {",
        "R132：任务详情页 / 我的音频页的独立入口与目的地都撤了",
    ],
    "hub": [
        "import androidx.activity.compose.rememberLauncherForActivityResult",
        "import androidx.activity.result.contract.ActivityResultContracts",
        "import androidx.compose.foundation.Canvas",
        "import java.io.File",
        "var multiMode by remember(book.id) { mutableStateOf(false) }",
        "var segChecked by remember(book.id) { mutableStateOf(setOf<Int>()) }",
        "MultiSelectBar(",
        "SelectBox(",
        "var localTick by remember { mutableStateOf(0) }",
        "LaunchedEffect(book.id, refresh, diskKey, localTick)",
        "localTick++",
        "fun filesOf(i: Int): Pair<List<File>, File?>",
        "拼成整章",
        "另存 WAV",
        "转 M4A",
        "删除选中",
        "BackHandler(enabled = multiMode) { exitMulti() }",
        "if (multiMode) {",
        "confirmChDel = selected.associateWith { filesOf(it) }",
        "confirmChDel?.let { all ->",
        "val (segs, merged) = files",
        "AudioOps.deleteAll(segs)",
        "AudioOps.deleteMerged(c, it)",
        "AudioTree.clearDone(c, book.id, i)",
        "localTick++",
    ],
    "structure": ['WANT_SCREENS = se.declared(main_src)',
                  'se.consistency(main_src)'],
    "ci": ["python3 scripts/r132_verify.py --selftest"],
}

BAN = {
    "main": [
        "Screen.AUDIO",
        "Screen.TASK_DETAIL",
        "Screen.TASK_DETAIL -> TaskDetailScreen",
        "Screen.AUDIO -> AudioScreen",
        'Screen.AUDIO    -> ChromeSpec("我的音频"',
    ],
    "hub": [
        # 删除对话框不许回到「组合期直接读盘并解构」的旧形态
        "val i = confirmDel!!",
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
    # R195 · 个数随声明走，实质判据交给三处一致 + 退役零回潮
    if len(se.declared(f["main"])) < 6:
        e.append("Screen 枚举解析失败")
    e += se.consistency(f["main"])
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = {
        "章级操作面板被摘": ("hub", "拼成整章"),
        "删除入口被摘": ("hub", "删除选中"),
        "删除确认被摘": ("hub", "AudioOps.deleteAll(segs)"),
        "操作后不重读": ("hub", "localTick++"),
        "勾选态返回退出被摘": ("hub", "BackHandler(enabled = multiMode) { exitMulti() }"),
        "多选态被摘": ("hub", "var multiMode by remember(book.id) { mutableStateOf(false) }"),
        "勾选方块被摘": ("hub", "SelectBox("),
        "底部条被摘": ("hub", "MultiSelectBar("),
        "重读键被摘": ("hub", "LaunchedEffect(book.id, refresh, diskKey, localTick)"),
        "导出被摘": ("hub", "另存 WAV"),
        "转格式被摘": ("hub", "转 M4A"),
        "索引清理被摘": ("hub", "AudioTree.clearDone(c, book.id, i)"),
        "退役值被塞回": ("main", "private enum class Screen { LIBRARY, PRODUCE, TASKS, SETTINGS, MODELS, CHAPTERS }"),
        "结构脚本集合不跟": ("structure", 'WANT_SCREENS = se.declared(main_src)'),
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
            x[k] = f[k] + chr(10) + "// " + old.replace(chr(10), " ") + chr(10)
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
        print("R132 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R132 3c 收尾 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
