package com.risenav.rokid.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 阅读视图：Canvas 绘制当前位置文本 + 底部进度条。
 *
 * 翻页 = 起始行偏移 N 行，N = 每页行数 × 翻页幅度（整页/¾页/半页）。
 * 部分翻页时有内容重叠，阅读连贯性更好。
 *
 * 黑底白字单色优化，设置（字号/翻页幅度）从 SettingsStore 读取，
 * 网页修改后调用 applySettings 生效。
 */
class ReaderView(context: Context) : View(context) {

    var book: Book? = null
        set(value) {
            field = value
            repaginate()
        }

    private lateinit var settings: SettingsStore

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = SettingsStore.FONT_SIZES[0]   // 默认小字号
    }
    private val progressBgPaint = Paint().apply {
        color = Color.argb(80, 255, 255, 255)
    }
    private val progressFgPaint = Paint().apply {
        color = Color.WHITE
    }

    private val paddingX = 56f
    private val paddingTop = 48f
    private val progressBarHeight = 6f
    private val progressBarMargin = 24f

    private val lineHeight: Int
        get() = (textPaint.textSize * 1.5f).toInt()

    /** 进度变化回调（用于持久化） */
    var onProgressChanged: ((Float) -> Unit)? = null

    fun initSettings(settings: SettingsStore) {
        this.settings = settings
        applySettings()
    }

    /** 应用最新设置（字号 / 翻页幅度），网页修改后调用 */
    fun applySettings() {
        if (!::settings.isInitialized) return
        val newSize = SettingsStore.FONT_SIZES[settings.fontSizeIndex]
        if (textPaint.textSize != newSize) {
            textPaint.textSize = newSize
            repaginate()
        }
    }

    /** 翻页或字号变化后重新折行 */
    fun repaginate() {
        val b = book ?: return
        val usableWidth = width - paddingX * 2
        val usableHeight = height - (paddingTop + progressBarHeight + progressBarMargin).toInt()
        if (usableWidth <= 0 || usableHeight <= 0) {
            post { repaginate() }
            return
        }
        b.paginate(textPaint, lineHeight, usableWidth, usableHeight)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) repaginate()
    }

    /** 每次翻页的行数 = 每页行数 × 翻页幅度 */
    private fun scrollLines(): Int {
        val ratio = if (::settings.isInitialized) settings.scrollRatio else 1.0f
        val lines = (book?.linesPerPage ?: 1)
        return maxOf(1, (lines * ratio).toInt())
    }

    fun nextPage() {
        if (book?.scrollBy(scrollLines()) == true) {
            invalidate()
            notifyProgress()
        }
    }

    fun prevPage() {
        if (book?.scrollBy(-scrollLines()) == true) {
            invalidate()
            notifyProgress()
        }
    }

    fun scroll(dir: Int) {
        if (dir > 0) nextPage() else prevPage()
    }

    /** 切换字号档位（本地快捷键用，与网页设置同步） */
    fun cycleFontSize() {
        if (!::settings.isInitialized) return
        settings.fontSizeIndex = (settings.fontSizeIndex + 1) % SettingsStore.FONT_SIZES.size
        applySettings()
    }

    private fun notifyProgress() {
        book?.let { onProgressChanged?.invoke(it.progressRatio) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val b = book ?: run {
            drawCenteredText(canvas, "暂无书籍，请先从书库打开")
            return
        }
        if (b.lineCount == 0) {
            drawCenteredText(canvas, "加载中…")
            return
        }

        // 绘制当前位置可见行
        var y = paddingTop + textPaint.textSize
        for (line in b.visibleLines()) {
            if (line.isNotEmpty()) {
                canvas.drawText(line, paddingX, y, textPaint)
            }
            y += lineHeight
        }

        // 底部进度条
        val w = width.toFloat()
        val h = height.toFloat()
        val barTop = h - progressBarMargin - progressBarHeight
        canvas.drawRect(
            RectF(paddingX, barTop, w - paddingX, barTop + progressBarHeight),
            progressBgPaint
        )
        canvas.drawRect(
            RectF(paddingX, barTop, paddingX + (w - paddingX * 2) * b.progressRatio, barTop + progressBarHeight),
            progressFgPaint
        )
    }

    private fun drawCenteredText(canvas: Canvas, text: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, width / 2f, height / 2f, paint)
    }
}
