package com.tingben.app.core

import android.content.Context
import java.io.File

/**
 * R256 · 试听临时文件的唯一落点与清理口。
 *
 * 用户口径：调音台试听 / 裁剪试听 / 首页即贴即听产生的文件「播完就丢」，
 * 这个目录也不用在设置里可达 —— 放 cacheDir，第三方文件管理器天然进不去，
 * 系统空间紧张时优先回收，不占 filesDir 的正式数据区。
 *
 * 三层互不相干：cacheDir/trial（试听，播完即丢）/ filesDir/work（中间小块，
 * 拼成整章后清）/ filesDir/audio（整章成品）。sweep 只清第一层，
 * 绝不碰后两层，也绝不碰 seg 通道的小块（那些 queueId 是 "seg"）。
 */
object TrialFiles {

    fun dir(c: Context): File = File(c.cacheDir, "trial").apply { mkdirs() }

    /** 试听文件名一律走这里，不许再拼到 filesDir 根上。 */
    fun file(c: Context, name: String): File = File(dir(c), name)

    /** 播完 / 取消 / 关窗都调它；只清试听目录，返回清掉的个数。 */
    fun sweep(c: Context): Int {
        var n = 0
        try {
            dir(c).listFiles()?.forEach { f ->
                if (f.isFile && f.delete()) n++
            }
        } catch (_: Throwable) { }
        return n
    }
}
