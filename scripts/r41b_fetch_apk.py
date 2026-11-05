#!/usr/bin/env python3
"""按 run 号从公开直链仓库取回 APK，校验 CI 全绿 + blob 自校验 + 落盘。

用法: python3 scripts/r41b_fetch_apk.py <run_number>
替代 r41a_fetch_apk.py（写死 51）；以后每轮交付前必跑，避免 R33 那种「本地发了版、直链还是旧的」。
"""
import base64, json, os, pathlib, re, sys, urllib.request, hashlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = pathlib.Path("/data/workspace/听本_app-debug.apk")
RUN = int(sys.argv[1]) if len(sys.argv) > 1 else sys.exit("用法: r41b_fetch_apk.py <run_number>")
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


runs = get("/actions/runs?per_page=8")["workflow_runs"]
target = next((r for r in runs if r["run_number"] == RUN), None)
if target is None:
    sys.exit("没找到 run %d" % RUN)
rid = target["id"]
run = get("/actions/runs/%s" % rid)
print("run %d:" % RUN, run["status"], run["conclusion"])
if run["conclusion"] != "success":
    sys.exit("CI 未成功，停止取包")
job = get("/actions/runs/%s/jobs" % rid)["jobs"][0]
bad = [s["name"] for s in job["steps"]
       if s["conclusion"] not in ("success", "skipped", None)]
print("失败 step:", bad or "无")
if bad:
    sys.exit("有 step 失败，停止取包")
print("step 数:", len(job["steps"]))

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
print("sha256:", hashlib.sha256(raw).hexdigest())
