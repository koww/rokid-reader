package com.risenav.rokid.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 主活动：书架 + 阅读器。
 *
 * 交互模型（适配眼镜滚轮 + 触摸板）:
 *   滚轮 / 方向键上下 —— 书架移动选中项；阅读时翻页（上滚=上一页，下滚=下一页）
 *   鼠标指针 —— 书架悬停高亮，单击打开
 *   ENTER / 滚轮按压 —— 书架打开书籍；阅读时单击切换字号，双击回书架
 *   触摸板滚轮 —— 阅读时平滑滚动（映射为翻页）
 *
 * 阅读进度自动保存，重新打开同一本书时恢复。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var readerView: ReaderView
    private lateinit var libraryStore: LibraryStore
    private lateinit var settingsStore: SettingsStore
    private var libraryView: LibraryView? = null
    private var currentBook: Book? = null
    private var transferServer: TransferServer? = null

    private val handler = Handler(Looper.getMainLooper())

    /** 双击判定窗口（毫秒） */
    private val doubleTapTimeout = 350L

    /** 待执行的单击动作；双击到来时取消 */
    private var pendingClick: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        libraryStore = LibraryStore(this)
        settingsStore = SettingsStore(this)

        container = FrameLayout(this)
        readerView = ReaderView(this)
        readerView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        readerView.initSettings(settingsStore)
        readerView.onProgressChanged = { ratio ->
            currentBook?.let { libraryStore.saveProgress(it.uri.toString(), ratio) }
        }
        container.addView(readerView)
        setContentView(container)

        // 启动显示书架
        showLibrary()

        // 处理从文件管理器直接打开 txt 的 intent
        handleOpenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            openBookFromUri(uri, null)
        }
    }

    // ---------- 书架 ----------

    private fun showLibrary() {
        if (libraryView != null) return
        // 保存当前书进度后再回书架
        currentBook?.let { libraryStore.saveProgress(it.uri.toString(), it.progressRatio) }

        val view = LibraryView(
            this,
            libraryStore.loadBooks(),
            onOpenBook = { entry ->
                hideLibrary()
                openBookFromUri(Uri.parse(entry.uri), entry.title)
            },
            onToggleServer = { toggleServer() },
            onClose = { /* 书架是最底层，不关闭 */ }
        )
        // 恢复服务器状态显示（书架重建时服务器可能还在运行）
        transferServer?.let {
            view.serverRunning = true
            view.serverAddress = it.localAddress()
        }
        libraryView = view
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun hideLibrary() {
        libraryView?.let { container.removeView(it) }
        libraryView = null
    }

    private val isLibraryVisible: Boolean
        get() = libraryView != null

    // ---------- 书籍打开 ----------

    /** 从 uri 解析显示标题（优先系统 DISPLAY_NAME，否则用路径末段） */
    private fun resolveTitle(uri: Uri): String {
        var title: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) title = cursor.getString(idx)
            }
        } catch (e: Exception) { /* ignore */ }
        return title ?: uri.lastPathSegment ?: "未命名"
    }

    private fun openBookFromUri(uri: Uri, title: String?) {
        val book = Book(uri, title ?: resolveTitle(uri), this)
        if (!book.load()) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
            showLibrary()
            return
        }
        currentBook = book
        libraryStore.touchBook(uri.toString())
        // 恢复上次阅读进度
        book.seekToRatio(libraryStore.loadProgress(uri.toString()))
        readerView.book = book
    }

    // ---------- 局域网传书服务器 ----------

    private fun toggleServer() {
        if (transferServer != null) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        try {
            val server = TransferServer(filesDir, libraryStore, settingsStore, 8000) {
                // 书库或设置变更（上传/删除/改设置）后回主线程刷新
                runOnUiThread {
                    readerView.applySettings()   // 网页可能改了字号/翻页幅度
                    refreshLibrary()
                }
            }
            server.start()
            transferServer = server
            libraryView?.apply {
                serverRunning = true
                serverAddress = server.localAddress()
                invalidate()
            }
            Toast.makeText(this, "书库管理服务已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        transferServer?.stop()
        transferServer = null
        libraryView?.apply {
            serverRunning = false
            serverAddress = ""
            invalidate()
        }
    }

    /** 重建书架列表（新书入库后刷新显示） */
    private fun refreshLibrary() {
        if (!isLibraryVisible) return
        hideLibrary()
        showLibrary()
    }

    // ---------- 输入处理 ----------

    private fun logEvent(tag: String, msg: String) {
        android.util.Log.d("RokidInput", "[$tag] $msg")
    }

    /** 触摸板滚轮：书架移动选中；阅读时翻页 */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            logEvent("SCROLL", "v=$v source=${event.source}")
            if (v != 0f) {
                val dir = if (v > 0) 1 else -1
                if (isLibraryVisible) {
                    if (dir > 0) libraryView?.moveDown() else libraryView?.moveUp()
                } else {
                    readerView.scroll(dir)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** 眼镜滚轮按键映射：NOTIFICATION 噪声丢弃，UP/DOWN 驱动，RIGHT/LEFT 噪声丢弃 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logEvent("KEY", "action=${event.action} code=${event.keyCode} " +
                "name=${KeyEvent.keyCodeToString(event.keyCode)} repeat=${event.repeatCount}")
        return dispatchKeyEventInternal(event)
    }

    private fun dispatchKeyEventInternal(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_NOTIFICATION) return true
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onBackKey()
            }
            return true
        }

        val isNavKey = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        if (!isNavKey) return super.dispatchKeyEvent(event)

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true
            return onNavKeyDown(keyCode)
        }
        return true
    }

    private fun onNavKeyDown(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLibraryVisible) libraryView?.moveUp() else readerView.prevPage()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLibraryVisible) libraryView?.moveDown() else readerView.nextPage()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true   // 滚轮前导噪声，消费不响应
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                handleConfirmKey()
                true
            }
            else -> false
        }
    }

    /** 单击/双击判定：书架单击打开、双击退出；阅读单击切字号、双击回书架 */
    private fun handleConfirmKey() {
        val pending = pendingClick
        if (pending != null) {
            handler.removeCallbacks(pending)
            pendingClick = null
            onDoubleTap()
        } else {
            val action = Runnable {
                pendingClick = null
                if (isLibraryVisible) {
                    libraryView?.confirm()
                } else {
                    readerView.cycleFontSize()
                }
            }
            pendingClick = action
            handler.postDelayed(action, doubleTapTimeout)
        }
    }

    private fun onDoubleTap() {
        if (isLibraryVisible) {
            finish()          // 书架双击 = 退出
        } else {
            showLibrary()     // 阅读双击 = 回书架
        }
    }

    /** BACK 键（滚轮按压映射）：阅读时回书架，书架页退出程序 */
    private fun onBackKey() {
        if (isLibraryVisible) {
            finish()
        } else {
            showLibrary()
        }
    }

    /** 上一次触摸抬起的时间，用于书架页双击退出判定 */
    private var lastTouchUpTime = 0L

    /** 触摸：书架点选由 LibraryView 处理，但双击需在此拦截退出；
     *  阅读时触摸单击=翻页 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        logEvent("TOUCH", "action=${event.action} x=${event.x} y=${event.y}")
        if (isLibraryVisible) {
            // 双击检测：LibraryView 的单击点选照常放行，
            // 但快速连续两次 ACTION_UP 判定为双击退出
            if (event.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                if (now - lastTouchUpTime < doubleTapTimeout) {
                    lastTouchUpTime = 0
                    finish()
                    return true
                }
                lastTouchUpTime = now
            }
            return super.onTouchEvent(event)   // LibraryView 自己处理点选
        }
        // 阅读中：触摸下半屏=下一页，上半屏=上一页（眼镜触摸板常用映射）
        if (event.action == MotionEvent.ACTION_UP) {
            if (event.y > readerView.height / 2f) readerView.nextPage() else readerView.prevPage()
        }
        return true
    }

    override fun onDestroy() {
        pendingClick?.let { handler.removeCallbacks(it) }
        stopServer()
        currentBook?.let { libraryStore.saveProgress(it.uri.toString(), it.progressRatio) }
        super.onDestroy()
    }
}
