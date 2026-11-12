package com.tingben.app.core

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * R156 · 成品文件「能被文件管理器管到」的地基。
 *
 * 用户目标方案里说：「音频管理没必要了，设置里加一个入口指向音频文件保存的地方，
 * 点一下用系统文件管理器进去就行」。查证后这个做法**在 Android 上做不到**：
 * 成品落在 `c.filesDir/audio/`（/data/data/<pkg>/files/），是 App 私有目录，
 * App 没有存储权限、也没有注册 DocumentsProvider，第三方文件管理器无 root 进不去。
 *
 * 所以纠正成「导出到用户自己选的目录」：
 *   · 用系统 `ACTION_OPEN_DOCUMENT_TREE` 让用户挑一个文件夹（一般是 Download）
 *   · 记住这个位置（persistable URI permission），下次直接导出
 *   · 把每章成品 WAV 写进去 → 之后文件管理器、音乐 App 都能管
 *
 * R157 · 用户补的目录结构（第二份真机日志之后）：
 *   · 从 txt/md/epub 导入的书：**按书名各自建一个文件夹**
 *   · 输入框 / 分享文字建的书：统一放进 `输入朗读/` 这一层，再按书名分
 *   · 「导出」= 写进上面记住的默认目录；「另存为」= 单章用系统保存界面自选位置
 * 这样多本书不会混成一片，输入框来的零散音频也有自己的地方。
 *
 * **明确不做**：让用户配置内部 `work/` / `audio/` / 暂存目录路径。
 * `AudioTree.audio_tree.json` 里存的是成品绝对路径，`TaskResume` 的断点按
 * `work/{bookId}/{bucket}` 定位，改路径会让已做好的音频全部找不到、续跑重算。
 * 这是数据风险，不是「设置项不够多」。
 */
object AudioExport {

    private const val SP = "tingben_export"
    private const val KEY_TREE = "export_tree"
    /** 输入框 / 分享文字建的书统一归到这一层。 */
    const val INPUT_DIR = "输入朗读"

    /** dir 是相对导出根的路径，可能含 `/`；name 是该层里的文件名。 */
    data class Item(val dir: String, val name: String, val file: File)

    data class Result(
        val ok: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val reason: String = ""
    )

    private fun prefs(c: Context) =
        c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun treeUri(c: Context): Uri? =
        prefs(c).getString(KEY_TREE, null)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** 用户选完目录后调用：持久化 URI 授权，否则下次开软件就写不进去了。 */
    fun saveTree(c: Context, uri: Uri?) {
        if (uri == null) {
            prefs(c).edit().remove(KEY_TREE).apply()
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            c.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {
            // 少数厂商不给持久化授权：仍然记着，导出失败时会给中文原因
        }
        prefs(c).edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun clearTree(c: Context) = saveTree(c, null)

    private fun safeName(s: String): String = AudioLibrary.safeName(s)

    /**
     * 收集全部书、全部已完成的整章成品。
     * R157 · 目录规则见类注释；同名书追加 `_2`、`_3`，避免两本书的 WAV 互相覆盖。
     * 只收有成品的书，空书不建空文件夹。
     */
    fun collect(c: Context): List<Item> {
        val out = mutableListOf<Item>()
        val used = mutableMapOf<String, Int>()
        for (b in BookStore.list(c)) {
            // R272 · 只导出「一章一个音频」。整本.wav 不再新生成，也不再进导出：
            // 一本书拼成一份大文件会把手机撑爆（用户口径）。R268 那段收整本的
            // 逻辑连同 R271「作废旧整本」一起删；旧版落盘的整本.wav 不主动删。
            val items = AudioLibrary.playableItems(c, b.id)
            if (items.isEmpty()) continue
            val prefix = if (b.origin == "input") "$INPUT_DIR/" else ""
            val base = safeName(b.title)
            val key = prefix + base
            val n = (used[key] ?: 0) + 1
            used[key] = n
            val dir = prefix + if (n > 1) "${base}_$n" else base
            val plan = ChapterPlan.load(c, b.id)?.chapters
            for ((ch, a) in items) {
                val t = plan?.getOrNull(ch)?.title?.takeIf { it.isNotBlank() }
                    ?: "第 ${ch + 1} 章"
                out += Item(dir, "%03d_%s.wav".format(ch + 1, safeName(t)), a.file)
            }
        }
        return out
    }

    /**
     * 在目录树里找一个已有的同名子目录。SAF 的 `createDocument` 对同名目录
     * 有些 provider 会自动改名成「书名 (1)」，所以先查再建，重复导出不会越导越乱。
     */
    private fun findChildDir(r: ContentResolver, tree: Uri, parent: Uri, name: String): Uri? = try {
        val parentId = DocumentsContract.getDocumentId(parent)
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        r.query(kids, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val dn = cur.getString(1) ?: continue
                val mime = cur.getString(2) ?: continue
                if (dn == name && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
                }
            }
        }
        null
    } catch (_: Throwable) {
        null
    }

    /**
     * R268 · 同名成品文件是否已存在，以及它的大小。
     * 目录层 findChildDir 早就做了「先查再建」，文件层却一直直接 createDocument：
     * 多数 SAF provider 对同名文件会自动改名成「xxx (1).wav」，于是每点一次
     * 「导出全部」就在目标目录多堆一份副本，越导越乱。
     * 返回 (Uri, 字节数)；COLUMN_SIZE 取不到时 provider 可能给 -1，按「需重写」处理。
     */
    private fun findChildFile(r: ContentResolver, tree: Uri, parent: Uri,
                              name: String): Pair<Uri, Long>? = try {
        val parentId = DocumentsContract.getDocumentId(parent)
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        r.query(kids, proj, null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0)) to
                        cur.getLong(2)
                }
            }
        }
        null
    } catch (_: Throwable) {
        null
    }

    /**
     * 把 collect() 的成品写进用户选的目录树，按 Item.dir 逐级建/复用子目录。
     * 用原生 DocumentsContract，不引入 androidx.documentfile 依赖。
     * 必须走 IO 线程（调用方负责）。
     */
    fun exportAll(c: Context, tree: Uri?): Result {
        val t = tree ?: return Result(reason = "还没选导出位置")
        val r = c.contentResolver
        val treeId = try {
            DocumentsContract.getTreeDocumentId(t)
        } catch (_: Throwable) {
            return Result(reason = "上次选的文件夹失效了，请重新选一次")
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(t, treeId)
        val items = collect(c)
        if (items.isEmpty()) return Result(reason = "还没有做好的整章音频")

        val dirCache = mutableMapOf<String, Uri>()

        fun ensureDir(rel: String): Uri? {
            if (rel.isEmpty()) return root
            dirCache[rel]?.let { return it }
            var cur = root
            var acc = ""
            for (part in rel.split("/")) {
                if (part.isEmpty()) continue
                acc = if (acc.isEmpty()) part else "$acc/$part"
                cur = dirCache[acc] ?: run {
                    val got = findChildDir(r, t, cur, part)
                        ?: try {
                            DocumentsContract.createDocument(
                                r, cur, DocumentsContract.Document.MIME_TYPE_DIR, part
                            )
                        } catch (e: Throwable) {
                            null
                        } ?: return null
                    dirCache[acc] = got
                    got
                }
            }
            return cur
        }

        var ok = 0
        var skipped = 0
        var failed = 0
        var firstErr = ""
        for (it in items) {
            if (!it.file.isFile) { skipped++; continue }
            val parent = ensureDir(it.dir)
            if (parent == null) {
                if (firstErr.isEmpty()) firstErr = "建不了「${it.dir}」文件夹"
                failed++
                continue
            }
            // R268 · 幂等：同名同大小说明已经导过且源没变，直接跳过；
            // 大小不同说明这一章重做过，先删旧副本再写新的，不堆「(1)」文件。
            val existing = findChildFile(r, t, parent, it.name)
            if (existing != null && existing.second == it.file.length()) {
                skipped++
                continue
            }
            if (existing != null) {
                try { DocumentsContract.deleteDocument(r, existing.first) }
                catch (_: Throwable) { /* 删不掉就照常新建，provider 会自己改名 */ }
            }
            val child = try {
                DocumentsContract.createDocument(r, parent, "audio/wav", it.name)
            } catch (e: Throwable) {
                if (firstErr.isEmpty()) firstErr = e.message ?: "系统不给写"
                failed++
                continue
            }
            if (child == null) { failed++; continue }
            if (AudioOps.copyTo(c, it.file, child)) ok++ else failed++
        }
        return Result(
            ok = ok, skipped = skipped, failed = failed,
            reason = if (failed > 0 && firstErr.isNotEmpty()) firstErr else ""
        )
    }

    /** 给设置页显示用：目录的最后一段，取不到就说「已设置」。 */
    fun treeLabel(c: Context): String {
        val u = treeUri(c) ?: return "未设置"
        return runCatching {
            u.lastPathSegment?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() } ?: "已设置"
        }.getOrDefault("已设置")
    }
}
