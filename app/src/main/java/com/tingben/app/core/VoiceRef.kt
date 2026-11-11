package com.tingben.app.core

import android.content.Context
import android.net.Uri
import com.tingben.app.diag.EventLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VoiceRef {
    private const val NAME = "voice_ref.wav"
    private const val RATE = 24000
    private const val MAX_SECONDS = 30

    fun file(c: Context): File = File(c.filesDir, NAME)
    fun exists(c: Context): Boolean = file(c).isFile && file(c).length() > 44

    /**
     * R234 · 从系统文件选择器进来的任意格式（mp3 / m4a / aac / ogg / wav）
     * 先过平台内置解码底座，再复用 save() 的全部校验与原子落盘。
     * startMs / endMs 留给波形裁剪 UI 拖选；这一批先全段导入。
     */
    fun saveFromUri(c: Context, uri: Uri, startMs: Long = 0, endMs: Long = -1): DecodedAudio {
        val d = AudioDecode.decode(c, uri, startMs, endMs)
        save(c, d.wav)
        return d
    }

    fun save(c: Context, bytes: ByteArray) {
        if (bytes.size < 44) throw IllegalStateException("这个文件太短，不像一段 WAV 录音")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE")
            throw IllegalStateException("不是标准 WAV 文件，请选一段录音的 WAV")
        var off = 12
        var fmtOff = -1
        var dataOff = -1
        var dataLen = 0
        while (off + 8 <= bytes.size) {
            val len = b.getInt(off + 4)
            if (len < 0 || off + 8 + len > bytes.size) break
            if (tag(off) == "fmt ") fmtOff = off + 8
            if (tag(off) == "data") { dataOff = off + 8; dataLen = len; break }
            off += 8 + len + (len and 1)
        }
        if (fmtOff < 0 || dataOff < 0 || dataLen < 2)
            throw IllegalStateException("WAV 里没找到可用的声音数据")
        val format = b.getShort(fmtOff).toInt() and 0xffff
        val channels = b.getShort(fmtOff + 2).toInt() and 0xffff
        val rate = b.getInt(fmtOff + 4)
        val bits = b.getShort(fmtOff + 14).toInt() and 0xffff
        if (format != 1 && format != 3)
            throw IllegalStateException("只收未压缩 WAV，这份是压缩格式（format=$format）")
        if (channels !in 1..8 || rate !in 8000..192000 || bits !in setOf(8, 16, 24, 32))
            throw IllegalStateException("这份 WAV 的参数读不懂（$channels 声道 / ${rate}Hz / ${bits}-bit）")
        if (format == 3 && bits != 32)
            throw IllegalStateException("浮点 WAV 只认 32-bit")
        val bytesPerSample = bits / 8
        val frameBytes = bytesPerSample * channels
        val frames = minOf(dataLen, bytes.size - dataOff) / frameBytes
        if (frames < rate / 2) throw IllegalStateException("录音太短，至少要半秒")
        val mono = FloatArray(frames)
        var p = dataOff
        for (i in 0 until frames) {
            var acc = 0f
            for (ch in 0 until channels) {
                val q = p + ch * bytesPerSample
                val v = when {
                    format == 3 -> b.getFloat(q)
                    bits == 8 -> ((bytes[q].toInt() and 0xff) - 128) / 128f
                    bits == 16 -> b.getShort(q).toFloat() / 32768f
                    bits == 24 -> {
                        val raw = (bytes[q].toInt() and 0xff) or
                            ((bytes[q + 1].toInt() and 0xff) shl 8) or
                            ((bytes[q + 2].toInt() and 0xff) shl 16)
                        val signed = if ((raw and 0x800000) != 0)
                            (raw or 0xFF000000.toInt()) else raw
                        signed.toFloat() / 8388608f
                    }
                    else -> b.getInt(q).toFloat() / 2147483648f
                }
                acc += v
            }
            mono[i] = (acc / channels).coerceIn(-1f, 1f)
            p += frameBytes
        }
        val target = (frames.toLong() * RATE / rate).toInt()
        if (target > RATE * MAX_SECONDS)
            throw IllegalStateException("参考音频最长 ${MAX_SECONDS} 秒，这段约 ${target / RATE} 秒")
        val outPcm = FloatArray(target)
        for (i in 0 until target) {
            val src = i.toDouble() * rate / RATE
            val i0 = src.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val f = (src - i0).toFloat()
            outPcm[i] = mono[i0] * (1f - f) + mono[i1] * f
        }
        val outBytes = ByteArray(44 + outPcm.size * 2)
        fun putStr(o: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(outBytes, o) }
        fun putInt(o: Int, v: Int) {
            outBytes[o] = (v and 0xff).toByte(); outBytes[o + 1] = ((v shr 8) and 0xff).toByte()
            outBytes[o + 2] = ((v shr 16) and 0xff).toByte(); outBytes[o + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(o: Int, v: Int) {
            outBytes[o] = (v and 0xff).toByte(); outBytes[o + 1] = ((v shr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + outPcm.size * 2); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, RATE); putInt(28, RATE * 2); putShort(32, 2); putShort(34, 16)
        putStr(36, "data"); putInt(40, outPcm.size * 2)
        val ob = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in outPcm.indices) ob.putShort(44 + i * 2, (outPcm[i] * 32767f).toInt().toShort())
        val dst = file(c)
        val part = File(c.filesDir, NAME + ".part")
        try {
            part.writeBytes(outBytes)
            if (!part.renameTo(dst))
                throw IllegalStateException("参考音频没能保存，原来的还在")
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        EventLog.log(EventLog.Kind.MODEL, "VOICE_REF_SAVED",
            "$rate Hz / $channels 声道 / ${bits}-bit → 24kHz 单声道 · ${outPcm.size} samples")
    }

    fun pcm(c: Context): FloatArray {
        if (!exists(c)) throw IllegalStateException("还没导入参考音频")
        val bytes = file(c).readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var off = 36
        var dataOff = -1
        var dataLen = 0
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4, Charsets.US_ASCII)
            val len = b.getInt(off + 4)
            if (id == "data") { dataOff = off + 8; dataLen = len; break }
            if (len < 0 || off + 8 + len > bytes.size) break
            off += 8 + len + (len and 1)
        }
        if (dataOff < 0 || dataLen < 2) throw IllegalStateException("参考音频已损坏，请重新导入")
        val n = minOf(dataLen / 2, (bytes.size - dataOff) / 2)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = b.getShort(dataOff + i * 2).toFloat() / 32768f
        return out
    }

    fun fingerprint(c: Context): String {
        if (!exists(c)) return "none"
        val f = file(c)
        val head = try {
            f.inputStream().use { s ->
                val b = ByteArray(4096)
                val n = s.read(b)
                if (n > 0) sha(String(b, 0, n, Charsets.ISO_8859_1)) else ""
            }
        } catch (_: Throwable) { "" }
        return sha(f.name + "|" + f.length() + "|" + f.lastModified() +
            "|" + head + "|" + Prefs.refText(c))
    }

    fun status(c: Context): String =
        if (!exists(c)) "还没导入参考音频" else
            "已导入 · ${"%.1f".format((file(c).length() - 44).coerceAtLeast(0) / 2f / RATE)} 秒"

    fun clear(c: Context) {
        file(c).delete()
        File(c.filesDir, NAME + ".part").delete()
        Prefs.setRefText(c, "")
        EventLog.log(EventLog.Kind.MODEL, "VOICE_REF_CLEARED", "参考音频已删除")
    }
}
