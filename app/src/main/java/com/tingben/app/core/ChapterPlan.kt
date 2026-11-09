package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

// R138 · 批次 B 地基：章节表是「一本书一份」的落盘真值源。
//
// 为什么必须有它：1.1.2 里 ChapterSplitter.split() 被 4 个地方各自重算，
// 而音频索引 / 断点 / 阅读位置全都按「章 index」存。规则一改，章号重新编号，
// 已生成音频会整体对错章。现在把 ruleId + sha + chapters 落盘并复用：
//   · 老书（已有成品或任务）自动沿用 legacy_v2 规则，章号与 1.1.2 完全一致，
//     既不毁数据，旧任务也能安全续跑；
//   · 新书按规则集自动选优；
//   · 只有用户显式「重新分章」才换规则，界面明确提示章号会变、音频要重做。
// 章节表跟书放在同一个 books/ 目录，删书时一并清掉。
data class ChapterPlanData(
    val schema: Int,
    val ruleId: String,
    val sha: String,
    val chapters: List<Chapter>,
    /** R172 · 章节表代数：只有用户显式「重新分章」才 +1，任务据此判失效。 */
    val epoch: Int = 0
)

object ChapterPlan {

    const val EPUB = "epub"

    private fun file(c: Context, bookId: String) =
        File(c.filesDir, "books/$bookId.chapters.json")

    private fun fingerprint(text: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Throwable) { "len_${text.length}_${text.hashCode()}" }

    fun load(c: Context, bookId: String): ChapterPlanData? = try {
        val r = AtomicJson.read(file(c, bookId))
        val body = if (r is AtomicJson.Read.Ok) r.text else return null
        val o = JSONObject(body)
        val arr = o.optJSONArray("chapters") ?: return null
        val list = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            Chapter(it.optInt("index", i), it.optString("title", "未命名章节"),
                it.optInt("start", 0), it.optInt("end", 0))
        }
        if (list.isEmpty()) null else
            ChapterPlanData(o.optInt("schema", 0), o.optString("ruleId", ""),
                o.optString("sha", ""), list, o.optInt("epoch", 0))
    } catch (_: Throwable) { null }

    private fun save(c: Context, bookId: String, plan: ChapterPlanData) {
        try {
            val a = JSONArray()
            plan.chapters.forEach { ch ->
                a.put(JSONObject().put("index", ch.index).put("title", ch.title)
                    .put("start", ch.start).put("end", ch.end))
            }
            val ok = AtomicJson.write(file(c, bookId), JSONObject()
                .put("schema", plan.schema).put("ruleId", plan.ruleId)
                .put("sha", plan.sha).put("chapters", a)
                .put("epoch", plan.epoch).toString())
            if (!ok) UiErrors.storage("章节表没写进磁盘，下次进目录页会重新识别", null)
        } catch (t: Throwable) {
            UiErrors.storage("章节表写入失败，下次进目录页会重新识别", t)
        }
    }

    fun delete(c: Context, bookId: String) {
        try { AtomicJson.delete(file(c, bookId)) } catch (_: Throwable) { }
    }

    /** 老书判定：已有成品或已有任务，就必须沿用旧规则，不能悄悄换章号。 */
    private fun hasLegacyData(c: Context, bookId: String): Boolean = try {
        val hasAudio = AudioLibrary.list(c).any { it.bookId == bookId }
        val hasTask = TaskResume.byBook(c, bookId) != null
        hasAudio || hasTask
    } catch (_: Throwable) { false }

    /**
     * 统一入口：四个调用点（目录页 / 制作准备页 / 制作运行 / 断点校验）全走这里，
     * 保证同一本书在任何地方看到的章号完全一致。
     */
    /**
     * I3 · 首章标题之前的文本（站点页眉、下载网址、版权信息、目录页）。
     *
     * 纠正一条自己排错的任务：原计划「并进首章」，实际不能这么做 ——
     * 用户日志明确抱怨过「开头把下载网址、邮箱、章节内容开始当正文念了」，
     * 并进去会让 TTS 念广告；而且改首章 start 会挪动全部章区间，
     * 已生成音频的章起点索引与断点全部对错章（破坏性）。
     *
     * 所以保持「不进任何章」，只把静默丢弃变成可见诊断：让读者知道
     * 书前那段是出版/站点信息、本来就不该朗读，而不是软件漏读了。
     */
    data class FrontInfo(val chars: Int, val noisy: Boolean)

    private val siteNoise = Regex(
        """https?://|www\.|@[\w.-]+\.[A-Za-z]{2,}|下载|更多电子书|章节内容开始|""" +
        """最新网址|请收藏|电子书|TXT下载|论坛|QQ群|微信公众号""",
        RegexOption.IGNORE_CASE)

    fun frontMatter(text: String, chapters: List<Chapter>): FrontInfo {
        val end = chapters.firstOrNull()?.start ?: 0
        if (end <= 0) return FrontInfo(0, false)
        val head = text.substring(0, end.coerceAtMost(text.length).coerceAtMost(4096))
        return FrontInfo(end, siteNoise.containsMatchIn(head))
    }

    fun resolve(c: Context, book: Book): Pair<List<Chapter>, List<Chunk>> =
        resolve(c, book, BookStore.read(c, book))

    /** 制作运行时已经读过文本，复用它，别再读一次 64MB。 */
    fun resolve(c: Context, book: Book, text: String): Pair<List<Chapter>, List<Chunk>> {
        if (text.length > BookStore.MAX_TEXT_CHARS)
            throw IllegalStateException(
                "这本书太长（${text.length / 10000} 万字），手机上读不动；先拆成几本再导入")
        val sha = if (book.sha.isNotBlank()) book.sha else fingerprint(text)
        val cached = load(c, book.id)
        if (cached != null && cached.schema == TocRules.SCHEMA &&
            (cached.sha == sha || cached.sha.isBlank()) && cached.ruleId.isNotBlank()) {
            return cached.chapters to ChapterSplitter.cutFrom(text, cached.chapters)
        }
        val (ruleId, chapters) = build(text, if (hasLegacyData(c, book.id)) TocRules.LEGACY else null)
        // R173 · 表被删 / 损坏后重建时保留代数，不能悄悄退回 0：
        // 否则「重新分章过」的书会与旧任务 planEpoch 又对上，失效判定失效。
        val keptEpoch = load(c, book.id)?.epoch ?: 0
        save(c, book.id, ChapterPlanData(TocRules.SCHEMA, ruleId, sha, chapters, keptEpoch))
        return chapters to ChapterSplitter.cutFrom(text, chapters)
    }

    private fun build(text: String, forcedRule: String?): Pair<String, List<Chapter>> {
        if (text.contains(Importer.CHAPTER_SEP)) {
            // EPUB 走结构化分隔，一个字不改、完全不经过正则
            val chapters = ChapterSplitter.split(text).first
            return EPUB to chapters
        }
        val ruleId = forcedRule ?: TocRules.choose(text).first
        val chapters = ChapterSplitter.fromMarks(text, TocRules.splitMarks(text, ruleId))
        return ruleId to chapters
    }

    /** 章节表代数；老书 / 无表一律 0，与老任务默认 0 一致，升级不误伤。 */
    fun epoch(c: Context, bookId: String): Int = load(c, bookId)?.epoch ?: 0

    /**
     * R172 · P1「重新分章后旧任务对错章」根治。
     *
     * 原来 refresh() 只覆盖章节表：任务 total / only 仍是旧章号，splitRule 新旧
     * 同为 3 不触发版本检查，invalidationReason 也没有章节表指纹 → 判定可续跑，
     * 旧章号去过滤新章节表，合成错章、进度分母与章起点索引全部错位。
     * 现在换表同时：任务标成「需重新确认」、队列项移除、work 下旧分段与
     * chapter_done / seg_chapters / chapters 索引一并作废（否则 direct_0000.wav
     * 会按段号复用成完全不同的内容）。成品整章音频不删，仍可听。
     */
    private fun invalidateForResplit(c: Context, bookId: String) {
        try {
            val t = TaskResume.byBook(c, bookId)
            if (t != null) {
                // 保留旧 planEpoch：invalidationReason 拿它和新表 epoch 一比就拦住
                TaskResume.save(c, t.copy(phase = "reconfirm", stage = "重新分章",
                    done = 0, total = 0, output = null))
            }
            TaskResume.dequeue(c, bookId)
        } catch (t: Throwable) {
            EventLog.log(EventLog.Kind.MODEL, "RESPLIT_TASK_FAIL",
                "任务作废失败 book=$bookId ${t.message ?: ""}")
        }
        try {
            val work = File(c.filesDir, "work/$bookId")
            if (work.exists()) work.deleteRecursively()
        } catch (t: Throwable) {
            EventLog.log(EventLog.Kind.MODEL, "RESPLIT_WORK_FAIL",
                "分段缓存没清干净 book=$bookId ${t.message ?: ""}")
        }
        EventLog.log(EventLog.Kind.MODEL, "RESPLIT_INVALIDATE",
            "书=$bookId 旧任务标待确认 / 队列移除 / 分段与章号索引作废")
    }

    /** 重新分章：用户显式操作。返回新章数；章号会变，调用方必须提示音频需重做。 */
    fun refresh(c: Context, book: Book, ruleId: String? = null): Pair<String, Int> {
        val text = BookStore.read(c, book)
        val sha = if (book.sha.isNotBlank()) book.sha else fingerprint(text)
        val nextEpoch = load(c, book.id)?.epoch?.plus(1) ?: 1
        val (rid, chapters) = build(text, ruleId ?: TocRules.choose(text).first)
        save(c, book.id, ChapterPlanData(TocRules.SCHEMA, rid, sha, chapters, nextEpoch))
        invalidateForResplit(c, book.id)
        return rid to chapters.size
    }

    /** 预览：章数 + 头 3 / 尾 2 标题，给用户确认再应用（成熟阅读器的做法）。 */
    fun preview(c: Context, book: Book, ruleId: String?): Pair<String, Pair<Int, List<String>>> {
        val text = BookStore.read(c, book)
        val rid = ruleId ?: TocRules.choose(text).first
        val marks = TocRules.splitMarks(text, rid)
        val titles = marks.map { it.second }
        val shown = if (titles.size <= 5) titles else
            titles.take(3) + listOf("…共 ${titles.size} 章…") + titles.takeLast(2)
        return rid to (marks.size to shown)
    }

    fun ruleName(ruleId: String): String = when (ruleId) {
        EPUB -> "EPUB 内置目录"
        TocRules.LEGACY -> "旧版规则（保留章号）"
        TocRules.BY_SIZE -> "按字数分节"
        else -> TocRules.byId(ruleId)?.name ?: ruleId
    }

    fun currentRuleId(c: Context, bookId: String): String =
        load(c, bookId)?.ruleId ?: ""
}
