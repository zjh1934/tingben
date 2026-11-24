package com.tingben.app.core

import android.content.Context
import com.tingben.app.diag.EventLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * R387 · 请求文本留存（用户明确要求：「每次过来合成的请求，它的文本就留下」）。
 *
 * **只留文本，不留音频**：条目只有 id / 来源 / 正文 / 时间 / 字数，
 * 没有任何音频路径、字节、格式或中间产物字段。合成出来的 WAV / M4A 仍走
 * 原来的即用即删链路，一个都不进这里。
 *
 * **口径迁移（用户本轮推翻旧规，按「只留最新、不堆旧账」办）**：
 * R225 起「正文零落盘」指的是 **EventLog / 诊断摘要 / 诊断导出零正文**。
 * 本文件是用户主动要查看的留存区，是唯一例外：它躺在
 * filesDir（allowBackup=false 私有区，R175），**不进 events.log、不进
 * exportBundle / exportSummary、不进崩溃环形缓冲、不进任何一行日志**。
 * 排查仍靠埋点里的「来源 + 字数」，要看原文只能进设置页这个板块。
 *
 * 两个渠道分开：跨网中继（SRC_RELAY）与本机/局域网网关（SRC_GATEWAY），
 * UI 左右两列读各自 source。默认每列展示最近 10 条，可展开到上限 100 条；
 * 超过上限自动丢最老，避免一个长用的手机把私有区写胀。
 *
 * 调用方来自三个线程（中继轮询线程、网关 IO 线程池、Compose 主线程），
 * 全部走同一把锁 + 临时文件原子替换，不并发写坏 JSON。
 */
internal object RequestArchive {

    const val SRC_RELAY = "relay"
    const val SRC_GATEWAY = "gateway"
    const val DEFAULT_SHOW = 10
    const val MAX_KEEP = 100

    private const val FILE = "request_archive.json"
    private val lock = Any()

    data class Entry(
        val id: String,
        val source: String,
        val text: String,
        val ts: Long
    ) {
        val chars: Int get() = text.length
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("source", source).put("text", text).put("ts", ts)

        companion object {
            fun of(o: JSONObject): Entry? {
                val t = o.optString("text", "")
                val s = o.optString("source", "")
                if (t.isEmpty() || (s != SRC_RELAY && s != SRC_GATEWAY)) return null
                return Entry(o.optString("id", UUID.randomUUID().toString()), s,
                    t, o.optLong("ts", 0L))
            }
        }
    }

    private fun file(c: Context): File = File(c.filesDir, FILE)
    private fun tmp(c: Context): File = File(c.filesDir, FILE + ".tmp")

    private fun readAll(c: Context): MutableList<Entry> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                Entry.of(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (_: Throwable) {
            // 文件坏了不炸 App，也不把半截内容当真；下次 add 会重写一份干净的。
            mutableListOf()
        }
    }

    private fun writeAll(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val t = tmp(c)
        t.writeText(arr.toString(), Charsets.UTF_8)
        val f = file(c)
        if (f.exists() && !f.delete()) return
        if (!t.renameTo(f)) t.copyTo(f, overwrite = true)
    }

    /** 拿到一条请求就留：包括后来被判「空 / 超 1500 字 / 合成失败」的，排查才全。 */
    fun add(c: Context, source: String, text: String) {
        val n = synchronized(lock) {
            val all = readAll(c)
            all.add(Entry(UUID.randomUUID().toString(), source, text,
                System.currentTimeMillis()))
            // 两个渠道各自保上限，别让一边把另一边挤掉。
            for (s in listOf(SRC_RELAY, SRC_GATEWAY)) {
                val mine = all.filter { it.source == s }
                if (mine.size > MAX_KEEP) {
                    val drop = mine.sortedBy { it.ts }.take(mine.size - MAX_KEEP).toSet()
                    all.removeAll(drop)
                }
            }
            writeAll(c, all)
            all.size
        }
        // R381 埋点规则：只记来源与字数，正文一个字不落日志。
        EventLog.log(EventLog.Kind.PIPELINE, "ARCHIVE_ADD",
            "来源=$source · ${text.length}字 · 共 ${n} 条",
            fields = mapOf("source" to source, "chars" to text.length.toString()))
    }

    /** 某渠道的全部（新→旧）；UI 默认只取前 10 条。 */
    fun list(c: Context, source: String): List<Entry> = synchronized(lock) {
        readAll(c).filter { it.source == source }.sortedByDescending { it.ts }
    }

    fun show(c: Context, source: String, expanded: Boolean): List<Entry> {
        val all = list(c, source)
        return if (expanded) all else all.take(DEFAULT_SHOW)
    }

    fun counts(c: Context): Pair<Int, Int> = synchronized(lock) {
        val a = readAll(c)
        Pair(a.count { it.source == SRC_RELAY }, a.count { it.source == SRC_GATEWAY })
    }

    /** 一键复制：带来源与序号，多条之间空一行；选中态与整列都走这里。 */
    fun copyText(entries: List<Entry>): String = buildString {
        entries.forEachIndexed { i, e ->
            if (i > 0) append("\n\n")
            append("[").append(if (e.source == SRC_RELAY) "跨网中继" else "语音网关")
                .append(" · ").append(i + 1).append("] ").append(e.text)
        }
    }

    fun delete(c: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val n = synchronized(lock) {
            val all = readAll(c)
            val before = all.size
            all.removeAll { it.id in ids }
            writeAll(c, all)
            before - all.size
        }
        EventLog.log(EventLog.Kind.PIPELINE, "ARCHIVE_DEL", "删掉 $n 条")
        return n
    }

    fun clear(c: Context) {
        val n = synchronized(lock) {
            val all = readAll(c)
            val k = all.size
            writeAll(c, emptyList())
            k
        }
        EventLog.log(EventLog.Kind.PIPELINE, "ARCHIVE_CLEAR", "清空 $n 条")
    }
}
