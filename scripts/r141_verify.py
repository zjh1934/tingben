# -*- coding: utf-8 -*-
"""R141 · 批次 E「日志导出 / 自检去摩擦 / 退役脚本收敛 / 清单定稿」验证。

四件落地：
  E1 设置页导出诊断日志（崩溃栈 + 环形缓冲 + 事件/详细日志 + 设备版本），
     走系统文件选择器、IO 线程、显式隐私提示
  E2 r40/r46 的 git HEAD 逐字节对拍降级为语义守恒（TINGBEN_STRICT=1 才做），
     去掉「每改一行必须先本地 commit 才绿」；r135 轻量清单换成现行 5 项
  E3 CI 里 17 个旧页壳脚本（每个都跑同一个 check()）收敛成 1 项统一守门，
     CI 挂项 62 → 46
  E4 真机待验清单定稿 26 项，分组带「怎么验 / 什么算过」

用法：python3 scripts/r141_verify.py [--selftest]
"""
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "eventlog": APP / "diag" / "EventLog.kt",
    "main": APP / "MainActivity.kt",
    "r40": ROOT / "scripts/r40_verify.py",
    "r46": ROOT / "scripts/r46_verify.py",
    "r135": ROOT / "scripts/r135_verify.py",
    "retired": ROOT / "scripts/retired_pages.py",
    "ci": ROOT / ".github/workflows/ci.yml",
    "audit": ROOT / "docs/修复清单_按优先级.md",
}

POS = {
    # R142 语义化：日志从「给用户看的 txt 诊断文件」改成「给开发者排查的
    # 完整 Markdown + 一键复制摘要」，锚点跟着换，守的行为没变少。
    "eventlog": [
        "fun exportBundle(c: Context): String",
        "fun exportSummary(c: Context): String",
        "## 环境", "## 运行快照", "事件日志（全量）", "详细日志（全量）",
        "崩溃前事件（环形缓冲）", "给开发者排查用的",
        "Build.VERSION.RELEASE", "Build.SUPPORTED_ABIS",
    ],
    "main": [
        'ActivityResultContracts.CreateDocument("text/markdown")',
        "EventLog.exportBundle(c)", "EventLog.exportSummary(c)",
        "withContext(Dispatchers.IO)", "diagClipboard.setText(AnnotatedString(txt))",
        "exportLauncher.launch(name)", '"开发日志"',
        '"一键复制摘要"', '"导出完整 .md"',
        "可能含书名和正文片段",
        "UiErrors.storage(\"诊断日志没导出来",
    ],
    "r40": [
        "语义守恒（不再要求 git HEAD 逐字节一致）",
        "TINGBEN_STRICT",
        "壳层结构不变量",
        "private fun App(",
    ],
    "r46": [
        "TINGBEN_STRICT",
        "语义守恒：正向",
        "逐字节对拍仅 TINGBEN_STRICT=1",
    ],
    "r135": [
        '"python3 scripts/retired_pages.py --selftest"',
        '"python3 scripts/r140_verify.py --selftest"',
        '"python3 scripts/r139_verify.py --selftest"',
    ],
    "retired": [
        "def main():", "if __name__ == \"__main__\":",
        "退役页统一守门：", "反向注入 %d/3 全拦下",
    ],
    "ci": [
        "python3 scripts/retired_pages.py --selftest",
        "python3 scripts/r141_verify.py --selftest",
        "17 个重复壳脚本收敛为 1 项",
    ],
    "audit": [
        "真机待验清单（定稿 · 26 项",
        "### A · 制作链（A/B/C 批次，8 项）",
        "### B · 界面与手感（8 项）",
        "### C · 稳定性、权限与两态（7 项）",
        "### D · 构建、数据与发版（3 项）",
        "什么算过",
        "静态自检全绿、CI 编译过，都不等于",
    ],
}

# 17 个壳脚本：文件保留（避免连锁删除），但不再逐轮挂 CI
SHELL = ["r68_verify.py", "r79_verify.py", "r80_verify.py", "r84_verify.py",
         "r87_verify.py", "r88_verify.py", "r101_verify.py", "r103_verify.py",
         "r104_verify.py", "r111_verify.py", "r117_verify.py", "r118_verify.py",
         "r119_verify.py", "r120_verify.py", "r121_verify.py", "r122_verify.py",
         "r123_verify.py"]


def load():
    # R143 · CI 里仓库外没有沙盒卡片；任何读不到的文件回空串，
    # 绝不允许「本地绿 / CI 红」因为一个文件不存在就整脚本崩掉。
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def logic_cases(f):
    bad = []
    ci = f["ci"]
    # ① 17 个壳脚本不再挂 CI，且统一守门挂上了
    for n in SHELL:
        if "python3 scripts/" + n in ci:
            bad.append("壳脚本仍挂在 CI 主链: " + n)
    if "python3 scripts/retired_pages.py --selftest" not in ci:
        bad.append("统一守门没挂 CI")
    steps = len(re.findall(r"run:\s*python3 scripts/", ci))
    # R149 后说明：下限 40 不放（防止把守门全删了）, 上限放到 62 ——
    # 每多一份真机实证就多一个专职守门（R147/R148/R149），这是增量不是回潮；
    # R160 加了 1 项 AOCI 认知索引治理守门（61），R161 再加 1 项（62）。
    # R163 三列并一列后新增 1 份语义守门（63）：20 个历史脚本的 UI 字面量
    # 已批量换成单列等价物，语义判据由 hub_semantics.py 唯一真源接管。
    # 旧壳脚本是否复活由上面的 retired_pages 正向/BAN 严格守着。
    # R171 加 2 项（五题正/反注入）、R172 加 3 项（算法对拍 + 正/反注入）→ 74，
    # R173 加 2 项（Kotlin 结构级编译守门 正/反注入）→ 76，
    # R174 加 2 项（界面自审 正/反注入）→ 78，
    # R176 加 2 项（四件 正/反注入）→ 80，
    # R177 加 2 项（发版对齐 正/反注入）→ 82，
    # R178 加 2 项（界面收口 正/反注入）→ 84，
    # R180 加 2 项 → 86，R182 加 2 项 → 90，
    # R183 加 2 项（真机实证 正/反注入）→ 92。
    if not (40 <= steps <= 96):
        bad.append("CI 挂项 %d，不在收敛后预期区间" % steps)
    else:
        print("CI 挂项 %d" % steps)
    # ② r40/r46 本地不再强制对拍（只有 STRICT 才读 git show）
    for name in ("r40", "r46"):
        t = f[name]
        seg = t[t.find("R141") :]
        strict_guard = re.search(r'TINGBEN_STRICT"\)\s*==\s*"1"', t)
        if not strict_guard:
            bad.append(name + " 没有 STRICT 开关")
        if 'git show' in seg and 'TINGBEN_STRICT' not in seg[:200]:
            bad.append(name + " 本地仍可能逐字节对拍")
    # ③ 清单 26 项：编号行计数
    rows = re.findall(r"^\| ([A-D]\d+) \|", f["audit"], re.M)
    if len(rows) != 26:
        bad.append("清单编号行 %d 条，应为 26" % len(rows))
    else:
        print("真机清单 %d 项，编号连续: %s…%s" % (len(rows), rows[0], rows[-1]))
    if sorted(rows) != sorted(["%s%d" % (g, i) for g, n in
                               [("A", 8), ("B", 8), ("C", 7), ("D", 3)]
                               for i in range(1, n + 1)]):
        bad.append("清单编号不连续或分组数量不对")
    # ④ 导出必须走 IO 且带隐私提示
    m = re.search(r'val exportLauncher = rememberLauncherForActivityResult\(([\s\S]*?)\n    \}\n', f["main"])
    if not m or "withContext(Dispatchers.IO)" not in m.group(1):
        bad.append("日志导出没切 IO 线程")
    if "可能含书名和正文片段" not in f["main"]:
        bad.append("导出入口没有隐私提示")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:72]))
    # 旧形态零回潮
    if "16 项真机待验清单" in f["audit"]:
        e.append("旧口径「16 项」仍在清单里")
    if "点小段试听会暂时代替主播放 —— 确认是否可接受" in f["audit"]:
        e.append("清单仍把独立试听通道列为待定（批次 D 已做）")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了，先修基线"
    logic_bad = len(logic_cases(f))
    orig = dict(f)
    cuts = [
        ("导出汇总被摘", "eventlog", "fun exportBundle(c: Context): String", ""),
        ("崩溃栈没进导出", "eventlog", "崩溃前事件（环形缓冲）", ""),
        ("导出隐私提示被摘", "eventlog", "给开发者排查用的", ""),
        ("设置页导出入口被摘", "main", '"一键复制摘要"', ""),
        ("文件选择器被摘", "main",
         'ActivityResultContracts.CreateDocument("text/markdown")', ""),
        ("导出切 IO 被摘", "main", "EventLog.exportBundle(c)", ""),
        ("r40 语义守恒被摘", "r40", "语义守恒（不再要求 git HEAD 逐字节一致）", ""),
        ("r40 STRICT 开关被摘", "r40", "TINGBEN_STRICT", ""),
        ("r46 STRICT 开关被摘", "r46", "TINGBEN_STRICT", ""),
        ("r135 轻量清单没换", "r135",
         '"python3 scripts/retired_pages.py --selftest"', ""),
        ("统一守门入口被摘", "retired", "def main():", ""),
        ("统一守门没挂 CI", "ci", "python3 scripts/retired_pages.py --selftest", ""),
        ("清单定稿 26 项被摘", "audit", "真机待验清单（定稿 · 26 项", ""),
        ("清单验收标准被摘", "audit", "什么算过", ""),
        ("CI 漏挂 r141", "ci", "python3 scripts/r141_verify.py --selftest", ""),
    ]
    bad = 0
    try:
        for name, key, needle, repl in cuts:
            x = dict(f)
            x[key] = f[key].replace(needle, repl)
            if not check(x):
                bad += 1
                print("  反向注入漏放:", name)
    finally:
        # R141 · 反向注入全程只改内存 dict（check(x) 不读盘），原来每跑一次
        # selftest 就把全部文件无条件写回 15 遍，纯属空转。改成「确实变了才写」，
        # 保留防误改的保险，但去掉 100+ 次无谓写盘。
        for k, v in orig.items():
            try:
                if FILES[k].read_text(encoding="utf-8", errors="replace") != v:
                    FILES[k].write_text(v, encoding="utf-8")
            except (OSError, UnicodeError):
                FILES[k].write_text(v, encoding="utf-8")
    total = len(cuts)
    print("反向注入 %d/%d 全拦下" % (total - bad, total))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R141 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    # 实测轻量清单耗时（效率是这批的目的之一）
    t = time.time()
    # 只取 QUICK = [...] 块，别把 CURRENT_VERIFIERS 等别的字符串也算进来
    qm = re.search(r"QUICK = \[([\s\S]*?)\n\]", f["r135"])
    quick = re.findall(r'"(python3 scripts/[^"]+)"', qm.group(1)) if qm else []
    oks = 0
    for c in quick:
        r = subprocess.run(c, shell=True, cwd=str(ROOT), capture_output=True,
                           text=True, timeout=120)
        oks += (r.returncode == 0)
    print("轻量改码清单 %d/%d 全绿 · %.1f 秒（全套留发版轮）"
          % (oks, len(quick), time.time() - t))
    if oks != len(quick):
        print("R141 FAIL：轻量清单自己没跑绿")
        return 1
    print("R141 批次E：日志导出 / 自检去摩擦 / 退役脚本收敛 / 清单定稿：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
