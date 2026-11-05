package com.tingben.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 移植自 Alexandria `generate_script.py` 第 293-303 行的 llm_responses.log：
 * 每一块、每一次 attempt 的模型原始产出**全文**落盘，带块号 / attempt / 长度 /
 * 预算 / 结束方式，事后可自证「模型到底标了没」，而不是只留头尾截取。
 *
 * 改写点：
 *  - 原实现是单个无限追加文件；这里按 run 分目录、只留最近 3 次 run，避免正文长期留存
 *  - 结束原因：Alexandria 拿 OpenAI finish_reason，我们只有产出字数与预算，
 *    用「是否被预算截断」近似，并在文件里明确标注是近似，不冒充服务端字段
 *  - 全文不进主事件流（会刷屏），事件流只写路径与计数；诊断页提供复制入口
 */
object RawDump {
    @Volatile private var runId = ""

    private fun dir(c: Context) = File(c.filesDir, "rawdump").apply { mkdirs() }

    fun begin(c: Context, id: String) {
        runId = id
        try {
            File(dir(c), "run-$id").mkdirs()
            val old = dir(c).listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("run-") }
                ?.sortedBy { it.name }.orEmpty()
            // 连同本次共保留 3 次 run
            old.drop(2).forEach { it.deleteRecursively() }
        } catch (_: Throwable) { }
    }

    fun write(c: Context, block: Int, attempt: Int, raw: String,
              promptChars: Int, budget: Int): String = try {
        val d = File(dir(c), "run-$runId").apply { mkdirs() }
        val f = File(d, "block_%04d_attempt%d.txt".format(block, attempt))
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        f.writeText(buildString {
            append("=== 听本 · 语言模型原始产出全文（未经任何清洗）===\n")
            append("时间 ").append(ts).append('\n')
            append("block=").append(block).append(" attempt=").append(attempt).append('\n')
            append("promptChars=").append(promptChars).append(" outChars=").append(raw.length)
                .append(" budget=").append(budget).append('\n')
            append("finish=").append(if (raw.length >= budget) "LENGTH" else "STOP")
                .append("（近似：按产出字数是否触顶判断，非服务端 finish_reason）\n")
            append("────────────────────────────\n")
            append(raw)
        })
        f.absolutePath
    } catch (_: Throwable) { "" }

    /** 诊断页用：最近一次 run 里最新的一块全文，没有就返回空串。 */
    fun latest(c: Context): String = try {
        val d = File(dir(c), "run-$runId")
        val f = d.listFiles()?.filter { it.isFile && it.name.startsWith("block_") }
            ?.maxByOrNull { it.name }
        if (f != null) f.readText() else ""
    } catch (_: Throwable) { "" }

    /**
     * R65 · 分段看台按块读「语言模型吐出来的原貌」。
     *
     * segmap 里的 block 是 0-based（SegPlan / Pipeline 的 i），而落盘文件名
     * 用的是 i + 1（1-based）。调用方必须传 block + 1，否则会取到上一块。
     * 一块常切成好几段，多段共享同一份原文是正常的；多次 attempt 会有多份，
     * 按 attempt 升序返回，由界面决定默认展示哪一份，不在这里替用户挑。
     * 只留最近 3 次 run，读不到就返回空列表，由界面给空态，不伪造内容。
     */
    fun forBlock(c: Context, block: Int): List<Pair<Int, String>> = try {
        val d = File(dir(c), "run-$runId")
        val prefix = "block_%04d_attempt".format(block)
        d.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".txt") }
            ?.mapNotNull { f ->
                val a = f.name.removePrefix(prefix).removeSuffix(".txt").toIntOrNull()
                if (a == null) null else a to f.readText()
            }
            ?.sortedBy { it.first }
            .orEmpty()
    } catch (_: Throwable) { emptyList() }

    fun runDir(c: Context): String = try {
        File(dir(c), "run-$runId").absolutePath
    } catch (_: Throwable) { "" }
}
