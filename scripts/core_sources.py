#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""core_sources · 大文件分家的读取层地基。

把 core/Pipeline.kt 按「符号」拆成多个 .kt 时，34 个历史守门仍按单文件读它，
其中 5 个还会在 finally 里写回。若直接让它们读拆后的主文件，锚点集体假红；
若把「多文件拼接视图」交给会写回的脚本，又会把拼接文本整体覆回主文件
（R195 踩过：MainActivity.kt 被写肿到 661 KB）。

本模块把「读」与「写」分开：
  - 主文件里以一行 `// @core-insert: <Name>` 占位；
  - 片段文件以 `// @core-part: <Name>` 起头，其后就是搬出去的正文；
  - read(root) 把占位行换成片段正文，结果与拆分前**逐字节一致**，判据零改动；
  - source(root) 返回 Path-like，供脚本的 FILES / PL 无缝替换；
    .write_text() 会把合并视图按片段首尾锚切回各文件，绝不整体写进主文件。

新增片段只改 PARTS 一行。PARTS 每项：(文件名, 正文首行锚, 下一个顶层声明锚)。
"""
from __future__ import annotations
import pathlib

CORE = "app/src/main/java/com/tingben/app/core"
INSERT = "// @core-insert: "
PART = "// @core-part: "
HEADER = "package com.tingben.app.core\n\n"

# 顺序即合并顺序。锚点必须是源码里唯一且稳定的字面量。
PARTS = (
    ("ChapterSplitter.kt", "object ChapterSplitter {", "object Annotator {"),
    ("Annotator.kt", "object Annotator {", "object AnnCache {"),
    ("AnnCache.kt", "object AnnCache {", "object WavMerger {"),
)


def _core(root) -> pathlib.Path:
    """历史脚本传进来的可能是仓库根，也可能是 .../app 源目录，两种都认。"""
    p = pathlib.Path(root)
    if (p / "core" / "Pipeline.kt").exists():
        return p / "core"
    return p / CORE


def _body(part: pathlib.Path) -> str:
    """片段文件里 @core-part 行之后的正文（不含该行）；文件不在就返回空串。"""
    if not part.is_file():
        return ""
    t = part.read_text(encoding="utf-8", errors="replace")
    marker = PART
    i = t.find(marker)
    if i < 0:
        return ""
    j = t.find("\n", i)
    return t[j + 1:] if j >= 0 else ""


def _prefix(part: pathlib.Path) -> str:
    """片段文件里 @core-part 行（含）之前的头部：package + 该文件自己的 import。

    R202 真编译教训：Pipeline.kt 顶部的 import 不会自动对每个拆分出来的文件生效，
    Kotlin 按文件独立编译。AnnCache.kt 用到 File / Context，必须在自己的头部 import；
    但这些行不能进 _body（否则合并视图会多出内容、与拆分前不再逐字节一致），
    所以放在哨兵行之前，写回时整段保留。
    """
    fallback = HEADER + PART + part.stem + "\n"
    if not part.is_file():
        return fallback
    t = part.read_text(encoding="utf-8", errors="replace")
    i = t.find(PART)
    if i < 0:
        return fallback
    j = t.find("\n", i)
    return t[:j + 1] if j >= 0 else t + "\n"


def _marker(name: str) -> str:
    return INSERT + name[:-3] + "\n"


def read(root) -> str:
    """只读合并视图：主文件 + 各片段正文，等价于拆分前的 Pipeline.kt。"""
    main = _core(root) / "Pipeline.kt"
    t = main.read_text(encoding="utf-8", errors="replace")
    for name, _, _ in PARTS:
        t = t.replace(_marker(name), _body(_core(root) / name), 1)
    return t


class SourcePath:
    """Path-like：只代理 Pipeline.kt 的读与写，接口覆盖历史脚本的用法。"""

    def __init__(self, root):
        self.root = pathlib.Path(root)
        self.path = _core(self.root) / "Pipeline.kt"

    def read_text(self, encoding="utf-8", errors="replace") -> str:
        return read(self.root)

    def exists(self) -> bool:
        return self.path.exists()

    def is_file(self) -> bool:
        return self.path.is_file()

    def write_text(self, text: str, encoding="utf-8", errors=None) -> int:
        """分发写回：把合并视图按片段首尾锚切回各文件，不污染主文件。"""
        cur = read(self.root)
        if text == cur:
            return 0  # 没变就不写，r140 那类 finally 走这条
        t = text
        writes = []
        for name, start, nxt in PARTS:
            i = t.find(start)
            j = t.find(nxt, i + len(start)) if i >= 0 else -1
            if i < 0 or j < 0:
                # 切不动就整体不写，宁可不动也不把拼接文本覆进主文件
                return 0
            body = t[i:j]
            t = t[:i] + _marker(name) + t[j:]
            part = _core(self.root) / name
            writes.append((part, _prefix(part) + body))
        writes.append((self.path, t))
        for p, v in writes:
            p.write_text(v, encoding="utf-8")
        return len(v)

    @property
    def name(self) -> str:
        return self.path.name

    @property
    def parent(self) -> pathlib.Path:
        return self.path.parent

    @property
    def stem(self) -> str:
        return self.path.stem

    @property
    def suffix(self) -> str:
        return self.path.suffix

    def __str__(self) -> str:
        return str(self.path)

    def __fspath__(self) -> str:
        return str(self.path)

    def __truediv__(self, other):
        return self.path / other


def source(root) -> SourcePath:
    return SourcePath(root)


def locate(root, symbol: str) -> str:
    """按符号定位：返回声明 `object/class/data class <symbol>` 所在文件名。"""
    for p in sorted(_core(root).glob("*.kt")):
        t = p.read_text(encoding="utf-8", errors="replace")
        if (symbol + " {") in t or (symbol + "(") in t:
            return p.name
    return ""
