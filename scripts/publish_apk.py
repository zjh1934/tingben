#!/usr/bin/env python3
"""把本轮构建出的 APK 推到公开仓库 tingben-apk，产出无需登录的固定 raw 直链。
令牌只从 GH_PAT 环境变量读取（CI Secret），不落盘、不打印、不进仓库文件。

R391 · 安装包改成「软件名-v版本.apk」：用户下载器里好几个项目全是
app-debug.apk，分不清谁是谁。发版时同时放两份同字节文件：
  tingben-v<版本>.apk  ← 主名，Release 资产与订阅源 enclosure 都指向它
  app-debug.apk        ← 兼容旧名，只给还没刷新的老直链兜底，不再新增混淆
订阅源只推主名，所以下载器以后下到的文件名一定带软件名和版本号。
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
# R391：主文件名带软件名与版本号，下载器队列里一眼分得清。
APK_NAME = "tingben-v" + VER + ".apk"
if VER == "unknown" or "app-debug" in APK_NAME or VER not in APK_NAME:
    sys.exit("安装包名没带软件名+版本：" + APK_NAME)
NOW = __import__("datetime").datetime.now(
    __import__("datetime").timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
ATOM = ('''<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>听本 · 安装包更新</title>
  <id>https://github.com/zjh1934/tingben/releases</id>
  <link rel="self" type="application/atom+xml" href="https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom"/>
  <link rel="alternate" type="text/html" href="https://github.com/zjh1934/tingben/releases"/>
  <updated>%s</updated>
  <subtitle>每条更新都带可直接下载的 APK 附件（enclosure），下载器按此取包；文件名含软件名与版本号。</subtitle>
  <entry>
    <title>听本 v%s</title>
    <id>https://github.com/zjh1934/tingben/releases/tag/v%s</id>
    <link rel="alternate" type="text/html" href="https://github.com/zjh1934/tingben/releases/tag/v%s"/>
    <link rel="enclosure" type="application/vnd.android.package-archive" length="%d" href="https://cdn.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/%s"/>
    <updated>%s</updated>
    <summary>听本 v%s · 安装包 %s · %d 字节。</summary>
  </entry>
</feed>
''') % (NOW, VER, VER, VER, len(raw), APK_NAME, NOW, VER, APK_NAME, len(raw))
if 'rel="enclosure"' not in ATOM:
    sys.exit("订阅源缺少 enclosure，下载器会拿到 HTML 而不是 APK")
if APK_NAME not in ATOM:
    sys.exit("订阅源附件没指向带版本名的安装包")

README = ("# 听本安装包\n\n"
          "每次发版提供 `tingben-v<版本>.apk`（软件名+版本号，下载器里一眼分清是哪个项目、哪个版本）。\n"
          "订阅源 `updates.atom` 的附件就指向它，下载器按此取包。\n"
          "`app-debug.apk` 是同内容的兼容旧名，只给还没刷新的老直链兜底，不会再新增分不清的条目。\n"
          "源码与 CI 在 zjh1934/tingben。\n")
c, readme_b = api("POST", "/git/blobs",
    {"content": base64.b64encode(README.encode()).decode(), "encoding": "base64"})
if c not in (200, 201): sys.exit("README blob 失败 %s %s" % (c, readme_b))

c, ref = api("GET", "/git/ref/heads/main")
if c != 200: sys.exit("读 main 失败 %s" % c)
parent = ref["object"]["sha"]
# R378：原来这里写死 base_tree=None，等于每次发版把整个公开仓重建成
# 「只有 APK + README」，把订阅源 updates.atom 与 Pages 静态页全删了，
# 下载器再抓就是 404。改成继承远端 tree，只覆盖本次文件。
c, head_commit = api("GET", "/git/commits/" + parent)
if c != 200: sys.exit("读父提交失败 %s" % c)
base_tree = head_commit["tree"]["sha"]
atom_sha = api("POST", "/git/blobs",
    {"content": base64.b64encode(ATOM.encode()).decode(), "encoding": "base64"})[1]["sha"]
c, t = api("POST", "/git/trees", {"base_tree": base_tree,
    "tree": [
        # R391 主名：带软件名+版本，订阅源 / Release 都用它
        {"path": APK_NAME, "mode": "100644", "type": "blob", "sha": b["sha"]},
        # R391 兼容旧名：同字节，只为老直链不 404；订阅源不再推它
        {"path": "app-debug.apk", "mode": "100644", "type": "blob", "sha": b["sha"]},
        {"path": "README.md", "mode": "100644", "type": "blob", "sha": readme_b["sha"]},
        {"path": "updates.atom", "mode": "100644", "type": "blob", "sha": atom_sha},
        {"path": "docs/updates.atom", "mode": "100644", "type": "blob", "sha": atom_sha}]})
if c not in (200, 201): sys.exit("tree 失败 %s %s" % (c, t))
c, cm = api("POST", "/git/commits",
    {"message": "release: 听本 %s · %s %d 字节" % (VER, APK_NAME, len(raw)),
     "tree": t["sha"], "parents": [parent]})
if c not in (200, 201): sys.exit("commit 失败 %s" % c)
c, r = api("PATCH", "/git/refs/heads/main", {"sha": cm["sha"], "force": False})
if c not in (200, 201): sys.exit("ref 失败 %s %s" % (c, r))
print("已发布", cm["sha"][:8], "主名", APK_NAME, "（旧名 app-debug.apk 同字节兜底）")

# R406 · 真机截图实证：下载器连 raw.githubusercontent.com 连续失败 4 次、
# 列表停在 v1.1.57。self/enclosure 改走 jsdelivr 镜像；@main 有缓存，
# 发版后主动 purge 一次（失败不阻断，APK 文件名带版本号本就是新路径无缓存）。
try:
    import urllib.request
    purge = "https://purge.jsdelivr.net/gh/zjh1934/ting-ben-apk@main/updates.atom"
    urllib.request.urlopen(urllib.request.Request(purge,
        headers={"User-Agent": "tingben-release"}), timeout=10).read()
    print("已刷新订阅源镜像缓存")
except Exception as ex:
    print("订阅源镜像缓存刷新失败（不阻断发版）:", type(ex).__name__)
