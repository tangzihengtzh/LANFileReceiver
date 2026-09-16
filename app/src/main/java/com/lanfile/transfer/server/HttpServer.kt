package com.lanfile.transfer.server

import com.lanfile.transfer.util.AppLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 解析后的 HTTP 请求。
 */
class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val input: InputStream,
    val contentLength: Long,
    val clientAddress: String
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    fun query(name: String): String? = query[name]
}

/**
 * HTTP 响应写出器。所有响应都带上 Content-Length 并关闭连接。
 */
class HttpResponseWriter(private val output: OutputStream) {

    fun sendBytes(
        status: Int,
        contentType: String?,
        bytes: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            for ((key, value) in extraHeaders) {
                append(key).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    fun sendText(status: Int, text: String, contentType: String = "text/plain; charset=utf-8") {
        sendBytes(status, contentType, text.toByteArray(Charsets.UTF_8))
    }

    fun sendJson(status: Int, json: String) {
        sendBytes(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }
}

/**
 * 极简 HTTP/1.1 服务器。
 *
 * 设计取舍：只依赖 JDK 的 ServerSocket，不引入第三方库，便于控制流式读取行为，
 * 所有上传数据都通过 InputStream/OutputStream 直接落盘，内存占用与文件大小无关。
 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpRequest, HttpResponseWriter) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lan-http-worker").apply { isDaemon = true }
    }

    private val activeSockets: MutableSet<Socket> =
        Collections.synchronizedSet(HashSet<Socket>())

    private val connectionSlots = Semaphore(MAX_CONNECTIONS)
    private val connectionCount = AtomicInteger(0)

    @Volatile
    var isRunning: Boolean = false
        private set

    var onConnectionCountChanged: ((Int) -> Unit)? = null

    /**
     * 绑定 0.0.0.0:port 并开始接受连接。
     * 端口被占用等错误会直接抛出，由调用方转换为用户可读提示。
     */
    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress("0.0.0.0", port), BACKLOG)
        } catch (t: Throwable) {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            throw t
        }
        serverSocket = socket
        isRunning = true
        acceptThread = Thread({ acceptLoop(socket) }, "lan-http-accept").apply {
            isDaemon = true
            start()
        }
        AppLog.i(AppLog.SERVER, "服务器已启动，监听 0.0.0.0:$port")
    }

    fun stop() {
        if (!isRunning && serverSocket == null) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        val snapshot = synchronized(activeSockets) { activeSockets.toList() }
        snapshot.forEach { socket ->
            try {
                socket.close()
            } catch (_: Throwable) {
            }
        }
        synchronized(activeSockets) { activeSockets.clear() }
        AppLog.i(AppLog.SERVER, "服务器已停止")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (isRunning) {
                    AppLog.w(AppLog.SERVER, "accept 异常：${t.message}", t)
                    continue
                }
                break
            }
            if (!connectionSlots.tryAcquire()) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
                continue
            }
            try {
                workers.execute { handleConnection(client) }
            } catch (_: Throwable) {
                connectionSlots.release()
                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        activeSockets.add(socket)
        val count = connectionCount.incrementAndGet()
        onConnectionCountChanged?.invoke(count)
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), IO_BUFFER_SIZE)
            val output = BufferedOutputStream(socket.getOutputStream(), IO_BUFFER_SIZE)
            val request = readRequest(input, socket)
            if (request != null) {
                AppLog.d(AppLog.SERVER, "客户端 ${request.clientAddress} ${request.method} ${request.path}")
                handler(request, HttpResponseWriter(output))
            }
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "连接处理异常：${t.message}", t)
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            activeSockets.remove(socket)
            connectionSlots.release()
            val remaining = connectionCount.decrementAndGet()
            onConnectionCountChanged?.invoke(remaining.coerceAtLeast(0))
        }
    }

    private fun readRequest(input: InputStream, socket: Socket): HttpRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw IOException("非法请求行：$requestLine")
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                    line.substring(separator + 1).trim()
            }
        }

        val questionMark = target.indexOf('?')
        val rawPath = if (questionMark >= 0) target.substring(0, questionMark) else target
        val rawQuery = if (questionMark >= 0) target.substring(questionMark + 1) else ""
        val path = try {
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (_: Throwable) {
            rawPath
        }

        val transferEncoding = headers["transfer-encoding"]
        val body: InputStream =
            if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
                ChunkedInputStream(input)
            } else {
                input
            }

        return HttpRequest(
            method = method,
            rawTarget = target,
            path = path,
            query = parseQuery(rawQuery),
            headers = headers,
            input = body,
            contentLength = headers["content-length"]?.trim()?.toLongOrNull() ?: -1L,
            clientAddress = socket.inetAddress?.hostAddress ?: ""
        )
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = if (index >= 0) pair.substring(0, index) else pair
            val value = if (index >= 0) pair.substring(index + 1) else ""
            val decodedKey = try {
                URLDecoder.decode(key, "UTF-8")
            } catch (_: Throwable) {
                key
            }
            val decodedValue = try {
                URLDecoder.decode(value, "UTF-8")
            } catch (_: Throwable) {
                value
            }
            result[decodedKey] = decodedValue
        }
        return result
    }

    private companion object {
        const val BACKLOG = 64
        const val MAX_CONNECTIONS = 24
        const val SOCKET_TIMEOUT_MS = 60_000
        const val IO_BUFFER_SIZE = 64 * 1024
    }
}

/**
 * 按行读取（仅用于请求行与头部，不用于文件数据）。
 */
internal fun readAsciiLine(input: InputStream, maxLength: Int = 16 * 1024): String? {
    val buffer = ByteArrayOutputStream(128)
    while (true) {
        val value = input.read()
        if (value < 0) {
            return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
        }
        if (value == '\n'.code) {
            var bytes = buffer.toByteArray()
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                bytes = bytes.copyOf(bytes.size - 1)
            }
            return String(bytes, Charsets.ISO_8859_1)
        }
        buffer.write(value)
        if (buffer.size() > maxLength) throw IOException("HTTP 头部过长")
    }
}

/**
 * chunked 传输编码解码流，用于兼容分块上传的客户端。
 */
internal class ChunkedInputStream(private val source: InputStream) : InputStream() {

    private var remaining = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (finished) return -1
        if (length == 0) return 0
        if (remaining == 0L && !nextChunk()) return -1
        val toRead = minOf(remaining, length.toLong()).toInt()
        val count = source.read(buffer, offset, toRead)
        if (count < 0) throw EOFException("分块数据意外结束")
        remaining -= count
        return count
    }

    private fun nextChunk(): Boolean {
        while (true) {
            val line = readAsciiLine(source) ?: run {
                finished = true
                return false
            }
            if (line.isEmpty()) continue
            val sizeText = line.substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16) ?: throw IOException("非法分块长度：$line")
            if (size == 0L) {
                while (true) {
                    val trailer = readAsciiLine(source) ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return false
            }
            remaining = size
            return true
        }
    }
}
