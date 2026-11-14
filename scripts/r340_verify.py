# -*- coding: utf-8 -*-
"""R340 · 图 18「重新分章」弹窗重新设计：形态守门 + 逻辑守恒。

R339 把预览与确认统一到同一份章节表之后，把改完的样子按真实代码画出来
看了一眼：标题「重新分章」+「当前 3 章 → 识别 338 章」+ 三行灰点 +
一整块橙色长句 + 实心「应用」主按钮。章号重排、分段作废这么重的事，
视觉权重跟「保存」没区别；预览只列 3 条不说这是头尾样例；按钮禁用时
没有任何解释；识别阶段一行字加一大片空白。

R340 只重排呈现：画出来的警示图标 / 规则来源卡 / 章数对比 / 章表预览 /
后果三行 / 禁用原因 / 语义明确的按钮。逻辑锚一条不动（preview / refresh /
planTick / Toast / EventLog / 按钮许可条件），不许借「重新设计」捏出
代码里没有的「选择规则」入口。

用法：python3 scripts/r340_verify.py [--selftest]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "hub": APP / "ChapterHub.kt",
    "ci": ROOT / ".github" / "workflows" / "ci.yml",
}

POS = {
    "hub": [
        # 呈现层：五块新形态
        "R340 · 图 18 重新设计",
        "drawPath(tri, color = HubWarn",
        "章号会重排，已做好的分段要重来",
        "正在按这本书的格式找章节…",
        "识别依据",
        "预览与确认用的是同一份章表",
        "将变成 $newCount 章",
        "只列开头和结尾",
        "分段音频作废，要重新做",
        "旧任务需重新选章",
        "已拼好的章音频、原书文字不动",
        "先停稳才能重新分章",
        "确认重新分章",
        # 逻辑守恒：一个都不许少
        "val bookBusy = (st.running && task?.bookId == book.id) ||",
        "Producer.isBusyForBook(book.id)",
        "enabled = pv != null && !bookBusy",
        "ChapterPlan.preview(c, book, null)",
        "ChapterPlan.refresh(c, book)",
        "planTick++",
        "已按${ChapterPlan.ruleName(rid)}重新分为 $n 章 · 旧任务请重新选章",
        "CHAPTER_RESPLIT",
    ],
    "ci": ["python3 scripts/r340_verify.py --selftest"],
}

BAN = {
    "hub": [
        # 旧形态回潮
        "当前 ${chapters.size} 章 → 识别",
        "自动识别：${ChapterPlan.ruleName(pv.first)}",
        "正在识别这本书的章节…",
        'Text("应用", color = HubPrimary)',
        "章号会变，旧任务需重新确认；分段音频作废",
        "enabled = pv != null &&\n                        !(st.running",
        # 不许借 redesign 捏一个代码里没有的假入口
        'Text("选择规则"',
        'Text("换一种分章方式"',
    ],
}


def load():
    return {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}


def check(files):
    e = []
    for k, words in POS.items():
        for w in words:
            if w not in files.get(k, ""):
                e.append("缺锚[%s]: %s" % (k, w[:60]))
    for k, words in BAN.items():
        for w in words:
            if w in files.get(k, ""):
                e.append("旧形态回潮[%s]: %s" % (k, w[:60]))
    return e


MUTANTS = [
    ("确认按钮改回「应用」", "确认重新分章", "应用"),
    ("规则来源卡被摘", "预览与确认用的是同一份章表", ""),
    ("禁用原因被摘", "先停稳才能重新分章", ""),
    ("按钮许可被放开", "enabled = pv != null && !bookBusy", "enabled = true"),
    ("图标改回放大汉字", "drawPath(tri, color = HubWarn", 'Text("！"'),
    ("refresh 被摘", "ChapterPlan.refresh(c, book)", "/* removed */"),
    ("preview 被摘", "ChapterPlan.preview(c, book, null)", "/* removed */"),
    ("后果三行少了作废那行", "分段音频作废，要重新做", ""),
    ("后果三行少了安心那行", "已拼好的章音频、原书文字不动", ""),
    ("章表预览不说明只列头尾", "只列开头和结尾", ""),
    ("规则名不显示", "识别依据", ""),
    ("识别阶段退回一行字", "正在按这本书的格式找章节…", ""),
    ("章数对比被摘", "将变成 $newCount 章", ""),
    ("警示副标题被摘", "章号会重排，已做好的分段要重来", ""),
    ("refresh 后不刷新章表", "planTick++", ""),
    ("埋点被摘", "CHAPTER_RESPLIT", ""),
    ("busy 判定被摘", "val bookBusy = (st.running && task?.bookId == book.id) ||", ""),
]


def selftest():
    base = load()
    assert not check(base), "正向不该红：%s" % check(base)
    for name, old, new in MUTANTS:
        f = dict(base)
        f["hub"] = f["hub"].replace(old, new, 1)
        e = check(f)
        assert e, "反向注入没拦下：%s" % name
    # BAN 侧：把旧形态塞回去，必须红
    for old_form in [
        "当前 ${chapters.size} 章 → 识别",
        "自动识别：${ChapterPlan.ruleName(pv.first)}",
        'Text("应用", color = HubPrimary)',
        'Text("选择规则"',
    ]:
        f = dict(base)
        f["hub"] = f["hub"] + "\n// " + old_form + "\n"
        assert check(f), "旧形态/假入口没拦下：%s" % old_form
    print("R340 重新分章弹窗重新设计：正向 PASS，反向 %d + BAN 4 全拦下"
          % len(MUTANTS))
    return 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    e = check(load())
    if e:
        for x in e:
            print(x)
        return 1
    print("R340 重新分章弹窗重新设计：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
