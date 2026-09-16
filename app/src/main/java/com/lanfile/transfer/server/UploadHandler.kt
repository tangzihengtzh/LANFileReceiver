package com.lanfile.transfer.server

import com.lanfile.transfer.repository.TransferRepository
import com.lanfile.transfer.storage.FileStorageManager
import com.lanfile.transfer.util.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * 处理 POST /api/upload。
 *
 * 数据从 socket 直接流式写入目标文件，全程使用固定大小缓冲区。
 */
class UploadHandler(
    private val storage: FileStorageManager,
    private val repository: TransferRepository
) {

    private data class UploadResult(
        val fileName: String,
        val size: Long,
        val success: Boolean,
        val message: String?
    )

    fun handle(request: HttpRequest, response: HttpResponseWriter) {
        val contentType = request.header("content-type")
        if (contentType.isNullOrBlank()) {
            response.sendJson(400, errorJson("缺少 Content-Type"))
            return
        }
        val boundary = extractBoundary(contentType)
        if (boundary.isNullOrBlank()) {
            response.sendJson(400, errorJson("无效的 multipart/form-data 边界"))
            return
        }

        val client = request.clientAddress
        val results = ArrayList<UploadResult>()
        var fatalError: String? = null
        var declaredSize = -1L

        val multipart = try {
            MultipartStream(request.input, boundary)
        } catch (t: Throwable) {
            AppLog.e(AppLog.UPLOAD, "初始化 multipart 解析失败", t)
            response.sendJson(400, errorJson("请求体格式错误"))
            return
        }

        while (fatalError == null) {
            val part = try {
                multipart.nextPart()
            } catch (t: Throwable) {
                fatalError = "请求数据不完整（${t.javaClass.simpleName}）"
                AppLog.w(AppLog.UPLOAD, "读取 multipart 失败：${t.message}", t)
                break
            } ?: break

            val rawName = part.fileName
            if (rawName.isNullOrBlank()) {
                // 网页会在文件之前附带一个 size 字段，用于手机端显示百分比
                try {
                    declaredSize = if (part.name == FIELD_SIZE) {
                        readSmallValue(multipart).trim().toLongOrNull() ?: -1L
                    } else {
                        multipart.drainCurrentBody()
                        declaredSize
                    }
                } catch (t: Throwable) {
                    fatalError = "请求数据不完整（${t.javaClass.simpleName}）"
                }
                continue
            }

            val result = receiveFile(multipart, rawName, part.contentType, client, declaredSize)
            declaredSize = -1L
            results.add(result)
            if (!result.success) {
                fatalError = result.message
            }
        }

        if (fatalError != null && results.isEmpty()) {
            // 一个文件都没保存成功：尽量返回可读错误（连接可能已断开）
            try {
                response.sendJson(400, errorJson(fatalError))
            } catch (_: Throwable) {
            }
            return
        }

        val success = results.isNotEmpty() && results.all { it.success }
        val last = results.lastOrNull()
        val body = buildString {
            append("{\"success\":").append(success)
            if (last != null) {
                append(",\"fileName\":").append(jsonString(last.fileName))
                append(",\"size\":").append(last.size)
            }
            if (results.size > 1) {
                append(",\"files\":[")
                results.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    append("{\"fileName\":").append(jsonString(item.fileName))
                    append(",\"size\":").append(item.size)
                    append(",\"success\":").append(item.success)
                    if (item.message != null) {
                        append(",\"message\":").append(jsonString(item.message))
                    }
                    append('}')
                }
                append(']')
            }
            if (!success) {
                append(",\"message\":").append(jsonString(fatalError ?: "部分文件保存失败"))
            }
            append('}')
        }
        response.sendJson(if (success) 200 else 500, body)
    }

    private fun receiveFile(
        multipart: MultipartStream,
        rawName: String,
        partContentType: String?,
        client: String,
        declaredSize: Long
    ): UploadResult {
        val id = UUID.randomUUID().toString()
        val displayName = com.lanfile.transfer.storage.FileNameUtils.sanitize(rawName)
        repository.beginTransfer(id, displayName, declaredSize, client)
        AppLog.i(AppLog.UPLOAD, "开始接收：$displayName 来自 $client")

        var destination: FileStorageManager.Destination? = null
        try {
            // 提前检测剩余空间，避免写入一半才失败
            if (declaredSize > 0 && declaredSize > storage.availableBytes() - SPACE_MARGIN_BYTES) {
                multipart.drainCurrentBody()
                repository.failTransfer(id, "存储空间不足")
                return UploadResult(displayName, 0L, false, "存储空间不足")
            }

            destination = storage.createDestination(displayName, partContentType)
            val output = destination.open()
            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
            var total = 0L
            var lastReported = 0L

            while (true) {
                val count = multipart.readBody(buffer, 0, buffer.size)
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
                if (total - lastReported >= PROGRESS_STEP_BYTES) {
                    repository.updateProgress(id, total)
                    lastReported = total
                }
            }
            output.flush()
            output.close()

            val savedPath = destination.commit()
            repository.completeTransfer(id, total, savedPath, destination.fileName)
            AppLog.i(AppLog.UPLOAD, "接收完成：${destination.fileName} 共 $total 字节")
            return UploadResult(destination.fileName, total, true, null)
        } catch (t: Throwable) {
            destination?.abort()
            val message = describe(t)
            repository.failTransfer(id, message)
            AppLog.e(AppLog.UPLOAD, "接收失败：$displayName -> $message", t)
            return UploadResult(displayName, 0L, false, message)
        }
    }

    /** 读取一个很小的普通表单字段（例如 size）。 */
    private fun readSmallValue(multipart: MultipartStream): String {
        val collected = ByteArrayOutputStream(64)
        val buffer = ByteArray(256)
        var total = 0
        while (true) {
            val count = multipart.readBody(buffer, 0, buffer.size)
            if (count < 0) break
            total += count
            if (total <= MAX_FIELD_BYTES) collected.write(buffer, 0, count)
        }
        return collected.toString("UTF-8")
    }

    private fun describe(t: Throwable): String = when (t) {
        is IOException -> {
            val text = t.message ?: ""
            when {
                text.contains("ENOSPC", ignoreCase = true) ||
                    text.contains("No space left", ignoreCase = true) -> "存储空间不足"
                text.contains("EPERM", ignoreCase = true) ||
                    text.contains("EACCES", ignoreCase = true) -> "没有写入权限"
                else -> "写入失败：$text"
            }
        }
        else -> t.message ?: t.javaClass.simpleName
    }

    companion object {
        private const val TRANSFER_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024L
        private const val FIELD_SIZE = "size"
        private const val MAX_FIELD_BYTES = 4096
        private const val SPACE_MARGIN_BYTES = 16L * 1024 * 1024

        fun extractBoundary(contentType: String): String? {
            val marker = "boundary="
            val index = contentType.indexOf(marker, ignoreCase = true)
            if (index < 0) return null
            var value = contentType.substring(index + marker.length).trim()
            val semicolon = value.indexOf(';')
            if (semicolon >= 0) value = value.substring(0, semicolon).trim()
            if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length - 1)
            }
            return value.ifBlank { null }
        }

        fun jsonString(value: String): String {
            val builder = StringBuilder(value.length + 2)
            builder.append('"')
            for (character in value) {
                when (character) {
                    '"' -> builder.append("\\\"")
                    '\\' -> builder.append("\\\\")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> {
                        if (character < ' ') {
                            builder.append(String.format("\\u%04x", character.code))
                        } else {
                            builder.append(character)
                        }
                    }
                }
            }
            builder.append('"')
            return builder.toString()
        }

        fun errorJson(message: String): String =
            "{\"success\":false,\"message\":${jsonString(message)}}"
    }
}
