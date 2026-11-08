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
import pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import retired_pages

APP = ROOT / "app/src/main/java/com/tingben/app"
MAIN = APP / "MainActivity.kt"
CI = ROOT / ".github/workflows/ci.yml"

# 批次 6 删掉的 13 个不可达顶层声明
DEAD_NAMES = [
    "TaskDetailData", "buildTaskDetail", "playChapterAudio", "TaskDetailScreen",
    "fmtTime", "AudioMenu", "AudioScreen", "GroupLabel", "SettingOption",
    "SettingSwitchRow", "SettingInfoRow", "DiagnosticsBlock", "SoundProbeBlock",
]

# 现行能力锚点（防「清理时把活代码一起删了」）
LIVE_ANCHORS = [
    "fun openBookCol(id: String?, col: Int)",
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
           "r154_verify.py"]

# R141 · 批次 E 更新后的轻量自检清单：改码轮只跑这 5 项，全套留到发版轮。
# 换了三项：退役页统一守门（原来 17 个壳脚本各起一次）、最近两批 R140/R139。
# 老的 R127/R128/R129 仍挂 CI 全套，只是不再占改码轮的 3 秒预算。
QUICK = [
    "python3 scripts/check_docs.py",
    "python3 scripts/check_structure.py",
    "python3 scripts/retired_pages.py --selftest",
    "python3 scripts/r140_verify.py --selftest",
    "python3 scripts/r139_verify.py --selftest",
]


def reachability():
    """从 App() 出发做传递闭包，返回 (所有顶层 private 名, 不可达名列表)。"""
    lines = MAIN.read_text(encoding="utf-8").splitlines()
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


def check():
    errs = []
    allkt = "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in APP.rglob("*.kt"))
    main = MAIN.read_text(encoding="utf-8", errors="replace")
    ci = CI.read_text(encoding="utf-8", errors="replace")

    _, unreachable = reachability()
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
    if not (ROOT / "scripts/retired_pages.py").exists():
        errs.append("退役守门模块缺失: scripts/retired_pages.py")
    if "retired_pages.py" not in (ROOT / "scripts/check_docs.py").read_text(encoding="utf-8"):
        errs.append("退役守门模块未纳入 check_docs 的 CI 依赖补集")
    # 统一守门自身必须绿
    errs += ["统一守门: " + e for e in retired_pages.check(retired_pages.sources())]
    return errs


def selftest():
    assert not check(), "基线自己就红了，先修基线"
    lines = MAIN.read_text(encoding="utf-8").splitlines()
    ci = CI.read_text(encoding="utf-8", errors="replace")

    cases = [
        ("死代码又长回来", lambda: MAIN.write_text(
            "\n".join(lines + ["", "private fun AudioScreen(c: Context) {}", ""]), encoding="utf-8")),
        ("现行能力被摘", lambda: MAIN.write_text(
            "\n".join(lines).replace("fun openBookCol(id: String?, col: Int)",
                                     "fun openBookColX(id: String?, col: Int)"), encoding="utf-8")),
        ("现行验证漏挂", lambda: CI.write_text(
            ci.replace("python3 scripts/r134_verify.py --selftest", ""), encoding="utf-8")),
    ]
    original = (MAIN.read_text(encoding="utf-8"), CI.read_text(encoding="utf-8"))
    bad = 0
    try:
        for name, mutate in cases:
            MAIN.write_text(original[0], encoding="utf-8")
            CI.write_text(original[1], encoding="utf-8")
            mutate()
            if not check():
                bad += 1
                print("  反向注入漏放:", name)
    finally:
        # 无论中途是否异常，都必须逐字节还原，否则跟 HEAD 对拍的守恒脚本会假红
        MAIN.write_text(original[0], encoding="utf-8")
        CI.write_text(original[1], encoding="utf-8")
    print("反向注入 %d/%d 全拦下" % (len(cases) - bad, len(cases)))

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
