package com.risenav.rokid.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

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

    init {
        // 纯绘制 View 没有文字/描述，默认可能被判为"对无障碍不重要"而从节点树里剪掉，
        // 无障碍服务就看不到下面上报的翻页动作
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * 阅读页是否在最前（书架覆盖时为 false），由 MainActivity 维护。
     *
     * 兄弟 View 遮挡**不会**让 isVisibleToUser 变 false——书架打开时 ReaderView
     * 仍在无障碍节点树里，若照常上报翻页动作，无障碍服务（指环）会翻动看不见的书。
     */
    var readerActive: Boolean = true

    var book: Book? = null
        set(value) {
            field = value
            // View 已 measure（书架打开时 ReaderView 就在容器里）时 onSizeChanged 不会再触发，
            // 必须在此主动 repaginate；repaginate 内部会处理 width=0 的边界（静默跳过，
            // 等 onSizeChanged 补上——首次 attach 的场景）
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
    /** 章节标题：加粗，与正文区分 */
    private val chapterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    /** 空书架/加载中提示文字 */
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
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
        chapterPaint.textSize = textPaint.textSize
        applySettings()
    }

    /** 应用最新设置（字号 / 翻页幅度），网页修改后调用 */
    fun applySettings() {
        if (!::settings.isInitialized) return
        val newSize = SettingsStore.FONT_SIZES[settings.fontSizeIndex]
        if (textPaint.textSize != newSize) {
            textPaint.textSize = newSize
            chapterPaint.textSize = newSize
            repaginate()
        }
    }

    /** 翻页或字号变化后重新折行。
     *  只在尺寸有效时执行；无效时静默跳过（onSizeChanged 会在 measure 后补上）。 */
    fun repaginate() {
        val b = book ?: return
        val usableWidth = width - paddingX * 2
        val usableHeight = height - (paddingTop + progressBarHeight + progressBarMargin).toInt()
        if (usableWidth <= 0 || usableHeight <= 0) return
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

    /** @return 是否真的翻动了（到头/到尾返回 false） */
    fun nextPage(): Boolean {
        if (book?.scrollBy(scrollLines()) != true) return false
        invalidate()
        notifyProgress()
        return true
    }

    /** @return 是否真的翻动了（到头/到尾返回 false） */
    fun prevPage(): Boolean {
        if (book?.scrollBy(-scrollLines()) != true) return false
        invalidate()
        notifyProgress()
        return true
    }

    fun scroll(dir: Int) {
        if (dir > 0) nextPage() else prevPage()
    }

    private fun notifyProgress() {
        book?.let { onProgressChanged?.invoke(it.progressRatio) }
    }

    // ---------- 无障碍：给蓝牙指环等无障碍服务的翻页通道 ----------
    //
    // 阅读页的触摸必须保持不响应（滚轮按压同时发触摸和 ENTER，见 CLAUDE.md），
    // 所以这里只在**节点信息**里上报 click/scroll，不调用 setClickable/setOnClickListener——
    // View 的触摸行为完全不变，无障碍动作是另一条独立通道。
    //
    // 同样不能让节点可获得焦点（不设 focusable）：无障碍服务的单轴导航通常先试
    // ACTION_FOCUS，一旦成功就直接返回、不再发 scroll，翻页会整个失效。

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!readerActive) return
        info.isClickable = true
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (readerActive) {
            when (action) {
                // 单击（指环单击）与向前滚动都对应下一页，与 ENTER / 滚轮下滚一致
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id -> return nextPage()
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id -> return prevPage()
            }
        }
        return super.performAccessibilityAction(action, arguments)
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

        // 绘制当前位置可见行（「...」包裹的行视为章节标题，加粗）
        var y = paddingTop + textPaint.textSize
        for (line in b.visibleLines()) {
            if (line.isNotEmpty()) {
                val isChapterTitle = line.startsWith("「") && line.endsWith("」")
                canvas.drawText(line, paddingX, y, if (isChapterTitle) chapterPaint else textPaint)
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
        canvas.drawText(text, width / 2f, height / 2f, hintPaint)
    }
}
