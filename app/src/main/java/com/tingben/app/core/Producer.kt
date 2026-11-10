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
import java.util.UUID

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
            "book=${fixed.title} phase=${fixed.phase} real=$real/${fixed.total}" +
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
                EventLog.log(EventLog.Kind.PIPELINE, "QUEUE_SKIP", "书没了 book=${t.title}")
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

    fun resumeQueue(c: Context): String? {
        queuePaused = false
        TaskResume.setQueuePaused(c, false)
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
                "book=${t.title} phase=${t.phase} reason=$why")
            return why
        }
        val book = BookStore.list(c).find { it.id == t.bookId }
            ?: return "这本书已从书架删掉，没法续跑"
        start(c, book, t.only, true, t.think, t.config)
        if (lastStartError != null) return lastStartError
        EventLog.log(EventLog.Kind.PIPELINE, "TASK_AUTO_RESUME",
            "book=${t.title} phase=${t.phase} done=${t.done}/${t.total}")
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
                " · 本次预设=" + config.summary())
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
        if (stageChanged || now - lastNotifyMs >= PERSIST_INTERVAL_MS) {
            appCtx?.let { ctx ->
                // R217 · 取「章号最大那一章」的进度给悬浮窗下球。
                // R214 原来比对的是 value（取所有章里进度最大的），做到第 2 章
                // 时下球还停在第 1 章的 100%，看着像卡住。比对 key 才是当前章。
                // 不用 maxByOrNull 之类版本相关 API，纯循环最稳。
                var chPct = -1f
                var chIdx = -1
                for ((k, v) in chapterProgress) if (k > chIdx) { chIdx = k; chPct = v }
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
            "chunks=${chunks.size} · 预设=" + config.summary())
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
            config.summary() + " · 指纹=" + presetStamp)

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
        val toSpeak = annotated.map { ann ->
            val base = if (nonVerbalOn) NonVerbal.apply(ann) else ann
            val built = VoiceProfile.buildWithReport(globalVoice, config.styleInstruct(), base.instruct)
            strippedTotal += built.second
            Ann(base.text, base.speaker, built.first)
        }
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
        val pending = (0 until toSpeak.size).count { i ->
            val f = File(work, String.format("direct_%04d.wav", i))
            if (f.exists() && f.length() > 44) return@count false
            val ci = segChapter.getOrElse(i) { -1 }
            val covered = ci >= 0 && doneChapterFiles[ci]
                ?.let { it.isFile && it.length() > 44 } == true
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
        try {
            for ((i, a) in toSpeak.withIndex()) {
                if (newlyWritten >= budgetSegs) { spaceStopped = true; break }
                if (cancelRequested) {
                    _state.value = halted(i, annotated.size, gateFails + synthFails)
                    return
                }
                val segStart = wavs.size
                val wav = File(work, String.format("direct_%04d.wav", i))
                val ci = segChapter.getOrElse(i) { -1 }
                val doneWav = if (ci >= 0) doneChapterFiles[ci] else null
                val covered = doneWav != null && doneWav.isFile && doneWav.length() > 44 &&
                    !(wav.exists() && wav.length() > 44)
                val already = wav.exists() && wav.length() > 44
                val skip = covered || already
                // R157 · 第二份真机日志：每次「继续制作」都从 TTS_INPUT 段 1/88 打起，
                // 看起来像从头重算，实际前 9 段走的是下面这个「已有音频」跳过分支。
                // 把日志和前台通知挪到真正要合成的段上；跳过段只记一条精简 SKIP。
                // 用户之前报过「愣住、不知道后面进哪张」，这条日志误导也占一份。
                if (skip) {
                    EventLog.log(EventLog.Kind.PIPELINE, "TTS_SKIP",
                        "段 ${i + 1}/${toSpeak.size} 已有音频，跳过",
                        level = EventLog.Level.TRACE,
                        fields = mapOf("seg" to (i + 1).toString(),
                            "src" to if (covered) "chapter" else "wav"))
                } else {
                    ProduceService.touch(c.applicationContext,
                        "合成段 ${i + 1}/${toSpeak.size}")
                    val v = SynthInput.params(c, a.instruct, config.styleInstruct())
                    EventLog.log(EventLog.Kind.ENGINE, "TTS_INPUT",
                        "段 ${i + 1}/${toSpeak.size} [${a.speaker}] " +
                            a.text.take(50).replace('\n', '⏎') + " → " + (v.instruct ?: "（无指令）"),
                        level = EventLog.Level.TRACE,
                        fields = mapOf("seg" to (i + 1).toString(), "speaker" to a.speaker,
                            "textChars" to a.text.length.toString(),
                            "instructChars" to (v.instruct?.length ?: 0).toString(),
                            "lang" to "chinese", "mode" to ModelStore.currentMode(c).id,
                            "speakerParam" to (v.speaker ?: "null")))
                    // 参数只给真正合成的那一段用
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
                        val v = lastParams
                            ?: SynthInput.params(c, a.instruct, config.styleInstruct())
                        val code = try {
                            NativeEngine.ttsSynthesize(ttsHandle, a.text, v.instruct, v.speaker, "chinese",
                                wav.absolutePath, knobs)
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
            try { NativeEngine.ttsFreeGated(ttsHandle) } catch (_: Throwable) { }
        }
        if (cancelRequested) {
            _state.value = halted(wavs.size, annotated.size, gateFails + synthFails)
            return
        }

        emit("正在整理音频", annotated.size, annotated.size, gateFails + synthFails, "正在把各段接成一个音频…")
        val outFile = File(AudioLibrary.dir(c), "${UUID.randomUUID()}.wav")
        val finalBytes = wavs.sumOf { it.length() } + 16L * 1024 * 1024
        if (finalBytes > outFile.parentFile!!.usableSpace) {
            _state.value = Progress(false, "没完成", annotated.size, annotated.size,
                gateFails + synthFails,
                "存储空间不够，音频接不起来；已生成的 ${wavs.size} 段都留着，" +
                    "清出空间后点「继续制作」")
            EventLog.log(EventLog.Kind.PIPELINE, "STORAGE_SHORT_MERGE",
                "need=$finalBytes free=${outFile.parentFile!!.usableSpace} parts=${wavs.size}")
            return
        }
        val ok = WavMerger.merge(wavs, outFile, config.gapMs)
        if (!ok || outFile.length() <= 44) {
            outFile.delete()
            File(outFile.parentFile, outFile.name + ".part").delete()
            _state.value = Progress(false, "没完成", annotated.size, annotated.size,
                gateFails + synthFails, "没能生成音频，可以回书架重新合成这一本")
            return
        }
        val item = AudioLibrary.register(c, book.id, book.title, only, direct, outFile, chapters.size)
        val regWarn = AudioLibrary.writeError
        EventLog.log(EventLog.Kind.PIPELINE, "AUDIO_REGISTER",
            "${item.title} · ${item.scope} · ${item.mode} · ${item.bytes} 字节")
        try {
            val chJson = JSONArray()
            val firstOffset = LinkedHashMap<Int, Long>()
            segStartBytes.forEachIndexed { si, off ->
                val ci = segChapter.getOrElse(si) { -1 }
                if (ci >= 0 && !firstOffset.containsKey(ci)) firstOffset[ci] = off / 48L
            }
            chapters.forEach { ch ->
                chJson.put(JSONObject().put("index", ch.index).put("title", ch.title)
                    .put("startMs", firstOffset[ch.index] ?: 0L))
            }
            AtomicJson.write(File(outFile.parentFile,
                "${outFile.nameWithoutExtension}.chapters.json"), chJson.toString())
        } catch (_: Throwable) { }

        _state.value = Progress(false, "完成", annotated.size, annotated.size,
            gateFails + synthFails,
            if (regWarn != null) "音频已生成，但记录没写上：$regWarn；文件仍在「我的音频」里"
            else "做好了，可以去「我的音频」听", outFile.absolutePath)
        val c = appCtx
        val t = current
        if (c != null && t != null) {
            val done = t.copy(phase = "done", stage = "完成",
                total = annotated.size, done = annotated.size,
                updatedAt = System.currentTimeMillis(), output = outFile.absolutePath)
            current = done
            TaskResume.save(c, done)
            _resume.value = done
        }
        EventLog.log(EventLog.Kind.PIPELINE, "PRODUCE_DONE", "${outFile.length()} 字节")
    }
}
