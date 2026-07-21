package com.risenav.rokid.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书库与阅读进度存储。
 *
 * - 书架：记录添加过的书籍（uri 字符串 + 标题）
 * - 进度：每本书保存阅读比例（0~1），重新打开时恢复
 * SharedPreferences + JSON，数据量小足够用。
 */
class LibraryStore(context: Context) {

    data class BookEntry(val uri: String, val title: String, val progress: Float = 0f)

    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BOOKS = "books"
        private const val KEY_PROGRESS_PREFIX = "progress:"
    }

    init {
        migrateProgressKeys()
    }

    /** 旧版本进度 key 用 uri.hashCode()（可能撞 hash），迁移到完整 uri 作 key；
     *  同时清理已不在书架上的书残留的旧格式进度 */
    private fun migrateProgressKeys() {
        val books = loadBooks()
        val validUris = books.map { it.uri }.toSet()
        val editor = prefs.edit()
        var changed = false

        // 1. 迁移书架上的书：hashCode key → uri key
        for (b in books) {
            val oldKey = KEY_PROGRESS_PREFIX + b.uri.hashCode()
            val newKey = KEY_PROGRESS_PREFIX + b.uri
            if (prefs.contains(oldKey) && !prefs.contains(newKey)) {
                editor.putFloat(newKey, prefs.getFloat(oldKey, 0f))
                editor.remove(oldKey)
                changed = true
            }
        }

        // 2. 清理已删书残留的旧格式进度（key 是 hashCode 格式但 uri 不在书架上）
        val allKeys = prefs.all.keys
        for (key in allKeys) {
            if (!key.startsWith(KEY_PROGRESS_PREFIX)) continue
            val suffix = key.removePrefix(KEY_PROGRESS_PREFIX)
            // 旧格式是 hashCode（纯数字，可能带负号）；新格式是 uri（含 ://）
            val isOldFormat = suffix.matches(Regex("-?\\d+"))
            if (isOldFormat) {
                // 如果没有任何书架上的 uri hashCode 等于它，就是孤儿 key
                val isOrphan = validUris.none { it.hashCode().toString() == suffix }
                if (isOrphan) {
                    editor.remove(key)
                    changed = true
                }
            }
        }

        if (changed) editor.apply()
    }

    fun loadBooks(): MutableList<BookEntry> {
        val result = mutableListOf<BookEntry>()
        try {
            val array = JSONArray(prefs.getString(KEY_BOOKS, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val obj: JSONObject = array.getJSONObject(i)
                val uri = obj.getString("uri")
                result.add(
                    BookEntry(
                        uri = uri,
                        title = obj.getString("title"),
                        progress = loadProgress(uri)
                    )
                )
            }
        } catch (e: Exception) {
            // 数据损坏返回空列表
        }
        return result
    }

    fun addBook(uri: String, title: String) {
        val books = loadBooks()
        books.removeAll { it.uri == uri }
        // progress 不用显式传：saveBooks 只存 uri/title，loadBooks 时现查 SharedPreferences
        books.add(0, BookEntry(uri, title))   // 最近阅读排最前
        saveBooks(books)
    }

    fun removeBook(uri: String) {
        val books = loadBooks()
        books.removeAll { it.uri == uri }
        saveBooks(books)
        prefs.edit().remove(KEY_PROGRESS_PREFIX + uri).apply()
    }

    fun touchBook(uri: String) {
        // 打开过的书移到书架最前
        val books = loadBooks()
        val idx = books.indexOfFirst { it.uri == uri }
        if (idx > 0) {
            val entry = books.removeAt(idx)
            books.add(0, entry)
            saveBooks(books)
        }
    }

    fun saveProgress(uri: String, ratio: Float) {
        prefs.edit().putFloat(KEY_PROGRESS_PREFIX + uri, ratio).apply()
    }

    fun loadProgress(uri: String): Float {
        return prefs.getFloat(KEY_PROGRESS_PREFIX + uri, 0f)
    }

    private fun saveBooks(list: List<BookEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("uri", it.uri).put("title", it.title))
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }
}
