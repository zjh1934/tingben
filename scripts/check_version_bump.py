#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R137 · 发版前校验 version.txt 必须严格大于线上的上一个 Release。

背景：CI 是 `on: push: branches: [main]`，任何代码提交都会打包发 Release，
而 tag_name 直接取 version.txt。忘改版本号 -> 以同名 tag 覆盖资产、
固定直链静默换包，用户完全无感。这里把「必须递增」变成硬闸门。

线上版本来源：GitHub Releases/latest（由 CI 的 GITHUB_TOKEN 读取）。
本地自检/反向注入时用 TINGBEN_PREV_VERSION 覆盖，避免依赖网络。

用法：python3 scripts/check_version_bump.py
退出码 0 = 可以发版；1 = 拦下。

R203 · 版本递增只在「main 发版流程」生效：CI 的 rc 验证分支由
GITHUB_REF（refs/heads/rc/...）自动识别并跳过；本地验证轮用
TINGBEN_VERIFY_ONLY=1 跳过。显式给了 TINGBEN_PREV_VERSION 时优先级更高
（那是在离线对拍闸门本身）。main 上不设这些变量，同号仍严格拦下。
"""
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
VER_FILE = ROOT / "version.txt"


def parse(v):
    v = (v or "").strip().lstrip("vV")
    if not re.fullmatch(r"\d+\.\d+\.\d+", v):
        return None
    return tuple(int(x) for x in v.split("."))


def online_prev():
    override = os.environ.get("TINGBEN_PREV_VERSION")
    if override is not None:
        return override.strip(), "override"
    repo = os.environ.get("GITHUB_REPOSITORY", "zjh1934/tingben")
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    h = {"Accept": "application/vnd.github+json", "User-Agent": "tingben-version-gate"}
    if tok:
        h["Authorization"] = "Bearer " + tok
    req = urllib.request.Request(
        "https://api.github.com/repos/%s/releases/latest" % repo, headers=h)
    try:
        d = json.load(urllib.request.urlopen(req, timeout=30))
        return (d.get("tag_name") or "").strip(), "api"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None, "no-release-yet"
        return None, "http-%d" % e.code
    except Exception as e:
        return None, "error:" + type(e).__name__


def check():
    e = []
    raw = VER_FILE.read_text(encoding="utf-8").strip()
    new = parse(raw)
    if new is None:
        e.append("version.txt 格式不对（必须 x.y.z）：%r" % raw)
        return e
    prev, src = online_prev()
    if prev is None:
        if src == "no-release-yet":
            print("首个 Release，放行 %s" % raw)
            return []
        e.append("查不到线上版本（来源 %s），无法确认递增，保守拦下" % src)
        return e
    old = parse(prev)
    if old is None:
        e.append("线上 tag 解析不了：%r（来源 %s）" % (prev, src))
        return e
    print("线上 %s（%s）→ 本次 %s" % (prev, src, raw))
    if new <= old:
        e.append("版本没有递增：改 version.txt 再推（当前 %s，线上 %s）；"
                 "同名 tag 会覆盖 Release 资产与固定直链" % (raw, prev))
    elif new[2] != old[2] + 1 and new[:2] == old[:2]:
        print("提示：规则是每次打包只升一个修订号，本次跳到了 %s" % raw)
    return e


def main():
    ref = os.environ.get("GITHUB_REF", "")
    verify_only = os.environ.get("TINGBEN_VERIFY_ONLY") == "1"
    # rc/... 等非 main 分支只跑真编译、不产出 Release；main 不在跳过集合里
    is_verify = verify_only or (ref and ref != "refs/heads/main")
    if is_verify and os.environ.get("TINGBEN_PREV_VERSION") is None:
        print("PASS · 验证轮跳过版本递增（%s；main 发版仍严格拦同号）"
              % (ref or "TINGBEN_VERIFY_ONLY"))
        return 0
    e = check()
    if e:
        print("FAIL · 版本守门")
        for x in e:
            print(" -", x)
        return 1
    print("PASS · 版本严格递增")
    return 0


if __name__ == "__main__":
    sys.exit(main())
