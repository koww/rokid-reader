package com.risenav.rokid.reader

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream

/**
 * 轻量 EPUB 解析器（零依赖）。
 *
 * EPUB = zip 包，正文是一系列 XHTML 文件，阅读顺序由 OPF 清单的 spine 定义。
 * 这里不追求完整规范兼容，走实用路线：
 *   1. 解压全部条目
 *   2. 找到 .opf 文件，解析 spine 拿到正文 XHTML 的顺序
 *   3. 按顺序提取每个 XHTML 的纯文本（剥标签，段落按 </p> <br> <h*> 切分）
 *
 * 对绝大多数标准排版的中文 epub（起点/微信读书导出等）足够用。
 */
object EpubParser {

    data class Chapter(val title: String, val paragraphs: List<String>)

    /** 解析 epub，返回按阅读顺序排列的章节列表；失败返回 null */
    fun parse(context: Context, uri: Uri): List<Chapter>? {
        return try {
            val entries = mutableMapOf<String, ByteArray>()
            context.contentResolver.openInputStream(uri)?.use { input ->
                val zip = ZipInputStream(input)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            } ?: return null

            // 找 OPF 文件（清单）
            val opfName = entries.keys.firstOrNull { it.endsWith(".opf", true) }
                ?: return fallbackParse(entries)
            val opf = String(entries[opfName]!!, Charsets.UTF_8)
            val opfDir = opfName.substringBeforeLast('/', "")

            // manifest: id -> href
            val manifest = mutableMapOf<String, String>()
            Regex("<item[^>]*id=\"([^\"]+)\"[^>]*href=\"([^\"]+)\"[^>]*/>").findAll(opf).forEach {
                manifest[it.groupValues[1]] = it.groupValues[2]
            }
            // 也匹配属性顺序相反的情况（href 在 id 前）
            Regex("<item[^>]*href=\"([^\"]+)\"[^>]*id=\"([^\"]+)\"[^>]*/>").findAll(opf).forEach {
                manifest[it.groupValues[2]] = it.groupValues[1]
            }

            // spine: 阅读顺序的 id 列表
            val spine = Regex("<itemref[^>]*idref=\"([^\"]+)\"")
                .findAll(opf).map { it.groupValues[1] }.toList()

            val chapters = mutableListOf<Chapter>()
            for (id in spine) {
                val href = manifest[id] ?: continue
                val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val html = entries[path] ?: entries[path.replace("%20", " ")] ?: continue
                val text = String(html, Charsets.UTF_8)
                val paras = htmlToParagraphs(text)
                if (paras.isNotEmpty()) {
                    chapters.add(Chapter(guessTitle(text, chapters.size), paras))
                }
            }
            if (chapters.isEmpty()) fallbackParse(entries) else chapters
        } catch (e: Exception) {
            null
        }
    }

    /** 找不到 OPF 时的兜底：按文件名顺序解析所有 html/xhtml */
    private fun fallbackParse(entries: Map<String, ByteArray>): List<Chapter>? {
        val chapters = mutableListOf<Chapter>()
        entries.keys
            .filter { it.endsWith(".html", true) || it.endsWith(".xhtml", true) || it.endsWith(".htm", true) }
            .sorted()
            .forEach { name ->
                val paras = htmlToParagraphs(String(entries[name]!!, Charsets.UTF_8))
                if (paras.isNotEmpty()) {
                    chapters.add(Chapter(name.substringAfterLast('/'), paras))
                }
            }
        return if (chapters.isEmpty()) null else chapters
    }

    /** XHTML → 纯文本段落：剥标签，块级标签处断行，合并空白 */
    private fun htmlToParagraphs(html: String): List<String> {
        var t = html
        // 去掉 script/style/注释
        t = Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL).replace(t, "")
        t = Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL).replace(t, "")
        t = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(t, "")
        // 块级标签换成换行
        t = Regex("</(p|div|h[1-6]|li|blockquote|tr|section|article)>").replace(t, "\n")
        t = Regex("<(br|hr)[^>]*/?>").replace(t, "\n")
        // 剥掉所有剩余标签
        t = Regex("<[^>]+>").replace(t, "")
        // 解码 HTML 实体
        t = decodeEntities(t)
        // 切分并清理
        return t.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun decodeEntities(s: String): String {
        return s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: "" }
    }

    /** 从 <title> 或第一个 <h1>/<h2> 猜章节标题 */
    private fun guessTitle(html: String, index: Int): String {
        Regex("<title[^>]*>([^<]+)</title>").find(html)?.let {
            return it.groupValues[1].trim()
        }
        Regex("<h[12][^>]*>([^<]+)</h[12]>").find(html)?.let {
            return it.groupValues[1].trim()
        }
        return "第 ${index + 1} 章"
    }
}
