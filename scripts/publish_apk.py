#!/usr/bin/env python3
"""把本轮构建出的 APK 推到公开仓库 tingben-apk，产出无需登录的固定 raw 直链。
令牌只从 GH_PAT 环境变量读取（CI Secret），不落盘、不打印、不进仓库文件。
"""
import base64, json, os, pathlib, sys, urllib.request, urllib.error

T = os.environ.get("GH_PAT")
if not T: sys.exit("缺少 GH_PAT")
PUB = "https://api.github.com/repos/zjh1934/tingben-apk"
H = {"Authorization": f"token {T}", "Accept": "application/vnd.github+json",
     "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28",
     "User-Agent": "tingben-publish"}

def api(m, path, d=None, timeout=240):
    r = urllib.request.Request(PUB + path, data=json.dumps(d).encode() if d is not None else None,
                               method=m, headers=H)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            return x.status, json.loads(x.read() or b"{}")
    except urllib.error.HTTPError as e:
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
c, ref = api("GET", "/git/ref/heads/main")
parent = ref["object"]["sha"]
c, t = api("POST", "/git/trees", {"base_tree": None,
    "tree": [{"path": "app-debug.apk", "mode": "100644", "type": "blob", "sha": b["sha"]},
             {"path": "README.md", "mode": "100644", "type": "blob",
              "sha": api("POST", "/git/blobs",
                  {"content": base64.b64encode("# 听本安装包\n\n浏览器直接打开 app-debug.apk 即可下载；源码与签名在 zjh1934/tingben-android。\n".encode()).decode(),
                   "encoding": "base64"})[1]["sha"]}]})
if c not in (200, 201): sys.exit("tree 失败 %s %s" % (c, t))
c, cm = api("POST", "/git/commits",
    {"message": "release: 听本 APK %d 字节" % len(raw), "tree": t["sha"], "parents": [parent]})
if c not in (200, 201): sys.exit("commit 失败 %s" % c)
c, r = api("PATCH", "/git/refs/heads/main", {"sha": cm["sha"], "force": False})
if c not in (200, 201): sys.exit("ref 失败 %s %s" % (c, r))
print("已发布", cm["sha"][:8])
