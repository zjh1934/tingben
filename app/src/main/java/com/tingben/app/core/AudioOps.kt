package com.tingben.app.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.tingben.app.diag.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.RandomAccessFile

object AudioOps {
    data class Result(val ok: Boolean, val file: File? = null, val reason: String = "", val kept: Int = 0)

    /**
     * R308 · 章级拼接/转码的实时阶段。
     * 目录页拿它在章名上盖蒙层（"正在拼接中"/"正在转码中"），
     * 免得整章 WAV 又大又没提示，用户只看到进度条发呆。
     * 用 StateFlow 而不是轮询，阶段一变界面立刻跟着变。
     */
    data class MergeBusy(val bookId: String, val chapter: Int, val phase: String)
    private val _mergeBusy = MutableStateFlow<MergeBusy?>(null)
    val mergeBusy: StateFlow<MergeBusy?> get() = _mergeBusy
    private fun setBusy(bookId: String, chapter: Int, phase: String?) {
        _mergeBusy.value = if (phase == null) null else MergeBusy(bookId, chapter, phase)
    }

    private const val M4A_BIT_RATE = 64000
    private const val M4A_CHANNELS = 1

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    /** 兼容保留：手动小文件用。整章转码走 pcmSpan + 流式，绝不把 PCM 读进堆。 */
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

    /**
     * R308 · 只扫 WAV 的 chunk 头，返回 (data 起始偏移, data 字节数, 采样率)。
     * 与 WavMerger 同一套无符号 Long 拼法：畸形头不许原地打转，
     * 高位为 1 的大文件也不会被当成负数。整章 PCM 从头到尾不驻留内存。
     */
    private fun pcmSpan(f: File): Triple<Long, Long, Int>? = try {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(12)
            raf.readFully(h)
            if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(h, 8, 4, Charsets.US_ASCII) != "WAVE") null
            else {
                val fileLen = raf.length()
                var pos = 12L
                var span: Pair<Long, Long>? = null
                var rate = 24000
                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val ch = ByteArray(8)
                    raf.readFully(ch)
                    val id = String(ch, 0, 4, Charsets.US_ASCII)
                    val sz = (ch[4].toLong() and 0xffL) or
                        ((ch[5].toLong() and 0xffL) shl 8) or
                        ((ch[6].toLong() and 0xffL) shl 16) or
                        ((ch[7].toLong() and 0xffL) shl 24)
                    val payload = pos + 8
                    if (payload + sz > fileLen) break
                    if (id == "fmt " && sz >= 16) {
                        val b = ByteArray(16)
                        raf.seek(payload); raf.readFully(b)
                        val r = leInt(b, 4)
                        if (r in 8000..96000) rate = r
                    } else if (id == "data") {
                        span = payload to minOf(sz, fileLen - payload)
                        break
                    }
                    val pad = if (sz % 2L == 1L) 1L else 0L
                    val next = payload + sz + pad
                    if (next <= pos) break
                    pos = next
                }
                span?.let { Triple(it.first, it.second, rate) }
            }
        }
    } catch (_: Throwable) { null }

    /** R308 · 转码完先自己验一遍：有音频轨、有正时长、文件不为空，才敢删内部 WAV。 */
    private fun validateM4a(f: File): Boolean {
        if (!f.isFile || f.length() <= 1024L) return false
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(f.absolutePath)
            var ok = false
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = try { fmt.getString(MediaFormat.KEY_MIME) ?: "" }
                    catch (_: Throwable) { "" }
                if (!mime.startsWith("audio/")) continue
                ok = fmt.containsKey(MediaFormat.KEY_DURATION) &&
                    fmt.getLong(MediaFormat.KEY_DURATION) > 0L
                break
            }
            ex.release()
            ok
        } catch (_: Throwable) { false }
    }

    /**
     * R308 · 整章 WAV → 64kbps / 单声道 / AAC-LC 的 M4A。
     * 原来是 pcmOf(src).readBytes() 把整章 PCM 一次读进堆，长章必 OOM；
     * 现在按 8KB 缓冲从 data chunk 流式喂 MediaCodec，内存与章长无关。
     * 平台自带编解码，不引第三方库、不加权限。
     */
    // R369 · stopCheck 默认关闭，只有「自动章拼接」传入。真机日志：点停止后
    // 章边界已经在转码，还要再跑四分钟才收尾；手动「转 M4A」不带这个回调，
    // 行为不变。命中后统一走下面的 stop/release 与 out.delete()，不留半成品。
    fun toM4a(c: Context, src: File, out: File,
              stopCheck: (() -> Boolean)? = null): Result {
        val span = pcmSpan(src) ?: return Result(false, reason = "这个文件不是能读的 WAV")
        val (offset, len, rate) = span
        if (len <= 0L) return Result(false, reason = "这个 WAV 里没有声音数据")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, rate, M4A_CHANNELS)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, M4A_BIT_RATE)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = enc
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mx = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            var track = -1
            var started = false
            var fed = 0L
            var inputDone = false
            var outputDone = false
            var stopped = false
            var guard = 0
            val info = MediaCodec.BufferInfo()
            val buf = ByteArray(8192)
            RandomAccessFile(src, "r").use { raf ->
                raf.seek(offset)
                while (!outputDone && !stopped && guard++ < 2000000) {
                    if (stopCheck?.invoke() == true) { stopped = true; break }
                    if (!inputDone) {
                        val idx = enc.dequeueInputBuffer(10000)
                        if (idx >= 0) {
                            val ib = enc.getInputBuffer(idx)
                            if (ib != null) {
                                ib.clear()
                                var n = minOf(buf.size.toLong(), len - fed,
                                    ib.limit().toLong()).toInt()
                                if (n % 2 == 1) n--
                                if (n > 0) {
                                    raf.readFully(buf, 0, n)
                                    ib.put(buf, 0, n)
                                    fed += n
                                }
                                val ptsUs = (fed - n) * 1_000_000L /
                                    (rate.toLong() * 2L * M4A_CHANNELS)
                                val flags = if (fed >= len || n == 0)
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
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                                outputDone = true
                        }
                    }
                }
            }
            // 先把封装器收尾写好 moov box，再用 MediaExtractor 校验；
            // 不 stop 就校验会读不到时长，长章会被误判成转码失败。
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { muxer?.stop() } catch (_: Throwable) { }
            try { muxer?.release() } catch (_: Throwable) { }
            codec = null; muxer = null
            if (stopped || !started || !inputDone || !outputDone || !validateM4a(out)) {
                out.delete()
                return Result(false,
                    reason = if (stopped) "已停止，转码中断，内部 WAV 留着"
                             else "转格式没完成，原文件留着")
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

    /** 拼章结果：最终成品、内部 WAV、是否真的转成了 M4A。 */
    private data class Encoded(val file: File, val wav: File, val converted: Boolean)

    /**
     * R308 · 拼完自动转 M4A：拼接 → 转码 → 校验。
     * 校验过了先不急着删 WAV，等登记成功再删；转码失败 WAV 原样留着，
     * 用户仍可用「另存 WAV」手动兜底，绝不让一章音频凭空消失。
     */
    private fun encodeChapter(c: Context, bookId: String, bookTitle: String, chapter: Int,
                              parts: List<File>, gapMs: Int, declickMs: Int,
                              stopCheck: (() -> Boolean)? = null): Encoded? {
        setBusy(bookId, chapter, "正在拼接中")
        try {
            // R369 · 点停止到章边界之间的窗口：别再开一份四分钟的拼接转码。
            // 在 try 内 return 仍会走 finally 清 mergeBusy，章名蒙层不会卡住。
            if (stopCheck?.invoke() == true) return null
            val wav = AudioLibrary.chapterFile(c, bookId, bookTitle, chapter,
                AudioTree.chapterTitle(c, bookId, chapter))
            val ok = WavMerger.merge(parts = parts, out = wav, gapMs = gapMs, declickMs = declickMs)
            if (!ok || wav.length() <= 44) {
                wav.delete()
                File(wav.parentFile, wav.name + ".part").delete()
                return null
            }
            setBusy(bookId, chapter, "正在转码中")
            val m4a = AudioLibrary.chapterM4aFile(c, bookId, bookTitle, chapter,
                AudioTree.chapterTitle(c, bookId, chapter))
            // R414 · 真机 3 次实证：MERGE_DONE → CHAPTER_M4A_OK 之间 4~5 分钟
            // 零日志、零浮窗刷新，通知钉在旧进度上像卡死。转码是流式长循环，
            // 借 stopCheck 每轮回调做 30s 节流心跳；只记阶段/耗时/已转字节，
            // 不动 done/total，不碰 MediaCodec 主链。
            val t0 = System.currentTimeMillis()
            var lastPulse = t0
            EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_ENCODE_BEGIN",
                "ch=${chapter + 1} · ${parts.size} 块 · wav=${wav.length()} 字节 · 开始转 M4A")
            val pulseCheck: () -> Boolean = {
                val now = System.currentTimeMillis()
                if (now - lastPulse >= 30_000L) {
                    lastPulse = now
                    EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_ENCODE_PULSE",
                        "ch=${chapter + 1} · 已进行 ${(now - t0) / 1000}s · 还在转码，已做好的段都留着")
                }
                stopCheck?.invoke() == true
            }
            val conv = toM4a(c, wav, m4a, pulseCheck)
            EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_ENCODE_END",
                "ch=${chapter + 1} · 耗时 ${(System.currentTimeMillis() - t0) / 1000}s · ok=${conv.ok}")
            if (conv.ok && m4a.length() > 0L) {
                EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_M4A_OK",
                    "ch=${chapter + 1} · wav=${wav.length()} → m4a=${m4a.length()} 字节")
                return Encoded(m4a, wav, true)
            }
            m4a.delete()
            EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_M4A_FALLBACK",
                "ch=${chapter + 1} · 转码没过，内部 WAV 留着可手动另存")
            return Encoded(wav, wav, false)
        } finally {
            setBusy(bookId, chapter, null)
        }
    }

    /**
     * R346 · 章一旦固化（chapter_done.json 记上），这一章残留的「段要重做」标记必须一起清掉：
     * 否则后续章收尾会把旧小块又合成一遍，主循环还卡在旧章不往前走。
     * R361 · 小块按设置留着时同样清标记：章已固化就不再插队重做，与文件在不在无关。
     * 只按小块文件名认段号，不碰别的章、别的状态；一次批量写盘。
     * 手动「拼接并转码」与自动章边界两条路径都走这里，口径唯一。
     */
    private fun clearRedoOfParts(parts: List<File>) {
        val work = parts.firstOrNull()?.parentFile ?: return
        val idxs = parts.mapNotNull {
            it.name.removePrefix("direct_").removeSuffix(".wav").toIntOrNull()
        }
        if (idxs.isNotEmpty()) SegPlan.clearSegs(work, idxs)
    }

    fun mergeChapter(c: Context, bookId: String, bookTitle: String, chapter: Int,
                     parts: List<File>, gapMs: Int = 0, declickMs: Int = 30): Result {
        if (parts.isEmpty()) return Result(false, reason = "这一章还没有音频小块")
        val enc = encodeChapter(c, bookId, bookTitle, chapter, parts, gapMs, declickMs)
            ?: return Result(false, reason = "拼接没成功，原来的小块都留着")
        AudioLibrary.register(c, bookId, bookTitle, setOf(chapter), true,
            enc.file, chapter + 1)
        if (!AudioLibrary.lastRegisterOk) {
            return Result(true, enc.file, kept = parts.size,
                reason = "整章已拼好，但成品登记没成功，文件仍在「我的音频」里；原来的小块先留着")
        }
        val marked = parts.firstOrNull()?.parentFile
            ?.let { AudioTree.markDone(it, chapter, enc.file) } ?: false
        if (!marked) {
            return Result(true, enc.file, kept = parts.size,
                reason = "整章已拼好，但章状态没记上，原来的小块先留着")
        }
        if (enc.converted) enc.wav.delete()
        val killedPaths = parts.map { it.absolutePath }
        // R361 · 新规则优先：小块删不删只认设置开关，不再写死「固化即删」。
        // 关＝文件与显示名都留着，去设置页「音频文件」里导出或删除；
        // 章状态、内部 WAV、重做标记仍按老口径处理，续跑不会回头。
        val autoDel = Prefs.autoDeleteSegsAfterMerge(c)
        val kept = if (autoDel) deleteAll(parts) else parts.size
        if (autoDel) AudioTree.forget(c, killedPaths)
        // R346 · 固化即清标记：目录页手动拼章这条路径以前不清，后续章收尾
        // 会把刚删掉的小块又合成一遍（孤儿 WAV），任务卡在旧章不往前走。
        clearRedoOfParts(parts)
        return Result(true, enc.file, kept = kept)
    }

    /**
     * R231 · 整本合成收尾时，给「这一章」单独出一份成品音频。
     * R361 · 新规则优先：整章拼好后，中间小块删不删只看设置开关（默认删）。
     * 旧口径「小块必删」不再并存；关开关时小块与显示名原样留着。
     * R308 · 成品改为 M4A：拼完即转码、校验通过才删内部 WAV（与小块两回事）；
     * 转码失败保留 WAV 当手动另存兜底，章名蒙层显示拼接/转码阶段。
     * 顺序有讲究——先写 chapter_done 再动小块：Producer 续跑靠 doneChapterFiles
     * 走 covered 跳过，不写就删会让整章重做。markDone 失败就保留小块并说实话。
     */
    fun mergeChapterAuto(c: Context, bookId: String, bookTitle: String, chapter: Int,
                         parts: List<File>, gapMs: Int = 0, declickMs: Int = 30): Result {
        if (parts.isEmpty()) return Result(false, reason = "这一章还没有音频小块")
        val enc = encodeChapter(c, bookId, bookTitle, chapter, parts, gapMs, declickMs,
            stopCheck = { Producer.isStoppingBook(bookId) })
            ?: return Result(false, reason = "这一章拼接没成功，小块都留着")
        // R368 · 真机日志：00:12:17 章边界开始拼、转码跑了四分钟，00:16:14 用户
        // 点停止，00:16:18 仍 CHAPTER_M4A_OK → CHAPTER_MERGE_ONE → CLEAN 清掉 55
        // 个中间小块。tryMergeChapter 进入前的 cancelRequested 守卫拦不住「已经在
        // 转码途中的章」。这里在写章状态之前再认一次停止：不登记、不 markDone、
        // 不删小块；刚生成但未登记的整章文件也一并清掉，免得 AudioLibrary 兜底
        // 扫描把它登记成成品、与 chapter_done 分家。小块原样留着，目录页仍可
        // 亲手点「拼接并转码」，与 R343 中间态口径一致。
        if (Producer.isStoppingBook(bookId)) {
            runCatching { if (enc.converted) enc.file.delete() }
            runCatching { enc.wav.delete() }
            EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_HOLD",
                "ch=${chapter + 1} · 已停止，章成品不登记、小块原样留着等手动拼接并转码")
            return Result(false, reason = "已停止，这一章的小块都留着，可在目录页点「拼接并转码」")
        }
        AudioLibrary.register(c, bookId, bookTitle, only = setOf(chapter),
            direct = true, file = enc.file, allChapterCount = chapter + 1)
        // R369 · 与手动路径同序：登记成功才写章状态。旧写法先 markDone 再查
        // lastRegisterOk，登记失败时章状态已固化，成品却没进「我的音频」，
        // 续跑 covered 跳过、补拼也跳过 —— 这章音频不见了却显示已完成。
        if (!AudioLibrary.lastRegisterOk) {
            return Result(true, enc.file, kept = parts.size,
                reason = "整章已拼好，但成品登记没成功，章状态与小块先留着")
        }
        EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_ONE",
            "ch=${chapter + 1} · ${parts.size} 块 · ${enc.file.length()} 字节")
        val work = parts.firstOrNull()?.parentFile
        val marked = work?.let { AudioTree.markDone(it, chapter, enc.file) } ?: false
        if (!marked) {
            return Result(true, enc.file, kept = parts.size,
                reason = "整章已拼好，但章状态没记上，中间小块先留着")
        }
        if (enc.converted) enc.wav.delete()
        val killedPaths = parts.map { it.absolutePath }
        // R361 · 同手动路径：小块删不删只认设置开关；章状态先写、标记照旧清。
        val autoDel = Prefs.autoDeleteSegsAfterMerge(c)
        val kept = if (autoDel) deleteAll(parts) else parts.size
        if (autoDel) AudioTree.forget(c, killedPaths)
        val tail = if (enc.converted) " · 内部 WAV 已删" else " · 转码没过，WAV 留着可手动另存"
        EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_CLEAN",
            if (autoDel) "ch=${chapter + 1} · 清掉 ${killedPaths.size - kept} 个中间小块" + tail
            else "ch=${chapter + 1} · 按设置保留 ${killedPaths.size} 个中间小块" + tail)
        // R346 · 与手动拼章同口径：章固化了就不留段级重做标记。
        clearRedoOfParts(parts)
        // R369 · 与手动路径同口径：kept 是「删失败、实际留着的份数」。
        // 旧写法恒返回 parts.size，开关开着且全删成功也说成留了一堆。
        return Result(true, enc.file, kept = kept)
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
        // R330 · 口径：开关开着时，章边界即时拼（R300），收尾只补边界漏网的章；
        // 开关关着时，章边界与收尾都不拼，小块原样留着给用户先重做坏块，
        // 再由目录页「拼接并转码」手动固化。force 只留给真正的强制补漏调用。
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

    fun deleteMerged(c: Context, file: File): Boolean =
        AudioLibrary.removeFile(c, file)

    fun deleteAll(files: List<File>): Int {
        var failed = 0
        files.forEach { f ->
            if (f.isFile && !f.delete()) failed++
        }
        return failed
    }

    /**
     * R377 · 网关 / 跨网中继「回传前压缩」：WAV 字节 → AAC-LC / 64kbps / 单声道 M4A 字节。
     *
     * 复用上面已在整章链验证过的 toM4a（平台自带 MediaCodec + MediaMuxer，零第三方依赖），
     * 不引 LAME / FFmpeg，不动 native 构建链。查证结论：Android 全版本没有原生 MP3 编码器，
     * MediaCodec 与 Media3 Transformer 都只出 AAC，所以这里出 M4A；体积约 WAV 的 1/10，
     * 与用户要的「别传大 WAV」一致。转码失败**原样回退 WAV 并如实标注 format**，
     * 绝不把没压过的文件谎称压缩过。
     */
    fun compressForTransfer(c: Context, wav: ByteArray, tag: String): Pair<ByteArray, String> {
        if (wav.size <= 44) return Pair(wav, "wav")
        return try {
            val d = File(c.cacheDir, "xfer").apply { mkdirs() }
            val src = File(d, "x_" + tag + ".wav")
            val out = File(d, "x_" + tag + ".m4a")
            src.writeBytes(wav)
            val r = toM4a(c, src, out)
            val b = if (r.ok && out.isFile && out.length() > 1024L) out.readBytes() else wav
            src.delete(); out.delete()
            if (b === wav) Pair(wav, "wav") else Pair(b, "m4a")
        } catch (_: Throwable) {
            Pair(wav, "wav")
        }
    }
}
