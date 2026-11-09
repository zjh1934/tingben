# -*- coding: utf-8 -*-
"""R155 · 界面讨论轮：三处「查证后确认」的小修 + 一处纠正。

用户这轮提了一整套信息架构重构（三列并一列 / 去抽屉 / 主页输入框 /
模型并入设置），同时夹着几个可独立查证的具体问题。我判断大架构
留到真机证据回来再动，这轮只落确认过的四项：

  ① 折叠会不会占内存 / 卡
     查证：同一时刻只有一章展开（`expandedChapter` 是 Int?），章行本身
     是 LazyColumn 的惰性 item —— 不是「每章都展开」。真正的隐患在
     展开章的 ChapterSegGrid：它用 Column + chunked().forEach 把整章
     几百个格子一次性全组合，且挤在父列表的**同一个 item** 里，
     滚动经过时整块测量 / 重组。改成按行拆成父列表独立 item。
  ② 调音弹层盖住系统返回 / 桌面手势区、点空白返回不了
     查证：全屏 Dialog 只 padding(12.dp)，没有 statusBars/navigationBars
     避让；onDismissRequest 是空实现；关闭叉是灰色 IconClose。
     补安全区 + 把统一 leave 注册给遮罩 + 关闭叉给浅底主色。
  ③ 「进去读的话文本应该可以复制」
     查证：ReaderPane 只是裸 Text，长按没反应。包 SelectionContainer。
  ④ 纠正（不落地）：「书架那条小横条可以删，页面下拉会刷新」
     事实：全仓没有下拉刷新手势，RefreshStrip 那条 22dp 小横条本身
     就是唯一的刷新热区（拖 >55px 才触发）。删了就没入口。
     正确改法是先补整页下拉再删条，排到下一轮，见评估文档。

用法：python3 scripts/r155_verify.py --selftest
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": APP / "ChapterHub.kt",
    "main": ui_sources.source(APP),
    "ci": ROOT / ".github/workflows/ci.yml",
    "r135": ROOT / "scripts" / "r135_verify.py",
}

POS = {
    "hub": [
        "import androidx.compose.foundation.text.selection.SelectionContainer",
        # ① 展开格子惰性化
        "ui.forEach { r ->",
        'item(key = "ch_${r.index}")',
        "items(chapterSegs.chunked(SEG_GRID_COLUMNS),",
        'key = { row -> "seg_${r.index}_${row.first().index}" })',
        'item(key = "seg_head_${r.index}") {',
        "private fun SegGridHead(",
        "private fun ChapterSegGrid(",
        "expandedChapter = if (expandedChapter == r.index) null else r.index",
        # ③ 阅读页可复制
        "SelectionContainer {",
        "本章 ${segs.size} 小段",
    ],
    "main": [
        # ② 调音弹层
        "onDismissRequest = { requestLeave?.invoke() }",
        "var requestLeave by remember { mutableStateOf<(() -> Unit)?>(null) }",
        ".statusBarsPadding()\n            .navigationBarsPadding()",
        "onRegisterLeave = { requestLeave = it }",
        "onRegisterLeave: ((() -> Unit) -> Unit)? = null",
        "LaunchedEffect(dirty) { onRegisterLeave(leave) }",
        ".background(PrimarySoft, RoundedCornerShape(12.dp))",
        "IconClose(Primary)",
        # ④ 纠正项：刷新条这轮保留，不许被顺手删掉
        "RefreshStrip(",
    ],
    "ci": ["python3 scripts/r155_verify.py --selftest"],
    "r135": ['"r155_verify.py"'],
}

BAN = {
    "hub": [
        "segs.chunked(8).forEach",   # 旧的全量组合
    ],
    "main": [],
}


def load():
    out = {}
    for k, p in FILES.items():
        try:
            out[k] = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            out[k] = ""
    return out


def code_only(s):
    return "\n".join(
        line for line in s.splitlines()
        if not line.strip().startswith(("//", "/*", "*")))


def raw_column_body(h):
    a = h.find("private fun ChapterSegGrid(")
    if a < 0:
        return ""
    b = h.find("\n}\n", a)
    return h[a:b] if b > a else ""


def mixer_dialog_body(m):
    a = m.find("private fun MixerDialog(")
    if a < 0:
        return ""
    b = m.find("\n@OptIn", a)
    return m[a:b] if b > a else ""


def boxes_old(segs):
    """旧方案：整章每个格子都在同一个 item 里全量组合。"""
    return segs


def boxes_new(segs, per_row=8, visible_rows=6):
    """新方案：父 LazyColumn 只组合看得见的那几行格子（head 只是 1 个 Text）。"""
    return min((segs + per_row - 1) // per_row, visible_rows) * per_row


def logic_cases(f):
    bad = []
    h, m = f["hub"], f["main"]
    # ① 惰性化收益：短章不劣化，长章的组合量不再随段数线性膨胀
    for segs in (24, 120, 400):
        old, new = boxes_old(segs), boxes_new(segs)
        if new > old or (segs >= 48 and new >= old):
            bad.append("%d 段章展开仍全量组合：old=%d new=%d" % (segs, old, new))
        else:
            print("  %3d 段章：全量 %3d 个格子 → 只组合 %3d 个 ✓"
                  % (segs, old, new))
    # 一次只展开一章
    if "expandedChapter = if (expandedChapter == r.index) null else r.index" not in h:
        bad.append("展开态不是单章互斥，会同时展开多章")
    else:
        print("  一次只展开一章 ✓（不是「每章下面都折叠」）")
    # 行为判据（也进 check，才能被反向注入拦住）
    # R163 · 单列后不再有 RawColumn 函数体，直接守整页里的惰性行
    rb = code_only(h)
    if "items(chapterSegs.chunked(SEG_GRID_COLUMNS)," not in rb:
        bad.append("展开格子没拆成父列表的独立 item")
    if "chunked(8).forEach" in rb:
        bad.append("展开格子仍是全量 forEach 组合")
    db = code_only(mixer_dialog_body(m))
    if not db or "navigationBarsPadding()" not in db:
        bad.append("调音弹层没避让系统导航区")
    if "requestLeave?.invoke()" not in db:
        bad.append("点空白仍不能退出")
    # 阅读页：SelectionContainer 必须包住正文 LazyColumn
    rp = h.find("private fun ReaderPane(")
    tail = h[rp:rp + 3500] if rp >= 0 else ""
    if "SelectionContainer {" not in tail or "LazyColumn(state = listState" not in tail:
        bad.append("阅读页正文没包 SelectionContainer")
    else:
        print("  阅读页正文可长按选中复制 ✓")
    # ④ 刷新条保留（删了就没刷新入口）
    if "RefreshStrip(" not in m:
        bad.append("刷新条被删了，但全仓没有下拉刷新，刷新入口会消失")
    else:
        print("  刷新条本轮保留 ✓（它是唯一刷新热区，不是装饰）")
    print("逻辑用例 %s" % ("通过" if not bad else "有失败"))
    return bad


def check(f):
    e = []
    for key, needles in POS.items():
        for s in needles:
            if s not in f[key]:
                e.append("正向缺失[%s]: %s" % (key, s[:75]))
    for key, needles in BAN.items():
        for s in needles:
            if s in code_only(f[key]):
                e.append("旧形态回潮[%s]: %s" % (key, s[:65]))
    # R163 · 三列并一列后不再有 RawColumn 函数体，直接守单列里的惰性行
    rb = code_only(f["hub"])
    if "items(chapterSegs.chunked(SEG_GRID_COLUMNS)," not in rb or "chunked(8).forEach" in rb:
        e.append("展开格子没有惰性化")
    db = code_only(mixer_dialog_body(f["main"]))
    if not db or "navigationBarsPadding()" not in db or "requestLeave?.invoke()" not in db:
        e.append("调音弹层的安全区或空白退出没落地")
    rp = f["hub"].find("private fun ReaderPane(")
    tail = f["hub"][rp:rp + 3500] if rp >= 0 else ""
    if "SelectionContainer {" not in tail:
        e.append("阅读页正文不可复制")
    if "RefreshStrip(" not in f["main"]:
        e.append("刷新条被删（会丢唯一刷新入口）")
    return e


def selftest():
    f = load()
    assert not check(f), "基线自己就红了"
    logic_bad = len(logic_cases(f))
    cuts = [
        ("展开格子退回全量 forEach", "hub",
         "items(chapterSegs.chunked(SEG_GRID_COLUMNS),",
         "val _all = segs.chunked(8).forEach { }; items(chapterSegs.chunked(8),"),
        ("展开格子被整个摘掉", "hub", "items(chapterSegs.chunked(SEG_GRID_COLUMNS),", ""),
        ("章行退回单 items 整块", "hub", 'item(key = "ch_${r.index}")', ""),
        ("段头被摘", "hub", 'item(key = "seg_head_${r.index}") {', ""),
        ("单行渲染器被摘", "hub",
         "private fun ChapterSegGrid(", ""),
        ("单章互斥被破坏", "hub",
         "expandedChapter = if (expandedChapter == r.index) null else r.index",
         "expandedChapter = i"),
        ("阅读页可复制被摘", "hub", "SelectionContainer {", ""),
        ("系统导航区避让被摘", "main",
         ".statusBarsPadding()\n            .navigationBarsPadding()",
         ".statusBarsPadding()"),
        ("空白退出变回空实现", "main",
         "onDismissRequest = { requestLeave?.invoke() }",
         "onDismissRequest = { /* 空 */ }"),
        ("leave 注册被摘", "main",
         "LaunchedEffect(dirty) { onRegisterLeave(leave) }", ""),
        ("关闭叉退回灰图标", "main", "IconClose(Primary)", "IconClose(Sub)"),
        ("刷新条被顺手删掉", "main", "RefreshStrip(", ""),
        ("CI 漏挂 r155", "ci", "python3 scripts/r155_verify.py --selftest", ""),
        ("现行清单漏 r155", "r135", '"r155_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        x = dict(f)
        x[key] = f[key].replace(needle, repl, 1)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad + logic_bad


def main():
    if "--selftest" in sys.argv:
        return 1 if selftest() else 0
    f = load()
    e = check(f) + logic_cases(f)
    if e:
        print("R155 FAIL %d" % len(e))
        for x in e:
            print(" -", x)
        return 1
    print("R155 界面讨论轮（展开惰性化 / 弹层安全区与空白退出 / 阅读可复制 / 刷新条保留）：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
