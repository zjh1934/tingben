#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R352 · 目录页任务开关与任务页重设计的形态守门。

看图后定的口径（对照 /data/workspace/听本_R352_看一眼/ 六张预览图）：
  · 目录页「停止 / 继续 / 开始任务」与「接着听 / 按顺序播放」同一排、三枚等大；
    时间从 12sp 单行主文案挪到副行，任务开关副行写清点下去会怎样；
  · 任务页不再四互斥页签，顶部常驻现在卡 + 三段筛选只收窄分区；
  · 音频卡名字两行（与设置页「本机音频」同口径），待续卡书名两行；
  · 批量条把「项」与「可播/可导出的音频份数」分开，空选点下去有反馈；
  · 队列暂停态不画 0% 空进度条，两枚按钮各管一件事。

用法：python3 scripts/r352_verify.py [--selftest]
"""
import pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import ui_sources

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / ui_sources.UI
FILES = {
    "hub": APP / "ChapterHub.kt",
    "task": APP / "TaskUi.kt",
    # R206 · 主文件走合并视图读取层，不退回直读单文件
    "main": ui_sources.source(ROOT),
}

POS = {
    "hub": [
        'private fun RowScope.HubActChip(',
        'sub: String = ""',                       # 副行参数
        '.height(44.dp)',                         # 三枚同高
        'Text(sub, modifier = Modifier.fillMaxWidth(),',  # R363 · 副行也锁在按键宽内
        '"接着听" else "从头听"',                  # 主文案不再带 mm:ss
        '"断点 %d:%02d".format(',                      # 断点时间仍显示
        'val primarySub = when {',                # 任务开关状态说明
        '"按勾选开做"',                        # R363 · 短文案，字体放大也不顶出按键
        '"收尾这一段"',
        'HubActChip("按顺序播放"',
        'onOpenTasks: () -> Unit = {}',
        # R360 · rc #250 真编译抓到：Modifier.clip 缺 import、尾随 lambda 与
        # 带默认值的 sub 参数错位（onClick 必须显式具名，不能靠尾随 lambda）
        'import androidx.compose.ui.draw.clip',
        'sub = primarySub, onClick = { hubPrimaryAction() })',
        'sub = resumeSub,\n                    onClick = {',
        'canListen && !bookRunning,',
        '队列 ${queued.size} · 去任务页 ›',
        # R363 · 真机截图里文字顶出按键：列宽锁死 + 单行省略是硬要求
        'Text(label, modifier = Modifier.fillMaxWidth(),',
        'softWrap = false, overflow = TextOverflow.Ellipsis)',
    ],
    "task": [
        'FilterRow(filter) { filter = it }',
        'listOf("全部", "待续", "已完成")',       # 计数只在分区标题
        'SectionRow("待续 · ${pending.size}"',
        'SectionRow("做好的音频 · ${audioRows.size}"',
        'tag: String = "正在做"',
        'progressLabel: String = "看进度 ›"',
        'secondLabel: String = "去这一章 ›"',
        'showProgress: Boolean = true',
        'tag = "现在"',                           # 队列暂停态
        'progressLabel = "看待续 ›"',
        'secondLabel = "去挑一本 ›"',
        'showProgress = false',
        'onOpen = onLibrary',
        'maxLines = 2, overflow = TextOverflow.Ellipsis)',   # 音频卡 / 待续卡两行
        'Spacer(Modifier.height(8.dp))',          # 进度与操作行拉开
        '"已选 $n 项 · $audioN 份音频"',
        'val canAudio = audioN > 0 && !exporting',
        'if (canAudio) Primary else Line',
        'Toast.makeText(c, "先勾一份做好的音频，待续任务还不能播"',
        'Toast.makeText(c, "先勾一份做好的音频再导出"',
    ],
    "main": [
        'Screen.TASKS -> TaskCenterScreen(ctx, refresh,',
        'onOpenTask = { id -> openBook(id) }',
        'onOpenTasks = { nav.push(Screen.TASKS) })',
    ],
}

# 旧形态 / 死代码回潮禁令
BAN = {
    "hub": [
        '"接着听 · %d:%02d ›".format(',           # 时间塞回 12sp 单行
        'val primaryBg =',                        # 恒等死变量
        '.height(38.dp)\n        .clip(RoundedCornerShape(12.dp))\n        .background(if (enabled) bg else HubLine)',
        # R360 · rc #250：三枚按钮退回尾随 lambda → Gradle 报
        # No value passed for parameter 'onClick' / Too many arguments
        'sub = primarySub) {',
        'sub = resumeSub) {',
        'sub = "整本连着听") {',
        # R363 · 长副文案回潮（系统字体放大会顶出 1/3 宽的按键）
        '"接着做不用重来"',
        '"按勾选的章开做"',
        'sub = "整本连着听"',
    ],
    "task": [
        'onAudio: (String) -> Unit',              # 死参数回潮
        '"已选 $n 份"',
        '"待续 $pendingN"',
        'pct = 0, stage = "队列已暂停 · 点接着做恢复"',
        'onOpen = { filter = 1 },',               # 暂停态两枚按钮同做一件事
        'var tab by rememberSaveable',
        'val done = remember(tasks)',
    ],
    "main": [
        'onAudio = { id -> openBook(id); refresh++ }',
        'IconButton(onClick = { hubStop() })',    # 右上角小图标回潮
        'val hubRunning = hubProd.running',
    ],
}


def check(f):
    e = []
    for k, needles in POS.items():
        for s in needles:
            if s not in f[k]:
                e.append("正向缺失[%s]: %s" % (k, s[:66]))
    for k, needles in BAN.items():
        for s in needles:
            if s in f[k]:
                e.append("旧形态回潮[%s]: %s" % (k, s[:60]))
    # 音频卡名字必须两行：只在 AudioCard 组件体内查，别误伤 NowCard 的单行标题
    t = f["task"]
    a = t.find("internal fun AudioCard(")
    b = t.find("internal fun SectionLabel(", a)
    if a < 0 or b < 0:
        e.append("AudioCard 组件边界找不到")
    else:
        m = re.search(r"Text\(a\.name,[\s\S]{0,200}?maxLines = (\d)", t[a:b])
        if not m or m.group(1) != "2":
            e.append("音频卡名字不是两行")
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()}
    assert not check(base), "基线自己就红了，先修基线"
    cuts = [
        ("三枚等大被摘", "hub", '.height(44.dp)', '.height(38.dp)'),
        ("副行被摘", "hub", 'Text(sub, modifier = Modifier.fillMaxWidth(),', 'Text(sub, fontSize = 12.sp'),
        ("任务开关状态说明被摘", "hub", 'val primarySub = when {', 'val primarySub = 0'),
        ("时间又塞回主文案", "hub", '"接着听" else "从头听"', '"接着听 · %d:%02d ›".format('),
        ("队列真出口被摘", "hub", 'onOpenTasks: () -> Unit = {}', 'onOpenTasks: () -> Unit = { }'),
        ("筛选重复计数回潮", "task", 'listOf("全部", "待续", "已完成")', 'listOf("全部", "待续 $pendingN", "已完成 $doneN")'),
        ("分区计数被摘", "task", 'SectionRow("待续 · ${pending.size}"', 'SectionRow("待续"'),
        ("暂停态空进度条回潮", "task", 'showProgress = false,', 'showProgress = true,'),
        ("暂停态两枚按钮又同做一件事", "task", 'onOpen = onLibrary,', 'onOpen = { filter = 1 },'),
        ("暂停态看待续标签被摘", "task", 'progressLabel = "看待续 ›"', 'progressLabel = "看进度 ›"'),
        ("音频名两行被摘", "task", 'maxLines = 2, overflow = TextOverflow.Ellipsis)', 'maxLines = 1, overflow = TextOverflow.Ellipsis)'),
        ("批量条项/份混写回潮", "task", '"已选 $n 项 · $audioN 份音频"', '"已选 $n 份"'),
        ("空选静默回潮", "task", 'Toast.makeText(c, "先勾一份做好的音频，待续任务还不能播"', 'return'),
        ("导出空选静默回潮", "task", 'Toast.makeText(c, "先勾一份做好的音频再导出"', 'return'),
        ("未勾音频仍亮主色", "task", 'val canAudio = audioN > 0 && !exporting', 'val canAudio = true'),
        ("死参数 onAudio 回潮", "task", 'onLibrary: () -> Unit,', 'onAudio: (String) -> Unit, onLibrary: () -> Unit,'),
        ("死变量 tab 回潮", "task", 'var loadTick by remember { mutableStateOf(0) }', 'var tab by rememberSaveable { mutableStateOf(0) }'),
        ("右上角小停止图标回潮", "main", 'Screen.TASKS -> TaskCenterScreen(ctx, refresh,', 'IconButton(onClick = { hubStop() }) { StopIcon(Warn) }'),
        ("clip import 被摘（rc #250 真编译错）", "hub", 'import androidx.compose.ui.draw.clip\n', ''),
        ("第一枚按钮尾随 lambda 回潮（rc #250）", "hub",
         'sub = primarySub, onClick = { hubPrimaryAction() })', 'sub = primarySub) { hubPrimaryAction() }'),
        ("第三枚按钮尾随 lambda 回潮（rc #250）", "hub",
         'canListen && !bookRunning,',
         'sub = "从第一章起") {\n                    playBook(null, null)\n                }'),
        ("任务页按卡片 id 回书被摘", "main", 'onOpenTask = { id -> openBook(id) }', 'onOpenTask = { }'),
    ]
    bad = 0
    for name, k, old, new in cuts:
        x = dict(base); x[k] = base[k].replace(old, new, 1)
        if not check(x):
            bad += 1
            print("  反向注入漏放:", name)
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return 1 if bad else 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    e = check({k: p.read_text(encoding="utf-8", errors="replace") for k, p in FILES.items()})
    if e:
        print("R352 FAIL %d" % len(e))
        for x in e: print(" -", x)
        return 1
    print("R352 目录页任务开关 / 任务页重设计形态守门：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
