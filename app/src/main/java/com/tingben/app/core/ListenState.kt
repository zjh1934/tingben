package com.tingben.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * R180 · 断点续听：整章 / 整本播放队列的上次位置。
 *
 * 原来播放位置只活在 PlayQueue 的内存 StateFlow 里，退出软件、被系统回收、
 * 切去别本书再回来，一律从头开始。对一本几小时的有声书，这是最扎心的缺口。
 * ReadingState 存的是「读到第几章第几段」，不是「听到这一章的第几秒」，两者不混。
 *
 * key 用队列 id（整章队列恒为 `chapter:<bookId>`），value 记曲目 id + 毫秒位置
 * + 书名章名。曲目 id 是音频文件绝对路径，删掉成品后 indexOfFirst 找不到就
 * 自然退回第 1 章，不会报错也不会跳到错的章。
 * 试听队列（seg / trial）不落盘。
 */
object ListenState {
    private fun file(c: Context) = File(c.filesDir, "listen_state.json")

    data class Pos(val trackId: String, val positionMs: Long, val label: String)

    fun get(c: Context, queueId: String): Pos? = try {
        when (val r = AtomicJson.read(file(c))) {
            is AtomicJson.Read.Ok -> {
                val o = JSONObject(r.text).optJSONObject(queueId) ?: return null
                val id = o.optString("trackId", "")
                val ms = o.optLong("positionMs", 0L)
                if (id.isEmpty() || ms <= 0L) null
                else Pos(id, ms, o.optString("label", ""))
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    fun save(c: Context, queueId: String, trackId: String, positionMs: Long, label: String) {
        if (queueId.isEmpty() || trackId.isEmpty() || positionMs < 0L) return
        try {
            val f = file(c)
            val o = when (val r = AtomicJson.read(f)) {
                is AtomicJson.Read.Corrupt -> return   // 坏了绝不覆写
                is AtomicJson.Read.Missing -> JSONObject()
                is AtomicJson.Read.Ok -> try { JSONObject(r.text) } catch (_: Throwable) { return }
            }
            o.put(queueId, JSONObject()
                .put("trackId", trackId).put("positionMs", positionMs)
                .put("label", label))
            AtomicJson.write(f, o.toString())
        } catch (_: Throwable) { }
    }

    fun clear(c: Context, queueId: String) {
        if (queueId.isEmpty()) return
        try {
            val f = file(c)
            val o = when (val r = AtomicJson.read(f)) {
                is AtomicJson.Read.Corrupt -> return
                is AtomicJson.Read.Missing -> return
                is AtomicJson.Read.Ok -> try { JSONObject(r.text) } catch (_: Throwable) { return }
            }
            if (o.remove(queueId) != null) AtomicJson.write(f, o.toString())
        } catch (_: Throwable) { }
    }
}
