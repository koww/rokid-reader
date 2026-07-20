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
        books.add(0, BookEntry(uri, title, loadProgress(uri)))   // 最近阅读排最前
        saveBooks(books)
    }

    fun removeBook(uri: String) {
        val books = loadBooks()
        books.removeAll { it.uri == uri }
        saveBooks(books)
        prefs.edit().remove(KEY_PROGRESS_PREFIX + uri.hashCode()).apply()
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
        prefs.edit().putFloat(KEY_PROGRESS_PREFIX + uri.hashCode(), ratio).apply()
    }

    fun loadProgress(uri: String): Float {
        return prefs.getFloat(KEY_PROGRESS_PREFIX + uri.hashCode(), 0f)
    }

    private fun saveBooks(list: List<BookEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("uri", it.uri).put("title", it.title))
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }
}
