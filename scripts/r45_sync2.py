#!/usr/bin/env python3
"""R45 · 守恒比较规范化：marker 剔除后残留的空行差异不算业务改动。

逐行去掉尾部空白、过滤纯空行与 import 行后比 HEAD；任何真实代码改动仍会被抓到。
"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parent.parent

(ROOT / "scripts/r45_common.py").write_text('''#!/usr/bin/env python3
"""R45 守恒切片：剔除本轮新增的设置页入口块，其余业务代码逐行比 HEAD。"""
BEGIN = "// R45_FIXED_VOICE_ENTRY_BEGIN"
END = "// R45_FIXED_VOICE_ENTRY_END"

def strip_r45(s):
    a = s.find(BEGIN)
    b = s.find(END, a + 1) if a >= 0 else -1
    if a >= 0 and b >= 0:
        ls = s.rfind("\\n", 0, a) + 1
        le = s.find("\\n", b) + 1
        return s[:ls] + (s[le:] if le > 0 else "")
    return s

def canon(t):
    """去纯空行 / 去尾部空白 / 去 import 行，供调音台前业务代码守恒比较。"""
    return [x.rstrip() for x in strip_r45(t).splitlines()
            if x.strip() and not x.startswith("import ")]
''')

def rep(path, pairs):
    s = path.read_text()
    for i, (old, new) in enumerate(pairs, 1):
        n = s.count(old)
        if n != 1:
            sys.exit(f"{path.name} 第 {i} 条命中 {n} 次，整体不写盘")
        s = s.replace(old, new, 1)
    path.write_text(s)
    print("同步", path, len(pairs), "条")

rep(ROOT / "scripts/r40_verify.py", [
    ("""    head = no_imports(strip_r45(m).split("// ── ⑥ 调音台")[0].splitlines())""",
     """    head = canon(m.split("// ── ⑥ 调音台")[0])"""),
    ("""        old = no_imports(git_head(MAIN).decode("utf-8").split("// ── ⑥ 调音台")[0].splitlines())""",
     """        old = canon(git_head(MAIN).decode("utf-8").split("// ── ⑥ 调音台")[0])"""),
])
rep(ROOT / "scripts/r41a_verify.py", [
    ("""        cur = no_imports(strip_r45(m).split("// ── ⑥ 调音台")[0].splitlines())
        old = head(MAIN)
        same = old is not None and cur == no_imports(
            old.decode("utf-8").split("// ── ⑥ 调音台")[0].splitlines())""",
     """        cur = canon(m.split("// ── ⑥ 调音台")[0])
        old = head(MAIN)
        same = old is not None and cur == canon(
            old.decode("utf-8").split("// ── ⑥ 调音台")[0])"""),
])
rep(ROOT / "scripts/r41_verify.py", [
    ("""    cut = lambda t: no_imports(strip_r45(t).split("// ── ⑥ 调音台")[0].splitlines())""",
     """    cut = lambda t: canon(t.split("// ── ⑥ 调音台")[0])"""),
])
rep(ROOT / "scripts/r42_verify.py", [
    ("""        noimp = lambda t: [x for x in strip_r45(t).split("// ── ⑥ 调音台")[0].splitlines()
                           if not x.startswith("import ")]""",
     """        noimp = lambda t: canon(t.split("// ── ⑥ 调音台")[0])"""),
])
print("规范化同步完成")
