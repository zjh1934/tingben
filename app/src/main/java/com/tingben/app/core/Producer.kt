package com.tingben.app.core

import android.content.Context
import com.tingben.app.ProduceService
import com.tingben.app.diag.EventLog
import com.tingben.app.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

// @core-part: Producer
object Producer {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state
    @Volatile private var busy = false
    @Volatile private var cancelRequested = false
    @Volatile private var queuePaused = false

    @Volatile private var appCtx: Context? = null
    @Volatile private var current: TaskInfo? = null
    private val _resume = MutableStateFlow<TaskInfo?>(null)
    val resume: StateFlow<TaskInfo?> = _resume

    fun reset() { if (!busy) _state.value = Progress() }
    fun isBusy() = busy

    /**
     * R193 · 点「停止」后 running 立刻变 false，但 busy 要等当前那段推理
     * 收尾完才在 finally 里清（native 一段 1~3 分钟）。这段时间后台还握着
     * work/<id>，删书 / 重新分章不能只看 state.running，否则会删掉正在写的
     * 目录、重建幽灵分段。stop() 把 current 换成 stopped 副本但 bookId 不变，
     * 所以这个判据能完整覆盖收尾窗口。
     */
    fun isBusyForBook(bookId: String): Boolean =
        busy && current?.bookId == bookId

    // R139 · begin() 返回 false 时写在这里，autoResume 读它给 toast，不静默吞掉
    @Volatile var lastStartError: String? = null
        private set
    fun isStopping() = cancelRequested

    fun restore(c: Context) {
        appCtx = c.applicationContext
        val t = TaskResume.active(c) ?: return
        val fixed = if (t.phase == "running") t.copy(phase = "interrupted") else t
        TaskResume.save(c, fixed)
        current = fixed
        val real = TaskResume.realProgress(c, fixed)
        val why = TaskResume.invalidationReason(c, fixed)
        _resume.value = fixed.copy(done = real)
        _state.value = Progress(false, "上次中断", real, fixed.total, 0,
            if (t.phase == "blocked")
                "《${fixed.title}》上次没能开始制作；请在任务页点「继续制作」重试。"
            else if (t.phase == "reconfirm")
                "《${fixed.title}》重新分过章，旧任务不能接着用；请重新选章制作。"
            else if (why != null)
                "上次做到《${fixed.title}》。$why，分段文字还留着，点「继续制作」接着做。"
            else
                "上次做到《${fixed.title}》，已做好的块都留着。点「继续制作」从这接着跑，不用重算。")
        EventLog.log(EventLog.Kind.PIPELINE, "TASK_RESTORE",
            "book=${EventLog.safe(fixed.title)} phase=${fixed.phase} real=$real/${fixed.total}" +
                (if (why != null) " invalidate=$why" else ""))
    }

    // 软件重新打开时调用：只有「进程被杀 / 崩溃」留下的 interrupted 任务自动接上。
    // 必须在 Activity 可见之后调，不能在 Application.onCreate 里，
    // 否则 Android 12+ 会拦后台启动前台服务。
    fun queueState(c: Context): Pair<Int, Boolean> =
        TaskResume.queueSize(c) to (queuePaused || TaskResume.queuePaused(c))

    // 目录页点「合成音频」：忙时不再拒绝，排进队列；空闲且队列为空时直接开跑。
    fun enqueueOrStart(c: Context, book: Book, only: Set<Int>?, think: Boolean?,
                       config: ProduceConfig): String {
        val t = TaskInfo(book.id, book.title, only, true, think ?: false,
            0, 0, "queued", "排队中", System.currentTimeMillis(),
            config = config, modeId = ModelStore.currentMode(c).id,
            splitRule = TaskResume.SPLIT_RULE,
            planEpoch = ChapterPlan.epoch(c, book.id))
        val queueEmpty = TaskResume.queue(c).isEmpty()
        // R213 · 暂停标记只用来防「进程重启后自动把停掉的队列跑起来」。
        // 队列已空时它只是上次收尾的残留；用户主动点合成是明确意图，
        // 先清掉再直接开跑，否则会被排进一个暂停队列、点了没反应。
        if (queueEmpty && TaskResume.queuePaused(c)) {
            queuePaused = false
            TaskResume.setQueuePaused(c, false)
        }
        val paused = TaskResume.queuePaused(c) && !queueEmpty
        if (!busy && queueEmpty && !paused) {
            start(c, book, only, true, think, config)
            // R175 · P2-6：队列泵与自动续跑都查了，只有这条直接启动路径漏了。
            // FGS 起不来时 start() 已把任务存成 blocked，这里绝不能再报「开始制作」。
            lastStartError?.let { return it }
            return "开始制作《${book.title}》"
        }
        TaskResume.enqueue(c, t)
        if (!busy && !paused) {
            queuePaused = false
            TaskResume.setQueuePaused(c, false)
            scope.launch { pumpQueue(c.applicationContext) }
        }
        val n = TaskResume.queueSize(c)
        return "《${book.title}》已排到队列第 $n 个" +
            if (paused) "（队列已暂停，点「继续队列」开跑）" else "，做完当前这本自动接着跑"
    }

    private fun pumpQueue(c: Context): String? {
        if (busy || queuePaused || TaskResume.queuePaused(c)) return null
        while (true) {
            val t = TaskResume.queue(c).firstOrNull() ?: return null
            // R138 · 排队项可能是旧口径任务，先按章节表判定能否安全迁移
            if (t.splitRule < TaskResume.SPLIT_RULE) {
                ChapterPlan.resolve(c, BookStore.list(c).find { it.id == t.bookId }
                    ?: return null)
                val rid = ChapterPlan.currentRuleId(c, t.bookId)
                if (rid != TocRules.LEGACY && rid != ChapterPlan.EPUB) return "《${t.title}》分章规则变过，队列暂停，请先在目录页重新分章"
                TaskResume.migrateSplit(c, t.bookId)
            }
            val book = BookStore.list(c).find { it.id == t.bookId }
            if (book == null) {
                TaskResume.dequeue(c, t.bookId)
                EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_SKIP",
                    "书没了 id=${t.bookId.take(8)} · title=${EventLog.safe(t.title)}")
                continue
            }
            val why = TaskResume.autoResumeBlockReason(c, t.copy(phase = "interrupted"))
            if (why != null) {
                queuePaused = true
                TaskResume.setQueuePaused(c, true)
                EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_PAUSE", "reason=$why")
                return "队列已暂停：$why"
            }
            TaskResume.dequeue(c, t.bookId)
            start(c, book, t.only, true, t.think, t.config)
            if (lastStartError != null) {
                // R139 · 前台服务没起来：把这一项放回队列并暂停，
                // 不能因为一次系统限制就把排队任务吞掉。
                TaskResume.enqueue(c, t)
                queuePaused = true
                appCtx?.let { TaskResume.setQueuePaused(it, true) }
                return lastStartError
            }
            return "队列接着做《${t.title}》 · 还剩 ${TaskResume.queueSize(c)} 个"
        }
    }

    /**
     * R284 · 暂停标记只该防「进程重启后自动把停掉的队列跑起来」（R213）。
     * 用户在任务页主动点「继续制作」是明确意图，内存态与落盘态要一起清，
     * 否则 pumpQueue 仍被 queuePaused 挡住，排队项永远卡死。
     */
    fun unpauseQueue(c: Context) {
        queuePaused = false
        TaskResume.setQueuePaused(c, false)
    }

    fun resumeQueue(c: Context): String? {
        unpauseQueue(c)
        EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_RESUME", "用户恢复队列")
        return pumpQueue(c.applicationContext)
    }

    // R159 · 开软件时的「待处理任务」。
    //
    // 用户实证：「关闭以后重新打开应用，它不一定弹」。查实有四条原因，其中三条
    // 在这里：原来 hasResumable 只放行「真的能自动续跑」的任务，手动停 / 失败 /
    // 模型没齐 / 队列暂停一律返回 null，界面就静默不弹；用户眼里就是「看运气」。
    // 现在改成：只要存在未完成任务就返回，由 PendingTask.action 决定弹窗给什么
    // 出口（自动续跑 / 继续队列 / 去任务页手动处理）。守卫仍留在 autoResume 里，
    // 放宽「看不看得见」不等于放宽「能不能自动跑」。
    enum class ResumeAction { AUTO, QUEUE, VIEW }

    data class PendingTask(
        val task: TaskInfo,
        val action: ResumeAction,
        val label: String,     // 短状态标签，进胶囊，不写成长句解释
        val reason: String?    // 只进日志，不进 UI
    )

    fun pending(c: Context): PendingTask? {
        if (busy) return null
        val paused = TaskResume.queuePaused(c)
        if (paused) queuePaused = true
        val q = TaskResume.queue(c).firstOrNull()
        if (q != null) {
            // R284 · 真机诊断快照：「队列 1 项 · 暂停=true」同时 active 是
            // stopped 385/26678。原实现只要队列非空就返回排队项（done/total 都是 0），
            // 弹窗显示 0/0 块，真正在做的那本被藏掉；主按钮开跑的又是排队项 ——
            // 用户眼里就是「这一章不去合成了」。队列是内部调度，恢复决策以
            // active（有真实断点）为准；自动续跑守卫一条不动。
            val live = TaskResume.active(c)?.takeIf { it.phase != "done" }
                ?.takeIf { BookStore.list(c).any { b -> b.id == it.bookId } }
            if (live != null) {
                val liveWhy = TaskResume.autoResumeBlockReason(c, live)
                val liveLabel = when {
                    live.stage == "已达时长上限" -> "已达时长上限"
                    live.phase == "blocked" -> "没开始成功"
                    live.phase == "stopped" -> "已停止"
                    liveWhy != null -> "待确认"
                    else -> "排队中"
                }
                // 手动停 / 失败 / 规则变过 / 队列暂停：都不自动跑，给「查看任务」，
                // 由用户在任务页点「继续制作」（resumeTask）主动续这本。
                return if (paused || liveWhy != null)
                    PendingTask(live, ResumeAction.VIEW, liveLabel,
                        liveWhy ?: "队列已暂停")
                else
                    PendingTask(live, ResumeAction.AUTO, liveLabel, null)
            }
            return if (paused)
                PendingTask(q, ResumeAction.QUEUE, "队列已暂停", "队列已暂停")
            else
                PendingTask(q, ResumeAction.AUTO, "排队中", null)
        }
        val t = TaskResume.active(c) ?: return null
        if (t.phase == "done") return null
        if (BookStore.list(c).none { it.id == t.bookId }) return null
        val why = TaskResume.autoResumeBlockReason(c, t)
        val label = when {
            t.stage == "已达时长上限" -> "已达时长上限"
            t.phase == "blocked" -> "没开始成功"
            t.phase == "stopped" -> "已停止"
            t.phase == "interrupted" && why == null -> "意外中断"
            why != null -> "待确认"
            else -> "未完成"
        }
        return PendingTask(
            t, if (why == null) ResumeAction.AUTO else ResumeAction.VIEW, label, why)
    }

    /** 兼容旧调用：只把「能自动续跑」的挑出来，口径与 pending 同源，不分家。 */
    fun hasResumable(c: Context): TaskInfo? =
        pending(c)?.takeIf { it.action == ResumeAction.AUTO }?.task

    // 软件重新打开时调用：队列优先（用户要「队列里有任务就自动开始跑」），
    // 没有排队任务才处理崩溃中断的单本续跑。
    // G1 之后只在弹窗里用户点「继续制作」时才被调，不再由 ON_RESUME 直接触发。
    fun autoResume(c: Context): String? {
        lastStartError = null
        if (busy) return null
        if (TaskResume.queuePaused(c)) queuePaused = true
        else {
            val q = pumpQueue(c.applicationContext)
            if (lastStartError != null) return lastStartError
            if (q != null) return q
        }
        val t = TaskResume.active(c) ?: return null
        if (t.phase == "done") return null
        val why = TaskResume.autoResumeBlockReason(c, t)
        if (why != null) {
            EventLog.log(EventLog.Kind.PIPELINE, "TASK_AUTO_RESUME_SKIP",
                "book=${EventLog.safe(t.title)} phase=${t.phase} reason=$why")
            return why
        }
        val book = BookStore.list(c).find { it.id == t.bookId }
            ?: return "这本书已从书架删掉，没法续跑"
        start(c, book, t.only, true, t.think, t.config)
        if (lastStartError != null) return lastStartError
        EventLog.log(EventLog.Kind.PIPELINE, "TASK_AUTO_RESUME",
            "book=${EventLog.safe(t.title)} phase=${t.phase} done=${t.done}/${t.total}")
        return "已自动接着上次的《${t.title}》继续制作"
    }

    /**
     * R192 · 删书时若正在制作这本书，立刻停。否则后台协程会往刚
     * deleteRecursively 掉的 work/<id> 重新写盘，重建幽灵目录继续推理。
     * 复用 stop()（它会同步 current / 落 stopped / nativeCancel），
     * 因此会连带把队列置为暂停 —— 如实写进回执，不静默吞掉。
     */
    fun stopIfBook(bookId: String): Boolean {
        if (!busy || current?.bookId != bookId) return false
        stop()
        return true
    }

    fun stop() {
        if (!busy) return
        // 用户点停止 = 暂停整个队列，不是只停当前这一本，
        // 否则停完 finally 又自动接下一个，等于没停。
        queuePaused = true
        appCtx?.let { TaskResume.setQueuePaused(it, true) }
        cancelRequested = true
        // R148 · 1.1.3 日志：点停止后 79 秒才 FGS_FINISH，用户以为「按了没反应」。
        // 取消只在段间检查，而一段推理要 1~3 分钟。先立刻把状态改成已停止，
        // 当前那段跑完直接丢掉（不写盘、不计入进度），不再让用户干等。
        _state.value = Progress(false, "已停止", _state.value.done, _state.value.total,
            _state.value.failed, "已停止，正在收尾当前这一小段…已做好的都留着")
        try { NativeEngine.nativeCancel() } catch (_: Throwable) { }
        appCtx?.let { c ->
            current?.let { t ->
                val stopped = t.copy(phase = "stopped",
                    stage = _state.value.stage, done = _state.value.done,
                    updatedAt = System.currentTimeMillis())
                // 同步内存态：否则 finally 会按 running 再标成 interrupted，
                // 下次开软件就被当成崩溃中断而自动续跑，违背用户主动停止的意图。
                current = stopped
                TaskResume.save(c, stopped)
            }
            ProduceService.stopping(c)
        }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_STOP", "用户点了停止")
    }

    /**
     * R161 · 系统前台服务跑到 6 小时上限（Service.onTimeout）时的统一出口。
     * 与用户点停止共用 stop()，但把 stage 写成可辨识的「已达时长上限」：
     * 下次开软件弹窗显示这个原因，而不是「意外中断」，也不会被当成崩溃
     * 自动续跑（一续跑又会立刻撞上限）。手动停的任务绝不被偷偷跑起来。
     */
    fun onServiceTimeout(c: Context, fgsType: Int) {
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_FGS_TIMEOUT",
            "前台服务时长到点，任务安全停下 · fgsType=$fgsType · " +
                "done=${_state.value.done}/${_state.value.total}",
            fields = mapOf("fgsType" to fgsType.toString()))
        if (busy) stop()
        val ctx = appCtx ?: c.applicationContext
        val p = _state.value
        current?.let { t ->
            val fixed = t.copy(phase = "stopped", stage = "已达时长上限",
                done = p.done, updatedAt = System.currentTimeMillis())
            current = fixed
            TaskResume.save(ctx, fixed)
            _resume.value = fixed
        }
        _state.value = p.copy(running = false, stage = "已达时长上限",
            message = "已到系统本次运行时长上限，安全停在 ${p.done}/${p.total} 块，做好的都留着。")
    }

    private fun halted(done: Int, total: Int, failed: Int): Progress = Progress(
        false, "已停止", done, total, failed,
        "已停在 $done/$total 块，已做好的都留着。点「继续制作」从这接着跑。")

    fun hasProgress(c: Context, bookId: String, only: Set<Int>?) =
        AnnCache.hasProgress(c, bookId, only)

    fun clearProgress(c: Context, bookId: String, only: Set<Int>?) {
        AnnCache.clear(AnnCache.dirFor(c, bookId, only))
        val saved = TaskResume.byBook(c, bookId)
        if (saved != null || current?.bookId == bookId) {
            current = null; _resume.value = null
        }
        TaskResume.remove(c, bookId)
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_RESET", "清空这本书的进度，从头重做")
    }

    fun start(c: Context, book: Book, onlyChapters: Set<Int>? = null,
              directRead: Boolean = false, thinkOverride: Boolean? = null,
              config: ProduceConfig = ProduceConfig()) {
        if (busy) return
        busy = true
        cancelRequested = false
        appCtx = c.applicationContext
        val thinkOn = thinkOverride ?: false
        current = TaskInfo(book.id, book.title, onlyChapters, true, thinkOn,
            0, 0, "running", "正在准备", System.currentTimeMillis(),
            config = config, modeId = ModelStore.currentMode(c).id,
            splitRule = TaskResume.SPLIT_RULE,
            planEpoch = ChapterPlan.epoch(c, book.id))
        appCtx?.let { TaskResume.save(it, current!!) }
        _resume.value = current
        _state.value = Progress(true, "正在准备", 0, 0, 0, "正在启动…")
        try { NativeEngine.nativeCancelReset() } catch (_: Throwable) { }
        AnnCache.cleanup(AnnCache.dirFor(c, book.id, onlyChapters))
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_MODE",
            "直接朗读" +
                " · think=" + thinkOn +
                " · " + (onlyChapters?.let { "选中 ${it.size} 章" } ?: "整本") +
                " · 本次预设=" + config.summarySafe())
        ProduceService.activityOf(c)?.let { act ->
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                act.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    act.requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001)
                    EventLog.log(EventLog.Kind.PIPELINE, "FGS_NOTIF_PERMISSION",
                        "已向用户申请 POST_NOTIFICATIONS（拒绝不影响制作）")
                }
            }
        }
        // R139 · 前台服务起不来就别开协程：没有 FGS 的进程随时被杀，
        // 断点会留在半途，界面却一直显示「正在制作」。停在「没开始」并说清原因。
        if (!ProduceService.begin(c, book.title)) {
            val why = "系统没让制作服务启动，任务没有开始；回到软件前台再点一次开始"
            lastStartError = why
            busy = false
            cancelRequested = false
            // R175 · 系统限制不是「用户手动停」，也不该被 finally 误标中断
            abortBeforeStart(why)
            EventLog.log(EventLog.Kind.CRASH, "PRODUCE_FGS_BLOCKED", why)
            return
        }
        lastStartError = null
        scope.launch {
            try {
                run(c, book, onlyChapters, true, thinkOverride, config)
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    EventLog.log(EventLog.Kind.CRASH, "PRODUCE_EXCEPTION", t.message ?: "")
                    _state.value = Progress(false, "出错了", 0, 0, 0, "制作中断：" + (t.message ?: ""))
                    markInterrupted("出错中断")
                }
            } finally {
                busy = false
                if (current?.phase == "running") {
                    val s = _state.value.stage
                    markInterrupted(if (s in listOf("没完成", "出错了", "已停止")) s else "中断")
                }
                val p = _state.value
                ProduceService.finish(c.applicationContext, p.stage, p.done, p.total)
                // 只有真正「完成」才自动接下一个；停止 / 报错 / 存储不足一律停在原地，
                // 队列保留但暂停，不自动跳过、不无限重试。
                if (p.stage == "完成" || current?.phase == "done") {
                    if (!queuePaused && !TaskResume.queuePaused(c.applicationContext))
                        scope.launch { pumpQueue(c.applicationContext) }
                } else {
                    queuePaused = true
                    TaskResume.setQueuePaused(c.applicationContext, true)
                    EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_PAUSE",
                        "stage=${p.stage} 没正常完成，队列停在原地")
                }
            }
        }
    }

    /**
     * R175 · P2-5「没开始的配置失败被记成意外中断」根治。
     *
     * 原来 run() 里 native 不可用 / 模型没齐 / 没有可制作文字 / 模型加载失败
     * 都只设 _state 后 return，current.phase 仍是 "running"；finally 于是
     * finally 把它标成「意外中断」。而 autoResumeBlockReason 的 stage 黑名单只有
     * 没完成/出错了/已停止，不含「中断」；模型齐全时 synthesisBlockReason 与
     * invalidationReason 都返回 null → pending() 给 AUTO → 开软件自动续跑、
     * 再次早退、再次记中断，循环往复，弹窗还写着「意外中断」。
     * 现在显式落 blocked 阶段并说清原因，finally 不再碰它。
     */
    private fun abortBeforeStart(msg: String, stage: String = "没开始") {
        _state.value = Progress(false, stage, 0, 0, 0, msg)
        val t = current ?: return
        val blocked = t.copy(phase = "blocked", stage = stage,
            done = 0, total = 0, updatedAt = System.currentTimeMillis())
        current = blocked
        appCtx?.let { TaskResume.save(it, blocked) }
        _resume.value = blocked
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_BLOCKED", "$stage：$msg")
    }

    private fun markInterrupted(stage: String) {
        val c = appCtx ?: return
        val t = current ?: return
        val fixed = t.copy(phase = "interrupted", stage = stage,
            done = _state.value.done, total = _state.value.total.coerceAtLeast(t.total),
            updatedAt = System.currentTimeMillis())
        current = fixed
        TaskResume.save(c, fixed)
        _resume.value = fixed.copy(done = TaskResume.realProgress(c, fixed))
    }

    /**
     * R176 · 长书落盘 / 通知节流。
     *
     * 原来每段 emit 都全量重写 tasks.json 与任务清单：AtomicJson.write 内部还要
     * 读旧文件、写 .bak、写 .part、rename，一段就是十来次文件操作；9000 段的
     * 6 小时书累计几万次 IO，外加 9000 次 nm.notify。长书后段明显变慢、耗电。
     *
     * 现在 1.5 秒落一次盘、1.5 秒刷一次通知；阶段切换与收尾强制落一次。
     * 断点不丢：已落盘 WAV 才是进度真值（TaskResume.realProgress 按磁盘重算），
     * tasks.json 的 done 只影响文案，进程被硬杀时最多滞后 1.5 秒。
     * 内存态 _state / current 仍每段更新，界面与进度条完全不受节流影响。
     */
    private const val PERSIST_INTERVAL_MS = 1500L
    private var lastPersistMs = 0L
    private var lastNotifyMs = 0L

    private fun persistTick(force: Boolean) {
        val c = appCtx ?: return
        val t = current ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistMs < PERSIST_INTERVAL_MS) return
        TaskResume.save(c, t)
        lastPersistMs = now
    }

    /** R183 · 把毫秒说成人能估量的「X 小时 Y 分 / X 分」，不给假精确。 */
    private fun formatEta(ms: Long): String {
        val min = (ms / 60000L).coerceAtLeast(1L)
        return if (min >= 60L) "${min / 60L} 小时 ${min % 60L} 分" else "$min 分"
    }

    /**
     * R183 · 制作前就能说清「这次设置和已做好的段不一样，会重做」。
     * 判据与 run() 里两处作废逻辑同源：mode_stamp / produce_stamp 任一变了，
     * 或从未写过 stamp 但已有 wav（老数据）。只数有效 wav，不读正文。
     */
    fun voiceCacheSummary(c: Context, bookId: String, only: Set<Int>?,
                          config: ProduceConfig = ProduceConfig()): VoiceCache.Summary = try {
        val work = AnnCache.dirFor(c, bookId, only)
        val key = VoiceCache.keyOf(Prefs.voiceStamp(c),
            TaskResume.expectedModeStamp(c), config.audioStamp())
        VoiceCache.summary(work, key)
    } catch (_: Throwable) { VoiceCache.Summary(0, 0, 0, 0L) }

    private fun emit(stage: String, done: Int, total: Int, failed: Int, msg: String,
                     out: String? = null, chapterProgress: Map<Int, Float> = emptyMap()) {
        _state.value = Progress(true, stage, done, total, failed, msg, out, chapterProgress)
        val c = appCtx
        val t = current
        // t 是 copy 之前的旧对象，拿它比 stage 能准确识别「刚换了阶段」
        val stageChanged = t?.stage != stage
        if (c != null && t != null && t.phase == "running") {
            val up = t.copy(total = total, done = done, stage = stage,
                updatedAt = System.currentTimeMillis())
            current = up
            persistTick(force = stageChanged)
        }
        val now = System.currentTimeMillis()
        // R217 · 取「章号最大那一章」的进度给悬浮窗下球。
        // R214 原来比对的是 value（取所有章里进度最大的），做到第 2 章
        // 时下球还停在第 1 章的 100%，看着像卡住。比对 key 才是当前章。
        // 不用 maxByOrNull 之类版本相关 API，纯循环最稳。
        var chPct = -1f
        var chIdx = -1
        for ((k, v) in chapterProgress) if (k > chIdx) { chIdx = k; chPct = v }
        // R255 · 上球真机反馈「不更新」：浮窗刷新从通知节流里拆出来，每段必刷。
        // 通知仍按 stage 切换 / 1.5s 节流，互不拖累。
        appCtx?.let { ctx ->
            ProduceService.overlay(ctx.applicationContext, done, total, chPct)
        }
        if (stageChanged || now - lastNotifyMs >= PERSIST_INTERVAL_MS) {
            appCtx?.let { ctx ->
                ProduceService.update(ctx.applicationContext, stage, done, total,
                    msg, chPct)
            }
            lastNotifyMs = now
        }
    }

    private fun run(c: Context, book: Book, only: Set<Int>?, direct: Boolean,
                    thinkOverride: Boolean? = null, config: ProduceConfig = ProduceConfig()) {
        // R176 · 每个任务重新计时，别让上一任务的节流窗口吃掉本次首帧
        lastPersistMs = 0L
        lastNotifyMs = 0L
        if (!NativeEngine.isAvailable()) {
            abortBeforeStart(NativeEngine.shortReason())
            return
        }
        ModelStore.synthesisBlockReason(c)?.let { reason ->
            abortBeforeStart(reason)
            return
        }
        val tk = ModelStore.modeFile(c, ModelStore.currentMode(c))
        val tok = ModelStore.tokenizer(c)
        val miss = mutableListOf<String>()
        if (tk == null) miss.add("模式主文件")
        if (tok == null) miss.add("共用底座（12Hz）")
        if (miss.isNotEmpty()) {
            abortBeforeStart("还差：" + miss.joinToString("、") + "。去模型页导入。")
            return
        }

        emit("正在分章", 0, 0, 0, "正在把这本书切成能读的小块…")
        val text = BookStore.read(c, book)
        // R138 · 制作运行也用落盘章节表，保证与目录页、断点校验同一套章号
        val (chapters, allChunks) = ChapterPlan.resolve(c, book, text)
        val chunks = if (only == null) allChunks else allChunks.filter { it.chapter in only }
        if (chunks.isEmpty()) {
            abortBeforeStart("这本书没有可制作的文字")
            return
        }
        val thinkOn = thinkOverride ?: false
        val work = AnnCache.dirFor(c, book.id, only)
        // R219 · 记下这批分段用的粒度；续跑时若代际不同，绝不复按段号用旧段
        try {
            work.mkdirs()
            File(work, "split_stamp.txt").writeText(TextSplitter.SPLIT_EPOCH.toString())
        } catch (_: Throwable) { }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_CONFIG",
            "chunks=${chunks.size} · 预设=" + config.summarySafe())
        EventLog.resetEngine()

        val gateFails = 0
        val stats = mutableListOf<TextSplitter.Stats>()
        val segChapter = mutableListOf<Int>()
        val annotated = chunks.flatMap { ch ->
            val (segs, s) = Annotator.directWithStats(ch.text)
            stats.add(s)
            repeat(segs.size) { segChapter.add(ch.chapter) }
            segs
        }
        EventLog.log(EventLog.Kind.PIPELINE, "SPLIT_STATS",
            "${chunks.size} 块 → ${annotated.size} 段 · 只在标点/段落边界断 · " +
                "字符硬切 0 · 句段边界 ${stats.sumOf { it.sentenceCuts }} · " +
                "次级边界 ${stats.sumOf { it.softCuts }} · " +
                "超长整句保留 ${stats.sumOf { it.oversize }} · " +
                "最长 ${stats.maxOfOrNull { it.longest } ?: 0} 字 · " +
                "原文 ${stats.sumOf { it.charsIn }} 字 拼回 ${stats.sumOf { it.charsOut }} 字" +
                (if (stats.sumOf { it.charsIn } == stats.sumOf { it.charsOut }) " · 逐字无损" else " · ⚠️字数不一致"))
        EventLog.log(EventLog.Kind.PIPELINE, "DIRECT_READ",
            "${chunks.size} 块跳过分段，切成 ${annotated.size} 段直接朗读")
        try {
            val sc = JSONArray()
            segChapter.forEach { sc.put(it) }
            AtomicJson.write(File(work, "seg_chapters.json"), sc.toString())
            val ct = JSONArray()
            chapters.forEach { ch ->
                ct.put(JSONObject().put("index", ch.index).put("title", ch.title))
            }
            AtomicJson.write(File(work, "chapters.json"), ct.toString())
            // R140 · 批次 D：启用 segmap。之前 writeMap 全工程零调用、readMap 恒 null，
            // 看台 / 剩余段数 / verdict 全都走「按已落盘最大序号 + total」的兜底推算。
            // 现在把本次计划段一次性落盘，UI 拿到的是真值而不是猜的。
            // wavName 与 Producer 合成循环里的命名严格一致（direct_%04d.wav）。
            SegPlan.writeMap(work, annotated.mapIndexed { i, a ->
                SegMeta(i, i, 0, null, a.text.take(200), a.speaker, a.instruct,
                    String.format("direct_%04d.wav", i))
            })
            SegPlan.lastWriteError?.let { UiErrors.storage(it, null) }
        } catch (t: Throwable) {
            UiErrors.storage("分段计划没写进磁盘，看台会改用兜底推算", t)
        }
        emit("正在生成声音", 0, annotated.size, gateFails, "音频模型正在把文字读出来…")

        val knobs = Prefs.ttsKnobs(c)
        val globalVoice = Prefs.voiceDescription(c)
        val voiceStamp = Prefs.voiceStamp(c)
        val modeStamp = TaskResume.expectedModeStamp(c)
        val presetStamp = config.audioStamp()

        // R185 · 原来这里有三段「stamp 一变就把 work 根下所有 wav 删光」的逻辑。
        // 用户经常调音色，一次保存调优就把几小时的成果全抹了（真机日志
        // MODE_INVALIDATE 作废 9 段、real 从 9/88 掉回 1/88）。现在改成按
        // 音色指纹分套归档：旧套搬到 _voice_{key}/ 留着，目标套搬回根目录。
        // 三个 stamp 文件照旧写，TaskResume 的失效判据继续有效，但不再删文件。
        val cacheKey = VoiceCache.keyOf(voiceStamp, modeStamp, presetStamp)
        val sw = VoiceCache.switchTo(work, cacheKey)
        if (sw.archived > 0 || sw.restored > 0) {
            EventLog.log(EventLog.Kind.PIPELINE, "VOICE_CACHE_SWITCH",
                "音色套=$cacheKey · 旧套留档 ${sw.archived} 段 · 认领 ${sw.restored} 段" +
                    " · 已做好的不重做，后面按新参数继续（切回原设置可还原）")
        }
        try { File(work, "voice_stamp.txt").writeText(voiceStamp) } catch (_: Throwable) { }
        try { File(work, "mode_stamp.txt").writeText(modeStamp) } catch (_: Throwable) { }
        try { File(work, "produce_stamp.txt").writeText(presetStamp) } catch (_: Throwable) { }

        // R166 · 括号里那句关于目标语言取值的说明是写给我看的开发依据，
        // 混进运行时日志只会让每行拖一句解释；依据留在 ParamSpec / 官方文档。
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_MODE",
            "${SynthInput.label(c)} · 指纹=$voiceStamp · seed=${knobs.seed}" +
                " · temp=${knobs.temperature} · lang=chinese")
        EventLog.log(EventLog.Kind.PIPELINE, "PRESET_APPLIED",
            config.summarySafe() + " · 指纹=" + presetStamp)

        val ttsHandle = try {
            NativeEngine.ttsLoadGated(tk!!.absolutePath, tok!!.absolutePath, null)
        } catch (t: Throwable) {
            abortBeforeStart("音频模型加载失败：" + (t.message ?: ""))
            return
        }
        if (ttsHandle == 0L) {
            abortBeforeStart("音频模型打不开，请确认两个文件都导入了")
            return
        }
        try {
            SynthInput.prepare(c, ttsHandle)
        } catch (t: Throwable) {
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
            abortBeforeStart(t.message ?: "模式输入没准备好")
            return
        }

        val nonVerbalOn = Prefs.nonVerbal(c)
        var strippedTotal = 0
        // R235 · 开跑前只做统计与日志，不再把整本的 instruct 一次性拼好。
        // 身份层 / 风格层 / 拟声 / 采样参数都挪到逐段循环里现读现算：
        // 制作过程中在调音台改了设置，下一段就按新配置念，不必整本停了重来。
        annotated.forEach { ann ->
            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann
            val built = VoiceProfile.buildWithReport(globalVoice, config.styleInstruct(), base.instruct)
            strippedTotal += built.second
        }
        val toSpeak = annotated
        EventLog.log(EventLog.Kind.ENGINE, "VOICE_IDENTITY",
            "身份层=${if (globalVoice.isBlank()) "默认锚点" else "自定义"} · " +
                    "版本=$INSTRUCT_TEMPLATE_VERSION · " +
                    "seed=${knobs.seed} · ${annotated.size} 段")
        if (strippedTotal > 0) EventLog.log(EventLog.Kind.PIPELINE, "INSTRUCT_SANITIZED",
            "从 $strippedTotal 处导演指令里剥掉音色身份词；身份由固定层提供，不再逐段重造")
        if (nonVerbalOn) {
            val hits = NonVerbal.count(annotated)
            EventLog.log(EventLog.Kind.PIPELINE, "NONVERBAL",
                "命中 $hits/${annotated.size} 段，只增强导演指令，正文未改")
        }

        val doneChapterFiles = try { AudioTree.readDone(work) } catch (_: Throwable) { emptyMap() }
        val usedDoneChapters = mutableSetOf<Int>()
        val wavs = mutableListOf<File>()
        var synthFails = 0
        // R157 · 只有真合成的段才算参数；跳过段不碰它（见循环里的 TTS_SKIP）。
        // 声明成可空并在用时兜底重算，任何意外路径都不会拿到空指令。
        var lastParams: SynthInput.Params? = null
        val gapBytes = ((config.gapMs.coerceIn(0, 5000) * 48) / 2) * 2
        val segStartBytes = mutableListOf<Long>()
        var cursorBytes = 0L
        val perChapterTotal = segChapter.groupingBy { it }.eachCount()
        val perChapterDone = mutableMapOf<Int, Int>()
        // R233 · 一次读入「用户删掉的段」，循环与拼章共用，不逐段读盘
        val dropSnap = SegDrops.snapshot(c, book.id)
        val dropText = { i: Int -> annotated[i].text.take(200) }
        val dropWav = { i: Int -> String.format("direct_%04d.wav", i) }
        // R235 · 开跑时先读一次「段级重做」标记，空间预算把这些段算进待合成
        val redoAtStart = SegPlan.pending(work)
        val pending = (0 until toSpeak.size).count { i ->
            val wavName = dropWav(i)
            // 删掉的段不算待合成，空间预算与进度分母都不虚高
            if (SegDrops.matches(dropSnap, wavName, dropText(i))) return@count false
            val f = File(work, wavName)
            if (f.exists() && f.length() > 44) return@count false
            val ci = segChapter.getOrElse(i) { -1 }
            val covered = ci >= 0 && doneChapterFiles[ci]
                ?.let { it.isFile && it.length() > 44 } == true
            if ("seg:$i" in redoAtStart) return@count true
            !covered
        }
        val sample = (0 until toSpeak.size).mapNotNull { i ->
            File(work, String.format("direct_%04d.wav", i))
                .takeIf { it.exists() && it.length() > 44 }?.length()
        }
        // R148 · 三处修正（1.1.3 日志实证：free=0 误判，103 章全本被直接拒掉）
        //  ① 可用空间改读 filesDir：诊断日志用它读到 273GB；work 子目录若
        //     mkdirs 失败（见 bucket 修复），usableSpace 会返回 0
        //  ② 单段兜底从「60 秒」改成「12 秒」，且不再「分段 + 合并」双算；
        //     原算法 9137 段估出 61GB，实际远不需要
        //  ③ 真不够也不一刀切拒绝：算出预算段数，做到哪算哪，停时明确说
        //     「已做 N 段，清出空间后继续」，已生成的段全留着
        val segBytes = if (sample.isNotEmpty()) sample.average().toLong()
                       else 24_000L * 2L * 12L
        val freeBytes = try { c.filesDir.usableSpace } catch (_: Throwable) { 0L }
        val headroom = 16L * 1024 * 1024
        // 查不到可用空间就放行，绝不因为「查不到」拒绝制作
        val budgetBytes = if (freeBytes > 0L) maxOf(0L, freeBytes - headroom)
                          else Long.MAX_VALUE
        val estimateBytes = pending.toLong() * segBytes + headroom
        val budgetSegs = if (budgetBytes == Long.MAX_VALUE) Int.MAX_VALUE
                         else (budgetBytes / maxOf(1L, segBytes)).toInt()
        if (estimateBytes > budgetBytes) {
            EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_PARTIAL",
                "空间可能不够，按预算先做 · free=$freeBytes need≈$estimateBytes " +
                    "budgetSegs=$budgetSegs pending=$pending seg=$segBytes")
        }
        var spaceStopped = false
        // R175 · P2-7：budgetSegs 是「这次还能新合成多少段」。原来拿 wavs.size
        // （含跳过复用的旧段）去比，纯续跑一个字节没写也会被判定空间用完、
        // 提前 break 卡在合并之前。只数真正落盘的新段。
        var newlyWritten = 0
        // R183 · 真机日志实证：每段 50~220 秒，用户等 4 分 23 秒只出 1 段就点了
        // 停止。进度里给「按当前速度还需约多久」，让人知道不是卡死，也好决定
        // 要不要整本做。只统计真正合成的新段，跳过的旧段不算进均值。
        var segCostSum = 0L
        var segCostCount = 0
        // R266 · 本次任务里已经回头重做过的段。失败也只试一次，标记留给下次
        // 「继续制作」再认领，不在这里反复重试把主循环拖死。
        val handledRedo = mutableSetOf<Int>()
        // R300 · 用户口径：不是整本跑完才统一拼，是「这一章的小块做完，这章
        // 立刻自动拼成一个章音频」。同章段在 toSpeak 里是连续区间，先按章归好
        // 索引、记下每章最后一段，主循环走到边界就 mergeChapterAuto（登记 +
        // markDone + 清这一章的小块），然后接着做下一章，段进度与通知一直有
        // 动静。收尾那次 ensureChapterAudios 只当补漏网：正常路径到收尾时各
        // 章都已拼过，hasChapterAudio 会直接跳过，不再是「全部跑完再拼」。
        val chapterSegIndices = LinkedHashMap<Int, MutableList<Int>>()
        segChapter.forEachIndexed { idx, ch ->
            chapterSegIndices.getOrPut(ch) { mutableListOf() }.add(idx)
        }
        val chapterLastIndex = chapterSegIndices.mapValues { (_, idxs) -> idxs.last() }
        val chapterAtBoundary = chapterLastIndex.entries.associate { (ch, last) -> last to ch }
        val mergedThisRun = mutableSetOf<Int>()
        val deferredChapters = mutableSetOf<Int>()
        var chaptersMade = 0
        var chaptersSkipped = 0
        // 该章是否还有「小块重做」没消化：有就先别拼，等插队重做完再拼，
        // 否则旧声音被拼进成品就再也改不回来。
        fun chapterRedoLeft(ci: Int): Boolean = SegPlan.pending(work).any { key ->
            val j = key.removePrefix("seg:").toIntOrNull() ?: return@any false
            j in toSpeak.indices && j !in handledRedo &&
                chapterSegIndices[ci]?.contains(j) == true &&
                !SegDrops.matches(dropSnap, dropWav(j), dropText(j))
        }
        fun tryMergeChapter(ci: Int, at: Int) {
            if (ci in mergedThisRun || cancelRequested || spaceStopped) return
            if (chapterRedoLeft(ci)) { deferredChapters.add(ci); return }
            val app = appCtx
            val already = doneChapterFiles[ci]?.let { it.isFile && it.length() > 44 } == true ||
                (app != null && AudioLibrary.hasChapterAudio(app, book.id, ci))
            if (already) {
                mergedThisRun.add(ci); deferredChapters.remove(ci); chaptersSkipped++
                return
            }
            if (app == null) return
            val parts = mutableListOf<File>()
            var allOk = true
            for (j in chapterSegIndices[ci].orEmpty()) {
                val wn = dropWav(j)
                if (SegDrops.matches(dropSnap, wn, dropText(j))) continue
                val f = File(work, wn)
                if (!f.isFile || f.length() <= 44L) { allOk = false; break }
                parts.add(f)
            }
            if (!allOk || parts.isEmpty()) {
                deferredChapters.add(ci)
                EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_DEFER",
                    "ch=${ci + 1} · 有段还没好，先留着小块等收尾补")
                return
            }
            // 拼好即清标记：这一章已合成，就没有「小块重做」这回事了
            chapterSegIndices[ci].orEmpty().forEach { j -> SegPlan.clearRedo(work, "seg:$j") }
            val r = AudioOps.mergeChapterAuto(app, book.id, book.title, ci, parts,
                gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c))
            if (r.ok) {
                mergedThisRun.add(ci); deferredChapters.remove(ci); chaptersMade++
                emit("正在生成声音", at + 1, annotated.size, gateFails + synthFails,
                    "第 ${ci + 1} 章已拼好，接着做下一章",
                    chapterProgress = _state.value.chapterProgress)
            } else {
                deferredChapters.add(ci)
                EventLog.log(EventLog.Kind.PIPELINE, "CHAPTER_MERGE_DEFER",
                    "ch=${ci + 1} · " + r.reason)
            }
        }
        // R235 · 每段实时读一次 Prefs。音色分套（voiceStamp / cacheKey /
        // VoiceCache.switchTo）仍锁在开跑时那一次，绝不因运行中改设置换套、
        // 不删已做好的段、不把进度归零 —— R220 硬口径不破。
        data class LiveSeg(val text: String, val speaker: String,
                           val params: SynthInput.Params, val knobs: TtsKnobs)
        fun liveSeg(i: Int): LiveSeg {
            val raw = annotated[i]
            val perf = Prefs.defaultPerf(c)
            val voice = Prefs.voiceDescription(c)
            val base = if (Prefs.nonVerbal(c)) NonVerbal.apply(raw) else raw
            val style = perf.styleInstruct()
            val built = VoiceProfile.buildWithReport(voice, style, base.instruct)
            return LiveSeg(base.text, base.speaker,
                SynthInput.params(c, built.first, style), Prefs.ttsKnobs(c))
        }
        // 缓存上一份实时参数：重做 / 重试同一段时不重复读盘
        var lastLive: LiveSeg? = null
        // R278 · 1.1.31 真机日志实证：续跑恢复时前 300+ 段逐条打
        // 「段 N 已有音频，跳过」，详细日志被同一句话撑到 1MB（事件 266554 / 详细 1010942 字节），
        // 真正有用的合成与报错全被淹掉。改成按窗口汇总：跳过原因（wav / chapter）、
        // 首尾段号、条数一条不少，日志从 O(N) 降到 O(N/500)。
        // 「已手工删除」是用户行为证据，仍逐条保留，不并入汇总。
        var skipWav = 0
        var skipChapter = 0
        var skipFirst = -1
        var skipLast = -1
        fun flushSkip() {
            val n = skipWav + skipChapter
            if (n <= 0) return
            EventLog.log(EventLog.Kind.PIPELINE, "TTS_SKIP",
                "跳过 $n 段已有音频 · 段 $skipFirst~$skipLast · wav=$skipWav chapter=$skipChapter",
                level = EventLog.Level.TRACE,
                fields = mapOf("src" to "summary"))
            skipWav = 0
            skipChapter = 0
            skipFirst = -1
            skipLast = -1
        }
        try {
            for ((i, a) in toSpeak.withIndex()) {
                val live = liveSeg(i)
                if (newlyWritten >= budgetSegs) { spaceStopped = true; break }
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val segStart = wavs.size
                val wavName = dropWav(i)
                val wav = File(work, wavName)
                // R233 · 手工删掉的段：不合成、不复用、也不进整本/整章拼接。
                // 进度仍往前走，避免「做了半天数字不动」的错觉。
                if (SegDrops.matches(dropSnap, wavName, dropText(i))) {
                    EventLog.log(EventLog.Kind.PIPELINE, "TTS_SKIP",
                        "段 ${i + 1}/${toSpeak.size} 已手工删除，不参与合成与拼接",
                        level = EventLog.Level.TRACE,
                        fields = mapOf("seg" to (i + 1).toString(), "src" to "dropped"))
                    emit("正在生成声音", i + 1, annotated.size, gateFails + synthFails,
                        "已生成 ${i + 1}/${annotated.size} 段声音",
                        chapterProgress = _state.value.chapterProgress)
                    // R297 · 标记后又被手工删掉的段不再合成，标记也不能永久留着：
                    // 否则目录页格子一直转黄、每秒读盘还一直带着它。
                    SegPlan.clearRedo(work, "seg:$i")
                    // R300 · 章末最后一段正好被删掉时也不能漏了拼这一章
                    chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }
                    deferredChapters.toList().forEach { tryMergeChapter(it, i) }
                    continue
                }
                val ci = segChapter.getOrElse(i) { -1 }
                val doneWav = if (ci >= 0) doneChapterFiles[ci] else null
                val covered = doneWav != null && doneWav.isFile && doneWav.length() > 44 &&
                    !(wav.exists() && wav.length() > 44)
                val already = wav.exists() && wav.length() > 44
                // R235 · 运行中也可能刚标记重做，逐段重读 redo.json
                val redoSeg = "seg:$i" in SegPlan.pending(work)
                val skip = (covered || already) && !redoSeg
                // R157 · 第二份真机日志：每次「继续制作」都从 TTS_INPUT 段 1/88 打起，
                // 看起来像从头重算，实际前 9 段走的是下面这个「已有音频」跳过分支。
                // 把日志和前台通知挪到真正要合成的段上；跳过段只记一条精简 SKIP。
                // 用户之前报过「愣住、不知道后面进哪张」，这条日志误导也占一份。
                if (skip) {
                    // R278 · 不再逐段写盘；攒够一批或收尾时打一条汇总
                    if (covered) skipChapter++ else skipWav++
                    if (skipFirst < 0) skipFirst = i + 1
                    skipLast = i + 1
                    if (skipWav + skipChapter >= 500) flushSkip()
                } else {
                    ProduceService.touch(c.applicationContext,
                        "合成段 ${i + 1}/${toSpeak.size}")
                    val v = live.params
                    lastLive = live
                    // R257 · 1.1.30 真机日志实证：这里原来把正文前 50 字与
                    // instruct（音色描述/导演指令）原文写进日志，R225 的「零落盘」
                    // 漏了这一处。全部改长度 + 短哈希，排查仍够用（段号、字数、
                    // 指令长度、音色是否变化），但不再落用户内容。
                    EventLog.log(EventLog.Kind.ENGINE, "TTS_INPUT",
                        "段 ${i + 1}/${toSpeak.size} · " +
                            "text=${EventLog.safe(live.text)} · " +
                            "instruct=${EventLog.safe(v.instruct)} · " +
                            "speaker=${EventLog.safe(live.speaker)}",
                        level = EventLog.Level.TRACE,
                        fields = mapOf("seg" to (i + 1).toString(),
                            "textChars" to live.text.length.toString(),
                            "instructChars" to (v.instruct?.length ?: 0).toString(),
                            "lang" to "chinese", "mode" to ModelStore.currentMode(c).id,
                            "speakerParam" to EventLog.safe(v.speaker)))
                    // R235 · 参数只给真正合成的那一段用；运行中改设置下一段即生效
                    lastParams = v
                }
                if (skip) {
                    if (covered) {
                        if (usedDoneChapters.add(ci)) {
                            wavs.add(doneWav!!)
                            segStartBytes.add(cursorBytes)
                            cursorBytes += (doneWav.length() - 44) + gapBytes
                        } else {
                            segStartBytes.add(cursorBytes)
                        }
                    } else {
                        wavs.add(wav)
                        segStartBytes.add(cursorBytes)
                        cursorBytes += (wav.length() - 44) + gapBytes
                    }
                    SegPlan.clearRedo(work, "seg:$i")
                } else {
                    val segT0 = System.currentTimeMillis()
                    var ok = false
                    var lastCode = -1
                    for (attempt in 0..1) {
                        if (cancelRequested) {
                            _state.value = halted(i, annotated.size, gateFails + synthFails)
                            return
                        }
                        val v = lastParams ?: live.params
                        val cur = lastLive ?: live
                        val code = try {
                            NativeEngine.ttsSynthesize(ttsHandle, cur.text, v.instruct, v.speaker, "chinese",
                                wav.absolutePath, cur.knobs)
                        } catch (t: Throwable) {
                            EventLog.log(EventLog.Kind.ENGINE, "TTS_ERROR", t.message ?: "")
                            -99
                        }
                        lastCode = code
                        if (code == 0 && wav.exists() && wav.length() > 44) { ok = true; break }
                        wav.delete()
                    }
                    if (!ok) {
                        synthFails++
                        _state.value = Progress(false, "没完成", i, annotated.size,
                            gateFails + synthFails,
                            "第 ${i + 1} 段没生成声音（${SynthErrors.reason(lastCode, false)}）；" +
                                "前面 ${wavs.size} 段都留着，" +
                                "点「继续制作」从这一段接着做，不会重算切分")
                        EventLog.log(EventLog.Kind.PIPELINE, "TTS_SEG_ABORT",
                            "seg=${i + 1} code=$lastCode kept=${wavs.size}")
                        return
                    }
                    if (cancelRequested) {
                        // 停止发生在这段推理途中：这段结果不要了，直接收尾
                        try { wav.delete() } catch (_: Throwable) { }
                        _state.value = halted(wavs.size, annotated.size, gateFails + synthFails)
                        return
                    }
                    wavs.add(wav)
                    newlyWritten++
                    segCostSum += (System.currentTimeMillis() - segT0).coerceAtLeast(0L)
                    segCostCount++
                    SegPlan.clearRedo(work, "seg:$i")
                    segStartBytes.add(cursorBytes)
                    cursorBytes += (wav.length() - 44) + gapBytes
                }
                val chapterSnapshot = if (wavs.size > segStart) {
                    val ci = segChapter.getOrElse(i) { -1 }
                    if (ci >= 0) perChapterDone[ci] = (perChapterDone[ci] ?: 0) + 1
                    perChapterDone.mapValues { (k, v) ->
                        (v.toFloat() / (perChapterTotal[k] ?: 1).coerceAtLeast(1).toFloat())
                            .coerceIn(0f, 1f)
                    }
                } else _state.value.chapterProgress
                val left = annotated.size - (i + 1)
                val avg = if (segCostCount > 0) segCostSum / segCostCount else 0L
                val eta = if (avg > 0L && left > 0) avg * left else 0L
                emit("正在生成声音", i + 1, annotated.size, gateFails + synthFails,
                    "已生成 ${i + 1}/${annotated.size} 段声音" +
                        if (eta > 0L) " · 按当前速度还需约 ${formatEta(eta)}" else "",
                    chapterProgress = chapterSnapshot)
                // R266 · 段级重做就地接上：不排「整章新任务」进队列（那样得等整本
                // 跑完才轮到，任务一停队列还会永久暂停），也不追加到 wavs，
                // 直接覆盖同一个文件，拼接读到的就是新声音。
                // R297 · 旧做法把整批 backlog 在一段收尾后一次性同步跑完：
                // N 段 × 几分钟全堵在这里，进度钉住、通知不刷、唤醒锁 30 分钟
                // 不 touch 被释放，真机上就是「点了重做就卡死、不往前推进」。
                // 现在每段收尾只插队做标记里最早的那一个，做完立刻 emit 一次
                // 回主循环，done 继续涨、通知与浮窗继续动。
                val nextRedo = SegPlan.pending(work)
                    .filter { it.startsWith("seg:") }
                    .mapNotNull { it.removePrefix("seg:").toIntOrNull() }
                    .filter { j -> j <= i && j in toSpeak.indices && j !in handledRedo &&
                        segChapter.getOrElse(j) { -1 } !in mergedThisRun &&
                        !SegDrops.matches(dropSnap, dropWav(j), dropText(j)) }
                    .minOrNull()
                if (nextRedo != null && !cancelRequested) {
                    handledRedo.add(nextRedo)
                    emit("正在重做小块", i + 1, annotated.size, gateFails + synthFails,
                        "正在重做段 ${nextRedo + 1} · 做完立刻接着往前做",
                        chapterProgress = chapterSnapshot)
                    // 重做同样要喂唤醒锁，否则连续重做超过 UNIT_TIMEOUT_MS
                    // 锁被系统释放，推理会挂在半路。
                    ProduceService.touch(c.applicationContext, "重做段 ${nextRedo + 1}")
                    val wj = File(work, dropWav(nextRedo))
                    val lj = liveSeg(nextRedo)
                    val code = try {
                        NativeEngine.ttsSynthesize(ttsHandle, lj.text,
                            lj.params.instruct, lj.params.speaker, "chinese",
                            wj.absolutePath, lj.knobs)
                    } catch (t: Throwable) { -99 }
                    if (code == 0 && wj.exists() && wj.length() > 44) {
                        SegPlan.clearRedo(work, "seg:$nextRedo")
                        EventLog.log(EventLog.Kind.PIPELINE, "SEG_REDO_DONE",
                            "seg=${nextRedo + 1} · ${wj.length()} 字节")
                    } else {
                        // 失败保留旧 wav 与标记，不删能听的那一份
                        EventLog.log(EventLog.Kind.PIPELINE, "SEG_REDO_FAIL",
                            "seg=${nextRedo + 1} code=$code 留待下次续跑")
                    }
                    // 切回主阶段：stage 变化会驱动目录页重读 redo.json，
                    // 格子由琥珀变回绿色，不用等整本跑完。
                    emit("正在生成声音", i + 1, annotated.size, gateFails + synthFails,
                        "已重做 1 个选中的小块，继续往前做",
                        chapterProgress = chapterSnapshot)
                }
                // R300 · 本段收尾后看章边界：这一章小块齐了就立刻拼成章音频、
                // 清掉小块，主循环接着做下一章，不等整本跑完。
                chapterAtBoundary[i]?.let { tryMergeChapter(it, i) }
                deferredChapters.toList().forEach { tryMergeChapter(it, i) }
            }
            if (spaceStopped) {
                _state.value = Progress(false, "没完成", wavs.size, annotated.size,
                    gateFails + synthFails,
                    "空间用完了，已做好 ${wavs.size} 段并都留着；清出空间后点「继续制作」从这接着做")
                EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_PARTIAL_STOP",
                    "kept=${wavs.size} newly=$newlyWritten total=${annotated.size} free=$freeBytes")
                return
            }
        } finally {
            // R278 · 尾巴不足一批的跳过段也别丢：return / break / 异常各出口都走这里
            try { flushSkip() } catch (_: Throwable) { }
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
        }
        if (cancelRequested) {
            _state.value = halted(wavs.size, annotated.size, gateFails + synthFails)
            return
        }

        emit("正在按章整理", annotated.size, annotated.size, gateFails + synthFails,
            "正在把各章的小块拼成章音频…")
        // R272 · 用户口径：一本书拼成一份整本.wav 会把手机撑爆，不要整本大文件。
        // 唯一成品形态是「一章一个音频」：主循环已在每章边界 mergeChapterAuto
        // 逐章拼好（R300），这里只补漏——边界没拼成的章才进 byCh，
        // force=true 保证关掉「自动拼成整章」也必须出章成品。
        // 旧版已落在磁盘的整本.wav 不主动删、list() 仍兼容读取，只是不再新生成。
        val c = appCtx
        val byCh = LinkedHashMap<Int, MutableList<File>>()
        for (i in 0 until toSpeak.size) {
            val wavName = dropWav(i)
            // R233 · 用户删掉的段本来就不该拼进来，也不算「缺块」
            if (SegDrops.matches(dropSnap, wavName, dropText(i))) continue
            val ci = segChapter.getOrElse(i) { -1 }
            if (ci < 0) continue
            // R300 · 这章已在边界拼好并清了小块，收尾不再重复扫它
            if (ci in mergedThisRun) continue
            byCh.getOrPut(ci) { mutableListOf() }
                .add(File(work, wavName))
        }
        val merged = if (c != null) AudioOps.ensureChapterAudios(c, book.id, book.title, byCh,
            gapMs = config.gapMs, declickMs = Prefs.mergeDeclickMs(c), force = true)
        else 0 to 0
        // R300 · 章边界即时拼的 + 收尾补漏的，才是本次真正做出的章音频数
        val totalMade = chaptersMade + merged.first
        val regWarn = AudioLibrary.writeError
        val outDir = if (c != null)
            AudioLibrary.bookDir(c, book.id, book.title).absolutePath else null
        _state.value = Progress(false, "完成", annotated.size, annotated.size,
            gateFails + synthFails,
            if (regWarn != null) "音频已生成，但记录没写上：$regWarn；文件仍在「我的音频」里"
            else if (totalMade > 0) "做好了，${totalMade} 个章节音频在「我的音频」里"
            else "做好了，可以去「我的音频」听", outDir)
        val t = current
        if (c != null && t != null) {
            val done = t.copy(phase = "done", stage = "完成",
                total = annotated.size, done = annotated.size,
                updatedAt = System.currentTimeMillis(), output = outDir)
            current = done
            TaskResume.save(c, done)
            _resume.value = done
        }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_DONE",
            "chapters=$totalMade immediate=$chaptersMade late=${merged.first} " +
                "skipped=${merged.second} segs=${annotated.size}")
    }
}
