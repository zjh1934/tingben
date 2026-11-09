#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发版体检单（只读，不推送、不升版本、不打包）。

在能走 git 传输的环境里，发版对齐轮先跑这一份，它只回答一个问题：
「离把当前版本推出去还差什么」。所有检查都在本地，不联网。
R179 起版本判据动态取 version.txt，不再绑死某个字面量。

用法：python3 scripts/release_preflight.py
"""
import pathlib, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.run(list(args), cwd=str(ROOT), capture_output=True, text=True)


def sh(cmd):
    return subprocess.run(cmd, shell=True, cwd=str(ROOT), capture_output=True, text=True).stdout.strip()


def main():
    lines = []
    ver = (ROOT / "version.txt").read_text(encoding="utf-8").strip()
    dirty = sh("git status --porcelain")
    remote = sh("git remote -v")
    branch = sh("git branch --show-current")
    head = sh("git log -1 --oneline")

    lines.append("版本文件        : %s" % ver)
    lines.append("工作区          : %s" % ("干净" if not dirty else "有未提交改动（%d 项）" % len(dirty.splitlines())))
    lines.append("本地分支 / HEAD : %s / %s" % (branch, head))
    lines.append("git remote      : %s" % (remote.splitlines()[0] if remote else "未配置"))

    tools = ["java", "kotlinc", "gradle", "aapt2"]
    have = [t for t in tools if shutil.which(t)]
    lines.append("编译工具链      : %s" % (", ".join(have) or "无"))
    lines.append("真编译（本环境）: %s" %
                 ("可做" if all(t in have for t in ("kotlinc", "gradle", "aapt2")) else "做不了，归 CI"))

    checks = [
        ("R172 四件正向", ["python3", "scripts/r172_verify.py"]),
        ("R172 反向注入", ["python3", "scripts/r172_verify.py", "--selftest"]),
        ("R172 算法对拍", ["python3", "scripts/r172_wav_parity.py"]),
        ("R173 结构守门", ["python3", "scripts/r173_verify.py"]),
        ("R173 反向注入", ["python3", "scripts/r173_verify.py", "--selftest"]),
        ("AOCI 无漂移", ["python3", "scripts/aoci_check.py"]),
        ("R94 反向注入", ["python3", "scripts/r94_verify.py", "--selftest"]),
        ("R138 反向注入", ["python3", "scripts/r138_verify.py", "--selftest"]),
        ("R141 收口", ["python3", "scripts/r141_verify.py"]),
        ("R135 收口", ["python3", "scripts/r135_verify.py"]),
        ("R174 界面自审", ["python3", "scripts/r174_verify.py"]),
        ("R175 六条收口", ["python3", "scripts/r175_verify.py"]),
        ("R175 反向注入", ["python3", "scripts/r175_verify.py", "--selftest"]),
        ("R176 四件收口", ["python3", "scripts/r176_verify.py"]),
        ("R176 反向注入", ["python3", "scripts/r176_verify.py", "--selftest"]),
        ("R177 发版对齐", ["python3", "scripts/r177_verify.py"]),
        ("R178 界面收口", ["python3", "scripts/r178_verify.py"]),
        ("R178 反向注入", ["python3", "scripts/r178_verify.py", "--selftest"]),
        ("R180 核心闭环", ["python3", "scripts/r180_verify.py"]),
        ("R180 反向注入", ["python3", "scripts/r180_verify.py", "--selftest"]),
        ("R182 收听连续性", ["python3", "scripts/r182_verify.py"]),
        ("R182 反向注入", ["python3", "scripts/r182_verify.py", "--selftest"]),
        ("R183 真机实证", ["python3", "scripts/r183_verify.py"]),
        ("R183 反向注入", ["python3", "scripts/r183_verify.py", "--selftest"]),
        ("R185 音色缓存", ["python3", "scripts/r185_verify.py"]),
        ("R185 反向注入", ["python3", "scripts/r185_verify.py", "--selftest"]),
        ("R188 侧车随套", ["python3", "scripts/r188_verify.py"]),
        ("R188 反向注入", ["python3", "scripts/r188_verify.py", "--selftest"]),
        ("R189 归档套隔离", ["python3", "scripts/r189_verify.py"]),
        ("R189 反向注入", ["python3", "scripts/r189_verify.py", "--selftest"]),
        ("R192 删书清理", ["python3", "scripts/r192_verify.py"]),
        ("R192 反向注入", ["python3", "scripts/r192_verify.py", "--selftest"]),
        ("R193 收尾窗口守卫", ["python3", "scripts/r193_verify.py"]),
        ("R193 反向注入", ["python3", "scripts/r193_verify.py", "--selftest"]),
    ]
    ok = 0
    for name, cmd in checks:
        r = run(*cmd)
        good = r.returncode == 0
        ok += good
        lines.append("  %-16s %s" % (name, "绿" if good else "红"))
    lines.append("关键守门        : %d/%d 绿" % (ok, len(checks)))

    gate = []
    if not ver:
        gate.append("version.txt 为空")
    if "kotlinc" not in have:
        gate.append("本环境无 Android SDK，真编译归 CI；发版前必须盯 rc 分支的 Kotlin/Compose 结果")
    gate.append("github.com 的 git 传输在本沙盒被 403；走仓库外 rc_push.py 的 API 增量通道")
    gate.append("流程：rc 分支 workflow_dispatch 真编译 → 绿了再 promote main 发版")
    gate.append("本地没有 APK，publish_apk 要等 CI 出包后才跑")

    print("=" * 62)
    print("听本 · 发版体检单（只读）")
    print("=" * 62)
    print("\n".join(lines))
    print("\n离发版还差：")
    for i, g in enumerate(gate, 1):
        print("  %d. %s" % (i, g))
    if ok != len(checks) or dirty:
        print("\n结论：本地还有未收口项，先别发版（见上面红项与工作区状态）。")
    else:
        print("\n结论：本地已收口。走 rc_push.py --push → 盯 rc 分支 CI 真编译 → 绿了 --promote 发版。")
    return 0 if ok == len(checks) and not dirty else 1


if __name__ == "__main__":
    sys.exit(main())
