#!/usr/bin/env python3
"""R40 · 把 MainActivity.kt 的调音台区块整体换成「统一提交」版。

只切 `// ── ⑥ 调音台` 到文件末尾这一段，前面的 1885 行（导航 / 书架 / 4B /
native 链路）一个字节不动。此脚本只在本地执行一次，执行后可由 r40_verify.py 复核。
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/tingben/app/MainActivity.kt"
BLOCK = ROOT / "scripts/r40_mixer_block.txt"

src = MAIN.read_text(encoding="utf-8")
mark = "// ── ⑥ 调音台"
i = src.find(mark)
if i < 0:
    raise SystemExit("找不到调音台区块起点，停止")
head = src[:i]
new = BLOCK.read_text(encoding="utf-8")
if not new.rstrip().endswith("}"):
    raise SystemExit("新区块结尾不是 }，停止")
MAIN.write_text(head + new, encoding="utf-8")
print("已替换调音台区块：保留前", head.count("\n") + 1, "行，新区块", new.count("\n") + 1, "行")
