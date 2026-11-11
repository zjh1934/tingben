#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R234 · 内嵌 SDK 选型落地：平台内置 MediaExtractor+MediaCodec 解码底座。

口径：
  · 参考音频不再只吃未压缩 WAV，mp3/m4a/aac/ogg 统一解成 24k 单声道 16-bit WAV
  · 用 Android 系统内置 SDK，不引 FFmpeg / Amplituda / jitpack，运行时零联网
  · 波形峰值数组按窗产出（借鉴 Ringdroid 做法），裁剪 UI 下一批接
  · 播放器 R228 引 AndroidX Media3（官方内嵌库），本地播放不需 INTERNET 权限
用法：python3 scripts/r234_verify.py [--selftest]
"""
import pathlib, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/tingben/app"

def files():
    def rd(p): return p.read_text(encoding="utf-8", errors="replace")
    return {
        "decode": rd(APP / "core/AudioDecode.kt"),
        "ref": rd(APP / "core/VoiceRef.kt"),
        "mixer": rd(APP / "MixerUi.kt"),
        "gradle": rd(ROOT / "app/build.gradle.kts"),
        "toml": rd(ROOT / "gradle/libs.versions.toml"),
        "agents": rd(ROOT / "AGENTS.md"),
        "ci": rd(ROOT / ".github/workflows/ci.yml"),
        "r135": rd(ROOT / "scripts/r135_verify.py"),
        "config": rd(ROOT / ".aoci/config.json"),
    }

def check(f):
    e = []
    d, r, m = f["decode"], f["ref"], f["mixer"]
    # ① 平台内置解码底座
    for k in ["import android.media.MediaExtractor",
              "import android.media.MediaCodec",
              "import android.media.MediaFormat",
              "import android.media.AudioFormat",
              "import android.net.Uri",
              "object AudioDecode",
              "private const val TARGET_RATE = 24000",
              "private const val MAX_SECONDS = 30",
              "private const val MAX_DECODE_SECONDS = 600",
              "private const val PEAK_BUCKETS = 400",
              "fun clipWav(wav: ByteArray, startMs: Long, endMs: Long): ByteArray",
              "if (n > rate * MAX_SECONDS)",
              '"AUDIO_CLIPPED"',
              "private const val WINDOW_MS = 20",
              "extractor.setDataSource(c, uri, null)",
              "extractor.selectTrack(track)",
              "MediaCodec.createDecoderByType(mime)",
              "codec.configure(extractor.getTrackFormat(track), null, null, 0)",
              "MediaCodec.INFO_OUTPUT_FORMAT_CHANGED",
              "MediaCodec.BUFFER_FLAG_END_OF_STREAM",
              "codec.releaseOutputBuffer(outIdx, false)",
              "codec.stop()", "codec.release()", "extractor.release()",
              "val fromUs = startMs.coerceAtLeast(0) * 1000L",
              "val toUs = if (endMs > 0L) endMs * 1000L else Long.MAX_VALUE",
              "val peaks = FloatArray((pcm.size + win - 1) / win)",
              '"fmt ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)',
              'putShort(bytes, 20, 1); putShort(bytes, 22, 1)',
              'putInt(bytes, 24, TARGET_RATE)',
              '"AUDIO_DECODED"']:
        if k not in d: e.append("解码底座缺: " + k[:60])
    # 不许把用户文件路径/URI 写进日志
    if "uri.toString()" in d or "uri.path" in d:
        e.append("日志落了用户文件路径，违反 R225 脱敏口径")
    # PCM 编码必须按输出格式判断，不能写死 16bit
    if "AudioFormat.ENCODING_PCM_FLOAT" not in d:
        e.append("没按 outputFormat 处理浮点 PCM，某些机型会出杂音")
    # ② VoiceRef 接线
    for k in ["import android.net.Uri",
              "fun saveFromUri(c: Context, uri: Uri, startMs: Long = 0, endMs: Long = -1): DecodedAudio",
              "val d = AudioDecode.decode(c, uri, startMs, endMs)",
              "save(c, d.wav)", "return d"]:
        if k not in r: e.append("VoiceRef 接线缺: " + k[:56])
    # ③ 导入入口（R236 迁移）：先解码拿波形 → 裁剪 → 再走 save 校验落盘
    for k in ["AudioDecode.decode(c, uri)", "var refClip by remember",
              "AudioDecode.clipWav(d.wav, startMs, endMs)", "VoiceRef.save(c, AudioDecode.clipWav"]:
        if k not in m: e.append("调音台导入链路缺: " + k[:56])
    if "VoiceRef.save(c, b)" in m:
        e.append("调音台又退回只读 WAV 字节")
    # ④ 选型口径：不引 FFmpeg 系、播放器定 Media3
    for banned in ["ffmpeg", "amplituda", "jitpack"]:
        if banned in f["gradle"].lower() or banned in f["toml"].lower():
            e.append("选型回潮：又引了 %s" % banned)
    for k in ["media3-exoplayer", "media3-session", "MediaSessionService",
              "不引 FFmpeg", "R234"]:
        if k not in f["agents"]: e.append("AGENTS 漏写选型口径: " + k)
    # ⑤ 登记
    for name, src, needle in [("CI", f["ci"], "python3 scripts/r234_verify.py --selftest"),
                              ("清单", f["r135"], '"r234_verify.py"'),
                              ("受管根", f["config"], '"app/src/main/java"')]:
        if needle not in src: e.append(name + " 漏登记 R234")
    return e

def selftest():
    f = files()
    assert not check(f), "基线自己就红了"
    cuts = [
        ("MediaExtractor 被摘", "decode", "import android.media.MediaExtractor", ""),
        ("硬解改软解", "decode", "MediaCodec.createDecoderByType(mime)", "TODO()"),
        ("输出格式不处理", "decode", "MediaCodec.INFO_OUTPUT_FORMAT_CHANGED", "MediaCodec.INFO_TRY_AGAIN_LATER"),
        ("EOS 不收尾", "decode", "MediaCodec.BUFFER_FLAG_END_OF_STREAM", "0"),
        ("不释放解码器", "decode", "codec.release()", ""),
        ("不释放解复用器", "decode", "extractor.release()", ""),
        ("裁剪起点被摘", "decode", "val fromUs = startMs.coerceAtLeast(0) * 1000L", "val fromUs = 0L"),
        ("30 秒落盘闸被摘", "decode", "if (n > rate * MAX_SECONDS)", "if (false)"),
        ("clipWav 被摘", "decode", "fun clipWav(wav: ByteArray, startMs: Long, endMs: Long): ByteArray", ""),
        ("预览又卡 30 秒", "decode", "private const val MAX_DECODE_SECONDS = 600", "private const val MAX_DECODE_SECONDS = 30"),
        ("波形峰值被摘", "decode", "val peaks = FloatArray((pcm.size + win - 1) / win)", ""),
        ("日志落用户路径", "decode", '"AUDIO_DECODED"', '"AUDIO_DECODED" + uri.toString()'),
        ("采样率写死非 24k", "decode", "private const val TARGET_RATE = 24000", "private const val TARGET_RATE = 44100"),
        ("单声道被改", "decode", "putShort(bytes, 22, 1)", "putShort(bytes, 22, 2)"),
        ("saveFromUri 被摘", "ref", "fun saveFromUri(c: Context, uri: Uri, startMs: Long = 0, endMs: Long = -1): DecodedAudio", ""),
        ("解码没接上", "ref", "val d = AudioDecode.decode(c, uri, startMs, endMs)", ""),
        ("没走原校验", "ref", "save(c, d.wav)", ""),
        ("导入不解码", "mixer", "AudioDecode.decode(c, uri)", "VoiceRef.save(c, byteArrayOf())"),
        ("不裁剪直接落盘", "mixer", "AudioDecode.clipWav(d.wav, startMs, endMs)", "d.wav"),
        ("又引 FFmpeg", "gradle", "implementation(libs.androidx.core.ktx)",
         'implementation("com.ffmpeg:ffmpeg:1.0")'),
        ("播放器选型丢失", "agents", "media3-exoplayer", ""),
        ("CI 漏挂", "ci", "python3 scripts/r234_verify.py --selftest", ""),
        ("清单漏登记", "r135", '"r234_verify.py"', ""),
    ]
    bad = 0
    for name, key, needle, repl in cuts:
        # 同一锚点可能在文件里出现多次（EOS 标志、释放调用等），
        # 注入必须全量替换，否则留一处就判据恒真、反向注入漏放
        g = dict(f); g[key] = f[key].replace(needle, repl)
        if check(g): print("  反向注入 %-20s 拦下" % name)
        else: print("  反向注入漏放:", name); bad += 1
    print("反向注入 %d/%d 全拦下" % (len(cuts) - bad, len(cuts)))
    return bad

if __name__ == "__main__":
    if "--selftest" in sys.argv: sys.exit(1 if selftest() else 0)
    e = check(files())
    if e:
        print("R234 FAIL", len(e))
        for x in e: print(" -", x)
        sys.exit(1)
    print("R234 平台内置解码底座 / 参考音频多格式 / 播放器选型：PASS")
