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

## 关键架构

### 渲染：全 Canvas 自绘，无 XML 布局

所有界面（书架 `LibraryView`、阅读页 `ReaderView`）都是自定义 `View` + `Canvas` 绘制。字体大小、行高、边距都是像素硬编码——改字号/间距直接改对应 View 里的 `textSize`、`rowHeight`、`paddingX` 常量。

### 分页模型：行偏移，非页列表

`Book` 不维护"页"的列表，而是：全文折成 `lines: List<String>`，阅读位置 = `position`（起始行索引）。翻页 = `position += 每页行数 × 翻页幅度`。这支持半页/¾页翻页（有内容重叠）。字号变化时重新折行，用 `progressRatio`（0~1 浮点）恢复位置——**持久化只存 ratio，不存行号**（因为行号随字号变化）。

### 输入：眼镜滚轮的事件映射

Rokid 眼镜滚轮滚一下 = 系统发 `NOTIFICATION` + `RIGHT/LEFT` + `DOWN/UP` 四个键，滚轮按压 = `KEYCODE_BACK` 或触摸事件。`MainActivity.dispatchKeyEvent` 里：
- `NOTIFICATION`、`RIGHT/LEFT` 直接消费不响应（前导噪声）
- 只用 `UP/DOWN` 驱动导航（避免一次滚动触发两次移动）
- `BACK` 键 = 回退/退出

触摸板滚轮发的是 `MotionEvent.ACTION_SCROLL`（AXIS_VSCROLL），在 `dispatchGenericMotionEvent` 拦截（WebView/子 View 会抢 `onGenericMotionEvent`，必须在 dispatch 层拦）。

调试输入映射：`adb logcat -d | grep RokidInput` 会打出所有按键/滚轮/触摸事件。

### 局域网服务器：手写零依赖 HTTP

`TransferServer` 是手写极简 HTTP 服务器（不引 NanoHTTPD——gradle 拉依赖被代理挡，且只需两个端点）。**关键坑**：请求解析必须全程用原始字节流按 `\r\n\r\n` 切分 headers/body，绝不能用 `BufferedReader.readLine()`——它的内部缓冲区会吞掉部分 body 字节，导致按 `Content-Length` 读 body 时永远读不满、阻塞超时（浏览器报 `ERR_EMPTY_RESPONSE`）。

服务器还提供 `/api/books`、`/api/settings`、`/download/*`、`/delete/*` 端点，网页管理界面（书单/下载/删除/改字号/改翻页幅度）是内嵌在 `managePage()` 里的单文件 HTML。

### 格式解析：按扩展名路由

`Book.load()` 按文件扩展名分发：
- `.epub` → `EpubParser`（zip 解包 + OPF spine 顺序 + 正则剥 XHTML 标签，零依赖）
- `.md` → 纯文本读取后剥 Markdown 标记
- 其他 → 纯文本，编码探测（UTF-8 严格解码失败则回退 GBK）

### 设置与进度持久化

- `LibraryStore`: SharedPreferences + JSON，书架（uri/title）+ 每本书进度 ratio
- `SettingsStore`: SharedPreferences，字号档位索引 + 翻页幅度（0.5/0.75/1.0）
- 网页改设置后服务器回调 `onLibraryChanged` → `ReaderView.applySettings()` 重新分页

## 修改时的注意事项

- **类注释里别写 `*/`**：`TransferServer.kt` 类注释曾因写 `/download/*` 导致 `*/` 提前闭合注释块，整个文件编译失败。写端点文档时用 `xx` 代替 `*`。
- **中文文件名**：multipart 的 `filename=` 字段，浏览器可能发 UTF-8 原始字节或百分号编码，`TransferServer.decodeFileName()` 两种都要兼容（先 URLDecoder，再 latin-1→UTF-8 重解码）。
- **单色屏 UI**：新增 UI 元素用 `Color.BLACK` 背景 + `Color.WHITE` 文字，高亮只用白色描边（`Paint.Style.STROKE`），不要灰色填充——单色屏显示不出灰度层次。
- keystore 密码硬编码在 `app/build.gradle.kts`（个人项目），仓库公开前应挪到环境变量。
