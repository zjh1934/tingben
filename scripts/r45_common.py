#!/usr/bin/env python3
"""R45 conservation slice: strip the injected settings entry, compare code lines only."""
BEGIN = "// R45_FIXED_VOICE_ENTRY_BEGIN"
END = "// R45_FIXED_VOICE_ENTRY_END"

def strip_r45(s):
    a = s.find(BEGIN)
    b = s.find(END, a + 1) if a >= 0 else -1
    if a >= 0 and b >= 0:
        ls = s.rfind("\n", 0, a) + 1
        le = s.find("\n", b) + 1
        return s[:ls] + (s[le:] if le > 0 else "")
    return s

def canon(t):
    t = strip_r45(t)
    a = t.find("private fun GroupLabel(")
    b = t.find("private fun ModelScreen(", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    a = t.find("private fun MixerScreen(")
    if a >= 0:
        t = t[:a]
    a = t.find("private fun App(")
    b = t.find("private fun queryName(", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    return [x.rstrip() for x in t.splitlines()
            if x.strip() and not x.startswith("import ")
            and not x.lstrip().startswith(("//", "/*", "*", "#"))]


def scope_indexed(config_text, path):
    """官方 AOCI 0.1.1 起，受管范围由 managed_scope.rules 表达：
    legacy-index-all 的 index 兜底 + 显式 exclude 负空间，不再逐条列
    managed_roots。判 path 是否真在 index 覆盖内、且没被 exclude 命中。
    """
    import fnmatch, json
    try:
        d = json.loads(config_text)
    except Exception:
        return False
    rules = [r for r in d.get("managed_scope", {}).get("rules", [])
             if r.get("enabled", True)]
    if not any(r.get("action") == "index" and r.get("pattern") == "**"
               for r in rules):
        return False
    p = path.strip().lstrip("/")
    for r in rules:
        if r.get("action") != "exclude":
            continue
        pat = str(r.get("pattern", "")).strip().lstrip("/")
        if not pat:
            continue
        if p == pat or p.startswith(pat.rstrip("/") + "/"):
            return False
        if fnmatch.fnmatch(p, pat):
            return False
    return True
