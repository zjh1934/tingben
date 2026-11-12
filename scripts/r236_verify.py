#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R236 · 参考音频波形拖拽裁剪（借鉴 Ringdroid 交互，Compose 自绘）。

口径：
  · 预览解码不卡 30 秒：整首 mp3 也要先看全段波形才能拖选；30 秒硬闸挪到裁剪落盘
  · clipWav 纯字节裁剪，不二次解码；输出仍是 24k/单声道/16-bit WAV
  · 波形峰值封顶 400 根柱，长音频不往 Canvas 塞上万次 drawLine
  · 默认选前 10 秒（参考音频公开口径 3~10 秒最稳）
  · 顶层 composable 必须 internal（UI 片段跨文件可见，R202/R206 硬口径）
  · 日志不落用户文件路径（R225 脱敏）
用法：python3 scripts/r236_verify.py [--selftest]
"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "decode": rd(APP / "core/AudioDecode.kt"),
        "mixer": rd(APP / "MixerUi.kt"),
        "agents": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    d, m = f["decode"], f["mixer"]
    # ① 预览不卡死 / 峰值封顶 / 纯字节裁剪
    for k in ["private const val MAX_SECONDS = 30",
              "private const val MAX_DECODE_SECONDS = 600",
              "private const val PEAK_BUCKETS = 400",
              "if (target > TARGET_RATE * MAX_DECODE_SECONDS)",
              "先裁出一小段再导入",
              "val win = maxOf((TARGET_RATE * WINDOW_MS / 1000).coerceAtLeast(1),",
              "(pcm.size + PEAK_BUCKETS - 1) / PEAK_BUCKETS).coerceAtLeast(1)",
              "val peaks = FloatArray((pcm.size + win - 1) / win)",
              "fun clipWav(wav: ByteArray, startMs: Long, endMs: Long): ByteArray",
              "String(wav, 0, 4, Charsets.US_ASCII) != \"RIFF\"",
              "val rate = b.getInt(24).coerceAtLeast(8000)",
              "val frameBytes = (channels * bits / 8).coerceAtLeast(1)",
              "if (n < rate / 2)",
              "if (n > rate * MAX_SECONDS)",
              "ob.putInt(4, 36 + n * frameBytes)",
              "ob.putInt(40, n * frameBytes)",
              "System.arraycopy(wav, 44 + s * frameBytes, out, 44, n * frameBytes)",
              '"AUDIO_CLIPPED"']:
        if k not in d: e.append("解码/裁剪底座缺: " + k[:60])
    if "uri.toString()" in d or "uri.path" in d:
        e.append("解码日志落了用户文件路径")
    # 旧口径不许回潮：预览阶段一刀切 30 秒拒绝
    if "参考音频最长 $MAX_SECONDS 秒，这段约" in d:
        e.append("预览解码又卡 30 秒，整首 mp3 没法拖选")
    # ② 导入链路：先解码 → 弹裁剪框 → 拖选后落盘
    for k in ["var refClip by remember { mutableStateOf<DecodedAudio?>(null) }",
              "AudioDecode.decode(c, uri)",
              "r.onSuccess { refClip = it }",
              "fun applyRefClip(d: DecodedAudio, startMs: Long, endMs: Long)",
              "VoiceRef.save(c, AudioDecode.clipWav(d.wav, startMs, endMs))",
              "refClip?.let { d ->",
              "RefClipDialog(",
              "onCancel = { PlayQueue.stopPreview(); refClip = null }",
              "onUse = { s, e -> PlayQueue.stopPreview(); applyRefClip(d, s, e) }",
              "internal fun RefClipDialog(",
              "Text(\"导入并裁剪 ›\""]:
        if k not in m: e.append("导入链路缺: " + k[:60])
    if "private fun RefClipDialog(" in m:
        e.append("顶层 composable 用了 private，UI 片段跨文件不可见")
    for banned in ["VoiceRef.saveFromUri(c, uri)", "VoiceRef.save(c, b)"]:
        if banned in m: e.append("导入又退回 R234 旧路径: " + banned)
    if "uri.toString()" in m or "uri.path" in m:
        e.append("导入日志落了用户文件路径")
    # ③ 波形交互：拖两端、默认 10 秒、画选区、确认按钮
    for k in ["import androidx.compose.foundation.Canvas",
              "import androidx.compose.foundation.gestures.detectDragGestures",
              "import androidx.compose.ui.geometry.Offset",
              "import kotlin.math.abs",
              "mutableStateOf(minOf(1f, 10_000f / totalMs))",
              "var dragging by remember(data) { mutableStateOf(0) }",
              "val selectedMs = (totalMs * (endFrac - startFrac)).toLong()",
              ".pointerInput(data.peaks.size)",
              "detectDragGestures(",
              "onDragStart = { pos ->",
              "dragging = if (abs(f - startFrac) <= abs(f - endFrac)) 1 else 2",
              "onDrag = { change, _ ->",
              "change.consume()",
              "startFrac = minOf(f, endFrac - 0.02f).coerceAtLeast(0f)",
              "endFrac = maxOf(f, startFrac + 0.02f).coerceAtMost(1f)",
              "if (f in startFrac..endFrac) Primary",
              "drawLine(Warn, Offset(startFrac * w, 0f)",
              "drawLine(Warn, Offset(endFrac * w, 0f)",
              '"已选 %.1f 秒 / 共 %.1f 秒".format(',
              "onUse((totalMs * startFrac).toLong(), (totalMs * endFrac).toLong())",
              'Text("用这一段"']:
        if k not in m: e.append("波形交互缺: " + k[:60])
    # ④ 口径与登记
    for k in ["波形裁剪（R236）", "Ringdroid", "30 秒硬闸挪到裁剪落盘"]:
        if k not in f["agents"]: e.append("AGENTS 漏写口径: " + k)
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r236_verify.py --selftest"),
                              ("清单", f["r135"], '"r236_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if name == "受管根":
            from r45_common import scope_indexed
            if not scope_indexed(f["config"], "app/src/main/java"):
                e.append(name + " 漏登记 R236")
        elif needle not in src: e.append(name + " 漏登记 R236")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("预览又卡 30 秒", "decode", "if (target > TARGET_RATE * MAX_DECODE_SECONDS)", "if (target > TARGET_RATE * MAX_SECONDS)"),
        ("峰值不封顶", "decode", "(pcm.size + PEAK_BUCKETS - 1) / PEAK_BUCKETS).coerceAtLeast(1)", "1"),
        ("clipWav 被摘", "decode", "fun clipWav(wav: ByteArray, startMs: Long, endMs: Long): ByteArray", ""),
        ("30 秒落盘闸被摘", "decode", "if (n > rate * MAX_SECONDS)", "if (false)"),
        ("半秒下限被摘", "decode", "if (n < rate / 2)", "if (false)"),
        ("WAV 头没回填", "decode", "ob.putInt(40, n * frameBytes)", ""),
        ("PCM 没裁", "decode", "System.arraycopy(wav, 44 + s * frameBytes, out, 44, n * frameBytes)", ""),
        ("裁剪日志被摘", "decode", '"AUDIO_CLIPPED"', ""),
        ("导入不解码", "mixer", "AudioDecode.decode(c, uri)", "VoiceRef.save(c, byteArrayOf())"),
        ("解码不弹裁剪框", "mixer", "r.onSuccess { refClip = it }", ""),
        ("不裁剪直接落盘", "mixer", "VoiceRef.save(c, AudioDecode.clipWav(d.wav, startMs, endMs))", "VoiceRef.save(c, d.wav)"),
        ("裁剪框被摘", "mixer", "refClip?.let { d ->", ""),
        ("顶层改 private", "mixer", "internal fun RefClipDialog(", "private fun RefClipDialog("),
        ("默认全段", "mixer", "mutableStateOf(minOf(1f, 10_000f / totalMs))", "mutableStateOf(1f)"),
        ("波形不能拖", "mixer", "detectDragGestures(", ""),
        ("把手判定被摘", "mixer", "dragging = if (abs(f - startFrac) <= abs(f - endFrac)) 1 else 2", "dragging = 1"),
        ("拖动不更新", "mixer", "startFrac = minOf(f, endFrac - 0.02f).coerceAtLeast(0f)", ""),
        ("选区不高亮", "mixer", "if (f in startFrac..endFrac) Primary", "Primary"),
        ("边界线被摘", "mixer", "drawLine(Warn, Offset(startFrac * w, 0f)", ""),
        ("确认不传区间", "mixer", "onUse((totalMs * startFrac).toLong(), (totalMs * endFrac).toLong())", "onUse(0L, 0L)"),
        ("日志落用户路径", "mixer", "val r = runCatching { AudioDecode.decode(c, uri) }",
         'val r = runCatching { AudioDecode.decode(c, uri).also { EventLog.log(EventLog.Kind.MODEL, "X", uri.toString()) } }'),
        ("CI 漏挂", "ci", "python3 scripts/r236_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r236_verify.py"', ""),
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
        print("R236 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R236 波形拖拽裁剪 / 30 秒闸后移 / 纯字节裁剪：PASS")
