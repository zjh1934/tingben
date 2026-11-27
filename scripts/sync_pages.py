#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R411 · 把源码仓 `docs/pages/` 两页同步到实际托管位置。

工程文件地图.md 从 R392 起就写「直链仓 docs/*.html 由 docs/pages/ 同步过去」，
但一直**没有同步机制**：发版脚本只推 APK / README / atom，两页每次改真值源
都不会到线上。R411 实测三处全漂移——

  · 分发仓 docs/index.html 少了「版本入口与检查更新」整块，还留着
    「不开启时 App 无出站流量」（R408 之后关于页会读一次公开订阅，已不成立）
  · 分发仓 docs/relay.html 少了 R401 的 busy 排队提示
  · 私有仓 docs/index.html 更旧，连 M4A 说明与「手机要开中继」都没有

Pages 实际托管在 **ting-ben-apk/docs/**（homepage 指向它），不是源码仓。
私有仓那份只是仓库内备份，不托管 Pages，所以相对链接要换成绝对地址。

用法：
  python3 scripts/sync_pages.py --check   # 只报告差异，不写
  python3 scripts/sync_pages.py           # 同步（GH_PAT 环境变量，CI Secret）

令牌只从环境变量读，不落盘、不打印。更新已存在文件必须带 sha（R404 教训）。
"""
import base64
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
T = os.environ.get("GH_PAT")
HDR = {"Authorization": "token " + (T or ""), "User-Agent": "tingben-pages",
       "Accept": "application/vnd.github+json", "Content-Type": "application/json",
       "X-GitHub-Api-Version": "2022-11-28"}

PAGES_HOME = "https://zjh1934.github.io/ting-ben-apk/"

# 私有仓那份不托管 Pages：把相对首页链接换成绝对地址，并说明自己只是备份。
PRIVATE_BACKUP_NOTE = (
    '<p class="note">本页是私有仓内的备份，实际入口在 '
    '<a href="' + PAGES_HOME + 'relay.html">项目 Pages 的提交页</a>；'
    '两边内容由 <code>sync_pages.py</code> 保持一致。</p>'
)


def private_variant(relay: str) -> str:
    out = relay.replace(
        '<p class="note"><a href="index.html">← 听本首页</a> · 这一页才提交文字</p>',
        '<p class="note"><a href="' + PAGES_HOME + '">← 听本首页（项目 Pages）</a>'
        ' · 这一页才提交文字</p>', 1)
    out = out.replace('<body>', '<body>\n' + PRIVATE_BACKUP_NOTE, 1)
    return out


TARGETS = [
    ("ting-ben-apk", "docs/index.html", "index.html"),
    ("ting-ben-apk", "docs/relay.html", "relay.html"),
    ("tingben-relay", "docs/index.html", "@private"),
]


def api(repo, method, path, data=None, timeout=60):
    url = "https://api.github.com/repos/zjh1934/" + repo + path
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers=HDR)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as x:
            return x.status, json.loads(x.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def remote(repo, path):
    st, d = api(repo, "GET", "/contents/" + path + "?ref=main")
    if st != 200:
        return None, None
    return base64.b64decode(d["content"]).decode("utf-8", "replace"), d["sha"]


def main():
    if not T:
        sys.exit("缺少 GH_PAT")
    check = "--check" in sys.argv
    index = (ROOT / "docs/pages/index.html").read_text(encoding="utf-8")
    relay = (ROOT / "docs/pages/relay.html").read_text(encoding="utf-8")
    src = {"index.html": index, "relay.html": relay, "@private": private_variant(relay)}

    changed = 0
    for repo, path, key in TARGETS:
        want = src[key]
        cur, sha = remote(repo, path)
        if cur == want:
            print("一致  %-16s %s" % (repo, path))
            continue
        print("%s %-16s %s (%dB → %dB)" %
              ("将同步" if check else "同步", repo, path,
               len(cur or ""), len(want)))
        changed += 1
        if check:
            continue
        # R404 · 更新已存在文件必须带当前 sha，否则 422
        payload = {"message": "docs: R411 同步 docs/pages 真值源",
                   "content": base64.b64encode(want.encode()).decode(),
                   "encoding": "base64", "branch": "main"}
        if sha:
            payload["sha"] = sha
        st, d = api(repo, "PUT", "/contents/" + path, payload)
        if st not in (200, 201):
            sys.exit("写 %s/%s 失败 HTTP %s %s" %
                     (repo, path, st, str(d)[:180]))
        print("   已写入", st)
    print("差异 %d 处%s" % (changed, "（--check，未写入）" if check else "，已同步"))


if __name__ == "__main__":
    main()
