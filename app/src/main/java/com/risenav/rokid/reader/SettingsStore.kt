package com.risenav.rokid.reader

import android.content.Context

/**
 * 阅读设置持久化。
 *
 * 可在网页管理界面中修改，App 启动和翻页时读取最新值。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FONT_SIZE = "font_size_index"
        private const val KEY_SCROLL_RATIO = "scroll_ratio"

        /** 字号档位（与 ReaderView 的 fontSizes 对应） */
        val FONT_SIZES = floatArrayOf(22f, 26f, 31f)

        /** 翻页幅度档位（0.5=半页, 0.75=四分之三页, 1.0=整页） */
        val SCROLL_RATIOS = floatArrayOf(0.5f, 0.75f, 1.0f)
    }

    var fontSizeIndex: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 0).coerceIn(0, FONT_SIZES.size - 1)   // 默认小
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    /** 翻页幅度（0~1），默认 ¾ 页 */
    var scrollRatio: Float
        get() = prefs.getFloat(KEY_SCROLL_RATIO, 0.75f)
        set(value) = prefs.edit().putFloat(KEY_SCROLL_RATIO, value).apply()

    /** 翻页幅度档位索引（网页显示用） */
    var scrollRatioIndex: Int
        get() {
            val ratio = scrollRatio
            return SCROLL_RATIOS.indexOfFirst { Math.abs(it - ratio) < 0.01f }
                .takeIf { it >= 0 } ?: 1   // 默认 ¾ 页
        }
        set(value) {
            val idx = value.coerceIn(0, SCROLL_RATIOS.size - 1)
            scrollRatio = SCROLL_RATIOS[idx]
        }
}
