package com.risenav.rokid.reader

import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 局域网书库管理服务器（零依赖手写实现）。
 *
 *   GET  /             —— 管理页面（书籍列表 + 上传 + 设置）
 *   GET  /api/books    —— 书籍列表 JSON
 *   GET  /download/xx  —— 下载书籍文件
 *   GET  /api/settings —— 读取设置 JSON
 *   POST /upload       —— 上传书籍（multipart）
 *   POST /api/settings —— 保存设置（JSON body）
 *   POST /delete/xx    —— 删除书籍
 *
 * 请求解析全部基于原始字节流，不使用 BufferedReader（避免缓冲区吞 body）。
 */
class TransferServer(
    private val filesDir: File,
    private val libraryStore: LibraryStore,
    private val settingsStore: SettingsStore,
    private val port: Int = 8000,
    private val onLibraryChanged: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    private val booksDir = File(filesDir, "uploads").apply { mkdirs() }

    fun start() {
        running = true
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket(port)
                ss.reuseAddress = true
                serverSocket = ss
                while (running) {
                    val client = try { ss.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e("TransferServer", "server error", e)
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
    }

    fun localAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return "http://${addr.hostAddress}:$port"
                    }
                }
            }
            "http://<未知IP>:$port"
        } catch (e: Exception) {
            "http://<未知IP>:$port"
        }
    }

    // ---------- 请求处理 ----------

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val raw = readRequest(input) ?: return
            val headerEnd = indexOf(raw, "\r\n\r\n".toByteArray())
            if (headerEnd < 0) return

            val headerText = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val parts = lines.firstOrNull()?.split(" ") ?: return
            if (parts.size < 2) return
            val method = parts[0]
            val path = java.net.URLDecoder.decode(parts[1], "UTF-8")

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] =
                        lines[i].substring(idx + 1).trim()
                }
            }
            val body = raw.copyOfRange(headerEnd + 4, raw.size)

            when {
                method == "GET" && path == "/" ->
                    sendResponse(output, 200, "text/html; charset=utf-8", managePage().toByteArray(Charsets.UTF_8))
                method == "GET" && path == "/api/books" ->
                    sendJson(output, booksJson())
                method == "GET" && path == "/api/settings" ->
                    sendJson(output, settingsJson())
                method == "GET" && path.startsWith("/download/") ->
                    handleDownload(path.removePrefix("/download/"), output)
                method == "POST" && path == "/upload" ->
                    handleUpload(headers, body, output)
                method == "POST" && path == "/api/settings" ->
                    handleSaveSettings(body, output)
                method == "POST" && path.startsWith("/delete/") ->
                    handleDelete(path.removePrefix("/delete/"), output)
                else -> sendResponse(output, 404, "text/plain", "not found".toByteArray())
            }
        } catch (e: Exception) {
            Log.e("TransferServer", "client error", e)
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    private fun readRequest(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var headerEnd = -1
        var contentLength = -1

        while (true) {
            val n = try { input.read(chunk) } catch (e: Exception) { -1 }
            if (n < 0) break
            buffer.write(chunk, 0, n)
            val data = buffer.toByteArray()

            if (headerEnd < 0) {
                headerEnd = indexOf(data, "\r\n\r\n".toByteArray())
                if (headerEnd >= 0) {
                    val headerText = String(data, 0, headerEnd, Charsets.ISO_8859_1)
                    contentLength = headerText.split("\r\n")
                        .firstOrNull { it.lowercase().startsWith("content-length:") }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                }
            }
            if (headerEnd >= 0 && data.size >= headerEnd + 4 + contentLength) {
                return data
            }
            if (n == 0) break
        }
        val data = buffer.toByteArray()
        return if (data.isNotEmpty()) data else null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // ---------- API ----------

    private fun booksJson(): String {
        val books = libraryStore.loadBooks()
        val sb = StringBuilder("[")
        books.forEachIndexed { i, b ->
            if (i > 0) sb.append(",")
            val fileName = Uri.parse(b.uri).lastPathSegment ?: ""
            sb.append("{\"title\":").append(jsonString(b.title))
                .append(",\"file\":").append(jsonString(fileName))
                .append(",\"progress\":").append((b.progress * 100).toInt())
                .append("}")
        }
        return sb.append("]").toString()
    }

    private fun settingsJson(): String {
        return "{\"fontSizeIndex\":${settingsStore.fontSizeIndex}," +
                "\"scrollRatioIndex\":${settingsStore.scrollRatioIndex}}"
    }

    private fun handleDownload(fileName: String, output: java.io.OutputStream) {
        val file = File(booksDir, File(fileName).name)   // 防路径穿越
        if (!file.exists() || !file.isFile) {
            return sendResponse(output, 404, "text/plain", "not found".toByteArray())
        }
        val bytes = file.readBytes()
        val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Disposition: attachment; filename*=UTF-8''$encodedName\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun handleDelete(fileName: String, output: java.io.OutputStream) {
        val name = File(fileName).name
        val file = File(booksDir, name)
        if (file.exists()) {
            file.delete()
            // 同时从书架移除（uri 是 file:// 路径）
            libraryStore.removeBook(Uri.fromFile(file).toString())
            onLibraryChanged()
        }
        sendJson(output, "{\"ok\":true}")
    }

    private fun handleSaveSettings(body: ByteArray, output: java.io.OutputStream) {
        val json = String(body, Charsets.UTF_8)
        // 极简 JSON 解析（只取整数字段）
        Regex("\"fontSizeIndex\"\\s*:\\s*(\\d+)").find(json)?.let {
            settingsStore.fontSizeIndex = it.groupValues[1].toInt()
        }
        Regex("\"scrollRatioIndex\"\\s*:\\s*(\\d+)").find(json)?.let {
            settingsStore.scrollRatioIndex = it.groupValues[1].toInt()
        }
        onLibraryChanged()   // 通知 App 设置已变（重新分页等）
        sendJson(output, "{\"ok\":true}")
    }

    // ---------- 上传 ----------

    private fun handleUpload(headers: Map<String, String>, body: ByteArray, output: java.io.OutputStream) {
        val contentType = headers["content-type"] ?: return sendText(output, 400, "missing content-type")
        val boundary = contentType.substringAfter("boundary=", "").trim()
        if (boundary.isEmpty()) return sendText(output, 400, "missing boundary")

        val text = String(body, Charsets.ISO_8859_1)
        val nameMarker = "filename=\""
        val nameStart = text.indexOf(nameMarker)
        if (nameStart < 0) return sendText(output, 400, "no file field")
        val nameEnd = text.indexOf('"', nameStart + nameMarker.length)
        if (nameEnd < 0) return sendText(output, 400, "bad filename")
        val rawName = decodeFileName(text.substring(nameStart + nameMarker.length, nameEnd))

        val contentStart = text.indexOf("\r\n\r\n", nameEnd)
        if (contentStart < 0) return sendText(output, 400, "bad part header")
        val dataStart = contentStart + 4
        val dataEnd = text.indexOf("\r\n--$boundary", dataStart)
        if (dataEnd < 0) return sendText(output, 400, "bad part end")
        val fileBytes = text.substring(dataStart, dataEnd).toByteArray(Charsets.ISO_8859_1)

        // 支持的格式：txt / epub / md / markdown，其他一律按 txt 存
        val supportedExts = listOf(".txt", ".epub", ".md", ".markdown")
        val safeName = rawName
            .substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "book_${System.currentTimeMillis()}.txt" }
            .let { name ->
                if (supportedExts.any { ext -> name.endsWith(ext, true) }) name else "$name.txt"
            }
        val dest = uniqueFile(safeName)
        FileOutputStream(dest).use { it.write(fileBytes) }

        Log.d("TransferServer", "received ${fileBytes.size} bytes -> ${dest.name}")
        val title = safeName.removeSuffix(".txt")
        libraryStore.addBook(Uri.fromFile(dest).toString(), title)
        onLibraryChanged()
        sendText(output, 200, "ok: $safeName")
    }

    private fun decodeFileName(raw: String): String {
        val percentDecoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) { raw }
        return try {
            val bytes = percentDecoded.toByteArray(Charsets.ISO_8859_1)
            val utf8 = String(bytes, Charsets.UTF_8)
            if (!utf8.contains('�')) utf8 else percentDecoded
        } catch (e: Exception) {
            percentDecoded
        }
    }

    private fun uniqueFile(name: String): File {
        var f = File(booksDir, name)
        var i = 1
        while (f.exists()) {
            val base = name.removeSuffix(".txt")
            f = File(booksDir, "${base}_$i.txt")
            i++
        }
        return f
    }

    // ---------- 响应 ----------

    private fun sendResponse(output: java.io.OutputStream, code: Int, contentType: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun sendText(output: java.io.OutputStream, code: Int, msg: String) {
        sendResponse(output, code, "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8))
    }

    private fun sendJson(output: java.io.OutputStream, json: String) {
        sendResponse(output, 200, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    // ---------- 管理页 ----------

    private fun managePage(): String = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>眼镜书库管理</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: sans-serif; background:#111; color:#eee; max-width:640px;
         margin:0 auto; padding:24px 20px; }
  h1 { font-size:24px; margin:0 0 16px; }
  h2 { font-size:18px; margin:32px 0 12px; color:#aaa; font-weight:normal; }
  .card { background:#1c1c1e; border-radius:14px; padding:20px; }
  .drop { border:2px dashed #444; border-radius:12px; padding:32px 16px;
          text-align:center; color:#888; cursor:pointer; }
  .drop.over { border-color:#4af; color:#4af; }
  .book { display:flex; align-items:center; padding:12px 4px;
          border-bottom:1px solid #2c2c2e; }
  .book:last-child { border-bottom:none; }
  .book .info { flex:1; min-width:0; }
  .book .title { font-size:17px; overflow:hidden; text-overflow:ellipsis;
                 white-space:nowrap; }
  .book .meta { font-size:13px; color:#777; margin-top:2px; }
  .book button { background:none; border:1px solid #444; color:#ccc;
                 border-radius:8px; padding:6px 12px; margin-left:8px;
                 font-size:13px; cursor:pointer; flex-shrink:0; }
  .book button:hover { border-color:#4af; color:#4af; }
  .book button.del:hover { border-color:#f66; color:#f66; }
  .opt { display:flex; align-items:center; padding:10px 0; }
  .opt label { width:100px; color:#aaa; font-size:15px; }
  .opt .btns { display:flex; gap:8px; }
  .opt .btns button { background:#2c2c2e; border:none; color:#ccc;
                      border-radius:8px; padding:8px 16px; font-size:14px; cursor:pointer; }
  .opt .btns button.on { background:#4af; color:#fff; }
  .status { margin-top:14px; font-size:14px; color:#6c6; min-height:20px; text-align:center; }
  .empty { color:#555; text-align:center; padding:24px 0; font-size:15px; }
</style>
</head>
<body>
  <h1>📖 眼镜书库管理</h1>

  <div class="card">
    <div class="drop" id="drop">拖拽 txt / epub / md 到这里上传，或点击选择文件</div>
    <input type="file" id="file" accept=".txt,.epub,.md,.markdown,text/plain,application/epub+zip" multiple style="display:none">
    <div class="status" id="status"></div>
  </div>

  <h2>书架（<span id="count">0</span> 本）</h2>
  <div class="card" id="books"><div class="empty">加载中…</div></div>

  <h2>阅读设置</h2>
  <div class="card">
    <div class="opt">
      <label>字体大小</label>
      <div class="btns" id="fontBtns">
        <button data-v="0">小</button>
        <button data-v="1">中</button>
        <button data-v="2">大</button>
      </div>
    </div>
    <div class="opt">
      <label>翻页幅度</label>
      <div class="btns" id="ratioBtns">
        <button data-v="0">半页</button>
        <button data-v="1">¾页</button>
        <button data-v="2">整页</button>
      </div>
    </div>
  </div>

<script>
const ${'$'} = id => document.getElementById(id);
const drop = ${'$'}('drop'), fileInput = ${'$'}('file'), status = ${'$'}('status');

async function loadBooks() {
  const r = await fetch('/api/books');
  const books = await r.json();
  ${'$'}('count').textContent = books.length;
  const el = ${'$'}('books');
  if (!books.length) { el.innerHTML = '<div class="empty">书架为空，先上传几本吧</div>'; return; }
  el.innerHTML = books.map(b => `
    <div class="book">
      <div class="info">
        <div class="title">${'$'}{esc(b.title)}</div>
        <div class="meta">已读 ${'$'}{b.progress}%</div>
      </div>
      <button onclick="dl('${'$'}{esc(b.file)}')">下载</button>
      <button class="del" onclick="del('${'$'}{esc(b.file)}')">删除</button>
    </div>`).join('');
}
function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML;}

function dl(f){ location.href = '/download/' + encodeURIComponent(f); }
async function del(f){
  if (!confirm('从眼镜删除「' + f + '」？')) return;
  await fetch('/delete/' + encodeURIComponent(f), {method:'POST'});
  loadBooks();
}

async function upload(files) {
  if (!files.length) return;
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    const fd = new FormData();
    fd.append('file', f, f.name);
    status.textContent = '上传中 (' + (i+1) + '/' + files.length + '): ' + f.name;
    try {
      await fetch('/upload', { method:'POST', body: fd });
      status.textContent = '✓ 完成: ' + f.name;
    } catch(e) { status.textContent = '✗ 失败: ' + f.name; }
  }
  loadBooks();
}

drop.onclick = () => fileInput.click();
fileInput.onchange = () => upload(fileInput.files);
drop.ondragover = e => { e.preventDefault(); drop.classList.add('over'); };
drop.ondragleave = () => drop.classList.remove('over');
drop.ondrop = e => { e.preventDefault(); drop.classList.remove('over'); upload(e.dataTransfer.files); };

async function loadSettings() {
  const r = await fetch('/api/settings');
  const s = await r.json();
  markOn('fontBtns', s.fontSizeIndex);
  markOn('ratioBtns', s.scrollRatioIndex);
}
function markOn(id, v) {
  ${'$'}(id).querySelectorAll('button').forEach(b =>
    b.classList.toggle('on', +b.dataset.v === v));
}
async function saveSettings() {
  const font = +${'$'}('fontBtns').querySelector('.on').dataset.v;
  const ratio = +${'$'}('ratioBtns').querySelector('.on').dataset.v;
  await fetch('/api/settings', { method:'POST',
    body: JSON.stringify({fontSizeIndex: font, scrollRatioIndex: ratio}) });
  status.textContent = '✓ 设置已保存到眼镜';
}
${'$'}('fontBtns').onclick = e => { if(e.target.dataset.v){markOn('fontBtns',+e.target.dataset.v);saveSettings();} };
${'$'}('ratioBtns').onclick = e => { if(e.target.dataset.v){markOn('ratioBtns',+e.target.dataset.v);saveSettings();} };

loadBooks();
loadSettings();
</script>
</body>
</html>
""".trimIndent()
}
