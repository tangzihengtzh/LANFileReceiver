package com.lanfile.transfer.server

import android.content.Context
import com.lanfile.transfer.model.TransferStatus
import com.lanfile.transfer.repository.ShareRepository
import com.lanfile.transfer.repository.TransferRepository
import com.lanfile.transfer.storage.FileStorageManager
import com.lanfile.transfer.util.AppLog
import java.util.Locale

/**
 * 请求路由与访问校验。
 *
 * 访问规则：
 * - GET /                公开，返回内置上传页面（页面本身不含 Token）
 * - GET /app.js 等       公开的静态资源
 * - POST /api/auth       提交 Token 换取会话 Cookie
 * - 其它 /api 下接口      需要有效会话 Cookie 或 X-Auth-Token 请求头
 *
 * Token 只会出现在 POST 请求体或请求头中，绝不出现在 URL 上。
 */
class ApiRouter(
    context: Context,
    private val storage: FileStorageManager,
    private val repository: TransferRepository,
    shareRepository: ShareRepository,
    private val sessions: SessionStore,
    private val tokenProvider: () -> String,
    private val deviceNameProvider: () -> String
) {

    private val appContext = context.applicationContext
    private val uploadHandler = UploadHandler(storage, repository)
    private val shareHandler = ShareHandler(appContext, shareRepository)
    private val assetCache = HashMap<String, ByteArray>()

    fun route(request: HttpRequest, response: HttpResponseWriter) {
        try {
            when {
                request.method == "GET" &&
                    (request.path == "/" || request.path == "/index.html") -> serveIndex(response)

                request.method == "GET" && request.path == "/app.js" ->
                    serveAsset("web/app.js", JS_CONTENT_TYPE, response)

                request.method == "GET" && request.path == "/style.css" ->
                    serveAsset("web/style.css", CSS_CONTENT_TYPE, response)

                request.method == "GET" && request.path == "/favicon.ico" ->
                    response.sendBytes(204, null, EMPTY_BYTES)

                request.path == "/api/auth" -> handleAuth(request, response)

                request.path.startsWith("/api/") -> {
                    if (!isAuthorized(request)) {
                        deny(request, response)
                    } else {
                        routeApi(request, response)
                    }
                }

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

    private fun routeApi(request: HttpRequest, response: HttpResponseWriter) {
        when {
            request.method == "GET" && request.path == "/api/status" -> serveStatus(response)

            request.method == "GET" && request.path == "/api/transfers" -> serveTransfers(response)

            request.method == "POST" && request.path == "/api/upload" ->
                uploadHandler.handle(request, response)

            request.path.startsWith("/api/files") -> shareHandler.handle(request, response)

            else -> response.sendJson(404, UploadHandler.errorJson("接口不存在"))
        }
    }

    // ---------------- 鉴权 ----------------

    private fun handleAuth(request: HttpRequest, response: HttpResponseWriter) {
        if (request.method != "POST") {
            response.sendJson(405, UploadHandler.errorJson("请求方法不支持"))
            return
        }

        val client = request.clientAddress
        if (sessions.isLockedOut(client)) {
            val seconds = sessions.lockoutRemainingSeconds(client)
            AppLog.w(AppLog.SERVER, "Token 尝试次数过多，暂时锁定：$client")
            response.sendJson(429, UploadHandler.errorJson("尝试次数过多，请 $seconds 秒后再试"))
            return
        }

        val provided = request.header("x-auth-token")?.trim()
            ?: extractToken(request.readBodyText(MAX_AUTH_BODY_BYTES))

        if (provided.isNullOrEmpty() || provided != tokenProvider()) {
            sessions.recordFailure(client)
            AppLog.w(AppLog.SERVER, "Token 校验失败：$client")
            response.sendJson(403, UploadHandler.errorJson("Token 不正确"))
            return
        }

        sessions.recordSuccess(client)
        val sessionId = sessions.create()
        AppLog.i(AppLog.SERVER, "客户端通过验证：$client")
        response.sendBytes(
            status = 200,
            contentType = "application/json; charset=utf-8",
            bytes = "{\"success\":true}".toByteArray(Charsets.UTF_8),
            extraHeaders = mapOf(
                "Set-Cookie" to "${SessionStore.COOKIE_NAME}=$sessionId; Path=/; " +
                    "HttpOnly; SameSite=Strict; Max-Age=${SESSION_COOKIE_MAX_AGE}"
            )
        )
    }

    /** 兼容裸 Token、表单 token= 与 {"token":"..."} 三种提交形式。 */
    private fun extractToken(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val text = body.trim()
        JSON_TOKEN.find(text)?.let { return it.groupValues[1].trim() }
        if (text.startsWith("token=")) {
            return text.removePrefix("token=").substringBefore('&').trim()
        }
        return text
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val headerToken = request.header("x-auth-token")
        if (!headerToken.isNullOrEmpty() && headerToken == tokenProvider()) return true
        return sessions.isValid(request.cookie(SessionStore.COOKIE_NAME))
    }

    private fun deny(request: HttpRequest, response: HttpResponseWriter) {
        AppLog.w(AppLog.SERVER, "拒绝访问（未通过验证）：${request.clientAddress} ${request.path}")
        response.sendJson(
            403,
            "{\"success\":false,\"authRequired\":true,\"message\":\"请先输入访问 Token\"}"
        )
    }

    // ---------------- 页面与静态资源 ----------------

    private fun serveIndex(response: HttpResponseWriter) {
        val bytes = readAssetBytes("web/index.html")
        response.sendBytes(200, HTML_CONTENT_TYPE, bytes)
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

    // ---------------- API ----------------

    private fun serveStatus(response: HttpResponseWriter) {
        val state = repository.serverState.value
        val addresses = state.addresses.joinToString(",") { "\"${it.ip}\"" }
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

    private fun statusText(status: TransferStatus): String = status.name.lowercase(Locale.ROOT)

    private fun readAssetBytes(path: String): ByteArray {
        appContext.assets.open(path).use { return it.readBytes() }
    }

    companion object {
        const val VERSION = "0.2"
        private const val HTML_CONTENT_TYPE = "text/html; charset=utf-8"
        private const val JS_CONTENT_TYPE = "application/javascript; charset=utf-8"
        private const val CSS_CONTENT_TYPE = "text/css; charset=utf-8"
        private const val MAX_AUTH_BODY_BYTES = 512
        private const val SESSION_COOKIE_MAX_AGE = 12 * 60 * 60
        private val EMPTY_BYTES = ByteArray(0)
        private val JSON_TOKEN = Regex("\"token\"\\s*:\\s*\"([^\"]*)\"")
    }
}
