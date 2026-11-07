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
