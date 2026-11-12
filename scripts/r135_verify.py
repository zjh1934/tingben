# -*- coding: utf-8 -*-
"""R135 · 批次 6 收口验证（主守门）。

背景：批次 6 从 App() 出发做传递闭包，物理删除了 13 个不可达死代码块（747 行），
并把 17 个专门验证这些旧页的历史脚本收敛成统一「防回潮」守门。

本脚本守四件事：
  1) 可达性：MainActivity 里所有顶层 private 声明都必须从 App() 可达（不许再长死代码）
  2) 旧页符号零回潮 + 现行能力守恒（复用 retired_pages）
  3) R131~R134 四个现行验证都挂在 CI 上
  4) 轻量自检清单 5 项仍可执行（--selftest 时实跑，防清单腐烂）

沙盒无 Android SDK，静态绿 != 编译过；真编译由 CI 负责。
用法：python3 scripts/r135_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import retired_pages

APP = ROOT / "app/src/main/java/com/tingben/app"
MAIN = ui_sources.source(APP)
CI = ROOT / ".github/workflows/ci.yml"

# 批次 6 删掉的 13 个不可达顶层声明
DEAD_NAMES = [
    "TaskDetailData", "buildTaskDetail", "playChapterAudio", "TaskDetailScreen",
    "fmtTime", "AudioMenu", "AudioScreen", "GroupLabel", "SettingOption",
    "SettingSwitchRow", "SettingInfoRow", "DiagnosticsBlock", "SoundProbeBlock",
]

# 现行能力锚点（防「清理时把活代码一起删了」）
LIVE_ANCHORS = [
    "fun openBook(id: String?)",
    "var playerOpen by rememberSaveable { mutableStateOf(false) }",
    "PlayerSheet(ctx, miniBook ?: bookId,",
    "onExpand = { playerOpen = true",
    "MiniPlayBar(ctx, miniBook,",
]

CURRENT_VERIFIERS = ["r131_verify.py", "r132_verify.py", "r133_verify.py",
                    "r134_verify.py", "r136_verify.py", "r137_verify.py",
                    "r138_verify.py", "r139_verify.py", "r140_verify.py",
                    "r141_verify.py", "r142_verify.py", "r147_verify.py", "r148_verify.py",
           "r149_verify.py", "r150_verify.py", "r151_verify.py",
           "r152_verify.py", "r153_verify.py",
           "r154_verify.py", "r155_verify.py",
           "r156_verify.py", "r157_verify.py", "r158_verify.py",
           "aoci_check.py", "r161_verify.py", "r163_verify.py",
           # R189 · 补齐停在 r163 的登记（这 15 个都早已挂 CI，只是清单没跟上）
           "r169_verify.py", "r170_verify.py", "r171_verify.py",
           "r172_verify.py", "r173_verify.py", "r174_verify.py",
           "r175_verify.py", "r176_verify.py", "r177_verify.py",
           "r178_verify.py", "r180_verify.py", "r182_verify.py",
           "r183_verify.py", "r185_verify.py", "r188_verify.py",
           "r189_verify.py", "r192_verify.py", "r193_verify.py",
           "r197_verify.py", "r200_verify.py", "r206_verify.py", "r209_verify.py", "r210_verify.py", "r213_verify.py", "r214_verify.py", "r215_verify.py", "r217_verify.py", "r218_verify.py", "r219_verify.py", "r220_verify.py", "r221_verify.py", "r223_verify.py", "r224_verify.py", "r225_verify.py", "r226_verify.py", "r230_verify.py", "r231_verify.py", "r233_verify.py", "r234_verify.py", "r235_verify.py", "r236_verify.py", "r237_verify.py", "r238_verify.py", "r239_verify.py", "r248_verify.py", "r257_verify.py", "r300_verify.py"]

# R141 建立、R160 补第 6 项的轻量自检清单：改码轮只跑这 6 项，全套留到发版轮。
# 换了三项：退役页统一守门（原来 17 个壳脚本各起一次）、最近两批 R140/R139。
# 老的 R127/R128/R129 仍挂 CI 全套，只是不再占改码轮的 3 秒预算。
QUICK = [
    "python3 scripts/check_docs.py",
    "python3 scripts/check_structure.py",
    "python3 scripts/retired_pages.py --selftest",
    "python3 scripts/r140_verify.py --selftest",
    "python3 scripts/r139_verify.py --selftest",
    # AOCI 认知守门：受管文件改了而索引/基线没维护，改码轮当场就红
    "python3 scripts/aoci_check.py",
]


def reachability(main_text=None):
    """从 App() 出发做传递闭包，返回 (所有顶层 private 名, 不可达名列表)。
    R170 · 接受内存文本，selftest 不再需要写源文件。"""
    lines = (main_text if main_text is not None
             else MAIN.read_text(encoding="utf-8")).splitlines()
    defs = []
    for i, l in enumerate(lines):
        m = re.match(r'private (?:@Composable\s+)?(?:fun|data class|class|enum class|object|val) ([A-Za-z_]\w*)', l)
        if m:
            defs.append((i, m.group(1)))
    bodies = {}
    for k, (i, n) in enumerate(defs):
        end = defs[k + 1][0] if k + 1 < len(defs) else len(lines)
        bodies[n] = "\n".join(lines[i:end])
    live = {"App"}
    for _ in range(20):
        before = len(live)
        for n in list(live):
            if n not in bodies:
                continue
            for other in bodies:
                if other != n and re.search(r'\b' + re.escape(other) + r'\b', bodies[n]):
                    live.add(other)
        if len(live) == before:
            break
    return [n for _, n in defs], [n for _, n in defs if n not in live]


def check(main=None, ci=None, allkt=None):
    errs = []
    allkt = allkt if allkt is not None else "\n".join(
        p.read_text(encoding="utf-8", errors="replace") for p in APP.rglob("*.kt"))
    main = main if main is not None else MAIN.read_text(encoding="utf-8", errors="replace")
    ci = ci if ci is not None else CI.read_text(encoding="utf-8", errors="replace")

    _, unreachable = reachability(main)
    if unreachable:
        errs.append("又长出不可达死代码: " + ", ".join(unreachable))
    for n in DEAD_NAMES:
        if re.search(r'private (?:fun|data class|class|val)\s+' + re.escape(n) + r'\b', allkt):
            errs.append("退役声明回潮: " + n)
    for a in LIVE_ANCHORS:
        if a not in main:
            errs.append("现行能力丢失: " + a[:56])
    for v in CURRENT_VERIFIERS:
        if v not in ci:
            errs.append("现行验证没挂 CI: " + v)
    # R189 · 反向防漏：CI 里挂了、编号属于现行批次（>=161）却没登记进清单。
    # 以前新增守门忘登记只是「悄悄不进轻量清单」，现在当场红。
    live = set(re.findall(r'scripts/(r(?:1[6-9]\d|[2-9]\d\d)_verify\.py)', ci))
    missing = sorted(live - set(CURRENT_VERIFIERS) - {"r135_verify.py"})
    if missing:
        errs.append("现行守门漏登记 CURRENT_VERIFIERS: " + ", ".join(missing))
    if not (ROOT / "scripts/retired_pages.py").exists():
        errs.append("退役守门模块缺失: scripts/retired_pages.py")
    if "retired_pages.py" not in (ROOT / "scripts/check_docs.py").read_text(encoding="utf-8"):
        errs.append("退役守门模块未纳入 check_docs 的 CI 依赖补集")
    # 统一守门自身必须绿
    errs += ["统一守门: " + e for e in retired_pages.check(retired_pages.sources())]
    return errs


def selftest():
    # R170 · 事故收口：原来三个注入都原地覆写 157KB 的 MainActivity.kt，
    # 靠 finally 还原；一次沙盒 502 砍在循环中途就会把注入留在源码里。
    # 现在全部在内存里造变体传给 check，源文件零写入。
    base_main = MAIN.read_text(encoding="utf-8", errors="replace")
    base_ci = CI.read_text(encoding="utf-8", errors="replace")
    base_allkt = "\n".join(
        p.read_text(encoding="utf-8", errors="replace") for p in APP.rglob("*.kt"))
    assert not check(main=base_main, ci=base_ci, allkt=base_allkt), \
        "基线自己就红了，先修基线"

    cases = [
        ("死代码又长回来", (
            lambda m, c, a: (m, c, a + "\nprivate fun AudioScreen(c: Context) {}"))),
        ("现行能力被摘", (
            lambda m, c, a: (m.replace("fun openBook(id: String?)",
                                       "fun openBookColX(id: String?, col: Int)"), c, a))),
        ("现行验证漏挂", (
            lambda m, c, a: (m, c.replace("python3 scripts/r134_verify.py --selftest", ""), a))),
        ("新增守门忘登记", (
            lambda m, c, a: (m, c + "\n      - run: python3 scripts/r199_verify.py\n", a))),
    ]
    bad = 0
    for name, mutate in cases:
        m, c, a = mutate(base_main, base_ci, base_allkt)
        assert (m, c, a) != (base_main, base_ci, base_allkt), "注入没生效: " + name
        if not check(main=m, ci=c, allkt=a):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))

    # 轻量清单实跑：清单本身不能腐烂
    quick_bad = 0
    for cmd in QUICK:
        r = subprocess.run(cmd, shell=True, cwd=str(ROOT),
                           capture_output=True, text=True, timeout=300)
        if r.returncode != 0:
            quick_bad += 1
            print("  轻量清单跑红:", cmd)
    print("轻量自检清单 %d/%d 全绿" % (len(QUICK) - quick_bad, len(QUICK)))
    return bad + quick_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    e = check()
    if e:
        print("R135 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    names, _ = reachability()
    print("R135 收口守门：顶层 private 声明 %d 个全部可达 / 旧页零回潮 / "
          "现行验证 %d 项在 CI / 轻量清单 %d 项：PASS" %
          (len(names), len(CURRENT_VERIFIERS), len(QUICK)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
