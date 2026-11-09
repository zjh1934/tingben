#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R206 · MainActivity.kt 分家的读取层地基。

和 core_sources 的区别（这是 UI 文件独有的坑）：
  Kotlin 顶层 `private` 是**文件私有**。MainActivity.kt 里 40 个顶层 private
  符号（Screen / 颜色常量 / CardBox / 各 XxxScreen / App）拆到不同 .kt 后
  互相不可见，必须改成 internal。但 67 个历史守门、r40/r46 的 git HEAD 守恒
  对拍都认「拆分前那份原文」（全是 private）。

解法是双向变换：
  磁盘片段文件：顶层声明一律 internal（编译期跨文件可见）
  合并视图    ：还原成 private（与拆分前逐字节一致，历史判据全过）
  write_text  ：合并视图切回片段时再变回 internal

主文件本身**不参与变换**：拆完后它只留 class MainActivity 与 App()，
App 只被本文件调用，保持 private 即可；所有需要跨文件的符号都放片段里。

用法与 core_sources 对齐：ui_sources.read(root) / ui_sources.source(root)。
"""
from __future__ import annotations
import pathlib
import re

UI = "app/src/main/java/com/tingben/app"
MAIN = "MainActivity.kt"
INSERT = "// @ui-insert: "
PART = "// @ui-part: "
HEADER = "package com.tingben.app\n\n"
END = "__UI_END__"

# 顺序即合并顺序；右锚必须是合并视图里唯一且稳定的字面量。末块用 END。
PARTS = (
    ("Shell.kt", "@Composable\nprivate fun KeepScreenOn(", "@Composable\nprivate fun LibraryScreen("),
    ("LibraryUi.kt", "@Composable\nprivate fun LibraryScreen(", "@Composable\nprivate fun InputReadScreen("),
    ("InputReadUi.kt", "@Composable\nprivate fun InputReadScreen(", "@Composable\nprivate fun ProduceScreen("),
    ("ProduceUi.kt", "@Composable\nprivate fun ProduceScreen(", "@Composable\nprivate fun TaskCenterScreen("),
    ("TaskUi.kt", "@Composable\nprivate fun TaskCenterScreen(", "@Composable\nprivate fun OverlayProgressRow("),
    ("SettingsUi.kt", "@Composable\nprivate fun OverlayProgressRow(", "@Composable\nprivate fun ClearSegmentsRow("),
    ("ModelUi.kt", "@Composable\nprivate fun ClearSegmentsRow(", "@Composable\nprivate fun MixerDialog("),
    ("MixerUi.kt", "@Composable\nprivate fun MixerDialog(", "@Composable\nprivate fun ResumeAskDialog("),
    ("ResumeUi.kt", "@Composable\nprivate fun ResumeAskDialog(", END),
)

# 只匹配**行首无缩进**的顶层声明；类内 private 成员（有缩进）绝不能动。
_DECL = r"^(private|internal) ((?:const val|fun|val|var|data class|class|enum class|object)\b)"


def _to_view(text: str) -> str:
    """片段磁盘文本 -> 合并视图：顶层 internal 还原成 private。"""
    return re.sub(_DECL, lambda m: "private " + m.group(2), text, flags=re.M)


def _to_disk(text: str) -> str:
    """合并视图片段 -> 磁盘：顶层 private 改成 internal，跨文件可见。"""
    return re.sub(_DECL, lambda m: "internal " + m.group(2), text, flags=re.M)


def _ui(root) -> pathlib.Path:
    p = pathlib.Path(root)
    if (p / MAIN).exists():
        return p
    return p / UI


def _body(part: pathlib.Path) -> str:
    """片段文件 @ui-part 行之后的正文（不含该行），并还原成 private 视图。"""
    if not part.is_file():
        return ""
    t = part.read_text(encoding="utf-8", errors="replace")
    i = t.find(PART)
    if i < 0:
        return ""
    j = t.find("\n", i)
    return _to_view(t[j + 1:]) if j >= 0 else ""


def _prefix(part: pathlib.Path) -> str:
    """片段文件哨兵行（含）之前的头部：package + 自己的 import。"""
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
    """只读合并视图：主文件 + 各片段正文，等价于拆分前的 MainActivity.kt。"""
    t = (_ui(root) / MAIN).read_text(encoding="utf-8", errors="replace")
    # 主文件里的顶层 internal 也还原成 private（拆后共享符号要跨文件可见）
    t = _to_view(t)
    for name, _, _ in PARTS:
        t = t.replace(_marker(name), _body(_ui(root) / name), 1)
    return t


class UiSourcePath:
    """Path-like：只代理 MainActivity.kt 的读与写，接口覆盖历史脚本的用法。"""

    def __init__(self, root):
        self.root = pathlib.Path(root)
        self.path = _ui(self.root) / MAIN

    def read_text(self, encoding="utf-8", errors="replace") -> str:
        return read(self.root)

    def read_bytes(self) -> bytes:
        return read(self.root).encode("utf-8")

    def exists(self) -> bool:
        return self.path.exists()

    def is_file(self) -> bool:
        return self.path.is_file()

    def write_text(self, text: str, encoding="utf-8", errors=None) -> int:
        """分发写回：把合并视图按片段首尾锚切回各文件，不污染主文件。"""
        cur = read(self.root)
        if text == cur:
            return 0
        t = text
        writes = []
        for name, start, nxt in PARTS:
            i = t.find(start)
            j = (len(t) if nxt == END else
                 (t.find(nxt, i + len(start)) if i >= 0 else -1))
            if i < 0 or j < 0:
                return 0
            body = _to_disk(t[i:j])
            t = t[:i] + _marker(name) + t[j:]
            writes.append((_ui(self.root) / name,
                           _prefix(_ui(self.root) / name) + body))
        # 有片段时主文件也要放行顶层符号（private -> internal），
        # 片段才能跨文件引用共享的颜色常量 / helper；PARTS 为空则原样写回。
        writes.append((self.path, _to_disk(t) if PARTS else t))
        for p, v in writes:
            p.write_text(v, encoding="utf-8")
        return len(t)

    @property
    def name(self) -> str:
        return MAIN

    @property
    def parent(self):
        return self.path.parent

    def relative_to(self, other):
        return self.path.relative_to(other)

    def __truediv__(self, other):
        return self.path / other

    def __str__(self):
        return str(self.path)

    def __fspath__(self):
        return str(self.path)


def view_for(path, root) -> str:
    """给「遍历真实 .kt 文件」的守门用。

    MainActivity.kt 返回合并视图（顶层声明仍是 private，历史判据全过）；
    带 @ui-part 的片段文件返回空串（内容已在合并视图里，避免重复计数）；
    其余文件原样返回。
    """
    p = pathlib.Path(path)
    if p.name == MAIN:
        return read(root)
    t = p.read_text(encoding="utf-8", errors="replace")
    return "" if PART in t else t


def source(root) -> UiSourcePath:
    return UiSourcePath(root)


def locate(root, symbol: str) -> str:
    """按符号定位（给 screen_enum 之类用）；返回合并视图。"""
    s = read(root)
    if symbol not in s:
        raise KeyError(symbol)
    return s
