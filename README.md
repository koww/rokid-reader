# Rokid 智能眼镜小说阅读器

Rokid 智能眼镜的本地小说阅读器。纯 Android 应用，无后端，书籍通过局域网从电脑浏览器上传。

针对**单色衍射光波导屏**优化：全黑底白字，无彩色、无灰度，高亮只用白色描边。

## 功能

- 📚 本地书架管理（txt / epub / md）
- 🌐 局域网网页传书（电脑浏览器拖拽上传 / 下载 / 删除）
- 📖 翻页阅读，支持半页 / ¾页 / 整页翻页幅度
- 🔤 三档字号（网页端切换）
- 💾 自动保存阅读进度，重开续读
- 🎡 眼镜滚轮 / 触摸板全操作，无需触屏

## 操作方式

| 输入 | 书架页 | 阅读页 |
|------|--------|--------|
| 滚轮上滚 | 上移选中 | 上一页 |
| 滚轮下滚 | 下移选中 | 下一页 |
| 滚轮按压（ENTER） | 打开书 / 切换传书 | 下一页 |
| BACK | 退出程序 | 回书架 |

字号和翻页幅度在传书网页里改，实时同步到眼镜。

## 使用

1. 眼镜上打开 App，书架选中"局域网传书…"，按滚轮确认启动服务
2. 屏幕显示地址（如 `http://192.168.1.100:8000`），电脑浏览器打开
3. 拖拽 txt / epub / md 文件上传，或在网页里管理书架、改字号、改翻页幅度
4. 眼镜上书架上出现新书，滚轮选中、按压打开

单文件上限 30MB。

## 构建

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # 签名 release APK（需 rokid-reader.keystore）

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

release 与 debug 签名不同，覆盖安装前先 `adb uninstall com.risenav.rokid.reader`。

## 技术要点

- **零依赖**：HTTP 服务器、EPUB 解析（zip + OPF + XHTML 剥标签）全部手写，不引第三方库
- **全 Canvas 自绘**：无 XML 布局，书架和阅读页都是自定义 View
- **行偏移分页**：全文折行 + 起始行索引定位，支持任意翻页幅度；进度按 0~1 比例持久化（行号随字号变化，比例不变）
- **眼镜输入适配**：滚轮的 NOTIFICATION/RIGHT/LEFT 前导噪声过滤，按压的触摸+按键双通道去重

详细架构和踩坑记录见 [CLAUDE.md](CLAUDE.md)。

## 格式支持

| 格式 | 说明 |
|------|------|
| `.txt` | UTF-8 / GBK 编码自动探测 |
| `.epub` | OPF spine 顺序解析，章节标题加粗显示 |
| `.md` | 剥 Markdown 标记后按纯文本显示 |

mobi 不支持（二进制私有格式，建议用 [Calibre](https://calibre-ebook.com/) 转 epub 后上传）。
