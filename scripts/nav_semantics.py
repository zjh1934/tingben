#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""F-25 · 导航语义判据（唯一真源）。

各验证脚本不再把导航实现的字面量写死（那会在 F-04/F-07 改导航时集体假红），
改问「语义还在不在」：状态字段齐不齐、有没有全局回退、抽屉回退是否优先、
二级页返回是否按来源、枚举与分发是否对齐。

判据对实现形态不敏感：remember / rememberSaveable、直接赋值 / 返回栈 pop 都算成立。
只依赖标准库，可被任何 verify 脚本 import。
"""
from __future__ import annotations
import re

NAV_FIELDS = {
    "screen", "bookId", "miniBook", "lastSelection", "refresh",
    "modelsFrom", "importing",
}

def nav_fields(src: str) -> dict:
    """字段名 -> 声明方式（remember / rememberSaveable）；没找到就不在字典里。"""
    out = {}
    for m in re.finditer(
        r"\bvar\s+(\w+)\s+by\s+(rememberSaveable|remember)\s*\{", src
    ):
        if m.group(1) in NAV_FIELDS:
            out[m.group(1)] = m.group(2)
    return out

def fields_ok(src: str) -> list:
    """返回缺失的导航字段列表；空列表即通过。"""
    return sorted(NAV_FIELDS - set(nav_fields(src)))

def _balanced(src: str, start: int) -> str:
    """从 '(' 起做括号配对，返回含括号的完整片段。"""
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return ""

def back_handlers(src: str) -> list:
    """[(位置, enabled 表达式, 调用体)]，按源码顺序。"""
    out = []
    for m in re.finditer(r"\bBackHandler\s*\(", src):
        frag = _balanced(src, m.end() - 1)
        if not frag:
            continue
        body = frag[1:-1].strip()
        em = re.search(r"enabled\s*=\s*(.+)", body, re.S)
        enabled = em.group(1).strip() if em else ""
        out.append((m.start(), enabled, frag))
    return out

def global_back(src: str):
    """非书架页启用的全局回退处理器（不含抽屉态）。"""
    for pos, enabled, frag in back_handlers(src):
        if "drawerState" in enabled or "dirty" in enabled:
            continue
        if "screen" in enabled or "canPop" in enabled or "nav." in enabled:
            return (pos, enabled, frag)
    return None

def drawer_back(src: str):
    for pos, enabled, frag in back_handlers(src):
        if "drawerState" in enabled:
            return (pos, enabled, frag)
    return None

def back_order_ok(src: str) -> bool:
    """抽屉开着时先收抽屉：抽屉 BackHandler 必须注册在全局回退之后（LIFO 优先）。"""
    g, d = global_back(src), drawer_back(src)
    return bool(g and d and d[0] > g[0])

def _branch(src: str, name: str) -> str:
    """取分发块里 `Screen.NAME ->` 的窗口。

    同名映射在 chromeOf / drawerSelected 里也会出现，那里没有 onBack；
    因此遍历所有命中，优先返回含 onBack 的窗口，避免选错块。
    """
    best = ""
    for m in re.finditer(r"Screen\.%s\s*->" % re.escape(name), src):
        tail = src[m.start():m.start() + 900]
        nxt = re.search(r"\n\s*Screen\.[A-Z_]+\s*->", tail[10:])
        win = tail[:10 + nxt.start()] if nxt else tail
        if "onBack" in win:
            return win
        if len(win) > len(best):
            best = win
    return best

def _on_back(branch: str):
    m = re.search(r"onBack\s*=\s*\{([^}]*)\}", branch, re.S)
    return m.group(1).strip() if m else None

def back_uses_source(src: str, name: str) -> bool:
    """二级页返回按来源（变量 / 栈 pop），而不是写死某个 Screen 常量。"""
    b = _on_back(_branch(src, name))
    if b is None:
        return False
    if re.search(r"screen\s*=\s*Screen\.(SETTINGS|LIBRARY|AUDIO|TASKS|PROGRESS|PRODUCE|MODELS|CHAPTERS|MIXER)", b):
        return False
    return bool(re.search(r"drawerReturn|modelsFrom|\bpop\s*\(|\bback\s*\(|nav\.", b))

def mixer_back_ok(src: str) -> bool:
    """R196 · 调音台已从独立页收成浮层：守「关闭时把开关归位」。"""
    return bool(re.search(r"chapterMixerOpen\s*=\s*false", src))

def models_back_ok(src: str) -> bool:
    return back_uses_source(src, "MODELS")

def mixer_entries(src: str) -> list:
    """调音台入口是否存在。

    R148/R162 起主路径是「点合成之前就地弹层」（chapterMixerOpen / MixerDialog），
    不再 push 独立页；旧形态（压栈 + 记录来源）仍算成立，判据对两种形态不敏感。
    """
    out = []
    for m in re.finditer(r"onMixer\s*=\s*\{([^}]*)\}", src, re.S):
        body = m.group(1).strip()
        if not body:
            continue
        if re.search(r"chapterMixerOpen|MixerDialog|OVERLAY_MIXER|Screen\.MIXER|MIXER", body):
            out.append(body)
    return out

def model_entries(src: str) -> list:
    """模型页入口：进 MODELS 且记录来源（或压栈）。"""
    out = []
    for m in re.finditer(r"onModels\s*=\s*\{([^}]*)\}", src, re.S):
        body = m.group(1)
        if "Screen.MODELS" in body or "MODELS" in body:
            if re.search(r"modelsFrom|\bpush\s*\(|nav\.", body):
                out.append(body.strip())
    return out

def library_reachable(src: str) -> bool:
    """书架仍是可到达目的地：有赋值回 LIBRARY，或有返回栈引用 LIBRARY。"""
    if re.search(r"screen\s*=\s*Screen\.LIBRARY", src):
        return True
    return bool(re.search(r"(?:pop|push|back|navigate)\w*\s*\([^)]*Screen\.LIBRARY", src))

def screen_enum(src: str) -> set:
    m = re.search(r"(?:private\s+)?enum\s+class\s+Screen\s*\{([^}]*)\}", src)
    return set(re.findall(r"\b[A-Z_]+\b", m.group(1))) if m else set()

def semantics(src: str) -> list:
    """一次性核完所有导航语义，返回错误列表（空即通过）。"""
    e = []
    miss = fields_ok(src)
    if miss:
        e.append("导航状态字段缺失: " + ", ".join(miss))
    if global_back(src) is None:
        e.append("没有全局回退处理器")
    if drawer_back(src) is None:
        e.append("没有抽屉优先回退处理器")
    if not back_order_ok(src):
        e.append("抽屉回退没排在全局回退之后")
    if not mixer_back_ok(src):
        e.append("调音台浮层关闭没归位")
    if not models_back_ok(src):
        e.append("模型页返回没按来源")
    if not mixer_entries(src):
        e.append("没有记录来源的调音台入口")
    if not library_reachable(src):
        e.append("书架不再是可达目的地")
    return e

R109_SHAPE = {
    "state": ("var screen by remember {", "var screen by rememberSaveable {"),
    "back": ("BackHandler(enabled = screen != Screen.LIBRARY) { screen = Screen.LIBRARY }",
             "BackHandler(enabled = nav.canPop()) { nav.pop() }"),
    "mixer_onback": ("onBack = { screen = drawerReturn }", "onBack = { nav.pop() }"),
    "models_onback": ("onBack = { screen = modelsFrom }", "onBack = { nav.pop() }"),
    "mixer_entry": ("onMixer = { drawerReturn = Screen.SETTINGS; screen = Screen.MIXER }",
                    "onMixer = { nav.push(Screen.MIXER) }"),
}

def simulate_r109(src: str) -> str:
    """把当前导航实现改写成 R109 计划形态，用于验证语义判据不假红。"""
    for _, (old, new) in R109_SHAPE.items():
        src = src.replace(old, new)
    src = re.sub(r"\bvar (\w+) by remember \{",
                 lambda m: "var %s by rememberSaveable {" % m.group(1)
                 if m.group(1) in NAV_FIELDS else m.group(0), src)
    return src
