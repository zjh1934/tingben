#!/usr/bin/env python3
"""R45 守恒切片：剔除本轮新增的设置页入口块，其余业务代码逐行比 HEAD。"""
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
    """去纯空行 / 去尾部空白 / 去 import 行，供调音台前业务代码守恒比较。"""
    return [x.rstrip() for x in strip_r45(t).splitlines()
            if x.strip() and not x.startswith("import ")]
