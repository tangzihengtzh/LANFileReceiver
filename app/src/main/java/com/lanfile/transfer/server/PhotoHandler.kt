package com.lanfile.transfer.server

import android.content.Context
import android.net.Uri
import com.lanfile.transfer.media.PhotoAccess
import com.lanfile.transfer.repository.PhotoShareRepository
import com.lanfile.transfer.util.AppLog
import java.net.URLEncoder

/**
 * 处理「手机 → 电脑」照片接口。
 *
 * - GET /api/photos            列出手机端已选照片
 * - GET /api/photos/{id}       下载原始照片
 * - GET /api/photos/{id}/thumb 下载缩略图（供电脑网页画廊预览）
 */
class PhotoHandler(
    private val context: Context,
    private val repository: PhotoShareRepository
) {

    private val thumbnailCache = object : LinkedHashMap<String, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_THUMBNAIL_CACHE
    }

    /** 返回 true 表示该请求已由本处理器处理。 */
    fun handle(request: HttpRequest, response: HttpResponseWriter): Boolean {
        if (!request.path.startsWith(PHOTO_PREFIX)) return false

        val segments = request.path
            .removePrefix(PHOTO_PREFIX)
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }

        if (request.method != "GET") {
            response.sendJson(405, UploadHandler.errorJson("请求方法不支持"))
            return true
        }

        when {
            segments.isEmpty() -> serveList(response)
            segments.size == 1 -> servePhoto(segments[0], response)
            segments.size == 2 && segments[1] == "thumb" -> serveThumbnail(segments[0], response)
            else -> response.sendJson(404, UploadHandler.errorJson("接口不存在"))
        }
        return true
    }

    private fun serveList(response: HttpResponseWriter) {
        val photos = repository.photos.value
        val body = buildString {
            append("{\"success\":true,\"photos\":[")
            photos.forEachIndexed { index, photo ->
                if (index > 0) append(',')
                append('{')
                append("\"id\":").append(UploadHandler.jsonString(photo.id))
                append(",\"name\":").append(UploadHandler.jsonString(photo.displayName))
                append(",\"size\":").append(photo.size)
                append(",\"mimeType\":").append(UploadHandler.jsonString(photo.mimeType))
                append(",\"downloaded\":").append(photo.downloaded)
                append(",\"addedAt\":").append(photo.addedAt)
                append('}')
            }
            append("]}")
        }
        response.sendJson(200, body)
    }

    private fun servePhoto(id: String, response: HttpResponseWriter) {
        val photo = repository.find(id)
        if (photo == null) {
            response.sendJson(404, UploadHandler.errorJson("照片不存在或已从手机移除"))
            return
        }

        val uri = Uri.parse(photo.uri)
        val stream = try {
            PhotoAccess.openStream(context, uri)
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "打开照片失败：${t.message}", t)
            null
        }
        if (stream == null) {
            response.sendJson(410, UploadHandler.errorJson("无法读取该照片，请在手机上重新选择"))
            return
        }

        val length = PhotoAccess.lengthOf(context, uri, photo.size)
        val disposition = "attachment; filename=\"${asciiFallbackName(photo.displayName)}\"; " +
            "filename*=UTF-8''${encodeRfc5987(photo.displayName)}"

        try {
            response.sendStream(
                status = 200,
                contentType = photo.mimeType,
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
            AppLog.i(AppLog.SERVER, "照片已发送到电脑：${photo.displayName}")
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "发送照片中断：${t.message}", t)
            try {
                stream.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun serveThumbnail(id: String, response: HttpResponseWriter) {
        val cached = synchronized(thumbnailCache) { thumbnailCache[id] }
        if (cached != null) {
            response.sendBytes(200, JPEG_CONTENT_TYPE, cached)
            return
        }

        val photo = repository.find(id)
        if (photo == null) {
            response.sendJson(404, UploadHandler.errorJson("照片不存在或已从手机移除"))
            return
        }

        val bitmap = try {
            PhotoAccess.loadThumbnailBitmap(context, Uri.parse(photo.uri), THUMBNAIL_SIZE)
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "生成缩略图失败：${t.message}", t)
            null
        }
        if (bitmap == null) {
            response.sendJson(404, UploadHandler.errorJson("无法生成缩略图"))
            return
        }

        val bytes = PhotoAccess.encodeJpeg(bitmap)
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
            builder.append(if (character.code in 32..126 && character != '"' && character != '\\') character else '_')
        }
        return builder.toString()
    }

    private companion object {
        const val PHOTO_PREFIX = "/api/photos"
        const val JPEG_CONTENT_TYPE = "image/jpeg"
        const val THUMBNAIL_SIZE = 480
        const val MAX_THUMBNAIL_CACHE = 64
        const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
    }
}
