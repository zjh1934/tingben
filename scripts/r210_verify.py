#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R210 · 补 R209 的遗漏 + 日志去噪 + 守恒地基修正。

日志 /data/inputs/tingben_diag_20261110_050933.md（1.1.22，464 行）：
  ① R209 只堵了目录页「继续」按钮；05:09:35 正显示着续跑询问弹层，
     弹层主按钮与任务看板「继续制作」都走 MainActivity.resumeTask，照样静默。
  ② RESUME_ASK_SKIP reason=no_pending 刷了 20+ 条，R169 只节流了 decided。
  ③ R208 把「拆分前 canon 哈希」写死进 r46，任何正常业务改动都会永久假红。

用法：python3 scripts/r210_verify.py [--selftest]
"""
from __future__ import annotations
import pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
A = ROOT / "app/src/main/java/com/tingben/app"

def files():
    return {
        "main": ui_sources.read(ROOT),
        "hub": (A / "ChapterHub.kt").read_text(encoding="utf-8", errors="replace"),
        "plan": (A / "core/ChapterPlan.kt").read_text(encoding="utf-8", errors="replace"),
        "r46": (SCRIPTS / "r46_verify.py").read_text(encoding="utf-8", errors="replace"),
        "self": (SCRIPTS / "r210_verify.py").read_text(encoding="utf-8", errors="replace"),
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8", errors="replace"),
        "r135": (SCRIPTS / "r135_verify.py").read_text(encoding="utf-8", errors="replace"),
    }

def check(f):
    e = []
    # ① 收尾判断必须在 resumeTask 里、且排在 start 之前；目录页那枚按钮的双保险也要在
    rt = f["main"]
    i = rt.find("val resumeTask:")
    j = rt.find("val chrome = chromeOf(screen)", i)
    block = rt[i:j] if i >= 0 and j > i else ""
    if not block:
        e.append("resumeTask 区块定位不到")
    else:
        for k in ["Producer.isBusyForBook(t.bookId)", "RESUME_BLOCKED_BUSY", "稍等再继续"]:
            if k not in block:
                e.append("resumeTask 缺收尾反馈: " + k)
        # 注释里也会出现 "Producer.start"，必须认实际调用串，否则顺序判断被注释干扰
        a, b = block.find("Producer.isBusyForBook"), block.find("Producer.start(ctx, b,")
        if a < 0 or b < 0 or a > b:
            e.append("收尾判断没排在 start 之前")
    # ChapterHub 里这个调用串有两处（重新分章守卫 + 续跑按钮），
    # 认 R209 特有的记账符号才精确；那一处由 r209 守门独立把守。
    # R226 · 双保险随按钮上移系统顶栏，main 已是合并视图
    if "HUB_RESUME_BLOCKED" not in f["main"]:
        e.append("目录页那枚「继续」的双保险被摘")
    # ② no_pending 按 reason 变化才记，且不能顶掉 R169 的 decided 节流
    for k in ["var lastSkipReason by remember", 'lastSkipReason != "no_pending"',
              'lastSkipReason = "no_pending"', "lastSkipReason = \"\""]:
        if k not in f["main"]:
            e.append("no_pending 去噪缺: " + k[:40])
    if "resumeSkipLogged" not in f["main"] or "if (!resumeSkipLogged)" not in f["main"]:
        e.append("R169 的 decided 节流被顶掉")
    # ③ r46 不再拿写死哈希卡业务代码
    if "BASELINE_CANON" in f["r46"]:
        e.append("r46 又写死业务代码哈希，正常改动会永久假红")
    # ④ 章节表写前目录防御 + 线索（不猜根因、不删文件、不重试）
    if "file(c, bookId).parentFile?.mkdirs()" not in f["plan"]:
        e.append("章节表写前目录防御被摘")
    for k in ["目录存在=", "可写=", "章数="]:
        if k not in f["plan"]:
            e.append("写盘失败线索缺: " + k)
    if "AtomicJson.write" not in f["plan"]:
        e.append("ChapterPlan 不再走 AtomicJson")
    # 登记
    if "python3 scripts/r210_verify.py --selftest" not in f["ci"]:
        e.append("CI 漏挂 r210")
    if '"r210_verify.py"' not in f["r135"]:
        e.append("现行清单漏登记 r210")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("下沉判断被摘", "main", "Producer.isBusyForBook(t.bookId)", "false"),
        ("被拦不记账", "main", "RESUME_BLOCKED_BUSY", "RESUME_BLOCKED"),
        ("被拦不给反馈", "main", "稍等再继续", ""),
        ("目录页双保险被摘", "main", "HUB_RESUME_BLOCKED", "HUB_RESUME"),
        ("no_pending 节流被摘", "main", 'lastSkipReason != "no_pending"', "true"),
        ("R169 节流被顶掉", "main", "if (!resumeSkipLogged)", "if (true)"),
        ("r46 哈希守恒回潮", "r46", "STRICT：逐字节守恒", "BASELINE_CANON = \"x\"\n    STRICT：逐字节守恒"),
        ("目录防御被摘", "plan", "file(c, bookId).parentFile?.mkdirs()", ""),
        ("写盘线索被摘", "plan", "可写=", ""),
        ("CI 漏挂", "ci", "python3 scripts/r210_verify.py --selftest", ""),
        ("现行清单漏登记", "r135", '"r210_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f)
        g[key] = f[key].replace(needle, repl, 1)
        got = check(g)
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
    e = check(files())
    if e:
        print("R210 FAIL", len(e))
        for x in e: print(" -", x)
        return 1
    print("R210 续跑全入口收尾反馈 / 日志去噪 / 守恒地基：PASS")
    return 0

if __name__ == "__main__":
    sys.exit(main())
