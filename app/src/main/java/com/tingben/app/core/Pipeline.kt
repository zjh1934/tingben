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

// R157 · origin 只用于「导出时归到哪个文件夹」，不参与分章/合成/索引：
//   file  = 从 txt/md/epub 导入的书
//   input = 输入框粘贴 / 别的 App 分享文字建的书
// 老 books.json 没有这个字段，list() 用 optString 兜底成 file，零迁移。
data class Book(val id: String, val title: String, val path: String, val chars: Int,
                val sha: String = "", val origin: String = "file")
data class Chapter(val index: Int, val title: String, val start: Int, val end: Int)
data class Chunk(val index: Int, val chapter: Int, val text: String, val start: Int, val end: Int)
data class Ann(val text: String, val speaker: String, val instruct: String)

// @core-insert: BookStore
// @core-insert: ModelStore
// @core-insert: ChapterSplitter
// @core-insert: Annotator
// @core-insert: AnnCache
// @core-insert: WavMerger
data class Progress(
    val running: Boolean = false,
    val stage: String = "还没开始",
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0,
    val message: String = "",
    val output: String? = null,
    val chapterProgress: Map<Int, Float> = emptyMap()
)

// @core-insert: Producer
