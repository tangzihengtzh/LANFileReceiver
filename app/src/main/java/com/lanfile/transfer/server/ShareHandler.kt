package com.lanfile.transfer.server

import android.content.Context
import android.net.Uri
import com.lanfile.transfer.media.SharedFileAccess
import com.lanfile.transfer.repository.ShareRepository
import com.lanfile.transfer.util.AppLog
import java.net.URLEncoder

/**
 * 处理「手机 → 电脑」文件接口。
 *
 * - GET /api/files            列出手机端已选文件
 * - GET /api/files/{id}       下载原始文件
 * - GET /api/files/{id}/thumb 下载缩略图（仅图片，供电脑网页预览）
 */
class ShareHandler(
    private val context: Context,
    private val repository: ShareRepository
) {

    private val thumbnailCache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_THUMBNAIL_CACHE
    }

    /** 返回 true 表示该请求已由本处理器处理。 */
    fun handle(request: HttpRequest, response: HttpResponseWriter): Boolean {
        if (!request.path.startsWith(FILES_PREFIX)) return false

        val segments = request.path
            .removePrefix(FILES_PREFIX)
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }

        if (request.method != "GET") {
            response.sendJson(405, UploadHandler.errorJson("请求方法不支持"))
            return true
        }

        when {
            segments.isEmpty() -> serveList(response)
            segments.size == 1 -> serveFile(segments[0], response)
            segments.size == 2 && segments[1] == "thumb" -> serveThumbnail(segments[0], response)
            else -> response.sendJson(404, UploadHandler.errorJson("接口不存在"))
        }
        return true
    }

    private fun serveList(response: HttpResponseWriter) {
        val files = repository.files.value
        val body = buildString {
            append("{\"success\":true,\"files\":[")
            files.forEachIndexed { index, file ->
                if (index > 0) append(',')
                append('{')
                append("\"id\":").append(UploadHandler.jsonString(file.id))
                append(",\"name\":").append(UploadHandler.jsonString(file.displayName))
                append(",\"size\":").append(file.size)
                append(",\"mimeType\":").append(UploadHandler.jsonString(file.mimeType))
                append(",\"image\":").append(file.isImage)
                append(",\"downloaded\":").append(file.downloaded)
                append(",\"addedAt\":").append(file.addedAt)
                append('}')
            }
            append("]}")
        }
        response.sendJson(200, body)
    }

    private fun serveFile(id: String, response: HttpResponseWriter) {
        val file = repository.find(id)
        if (file == null) {
            response.sendJson(404, UploadHandler.errorJson("文件不存在或已从手机移除"))
            return
        }

        val uri = Uri.parse(file.uri)
        val stream = try {
            SharedFileAccess.openStream(context, uri)
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "打开文件失败：${t.message}", t)
            null
        }
        if (stream == null) {
            response.sendJson(410, UploadHandler.errorJson("无法读取该文件，请在手机上重新选择"))
            return
        }

        val length = SharedFileAccess.lengthOf(context, uri, file.size)
        val disposition = "attachment; filename=\"${asciiFallbackName(file.displayName)}\"; " +
            "filename*=UTF-8''${encodeRfc5987(file.displayName)}"

        try {
            response.sendStream(
                status = 200,
                contentType = file.mimeType,
                contentLength = if (length > 0) length else -1L,
                extraHeaders = mapOf(
                    "Content-Disposition" to disposition,
                    "X-Content-Type-Options" to "nosniff"
                )
            ) { output ->
                stream.use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            repository.markDownloaded(id)
            AppLog.i(AppLog.SERVER, "文件已发送到电脑：${file.displayName}")
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "发送文件中断：${t.message}", t)
            try {
                stream.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun serveThumbnail(id: String, response: HttpResponseWriter) {
        val file = repository.find(id)
        if (file == null) {
            response.sendJson(404, UploadHandler.errorJson("文件不存在或已从手机移除"))
            return
        }
        if (!file.isImage) {
            response.sendJson(415, UploadHandler.errorJson("该文件没有缩略图"))
            return
        }

        val cached = synchronized(thumbnailCache) { thumbnailCache[id] }
        if (cached != null) {
            response.sendBytes(200, JPEG_CONTENT_TYPE, cached)
            return
        }

        val bitmap = try {
            SharedFileAccess.loadThumbnailBitmap(context, Uri.parse(file.uri), THUMBNAIL_SIZE)
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "生成缩略图失败：${t.message}", t)
            null
        }
        if (bitmap == null) {
            response.sendJson(404, UploadHandler.errorJson("无法生成缩略图"))
            return
        }

        val bytes = SharedFileAccess.encodeJpeg(bitmap)
        bitmap.recycle()
        if (bytes == null) {
            response.sendJson(500, UploadHandler.errorJson("缩略图编码失败"))
            return
        }

        synchronized(thumbnailCache) { thumbnailCache[id] = bytes }
        response.sendBytes(200, JPEG_CONTENT_TYPE, bytes)
    }

    private fun encodeRfc5987(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** 非 ASCII 文件名在 filename= 中必须退化为 ASCII，真实文件名由 filename* 提供。 */
    private fun asciiFallbackName(name: String): String {
        val builder = StringBuilder(name.length)
        for (character in name) {
            builder.append(
                if (character.code in 32..126 && character != '"' && character != '\\') character else '_'
            )
        }
        return builder.toString()
    }

    private companion object {
        const val FILES_PREFIX = "/api/files"
        const val JPEG_CONTENT_TYPE = "image/jpeg"
        const val THUMBNAIL_SIZE = 480
        const val MAX_THUMBNAIL_CACHE = 64
        const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
    }
}
