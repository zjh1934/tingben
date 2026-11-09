# -*- coding: utf-8 -*-
"""R195 · Screen 枚举的「按符号定位」单一真值源。

以前 35 个历史守门各自写死
    {"LIBRARY","PRODUCE","PROGRESS","TASKS","SETTINGS","MODELS","MIXER","CHAPTERS"}
或整行 `private enum class Screen { ... }`。删一个死值、拆一次文件，
十几个脚本一起假红 —— 这才是「老树盘根」的真正来源。

现在只从 MainActivity.kt 的源码符号解析，守门判据统一成：
    枚举声明集合 == when(screen) 分发集合 == chromeOf 映射集合
外加两个白名单：
    RETIRED：已物理删除的死值，绝不允许回来
    ALLOWED_ORPHAN：暂时保留、但已被标记为下轮要清的不可达值

这样后面删 MIXER、拆 MainActivity，守门会自动跟上，不用逐个改字面量。
"""
import re

# 已物理删除的死值（R195 清掉 PROGRESS）。出现即红。
RETIRED = {"PROGRESS"}

# R196 · 仍声明但无跳转入口的孤儿值。PRODUCE 的 ProduceScreen 里还有
# 没搬进目录页的细粒度制作配置（朗读档位/语速/情绪/缓存摘要/从头重做），
# 不属「没用的」，重构轮不许直接删；MIXER 已于 R196 清掉。
ALLOWED_ORPHAN = {"PRODUCE"}


def declared(src: str) -> set:
    """从 `private enum class Screen { ... }` 解析声明集合。"""
    m = re.search(r"private enum class Screen\s*\{([^}]*)\}", src)
    if not m:
        return set()
    return {x for x in re.findall(r"\b[A-Z_]+\b", m.group(1))}


def dispatch(src: str) -> set:
    """when (screen) { ... } 分发块里 `Screen.X ->` 的集合。"""
    head = "Box(Modifier.weight(1f)) {\n            when (screen) {"
    i = src.find(head)
    if i < 0:
        return set()
    open_i = i + len(head) - 1
    depth, end = 0, None
    for k in range(open_i, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                end = k
                break
    block = src[open_i:(end + 1)] if end else ""
    return set(re.findall(r"Screen\.([A-Z_]+)\s*->", block))


def chrome(src: str) -> set:
    """chromeOf 的 when 映射目的地集合。"""
    m = re.search(
        r"private fun chromeOf\(s: Screen\): ChromeSpec = when \(s\) \{(.*?)\n\}",
        src, re.S)
    return set(re.findall(r"Screen\.([A-Z_]+)\s*->", m.group(1))) if m else set()


def jump_targets(src: str) -> set:
    """真实可达目的地：nav.push / screen = 赋值 / 回退映射。"""
    return set(re.findall(
        r"(?:nav\.push\(|screen\s*=\s*|->\s*)Screen\.([A-Z_]+)", src))


def consistency(src: str) -> list:
    """三者一致 + 退役值零回潮。返回错误列表，空即绿。"""
    e = []
    d, w, c = declared(src), dispatch(src), chrome(src)
    if not d:
        return ["找不到 Screen 枚举声明"]
    if d != w:
        e.append("枚举与 when(screen) 分发不一致: %s" % sorted(d ^ w))
    if d != c:
        e.append("枚举与 chromeOf 映射不一致: %s" % sorted(d ^ c))
    back = d & RETIRED
    if back:
        e.append("已退役死值回潮: %s" % sorted(back))
    for name, fn in (("PROGRESS", "ProgressScreen"),):
        if name in RETIRED and re.search(
                r"Screen\.%s\b|private fun %s\(" % (name, fn), src):
            e.append("退役页符号仍残留: " + name)
    return e


def orphan_values(src: str) -> set:
    """声明了、却没有任何跳转入口的值（候选死代码）。"""
    return declared(src) - jump_targets(src)
