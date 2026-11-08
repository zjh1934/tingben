package com.tingben.app.diag

import android.content.Context
import com.tingben.app.BuildConfig
import com.tingben.app.core.AudioLibrary
import com.tingben.app.core.BookStore
import com.tingben.app.core.ModelStore
import com.tingben.app.core.PlayQueue
import com.tingben.app.core.Prefs
import com.tingben.app.core.Producer
import com.tingben.app.core.TaskResume
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

    /**
     * R142 · 日志的定位订正：日志**不是给用户看的**，是给开发者排查用的。
     * 所以要「尽量多、尽量详细」，并且要能一键贴出来，而不是只存个 txt
     * 让用户自己决定发给谁。
     *
     * exportBundle：完整 Markdown（环境 + 运行快照 + 崩溃栈 + 环形缓冲 +
     * 事件日志全量 + 详细日志全量），走系统文件选择器存 .md。
     * exportSummary：环境 + 快照 + 最近 120 行事件，给「一键复制」用，
     * 控制体积，避免大文本把剪贴板塞爆。
     *
     * 隐私：TTS_INPUT / BOOK_IMPORT 等事件带书名与正文片段，文本头部明确提示。
     * 软件无 INTERNET 权限，日志不会自己离开设备。
     */
    private fun envBlock(c: Context): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("- 导出时间: `$ts`\n")
            append("- 版本: `${BuildConfig.VERSION_NAME}`\n")
            append("- 设备: `${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}`\n")
            append("- 系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            append("- ABI: `${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}`\n")
            append("- 可用空间: `${c.filesDir.usableSpace / 1024 / 1024} MB`\n")
        }
    }

    /** 运行快照：出问题时光看事件流不够，得知道当时软件处在什么状态。 */
    private fun snapshotBlock(c: Context): String {
        fun <T> safe(block: () -> T): String = try {
            block().toString()
        } catch (t: Throwable) { "不可用（" + (t.javaClass.simpleName) + "）" }
        return buildString {
            append("- 书架: `${safe { BookStore.list(c).size }}` 本\n")
            append("- 当前任务: `${safe {
                val t = TaskResume.active(c)
                if (t == null) "无" else "${t.title} · ${t.phase} · ${t.done}/${t.total} · 规则 ${t.splitRule}"
            }}`\n")
            append("- 队列: `${safe { TaskResume.queueSize(c) }}` 项 · 暂停=`${safe { TaskResume.queuePaused(c) }}`\n")
            append("- 成品音频: `${safe { AudioLibrary.list(c).size }}` 条\n")
            append("- 模型已装: `${safe { ModelStore.installedModes(c).map { it.id } }}`\n")
            append("- 模型缺件: `${safe { ModelStore.missing(c) }}`\n")
            append("- 制作状态: `${safe {
                val p = Producer.state.value
                "${p.stage} · ${p.done}/${p.total} · ${p.message}"
            }}`\n")
            append("- 主播放: `${safe {
                val s = PlayQueue.state.value
                "${s.label} · playing=${s.isPlaying} · ${s.positionMs}/${s.durationMs}ms · 队列 ${s.queueId}"
            }}`\n")
            append("- 试听: `${safe {
                val s = PlayQueue.previewState.value
                "${s.label} · playing=${s.isPlaying} · 队列 ${s.queueId}"
            }}`\n")
            append("- 主题: `${safe { Prefs.themeMode(c) }}`\n")
            append("- 事件日志: `${safe { file?.length() ?: 0L }}` 字节 · 详细 `${safe { detailFile?.length() ?: 0L }}` 字节\n")
        }
    }

    private fun header(): String =
        "# 听本 · 开发诊断日志\n\n" +
            "> 这份日志是**给开发者排查用的**，不是给使用者看的。\n" +
            "> 可能含书名与正文片段，贴出去前自行确认；软件无 INTERNET 权限，日志不会自己离开设备。\n\n"

    private fun fence(title: String, body: String): String =
        "## " + title + "\n\n```text\n" +
            (body.ifBlank { "（空）" }) + "\n```\n\n"

    fun exportBundle(c: Context): String = buildString {
        append(header())
        append("## 环境\n\n").append(envBlock(c)).append("\n")
        append("## 运行快照\n\n").append(snapshotBlock(c)).append("\n")
        append(fence("最近一次崩溃", lastCrash()))
        append(fence("崩溃前事件（环形缓冲）", ringTail(200)))
        // 全量，不再截断到 400 / 800 行：排查要的是完整时间线
        append(fence("事件日志（全量）", read()))
        append(fence("详细日志（全量）", readDetail()))
    }

    /** 一键复制用：关键状态 + 最近事件，控制体积。 */
    fun exportSummary(c: Context): String = buildString {
        append("# 听本 · 诊断摘要\n\n")
        append("> 给开发者排查用，可能含书名与正文片段。完整日志在设置页导出 .md。\n\n")
        append("## 环境\n\n").append(envBlock(c)).append("\n")
        append("## 运行快照\n\n").append(snapshotBlock(c)).append("\n")
        append(fence("最近崩溃（尾部）", lastCrash().lines().takeLast(40).joinToString("\n")))
        append(fence("最近事件（末尾 120 行）", read().lines().takeLast(120).joinToString("\n")))
    }

    @JvmStatic
    fun nativeNote(kind: String, action: String, detail: String) {
        val k = try { Kind.valueOf(kind) } catch (_: Throwable) { Kind.ENGINE }
        val lv = if (action.startsWith("NATIVE_DECODE_STEP") || action.startsWith("NATIVE_PREFILL") ||
            action.startsWith("ANN_WAIT")) Level.TRACE else Level.INFO
        log(k, action, detail, level = lv)
    }
}
