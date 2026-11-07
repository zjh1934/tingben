#!/usr/bin/env python3
"""把本地工作区增量推到 tingben-android/main（GitHub Git Trees API）。

为什么不用 git push：本环境实测 github.com 的 git 通路返回 403，API 通路正常。
做法：先拉远端 tree 拿每个文件的 blob sha，本地按 git blob 算法算 sha，
只上传不一致的文件；POST /git/trees 带 base_tree。
R89 起：docs/ 与 scripts/ 这两个活跃目录按本地清单对齐——远端有、本地没有的
条目显式删除（原文都在归档包里，可回查）；其余目录仍一律保留，不误删。
凭据从 git remote 的 URL 里取，不落新文件、不打印。
"""
import base64, hashlib, json, os, pathlib, re, sys, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = "zjh1934/tingben-android"
API = "https://api.github.com/repos/" + REPO

_url = os.popen("git -C %s remote get-url origin" % ROOT).read().strip()
_m = re.search(r"https://([^@]+)@github\.com/", _url)
if not _m:
    sys.exit("取不到凭据")
TOKEN = _m.group(1)
H = {"Authorization": "token " + TOKEN, "Accept": "application/vnd.github+json",
     "Content-Type": "application/json", "X-GitHub-Api-Version": "2022-11-28",
     "User-Agent": "tingben-sync"}

def api(method, path, data=None, timeout=120):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(API + path, data=body, method=method, headers=H)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        loc = e.headers.get("Location")
        if e.code in (301, 302, 307, 308) and loc:
            req2 = urllib.request.Request(loc, data=body, method=method, headers=H)
            try:
                with urllib.request.urlopen(req2, timeout=timeout) as r:
                    return r.status, json.loads(r.read() or b"{}")
            except urllib.error.HTTPError as e2:
                return e2.code, json.loads(e2.read() or b"{}")
        return e.code, json.loads(e.read() or b"{}")

SKIP_DIRS = {".git", "reference", "build", "app/build", ".gradle", ".idea",
             "__pycache__", ".cxx", "logs", "build-report", "node_modules"}
SKIP_FILE = re.compile(r".*\.(gguf|bin|safetensors|apk|jks|keystore|part|sha256|pyc|zip|tar\.gz)$")
LOCAL_ONLY = {"scripts/sync_remote.py"}

DELETE_PATHS = {
    "app/src/main/assets/prompt_zh.txt",
    "app/src/main/assets/prompt_zh_v1.txt",
    "contracts/prompt_zh.txt",
    "scripts/r42_verify.py", "scripts/r45_verify.py", "scripts/r54_verify.py",
    "scripts/r60_verify.py", "scripts/r61_verify.py", "scripts/r65_verify.py",
    "scripts/r60_apk_audit.py", "scripts/r61_apk_audit.py", "scripts/r65_apk_audit.py",
} | {"docs/" + n for n in ['PROMPT_REDESIGN.md', '标注工厂_输出契约.md', '本次测试_语言模型生效判定卡.md', 'R27_真机日志定案_标注为何没进合成.md', 'R55_设置页与语言模型设置项_代码实读与依据.md', 'R55_设置页项清单骨架.png', 'R56_更会写JSON的中文无限制语言模型调研.md', 'R57_换模型联动表.md', 'R58_设置页与语言模型设置页_项清单v2.md', 'R60_P0换模型与双页拆分_落地.md']}

def git_blob_sha(b: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(b)).encode() + b"\0" + b).hexdigest()

files = {}
for p in ROOT.rglob("*"):
    if not p.is_file():
        continue
    rel = p.relative_to(ROOT).as_posix()
    if any(rel == d or rel.startswith(d + "/") for d in SKIP_DIRS):
        continue
    if SKIP_FILE.match(rel) or rel.startswith(".env") or rel in LOCAL_ONLY:
        continue
    files[rel] = p.read_bytes()
print("本地候选文件:", len(files))

code, tree = api("GET", "/git/trees/main?recursive=1")
if code != 200:
    sys.exit("拉远端 tree 失败 %s %s" % (code, tree))
if tree.get("truncated"):
    sys.exit("远端 tree 被截断，停止以免误判")
remote = {e["path"]: e["sha"] for e in tree["tree"] if e["type"] == "blob"}
print("远端已有 blob:", len(remote))

changed = {k: v for k, v in files.items() if remote.get(k) != git_blob_sha(v)}
print("需要上传:", len(changed))
for k in sorted(changed):
    print("  +", k)

def upload(item):
    path, data = item
    c, b = api("POST", "/git/blobs",
               {"content": base64.b64encode(data).decode(), "encoding": "base64"})
    if c not in (200, 201):
        raise RuntimeError("blob 失败 %s %s %s" % (path, c, b))
    return path, b["sha"]

entries = []
PRUNE_PREFIXES = ("docs/", "scripts/", "logs/")
auto_deletes = [p for p in remote
                if p.startswith(PRUNE_PREFIXES)
                and p not in files and p not in LOCAL_ONLY]
deletes = sorted(({p for p in DELETE_PATHS if p in remote} | set(auto_deletes)))
print("活跃目录清理:", len(auto_deletes), "份")
for p in deletes:
    entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})
    print("  -", p, "(删除)")
with ThreadPoolExecutor(max_workers=6) as ex:
    for path, sha in ex.map(upload, sorted(changed.items())):
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": sha})

code, ref = api("GET", "/git/ref/heads/main")
if code != 200:
    sys.exit("取 main ref 失败 %s" % code)
parent = ref["object"]["sha"]
code, pc = api("GET", "/git/commits/" + parent)
if code != 200:
    sys.exit("取 parent commit 失败 %s" % code)
base_tree = pc["tree"]["sha"]

if not entries:
    print("无变化，不提交")
    sys.exit(0)

code, nt = api("POST", "/git/trees", {"base_tree": base_tree, "tree": entries})
if code not in (200, 201):
    sys.exit("建 tree 失败 %s %s" % (code, nt))
msg = sys.argv[1] if len(sys.argv) > 1 else "chore: 同步本地改动"
code, cm = api("POST", "/git/commits",
               {"message": msg, "tree": nt["sha"], "parents": [parent]})
if code not in (200, 201):
    sys.exit("建 commit 失败 %s %s" % (code, cm))
code, r = api("PATCH", "/git/refs/heads/main", {"sha": cm["sha"], "force": False})
if code not in (200, 201):
    sys.exit("更新 ref 失败 %s %s" % (code, r))
print("已提交", cm["sha"][:10], "（base_tree + 显式删除 %d 条）" % len(deletes))

code, chk = api("GET", "/git/trees/main?recursive=1")
if code == 200:
    now = {e["path"]: e["sha"] for e in chk["tree"] if e["type"] == "blob"}
    bad = [k for k, v in changed.items() if now.get(k) != git_blob_sha(v)]
    still = [p for p in deletes if p in now]
    if still: bad += ["删除未生效: " + p for p in still]
    print("核对:", "全部一致 / 删除生效" if not bad else ("不一致 " + str(bad)))
    if bad:
        sys.exit(1)
