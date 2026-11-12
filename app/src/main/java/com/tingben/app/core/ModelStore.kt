package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import java.io.File

// @core-part: ModelStore
object ModelStore {
    const val TOKENIZER = "qwen-tokenizer-12hz-Q8_0.gguf"

    enum class Mode(
        val id: String, val label: String, val file: String,
        val key: String, val hint: String
    ) {
        VOICE_DESIGN("voice_design", "描述造音色",
            "qwen-talker-1.7b-voicedesign-Q8_0.gguf", "voicedesign",
            "用一段文字描述想要的嗓音，可叠加速度、情绪等可调项。"),
        CUSTOM_VOICE("custom_voice", "预设音色",
            "qwen-talker-1.7b-customvoice-Q8_0.gguf", "customvoice",
            "从模型自带的音色里挑一个，再给它一句念法要求。"),
        BASE("base", "参考音频",
            "qwen-talker-1.7b-base-Q8_0.gguf", "base",
            "给一小段人声做样本，照着这个声音来念；不再用文字描述。")
    }

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }
    fun files(c: Context): List<File> = try {
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".gguf") }?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /**
     * R175 · P3-9「模型导入零完整性校验」根治。
     * 原来 import() 只判「文件非空」，pick/pickFor 又靠文件名子串认主文件：
     * 下载残件、误选的小文件、改名后的错模型都能通过 ready() 一路传进 native，
     * 轻则加载失败重则进程崩溃，UI 却显示「已导入 ✓」。
     * 现在文件头必须是 GGUF 魔数且不小于 64KB；精确名与模糊匹配一律只认
     * 通过校验的文件。校验发生在 rename 之前，残件进不了 models/。
     */
    private const val GGUF_MAGIC = "GGUF"
    private const val MIN_GGUF_BYTES = 64L * 1024L

    private fun looksLikeGguf(f: File): Boolean = try {
        if (!f.isFile || f.length() < MIN_GGUF_BYTES) false
        else java.io.RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(4)
            raf.readFully(h)
            String(h, Charsets.US_ASCII) == GGUF_MAGIC
        }
    } catch (_: Throwable) { false }

    private fun validFiles(c: Context): List<File> = files(c).filter { looksLikeGguf(it) }

    fun import(c: Context, name: String, source: java.io.InputStream,
               onProgress: (Long) -> Unit = {}): File {
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val tmp = File(dir(c), "$safe.part")
        val out = File(dir(c), safe)
        try {
            tmp.outputStream().buffered(64 * 1024).use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    total += n
                    if (total - lastReport >= 8L * 1024 * 1024) {
                        lastReport = total
                        onProgress(total)
                    }
                }
                o.flush()
                onProgress(total)
            }
            if (tmp.length() == 0L) throw IllegalStateException("选到的文件是空的")
            // R175 · 残件在 rename 成正式文件之前就被挡下，旧文件与 .bak 一个字不动
            if (!looksLikeGguf(tmp))
                throw IllegalStateException(
                    "这个文件不像完整的 GGUF 模型（文件头不对或下载不完整），重新下载后再导入")
            val bak = File(dir(c), "$safe.bak")
            if (bak.exists()) bak.delete()
            if (out.exists() && !out.renameTo(bak))
                throw IllegalStateException("旧文件挪不开，原文件留着")
            if (!tmp.renameTo(out)) {
                if (bak.exists()) bak.renameTo(out)
                throw IllegalStateException("文件没能保存，原文件已放回")
            }
            bak.delete()
            EventLog.log(EventLog.Kind.MODEL, "MODEL_IMPORT",
                "${EventLog.safe(name)} · ${out.length()} 字节")
            return out
        } catch (t: Throwable) {
            tmp.delete()
            val reason = t.message ?: ""
            EventLog.log(EventLog.Kind.CRASH, "MODEL_IMPORT_FAIL",
                "${EventLog.safe(name)} · ${t.javaClass.simpleName} · " +
                    "msg=${EventLog.safe(reason)}")
            throw t
        }
    }

    fun delete(c: Context, f: File) { f.delete(); EventLog.log(EventLog.Kind.MODEL, "MODEL_DELETE", EventLog.safe(f.name)) }

    fun cleanup(c: Context) {
        try {
            dir(c).listFiles()?.filter { it.isFile &&
                (it.name.endsWith(".part") || it.name.endsWith(".bak")) }?.forEach {
                EventLog.log(EventLog.Kind.MODEL, "PART_DISCARD",
                    "kind=${it.name.substringAfterLast('.')} · ${EventLog.safe(it.name)} · ${it.length()}")
                it.delete()
            }
            // R175 · 疑似残件不自动删（不替用户做破坏性决定），但明确记账
            files(c).filter { !looksLikeGguf(it) }.forEach {
                EventLog.log(EventLog.Kind.MODEL, "MODEL_SUSPECT",
                    "${EventLog.safe(it.name)} ${it.length()} 字节 · 头不对或过小，已排除在可用模型之外")
            }
        } catch (_: Throwable) { }
    }

    private fun pick(c: Context, exact: String, vararg hints: String): File? {
        val all = validFiles(c)
        all.find { it.name.equals(exact, true) }?.let { return it }
        return all.firstOrNull { f -> hints.any { f.name.contains(it, true) } }
    }

    private fun pickFor(c: Context, m: Mode): File? {
        val all = validFiles(c)
        all.find { it.name.equals(m.file, true) }?.let { return it }
        return all.firstOrNull { it.name.contains("-" + m.key + "-", true) }
    }

    fun modeFile(c: Context, m: Mode) = pickFor(c, m)
    fun modeFiles(c: Context): Map<Mode, File?> = Mode.values().associateWith { pickFor(c, it) }
    fun installedModes(c: Context): List<Mode> = Mode.values().filter { pickFor(c, it) != null }
    fun currentMode(c: Context): Mode =
        Mode.values().firstOrNull { it.id == Prefs.modelMode(c) } ?: Mode.VOICE_DESIGN

    fun talker(c: Context): File? = pickFor(c, currentMode(c)) ?: pickFor(c, Mode.VOICE_DESIGN)
    fun tokenizer(c: Context) = pick(c, TOKENIZER, "tokenizer", "12hz")

    fun synthesisBlockReason(c: Context): String? {
        // R291 · 底座是 native 必需的第二个文件，缺了会直接打不开；
        // 原先只查当前模式主文件，会让「只差底座」时按钮提前可用。
        if (tokenizer(c) == null)
            return "共用底座（12Hz）还没导入。到「模型」页导入再合成。"
        val m = currentMode(c)
        if (modeFile(c, m) == null)
            return "「${m.label}」的模式主文件还没导入。到「模型」页导入，或切回「描述造音色」。"
        return when (m) {
            Mode.VOICE_DESIGN -> null
            Mode.CUSTOM_VOICE -> {
                val picked = Prefs.speakerName(c).ifBlank {
                    Prefs.speakerNames(c).firstOrNull()?.also { Prefs.setSpeakerName(c, it) }.orEmpty()
                }
                if (picked.isBlank())
                    "先在调音台点「读取模型自带的音色表」挑一个音色，再合成。" else null
            }
            Mode.BASE ->
                if (!VoiceRef.exists(c)) "先在调音台导入一小段参考音频，再合成。" else null
        }
    }

    /**
     * R291 · 用户实证：新装清干净后实际要导入 4 份（共用底座 + 3 个模式主文件），
     * 首页却只显示「还差 2 个」——旧实现把 3 个模式合并成一个笼统的「模式主文件」，
     * 计数和清单都不反映真实文件数。
     * 现在按文件逐项列：缺底座算 1 份，每个没装的模式各算 1 份，最多 4 份。
     * 注意「能不能合成」另由 synthesisBlockReason 判（底座 + 当前模式齐即可），
     * 所以这里列全 4 份不会拦住只装够当前模式就开跑的用户。
     */
    fun missing(c: Context): List<String> {
        val m = mutableListOf<String>()
        if (tokenizer(c) == null) m.add("共用底座（12Hz）")
        val have = installedModes(c).toSet()
        Mode.values().filter { it !in have }.forEach { m.add("「${it.label}」主文件") }
        return m
    }
    fun ready(c: Context) = missing(c).isEmpty()
}

