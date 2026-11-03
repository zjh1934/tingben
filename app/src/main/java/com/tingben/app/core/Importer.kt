package com.tingben.app.core

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 外部「打开方式 / 分享」进来的文件统一转成纯文本。
 * 首版只认 txt / md / text / epub，全部用 Android 自带类，不引任何依赖。
 * epub = zip + OPF spine + XHTML，按 spine 顺序拼，避免章节乱序。
 */
object Importer {

    data class Result(val title: String, val text: String, val kind: String)

    fun nameOf(c: Context, uri: Uri): String {
        var n = ""
        try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && cur.moveToFirst()) n = cur.getString(i) ?: ""
            }
        } catch (_: Throwable) { }
        if (n.isBlank()) n = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        return n
    }

    /** 主入口：按扩展名/mime 决定怎么读，失败抛异常由 UI 兜底。 */
    fun read(c: Context, uri: Uri, mimeHint: String? = null): Result {
        val name = nameOf(c, uri)
        val lower = name.lowercase()
        val mime = mimeHint ?: try { c.contentResolver.getType(uri) } catch (_: Throwable) { null }
        val isEpub = lower.endsWith(".epub") ||
                (mime != null && mime.contains("epub")) ||
                lower.endsWith(".pub")
        val stream = c.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开这个文件")
        val text = stream.use { s ->
            if (isEpub) epubToText(s) else plainToText(s)
        }
        if (text.isBlank()) throw IllegalStateException("这个文件里没读到文字")
        val title = name.substringBeforeLast('.', name).ifBlank { "未命名" }
        return Result(title, text, if (isEpub) "epub" else "文本")
    }

    /** txt/md：优先 UTF-8，中文老书常见 GBK，读出来乱码就换一次。 */
    private fun plainToText(s: InputStream): String {
        val bytes = s.readBytes()
        val utf8 = String(bytes, Charsets.UTF_8)
        // 替换符超过一定比例就判为不是 UTF-8
        val bad = utf8.count { it == '\uFFFD' }
        return if (bad > 8) {
            try { String(bytes, charset("GBK")) } catch (_: Throwable) { utf8 }
        } else utf8
    }

    private fun epubToText(s: InputStream): String {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(s).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) {
                    try { entries[e.name] = zis.readBytes() } catch (_: Throwable) { }
                }
                zis.closeEntry()
            }
        }
        if (entries.isEmpty()) throw IllegalStateException("这个 epub 里是空的")

        // 1) container.xml 找 OPF 路径
        val container = entries.entries
            .firstOrNull { it.key.endsWith("container.xml", true) }
            ?.value?.let { String(it, Charsets.UTF_8) } ?: ""
        val rootPath = Regex("""full-path\s*=\s*["']([^"']+)["']""")
            .find(container)?.groupValues?.get(1)
            ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: throw IllegalStateException("epub 缺 OPF 目录文件")
        val base = rootPath.substringBeforeLast('/', "")

        val opf = entries[rootPath]?.let { String(it, Charsets.UTF_8) }
            ?: entries.entries.firstOrNull { it.key.endsWith(".opf", true) }
                ?.value?.let { String(it, Charsets.UTF_8) }
            ?: throw IllegalStateException("读不到 epub 目录")

        // 2) manifest: id -> href
        val hrefById = HashMap<String, String>()
        Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            hrefById[id] = href.substringBefore('#')
        }

        // 3) spine: 按 idref 顺序取正文，避免章节乱序
        val ordered = mutableListOf<String>()
        Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val id = attr(m.value, "idref") ?: return@forEach
            hrefById[id]?.let { ordered.add(it) }
        }
        // 兜底：spine 没解析出来时按文件名顺序读所有 xhtml
        if (ordered.isEmpty()) {
            entries.keys.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) }
                .sorted().forEach { ordered.add(it) }
        }

        val sb = StringBuilder()
        ordered.forEach { href ->
            val path = normalizePath(base, href)
            val bytes = entries[path]
                ?: entries.entries.firstOrNull { it.key.endsWith(href.substringAfterLast('/')) }?.value
                ?: return@forEach
            val html = String(bytes, Charsets.UTF_8)
            val t = htmlToText(html)
            if (t.isNotBlank()) { sb.append(t).append("\n\n") }
        }
        return sb.toString().trim()
    }

    private fun attr(tag: String, key: String): String? =
        Regex("""\b$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)

    private fun normalizePath(base: String, href: String): String {
        val raw = if (base.isBlank()) href else "$base/$href"
        val parts = mutableListOf<String>()
        raw.split('/').forEach { p ->
            when (p) {
                "", "." -> { }
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    /** 去标签、解实体、保留标题行（ChapterSplitter 靠「第X章」认章节）。 */
    private fun htmlToText(src: String): String {
        var t = src
        t = Regex("""(?is)<script.*?</script>|<style.*?</style>|<head.*?</head>""").replace(t, "")
        t = Regex("""(?i)<br\s*/?>""").replace(t, "\n")
        t = Regex("""(?i)</?(p|div|h[1-6]|li|tr|section|blockquote|article)[^>]*>""")
            .replace(t, "\n")
        t = Regex("""(?s)<[^>]+>""").replace(t, "")
        t = t.replace("&nbsp;", " ").replace("&#160;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&ldquo;", "“").replace("&rdquo;", "”")
            .replace("&lsquo;", "‘").replace("&rsquo;", "’")
            .replace("&mdash;", "—").replace("&hellip;", "…")
            .replace("&middot;", "·")
        t = Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrDefault("")
        }
        t = Regex("""&#(\d+);""").replace(t) { m ->
            runCatching { m.groupValues[1].toInt().toChar().toString() }.getOrDefault("")
        }
        t = Regex("""[ \t]+""").replace(t, " ")
        t = Regex("""\n{3,}""").replace(t, "\n\n")
        return t.trim()
    }
}
