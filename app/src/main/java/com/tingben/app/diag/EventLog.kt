package com.tingben.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * R21 · 日志模块重设计。
 *
 * 参考用户贴过的「看得清」诊断日志思路：静态快照（设置页拼）+ 分类事件流（最新在上）
 * + 一键复制；但不照抄，按听本实际改成两级：
 *   events.log        用户态：所有事件都记，行格式保持 ts | KIND | ACTION | detail 不变
 *   events_detail.log 诊断态：同一条事件额外带 level 与结构化字段，含产出截取片段
 * 另留 200 行环形缓冲，Java 层崩溃时连同最后 10 个块摘要一起写进 crash.log。
 * native 侧仍通过 nativeNote(kind, action, detail) 回调，签名不变。
 *
 * 分级只用于「复制摘要」时过滤，不删事件：用户要详细，就都留着。
 */
object EventLog {
    enum class Kind { UI, ENGINE, PIPELINE, MODEL, CRASH, AUDIT }
    enum class Level { INFO, WARN, ERROR, TRACE }

    @Volatile private var file: File? = null
    @Volatile private var detailFile: File? = null
    @Volatile private var crashFile: File? = null
    @Volatile private var lastEngineAction: String = ""
    @Volatile private var lastEngineDetail: String = ""
    @Volatile private var lastEngineAt: Long = 0L

    private val ring = ArrayDeque<String>()
    private const val RING_MAX = 200

    /** 给进度页做心跳：native 每 8 token 写一条，UI 每秒读一次，不用反复读文件。 */
    fun lastEngine(): Pair<String, String> = lastEngineAction to lastEngineDetail
    fun lastEngineAgeMs(): Long = System.currentTimeMillis() - lastEngineAt
    fun resetEngine() {
        lastEngineAction = ""; lastEngineDetail = ""; lastEngineAt = System.currentTimeMillis()
    }

    fun init(c: Context) {
        file = File(c.filesDir, "events.log")
        detailFile = File(c.filesDir, "events_detail.log")
        crashFile = File(c.filesDir, "crash.log")
        // 恢复上一次进程留下的环形尾部，被杀后重开也能接着看
        try {
            val old = file?.readLines()?.takeLast(RING_MAX).orEmpty()
            ring.clear(); old.forEach { ring.addLast(it) }
        } catch (_: Throwable) { }
        installCrashHandler(c)
        log(Kind.UI, "APP_START", "version=" + c.packageManager.getPackageInfo(c.packageName, 0).versionName)
    }

    /** Java 层崩溃一定留痕；native SIGSEGV 抓不到，但事件流会停在最后一步。 */
    private fun installCrashHandler(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val stack = StringBuilder()
                stack.append(throwable.javaClass.name).append(": ").append(throwable.message ?: "").append('\n')
                throwable.stackTrace.take(25).forEach { stack.append("  at ").append(it).append('\n') }
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth++ < 3) {
                    stack.append("Caused by: ").append(cause.javaClass.name)
                        .append(": ").append(cause.message ?: "").append('\n')
                    cause.stackTrace.take(8).forEach { stack.append("  at ").append(it).append('\n') }
                    cause = cause.cause
                }
                val ringTail = try { ring.joinToString("\n") } catch (_: Throwable) { "" }
                val blockTail = try { AuditLog.crashTail() } catch (_: Throwable) { "" }
                crashFile?.writeText(
                    "$ts | thread=${thread.name}\n$stack\n=== 崩溃前事件（环形缓冲）===\n$ringTail\n" +
                        "=== 最后 10 个分段块 ===\n$blockTail\n")
                log(Kind.CRASH, "JAVA_CRASH", throwable.javaClass.simpleName + ": " + (throwable.message ?: ""),
                    level = Level.ERROR)
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrash(): String = try {
        val f = crashFile
        if (f != null && f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

    fun clearCrash() { try { crashFile?.delete() } catch (_: Throwable) { } }

    private fun appendCapped(f: File?, line: String, maxBytes: Long, keepLines: Int) {
        val t = f ?: return
        try {
            t.appendText(line + "\n")
            if (t.length() > maxBytes) {
                val lines = t.readLines()
                t.writeText(lines.takeLast(keepLines).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) { }
    }

    /**
     * @param fields 结构化字段，既拼进人话 detail，也单独落进诊断态日志，便于逐块审计。
     */
    @Synchronized
    fun log(kind: Kind, action: String, detail: String = "",
            level: Level = Level.INFO, fields: Map<String, String> = emptyMap()) {
        if (kind == Kind.ENGINE) {
            lastEngineAction = action
            lastEngineDetail = detail
            lastEngineAt = System.currentTimeMillis()
        }
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val kv = if (fields.isEmpty()) "" else
            fields.entries.joinToString("") { " · ${it.key}=${it.value}" }
        val mainLine = "$ts | $kind | $action | $detail$kv"
        val detailLine = "$ts | $kind | $level | $action | $detail$kv"
        ring.addLast(mainLine)
        while (ring.size > RING_MAX) ring.removeFirst()
        // 主日志：全部保留（用户要详细），轮转上限比旧版放宽
        appendCapped(file, mainLine, 768 * 1024L, 800)
        // 诊断态：带 level + 字段，供「复制完整日志」使用
        appendCapped(detailFile, detailLine, 1536 * 1024L, 2000)
    }

    fun read(): String = try { file?.readText() ?: "" } catch (_: Throwable) { "" }
    fun readDetail(): String = try { detailFile?.readText() ?: "" } catch (_: Throwable) { "" }
    fun ringTail(n: Int = 60): String = try {
        ring.toList().takeLast(n).joinToString("\n")
    } catch (_: Throwable) { "" }

    /** JNI 回调入口，必须 @JvmStatic + 三个 String 参数。 */
    @JvmStatic
    fun nativeNote(kind: String, action: String, detail: String) {
        val k = try { Kind.valueOf(kind) } catch (_: Throwable) { Kind.ENGINE }
        val lv = if (action.startsWith("NATIVE_DECODE_STEP") || action.startsWith("NATIVE_PREFILL") ||
            action.startsWith("ANN_WAIT")) Level.TRACE else Level.INFO
        log(k, action, detail, level = lv)
    }
}
