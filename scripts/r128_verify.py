# -*- coding: utf-8 -*-
"""R128 · 分段看台状态重建验证。

真机反馈：章视角看台里，文本已切好的「灰格」（未合成段）在页面切出去再回来后
整片消失，只剩已出声的绿格，之后随合成一块一块冒出来；但续跑顺序一直是对的。

根因：同一页面 segs 有两个互斥数据源——
  chapterRows（音频树，只含已落盘小块） vs fallbackSegs（计划段全量，含灰占位），
而 treeChapter 由 LaunchedEffect 异步赋值，页面生命周期内发生切换。
音频树里没有「还没合成」的段，拿它当列表来源，灰格必然不存在。

本验证守：计划段做唯一骨架、音频树只补 wav、真值源不再把段砍到 60。
沙盒无 Android SDK，静态绿 ≠ 编译过；真编译由 CI 负责。
用法：python3 scripts/r128_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": APP / "MainActivity.kt",
    "work": APP / "core" / "Workbench.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

# R195 · 退役：旧版独立进度页看台实现的专属锚点已随死页面删净
# 现行能力由 r151 / r132 / check_structure 按符号守
POS = {
    "main": [
    ],
    "work": [
        "return mapped.map { m ->",
        "val upTo = last.coerceAtMost(2000)",
    ],
    "ci": ["python3 scripts/r128_verify.py --selftest"],
}

BAN = {
    "main": [
        "if (chapter != null && chapterRows != null) chapterRows else",
        "val chapterRows = treeChapter?.segments?.map",
        "SegRow(s.seq - 1, s.seq - 1, 0, null, s.file",
    ],
    "work": [
        "mapped.take(60)",
        "coerceAtMost(59)",
    ],
}


def load():
    # R195 · 看台搬进 ChapterHub，锚点按符号在全 UI 源码里找
    return {k: p.read_text(encoding="utf-8", errors="replace")
                       for k, p in FILES.items()}


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
        # R195 · 独立进度页看台标题随死页面删净，此例退役
        "segmap 真值源恢复 60 上限": ("work", "return mapped.map { m ->"),
        "兜底恢复 59 上限": ("work", "val upTo = last.coerceAtMost(2000)"),
    }
    bad = 0
    for name, (k, needle) in cuts.items():
        x = dict(f)
        x[k] = f[k].replace(needle, "/* removed */", 1)
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
        print("R128 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R128 看台段骨架 正向 %d 条 / 旧形态 %d 条：PASS" %
          (sum(len(v) for v in POS.values()), sum(len(v) for v in BAN.values())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
