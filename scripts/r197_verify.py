#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R197 · 「制作设置」能力可达性守门。

导航壳重排后，Screen.PRODUCE 的分发 / chromeOf 都还在，211 行 ProduceScreen
里的制作能力（段间静音 / 重试 / 音色缓存摘要 / 从头重做）是唯一实现，
**却没有任何跳转入口** —— 目录页只用
默认 ProduceConfig() 一键开跑，用户根本够不着。R197 在目录页多选底部条
补了「制作设置 ›」入口，本脚本守两件事：

  1. 入口 → 跳转 → 配置页这条链完整，任何一环被摘都报红；
  2. 所有 Screen 枚举值都可达（orphan_values 为空），防止以后又长出
     「能力还在但没人能打开」的死分支。

纯内存注入，不写源码。用法：python3 scripts/r197_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "main": ui_sources.source(APP),
    "hub": APP / "ChapterHub.kt",
    "enum": ROOT / "scripts/screen_enum.py",
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts/r135_verify.py",
}

# 入口 → 跳转 → 页面 → 能力
POS = {
    "hub": [
        'onProduceSetup: (String) -> Unit = {},',
        '"取消全选"',
        'onProduceSetup: (String) -> Unit',
    ],
    "main": [
        'nav.push(Screen.PRODUCE)',
        'Screen.PRODUCE -> ProduceScreen(',
        'private fun ProduceScreen(',
        # R183 / R185：耗时量级与音色缓存摘要是这页独有的提示
        '"每段约 1–3 分钟，块越多等得越久"',
        '"这套音色已做好 ${cacheInfo.current} 段，接着做不用重来"',
        '"从头重做（清掉已做的块）"',
        # R221 · 三档写死预设已整块砍掉，只守「怎么跑」
        'ExpandGroup("② 怎么跑", "")',
        'OptionRow("段间静音"',
        'OptionRow("分段失败重做"',
    ],
    "ci": ["python3 scripts/r197_verify.py --selftest"],
    "r135": ['"r197_verify.py"'],
}


def load():
    return {k: (p.read_text(encoding="utf-8", errors="replace") if p.is_file() else "")
            for k, p in FILES.items()}


def orphans(main):
    """从枚举声明里解析全部值，再看哪些没有 nav.push / screen= / -> 目标。"""
    m = re.search(r"private enum class Screen\s*\{([^}]*)\}", main)
    if not m:
        return ["找不到 Screen 枚举声明"]
    declared = set(re.findall(r"\b[A-Z_]+\b", m.group(1)))
    # 只统计真实跳转/分发目标，chromeOf 与 backTargetOf 的 `->` 也算目的地
    targets = set(re.findall(
        r"(?:nav\.push\(|screen\s*=\s*|->\s*)Screen\.([A-Z_]+)", main))
    out = ["够不着的孤儿页: " + x for x in sorted(declared - targets)]
    # 悬空目标：代码还在跳一个枚举里已经删掉的值，真编译必红
    out += ["跳向不存在的枚举值: " + x for x in sorted(targets - declared)]
    return out


BAN = {
    "main": ['ExpandGroup("② 怎么念"', 'OptionRow("朗读档位"',
             'OptionRow("语速倾向"', 'OptionRow("情绪强度"'],
}

def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("能力链缺失[%s]: %s" % (key, s[:62]))
    for key, banned in BAN.items():
        for s in banned:
            if s in f[key]: e.append("R221 停用三档回潮[%s]: %s" % (key, s[:50]))
    if "Screen.PRODUCE" not in f["main"]:
        e.append("PRODUCE 目的地整个没了")
    # R218 · 常态顶栏收成一枚齿轮（不写文字），勾选态保留文字入口
    # R304 · 制作设置入口右上角已有，底部菜单里一枚都不该有
    n_entry = f["hub"].count('"制作设置 ›"')
    if n_entry != 0:
        e.append("底部菜单不该再有制作设置入口，实为 %d" % n_entry)
    # R226 · 常态齿轮上移系统顶栏（合并视图），勾选态仍留 hub
    if 'Text("⚙", fontSize = 16.sp' not in f["main"]:
        e.append("常态顶栏齿轮入口被摘")
    if "nav.push(Screen.PRODUCE)" not in f["main"]:
        e.append("顶栏齿轮没跳制作设置")
    if "onProduceSetup: (String) -> Unit" not in f["hub"]:
        e.append("制作设置回调签名丢了")
    if "onProduceSetup(book.id)" in f["hub"]:
        e.append("底部菜单旧入口回潮")
    o = orphans(f["main"])
    if o:
        e += o
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    cuts = [
        ("常态齿轮入口整块被摘", "main",
         'Text("⚙", fontSize = 16.sp', ''),
        ("全选 toggle 被摘", "hub", '"取消全选"', '"全选章节"'),
        ("两枚都没传 book.id", "hub", 'onProduceSetup: (String) -> Unit', 'onProduceSetup("")'),
        ("回调签名退化成无参", "hub", 'onProduceSetup: (String) -> Unit = {},',
         'onProduceSetup: () -> Unit = {},'),
        ("跳转被摘", "main", "nav.push(Screen.PRODUCE)", ""),
        ("分发被摘", "main", "Screen.PRODUCE -> ProduceScreen(", ""),
        ("耗时提示被摘", "main", '"每段约 1–3 分钟，块越多等得越久"', ""),
        ("音色缓存摘要被摘", "main", '"这套音色已做好 ${cacheInfo.current} 段，接着做不用重来"', ""),
        ("从头重做被摘", "main", '"从头重做（清掉已做的块）"', ""),
        ("段间静音被摘", "main", 'OptionRow("段间静音"', ""),
        ("停用三档回潮", "main", 'ExpandGroup("② 怎么跑", "")',
         'OptionRow("朗读档位"'),
        ("枚举值被删", "main", "PRODUCE, TASKS", "TASKS"),
        ("CI 漏挂", "ci", "python3 scripts/r197_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r197_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl)
        got = check(x)
        if got:
            print("  反向注入 %-22s 拦下 %d 条" % (name, len(got)))
        else:
            print("  反向注入漏放: " + name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad


def main():
    f = load()
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(f)
    if e:
        print("R197 FAIL %d" % len(e))
        for x in e:
            print(" - " + x)
        return 1
    print("R197 制作设置入口可达 / 配置能力齐全 / 枚举零孤儿：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
