package com.tingben.app.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.tingben.app.diag.EventLog
import java.io.File

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
        val dst = AudioLibrary.chapterFile(c, bookId, bookTitle, chapter,
            AudioTree.chapterTitle(c, bookId, chapter))
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
        // R188 · 原来依赖「删完 isFile 刚好变 false」才成立，语义脆弱。
        val killedPaths = parts.map { it.absolutePath }
        val kept = deleteAll(parts)
        AudioTree.forget(c, killedPaths)
        return Result(true, dst, kept = kept)
    }

    /**
     * R231 · 整本合成收尾时，给「这一章」单独出一份成品音频。
     * R255 · 用户口径：整章既已拼好，中间态小块就该清掉，别占着私有目录。
     * 顺序有讲究——先写 chapter_done 再删小块：Producer 续跑靠 doneChapterFiles
     * 走 covered 跳过，不写就删会让整章重做。markDone 失败就保留小块并说实话。
     * 整本成品已在收尾前生成，音色分套不动；登记成单章 item 后小耳机直接播它。
     */
    fun mergeChapterAuto(c: Context, bookId: String, bookTitle: String, chapter: Int,
                         parts: List<File>, gapMs: Int = 0, declickMs: Int = 30): Result {
        if (parts.isEmpty()) return Result(false, reason = "这一章还没有音频小块")
        val dst = AudioLibrary.chapterFile(c, bookId, bookTitle, chapter,
            AudioTree.chapterTitle(c, bookId, chapter))
        // R231 · 具名参数：与手动 mergeChapter 的同一串区分，守门注入能精准定位
        val ok = WavMerger.merge(parts = parts, out = dst, gapMs = gapMs, declickMs = declickMs)
        if (!ok || dst.length() <= 44) {
            dst.delete()
            File(dst.parentFile, dst.name + ".part").delete()
            return Result(false, reason = "这一章拼接没成功，小块都留着")
        }
        AudioLibrary.register(c, bookId, bookTitle, only = setOf(chapter),
            direct = true, file = dst, allChapterCount = chapter + 1)
        EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_ONE",
            "ch=${chapter + 1} · ${parts.size} 块 · ${dst.length()} 字节")
        // R255 · 先记「这章的成品在哪」，续跑才会复用成品而不是重做整章。
        val work = parts.firstOrNull()?.parentFile
        val marked = work?.let { AudioTree.markDone(it, chapter, dst) } ?: false
        if (!marked) {
            return Result(true, dst, kept = parts.size,
                reason = "整章已拼好，但章状态没记上，中间小块先留着")
        }
        val killedPaths = parts.map { it.absolutePath }
        val kept = deleteAll(parts)
        AudioTree.forget(c, killedPaths)
        EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_CLEAN",
            "ch=${chapter + 1} · 清掉 ${killedPaths.size - kept} 个中间小块")
        return Result(true, dst, kept = parts.size)
    }

    /**
     * R243 · 章节成品补齐：制作收尾与目录页回溯共用这一处，别再各写一套。
     * 旧行为只在整本跑完那一刻拼一次，装新版前已合成好的小段永远长不出
     * 章节成品；这里按章补，**只看真章级成品**（R265），整本成品登记覆盖了
     * 全部章但不能算「这章已拼」——否则整本一收尾，338 章的补拼全被跳过。
     * 缺块章跳过（用户删掉的段由调用方排除，不在这里猜）。
     */
    fun ensureChapterAudios(c: Context, bookId: String, bookTitle: String,
                            byChapter: Map<Int, List<File>>,
                            gapMs: Int = 0, declickMs: Int = 30,
                            force: Boolean = false): Pair<Int, Int> {
        // R272 · 收尾必须无条件拼章：整本大文件已不再生成，章成品是唯一成品形态，
        // 拼好才清中间小块。开关只管「目录页回溯补齐」要不要自动做，
        // 不能让一次正常制作跑完却一个可播文件都没有。
        if (!force && !Prefs.autoMergeChapter(c)) return 0 to 0
        var made = 0
        var skipped = 0
        for ((ci, parts) in byChapter) {
            if (AudioLibrary.hasChapterAudio(c, bookId, ci)) { skipped++; continue }
            if (parts.isEmpty() || parts.any { !it.isFile || it.length() <= 44L }) {
                skipped++; continue
            }
            if (mergeChapterAuto(c, bookId, bookTitle, ci, parts,
                    gapMs = gapMs, declickMs = declickMs).ok) made++ else skipped++
        }
        // R292 · 0/0 不打空日志：新书还没做、回溯跑一次也是全空，
        // 真机诊断里三条「补拼 0 章 / 跳过 0 章」只是噪声。
        if (made > 0 || skipped > 0) {
            EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_ENSURE",
                "补拼 $made 章 / 跳过 $skipped 章")
        }
        return made to skipped
    }

    fun mergedTemp(c: Context, parts: List<File>): Result {
        if (parts.isEmpty()) return Result(false, reason = "这里还没有音频小块")
        val out = TrialFiles.file(c, "audio_copy_${System.currentTimeMillis()}.wav")
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
        AudioLibrary.removeFile(c, file)

    fun deleteAll(files: List<File>): Int {
        var failed = 0
        files.forEach { f ->
            if (f.isFile && !f.delete()) failed++
        }
        return failed
    }
}
