package com.risenav.rokid.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 书库视图：书架列表 + 局域网传书 + 打开新文件入口。
 *
 * 传书条目在服务器运行时行高加倍，地址独立一行完整显示（缩小字号），
 * 保证在小视场屏幕上不被右侧遮挡。
 *
 * 交互：
 *   滚轮 / 方向键 —— 移动选中项
 *   单击 / ENTER —— 打开书籍 / 开关传书 / 选文件
 *   鼠标指针 —— 悬停高亮，单击直接触发
 */
class LibraryView(
    context: Context,
    private val entries: List<LibraryStore.BookEntry>,
    private val onOpenBook: (LibraryStore.BookEntry) -> Unit,
    private val onToggleServer: () -> Unit,
    private val onClose: () -> Unit
) : View(context) {

    /** 列表项：书架书籍 + 传书入口 */
    private sealed class Item {
        data class BookItem(val entry: LibraryStore.BookEntry) : Item()
        object ServerToggle : Item()
    }

    private val items: List<Item> = entries.map { Item.BookItem(it) } + Item.ServerToggle

    /** 传书服务器状态（由 MainActivity 更新后调用 invalidate） */
    var serverRunning = false
        set(value) { field = value; invalidate() }
    var serverAddress = ""
        set(value) { field = value; invalidate() }

    private var selected = 0
    private var windowTop = 0

    // 单色显示优化：纯黑背景 + 纯白文字，高亮只用描边不用填充，
    // 在衍射光波导单色屏上对比度最高、最省电
    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        isFakeBoldText = true
    }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
    }
    private val selectedItemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
    }
    /** 传书地址：白色加粗，单色屏上无彩色可用 */
    private val addrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isFakeBoldText = true
    }
    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paddingX = 36f
    private val listStartY = 132f

    /** 普通行高 */
    private val rowHeight = 76f
    /** 传书行在服务器运行时的行高（多一行显示地址） */
    private val rowHeightExpanded = 130f

    /** 某项的实际行高（传书行运行时更高） */
    private fun heightOf(item: Item): Float =
        if (item is Item.ServerToggle && serverRunning) rowHeightExpanded else rowHeight

    /** 可视窗口内能放下的项索引范围（闭区间，上限 = 最后一项索引） */
    private fun visibleRange(): IntRange {
        if (items.isEmpty() || height <= 0) return IntRange.EMPTY
        var y = listStartY
        var last = windowTop - 1   // 还没放入任何项
        val maxY = height - 20f
        var i = windowTop
        while (i < items.size) {
            y += heightOf(items[i])
            if (y > maxY) break
            last = i
            i++
        }
        return if (last >= windowTop) windowTop..last else IntRange.EMPTY
    }

    fun moveUp() = move(-1)
    fun moveDown() = move(1)

    private fun move(dir: Int) {
        if (items.isEmpty()) return
        selected = (selected + dir).coerceIn(0, items.size - 1)
        if (selected < windowTop) windowTop = selected
        // 选中项滑出可视底部时逐步下移窗口，直到可见（有上限防死循环）
        var guard = 0
        while (windowTop < selected && !visibleRange().contains(selected) && guard < items.size) {
            windowTop++
            guard++
        }
        invalidate()
    }

    fun confirm() {
        when (val item = items.getOrNull(selected)) {
            is Item.BookItem -> onOpenBook(item.entry)
            Item.ServerToggle -> onToggleServer()
            null -> Unit
        }
    }

    fun close() = onClose()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        canvas.drawText("书架", paddingX, 88f, titlePaint)

        var y = listStartY
        val range = visibleRange()
        for (i in range) {
            val item = items[i]
            val rh = heightOf(item)
            val isSel = i == selected
            if (isSel) {
                // 单色屏高亮：只画白色描边框，不填充底色。
                // 上下 padding 收窄（文字 ascent/descent 自然留白即可），框更紧凑
                val rect = RectF(paddingX - 18f, y - 44f, w - paddingX + 18f, y + rh - 56f)
                canvas.drawRoundRect(rect, 12f, 12f, highlightBorderPaint)
            }
            val paint = if (isSel) selectedItemPaint else itemPaint
            when (item) {
                is Item.BookItem -> {
                    canvas.drawText(ellipsize(item.entry.title, paint, w - paddingX * 2), paddingX, y, paint)
                    val pct = (item.entry.progress * 100).toInt()
                    val sub = if (pct > 0) "已读 $pct%" else "未读"
                    canvas.drawText(sub, paddingX, y + 40f, subPaint)
                }
                Item.ServerToggle -> {
                    val label = if (serverRunning) "⏹ 停止传书" else "⇪ 局域网传书…"
                    canvas.drawText(label, paddingX, y, paint)
                    if (serverRunning) {
                        // 地址独立一行，完整显示不截断
                        canvas.drawText("电脑浏览器打开:", paddingX, y + 32f, subPaint)
                        canvas.drawText(serverAddress, paddingX, y + 64f, addrPaint)
                    }
                }
            }
            y += rh
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = hitTest(event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (index >= 0 && index != selected) {
                    selected = index
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (index >= 0) {
                    selected = index
                    confirm()
                }
            }
        }
        return true
    }

    /** 按 Y 坐标查找点中的项（遍历累加实际行高） */
    private fun hitTest(y: Float): Int {
        if (y < listStartY - 58f) return -1
        var curY = listStartY - 58f
        for (i in windowTop until items.size) {
            curY += heightOf(items[i])
            if (y < curY) return i
        }
        return -1
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0 || paint.measureText(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && paint.measureText("$t…") > maxWidth) {
            t = t.dropLast(1)
        }
        return "$t…"
    }
}
