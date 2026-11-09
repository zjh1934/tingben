#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R206 · MainActivity 分家读取层守门。

守五件事：
  1. 合并视图 == 拆分前 MainActivity.kt（逐字节，业务零改动）
  2. 磁盘主文件/片段顶层声明一律 internal（Kotlin 跨文件可见）
  3. private <-> internal 往返不丢字节，类内缩进 private 绝不动
  4. 67 个历史守门全部走 ui_sources，不许有人退回直读单文件
  5. 写回分发不污染主文件

用法：python3 scripts/r206_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, re, shutil, sys, tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources as u

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / u.UI
MAIN = APP / u.MAIN
SCRIPTS = ROOT / "scripts"
SELF = SCRIPTS / "r206_verify.py"
DECLS = ["private enum class Screen", "private fun App(", "private fun CardBox(",
         "private fun LibraryScreen(", "private fun SettingsScreen(",
         "private fun ModelScreen(", "private fun MixerDialog(",
         "private fun ResumeAskDialog(", "private fun ProduceScreen("]


def files():
    return {
        "main": MAIN.read_text(encoding="utf-8", errors="replace"),
        "self": SELF.read_text(encoding="utf-8", errors="replace"),
        "ui_sources": (SCRIPTS / "ui_sources.py").read_text(encoding="utf-8", errors="replace"),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
    }


def parts_text():
    return {n: (APP / n).read_text(encoding="utf-8", errors="replace")
            for n, _, _ in u.PARTS}


def check(f, parts):
    e = []
    view = u.read(ROOT)
    for d in DECLS:
        if d not in view:
            e.append("合并视图缺顶层声明: " + d[:40])
    # 磁盘顶层 private 必须清零（有片段时）
    if u.PARTS:
        for name, t in [("主文件", f["main"])] + list(parts.items()):
            for l in t.splitlines():
                if l.startswith("private ") and ("fun " in l or "val " in l or "class " in l):
                    e.append("磁盘 %s 仍有顶层 private: %s" % (name, l[:44]))
                    break
    # 往返一致
    if u._to_view(u._to_disk(view)) != view:
        e.append("private<->internal 往返丢了字节")
    # 类内缩进 private 不动
    a = sum(1 for l in view.splitlines() if l.startswith("    private "))
    b = sum(1 for l in u._to_disk(view).splitlines() if l.startswith("    private "))
    if a != b or a == 0:
        e.append("类内 private 被误改 / 样本为空")
    for name, _, _ in u.PARTS:
        marker = u.INSERT + name[:-3]
        if f["main"].count(marker) != 1:
            e.append("主文件占位行异常: " + name)
        body = parts.get(name, "")
        if body.count(u.PART) != 1:
            e.append("片段缺 @ui-part: " + name)
    for q in sorted(APP.glob("*.kt")):
        t = q.read_text(encoding="utf-8", errors="replace")
        if u.PART in t and q.name not in {n for n, _, _ in u.PARTS}:
            e.append("片段文件没登记进 PARTS: " + q.name)
    for name, _, _ in u.PARTS:
        if '"' + name + '"' not in f["ui_sources"]:
            e.append("PARTS 漏登记: " + name)
    # 接线全覆盖：任何脚本都不许再直接 / "MainActivity.kt"
    stragglers = []
    for p in sorted(SCRIPTS.glob("*.py")):
        if p.name in ("ui_sources.py", "r206_verify.py"):
            continue
        t = p.read_text(encoding="utf-8", errors="replace")
        for l in t.splitlines():
            if re.search(r'/\s*["\']MainActivity\.kt|/\s*["\']app/src/main/java/com/tingben/app/MainActivity\.kt', l):
                stragglers.append(p.name)
                break
    if stragglers:
        e.append("脚本退回直读单文件: " + ", ".join(stragglers[:6]))
    if "def _to_view(" not in f["ui_sources"] or "def _to_disk(" not in f["ui_sources"]:
        e.append("读取层缺可见性双向变换")
    if "python3 scripts/r206_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r206")
    if '"r206_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r206")
    return e


def writeback_case():
    errs = []
    with tempfile.TemporaryDirectory() as d:
        r = pathlib.Path(d)
        dst = r / u.UI
        dst.mkdir(parents=True, exist_ok=True)
        for q in APP.glob("*.kt"):
            shutil.copy2(q, dst / q.name)
        merged = u.read(r)
        assert merged == u.read(ROOT), "临时树合并视图与真源码不一致"
        for name, start, nxt in u.PARTS:
            base = u.read(r)
            i = base.find(start)
            j = len(base) if nxt == u.END else base.find(nxt, i + len(start))
            seg = base[i:j]
            # R208 · 注入锚点取「片段内第一行缩进代码」，绝不碰 start/nxt 字面量：
            # 9 块里有些只有一个函数，改函数声明会让 write_text 切不动。
            m = re.search(r"^(    \S.*)$", seg, re.M)
            if not m:
                errs.append("区间内找不到缩进锚点: " + name)
                continue
            needle, repl = m.group(1), m.group(1) + " // R208INJ"
            inj = base[:i] + seg.replace(needle, repl, 1) + base[j:]
            u.source(r).write_text(inj)
            after = (dst / name).read_text(encoding="utf-8")
            if "R208INJ" not in after:
                errs.append("注入没落片段: " + name)
            if "R208INJ" in (dst / u.MAIN).read_text(encoding="utf-8"):
                errs.append("写回把片段正文灌进主文件: " + name)
            u.source(r).write_text(base)
            if u.read(r) != merged:
                errs.append("还原后合并视图不一致: " + name)
    return errs


def selftest():
    f = files()
    parts = parts_text()
    assert not check(f, parts), "基线自己就红了"
    assert not writeback_case(), "写回分发自检没过"
    cuts = []
    for name, _, _ in u.PARTS:
        sym = name[:-3]
        cuts.append(("占位行被摘·" + sym, "main", u.INSERT + sym, ""))
        cuts.append(("哨兵被摘·" + sym, "part:" + name, u.PART, ""))
    cuts += [
        ("可见性变换被删", "ui_sources", "def _to_view(", "def unused_view("),
        ("主文件变回 private", "main", "internal fun App(", "private fun App("),
        # R208 · 每块都要有「漏登记即红」的用例，随 PARTS 动态生成
    ]
    for name, _, _ in u.PARTS:
        cuts.append(("PARTS 漏登记·" + name[:-3], "ui_sources",
                     '    ("' + name + '",', '    ("' + name + 'X",'))
    cuts += [
        ("CI 漏挂", "ci", "python3 scripts/r206_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r206_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        gp = dict(parts)
        if key.startswith("part:"):
            nm = key.split(":", 1)[1]
            gp[nm] = parts[nm].replace(needle, repl, 1)
        else:
            g[key] = f[key].replace(needle, repl, 1)
        got = check(g, gp)
        if got:
            print("  反向注入 %-24s 拦下 %d 条" % (name, len(got)))
        else:
            print("  反向注入漏放: " + name)
            bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check(files(), parts_text())
    if e:
        print("R206 FAIL", len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R206 合并视图完整 / 可见性 / 接线全覆盖 / 写回不污染：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
