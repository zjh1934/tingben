#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R107 · F-25 验证：脚本解绑 / 语义判据 / R109 模拟端到端 / 反向注入。

F-25 的验收不是「脚本改了」，而是：
1) 六个验证脚本不再锁导航实现字面量；
2) 语义判据在当前源码与「F-04/F-07 改造后的模拟源码」上都成立；
3) 把模拟源码放进镜像仓库、按 CI 原命令全量复跑，不出现假红；
4) 真破坏导航语义时仍会被拦下。

用法：python3 scripts/r107_verify.py [--selftest]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import os, pathlib, re, shutil, subprocess, sys, tempfile
import atexit

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS))
import nav_semantics as nav

MAIN_P = ui_sources.source(ROOT)
# R135：r79 / r80 验证对象（旧音频页）已物理删除并退役，
# 不再要求它们接入导航语义判据，改由统一守门约束。
TARGETS = ["check_structure.py", "r46_verify.py", "r89_verify.py",
           "r105_verify.py"]
RETIRED = ["r79_verify.py", "r80_verify.py"]

BANNED = [
    "BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }",
    "var modelsFrom by remember { mutableStateOf(Screen.LIBRARY) }",
    "onBack = { screen = modelsFrom }",
    "onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER }",
    "Screen.MIXER -> MixerScreen(ctx, onBack = { screen = drawerReturn })",
    "onModels = { modelsFrom = Screen.AUDIO; screen = Screen.MODELS }",
    "onLibrary = { screen = Screen.LIBRARY }",
    "var drawerReturn by remember { mutableStateOf(Screen.LIBRARY) }",
    "private enum class Screen { LIBRARY, PRODUCE, TASKS, SETTINGS, MODELS, CHAPTERS }",
]

def banned_hits(files=None):
    """返回 [(脚本, 字面量)]；无即解绑干净。"""
    out = []
    for name in TARGETS:
        t = (files or {}).get(name)
        if t is None:
            t = (SCRIPTS / name).read_text(encoding="utf-8")
        for lit in BANNED:
            if lit in t:
                out.append((name, lit))
    return out

def ci_commands():
    s = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    cmds = [m.group(1).strip() for m in re.finditer(
        r"run:\s*(python3 scripts/[^ \n]+(?:\s+--selftest)?)", s)]
    return [c for c in cmds if "publish_apk.py" not in c]

def _exec_script(path, extra_args=()):
    """进程内跑一份验证脚本；runpy 会把 __file__ 指向镜像路径，ROOT 自动落到镜像仓库。"""
    import contextlib, io, runpy
    old = sys.argv
    buf = io.StringIO()
    sys.argv = [str(path)] + list(extra_args)
    try:
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            try:
                runpy.run_path(str(path), run_name="__main__")
                return 0, buf.getvalue()
            except SystemExit as e:
                return (e.code if isinstance(e.code, int) else (0 if e.code is None else 1)), buf.getvalue()
            except Exception as e:
                return 1, buf.getvalue() + "\nEXC " + repr(e)
    finally:
        sys.argv = old

def atomic_write(path, text):
    """R170 · 原地覆写 157KB 大文件在这台沙盒上会触发 Stale file handle，
    而且进程被砍在 try/finally 中间会留注入残体。改成「写临时 + 原子替换」，
    每次都是新 inode；再注册 atexit 兜底，正常/异常退出都不留残。

    R206 · path 现在是 ui_sources.UiSourcePath（MainActivity 已分家）。
    直接 os.replace 到真实路径会把「合并视图原文」盖回主文件、丢掉占位行、
    片段也不同步；必须走它的 write_text 按片段分发，合并视图语义才成立。
    """
    if not isinstance(path, pathlib.Path) and hasattr(path, "write_text"):
        path.write_text(text)
        return
    tmp = pathlib.Path(str(path) + ".r107tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)

_ORIGINAL_MAIN = None
def _restore():
    if _ORIGINAL_MAIN is not None:
        atomic_write(MAIN_P, _ORIGINAL_MAIN)

def mirror_check():
    """把 MainActivity 临时换成 R109 计划形态，就地复跑相关脚本后立刻还原。

    不复制整仓：沙盒里每文件 IO 延迟很高，复制 1MB 也要 40s。
    单进程内 try/finally 还原，工作区不会留下改动。
    """
    global _ORIGINAL_MAIN
    original = MAIN_P.read_text(encoding="utf-8")
    _ORIGINAL_MAIN = original
    atexit.register(_restore)
    old_ci = os.environ.get("CI")
    os.environ["CI"] = "true"
    try:
        atomic_write(MAIN_P, nav.simulate_r109(original))
        focus = [c for c in ci_commands() if any(t in c for t in TARGETS)]
        focus.append("python3 scripts/r107_verify.py")
        fails = []
        for c in focus:
            parts = c.split()
            selftest = parts[-1] == "--selftest"
            name = parts[-2] if selftest else parts[-1]
            script = SCRIPTS / pathlib.Path(name).name
            code, out = _exec_script(script, [])
            if code != 0:
                fails.append((c + " [基线]", out.strip().splitlines()[-2:]))
            if selftest:
                code2, out2 = _exec_script(script, ["--selftest"])
                if code2 != 0:
                    fails.append((c, out2.strip().splitlines()[-2:]))
        return len(focus), fails
    finally:
        atomic_write(MAIN_P, original)
        _ORIGINAL_MAIN = None
        if old_ci is None:
            os.environ.pop("CI", None)
        else:
            os.environ["CI"] = old_ci

def injections():
    """真破坏导航语义时，semantics 必须报错。"""
    src = MAIN_P.read_text(encoding="utf-8")
    g = nav.global_back(src)[2]
    d = nav.drawer_back(src)[2]
    def in_branch(s, name, repl):
        win = nav._branch(s, name)
        lit = "onBack = { %s }" % nav._on_back(win)
        i = s.find(win)
        j = s.find(lit, i) if i >= 0 else -1
        return s if j < 0 else s[:j] + repl + s[j + len(lit):]

    def break_mixer(s):
        # R196 · 调音台收成浮层后，破坏「关闭归位」就是破坏其返回语义
        return s.replace("chapterMixerOpen = false", "", 1)

    def break_models(s):
        return in_branch(s, "MODELS", "onBack = { screen = Screen.LIBRARY }")
    out = [
        ("全局回退失效", lambda s: s.replace(g, "(enabled = false)", 1)),
        ("抽屉回退失效", lambda s: s.replace(d, "(enabled = false)", 1)),
        ("调音台浮层关闭没归位", break_mixer),
        ("模型页返回写死 LIBRARY", break_models),
        ("丢导航字段 modelsFrom",
         lambda s: re.sub(r"\s*var modelsFrom by remember(?:Saveable)? \{[^\n]*\n", "\n", s, count=1)),
        ("丢调音台入口", lambda s: re.sub(r"onMixer\s*=\s*\{[^}]*\}", "onMixer = {}", s)),
        ("抽屉回退排到全局之前", lambda s: swap_enabled(s, g, d)),
    ]
    return src, out

def swap_enabled(s, gfrag, dfrag):
    """按位置交换两个 BackHandler 的 enabled 片段；链式 replace 会换过去又换回来。"""
    gi, di = s.find(gfrag), s.find(dfrag)
    if gi < 0 or di < 0 or gi > di:
        return s
    return s[:gi] + dfrag + s[gi + len(gfrag):di] + gfrag + s[di + len(dfrag):]

def run():
    errs = []
    hits = banned_hits()
    for name, lit in hits:
        errs.append("仍锁死导航字面量 %s: %s" % (name, lit[:56]))

    for name in TARGETS:
        t = (SCRIPTS / name).read_text(encoding="utf-8")
        if "nav_semantics" not in t and "nav." not in t:
            errs.append("未接入语义判据: " + name)
    for name in RETIRED:
        if "from retired_pages import run" not in (SCRIPTS / name).read_text(encoding="utf-8"):
            errs.append("退役脚本未接入统一守门: " + name)

    src = MAIN_P.read_text(encoding="utf-8")
    for msg in nav.semantics(src):
        errs.append("当前源码导航语义: " + msg)
    sim = nav.simulate_r109(src)
    for msg in nav.semantics(sim):
        errs.append("R109 模拟源码导航语义假红: " + msg)

    base, inj = injections()
    blocked = 0
    for label, mutate in inj:
        mutated = mutate(base)
        if mutated == base:
            errs.append("反向注入锚点失效: " + label)
        elif not nav.semantics(mutated):
            errs.append("反向注入没拦下: " + label)
        else:
            blocked += 1
    print("反向注入 %d/%d 全拦下" % (blocked, len(inj)))

    fake = {"check_structure.py": (SCRIPTS / "check_structure.py").read_text(encoding="utf-8")
            + '\n# ' + BANNED[0] + '\n'}
    if not banned_hits(fake):
        errs.append("解绑检查对回潮字面量不敏感")

    if "--selftest" in sys.argv:
        n, fails = mirror_check()
        if fails:
            for c, tail in fails:
                errs.append("R109 镜像复跑假红 %s: %s" % (c, tail))
        print("R109 镜像复跑：%d 步，假红 %d" % (n, len(fails)))

    if errs:
        print("R107 FAIL", len(errs))
        for e in errs:
            print(" -", e)
        return 1
    print("R107 PASS · 解绑 %d 脚本 / 语义双形态成立 / 反向注入 %d 例" % (len(TARGETS), len(inj)))
    return 0

if __name__ == "__main__":
    sys.exit(run())
