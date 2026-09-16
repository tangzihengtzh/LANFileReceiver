package com.lanfile.transfer.server

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * 流式 multipart/form-data 解析器。
 *
 * 特点：
 * - 固定内存占用（约 64KB + 单个头部行），与上传文件体积无关；
 * - 支持超大文件（GB 级）逐块读取并直接写入磁盘；
 * - 支持文件名 UTF-8 与 RFC 5987（filename*）编码，中文名不乱码。
 */
class MultipartStream(
    private val input: InputStream,
    boundary: String
) {

    data class Part(
        val name: String?,
        val fileName: String?,
        val contentType: String?,
        val headers: Map<String, String>
    )

    private val boundaryMarker = "--$boundary"
    private val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)

    private var buffer = ByteArray(INITIAL_BUFFER_SIZE)
    private var head = 0
    private var tail = 0
    private var endOfInput = false

    private var finished = false
    private var bodyComplete = true
    private var lastPart = false

    init {
        if (!seekFirstBoundary()) {
            finished = true
        }
    }

    /**
     * 读取下一个 part 的头部信息。返回 null 表示整个 multipart 结束。
     */
    @Throws(IOException::class)
    fun nextPart(): Part? {
        if (finished) return null
        if (!bodyComplete) drainCurrentBody()
        if (lastPart) {
            finished = true
            return null
        }

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine() ?: throw EOFException("multipart 头部意外结束")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] =
                    line.substring(separator + 1).trim()
            }
        }

        val disposition = headers["content-disposition"]
        val name = parameterValue(disposition, "name")
        val encodedFileName = parameterValue(disposition, "filename*")
        val fileName = if (encodedFileName != null) {
            decodeRfc5987(encodedFileName)
        } else {
            parameterValue(disposition, "filename")
        }

        bodyComplete = false
        return Part(name, fileName, headers["content-type"], headers)
    }

    /**
     * 读取当前 part 的数据，返回写入字节数；返回 -1 表示当前 part 已结束。
     */
    @Throws(IOException::class)
    fun readBody(target: ByteArray, offset: Int, length: Int): Int {
        if (bodyComplete) return -1
        if (length == 0) return 0
        while (true) {
            val delimiterIndex = indexOfDelimiter()
            if (delimiterIndex >= 0) {
                val available = delimiterIndex - head
                if (available > 0) {
                    val count = minOf(available, length)
                    System.arraycopy(buffer, head, target, offset, count)
                    head += count
                    return count
                }
                head = delimiterIndex + delimiter.size
                consumeDelimiterSuffix()
                bodyComplete = true
                return -1
            }

            val safeEnd = tail - (delimiter.size - 1)
            val safeCount = safeEnd - head
            if (safeCount > 0) {
                val count = minOf(safeCount, length)
                System.arraycopy(buffer, head, target, offset, count)
                head += count
                return count
            }

            if (endOfInput) {
                // 未找到结束边界（客户端异常中断），把剩余数据全部吐出后结束
                val remaining = tail - head
                if (remaining > 0) {
                    val count = minOf(remaining, length)
                    System.arraycopy(buffer, head, target, offset, count)
                    head += count
                    return count
                }
                bodyComplete = true
                return -1
            }

            fill()
        }
    }

    /**
     * 丢弃当前 part 剩余数据（用于忽略非文件字段）。
     */
    @Throws(IOException::class)
    fun drainCurrentBody() {
        if (bodyComplete) return
        val scratch = ByteArray(DRAIN_BUFFER_SIZE)
        while (readBody(scratch, 0, scratch.size) >= 0) {
            // 丢弃
        }
    }

    /**
     * 丢弃尚未消费的整个请求体。
     */
    @Throws(IOException::class)
    fun drainAll() {
        while (true) {
            if (!bodyComplete) drainCurrentBody()
            if (nextPart() == null) break
        }
    }

    // ---------------- 内部实现 ----------------

    private fun seekFirstBoundary(): Boolean {
        while (true) {
            val line = readLine() ?: return false
            if (line == boundaryMarker) return true
            if (line == "$boundaryMarker--") {
                lastPart = true
                finished = true
                return false
            }
            // 其它内容为前导数据，忽略
        }
    }

    /**
     * 边界匹配之后需要区分：
     * - "\r\n"      -> 后面还有 part
     * - "--"        -> 整个 multipart 结束
     */
    private fun consumeDelimiterSuffix() {
        val first = readByte() ?: run {
            lastPart = true
            finished = true
            return
        }
        when {
            first == '-'.code -> {
                val second = readByte()
                if (second == '-'.code) {
                    lastPart = true
                }
                readLine() // 消费结尾 CRLF
            }
            first == '\r'.code -> {
                val second = readByte()
                if (second != '\n'.code && second != null) {
                    // 容错：忽略异常字节
                }
            }
            else -> {
                // 容错：忽略
            }
        }
    }

    private fun indexOfDelimiter(): Int {
        val pattern = delimiter
        val firstByte = pattern[0]
        val lastStart = tail - pattern.size
        var index = head
        while (index <= lastStart) {
            if (buffer[index] != firstByte) {
                index++
                continue
            }
            var offset = 1
            while (offset < pattern.size && buffer[index + offset] == pattern[offset]) {
                offset++
            }
            if (offset == pattern.size) return index
            index++
        }
        return -1
    }

    private fun fill() {
        if (head > 0) {
            System.arraycopy(buffer, head, buffer, 0, tail - head)
            tail -= head
            head = 0
        }
        if (tail >= buffer.size) {
            if (buffer.size >= MAX_BUFFER_SIZE) {
                throw IOException("multipart 数据块过大")
            }
            buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_BUFFER_SIZE))
        }
        if (endOfInput) return
        val count = input.read(buffer, tail, buffer.size - tail)
        if (count < 0) {
            endOfInput = true
        } else {
            tail += count
        }
    }

    private fun readByte(): Int? {
        if (head >= tail) {
            fill()
            if (head >= tail) return null
        }
        return buffer[head++].toInt() and 0xFF
    }

    /**
     * 读取一行（以 UTF-8 解码，保证中文文件名正确）。
     */
    private fun readLine(): String? {
        val collected = ByteArrayOutputStream(128)
        while (true) {
            val value = readByte()
            if (value == null) {
                if (collected.size() == 0) return null
                return decodeHeaderLine(collected.toByteArray())
            }
            if (value == '\n'.code) {
                var bytes = collected.toByteArray()
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes = bytes.copyOf(bytes.size - 1)
                }
                return decodeHeaderLine(bytes)
            }
            collected.write(value)
            if (collected.size() > MAX_HEADER_LINE) throw IOException("multipart 头部行过长")
        }
    }

    private fun decodeHeaderLine(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.indexOf('\uFFFD') >= 0) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    /**
     * 从 Content-Disposition 中取出指定参数，支持带引号与不带引号两种形式。
     */
    private fun parameterValue(header: String?, key: String): String? {
        if (header.isNullOrEmpty()) return null
        for (segment in splitParameters(header)) {
            val separator = segment.indexOf('=')
            if (separator <= 0) continue
            val name = segment.substring(0, separator).trim()
            if (!name.equals(key, ignoreCase = true)) continue
            var value = segment.substring(separator + 1).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            return value
        }
        return null
    }

    private fun splitParameters(header: String): List<String> {
        val segments = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (character in header) {
            when {
                character == '"' -> {
                    inQuotes = !inQuotes
                    current.append(character)
                }
                character == ';' && !inQuotes -> {
                    segments.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(character)
            }
        }
        segments.add(current.toString())
        return segments
    }

    private fun decodeRfc5987(value: String): String {
        val index = value.indexOf("''")
        val encoded = if (index >= 0) value.substring(index + 2) else value
        return try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Throwable) {
            encoded
        }
    }

    private companion object {
        const val INITIAL_BUFFER_SIZE = 64 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024
        const val MAX_HEADER_LINE = 64 * 1024
        const val DRAIN_BUFFER_SIZE = 64 * 1024
    }
}
