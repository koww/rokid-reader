package com.risenav.rokid.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
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
 *   ENTER / 滚轮按压 —— 书架打开书籍；阅读时向下翻一页（与滚轮下滚一致）
 *   BACK / 滚轮按压 —— 阅读时回书架；书架页退出程序
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
            onToggleServer = { toggleServer() }
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

    private fun openBookFromUri(uri: Uri, title: String) {
        val book = Book(uri, title, this)
        if (!book.load()) {
            Toast.makeText(this, "无法打开文件（不存在或超过 30MB）", Toast.LENGTH_SHORT).show()
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
        if (BuildConfig.DEBUG) {
            android.util.Log.d("RokidInput", "[$tag] $msg")
        }
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

    /** ENTER 确认：书架打开书籍 / 切换传书；阅读时向下翻一页（与滚轮下滚一致） */
    private fun handleConfirmKey() {
        if (isLibraryVisible) {
            libraryView?.confirm()
        } else {
            readerView.nextPage()
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

    /** 触摸：书架点选由 LibraryView 处理；阅读时触摸事件不响应——
     *  按压滚轮会同时发触摸 UP 和 ENTER，两者都翻页会翻两页，ENTER 已覆盖 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        logEvent("TOUCH", "action=${event.action} x=${event.x} y=${event.y}")
        return super.onTouchEvent(event)
    }

    override fun onDestroy() {
        stopServer()
        // 进度无需在此保存：onProgressChanged 翻页时已存，showLibrary 回书架时也存了
        super.onDestroy()
    }
}
