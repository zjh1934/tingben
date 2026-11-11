#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R242 · 发版前置自检（按项目真实链路重写）。

R240/R241 栽过的坑：把「本地 Android SDK / Gradle / JDK」「git remote」「本地 APK 产物」
当成发版必要条件，于是永远 BLOCK、连续几轮拿环境不齐当理由不推进。核对后确认：
  · 真编译在 GitHub Actions 上（setup-java → setup-gradle → assembleDebug），
    本地有没有 SDK 根本不影响出包；
  · 项目铁律是「禁 git push」，发版走 GitHub API 快进 refs/heads/main，
    git remote 本来就不该配；
  · APK 由 CI 产出并上传 Release/直链仓，本地不需要预置产物；
  · 令牌在**仓库外** `.secrets/gh_token`（相对仓库根目录的父级），
    不是仓库内的 `.secrets/`；R240 只查了仓库内，误报「没有凭据」。

现在的六项，全部对准真实链路，只查可达性/存在性，**不推送、不改版本、不产直链、
不打印令牌**：
  ① CI 有真编译与发版三段（assembleDebug / gh-release / publish_apk）
  ② 发版脚本齐（release_preflight.py + publish_apk.py）
  ③ 仓库外凭据文件可读且非空
  ④ api.github.com 可达
  ⑤ 远端 main 可读（令牌有 contents 权限）
  ⑥ 工作区干净（没有没提交的改动）

结论只有 READY（六项齐，才允许升版本 / 快进 main / 贴直链）或 BLOCK（列缺项）。
CI 只挂 `--selftest`；正向由发版轮手动跑，不挂进每次 push 的 workflow。

用法：
  python3 scripts/preflight.py              # 实跑，缺项则退出码 1
  python3 scripts/preflight.py --selftest   # 纯内存反向注入，源文件零写入
"""
import json
import pathlib
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CI = ROOT / ".github/workflows/ci.yml"
TOKEN = ROOT.parent / ".secrets" / "gh_token"   # 仓库外，R240 查错位置


def _ci_ok():
    t = CI.read_text(encoding="utf-8", errors="replace")
    return all(k in t for k in ("gradle assembleDebug",
                                "softprops/action-gh-release@v2",
                                "python3 scripts/publish_apk.py",
                                "actions/setup-java@v4",
                                "gradle/actions/setup-gradle@v4"))


def _scripts_ok():
    # R242 · 发版主工具 rc_push.py 在**仓库外**（/data/user_persistent_data/听本/），
    # R240 只查了仓库内 scripts/ 才误报「没有发版脚本」。
    rc = ROOT.parent / "rc_push.py"
    return rc.is_file() and all((ROOT / "scripts" / n).is_file()
                                for n in ("release_preflight.py", "publish_apk.py"))


def _creds_ok():
    # 只看存在与非空，绝不读取内容、绝不打印
    try:
        return TOKEN.is_file() and TOKEN.stat().st_size > 0
    except OSError:
        return False


def _token():
    try:
        return TOKEN.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _api(path, timeout=10):
    t = _token()
    if not t:
        return None
    r = urllib.request.Request(
        "https://api.github.com" + path,
        headers={"Authorization": "token " + t,
                 "Accept": "application/vnd.github+json",
                 "User-Agent": "tingben-preflight"})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            return json.load(x)
    except Exception:
        return None


def _net_ok():
    # R242 · 根路径探活偶发 8 秒超时会误报 BLOCK（线上 Release 其实已查得到）。
    # 改成与 _remote_ok 同源的带令牌仓库请求，并允许一次重试；两者口径对齐。
    for _ in range(2):
        if _api("/repos/zjh1934/tingben") is not None:
            return True
    return False


def _remote_ok():
    d = _api("/repos/zjh1934/tingben/git/ref/heads/main")
    return bool(d and d.get("object", {}).get("sha"))


def _clean_ok():
    # R246 · 单次 8 秒超时在机器忙时会把「干净」误判成脏（实测过一次），
    # 与 R242 修 _net_ok 的抖动同源。给两次机会；只有两次都明确跑完且
    # porcelain 为空才算干净，任一异常仍当缺项，不放宽判定。
    for _ in range(2):
        try:
            out = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                                 capture_output=True, text=True, timeout=20)
            if out.returncode == 0 and not out.stdout.strip():
                return True
            if out.returncode == 0 and out.stdout.strip():
                return False       # 真脏，重试也没用
        except (OSError, subprocess.SubprocessError):
            continue
    return False


COLLECTORS = {
    "ci": _ci_ok,
    "scripts": _scripts_ok,
    "creds": _creds_ok,
    "net": _net_ok,
    "remote": _remote_ok,
    "clean": _clean_ok,
}
LABELS = {
    "ci": "CI 真编译与发版三段",
    "scripts": "发版脚本 release_preflight / publish_apk",
    "creds": "仓库外 .secrets/gh_token",
    "net": "api.github.com 可达",
    "remote": "远端 main 可读（令牌权限）",
    "clean": "工作区干净",
}


def collect():
    out = {}
    for k, fn in COLLECTORS.items():
        try:
            out[k] = bool(fn())
        except Exception:
            out[k] = False   # 探针自己炸了也算缺项，绝不当成齐
    return out


def verdict(flags):
    """纯函数：缺一项就不能 READY。selftest 只喂内存快照，不碰网络/文件系统。"""
    missing = [k for k in COLLECTORS if not flags.get(k, False)]
    return ("READY", []) if not missing else ("BLOCK", missing)


def report(flags):
    for k in COLLECTORS:
        print("  [%s] %s" % ("OK  " if flags.get(k) else "MISS", LABELS[k]))
    state, missing = verdict(flags)
    if state == "BLOCK":
        print("发版前置：BLOCK（缺 %d 项：%s）" %
              (len(missing), "、".join(LABELS[k] for k in missing)))
        print("本轮不得升版本、不得报「已发」、不得贴下载直链。")
        return 1
    print("发版前置：READY（六项齐）→ 才允许升版本 / 快进 main / 发版")
    return 0


def self_guard(src, ci):
    """守门自身：六个探针、缺项判定、BLOCK 结论、CI 挂载一个都不能被摘。"""
    e = []
    for name in COLLECTORS:
        if '"%s": _%s_ok,' % (name, name) not in src:
            e.append("探针被摘: " + name)
    if "def _ci_ok():" not in src or "TOKEN = ROOT.parent" not in src:
        e.append("CI 探针 / 仓库外凭据定位被改")
    if "rc = ROOT.parent / \"rc_push.py\"" not in src:
        e.append("发版主工具又查回仓库内")
    if "missing = [k for k in COLLECTORS if not flags.get(k, False)]" not in src:
        e.append("缺项判定被改写")
    if '("READY", []) if not missing else ("BLOCK", missing)' not in src:
        e.append("BLOCK 结论被放宽")
    if "不得贴下载直链" not in src:
        e.append("禁贴直链的话术被摘")
    if "python3 scripts/preflight.py --selftest" not in ci:
        e.append("CI 漏挂 preflight --selftest")
    if "for _ in range(2):" not in src or "timeout=20" not in src:
        e.append("工作区探针瞬时抖动重试被摘（会误报 BLOCK 卡住发版）")
    return e


def selftest():
    src = pathlib.Path(__file__).read_text(encoding="utf-8")
    ci = CI.read_text(encoding="utf-8", errors="replace")
    base = self_guard(src, ci)
    assert not base, "基线自己就红了: " + "; ".join(base)

    cases = [
        ("全缺", {}, "BLOCK", 6),
        ("只齐 CI", {"ci": True}, "BLOCK", 5),
        ("五项齐但工作区脏", {"ci": True, "scripts": True, "creds": True,
                          "net": True, "remote": True}, "BLOCK", 1),
        ("六项齐", {k: True for k in COLLECTORS}, "READY", 0),
        ("探针异常当缺项", {k: True for k in COLLECTORS} | {"clean": None},
         "BLOCK", 1),
    ]
    bad = 0
    for name, flags, want, n in cases:
        st, miss = verdict(flags)
        if st != want or len(miss) != n:
            print("  用例失败:", name, st, miss); bad += 1
        else:
            print("  用例 %-16s %s 缺 %d" % (name, st, n))

    # R241 自指坑：锚点在 COLLECTORS / self_guard / cuts 里各出现一次，
    # 只 replace 第一处会恒不红；这里全量替换（纯内存，源文件零写入）。
    cuts = [
        ("摘 CI 探针", src.replace('"ci": _ci_ok,', '"ci": _net_ok,'), ci),
        ("摘脚本探针", src.replace('"scripts": _scripts_ok,', ''), ci),
        ("摘凭据探针", src.replace('"creds": _creds_ok,', ''), ci),
        ("摘网络探针", src.replace('"net": _net_ok,', ''), ci),
        ("摘远端探针", src.replace('"remote": _remote_ok,', ''), ci),
        ("摘工作区探针", src.replace('"clean": _clean_ok,', ''), ci),
        ("凭据又查回仓库内", src.replace("TOKEN = ROOT.parent", "TOKEN = ROOT"), ci),
        ("发版工具又查回仓库内", src.replace('rc = ROOT.parent / "rc_push.py"',
                                       'rc = ROOT / "scripts/rc_push.py"'), ci),
        ("缺项判定放宽", src.replace(
            "missing = [k for k in COLLECTORS if not flags.get(k, False)]",
            "missing = []"), ci),
        ("恒报 READY", src.replace(
            '("READY", []) if not missing else ("BLOCK", missing)',
            '("READY", [])'), ci),
        ("禁贴直链话术被摘", src.replace("不得贴下载直链", ""), ci),
        ("CI 漏挂", src, ci.replace("python3 scripts/preflight.py --selftest", "", 1)),
    ]
    for name, s2, c2 in cuts:
        if self_guard(s2, c2):
            print("  反向注入 %-16s 拦下" % name)
        else:
            print("  反向注入漏放:", name); bad += 1
    print("用例 %d + 反向注入 %d，漏放 %d" % (len(cases), len(cuts), bad))
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    print("听本 · 发版前置自检（R242 真实链路）")
    sys.exit(report(collect()))
