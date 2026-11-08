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

    // R147 · 用户会连着用好几次再来给日志。没有会话边界的话，一长条事件流
    // 分不清「哪一轮」，摘要只取末尾一小段还会把前面几轮全丢掉。每次冷启动记一个 id。
    @Volatile
    var currentSession: String = ""
        private set

    /** UI 交互专用入口：页面/浮层/勾选/播放器这类「用户做了什么」。 */
    fun ui(action: String, detail: String = "", fields: Map<String, String> = emptyMap()) =
        log(Kind.UI, action, detail, fields = fields)
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
        val ver = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) { "?" }
        currentSession = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        log(Kind.UI, "APP_START", "version=$ver", fields = mapOf("session" to currentSession))
        // 单独一行边界，导出时按它切会话，方便按「第几轮」读
        log(Kind.UI, "SESSION_START", "启动", fields = mapOf("session" to currentSession,
            "version" to ver))
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
        // R166 · 早期「给模型标注结构化字段」的方案做了一半就放弃，留下了双写：
        // detail 已经用中文把状态说全，fields 又逐字重复一遍英文 key=value，
        // 每行拖一截冗余尾巴（实测 FGS_FINISH 的 stage/done/total 全重复一遍）。
        // 这里只滤掉「同一 key=value 已在 detail 里逐字出现」的字段，
        // 有增量信息的字段（textChars / speakerParam / hash / code …）一律保留，
        // 信息量不减、体积变小、读起来不用人肉去比对两段。
        val kv = if (fields.isEmpty()) "" else
            fields.entries
                .filter { (k, v) -> "${k}=${v}" !in detail }
                .joinToString("") { " · ${it.key}=${it.value}" }
        val mainLine = "$ts | $kind | $action | $detail$kv"
        val detailLine = "$ts | $kind | $level | $action | $detail$kv"
        ring.addLast(mainLine)
        while (ring.size > RING_MAX) ring.removeFirst()
        appendCapped(file, mainLine, 768 * 1024L, 800)
        appendCapped(detailFile, detailLine, 1536 * 1024L, 2000)
    }

    fun read(): String = try { file?.readText() ?: "" } catch (_: Throwable) { "" }
    fun readDetail(): String = try { detailFile?.readText() ?: "" } catch (_: Throwable) { "" }
    /** 把事件流按 SESSION_START 切成「第几轮」，返回最近 n 轮的文本。 */
    private fun recentSessions(n: Int = 3): String {
        val lines = read().lines()
        val starts = mutableListOf<Int>()
        lines.forEachIndexed { i, l -> if (l.contains("| UI | SESSION_START |")) starts.add(i) }
        if (starts.isEmpty()) return lines.takeLast(300).joinToString("\n")
        val from = starts[maxOf(0, starts.size - n)]
        return lines.subList(from, lines.size).joinToString("\n")
    }

    /**
     * 全量错误行：不管哪一轮，出错都要带进摘要，别被窗口截掉。
     *
     * R157 · 第二份真机日志实证：摘要里那条 13:51「制作记录写入失败：segmap.json」
     * 其实是 1.1.3 的历史报错（R148 已修：目录名超长），但摘要区不标版本，
     * 读起来像 1.1.4 当前还在出错，差点让我去修一个已经修好的东西。
     * 现在按 SESSION_START 给每条标注产生时的版本；日志被裁剪、找不到会话边界的
     * 保守标成「旧版本」，不冒充当前版本。
     */
    private fun allErrors(): String {
        var ver = "旧版本"
        val out = mutableListOf<String>()
        for (l in readDetail().lines()) {
            if (l.contains("| UI | SESSION_START |")) {
                val m = Regex("version=([0-9][^\\s·|]*)").find(l)
                ver = m?.groupValues?.get(1) ?: "旧版本"
                continue
            }
            if (" | ERROR | " in l || " | CRASH | " in l || " | WARN | " in l)
                out += "[$ver] $l"
        }
        return out.takeLast(200).joinToString("\n")
    }

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

    /**
     * R161 · API 30+ 的 ApplicationExitInfo：进程上次是被谁干掉的。
     * 用户报「切出去再回来任务没了」，光看事件流分不清是用户划掉、
     * 系统低内存回收还是 ANR；系统记录是唯一能定性的证据。
     * 厂商 ROM 可能不给历史，读不到就静默（资源读失败属合理静默）。
     */
    fun logExitReasons(c: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager ?: return
            val list = am.getHistoricalProcessExitReasons(null, 0, 8)
            if (list.isNullOrEmpty()) return
            for (r in list) {
                log(Kind.AUDIT, "APP_EXIT",
                    "reason=${exitReasonName(r.reason)} ts=${r.timestamp} " +
                        "pid=${r.pid} importance=${r.importance} " +
                        "desc=${(r.description ?: "").take(160)}",
                    fields = mapOf("reason" to r.reason.toString(),
                        "ts" to r.timestamp.toString()))
            }
        } catch (_: Throwable) { }
    }

    /** 诊断导出用：最近几次进程退出原因，按系统返回顺序（最新在前）。 */
    fun exitReasonsBlock(c: Context): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R)
            return "（这台系统不提供进程退出原因）"
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager ?: return "（取不到 ActivityManager）"
            val list = am.getHistoricalProcessExitReasons(null, 0, 8)
            if (list.isNullOrEmpty()) return "（无记录）"
            buildString {
                for (r in list) {
                    val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                        .format(Date(r.timestamp))
                    append("- `$ts` · ${exitReasonName(r.reason)} · pid=${r.pid} · " +
                        "importance=${r.importance} · ${(r.description ?: "").take(120)}\n")
                }
            }
        } catch (t: Throwable) { "（不可用：" + t.javaClass.simpleName + "）" }
    }

    private fun exitReasonName(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动退出"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "用户强制停止"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存回收"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
        android.app.ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native 崩溃"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程死亡"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "收到信号"
        android.app.ApplicationExitInfo.REASON_FREEZER -> "系统冻结"
        android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "包状态变更"
        android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用更新"
        android.app.ApplicationExitInfo.REASON_OTHER -> "其他"
        else -> "未知($r)"
    }

    private fun fence(title: String, body: String): String =
        "## " + title + "\n\n```text\n" +
            (body.ifBlank { "（空）" }) + "\n```\n\n"

    fun exportBundle(c: Context): String = buildString {
        append(header())
        append("## 环境\n\n").append(envBlock(c)).append("\n")
        append("## 运行快照\n\n").append(snapshotBlock(c)).append("\n")
        append("## 进程退出原因（系统记录）\n\n").append(exitReasonsBlock(c)).append("\n")
        append(fence("最近一次崩溃", lastCrash()))
        append(fence("崩溃前事件（环形缓冲）", ringTail(200)))
        append("## 会话边界\n\n本次会话 `$currentSession`；" +
            "事件流里每条 `SESSION_START` 是一轮冷启动，按它切段即可对应「用第几轮」。\n\n")
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
        append("## 进程退出原因（系统记录）\n\n").append(exitReasonsBlock(c)).append("\n")
        append(fence("最近崩溃（尾部）", lastCrash().lines().takeLast(40).joinToString("\n")))
        append("> 下面是本机累计的全部报错，跨版本；方括号是产生时的版本，" +
            "**旧版本的报错不代表当前还在出错**。\n\n")
        append(fence("所有报错/警告（不受会话窗口限制）", allErrors()))
        append(fence("最近 3 轮完整事件（按 SESSION_START 分段）", recentSessions(3)))
    }

    @JvmStatic
    fun nativeNote(kind: String, action: String, detail: String) {
        val k = try { Kind.valueOf(kind) } catch (_: Throwable) { Kind.ENGINE }
        val lv = if (action.startsWith("NATIVE_DECODE_STEP") || action.startsWith("NATIVE_PREFILL") ||
            action.startsWith("ANN_WAIT")) Level.TRACE else Level.INFO
        log(k, action, detail, level = lv)
    }
}
