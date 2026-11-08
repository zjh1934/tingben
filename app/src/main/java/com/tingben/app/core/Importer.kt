package com.tingben.app.core

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

object Importer {

    const val CHAPTER_SEP = '\uE000'

    const val MAX_BYTES = 64 * 1024 * 1024

    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_UNZIPPED_BYTES = 96 * 1024 * 1024

    private fun readLimited(s: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES)
                throw IllegalStateException("这本书超过 64 MB，先拆成几本再导入")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    data class Result(
        val title: String,
        val text: String,
        val kind: String,
        val chapterCount: Int = 0
    )

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

    fun read(c: Context, uri: Uri, mimeHint: String? = null): Result {
        val name = nameOf(c, uri)
        val lower = name.lowercase()
        val mime = mimeHint ?: try { c.contentResolver.getType(uri) } catch (_: Throwable) { null }
        val bytes = try {
            if (uri.scheme == "file") {
                val p = uri.path ?: throw IllegalStateException("文件路径为空")
                val f = java.io.File(p)
                if (f.length() > MAX_BYTES)
                    throw IllegalStateException("这本书超过 64 MB，先拆成几本再导入")
                f.inputStream().use { readLimited(it) }
            } else {
                c.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                    ?: throw IllegalStateException("打不开这个文件")
            }
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("手机内存不够读这本书，关掉别的应用再试")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("读文件时出错：${e.message ?: "文件可能坏了"}")
        }
        if (bytes.size < 4) throw IllegalStateException("这个文件是空的或读不完整")

        val isZip = bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val isEpub = lower.endsWith(".epub") || lower.endsWith(".pub") || isZip ||
                (mime != null && mime.contains("epub"))
        return if (isEpub) {
            val r = try { epubToText(ByteArrayInputStream(bytes)) }
                catch (e: IllegalStateException) { throw e }
                catch (_: Throwable) { throw IllegalStateException("这个 epub 读不开，文件可能坏了") }
            checkText(r.first)
            Result(name.substringBeforeLast('.', name).ifBlank { "未命名" },
                r.first, "epub · ${r.second} 章", r.second)
        } else {
            val text = plainToText(bytes)
            if (text.isBlank()) throw IllegalStateException("这个文件里没读到文字")
            checkText(text)
            // R138 · TXT 也报章数：用规则集扫一遍（只找标题、不切块，很快），
            // 让用户导入那一刻就知道「分没分明白」，而不是进目录页才发现整本 1 章。
            val n = try { TocRules.choose(text).second.size } catch (_: Throwable) { 1 }
            Result(name.substringBeforeLast('.', name).ifBlank { "未命名" },
                text, "文本 · $n 章", n)
        }
    }

    private fun checkText(text: String) {
        if (text.length > BookStore.MAX_TEXT_CHARS)
            throw IllegalStateException(
                "这本书有 ${text.length / 10000} 万字，超过 " +
                    "${BookStore.MAX_TEXT_CHARS / 10000} 万字上限；先拆成几本再导入")
    }

    /**
     * R138 · 编码判定独立成段（Legado TextFile.kt / 52fisher TXT-TOC-Rules 的做法）。
     * 1.1.2 只数 U+FFFD 再试 GB18030/GBK/Big5，实测 Windows 记事本「Unicode」另存的
     * UTF-16LE 会被误选成 GB18030（替换符 1203→2），返回一串带 \u0000 的乱码，
     * 正则一个都匹配不上 → 整本 1 章，而且乱码被 writeText 永久写进书架。
     * 现在：BOM 优先于一切统计 → 严格 UTF-8（不静默替换）→ 中文密度打分选本地编码。
     */
    private fun plainToText(bytes: ByteArray): String {
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        val b2 = if (bytes.size > 2) bytes[2].toInt() and 0xff else -1
        val b3 = if (bytes.size > 3) bytes[3].toInt() and 0xff else -1
        // UTF-8 BOM：必须跳过 3 字节，否则不可见字符会挂在第一章标题前面
        if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF)
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00)
            return runCatching { String(bytes, 4, bytes.size - 4, charset("UTF-32LE")) }
                .getOrElse { plainFallback(bytes) }
        if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF)
            return runCatching { String(bytes, 4, bytes.size - 4, charset("UTF-32BE")) }
                .getOrElse { plainFallback(bytes) }
        if (b0 == 0xFF && b1 == 0xFE)
            return String(bytes, 2, bytes.size - 2, charset("UTF-16LE"))
        if (b0 == 0xFE && b1 == 0xFF)
            return String(bytes, 2, bytes.size - 2, charset("UTF-16BE"))
        // 无 BOM：严格 UTF-8，非法序列直接判失败，不再用替换符数量糊弄
        strictUtf8(bytes)?.let { return it }
        return plainFallback(bytes)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Throwable) { null }

    private fun plainFallback(bytes: ByteArray): String {
        var best = String(bytes, Charsets.UTF_8)
        var bestScore = score(best)
        for (enc in listOf("GB18030", "GBK", "Big5", "UTF-16LE", "UTF-16BE")) {
            val t = runCatching { String(bytes, charset(enc)) }.getOrNull() ?: continue
            val sc = score(t)
            if (sc > bestScore) { bestScore = sc; best = t }
        }
        return best
    }

    /** 中文密度打分：汉字/中文标点/ASCII 可打印加分，替换符与 NUL 控制字符重罚。 */
    private fun score(s: String): Double {
        if (s.isEmpty()) return -1.0
        var good = 0.0
        for (ch in s) {
            when {
                ch == '\uFFFD' -> good -= 5
                ch == '\u0000' || (ch < ' ' && ch != '\n' && ch != '\r' && ch != '\t') -> good -= 5
                ch in '\u4e00'..'\u9fff' -> good += 2
                ch in "，。！？；：、‘’“”（）《》…—\n\r\t " -> good += 1
                ch in ' '..'~' -> good += 1
                else -> good -= 1
            }
        }
        return good / s.length
    }

    private fun keepEntry(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".xml") || n.endsWith(".opf") || n.endsWith(".ncx") ||
                n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")
    }

    private fun readZipEntries(s: InputStream): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(s.buffered()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory && keepEntry(e.name)) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > MAX_ENTRY_BYTES || total > MAX_UNZIPPED_BYTES)
                            throw IllegalStateException("这个 epub 解压后太大，先拆成几本再导入")
                        out.write(buf, 0, n)
                    }
                    entries[e.name] = out.toByteArray()
                }
                zis.closeEntry()
            }
        }
        return entries
    }

    private fun epubToText(s: InputStream): Pair<String, Int> {
        val entries = readZipEntries(s)
        if (entries.isEmpty()) throw IllegalStateException("这个 epub 里是空的")

        val container = decodeXml(entries.entries
            .firstOrNull { it.key.endsWith("container.xml", true) }?.value ?: ByteArray(0))
        val rootPath = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
            ?: entries.keys.firstOrNull { it.endsWith(".opf", true) }
            ?: throw IllegalStateException("epub 缺 OPF 目录文件")
        val base = rootPath.substringBeforeLast('/', "")

        val opfBytes = entries[rootPath]
            ?: entries.entries.firstOrNull { it.key.endsWith(".opf", true) }?.value
            ?: throw IllegalStateException("读不到 epub 目录")
        val opf = decodeXml(opfBytes)

        val hrefById = HashMap<String, String>()
        Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val id = attr(m.value, "id") ?: return@forEach
            val href = attr(m.value, "href") ?: return@forEach
            hrefById[id] = href.substringBefore('#')
        }

        val ordered = mutableListOf<String>()
        Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val id = attr(m.value, "idref") ?: return@forEach
            hrefById[id]?.let { ordered.add(it) }
        }
        if (ordered.isEmpty()) {
            entries.keys.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) }
                .sorted().forEach { ordered.add(it) }
        }
        if (ordered.isEmpty()) throw IllegalStateException("epub 里没找到正文文件")

        val toc = HashMap<String, String>()
        entries.entries.firstOrNull { it.key.endsWith("toc.ncx", true) }?.value?.let { b ->
            val ncx = decodeXml(b)
            Regex("""(?is)<navPoint\b.*?</navPoint>""").findAll(ncx).forEach { np ->
                val label = Regex("""(?is)<text>(.*?)</text>""")
                    .find(np.value)?.groupValues?.get(1)?.let { stripTags(it) }?.trim().orEmpty()
                val src = Regex("""(?i)src\s*=\s*["']([^"']+)["']""")
                    .find(np.value)?.groupValues?.get(1)?.substringBefore('#').orEmpty()
                if (label.isNotBlank() && src.isNotBlank()) toc[keyOf(src)] = label
            }
        }
        if (toc.isEmpty()) {
            entries.entries.firstOrNull {
                it.key.endsWith("nav.xhtml", true) || it.key.endsWith("nav.html", true)
            }?.value?.let { b ->
                val nav = decodeXml(b)
                Regex("""(?is)<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""")
                    .findAll(nav).forEach { m ->
                        val label = stripTags(m.groupValues[2]).trim()
                        val src = m.groupValues[1].substringBefore('#')
                        if (label.isNotBlank() && src.isNotBlank()) toc[keyOf(src)] = label
                    }
            }
        }

        val sb = StringBuilder()
        var count = 0
        ordered.forEachIndexed { i, href ->
            if (href.contains("toc.ncx", true) || href.contains("nav.xhtml", true) ||
                href.contains("nav.html", true)) return@forEachIndexed
            val path = normalizePath(base, href)
            val bytes = entries[path]
                ?: entries.entries.firstOrNull { it.key.endsWith(href.substringAfterLast('/')) }?.value
                ?: return@forEachIndexed
            val html = decodeXml(bytes)
            val body = htmlToText(html)
            if (body.isBlank()) return@forEachIndexed

            val title = toc[keyOf(href)]
                ?: firstHeading(html)
                ?: htmlTitle(html)
                ?: href.substringAfterLast('/').substringBeforeLast('.', "").takeIf { it.isNotBlank() }
                ?: "第 ${i + 1} 节"
            val cleanTitle = title.replace('\n', ' ').trim().take(40).ifBlank { "第 ${i + 1} 节" }

            sb.append(CHAPTER_SEP).append(cleanTitle).append('\n').append(body).append("\n\n")
            count++
        }
        if (count == 0) throw IllegalStateException("epub 正文全是空的")
        return sb.toString().trim() to count
    }

    private fun keyOf(href: String) = href.substringAfterLast('/').substringBefore('#').lowercase()

    private fun decodeXml(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val head = String(bytes, 0, minOf(bytes.size, 2048), charset("ISO-8859-1"))
        val enc = Regex("""(?i)encoding\s*=\s*["']([\w\-]+)["']""").find(head)?.groupValues?.get(1)
            ?: Regex("""(?i)charset\s*=\s*["']?([\w\-]+)""").find(head)?.groupValues?.get(1)
            ?: "UTF-8"
        val cs = when (enc.lowercase()) {
            "utf-8", "utf8" -> Charsets.UTF_8
            "gb2312", "gbk", "gb18030", "gb-18030" -> runCatching { charset("GB18030") }
                .getOrDefault(Charsets.UTF_8)
            "big5", "big5-hkscs" -> runCatching { charset("Big5") }.getOrDefault(Charsets.UTF_8)
            else -> runCatching { charset(enc) }.getOrDefault(Charsets.UTF_8)
        }
        return String(bytes, cs)
    }

    private fun firstHeading(html: String): String? =
        Regex("""(?is)<h[1-3][^>]*>(.*?)</h[1-3]>""").find(html)
            ?.groupValues?.get(1)?.let { stripTags(it).trim() }?.takeIf { it.isNotBlank() }

    private fun htmlTitle(html: String): String? =
        Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)
            ?.groupValues?.get(1)?.let { stripTags(it).trim() }?.takeIf { it.isNotBlank() }

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

    private fun stripTags(src: String): String = decodeEntities(
        Regex("""(?s)<[^>]+>""").replace(src, ""))

    private fun htmlToText(src: String): String {
        var t = src
        t = Regex("""(?is)<title[^>]*>.*?</title>""").replace(t, "")
        t = Regex("""(?is)<script.*?</script>|<style.*?</style>""").replace(t, "")
        t = Regex("""(?i)<br\s*/?>""").replace(t, "\n")
        t = Regex("""(?i)</?(p|div|h[1-6]|li|tr|section|blockquote|article|head|body|html)[^>]*>""")
            .replace(t, "\n")
        t = Regex("""(?s)<[^>]+>""").replace(t, "")
        t = decodeEntities(t)
        t = Regex("""[ \t]+""").replace(t, " ")
        t = Regex("""\n{3,}""").replace(t, "\n\n")
        return t.trim()
    }

    private fun decodeEntities(t0: String): String {
        var t = t0
        val named = mapOf(
            "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
            "quot" to "\"", "apos" to "'", "ldquo" to "“", "rdquo" to "”",
            "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–",
            "hellip" to "…", "middot" to "·", "bull" to "•", "emsp" to " ",
            "ensp" to " ", "thinsp" to " ", "times" to "×"
        )
        t = Regex("""&([a-zA-Z]+);""").replace(t) { m ->
            named[m.groupValues[1].lowercase()] ?: m.value
        }
        t = Regex("""&#x([0-9a-fA-F]+);""").replace(t) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrDefault("")
        }
        t = Regex("""&#(\d+);""").replace(t) { m ->
            runCatching { m.groupValues[1].toInt().toChar().toString() }.getOrDefault("")
        }
        return t
    }
}
