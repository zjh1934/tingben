package com.tingben.app.core

import com.tingben.app.diag.EventLog
import java.io.File

// @core-part: WavMerger
object WavMerger {
    /**
     * R172 · P0「长书必崩」根治。
     *
     * 原来把整本书所有分段 PCM 一次性 readBytes() 进堆（6 小时 ≈ 1.04 GB，
     * declick 又逐段 copyOf 一份，峰值超 2 GB），走到「正在整理音频」必 OOM，
     * 前面几千段全白做。现在只读每个 WAV 的 chunk 头定位 data 段，按固定缓冲
     * 逐段流式写出；declick 只处理当前段的首尾帧，内存与整本长度无关。
     * 顺带修掉旧 pcmOf 用有符号 Int 拼 chunk 长度时高位为 1 会变负、
     * i 不前进甚至倒退的死循环（畸形 / 超大 WAV）。
     */
    private const val IO_BUF = 64 * 1024

    /** 返回 data chunk 的 (起始偏移, 字节数)；只扫 chunk 头，绝不把 PCM 读进内存。 */
    private fun dataSpan(f: File): Pair<Long, Long>? = try {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(12)
            raf.readFully(h)
            if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(h, 8, 4, Charsets.US_ASCII) != "WAVE") null
            else {
                val fileLen = raf.length()
                var pos = 12L
                var found: Pair<Long, Long>? = null
                while (found == null && pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val ch = ByteArray(8)
                    raf.readFully(ch)
                    val id = String(ch, 0, 4, Charsets.US_ASCII)
                    // 无符号 32 位：用 Long 拼，高位为 1 也不会变成负数
                    val sz = (ch[4].toLong() and 0xffL) or
                            ((ch[5].toLong() and 0xffL) shl 8) or
                            ((ch[6].toLong() and 0xffL) shl 16) or
                            ((ch[7].toLong() and 0xffL) shl 24)
                    val payload = pos + 8
                    if (payload + sz > fileLen) break
                    if (id == "data") {
                        found = payload to minOf(sz, fileLen - payload)
                    } else {
                        val pad = if (sz % 2L == 1L) 1L else 0L
                        val next = payload + sz + pad
                        if (next <= pos) break      // 畸形头：不许原地打转
                        pos = next
                    }
                }
                found
            }
        }
    } catch (_: Throwable) { null }

    /**
     * R231 · 借鉴同行（Merge Audio / MediaExtractor+MediaMuxer 无损拼接 / android_audio_mixer）：
     * 拼接前先把每段的采样率、声道、位深读出来对齐，不一致就不拼。
     * 我们的段全部由同一引擎产出，恒为 24000Hz / 单声道 / 16bit；
     * 任何一段偏离都说明混进了别的来源，硬接会变调或炸音，宁可失败留着小块。
     * 返回 Triple(rate, channels, bits)，读不出返回 null。
     */
    private fun fmtOf(f: File): Triple<Int, Int, Int>? = try {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(12)
            raf.readFully(h)
            if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(h, 8, 4, Charsets.US_ASCII) != "WAVE") null
            else {
                val fileLen = raf.length()
                var pos = 12L
                var found: Triple<Int, Int, Int>? = null
                while (found == null && pos + 8 <= fileLen) {
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
                        fun s16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
                        fun i32(o: Int) = (b[o].toLong() and 0xffL) or
                            ((b[o + 1].toLong() and 0xffL) shl 8) or
                            ((b[o + 2].toLong() and 0xffL) shl 16) or
                            ((b[o + 3].toLong() and 0xffL) shl 24)
                        found = Triple(i32(4).toInt(), s16(2), s16(14))
                    } else {
                        val pad = if (sz % 2L == 1L) 1L else 0L
                        val next = payload + sz + pad
                        if (next <= pos) break
                        pos = next
                    }
                }
                found
            }
        }
    } catch (_: Throwable) { null }

    private fun header(dataLen: Int): ByteArray {
        val rate = 24000; val ch = 1; val bits = 16
        val byteRate = rate * ch * bits / 8
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, off) }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte()
            h[off + 2] = ((v shr 16) and 0xff).toByte(); h[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) { h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte() }
        putStr(0, "RIFF"); putInt(4, 36 + dataLen); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, ch)
        putInt(24, rate); putInt(28, byteRate); putShort(32, ch * bits / 8); putShort(34, bits)
        putStr(36, "data"); putInt(40, dataLen)
        return h
    }

    /** 就地改一帧的增益，不再 copyOf 整段。 */
    private fun rampFrame(out: ByteArray, frame: Int, gain: Float) {
        val p = frame * 2
        if (p < 0 || p + 1 >= out.size) return
        val s = ((out[p].toInt() and 0xff) or
            ((out[p + 1].toInt() and 0xff) shl 8)).toShort().toInt()
        val v = (s * gain).toInt().coerceIn(-32768, 32767)
        out[p] = (v and 0xff).toByte()
        out[p + 1] = ((v shr 8) and 0xff).toByte()
    }

    private fun declickInPlace(pcm: ByteArray, ms: Int) {
        if (ms <= 0 || pcm.size < 4) return
        val frames = (24000 * ms / 1000).coerceIn(1, pcm.size / 2)
        for (j in 0 until frames) {
            val g = j.toFloat() / frames
            rampFrame(pcm, j, g)
            rampFrame(pcm, pcm.size / 2 - 1 - j, g)
        }
    }

    private fun copyRaw(raf: java.io.RandomAccessFile, offset: Long, len: Long,
                        os: java.io.OutputStream) {
        raf.seek(offset)
        val buf = ByteArray(IO_BUF)
        var left = len
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            os.write(buf, 0, n)
            left -= n
        }
    }

    /** 只把首尾各 frames 帧读进内存做 ramp，中间按缓冲直拷；峰值恒定几十 KB。 */
    private fun writePcm(raf: java.io.RandomAccessFile, offset: Long, len: Long,
                         os: java.io.OutputStream, declickMs: Int) {
        if (len <= 0) return
        val frames = if (declickMs <= 0) 0 else
            (24000 * declickMs / 1000).coerceIn(1, (len / 2).toInt().coerceAtLeast(1))
        if (frames == 0) { copyRaw(raf, offset, len, os); return }
        val edge = frames * 2
        if (len <= edge * 2L) {
            // 极短段：整段读进内存（单段最多几十秒，与整本长度无关）
            val one = ByteArray(len.toInt())
            raf.seek(offset); raf.readFully(one)
            declickInPlace(one, declickMs)
            os.write(one)
            return
        }
        val head = ByteArray(edge)
        raf.seek(offset); raf.readFully(head)
        for (j in 0 until frames) rampFrame(head, j, j.toFloat() / frames)
        os.write(head)
        copyRaw(raf, offset + edge, len - edge * 2L, os)
        val tail = ByteArray(edge)
        raf.seek(offset + len - edge); raf.readFully(tail)
        for (k in 0 until frames)
            rampFrame(tail, k, (frames - 1 - k).toFloat() / frames)
        os.write(tail)
    }

    fun merge(parts: List<File>, out: File, gapMs: Int = 0, declickMs: Int = 30): Boolean {
        val part = File(out.parentFile, out.name + ".part")
        return try {
            if (parts.isEmpty()) { part.delete(); return false }
            // ① 只扫头算总长；任何一段坏了就整体失败（等价于旧 chunks.size != parts.size）
            val spans = ArrayList<Pair<Long, Long>>(parts.size)
            val fmts = ArrayList<Triple<Int, Int, Int>>(parts.size)
            for (p in parts) {
                val sp = dataSpan(p)
                val fm = fmtOf(p)
                if (sp == null || sp.second <= 0 || fm == null) {
                    EventLog.log(EventLog.Kind.PIPELINE, "MERGE_FMT_UNREADABLE",
                        "有段读不出 WAV 头，整章不拼，小块都留着")
                    part.delete(); return false
                }
                spans.add(sp); fmts.add(fm)
            }
            // R231 · 格式必须彼此一致、且等于引擎输出；否则接缝会变调/炸音
            val want = Triple(24000, 1, 16)
            if (fmts.any { it != want }) {
                EventLog.log(EventLog.Kind.PIPELINE, "MERGE_FMT_MISMATCH",
                    "段格式不一致（rate/ch/bits），按同行无损拼接前提拒绝，小块都留着")
                part.delete(); return false
            }
            val gapBytes = ((gapMs.coerceIn(0, 5000) * 48) / 2) * 2
            var total = 0L
            spans.forEach { total += it.second }
            if (spans.size > 1) total += gapBytes.toLong() * (spans.size - 1)
            // WAV 的 RIFF / data 长度是 32 位有符号 Int，越界就明确拒绝，不写出坏头
            if (total <= 0L || total > Int.MAX_VALUE - 36L) {
                part.delete()
                EventLog.log(EventLog.Kind.PIPELINE, "MERGE_TOO_LARGE",
                    "成品 $total 字节超过单个 WAV 上限，先分章导出")
                return false
            }
            // ② 逐段流式写出，全书 PCM 从不同时驻留
            java.io.FileOutputStream(part, false).use { os ->
                os.write(header(total.toInt()))
                val zero = ByteArray(minOf(IO_BUF, gapBytes.coerceAtLeast(1)))
                spans.forEachIndexed { i, (offset, len) ->
                    if (i > 0 && gapBytes > 0) {
                        var left = gapBytes
                        while (left > 0) {
                            val n = minOf(zero.size, left)
                            os.write(zero, 0, n)
                            left -= n
                        }
                    }
                    java.io.RandomAccessFile(parts[i], "r").use { raf ->
                        writePcm(raf, offset, len, os, declickMs)
                    }
                }
                os.flush()
                os.fd.sync()
            }
            if (part.length() != 44L + total) { part.delete(); return false }
            if (out.exists() && !out.delete()) { part.delete(); return false }
            if (!part.renameTo(out)) { part.delete(); return false }
            EventLog.log(EventLog.Kind.PIPELINE, "MERGE_DONE",
                "${parts.size} 块 / $total 字节 · 段间静音 ${gapMs}ms")
            true
        } catch (_: Throwable) { part.delete(); false }
    }
}

