package com.lanfile.transfer.server

import android.content.Context
import com.lanfile.transfer.model.TransferStatus
import com.lanfile.transfer.repository.PhotoShareRepository
import com.lanfile.transfer.repository.TransferRepository
import com.lanfile.transfer.storage.FileStorageManager
import com.lanfile.transfer.util.AppLog
import java.io.IOException
import java.util.Locale

/**
 * 请求路由与 Token 校验。
 *
 * 访问规则：
 * - GET /            需要正确 Token，返回内置上传页面
 * - GET /app.js 等   静态资源，允许匿名获取（不含敏感信息）
 * - GET /api 下接口  需要正确 Token
 * - POST /api/upload 需要正确 Token
 */
class ApiRouter(
    context: Context,
    private val storage: FileStorageManager,
    private val repository: TransferRepository,
    photoRepository: PhotoShareRepository,
    private val tokenProvider: () -> String,
    private val deviceNameProvider: () -> String
) {

    private val appContext = context.applicationContext
    private val uploadHandler = UploadHandler(storage, repository)
    private val photoHandler = PhotoHandler(appContext, photoRepository)
    private val assetCache = HashMap<String, ByteArray>()
    private val indexTemplate: String by lazy { readAssetText("web/index.html") }

    fun route(request: HttpRequest, response: HttpResponseWriter) {
        try {
            when {
                request.method == "GET" && (request.path == "/" || request.path == "/index.html") ->
                    serveIndex(request, response)

                request.method == "GET" && request.path == "/app.js" ->
                    serveAsset("web/app.js", JS_CONTENT_TYPE, response)

                request.method == "GET" && request.path == "/style.css" ->
                    serveAsset("web/style.css", CSS_CONTENT_TYPE, response)

                request.method == "GET" && request.path == "/favicon.ico" ->
                    response.sendBytes(204, null, EMPTY_BYTES)

                request.method == "GET" && request.path == "/api/status" ->
                    withToken(request, response) { serveStatus(response) }

                request.method == "GET" && request.path == "/api/transfers" ->
                    withToken(request, response) { serveTransfers(response) }

                request.method == "POST" && request.path == "/api/upload" ->
                    withToken(request, response) { uploadHandler.handle(request, response) }

                request.path.startsWith("/api/photos") ->
                    withToken(request, response) { photoHandler.handle(request, response) }

                else -> response.sendJson(404, UploadHandler.errorJson("接口不存在"))
            }
        } catch (t: Throwable) {
            AppLog.e(AppLog.SERVER, "处理请求失败：${request.path}", t)
            try {
                response.sendJson(500, UploadHandler.errorJson("服务器内部错误"))
            } catch (_: Throwable) {
            }
        }
    }

    private inline fun withToken(
        request: HttpRequest,
        response: HttpResponseWriter,
        block: () -> Unit
    ) {
        if (isTokenValid(request)) {
            block()
        } else {
            AppLog.w(AppLog.SERVER, "拒绝访问（Token 无效）：${request.clientAddress}")
            response.sendBytes(
                403,
                HTML_CONTENT_TYPE,
                forbiddenPage().toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun isTokenValid(request: HttpRequest): Boolean {
        val expected = tokenProvider()
        if (expected.isBlank()) return false
        val provided = request.query("token")
            ?: request.header("x-auth-token")
            ?: return false
        return provided == expected
    }

    private fun serveIndex(request: HttpRequest, response: HttpResponseWriter) {
        if (!isTokenValid(request)) {
            AppLog.w(AppLog.SERVER, "拒绝访问（Token 无效）：${request.clientAddress}")
            response.sendBytes(403, HTML_CONTENT_TYPE, forbiddenPage().toByteArray(Charsets.UTF_8))
            return
        }
        val html = indexTemplate.replace(TOKEN_PLACEHOLDER, tokenProvider())
        response.sendBytes(200, HTML_CONTENT_TYPE, html.toByteArray(Charsets.UTF_8))
    }

    private fun serveAsset(path: String, contentType: String, response: HttpResponseWriter) {
        val bytes = synchronized(assetCache) { assetCache[path] } ?: run {
            val loaded = try {
                readAssetBytes(path)
            } catch (t: Throwable) {
                AppLog.e(AppLog.SERVER, "读取内置资源失败：$path", t)
                null
            }
            if (loaded != null) {
                synchronized(assetCache) { assetCache[path] = loaded }
            }
            loaded
        }
        if (bytes == null) {
            response.sendText(404, "资源不存在")
            return
        }
        response.sendBytes(200, contentType, bytes)
    }

    private fun serveStatus(response: HttpResponseWriter) {
        val state = repository.serverState.value
        val addresses = state.addresses.joinToString(",") {
            "\"${it.ip}\""
        }
        val body = buildString {
            append('{')
            append("\"status\":\"running\"")
            append(",\"deviceName\":").append(UploadHandler.jsonString(deviceNameProvider()))
            append(",\"version\":").append(UploadHandler.jsonString(VERSION))
            append(",\"port\":").append(state.port)
            append(",\"addresses\":[").append(addresses).append(']')
            append(",\"connectedDevices\":").append(repository.connectedDevices.value)
            append('}')
        }
        response.sendJson(200, body)
    }

    private fun serveTransfers(response: HttpResponseWriter) {
        val items = repository.snapshot()
        val body = buildString {
            append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) append(',')
                append('{')
                append("\"fileName\":").append(UploadHandler.jsonString(item.fileName))
                append(",\"size\":").append(if (item.fileSize > 0) item.fileSize else item.receivedBytes)
                append(",\"status\":").append(UploadHandler.jsonString(statusText(item.status)))
                append('}')
            }
            append(']')
        }
        response.sendJson(200, body)
    }

    private fun statusText(status: TransferStatus): String =
        status.name.lowercase(Locale.ROOT)

    private fun forbiddenPage(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>访问被拒绝</title>
          <style>
            body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                   font-family: system-ui, "Microsoft YaHei", sans-serif; background:#0f172a; color:#e2e8f0; }
            .card { max-width:520px; padding:32px; border-radius:16px; background:#1e293b; text-align:center; }
            h1 { font-size:20px; margin:0 0 12px; }
            p { color:#94a3b8; line-height:1.7; margin:8px 0 0; font-size:14px; }
            svg { width:56px; height:56px; color:#f87171; }
          </style>
        </head>
        <body>
          <div class="card">
            <svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2 1 21h22L12 2zm1 15h-2v-2h2v2zm0-4h-2V9h2v4z"/></svg>
            <h1>403 访问被拒绝</h1>
            <p>Token 无效或已过期。请在手机 App 上复制完整的访问地址（包含 token 参数）后重新打开。</p>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun readAssetBytes(path: String): ByteArray {
        appContext.assets.open(path).use { return it.readBytes() }
    }

    private fun readAssetText(path: String): String {
        return try {
            String(readAssetBytes(path), Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLog.e(AppLog.SERVER, "内置页面缺失：$path", t)
            throw IOException("内置页面缺失：$path")
        }
    }

    companion object {
        const val VERSION = "1.0"
        const val TOKEN_PLACEHOLDER = "__LAN_TOKEN__"
        private const val HTML_CONTENT_TYPE = "text/html; charset=utf-8"
        private const val JS_CONTENT_TYPE = "application/javascript; charset=utf-8"
        private const val CSS_CONTENT_TYPE = "text/css; charset=utf-8"
        private val EMPTY_BYTES = ByteArray(0)
    }
}
