package com.tingben.app.core

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.tingben.app.diag.EventLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * R234 · 参考音频解码底座：任意容器/编码 → 24kHz / 单声道 / 16-bit WAV。
 *
 * 用的是 **Android 系统内置的 SDK**（MediaExtractor 解复用 + MediaCodec 硬解码），
 * 不引 FFmpeg / 第三方 aar，不加 INTERNET 权限，离线可用；支持 mp3 / m4a / aac /
 * ogg / wav 等系统能解的格式。整体路线借鉴 Ringdroid（AOSP 开源铃声编辑器）：
 * 平台解码出 PCM → 画波形 → 拖选区间 → 导出 WAV，而不是自己手搓解码器。
 *
 * 波形峰值数组按 WINDOW_MS 分窗给出，供裁剪 UI 直接画（R234 下一批接）。
 * 日志只记 mime / 采样率 / 声道 / 时长 / 字节数，绝不落用户文件路径或文件名。
 */
data class DecodedAudio(
    val wav: ByteArray,        // 44 字节头 + 16-bit PCM
    val peaks: FloatArray,     // 每窗峰值，0~1
    val durationMs: Long,
    val sourceRate: Int,
    val sourceChannels: Int
)

object AudioDecode {

    private const val TARGET_RATE = 24000
    // 落盘的硬上限：模型吃不了太长的参考音频，公开口径 3~10 秒最稳
    private const val MAX_SECONDS = 30
    // R236 · 预览解码不设 30 秒闸：用户手里多是整首 mp3，得先看到全段波形
    // 才能拖选其中一小段。这里只留一个防 OOM 的兜底，真正 30 秒校验在 clipWav。
    private const val MAX_DECODE_SECONDS = 600
    private const val WINDOW_MS = 20
    // 波形最多画这么多根柱：3 分钟音频也不会往 Canvas 里塞上万次 drawLine
    private const val PEAK_BUCKETS = 400
    private const val TIMEOUT_US = 10_000L

    /** startMs / endMs 是用户波形拖选出的区间；endMs < 0 表示到文件末尾。 */
    fun decode(c: Context, uri: Uri, startMs: Long = 0, endMs: Long = -1): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(c, uri, null)
        } catch (t: Throwable) {
            extractor.release()
            throw IllegalStateException("打不开这段音频，换一个文件试试")
        }
        var track = -1
        var mime = ""
        var srcRate = 0
        var srcChannels = 1
        var durationUs = 0L
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (!m.startsWith("audio/")) continue
            track = i
            mime = m
            if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (f.containsKey(MediaFormat.KEY_DURATION))
                durationUs = f.getLong(MediaFormat.KEY_DURATION)
            break
        }
        if (track < 0 || mime.isEmpty()) {
            extractor.release()
            throw IllegalStateException("这个文件里没有能读的音轨")
        }
        extractor.selectTrack(track)

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            extractor.release()
            throw IllegalStateException("系统解不了这种音频格式")
        }
        try {
            codec.configure(extractor.getTrackFormat(track), null, null, 0)
            codec.start()
        } catch (t: Throwable) {
            try { codec.release() } catch (_: Throwable) { }
            extractor.release()
            throw IllegalStateException("音频解码器起不来，换一个文件试试")
        }

        val info = MediaCodec.BufferInfo()
        var pcmRate = srcRate.coerceAtLeast(8000)
        var pcmChannels = srcChannels.coerceIn(1, 8)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val guess = ((durationUs / 1_000_000.0) * pcmRate).toInt() + pcmRate
        var mono = FloatArray(guess.coerceAtLeast(pcmRate))
        var monoLen = 0
        var inputDone = false
        var outputDone = false
        val fromUs = startMs.coerceAtLeast(0) * 1000L
        val toUs = if (endMs > 0L) endMs * 1000L else Long.MAX_VALUE

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = extractor.readSampleData(inBuf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            pcmRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            pcmChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
                        pcmEncoding = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val b = outBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            val bytesPer = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val frames = info.size / (bytesPer * pcmChannels)
                            for (f in 0 until frames) {
                                val tUs = info.presentationTimeUs +
                                    f.toLong() * 1_000_000L / pcmRate
                                if (tUs < fromUs || tUs > toUs) continue
                                var acc = 0f
                                for (ch in 0 until pcmChannels) {
                                    val p = info.offset + (f * pcmChannels + ch) * bytesPer
                                    if (p + bytesPer > info.offset + info.size) break
                                    acc += when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> b.getFloat(p)
                                        AudioFormat.ENCODING_PCM_8BIT ->
                                            ((b.get(p).toInt() and 0xff) - 128) / 128f
                                        else -> b.getShort(p).toFloat() / 32768f
                                    }
                                }
                                if (monoLen >= mono.size) mono = mono.copyOf(mono.size * 2)
                                mono[monoLen++] = (acc / pcmChannels).coerceIn(-1f, 1f)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) { }
            try { codec.release() } catch (_: Throwable) { }
            extractor.release()
        }

        if (monoLen < pcmRate / 2)
            throw IllegalStateException("这段音频太短，至少要半秒")
        // 线性重采样到 24kHz；与 VoiceRef 原有口径一致
        val target = (monoLen.toLong() * TARGET_RATE / pcmRate).toInt()
        // R236 · 预览阶段只防离谱长文件；选区落盘由 clipWav 卡 MAX_SECONDS
        if (target > TARGET_RATE * MAX_DECODE_SECONDS)
            throw IllegalStateException("这段音频太长，先裁出一小段再导入")
        val pcm = FloatArray(target)
        for (i in 0 until target) {
            val src = i.toDouble() * pcmRate / TARGET_RATE
            val i0 = src.toInt().coerceAtMost(monoLen - 1)
            val i1 = (i0 + 1).coerceAtMost(monoLen - 1)
            val f = (src - i0).toFloat()
            pcm[i] = mono[i0] * (1f - f) + mono[i1] * f
        }
        // 波形峰值：按窗口取绝对值峰值，裁剪 UI 直接拿去画
        // R236 · 长音频动态放大窗口，峰值数组封顶 PEAK_BUCKETS
        val win = maxOf((TARGET_RATE * WINDOW_MS / 1000).coerceAtLeast(1),
            (pcm.size + PEAK_BUCKETS - 1) / PEAK_BUCKETS).coerceAtLeast(1)
        val peaks = FloatArray((pcm.size + win - 1) / win)
        for (w in peaks.indices) {
            var peak = 0f
            val a = w * win
            val b = minOf(a + win, pcm.size)
            for (i in a until b) peak = maxOf(peak, abs(pcm[i]))
            peaks[w] = peak
        }
        // 44 字节 WAV 头 + 16-bit PCM（24kHz / 单声道）
        val bytes = ByteArray(44 + pcm.size * 2)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putInt(bytes, 4, 36 + pcm.size * 2)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        putInt(bytes, 16, 16)
        putShort(bytes, 20, 1); putShort(bytes, 22, 1)
        putInt(bytes, 24, TARGET_RATE); putInt(bytes, 28, TARGET_RATE * 2)
        putShort(bytes, 32, 2); putShort(bytes, 34, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(bytes, 36)
        putInt(bytes, 40, pcm.size * 2)
        val ob = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in pcm.indices) ob.putShort(44 + i * 2, (pcm[i] * 32767f).toInt().toShort())

        EventLog.log(EventLog.Kind.MODEL, "AUDIO_DECODED",
            "mime=$mime · ${pcmRate}Hz / ${pcmChannels}ch · " +
                "${pcm.size * 1000L / TARGET_RATE}ms · ${bytes.size} 字节 · 峰值窗 ${peaks.size}")
        return DecodedAudio(bytes, peaks, pcm.size * 1000L / TARGET_RATE,
            pcmRate, pcmChannels)
    }

    /**
     * R236 · 从已解码的全段 WAV 里按时间裁一段，纯字节操作、不二次解码。
     * 借鉴 Ringdroid「选好区间再导出」的顺序；输出仍是 24kHz / 单声道 / 16-bit WAV，
     * 直接交给 VoiceRef.save() 走原校验与原子落盘。
     */
    fun clipWav(wav: ByteArray, startMs: Long, endMs: Long): ByteArray {
        if (wav.size < 46 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE")
            throw IllegalStateException("这段解码结果不是 WAV，重新选一次文件")
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val rate = b.getInt(24).coerceAtLeast(8000)
        val channels = b.getShort(22).toInt().coerceIn(1, 8)
        val bits = b.getShort(34).toInt().coerceIn(8, 32)
        val frameBytes = (channels * bits / 8).coerceAtLeast(1)
        val total = minOf(b.getInt(40), wav.size - 44) / frameBytes
        if (total < rate / 2) throw IllegalStateException("解码出来的声音太短")
        val s = (startMs.coerceAtLeast(0L) * rate / 1000L)
            .toInt().coerceIn(0, (total - 1).coerceAtLeast(0))
        val e = (if (endMs <= 0L) total.toLong()
                 else (endMs * rate / 1000L).coerceAtMost(total.toLong()))
            .toInt().coerceIn(s + 1, total)
        val n = e - s
        if (n < rate / 2) throw IllegalStateException("选中的片段太短，至少要半秒")
        if (n > rate * MAX_SECONDS)
            throw IllegalStateException("选中的片段最长 $MAX_SECONDS 秒，往回拖一点")
        val out = ByteArray(44 + n * frameBytes)
        wav.copyInto(out, 0, 0, 44)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        ob.putInt(4, 36 + n * frameBytes)
        ob.putInt(40, n * frameBytes)
        System.arraycopy(wav, 44 + s * frameBytes, out, 44, n * frameBytes)
        EventLog.log(EventLog.Kind.MODEL, "AUDIO_CLIPPED",
            "${n * 1000L / rate}ms · ${out.size} 字节")
        return out
    }

    private fun putInt(a: ByteArray, o: Int, v: Int) {
        a[o] = (v and 0xff).toByte(); a[o + 1] = ((v shr 8) and 0xff).toByte()
        a[o + 2] = ((v shr 16) and 0xff).toByte(); a[o + 3] = ((v shr 24) and 0xff).toByte()
    }
    private fun putShort(a: ByteArray, o: Int, v: Int) {
        a[o] = (v and 0xff).toByte(); a[o + 1] = ((v shr 8) and 0xff).toByte()
    }
}
