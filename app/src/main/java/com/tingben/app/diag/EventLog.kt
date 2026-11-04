package com.tingben.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * L2/L3 工程事件，只落盘、不进主界面。
 * native 侧通过 FindClass 调 nativeNote（JNI 名字已随包名改过）。
 */
object EventLog {
    enum class Kind { UI, ENGINE, PIPELINE, MODEL, CRASH }

    @Volatile private var file: File? = null
    @Volatile private var crashFile: File? = null
    @Volatile private var lastEngineAction: String = ""
    @Volatile private var lastEngineDetail: String = ""
    @Volatile private var lastEngineAt: Long = 0L

    /** 给进度页做心跳：native 每 32 token 写一条，UI 每秒读一次，不用反复读文件。 */
    fun lastEngine(): Pair<String, String> = lastEngineAction to lastEngineDetail
    fun lastEngineAgeMs(): Long = System.currentTimeMillis() - lastEngineAt
    fun resetEngine() {
        lastEngineAction = ""; lastEngineDetail = ""; lastEngineAt = System.currentTimeMillis()
    }

    fun init(c: Context) {
        file = File(c.filesDir, "events.log")
        crashFile = File(c.filesDir, "crash.log")
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
                val recent = try { read().lines().takeLast(25).joinToString("\n") } catch (_: Throwable) { "" }
                crashFile?.writeText(
                    "$ts | thread=${thread.name}\n$stack\n=== 崩溃前事件 ===\n$recent\n")
                log(Kind.CRASH, "JAVA_CRASH", throwable.javaClass.simpleName + ": " + (throwable.message ?: ""))
            } catch (_: Throwable) { }
            // 不吞掉：交回系统，让崩溃按正常流程结束进程
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrash(): String = try {
        val f = crashFile
        if (f != null && f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

    fun clearCrash() { try { crashFile?.delete() } catch (_: Throwable) { } }

    @Synchronized
    fun log(kind: Kind, action: String, detail: String = "") {
        if (kind == Kind.ENGINE) {
            lastEngineAction = action
            lastEngineDetail = detail
            lastEngineAt = System.currentTimeMillis()
        }
        val f = file ?: return
        try {
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$ts | $kind | $action | $detail\n")
            if (f.length() > 512 * 1024) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(500).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) { }
    }

    fun read(): String = try { file?.readText() ?: "" } catch (_: Throwable) { "" }

    /** JNI 回调入口，必须 @JvmStatic + 三个 String 参数。 */
    @JvmStatic
    fun nativeNote(kind: String, action: String, detail: String) {
        val k = try { Kind.valueOf(kind) } catch (_: Throwable) { Kind.ENGINE }
        log(k, action, detail)
    }
}
