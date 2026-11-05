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
    """去纯空行 / 去尾部空白 / 去 import 行，供调音台前业务代码守恒比较。

    R46 · V1 抽屉壳允许改 App() 内的导航与壳挂载（☰ / 抽屉 / 底栏 / 二级页返回），
    所以把 App() 函数体整段剔除；App() 之外的业务代码仍与 HEAD 逐行守恒。
    AppChrome.kt 整文件允许改，由 r46_verify.py 做形态断言与旧壳零残留检查。
    """
    t = strip_r45(t)
    a = t.find("private fun App(")
    b = t.find("private fun queryName(", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    # R47 · ParamRow 允许改（滑块双模），由 r47_verify.py 做逐行对拍；
    # 其余 App() 之外的业务代码仍与 HEAD 逐行守恒。
    a = t.find("private fun ParamRow(")
    b = t.find("/** R41 · 共用音色选择器", a + 1)
    if a >= 0 and b > a:
        t = t[:a] + t[b:]
    return [x.rstrip() for x in t.splitlines()
            if x.strip() and not x.startswith("import ")]
