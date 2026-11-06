package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R93 · base 模式的参考音频。qwen.h:294-301 只收 mono float PCM @24kHz，
 * 这里收与之对应的 24kHz / 单声道 / 16-bit PCM WAV，解成 FloatArray 交给 JNI。
 *
 * 边界：不做重采样、不做格式猜测。用户给的文件不对就明确说清要什么格式，
 * 不静默改采样率 —— 采样率错了克隆出来会变调，比直接失败更难查。
 * 上限 30 秒，避免把整本书的录音塞进内存。
 */
object VoiceRef {
    private const val NAME = "voice_ref.wav"
    private const val RATE = 24000
    private const val MAX_SECONDS = 30

    fun file(c: Context): File = File(c.filesDir, NAME)
    fun exists(c: Context): Boolean = file(c).isFile && file(c).length() > 44

    /** 校验并落盘。失败抛 IllegalStateException，文案直接给用户看。 */
    fun save(c: Context, bytes: ByteArray) {
        if (bytes.size < 44) throw IllegalStateException("这个文件太短，不像一段 WAV 录音")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(12) != "fmt ")
            throw IllegalStateException("不是标准 WAV 文件，请导出 24kHz 单声道 16-bit WAV")
        val format = b.getShort(20).toInt() and 0xffff
        val channels = b.getShort(22).toInt() and 0xffff
        val rate = b.getInt(24)
        val bits = b.getShort(34).toInt() and 0xffff
        if (format != 1) throw IllegalStateException("只收未压缩 PCM WAV，这份是压缩格式（format=$format）")
        if (channels != 1) throw IllegalStateException("要单声道录音，这份是 $channels 声道")
        if (rate != RATE) throw IllegalStateException("要 ${RATE}Hz 录音，这份是 ${rate}Hz；请先转好再导入")
        if (bits != 16) throw IllegalStateException("要 16-bit 录音，这份是 ${bits}-bit")

        // 从 36 字节起按 chunk 找 data，跳过可能存在的 LIST / fact 块
        var off = 36
        var dataOff = -1
        var dataLen = 0
        while (off + 8 <= bytes.size) {
            val id = tag(off)
            val len = b.getInt(off + 4)
            if (id == "data") { dataOff = off + 8; dataLen = len; break }
            if (len < 0 || off + 8 + len > bytes.size) break
            off += 8 + len + (len and 1)
        }
        if (dataOff < 0 || dataLen < 2 || dataOff + dataLen > bytes.size)
            throw IllegalStateException("WAV 里没找到可用的 data 块")
        val n = dataLen / 2
        if (n > RATE * MAX_SECONDS)
            throw IllegalStateException("参考音频最长 ${MAX_SECONDS} 秒，这段约 ${n / RATE} 秒")

        val out = file(c)
        out.writeBytes(bytes.copyOfRange(0, minOf(bytes.size, dataOff + n * 2)))
        EventLog.log(EventLog.Kind.MODEL, "VOICE_REF_SAVED",
            "$n samples · ${"%.1f".format(n.toFloat() / RATE)} 秒 · ${out.length()} 字节")
    }

    /** 解成 [-1,1] float PCM；格式由 save() 保证，这里只做防御性兜底。 */
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

    /** 参考音频变了要作废旧 WAV，但模式选择本身不进 voiceStamp。 */
    fun fingerprint(c: Context): String =
        if (!exists(c)) "none" else sha(file(c).name + "|" + file(c).length() + "|" + Prefs.refText(c))

    /** 给用户看的一句话状态，不暴露字段名。 */
    fun status(c: Context): String =
        if (!exists(c)) "还没导入参考音频" else
            "已导入 · ${"%.1f".format((file(c).length() - 44).coerceAtLeast(0) / 2f / RATE)} 秒"

    fun clear(c: Context) {
        file(c).delete()
        Prefs.setRefText(c, "")
        EventLog.log(EventLog.Kind.MODEL, "VOICE_REF_CLEARED", "参考音频已删除")
    }
}
