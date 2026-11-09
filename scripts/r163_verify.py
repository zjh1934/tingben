# -*- coding: utf-8 -*-
"""R163 · 目录页三列并一列验证（语义判据唯一真源）。

用户实证：17:18 一分钟内三列来回切十几次，「像在不同卡片里切来切去」。
R163 把章节 / 合成 / 可播三列合并成书内一个 LazyColumn；原来 20 个脚本
各自绑死三列 UI 字面量的判据，统一收敛到 `hub_semantics.py` 的
「能力还在不在」，不再逐个哄脚本。

守：
  · 单列：三列 / 分页 / 列跳转零回潮
  · 章行：点进阅读、长按进多选、勾选框只在多选态、听 / 展开 / 更多
  · 章级：拼整章 / 另存 WAV / 转 M4A / 删音频（删列不丢能力）
  · 小片：居中、惰性行、点播再点停、长按多选、批量试听与删除
  · 顶部：双进度、停止/继续、可播汇总、顺序播放、队列就地管理

用法：python3 scripts/r163_verify.py [--selftest]
沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import hub_semantics as hub

ROOT = pathlib.Path(__file__).resolve().parents[1]
HUB = ROOT / "app/src/main/java/com/tingben/app/ChapterHub.kt"
MAIN = ui_sources.source(ROOT)


def texts():
    return (HUB.read_text(encoding="utf-8", errors="replace"),
            MAIN.read_text(encoding="utf-8", errors="replace"))


def run():
    h, m = texts()
    errs = hub.checks(h, m)
    if errs:
        print("R163 FAIL", len(errs))
        for x in errs:
            print(" -", x)
        return 1
    print("R163 单列目录 / 章级能力守恒 / 小片惰性居中 / 三列零回潮：PASS")
    return 0


def selftest():
    h, m = texts()
    base = hub.checks(h, m)
    if base:
        print("基线自己就红了，先修基线：")
        for x in base:
            print(" -", x)
        return 1
    inj = hub.injections()
    bad = 0
    for name, key, mutate in inj:
        hh, mm = (mutate(h), m) if key == "hub" else (h, mutate(m))
        if hh == h and mm == m:
            print("  注入锚点失效:", name)
            bad += 1
        elif not hub.checks(hh, mm):
            print("  反向注入漏放:", name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(inj) - bad, len(inj)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv else run())
