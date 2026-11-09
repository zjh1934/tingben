#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AOCI-CODE 协议级治理守门（本仓适配层）。

背景：用户点名把 AOCI-CODE 接入本仓，专治「新会话要重新读一遍整个仓库」。
官方 CLI 二进制在构建环境拿不到 Release 资产（CDN 被出网策略拦），且无
Go 1.26 工具链与模块代理，所以用本脚本实现等价治理：

  · 校验 Root / Meta / Code 三个认知卷的公开协议格式（cognition-volumes/v1
    + object-fras-v2），标签字典、FRAS 四段、S 配额、R 引用、受管覆盖率
  · 校验噪声边界（归档不进索引）与私有拓扑（本机路径 / 凭据不进资产）
  · 校验 .aoci/baseline.json 的内容哈希，发现「代码改了、索引没维护」的漂移
  · --refresh 重算哈希基线并按真实行数校正规模标签（语义仍由模型创作）
  · --selftest 反向注入，守门被摘或放宽时必须红

如实边界：本脚本**不是**官方 aoci CLI，不宣称跑过 `aoci verify` / `aoci check`。
装了官方二进制的机器上，官方 verify/check 与 live Guide 仍然是权威。

用法：python3 scripts/aoci_check.py [--check | --refresh | --selftest]
"""
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CFG = ROOT / ".aoci" / "config.json"
BASELINE = ROOT / ".aoci" / "baseline.json"

def atomic_write(path, text):
    """R170 · 沙盒/网络 FS 偶发 Stale file handle：写新 inode 再原子替换目录项，
    避免对旧句柄直接 write 把基线写半截（基线缺项会让下轮全部假红）。"""
    import os as _os
    p = pathlib.Path(str(path) + ".tmpwrite")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    _os.replace(p, path)
ASSETS = {
    "root": ROOT / "aoci.txt",
    "meta": ROOT / "aoci.meta.txt",
    "code": ROOT / "aoci.code.txt",
    "agents": ROOT / "AGENTS.md",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

LAYERS = set("CEADKM PIRLFOTSXZ".replace(" ", ""))
MODULES = set("GU BDI NMS CORPW AHLVQEZ".replace(" ", ""))
PRIVATE_MARKERS = ("/data/user_persistent_data/听本", "/data/workspace", "/home/", "ghp_", "github_pat_",
                   "SANDBOX_EGRESS", "/Users/", "C:\\\\")
ROOT_PREFIX = "/tingben/"

SEC_RE = re.compile(r"^===([^=]+)===$")
ENTRY_RE = re.compile(r"^([^\[\s][^\[]*)\[([A-Z][A-Z][1-9][LMST])\]:\s*(.*)$")
R_REF_RE = re.compile(r"code:([^,\s|]+)")


def read(p):
    try:
        return p.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def load_cfg():
    try:
        return json.loads(read(CFG))
    except (ValueError, OSError):
        return {}


def managed_files(cfg):
    """受管对象集合 = 根白名单 + 三个全量目录 + scripts 关键名单。"""
    roots = cfg.get("managed_roots", {})
    out = set(roots.get("files", []))
    for d in roots.get("dirs", []):
        base = ROOT / d
        if not base.is_dir():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            rel = p.relative_to(ROOT).as_posix()
            if any(rel.startswith(x.rstrip("/") + "/") for x in cfg.get("exclude_dirs", [])):
                continue
            out.add(rel)
    for s in roots.get("scripts", []):
        out.add("scripts/" + s)
    return sorted(out)


def line_count(rel):
    p = ROOT / rel
    if not p.exists():
        return -1
    return len(read(p).splitlines())


def scale_of(n):
    return "L" if n > 400 else ("M" if n >= 200 else ("S" if n >= 100 else "T"))


def sha_of(rel):
    p = ROOT / rel
    if not p.exists():
        return ""
    return hashlib.sha256(p.read_bytes()).hexdigest()


def size_of(rel):
    p = ROOT / rel
    return p.stat().st_size if p.exists() else -1


def parse_index(text):
    """返回 (条目列表, 解析错误列表)。条目 = (rel, tag, body, section)。"""
    entries, errs = [], []
    section = None  # None = 还没遇到 section 头；空串 = 根目录
    for i, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        m = SEC_RE.match(line)
        if m:
            raw = m.group(1).strip()
            if not raw.startswith(ROOT_PREFIX):
                errs.append("第 %d 行 section 头不是 /tingben/ 历史坐标：%s" % (i, raw[:50]))
            section = raw[len(ROOT_PREFIX):]
            if section and not section.endswith("/"):
                section += "/"
            continue
        if line.startswith("#"):
            continue
        em = ENTRY_RE.match(line)
        if not em:
            errs.append("第 %d 行不是合法 Entry：%s" % (i, line[:60]))
            continue
        name, tag, body = em.group(1), em.group(2), em.group(3)
        if section is None:
            errs.append("第 %d 行 Entry 没有所属 section" % i)
            continue
        parts = body.split(" | ")
        if len(parts) != 4 or not all(
                p.startswith(k) for p, k in zip(parts, ("F:", "R:", "A:", "S:"))):
            errs.append("第 %d 行 FRAS 四段不全或分隔符错：%s" % (i, name))
            continue
        rel = (section + name).lstrip("/")
        entries.append((rel, tag, parts, i))
    return entries, errs


def quota_of(tag):
    c = int(tag[2])
    return 600 if c >= 8 else (200 if c >= 4 else 50)


def lookup(keyword, state=None):
    """按文件名 / 标签 / F/R/A/S 内容查地图，给「新会话先读地图」用。

    这不是搜索源码：返回的是已沉淀的认知（职责、强关系、契约、坑），
    用来决定要不要打开某个文件，而不是重新把整个工程翻一遍。
    """
    txt = read(ASSETS["code"])
    section = ""
    hits = []
    for i, line in enumerate(txt.splitlines(), 1):
        m = SEC_RE.match(line)
        if m:
            raw = m.group(1).strip()
            section = raw[len(ROOT_PREFIX):] if raw.startswith(ROOT_PREFIX) else raw.lstrip("/")
            if section and not section.endswith("/"):
                section += "/"
            continue
        em = ENTRY_RE.match(line)
        if not em:
            continue
        name, tag, body = em.group(1), em.group(2), em.group(3)
        rel = (section + name).lstrip("/")  # 根 section 是空串，也是合法坐标
        if keyword.lower() in (rel + " " + tag + " " + body).lower():
            parts = body.split(" | ")
            hits.append((rel, tag, parts, i))
    return hits


def check(state=None):
    """state: 可选的 {path: text} 覆盖，用于反向注入；不改盘。"""
    state = state or {}
    txt = {k: state.get(k, read(v)) for k, v in ASSETS.items()}
    cfg = load_cfg()
    if "cfg" in state:
        try:
            cfg = json.loads(state["cfg"])
        except ValueError:
            cfg = {}
    e = []

    # 1 · Root / Meta 结构
    if "#AOCI-ROOT-MANIFEST: 1" not in txt["root"]:
        e.append("Root 清单 marker 缺失")
    if "#Format-Version: cognition-volumes/v1" not in txt["root"]:
        e.append("Root 格式版本不是 cognition-volumes/v1")
    for needle in ("id=meta kind=meta path=aoci.meta.txt format=meta-v1",
                   "id=code kind=code path=aoci.code.txt format=object-fras-v2 depends=meta"):
        if needle not in txt["root"]:
            e.append("Root Volume 声明被摘：" + needle[:40])
    if "#AOCI-META-VOLUME: 1" not in txt["meta"]:
        e.append("Meta 卷 marker 缺失")
    if "#Object-Protocol: repository-cognition-object/v2" not in txt["meta"]:
        e.append("Meta 对象协议被摘")
    if "#FRAS-Discipline: 2" not in txt["meta"]:
        e.append("Meta FRAS 纪律被摘")
    if "#[Tag dictionary: code]" not in txt["meta"] or "#[Tag dictionary: database]" not in txt["meta"]:
        e.append("Meta 标签字典被摘")
    if "#S配额:" not in txt["meta"] and "#S quota:" not in txt["meta"]:
        e.append("Meta S 配额被摘")

    # 2 · AGENTS 托管块
    if "<!-- aoci:begin -->" not in txt["agents"] or "<!-- aoci:end -->" not in txt["agents"]:
        e.append("AGENTS.md 的 AOCI 托管块被摘")
    if "aoci.code.txt" not in txt["agents"]:
        e.append("AGENTS.md 没写阅读顺序")

    # 3 · 索引条目
    entries, parse_errs = parse_index(txt["code"])
    e.extend(parse_errs)
    indexed = set()
    for rel, tag, parts, lineno in entries:
        indexed.add(rel)
        if tag[0] not in LAYERS or tag[1] not in MODULES:
            e.append("标签越出字典：%s [%s]" % (rel, tag))
        if not (ROOT / rel).exists():
            e.append("索引对象不存在：%s" % rel)
            continue
        n = line_count(rel)
        if n >= 0 and tag[3] != scale_of(n):
            e.append("规模标签与实际行数不符：%s 标 %s 实 %d 行(%s)" % (rel, tag, n, scale_of(n)))
        s_body = parts[3][2:]
        if len(s_body) > quota_of(tag):
            e.append("S 超配额：%s %d>%d" % (rel, len(s_body), quota_of(tag)))
        for r in R_REF_RE.findall(parts[1]):
            if not (ROOT / r).exists():
                e.append("R 指向不存在的对象：%s → code:%s" % (rel, r))
        if parts[0].strip() in ("F:", "F:-") :
            e.append("F 为空：%s" % rel)

    # 4 · 覆盖率：受管对象必须逐条有 Entry
    want = set(managed_files(cfg))
    for rel in sorted(want - indexed):
        e.append("受管对象缺 Entry：%s" % rel)
    for rel in sorted(indexed - want):
        e.append("未在受管范围却进了索引：%s" % rel)

    # 5 · 噪声边界 + 私有拓扑
    for pat in cfg.get("curation_exclude", []):
        for rel in indexed:
            if pathlib.PurePosixPath(rel).match(pat) or rel.startswith(pat.rstrip("*").rstrip("/")):
                e.append("归档噪声回潮进索引：%s（命中 %s）" % (rel, pat))
                break
    for key in ("root", "meta", "code", "agents"):
        for marker in PRIVATE_MARKERS:
            if marker in txt[key]:
                e.append("认知资产里出现私有拓扑标记：%s in %s" % (marker, key))

    # 6 · config 自身
    if cfg.get("locale") != "zh-CN" or cfg.get("index_path") != "aoci.txt":
        e.append("config 语言环境或索引路径被改")
    if not cfg.get("managed_roots", {}).get("files"):
        e.append("config 受管根被清空")
    if cfg.get("integration", {}).get("mode") != "protocol-level":
        e.append("config 的集成模式被改（不许伪装成跑过官方 CLI）")

    # 7 · baseline 漂移
    try:
        base = json.loads(read(BASELINE)) if "baseline" not in state else json.loads(state["baseline"])
    except ValueError:
        base = {}
    files = base.get("files", {})
    for rel in sorted(want):
        rec = files.get(rel)
        if rec is None:
            e.append("基线缺项：%s（跑 --refresh）" % rel)
            continue
        if rec.get("sha256") != sha_of(rel) or rec.get("size") != size_of(rel):
            e.append("受管文件已改但索引/基线未维护：%s（跑 --refresh 并核 Entry）" % rel)
        if rec.get("role") != "index":
            e.append("基线 role 不是 index：%s" % rel)

    # 8 · 挂进 CI 与现行清单
    if "python3 scripts/aoci_check.py --selftest" not in txt["ci"]:
        e.append("CI 没挂 aoci_check.py")
    if '"aoci_check.py"' not in txt["r135"]:
        e.append("r135 现行清单没登记 aoci_check.py")

    return e


def refresh():
    """按真实行数校正规模标签（不动 F/R/A/S 语义），再重算哈希基线。

    顺序很关键：aoci.code.txt 自己也是受管对象，若先算哈希再改它的标签，
    基线记的就是旧内容，下一轮必然假红。基线文件自身不进受管清单，避免自指。
    """
    cfg = load_cfg()
    want = managed_files(cfg)
    import datetime
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    old_created = now
    try:
        old_created = json.loads(read(BASELINE)).get("created_at", now)
    except ValueError:
        pass

    # 1 · 规模是机器事实：只替换 tag 第 4 位，语义仍由模型创作
    p = ROOT / "aoci.code.txt"
    out = []
    for line in read(p).splitlines():
        em = ENTRY_RE.match(line)
        if not em:
            out.append(line)
            continue
        name, tag, body = em.group(1), em.group(2), em.group(3)
        hits = [w for w in want if w.endswith("/" + name) or w == name]
        if len(hits) == 1:
            tag = tag[:3] + scale_of(line_count(hits[0]))
        out.append("%s[%s]: %s" % (name, tag, body))
    atomic_write(p, "\n".join(out) + "\n")

    # 2 · 索引定稿后再算哈希，基线才不会自相矛盾
    files = {}
    for rel in want:
        h = sha_of(rel)
        files[rel] = {"role": "index", "sha256": h, "size": size_of(rel),
                      "normalized_sha256": h}
    BASELINE.parent.mkdir(parents=True, exist_ok=True)
    atomic_write(BASELINE, json.dumps(
        {"version": 1, "created_at": old_created, "updated_at": now, "files": files},
        ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print("刷新完成：%d 个受管对象，%s" % (len(want), BASELINE.relative_to(ROOT)))


def selftest():
    base = {k: read(v) for k, v in ASSETS.items()}
    assert not check(base), "基线自己就红了，先修基线：\n" + "\n".join(check(base)[:8])
    cfg = read(CFG)
    cases = [
        ("Root marker 被摘", "root", "#AOCI-ROOT-MANIFEST: 1", ""),
        ("Volume 声明被摘", "root", "id=code kind=code path=aoci.code.txt", ""),
        ("Meta 字典被摘", "meta", "#[Tag dictionary: code]", ""),
        ("S 配额被摘", "meta", "#S配额:", ""),
        ("AGENTS 托管块被摘", "agents", "<!-- aoci:begin -->", ""),
        ("一条 Entry 被摘", "code", "version.txt[", "VERSION_MISSING["),
        ("标签越出字典", "code", "[CG9T]", "[QQ9T]"),
        ("规模标签写错", "code", "[CG9T]", "[CG9L]"),
        ("FRAS 少一段", "code", " | S:每打一次包", " | 不带S前缀"),
        ("R 指向不存在", "code", "code:scripts/check_version_bump.py",
         "code:scripts/不存在的文件.py"),
        ("归档回潮进索引", "code", "===/tingben/docs/===",
         "===/tingben/dev/===\n历史回执.md[CG3T]: F:- | R:- | A:- | S:-\n\n===/tingben/docs/==="),
        ("私有拓扑进资产", "code", "#AOCI-CODE-VOLUME: 1",
         "#AOCI-CODE-VOLUME: 1 /data/user_persistent_data/听本/tingben"),
        ("config 受管根清空", "cfg", '"managed_roots"', '"managed_roots_off"'),
        ("集成模式伪装", "cfg", '"protocol-level"', '"official-cli"'),
        ("CI 漏挂", "ci", "python3 scripts/aoci_check.py --selftest", ""),
        ("现行清单漏登记", "r135", '"aoci_check.py"', '"aoci_check_OLD.py"'),
        ("基线哈希被改", "baseline", "", ""),
    ]
    bad = 0
    for name, key, needle, repl in cases:
        st = dict(base)
        if key == "baseline":
            st[key] = json.dumps({"version": 1, "files": {}}, ensure_ascii=False)
        elif key == "cfg":
            st[key] = cfg.replace(needle, repl)
        elif needle == "":
            st[key] = ""
        else:
            st[key] = base[key].replace(needle, repl, 1)
        if not check(st):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cases) - bad, len(cases)))
    return bad


def main():
    args = sys.argv[1:]
    if "--lookup" in args:
        i = args.index("--lookup")
        if i + 1 >= len(args):
            print("用法：python3 scripts/aoci_check.py --lookup <关键字>")
            return 2
        hits = lookup(args[i + 1])
        if not hits:
            print("地图里没有命中：%s" % args[i + 1])
            return 1
        print("地图命中 %d 条：" % len(hits))
        for rel, tag, parts, ln in hits:
            print("\n▶ %s  [%s]  (aoci.code.txt:%d)" % (rel, tag, ln))
            for k, body in zip(("F", "R", "A", "S"), parts):
                print("   %s: %s" % (k, body[2:]))
        return 0
    if "--refresh" in args:
        refresh()
        return 0
    if "--selftest" in args:
        return 1 if selftest() else 0
    e = check()
    if e:
        print("AOCI CHECK FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    cfg = load_cfg()
    print("AOCI 协议级治理：%d 个受管对象 / Root+Meta+Code+AGENTS 齐全 / "
          "基线无漂移 / 噪声边界守住：PASS" % len(managed_files(cfg)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
