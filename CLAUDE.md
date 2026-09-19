# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Rokid 智能眼镜的本地小说阅读器。纯 Android (Kotlin, minSdk 28)，无后端，书籍通过局域网 HTTP 服务器从电脑上传。目标设备是**单色衍射光波导屏**——所有 UI 都是黑底白字，无彩色、无灰色填充。

## 构建与安装

```bash
# 需要 Android Studio 的 JBR（系统无独立 JDK）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # 签名 release APK（需 rokid-reader.keystore，被 .gitignore 忽略）

adb install -r app/build/outputs/apk/debug/app-debug.apk
# release 与 debug 签名不同，覆盖安装前先 adb uninstall com.risenav.rokid.reader
```

无测试、无 lint（`lint.checkReleaseBuilds=false`，因为 lint 工具需联网下载，被本机代理挡住）。

`app/build.gradle.kts` 里显式开了 `buildFeatures.buildConfig = true`（AGP 8 默认关闭），`MainActivity.logEvent` 用 `BuildConfig.DEBUG` 门控日志。

## 关键架构

### 渲染：全 Canvas 自绘，无 XML 布局

所有界面（书架 `LibraryView`、阅读页 `ReaderView`）都是自定义 `View` + `Canvas` 绘制。字体大小、行高、边距都是像素硬编码——改字号/间距直接改对应 View 里的 `textSize`、`rowHeight`、`paddingX` 常量。

### 分页模型：行偏移，非页列表

`Book` 不维护"页"的列表，而是：全文折成 `lines: List<String>`，阅读位置 = `position`（起始行索引）。翻页 = `position += 每页行数 × 翻页幅度`。这支持半页/¾页翻页（有内容重叠）。字号变化时重新折行，用 `progressRatio`（0~1 浮点）恢复位置——**持久化只存 ratio，不存行号**（因为行号随字号变化）。

**关键坑**：`Book.load()` 只填 `paragraphs`，折行要等 `ReaderView` 第一次 `paginate()`（需 View measure 完有尺寸）。折行前调 `seekToRatio` 会算出 `maxPos = 0`、把 position 重置为 0——所以 `Book` 里用 `pendingRatio` 暂存待恢复的进度，首次 `paginate()` 完成后才消费。`ReaderView.book` setter 必须主动调 `repaginate()`（不能只靠 `onSizeChanged`——书架打开书时 View 尺寸不变，`onSizeChanged` 不触发）。

`scrollBy` 的上限是 `lineCount - linesPerPage`（不是 `lineCount - 1`）——保证最后一页总是满页，不会滚到只剩 1 行的"死页"。

单文件大小上限 30MB（`Book.maxFileBytes`），超限拒绝加载——txt/epub 全量读入内存，更大会 OOM。

### 输入：眼镜滚轮的事件映射

Rokid 眼镜滚轮滚一下 = 系统发 `NOTIFICATION` + `RIGHT/LEFT` + `DOWN/UP` 四个键；滚轮按压**同时**发触摸事件（`ACTION_DOWN/UP`）和按键事件（`KEYCODE_ENTER` 或 `KEYCODE_BACK`，取决于固件）。`MainActivity.dispatchKeyEvent` 里：
- `NOTIFICATION`、`RIGHT/LEFT` 直接消费不响应（前导噪声）
- 只用 `UP/DOWN` 驱动导航（避免一次滚动触发两次移动）
- `BACK` 键 = 阅读时回书架，书架页退出程序
- `ENTER` 键 = 书架打开书/切换传书，阅读时**下一页**（与滚轮下滚一致）

**按压同时发触摸和按键，两个通道只能绑一个动作**——阅读页触摸翻页已删（`MainActivity.onTouchEvent` 直接 `super`），否则一次按压翻两页。书架页触摸点选由 `LibraryView.onTouchEvent` 处理，有 24px 的 touchSlop 容差区分点选/滑动。

触摸板滚轮发的是 `MotionEvent.ACTION_SCROLL`（AXIS_VSCROLL），在 `dispatchGenericMotionEvent` 拦截（WebView/子 View 会抢 `onGenericMotionEvent`，必须在 dispatch 层拦）。

### 输入：无障碍动作通道（蓝牙指环）

R08 指环这类外设由**无障碍服务**接管：它吃掉指环按键，不会转发 KeyEvent，只会对当前界面执行无障碍动作，动作失败才退回注入假触摸。阅读页触摸不响应（见上），所以假触摸全部无效——`ReaderView`/`LibraryView` 因此在无障碍节点上上报动作：`ACTION_CLICK`（阅读=下一页，书架=打开选中项）、`ACTION_SCROLL_FORWARD/BACKWARD`（阅读=下/上一页，书架=下/上移选中）。

三条硬约束，改动前必读：

- **只改节点信息，不改 View 状态**：在 `onInitializeAccessibilityNodeInfo` 里设 `info.isClickable/isScrollable`，**绝不能**调 `setClickable`/`setOnClickListener`——那会让 View 真的响应触摸，滚轮按压的「触摸 + ENTER」双通道又会翻两页。
- **绝不能设 focusable**：无障碍服务的单轴导航先试 `ACTION_FOCUS`，一旦成功就直接返回、不再发 scroll，翻页会整个失效。纯绘制 View 默认不可获焦，正好满足。
- **`ReaderView.readerActive` 必须跟着书架开关走**（`MainActivity.showLibrary/hideLibrary`）：兄弟 View 遮挡不会让 `isVisibleToUser` 变 false，书架打开时若还上报翻页动作，指环会翻动看不见的书。

另外两个 View 都设了 `importantForAccessibility = YES`：纯绘制 View 没有文字/描述，默认可能被剪出节点树。书架的 scroll 动作到头也返回 `true`——返回 `false` 会让服务退回注入假滑动，而书架的 `onTouchEvent` 会响应它，把选中项跳到假坐标落点上。

调试：`adb logcat -s R08Bridge R08Navigator R08Gestures` 看服务侧。出现 `Performed accessibility scroll` / `Clicked focused accessibility node` 说明走通了；出现 `Dispatched vertical swipe` / `Dispatched tap` 说明又退回假触摸。

调试输入映射：`adb logcat -d | grep RokidInput` 会打出所有按键/滚轮/触摸事件（仅 debug 包，release 被 `BuildConfig.DEBUG` 关掉）。

### 局域网服务器：手写零依赖 HTTP

`TransferServer` 是手写极简 HTTP 服务器（不引 NanoHTTPD——gradle 拉依赖被代理挡，且只需两个端点）。**关键坑**：请求解析必须全程用原始字节流按 `\r\n\r\n` 切分 headers/body，绝不能用 `BufferedReader.readLine()`——它的内部缓冲区会吞掉部分 body 字节，导致按 `Content-Length` 读 body 时永远读不满、阻塞超时（浏览器报 `ERR_EMPTY_RESPONSE`）。

multipart 上传同样在**原始字节数组**上找 boundary（`indexOf(ByteArray, ByteArray)`），不能解码成 String 再 `indexOf`——boundary 字节序列可能恰好出现在二进制文件内容里（epub 是 zip），String 编解码会干扰匹配导致提前截断。

服务器还提供 `/api/books`、`/api/settings`、`/download/xx`、`/delete/xx` 端点，网页管理界面（书单/下载/删除/改字号/改翻页幅度）是内嵌在 `managePage()` 里的单文件 HTML。下载是流式 `inputStream().copyTo(output)`（不全量读内存）。请求体上限 200MB。

上传重名文件用 `uniqueFile()` 去重时**保留原扩展名**（`name_1.epub` 不是 `name_1.txt`——后者会让 `Book.load()` 按扩展名路由走错解析器）。`title` 用 `substringBeforeLast('.')` 去扩展名（不能硬编码 `removeSuffix(".txt")`）。

### 格式解析：按扩展名路由

`Book.load()` 按文件扩展名分发：
- `.epub` → `EpubParser`（zip 解包 + OPF spine 顺序 + 正则剥 XHTML 标签，零依赖）
- `.md` → 纯文本读取后剥 Markdown 标记
- 其他 → 纯文本，编码探测（UTF-8 严格解码失败则回退 GBK）

**EpubParser 的坑**：
- zip 解压只读 `.opf`/`.html`/`.xhtml`/`.htm`/`.ncx`，跳过图片/字体/CSS（省内存）
- OPF 的 `<item>`/`<itemref>` 标签可能跨行（属性间有换行），正则必须 `DOT_MATCHES_ALL` 先匹配整个标签再提取属性，不能 `<item[^>]*`（`[^>]*` 不匹配换行）
- OPF `href` 可能含 `../`（OPF 在子目录引用上级文件），`$opfDir/$href` 直接拼会得到 `OEBPS/../text/ch1.xhtml`——要 `normalizePath()` 规范化
- 空章节被跳过时章节标题序号要用 **spine 下标**（`spine.withIndex()`），不能用 `chapters.size`（会跳号）
- XHTML 块级标签列表要含 `td`/`th`（否则表格单元格挤一行）
- 章节标题作为独立段落插入，用 `「」` 包裹——`ReaderView.onDraw` 识别后加粗渲染

### 设置与进度持久化

- `LibraryStore`: SharedPreferences + JSON，书架（uri/title）+ 每本书进度 ratio
  - **进度 key 用完整 uri 字符串**（`progress:file:///...`），不用 `uri.hashCode()`——旧版本用 hashCode 有撞 hash 风险，`init` 块里的 `migrateProgressKeys()` 负责一次性迁移旧 key 并清理已删书的孤儿 key
  - `BookEntry.progress` 不入 JSON（`saveBooks` 只存 uri/title），`loadBooks` 时现查 SharedPreferences
- `SettingsStore`: SharedPreferences，字号档位索引 + 翻页幅度（0.5/0.75/1.0）
  - `fontSizeIndex` 的 setter 和 getter 都要 `coerceIn`——网页可能传越界值
- 网页改设置后服务器回调 `onLibraryChanged` → `ReaderView.applySettings()` 重新分页

### 书架 UI 的坑

- `LibraryView.hitTest` 起点必须是 `listStartY`（不能是 `listStartY - 58f`）——"书架"标题画在 y=88，起点 74 会把标题区域误判为第一项，点标题就打开第一本书
- `ellipsize` 用 `Paint.breakText` 一次算截断位置（O(n)），不能逐字 `dropLast + measureText`（O(n²) 且会切坏 UTF-16 代理对/emoji）
- 传书服务器开关切换会改 `ServerToggle` 行高（76↔130），`serverRunning` setter 里要调 `ensureSelectedVisible()` 防止选中项被挤出可视窗口

## 修改时的注意事项

- **类注释里别写 `*/`**：`TransferServer.kt` 类注释曾因写 `/download/*` 导致 `*/` 提前闭合注释块，整个文件编译失败。写端点文档时用 `xx` 代替 `*`。
- **中文文件名**：multipart 的 `filename=` 字段，浏览器可能发 UTF-8 原始字节或百分号编码，`TransferServer.decodeFileName()` 两种都要兼容（先 URLDecoder，再 latin-1→UTF-8 重解码）。
- **单色屏 UI**：新增 UI 元素用 `Color.BLACK` 背景 + `Color.WHITE` 文字，高亮只用白色描边（`Paint.Style.STROKE`），不要灰色填充——单色屏显示不出灰度层次。
- keystore 密码硬编码在 `app/build.gradle.kts`（个人项目），仓库公开前应挪到环境变量。
- **别引入外部 intent**：manifest 里没有 ACTION_VIEW intent-filter，所有书必须走书架（LAN 上传）。曾经支持文件管理器直接打开 txt，但 content:// uri 无法入书架、进度恢复路径不一致，已移除。
