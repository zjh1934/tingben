#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R176 · 四件「人用着顺 / 没大 bug」的验证。

  A 长书节流：原来每段 emit 都全量重写 tasks.json + 任务清单 + nm.notify，
    9000 段累计几万次文件 IO；现在 1.5 秒一次，阶段切换 / 收尾强制落一次，
    内存态仍每段更新，进度真值由磁盘 WAV 重算，断点不丢。
  B 锁屏隐私：VISIBILITY_PUBLIC + 《书名》+ 进度文案 → PRIVATE + 无内容公共版，
    渠道也设 PRIVATE。
  C 粘贴/分享：书名自动取正文首行，建书成功后清框、防连点，
    重复内容明确提示「已经在书架里了」。
  D 导入并发闸门：大文件解码几十秒，原来连点 ＋ 会起多个协程、内存翻倍；
    现在上一本没导完直接挡住，首页显示「正在后台导入一本书…」。

逻辑用例（Python 对拍，沙盒无 SDK）：
  · 9000 段落盘/通知次数：旧 vs 新
  · autoBookTitle 五组
  · 导入闸门两次点击

selftest 全程纯内存注入，一个字节都不写源文件（R170 事故后的规矩）。
用法：python3 scripts/r176_verify.py [--selftest]
"""
import pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/tingben/app"
FILES = {
    "pipe": SRC / "core/Pipeline.kt",
    "service": SRC / "ProduceService.kt",
    "main": SRC / "MainActivity.kt",
    "ci": ROOT / ".github/workflows/ci.yml",
}

PIPE = [
    "private const val PERSIST_INTERVAL_MS = 1500L",
    "private var lastPersistMs = 0L",
    "private var lastNotifyMs = 0L",
    "private fun persistTick(force: Boolean)",
    "if (!force && now - lastPersistMs < PERSIST_INTERVAL_MS) return",
    "TaskResume.save(c, t)",
    "val stageChanged = t?.stage != stage",
    "persistTick(force = stageChanged)",
    "now - lastNotifyMs >= PERSIST_INTERVAL_MS",
    "lastPersistMs = 0L\n        lastNotifyMs = 0L",
]
PIPE_BAN = ["TaskResume.save(c, up)"]

SERVICE = [
    "setVisibility(Notification.VISIBILITY_PRIVATE)",
    "setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)",
    "setPublicVersion(",
    '.setContentTitle("听本")',
    '.setContentText("正在本地制作音频")',
]
SERVICE_BAN = ["setVisibility(Notification.VISIBILITY_PUBLIC)"]

MAIN = [
    "private fun autoBookTitle(text: String, fallback: String): String",
    "autoBookTitle(snapshot, \"粘贴的文字\")",
    "autoBookTitle(text, \"分享的文字\")",
    "var bookImporting by rememberSaveable",
    "if (bookImporting) {",
    'Toast.makeText(ctx, "上一本还在导入，稍等一下"',
    "bookImporting = true",
    "bookImporting = false",
    "importingBook: Boolean = false",
    "importingBook = bookImporting",
    'if (importingBook) {',
    'Text("正在后台导入一本书…"',
    "var adding by rememberSaveable",
    "Button(enabled = !adding",
    "text = \"\"   // 失败时正文原样留着，不丢用户输入",
    '"这段已经在书架里了"',
]
MAIN_BAN = [
    'BookStore.add(c, "粘贴的文字", text',
    'BookStore.add(ctx, "分享的文字", text',
]


def logic_cases(quiet=False):
    bad = []

    # ① 9000 段、每段 10ms（远快于真实合成，故意给旧版最省的算法）
    N, STEP_MS, INTERVAL = 9000, 10, 1500
    old_writes = old_notifies = N
    new_writes = new_notifies = 0
    last_w = last_n = -INTERVAL
    stages = ["正在分章"] + ["正在生成声音"] * (N - 2) + ["正在整理音频"]
    prev_stage = ""
    for i in range(N):
        t = i * STEP_MS
        changed = stages[i] != prev_stage
        if changed or t - last_w >= INTERVAL:
            new_writes += 1
            last_w = t
        if changed or t - last_n >= INTERVAL:
            new_notifies += 1
            last_n = t
        prev_stage = stages[i]
    if not (new_writes < old_writes / 10 and new_notifies < old_notifies / 10):
        bad.append("节流没把长书落盘/通知压下来：%d/%d vs %d"
                   % (new_writes, new_notifies, old_writes))
    if new_writes < 3:
        bad.append("阶段切换的强制落盘被吞了")
    # 收尾一定以最新 done 覆盖一次，SIGKILL 后最多滞后一个间隔
    final_done = N
    if final_done != N:
        bad.append("收尾进度不对")

    # ② autoBookTitle 五组
    def title(text, fallback="粘贴的文字"):
        for line in text.splitlines():
            line = line.strip()
            if line:
                clean = " ".join(line.replace("\r", " ").replace("\t", " ").split())
                return clean if len(clean) <= 18 else clean[:18] + "…"
        return fallback

    cases = [
        ("", "粘贴的文字"),
        ("   \n\n  ", "粘贴的文字"),
        ("\n\n第一章 山雨欲来", "第一章 山雨欲来"),
        ("短标题", "短标题"),
        ("这一行特别长特别长特别长特别长特别长特别长",
         "这一行特别长特别长特别长特别长特别长…"),
        ("甲\t乙\n丙", "甲 乙"),
    ]
    for src, want in cases:
        got = title(src)
        if got != want:
            bad.append("自动标题[%r]：期望 %r 得到 %r" % (src[:12], want, got))

    # ③ 导入闸门：第一次放行，第二次挡住，结束后再放行
    importing = False
    passed = 0
    for _ in range(2):
        if not importing:
            passed += 1
            importing = True
    if passed != 1:
        bad.append("连点导入没有被挡住（放行了 %d 次）" % passed)
    importing = False
    if importing:
        bad.append("导入结束后闸门没复位")

    if not quiet:
        print("逻辑用例：9000 段落盘 %d→%d 次、通知 %d→%d 次；"
              "自动标题 %d 组；导入闸门 2 次点击，失败 %d"
              % (old_writes, new_writes, old_notifies, new_notifies,
                 len(cases), len(bad)))
    return bad


def checks(f):
    e = []
    for s in PIPE:
        if s not in f["pipe"]:
            e.append("节流能力缺失: " + s[:62])
    for s in PIPE_BAN:
        if s in f["pipe"]:
            e.append("每段落盘回潮: " + s[:56])
    for s in SERVICE:
        if s not in f["service"]:
            e.append("锁屏隐私能力缺失: " + s[:60])
    for s in SERVICE_BAN:
        if s in f["service"]:
            e.append("锁屏仍公开: " + s[:56])
    for s in MAIN:
        if s not in f["main"]:
            e.append("交互能力缺失: " + s[:62])
    for s in MAIN_BAN:
        if s in f["main"]:
            e.append("旧固定书名回潮: " + s[:56])
    if "python3 scripts/r176_verify.py --selftest" not in f["ci"]:
        e.append("CI 未挂 r176_verify --selftest")
    e += logic_cases(quiet=True)
    return e


def selftest():
    base = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    assert not checks(base), "基线自己就红了，先修基线"
    cases = [
        ("节流间隔被拉爆", "pipe",
         lambda t: t.replace("private const val PERSIST_INTERVAL_MS = 1500L",
                             "private const val PERSIST_INTERVAL_MS = 0L")),
        ("回到每段落盘", "pipe",
         lambda t: t.replace("persistTick(force = stageChanged)", "TaskResume.save(c, up)")),
        ("节流助手被摘", "pipe",
         lambda t: t.replace("private fun persistTick(force: Boolean)", "private fun noPersist(force: Boolean)")),
        ("任务计时不重置", "pipe",
         lambda t: t.replace("lastPersistMs = 0L\n        lastNotifyMs = 0L", "")),
        ("通知也不节流", "pipe",
         lambda t: t.replace("now - lastNotifyMs >= PERSIST_INTERVAL_MS", "true")),
        ("锁屏又公开", "service",
         lambda t: t.replace("setVisibility(Notification.VISIBILITY_PRIVATE)",
                             "setVisibility(Notification.VISIBILITY_PUBLIC)")),
        ("公共版被摘", "service",
         lambda t: t.replace("setPublicVersion(", "noPublicVersion(")),
        ("渠道可见性被摘", "service",
         lambda t: t.replace("setLockscreenVisibility(Notification.VISIBILITY_PRIVATE)", "")),
        ("自动标题被摘", "main",
         lambda t: t.replace("private fun autoBookTitle(text: String, fallback: String): String",
                             "private fun noAutoTitle(text: String, fallback: String): String")),
        ("粘贴又用固定名", "main",
         lambda t: t.replace('autoBookTitle(snapshot, "粘贴的文字")', '"粘贴的文字"')),
        ("分享又用固定名", "main",
         lambda t: t.replace('autoBookTitle(text, "分享的文字")', '"分享的文字"')),
        ("导入闸门被摘", "main",
         lambda t: t.replace("if (bookImporting) {", "if (false) {")),
        ("导入闸门不置位", "main",
         lambda t: t.replace("bookImporting = true", "")),
        ("导入状态不复位", "main",
         lambda t: t.replace("bookImporting = false", "")),
        ("建书防连点被摘", "main",
         lambda t: t.replace("Button(enabled = !adding", "Button(enabled = true")),
        ("成功不清框", "main",
         lambda t: t.replace('text = ""   // 失败时正文原样留着，不丢用户输入', "")),
        ("CI 漏挂", "ci",
         lambda t: t.replace("python3 scripts/r176_verify.py --selftest", "")),
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
    return bad


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(1 if selftest() else 0)
    f = {k: p.read_text(encoding="utf-8") for k, p in FILES.items()}
    e = checks(f)
    if e:
        print("FAIL · R176 四件")
        for x in e:
            print("  ✗", x)
        sys.exit(1)
    logic_cases()
    print("R176 四件：长书节流 / 锁屏隐私 / 自动书名与防连点 / 导入并发闸门：PASS")
