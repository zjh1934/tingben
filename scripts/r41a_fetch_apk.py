#!/usr/bin/env python3
"""R41a 收尾：从公开直链仓库取回 APK，逐字节核对并落盘到 /data/workspace。"""
import base64, json, os, pathlib, re, sys, urllib.request, urllib.error, hashlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = pathlib.Path("/data/workspace/听本_app-debug.apk")
url = os.popen("git -C %s remote get-url origin" % ROOT).read().strip()
tok = re.search(r"https://([^@]+)@github\.com/", url).group(1)
H = {"Authorization": "token " + tok, "Accept": "application/vnd.github+json",
     "User-Agent": "tingben-sync"}
API = "https://api.github.com/repos"


def get(path, repo="zjh1934/tingben-android"):
    r = urllib.request.Request(API + "/" + repo + path, headers=H)
    return json.load(urllib.request.urlopen(r, timeout=120))


def git_sha(b):
    return hashlib.sha1(b"blob " + str(len(b)).encode() + b"\0" + b).hexdigest()


runs = get("/actions/runs?per_page=6")["workflow_runs"]
target = next((r for r in runs if r["run_number"] == 51), None)
if target is None:
    sys.exit("没找到 run 51")
rid = target["id"]
run = get("/actions/runs/%s" % rid)
print("run 51:", run["status"], run["conclusion"])
if run["conclusion"] != "success":
    sys.exit("CI 未成功，停止取包")
jobs = get("/actions/runs/%s/jobs" % rid)["jobs"][0]
bad = [s["name"] for s in jobs["steps"]
       if s["conclusion"] not in ("success", "skipped", None) and s["number"] <= 20]
print("失败 step:", bad or "无")
if bad:
    sys.exit("有 step 失败，停止取包")

tree = get("/git/trees/main?recursive=1", "zjh1934/tingben-apk")
ent = [e for e in tree["tree"] if e["path"] == "app-debug.apk" and e["type"] == "blob"]
if not ent:
    sys.exit("直链仓库没有 app-debug.apk")
remote_sha = ent[0]["sha"]
blob = get("/git/blobs/" + remote_sha, "zjh1934/tingben-apk")
raw = base64.b64decode(blob["content"])
print("直链 blob:", remote_sha, len(raw), "字节")
print("本地算出的 git blob sha:", git_sha(raw), "一致" if git_sha(raw) == remote_sha else "不一致")
if git_sha(raw) != remote_sha:
    sys.exit("blob 自校验失败")
if raw[:2] != b"PK":
    sys.exit("不是 APK")
OUT.write_bytes(raw)
print("已写入", OUT, OUT.stat().st_size, "字节")
