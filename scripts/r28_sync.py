#!/usr/bin/env python3
# R28 · 用 Git Data API 一次 commit 推 4 个源码文件（避免 4 次 push 触发 4 次 CI），
# 再显式 dispatch 一次 workflow。路径全 ASCII，绕开之前中文路径转义误删的事故。
import base64, json, pathlib, urllib.request, urllib.error, os, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = "zjh1934/tingben-android"
TOK = os.popen("git remote get-url origin | sed -E 's#https://([^@]+)@.*#\\1#'").read().strip()
API = "https://api.github.com"

def call(path, method="GET", payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bearer " + TOK, "User-Agent": "tingben-r28",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, path, e.read()[:300].decode(errors="replace"))
        raise

FILES = [
    "app/src/main/java/com/tingben/app/core/SegPlan.kt",
    "app/src/main/java/com/tingben/app/core/Workbench.kt",
    "app/src/main/java/com/tingben/app/core/Pipeline.kt",
    "app/src/main/java/com/tingben/app/MainActivity.kt",
]

ref = call(f"/repos/{REPO}/git/ref/heads/main")
head = ref["object"]["sha"]
commit = call(f"/repos/{REPO}/git/commits/{head}")
tree_sha = commit["tree"]["sha"]
print("远端 HEAD", head[:10], "tree", tree_sha[:10])

items = []
for f in FILES:
    content = (ROOT / f).read_bytes()
    blob = call(f"/repos/{REPO}/git/blobs", "POST",
                {"content": base64.b64encode(content).decode(), "encoding": "base64"})
    items.append({"path": f, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    print("  blob", f.rsplit("/", 1)[-1], len(content), "字节", blob["sha"][:10])

tree = call(f"/repos/{REPO}/git/trees", "POST",
            {"base_tree": tree_sha, "tree": items})
new = call(f"/repos/{REPO}/git/commits", "POST", {
    "message": "R28: 段级重做（ReaderVoice 单元失效）+ 语言模型生效一眼判定 + 段/块索引对齐",
    "tree": tree["sha"], "parents": [head]})
call(f"/repos/{REPO}/git/refs/heads/main", "PATCH", {"sha": new["sha"]})
print("已推送 commit", new["sha"][:12])

try:
    call(f"/repos/{REPO}/actions/workflows/ci.yml/dispatches", "POST", {"ref": "main"})
    print("workflow_dispatch 已触发")
except Exception as e:
    print("dispatch 未确认（push 事件可能已触发）：", type(e).__name__)

runs = call(f"/repos/{REPO}/actions/runs?per_page=3")["workflow_runs"]
for r in runs:
    print("run", r["run_number"], r["status"], r["conclusion"], r["head_sha"][:10])
