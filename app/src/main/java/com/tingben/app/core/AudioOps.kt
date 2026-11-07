package com.tingben.app.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.util.UUID

object AudioOps {
    data class Result(val ok: Boolean, val file: File? = null, val reason: String = "", val kept: Int = 0)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    fun pcmOf(f: File): Pair<ByteArray, Int>? {
        return try {
            val b = f.readBytes()
            if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            var rate = 24000
            var i = 12
            while (i + 8 <= b.size) {
                val id = String(b, i, 4, Charsets.US_ASCII)
                val sz = leInt(b, i + 4)
                if (sz < 0) return null
                if (id == "fmt " && i + 8 + 16 <= b.size) {
                    val r = leInt(b, i + 8 + 4)
                    if (r in 8000..96000) rate = r
                }
                if (id == "data") {
                    val end = minOf(i + 8 + sz, b.size)
                    if (end <= i + 8) return null
                    return b.copyOfRange(i + 8, end) to rate
                }
                i += 8 + sz + (if (sz % 2 == 1) 1 else 0)
            }
            null
        } catch (_: Throwable) { null }
    }

    fun mergeChapter(c: Context, bookId: String, bookTitle: String, chapter: Int,
                     parts: List<File>, gapMs: Int = 0, declickMs: Int = 30): Result {
        if (parts.isEmpty()) return Result(false, reason = "这一章还没有音频小块")
        val dst = File(AudioLibrary.dir(c),
            "chapter_${bookId.take(8)}_%03d_%s.wav".format(chapter + 1, UUID.randomUUID().toString().take(8)))
        val ok = WavMerger.merge(parts, dst, gapMs, declickMs)
        if (!ok || dst.length() <= 44) {
            dst.delete()
            return Result(false, reason = "拼接没成功，原来的小块都留着")
        }
        AudioLibrary.register(c, bookId, bookTitle, setOf(chapter), true, dst, chapter + 1)
        if (!AudioLibrary.lastRegisterOk) {
            return Result(true, dst, kept = parts.size,
                reason = "整章已拼好，但成品登记没成功，文件仍在「我的音频」里；原来的小块先留着")
        }
        val marked = parts.firstOrNull()?.parentFile
            ?.let { AudioTree.markDone(it, chapter, dst) } ?: false
        if (!marked) {
            return Result(true, dst, kept = parts.size,
                reason = "整章已拼好，但章状态没记上，原来的小块先留着")
        }
        val kept = deleteAll(parts)
        AudioTree.forget(c, parts.filter { !it.isFile }.map { it.absolutePath })
        return Result(true, dst, kept = kept)
    }

    fun mergedTemp(c: Context, parts: List<File>): Result {
        if (parts.isEmpty()) return Result(false, reason = "这里还没有音频小块")
        val out = File(c.cacheDir, "audio_copy_${System.currentTimeMillis()}.wav")
        val ok = WavMerger.merge(parts, out, 0, 30)
        if (!ok || out.length() <= 44) {
            out.delete()
            return Result(false, reason = "复制没成功，原文件都留着")
        }
        return Result(true, out)
    }

    fun copyTo(c: Context, src: File, uri: Uri): Boolean = try {
        val out = c.contentResolver.openOutputStream(uri) ?: return false
        val copied = out.use { o -> src.inputStream().use { i -> i.copyTo(o) } }
        if (copied <= 0L) { dropUri(c, uri); false } else true
    } catch (_: Throwable) {
        dropUri(c, uri)
        false
    }

    private fun dropUri(c: Context, uri: Uri) {
        try { android.provider.DocumentsContract.deleteDocument(c.contentResolver, uri) }
        catch (_: Throwable) { }
    }

    fun toM4a(c: Context, src: File, out: File): Result {
        val parsed = pcmOf(src) ?: return Result(false, reason = "这个文件不是能读的 WAV")
        val pcm = parsed.first
        val rate = parsed.second
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = enc
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            var track = -1
            var started = false
            var offset = 0
            var inputDone = false
            var outputDone = false
            var guard = 0
            val info = MediaCodec.BufferInfo()
            while (!outputDone && guard++ < 20000) {
                if (!inputDone) {
                    val idx = enc.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        val buf = enc.getInputBuffer(idx)
                        if (buf != null) {
                            buf.clear()
                            var n = minOf(buf.limit(), pcm.size - offset, 8192)
                            if (n % 2 == 1) n--
                            if (n > 0) {
                                buf.put(pcm, offset, n)
                                offset += n
                            }
                            val ptsUs = (offset - n).toLong() * 1_000_000L / (rate.toLong() * 2L)
                            val flags = if (offset >= pcm.size || n == 0)
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            enc.queueInputBuffer(idx, 0, maxOf(n, 0), ptsUs, flags)
                            if (flags != 0) inputDone = true
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!started) {
                            track = mx.addTrack(enc.outputFormat)
                            mx.start()
                            started = true
                        }
                    }
                    oi >= 0 -> {
                        val ob = enc.getOutputBuffer(oi)
                        if (ob != null && track >= 0 && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            mx.writeSampleData(track, ob, info)
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            if (!started || !inputDone || !outputDone || out.length() <= 0L) {
                out.delete()
                return Result(false, reason = "转格式没完成，原文件留着")
            }
            Result(true, out)
        } catch (t: Throwable) {
            out.delete()
            Result(false, reason = "转格式失败：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { muxer?.stop() } catch (_: Throwable) { }
            try { muxer?.release() } catch (_: Throwable) { }
        }
    }

    fun deleteMerged(c: Context, file: File): Boolean =
        AudioLibrary.remove(c, file.nameWithoutExtension)

    fun deleteAll(files: List<File>): Int {
        var failed = 0
        files.forEach { f ->
            if (f.isFile && !f.delete()) failed++
        }
        return failed
    }
}
