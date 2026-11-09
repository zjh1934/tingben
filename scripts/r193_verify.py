#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R193 · 「停止后收尾窗口」守卫。

点停止后 running 立刻 false，busy 要等当前段收尾（1~3 分钟）才清。
这段时间后台仍握着 work/<id>；旧代码用 `!st.running` 判断，会让用户
在收尾中重新分章 / 删书，把正在写的目录 deleteRecursively 掉。

四道：
  A Producer.isBusyForBook（busy && current?.bookId == bookId）
  B ChapterPlan.refresh 开头硬守卫（IO 线程最后一道，抛中文异常）
  C ChapterHub 按钮按书判断（别的书在制作不再误挡）
  D BookStore.delete 返回 Boolean，收尾中先 stop、什么都不删、UI 给反馈

selftest 纯内存注入 + 逻辑对拍，源文件零写入。
用法：python3 scripts/r193_verify.py [--selftest]
"""
import core_sources
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": core_sources.source(APP),
    "plan": APP / "core/ChapterPlan.kt",
    "hub": APP / "ChapterHub.kt",
    "main": APP / "MainActivity.kt",
    "struct": ROOT / "scripts/check_structure.py",
    "r135": ROOT / "scripts/r135_verify.py",
    "ci": ROOT / ".github/workflows/ci.yml",
}

NEED = {
    "pipe": [
        "fun isBusyForBook(bookId: String): Boolean",
        "busy && current?.bookId == bookId",
        "fun delete(c: Context, id: String): Boolean",
        "if (Producer.isBusyForBook(id)) {",
        'EventLog.log(EventLog.Kind.MODEL, "BOOK_DELETE_DEFERRED"',
        "return false",
        "purge(c, id)\n        return true",
    ],
    "plan": [
        "if (Producer.isBusyForBook(book.id))",
        'throw IllegalStateException("这本书还在收尾上一段，等它停稳再重新分章")',
    ],
    "hub": [
        "!(st.running && task?.bookId == book.id)",
        "!Producer.isBusyForBook(book.id)",
        "这本书正在制作或收尾时先等它停稳；别的书在制作不影响。",
    ],
    "main": [
        "val deleted = BookStore.delete(c, id)",
        "if (deleted) onChanged()",
        "这本书还在收尾上一段，已点停止；稍等再删一次",
        "还在收尾上一段时会先停，稍等再删一次。",
    ],
    "struct": [
        'if "BookStore.delete(c, id)" not in main_src:',
        'if "confirmDelete = null" not in main_src:',
        'if "if (deleted) onChanged()" not in main_src:',
    ],
    "r135": ['"r193_verify.py"'],
    "ci": [
        "python3 scripts/r193_verify.py --selftest",
        "python3 scripts/r193_verify.py",
    ],
}
BAN = [
    "enabled = pv != null && !st.running, onClick =",   # 旧按钮判据
    "BookStore.delete(c, id); confirmDelete = null; onChanged()",  # 旧整句
]


def checks(f):
    e = []
    for key, needles in NEED.items():
        for s in needles:
            if s not in f[key]:
                e.append("能力缺失[%s]: %s" % (key, s[:66]))
    for s in BAN:
        for key in ("hub", "main", "struct"):
            if s in f[key]:
                e.append("旧形态回潮[%s]: %s" % (key, s[:56]))
    # B 守卫必须在读 64MB 文本之前，否则白读还可能已经动过状态
    p = f["plan"]
    if not (p.find("if (Producer.isBusyForBook(book.id))") <
            p.find("val text = BookStore.read(c, book)")):
        e.append("refresh 守卫没放在读文本之前")
    # D 守卫必须在删原书文件 / 改索引之前。
    # BookStore.add 里也有 `val all = list(c)`，全文 find 会命中最早那处。
    d = f["pipe"]
    st = d.find("fun delete(c: Context, id: String): Boolean {")
    dbody = d[st:] if st >= 0 else d
    if not (0 <= dbody.find("if (Producer.isBusyForBook(id)) {") <
            dbody.find("val all = list(c)")):
        e.append("delete 守卫没放在改索引之前")
    # 自动防漏
    live = set(__import__("re").findall(
        r'scripts/(r(?:1[6-9]\d|[2-9]\d\d)_verify\.py)', f["ci"]))
    declared = set(__import__("re").findall(r'"(r\d+_verify\.py)"', f["r135"]))
    gap = sorted(live - declared - {"r135_verify.py"})
    if gap:
        e.append("现行守门漏登记 CURRENT_VERIFIERS: " + ", ".join(gap))
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("isBusyForBook 被摘", "pipe",
         lambda t: t.replace("fun isBusyForBook(bookId: String): Boolean",
                             "fun unusedBusyForBook(bookId: String): Boolean")),
        ("isBusyForBook 只看 busy 不看是哪本", "pipe",
         lambda t: t.replace("busy && current?.bookId == bookId", "busy")),
        ("delete 守卫被摘", "pipe",
         lambda t: t.replace("        if (Producer.isBusyForBook(id)) {\n"
                             "            try { Producer.stopIfBook(id) } catch (_: Throwable) { }\n"
                             "            EventLog.log(EventLog.Kind.MODEL, \"BOOK_DELETE_DEFERRED\",\n"
                             "                \"书=$id 还在收尾上一段，已点停止、暂不删除，等停稳再删\")\n"
                             "            return false\n"
                             "        }\n", "")),
        ("delete 退回 Unit 返回", "pipe",
         lambda t: t.replace("fun delete(c: Context, id: String): Boolean {",
                             "fun delete(c: Context, id: String) {")),
        ("delete 真删后不返回 true", "pipe",
         lambda t: t.replace("        purge(c, id)\n        return true", "        purge(c, id)")),
        ("refresh 守卫被摘", "plan",
         lambda t: t.replace("        if (Producer.isBusyForBook(book.id))\n"
                             "            throw IllegalStateException(\"这本书还在收尾上一段，等它停稳再重新分章\")\n", "")),
        ("refresh 守卫挪到读文本之后", "plan",
         lambda t: t.replace(
             "        if (Producer.isBusyForBook(book.id))\n"
             "            throw IllegalStateException(\"这本书还在收尾上一段，等它停稳再重新分章\")\n"
             "        val text = BookStore.read(c, book)",
             "        val text = BookStore.read(c, book)\n"
             "        if (Producer.isBusyForBook(book.id))\n"
             "            throw IllegalStateException(\"这本书还在收尾上一段，等它停稳再重新分章\")")),
        ("按钮退回全局 !st.running", "hub",
         lambda t: t.replace("enabled = pv != null &&\n"
                             "                        !(st.running && task?.bookId == book.id) &&\n"
                             "                        !Producer.isBusyForBook(book.id), onClick = {",
                             "enabled = pv != null && !st.running, onClick = {")),
        ("按钮漏掉收尾守卫", "hub",
         lambda t: t.replace("                        !Producer.isBusyForBook(book.id), onClick = {",
                             "                        true, onClick = {")),
        ("弹窗不提收尾窗口", "hub",
         lambda t: t.replace("这本书正在制作或收尾时先等它停稳；别的书在制作不影响。",
                             "不管别的书在不在制作都一样。")),
        ("UI 又忽略 delete 返回值", "main",
         lambda t: t.replace("                    val deleted = BookStore.delete(c, id)\n"
                             "                    confirmDelete = null\n"
                             "                    if (deleted) onChanged()\n"
                             "                    else Toast.makeText(c, \"这本书还在收尾上一段，已点停止；稍等再删一次\",\n"
                             "                        Toast.LENGTH_LONG).show()",
                             "                    BookStore.delete(c, id); confirmDelete = null; onChanged()")),
        ("UI 删书失败也刷新列表", "main",
         lambda t: t.replace("if (deleted) onChanged()", "onChanged()")),
        ("check_structure 退回整句锚点", "struct",
         lambda t: t.replace('if "BookStore.delete(c, id)" not in main_src:',
                             'if "BookStore.delete(c, id); confirmDelete = null; onChanged()" not in main_src:')),
        ("忘登记 CURRENT_VERIFIERS", "r135",
         lambda t: t.replace(', "r193_verify.py"', "", 1)),
        ("CI 漏挂 r193", "ci",
         lambda t: t.replace("python3 scripts/r193_verify.py --selftest", "")),
    ]
    bad = 0
    for name, key, mut in cases:
        v = dict(base)
        v[key] = mut(base[key])
        assert v[key] != base[key], "注入没生效: " + name
        if not checks(v):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下（纯内存，源文件零写入）" % (len(cases) - bad, len(cases)))
    bad += logic_check()
    return bad


def logic_check():
    """模拟「点停止后的收尾窗口」，对比新旧判据。"""
    bad = 0
    try:
        def old_allows(st_running, task_book, this_book):
            # 旧：只看全局 !st.running（停止后立刻为 true 可操作）
            return not st_running

        def old_button_allows(st_running, task_book, this_book):
            # R193 之前的按钮：全局 !st.running
            return not st_running

        def busy_for_book(busy, current_book, this_book):
            return busy and current_book == this_book

        def new_allows(st_running, task_book, this_book, busy, current_book):
            making_this = st_running and task_book == this_book
            return not (making_this or busy_for_book(busy, current_book, this_book))

        # ① 收尾窗口：running 已 false、busy 仍 true、current 还是这本书
        assert old_allows(False, "A", "A") is True, "旧判据本该误放行"
        assert busy_for_book(True, "A", "A") is True
        assert new_allows(False, "A", "A", True, "A") is False, "新判据没挡住收尾窗口"

        # ② 别的书在制作：旧按钮误挡，新按钮按书放行
        assert old_button_allows(True, "B", "A") is False, "旧按钮本该误挡"
        assert new_allows(True, "B", "A", True, "B") is True, "别的书在制作不该挡这本书"

        # ③ 这本书正在制作
        assert new_allows(True, "A", "A", True, "A") is False

        # ④ 完全停稳：busy 清了，放行
        assert new_allows(False, "A", "A", False, "A") is True

        # ⑤ 别的书在收尾，这本书随便改
        assert new_allows(False, "B", "A", True, "B") is True

        print("逻辑对拍 5/5：收尾窗口被挡 / 别的书不误挡 / 制作中挡 / 停稳放行 / 别书收尾不挡")
    except Exception as ex:
        bad += 1
        print("  逻辑对拍失败:", str(ex)[:120])
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R193 收尾窗口守卫")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    print("R193 收尾窗口守卫：按书判断 / refresh 与 delete 双硬守 / UI 反馈：PASS")
