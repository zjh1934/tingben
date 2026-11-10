#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R215 · 给守「试听/合成口径」的老脚本用的合并视图。

R215 把合成真源抽到 core/ClipSynth.kt（不再塞在 MixerUi 里），
但 r46/r89/r98/r105/check_structure 这些老守门读的是 ui_sources 的
「拆分前 MainActivity 合并视图」，看不到 core 文件。
这里把两份合起来给它们看：判据不变，真源搬家、视图跟着合。

r206 的逐字节守恒仍然只读 ui_sources.read()，不受影响。
"""
from __future__ import annotations
import pathlib
import ui_sources

CLIP = "app/src/main/java/com/tingben/app/core/ClipSynth.kt"


def merged(root) -> str:
    root = pathlib.Path(root)
    clip = root / CLIP
    sep = chr(10)
    return ui_sources.read(root) + sep + clip.read_text(encoding="utf-8", errors="replace")
