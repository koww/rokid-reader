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
                    // 只读文本类 entry，跳过图片/字体/CSS 等二进制资源（省内存）
                    val name = entry.name
                    val isTextResource = !entry.isDirectory && (
                            name.endsWith(".opf", true) ||
                            name.endsWith(".html", true) ||
                            name.endsWith(".xhtml", true) ||
                            name.endsWith(".htm", true) ||
                            name.endsWith(".ncx", true))
                    if (isTextResource) {
                        entries[name] = zip.readBytes()
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
            // 先匹配 <item ...> 标签整体（DOT_MATCHES_ALL 允许属性跨行），再提取属性
            val manifest = mutableMapOf<String, String>()
            val attr = { tag: String, name: String ->
                Regex("$name=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
            }
            Regex("<item\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(opf).forEach { m ->
                val tag = m.value
                val id = attr(tag, "id")
                val href = attr(tag, "href")
                if (id != null && href != null) manifest[id] = href
            }

            // spine: 阅读顺序的 id 列表（同样允许跨行）
            val spine = Regex("<itemref\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
                .findAll(opf).mapNotNull { attr(it.value, "idref") }.toList()

            val chapters = mutableListOf<Chapter>()
            // 用 spine 下标（不是 chapters.size）作 fallback 标题序号——空章节被跳过时
            // chapters.size 会跳号，导致"第 N 章"与 spine 实际位置错位
            for ((spineIndex, id) in spine.withIndex()) {
                val href = manifest[id] ?: continue
                // href 可能含 ../（OPF 在子目录、引用上级文件），直接字符串拼接
                // 会得到 "OEBPS/../text/ch1.xhtml"，与 zip entry 名不匹配——需规范化
                val rawPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val path = normalizePath(rawPath)
                val html = entries[path] ?: entries[path.replace("%20", " ")] ?: continue
                val text = String(html, Charsets.UTF_8)
                val paras = htmlToParagraphs(text)
                if (paras.isNotEmpty()) {
                    chapters.add(Chapter(guessTitle(text, spineIndex), paras))
                }
            }
            if (chapters.isEmpty()) fallbackParse(entries) else chapters
        } catch (e: Exception) {
            null
        }
    }

    /** 规范化 zip 内路径：处理 "a/./b" 和 "a/../b"，与 zip entry 命名对齐 */
    private fun normalizePath(path: String): String {
        val parts = path.split("/")
        val stack = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                "", "." -> { /* 跳过 */ }
                ".." -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
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
        // 块级标签换成换行（td/th 也算——否则表格单元格会全挤在一行）
        t = Regex("</(p|div|h[1-6]|li|blockquote|tr|td|th|section|article)>").replace(t, "\n")
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
