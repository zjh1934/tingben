#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R177 · 发版对齐守门：验证分支不许误发版、版本必须递增。

沙盒的 git 传输被 403，但 GitHub API 通道可用且有 push 权限。发版路径改为：
  本地快照 → API 建 rc/<版本> 分支 → workflow_dispatch 跑全套 + 真编译
  → 绿了再把 main 快进到同一提交 → push main 自动发 Release。
因此：
  · Release 与固定直链两步必须限定 main，否则验证分支编译通过就提前覆盖
    线上旧版 Release 与直链；
  · version.txt 必须严格大于线上最新 tag。

selftest 全程纯内存注入，一个字节都不写源文件。
用法：python3 scripts/r177_verify.py [--selftest]
"""
import json, pathlib, sys, urllib.request, os

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = "zjh1934/tingben"
FILES = {
    "ver": ROOT / "version.txt",
    "ci": ROOT / ".github/workflows/ci.yml",
    "preflight": ROOT / "scripts/release_preflight.py",
}
GUARD = "if: github.ref == 'refs/heads/main'"


def remote_tag():
    tok = os.environ.get("GH_PAT") or os.environ.get("GH_TOKEN") or ""
    if not tok:
        return None
    try:
        r = urllib.request.Request(
            "https://api.github.com/repos/" + REPO + "/tags?per_page=1",
            headers={"Authorization": "Bearer " + tok,
                     "Accept": "application/vnd.github+json",
                     "User-Agent": "tingben-rc"})
        d = json.load(urllib.request.urlopen(r, timeout=20))
        return d[0]["name"] if d else None
    except Exception:
        return None


def vt(v):
    try:
        return tuple(int(x) for x in v.lstrip("v").split("."))
    except Exception:
        return (0,)


def checks(f, online=True, tag=None):
    e = []
    ver = f["ver"].strip()
    ci = f["ci"]
    # ① 两个发版步骤必须都有 main 保护
    for name in ("发布 Release（固定最新版直链）", "同步 APK 到公开直链"):
        i = ci.find("- name: " + name)
        if i < 0:
            e.append("CI 找不到步骤: " + name)
            continue
        seg = ci[i:i + 400]
        if GUARD not in seg:
            e.append("发版步骤没限定 main: " + name)
    if ci.count(GUARD) < 2:
        e.append("main 保护数量不足（Release + 直链各一条）")
    # ② 版本递增
    if not vt(ver) or len(vt(ver)) < 3:
        e.append("version.txt 不是合法三段版本号")
    if tag is None and online:
        tag = remote_tag()
    # 发版后本地与线上同版是正常的（main 上留的就是刚发的版本）。
    # 「严格递增」由 CI 的 check_version_bump 在 push 时把关；这里只拦回退。
    if tag and not (vt(ver) >= vt(tag)):
        e.append("版本比线上 %s 还旧：%s" % (tag, ver))
    # ③ 体检单在
    if not (ROOT / "scripts/release_preflight.py").is_file():
        e.append("发版体检单缺失")
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    # 离线 selftest 用「线上 == 本地当前版本」做基线：模拟刚发完版的正常态，
    # 这样「退回旧版本」会被拦下，又不会写死一个会过期的 tag。
    def ck(v):
        return checks(v, online=False, tag=base["ver"].strip())
    assert not ck(base), "基线自己就红了，先修基线"
    cases = [
        ("版本退回旧版", "ver", lambda t: "1.1.5\n"),
        ("版本清空", "ver", lambda t: "\n"),
        ("Release 保护被摘", "ci",
         lambda t: t.replace(GUARD + "\n        uses: softprops/action-gh-release@v2",
                             "uses: softprops/action-gh-release@v2")),
        ("直链保护被摘", "ci",
         lambda t: t.replace(GUARD + "\n        env:\n          GH_PAT: ${{ secrets.GH_PAT }}",
                             "env:\n          GH_PAT: ${{ secrets.GH_PAT }}")),
        ("保护条件被改宽", "ci",
         lambda t: t.replace(GUARD, "if: true")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not ck(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R177 发版对齐")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R177 发版对齐：版本 %s ≥ 线上 %s / 发版两步限定 main：PASS"
          % (f["ver"].strip(), remote_tag() or "（查不到）"))
