#!/usr/bin/env python3
"""把本轮构建出的 APK 推到公开仓库 tingben-apk，产出无需登录的固定 raw 直链。
令牌只从 GH_PAT 环境变量读取（CI Secret），不落盘、不打印、不进仓库文件。
"""
import base64, json, os, pathlib, sys, urllib.request, urllib.error

T = os.environ.get("GH_PAT")
if not T: sys.exit("缺少 GH_PAT")
PUB = "https://api.github.com/repos/zjh1934/ting-ben-apk"
H = {"Authorization": f"token {T}", "Accept": "application/vnd.github+json",
     "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28",
     "User-Agent": "tingben-publish"}

def api(m, path, d=None, timeout=240):
    body = json.dumps(d).encode() if d is not None else None
    r = urllib.request.Request(PUB + path, data=body, method=m, headers=H)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            return x.status, json.loads(x.read() or b"{}")
    except urllib.error.HTTPError as e:
        loc = e.headers.get("Location")
        if e.code in (301, 302, 307, 308) and loc:
            r2 = urllib.request.Request(loc, data=body, method=m, headers=H)
            try:
                with urllib.request.urlopen(r2, timeout=timeout) as x:
                    return x.status, json.loads(x.read() or b"{}")
            except urllib.error.HTTPError as e2:
                return e2.code, json.loads(e2.read() or b"{}")
        return e.code, json.loads(e.read() or b"{}")

apk = None
for f in pathlib.Path("app/build/outputs/apk/debug").glob("*.apk"):
    if "-unsigned" not in f.name: apk = f
if apk is None: sys.exit("没找到 APK")
raw = apk.read_bytes()
if raw[:2] != b"PK": sys.exit("产物不是 APK")
print("APK", apk.name, len(raw), "字节")

c, b = api("POST", "/git/blobs", {"content": base64.b64encode(raw).decode(), "encoding": "base64"})
if c not in (200, 201): sys.exit("blob 失败 %s %s" % (c, b))
# R378：订阅源必须跟着每次发版一起更新，否则下载器永远停在旧版本。
# 附件指向同仓 raw APK（application/octet-stream，不会像 release 网页那样回 text/html）。
VER = (pathlib.Path("version.txt").read_text(encoding="utf-8").strip()
       if pathlib.Path("version.txt").exists() else "unknown")
NOW = __import__("datetime").datetime.now(
    __import__("datetime").timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
ATOM = ('''<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>听本 · 安装包更新</title>
  <id>https://github.com/zjh1934/tingben/releases</id>
  <link rel="self" type="application/atom+xml" href="https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom"/>
  <link rel="alternate" type="text/html" href="https://github.com/zjh1934/tingben/releases"/>
  <updated>%s</updated>
  <subtitle>每条更新都带可直接下载的 APK 附件（enclosure），下载器按此取包。</subtitle>
  <entry>
    <title>听本 %s</title>
    <id>https://github.com/zjh1934/tingben/releases/tag/v%s</id>
    <link rel="alternate" type="text/html" href="https://github.com/zjh1934/tingben/releases/tag/v%s"/>
    <link rel="enclosure" type="application/vnd.android.package-archive" length="%d" href="https://raw.githubusercontent.com/zjh1934/ting-ben-apk/main/app-debug.apk"/>
    <updated>%s</updated>
    <summary>听本 %s · APK %d 字节。</summary>
  </entry>
</feed>
''') % (NOW, VER, VER, VER, len(raw), NOW, VER, len(raw))
if 'rel="enclosure"' not in ATOM:
    sys.exit("订阅源缺少 enclosure，下载器会拿到 HTML 而不是 APK")

c, readme_b = api("POST", "/git/blobs",
    {"content": base64.b64encode(
        ("# 听本安装包\n\n浏览器直接打开 app-debug.apk 即可下载；"
         "订阅源 updates.atom 由下载器使用；源码与 CI 在 zjh1934/tingben。\n").encode()).decode(),
     "encoding": "base64"})
if c not in (200, 201): sys.exit("README blob 失败 %s %s" % (c, readme_b))

c, ref = api("GET", "/git/ref/heads/main")
if c != 200: sys.exit("读 main 失败 %s" % c)
parent = ref["object"]["sha"]
# R378：原来这里写死 base_tree=None，等于每次发版把整个公开仓重建成
# 「只有 APK + README」，把订阅源 updates.atom 与 Pages 静态页全删了，
# 下载器再抓就是 404。改成继承远端 tree，只覆盖本次两个文件。
c, head_commit = api("GET", "/git/commits/" + parent)
if c != 200: sys.exit("读父提交失败 %s" % c)
base_tree = head_commit["tree"]["sha"]
c, t = api("POST", "/git/trees", {"base_tree": base_tree,
    "tree": [{"path": "app-debug.apk", "mode": "100644", "type": "blob", "sha": b["sha"]},
             {"path": "README.md", "mode": "100644", "type": "blob", "sha": readme_b["sha"]},
             {"path": "updates.atom", "mode": "100644", "type": "blob",
              "sha": api("POST", "/git/blobs", {"content": base64.b64encode(ATOM.encode()).decode(),
                                               "encoding": "base64"})[1]["sha"]},
             {"path": "docs/updates.atom", "mode": "100644", "type": "blob",
              "sha": api("POST", "/git/blobs", {"content": base64.b64encode(ATOM.encode()).decode(),
                                               "encoding": "base64"})[1]["sha"]}]})
if c not in (200, 201): sys.exit("tree 失败 %s %s" % (c, t))
c, cm = api("POST", "/git/commits",
    {"message": "release: 听本 APK %d 字节" % len(raw), "tree": t["sha"], "parents": [parent]})
if c not in (200, 201): sys.exit("commit 失败 %s" % c)
c, r = api("PATCH", "/git/refs/heads/main", {"sha": cm["sha"], "force": False})
if c not in (200, 201): sys.exit("ref 失败 %s %s" % (c, r))
print("已发布", cm["sha"][:8])
