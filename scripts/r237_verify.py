#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R237 · 通知两条进度条 + 裁剪弹层试听选中段。

口径：
  · 制作通知改自定义 RemoteViews：上条整本 done/total、下条当前章 chapterPct，
    一个 TextView 都不放（用户明确不要解释文字）；锁屏 PUBLIC 兜底仍不含书名
  · 复用 update() 早已传给悬浮窗的 chapterPct，Producer 链路一行不动、不引 Media3
  · 裁剪弹层「试听选中」：clipWav 出临时 wav → PlayQueue 的 "trial" preview 通道，
    不占整本书的播放控制通知、不落 VoiceRef
用法：python3 scripts/r237_verify.py [--selftest]
"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "layout": rd(ROOT / "app/src/main/res/layout/notif_two_bars.xml"),
        "svc": rd(APP / "ProduceService.kt"),
        "mixer": rd(APP / "MixerUi.kt"),
        "agents": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    x, s, m = f["layout"], f["svc"], f["mixer"]
    # ① 布局：两条进度条、零文字
    for k in ['android:id="@+id/bar_book"', 'android:id="@+id/bar_chapter"',
              'style="?android:attr/progressBarStyleHorizontal"',
              'android:indeterminate="false"', 'android:max="100"']:
        if k not in x: e.append("通知布局缺: " + k[:52])
    if x.count("<ProgressBar") != 2: e.append("通知布局进度条不是正好两条")
    if "TextView" in x: e.append("通知布局又塞了文字，用户明确不要解释文字")
    # ② 通知构建
    for k in ["import android.widget.RemoteViews",
              "@Volatile private var lastChapterPct = -1f",
              "lastChapterPct = chapterPct",
              "val rv = RemoteViews(c.packageName, com.tingben.app.R.layout.notif_two_bars)",
              "val max = total.coerceAtLeast(1)",
              "rv.setProgressBar(com.tingben.app.R.id.bar_book, max,",
              "rv.setProgressBar(com.tingben.app.R.id.bar_chapter, 100,",
              "(chapterPct.coerceIn(0f, 1f) * 100f).toInt()",
              ".setStyle(Notification.DecoratedCustomViewStyle())",
              ".setCustomContentView(rv)",
              ".setCustomBigContentView(rv)",
              "c: Context, title: String, done: Int, total: Int,",
              "chapterPct: Float, final: Boolean",
              "buildNotification(ctx, lastTitle,",
              "lastDone, lastTotal, lastChapterPct, final)",
              "buildNotification(this, lastTitle,",
              "lastDone, lastTotal, lastChapterPct, true)",
              "buildNotification(this, title, lastDone, lastTotal,",
              "lastChapterPct, finished)",
              ".setContentTitle(\"听本\")",
              '"正在本地制作音频"']:
        if k not in s: e.append("通知构建缺: " + k[:56])
    for banned in [".setContentText(text)", "b.setProgress(", ".setContentText(msg"]:
        if banned in s: e.append("通知又写回废话文字/单条进度: " + banned)
    if s.count("setProgressBar(") != 2:
        e.append("两条进度条没都接上 RemoteViews")
    # ③ 裁剪试听
    for k in ["fun trialRefClip(d: DecodedAudio, startMs: Long, endMs: Long)",
              "val bytes = AudioDecode.clipWav(d.wav, startMs, endMs)",
              'File(c.cacheDir, "ref_clip_trial.wav")',
              "f.writeBytes(bytes)",
              'PlayQueue.setTracks(listOf(Track(f, "裁剪试听",',
              'id = "trial:ref_clip")), "trial")',
              'PlayQueue.playFrom(0, id = "trial")',
              "onTrial = { s, e -> trialRefClip(d, s, e) }",
              "onTrial: (Long, Long) -> Unit,",
              "internal fun RefClipDialog(",
              'Text("试听选中"',
              "onTrial((totalMs * startFrac).toLong(),"]:
        if k not in m: e.append("裁剪试听缺: " + k[:56])
    # preview 通道不占通知：仍走 "trial"，不许塞进主队列
    if 'PlayQueue.setTracks(listOf(Track(f, "裁剪试听",' not in m:
        e.append("试听没走 preview 通道")
    # ④ 口径与登记
    for k in ["通知两条进度（R237）", "裁剪试听（R237）", "不引 Media3"]:
        if k not in f["agents"]: e.append("AGENTS 漏写口径: " + k)
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r237_verify.py --selftest"),
                              ("清单", f["r135"], '"r237_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if needle not in src: e.append(name + " 漏登记 R237")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("删整本进度条", "layout", 'android:id="@+id/bar_book"', ""),
        ("删当前章进度条", "layout", 'android:id="@+id/bar_chapter"', ""),
        ("布局塞文字", "layout", "<LinearLayout", "<TextView android:layout_width=\"1dp\"/>\n<LinearLayout"),
        ("RemoteViews 被摘", "svc", "val rv = RemoteViews(c.packageName, com.tingben.app.R.layout.notif_two_bars)", ""),
        ("整本条没接", "svc", "rv.setProgressBar(com.tingben.app.R.id.bar_book, max,", ""),
        ("当前章条没接", "svc", "rv.setProgressBar(com.tingben.app.R.id.bar_chapter, 100,", ""),
        ("章进度没存", "svc", "lastChapterPct = chapterPct", ""),
        ("章进度没传通知", "svc", "lastDone, lastTotal, lastChapterPct, final)", "lastDone, lastTotal, final)"),
        ("不设自定义视图", "svc", ".setCustomContentView(rv)", ""),
        ("不设 BigView", "svc", ".setCustomBigContentView(rv)", ""),
        ("写回单条系统进度", "svc", "if (!final) {", "b.setProgress(total, done, false)\n    if (!final) {"),
        ("写回废话文字", "svc", ".setContentTitle(title)", ".setContentTitle(title).setContentText(text)"),
        ("锁屏公共版被摘", "svc", '"正在本地制作音频"', ""),
        ("试听函数被摘", "mixer", "fun trialRefClip(d: DecodedAudio, startMs: Long, endMs: Long)", ""),
        ("试听不裁剪", "mixer", "val bytes = AudioDecode.clipWav(d.wav, startMs, endMs)", "val bytes = d.wav"),
        ("试听没写临时文件", "mixer", "f.writeBytes(bytes)", ""),
        ("试听占主队列", "mixer", 'id = "trial:ref_clip")), "trial")', 'id = "trial:ref_clip")), "book")'),
        ("试听没起播", "mixer", 'PlayQueue.playFrom(0, id = "trial")', ""),
        ("弹层没接试听", "mixer", "onTrial = { s, e -> trialRefClip(d, s, e) }", ""),
        ("试听按钮被摘", "mixer", 'Text("试听选中"', ""),
        ("试听不传区间", "mixer", "onTrial((totalMs * startFrac).toLong(),", "onTrial(0L,"),
        ("弹层改 private", "mixer", "internal fun RefClipDialog(", "private fun RefClipDialog("),
        ("CI 漏挂", "ci", "python3 scripts/r237_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r237_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        g = dict(f); g[key] = f[key].replace(needle, repl)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R237 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R237 通知两条进度 / 裁剪试听选中段：PASS")
