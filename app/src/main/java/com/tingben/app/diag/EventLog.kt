package com.tingben.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

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

    fun lastEngine(): Pair<String, String> = lastEngineAction to lastEngineDetail
    fun lastEngineAgeMs(): Long = System.currentTimeMillis() - lastEngineAt
    fun resetEngine() {
        lastEngineAction = ""; lastEngineDetail = ""; lastEngineAt = System.currentTimeMillis()
    }

    fun init(c: Context) {
        file = File(c.filesDir, "events.log")
        detailFile = File(c.filesDir, "events_detail.log")
        crashFile = File(c.filesDir, "crash.log")
        try {
            val old = file?.readLines()?.takeLast(RING_MAX).orEmpty()
            ring.clear(); old.forEach { ring.addLast(it) }
        } catch (_: Throwable) { }
        installCrashHandler(c)
        log(Kind.UI, "APP_START", "version=" + c.packageManager.getPackageInfo(c.packageName, 0).versionName)
    }

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
                crashFile?.writeText(
                    "$ts | thread=${thread.name}\n$stack\n=== 崩溃前事件（环形缓冲）===\n$ringTail\n")
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
        appendCapped(file, mainLine, 768 * 1024L, 800)
        appendCapped(detailFile, detailLine, 1536 * 1024L, 2000)
    }

    fun read(): String = try { file?.readText() ?: "" } catch (_: Throwable) { "" }
    fun readDetail(): String = try { detailFile?.readText() ?: "" } catch (_: Throwable) { "" }
    fun ringTail(n: Int = 60): String = try {
        ring.toList().takeLast(n).joinToString("\n")
    } catch (_: Throwable) { "" }

    @JvmStatic
    fun nativeNote(kind: String, action: String, detail: String) {
        val k = try { Kind.valueOf(kind) } catch (_: Throwable) { Kind.ENGINE }
        val lv = if (action.startsWith("NATIVE_DECODE_STEP") || action.startsWith("NATIVE_PREFILL") ||
            action.startsWith("ANN_WAIT")) Level.TRACE else Level.INFO
        log(k, action, detail, level = lv)
    }
}
