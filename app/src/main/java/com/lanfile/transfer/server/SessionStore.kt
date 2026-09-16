package com.lanfile.transfer.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 浏览器会话管理。
 *
 * 用户在网页上输入 Token 并通过校验后，服务器下发一个会话 Cookie；
 * 之后所有请求（包括浏览器原生发起的 &lt;img&gt;、&lt;a download&gt;）都靠 Cookie 鉴权，
 * Token 本身不会出现在任何 URL 中。
 *
 * 会话仅存在于内存，服务器重启后全部失效。
 */
class SessionStore {

    private val sessions = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, FailureState>()
    private val random = SecureRandom()

    fun create(): String {
        val bytes = ByteArray(SESSION_ID_BYTES)
        random.nextBytes(bytes)
        val id = buildString(bytes.size * 2) {
            for (byte in bytes) append(HEX[(byte.toInt() shr 4) and 0x0F]).append(HEX[byte.toInt() and 0x0F])
        }
        sessions[id] = System.currentTimeMillis()
        prune()
        return id
    }

    fun isValid(sessionId: String?): Boolean {
        if (sessionId.isNullOrEmpty()) return false
        val created = sessions[sessionId] ?: return false
        if (System.currentTimeMillis() - created > SESSION_TTL_MS) {
            sessions.remove(sessionId)
            return false
        }
        return true
    }

    fun clear() {
        sessions.clear()
        failures.clear()
    }

    // ---------------- 失败节流（4 位 Token 空间较小，做基本暴力破解防护） ----------------

    fun isLockedOut(clientAddress: String): Boolean {
        val state = failures[clientAddress] ?: return false
        if (state.count < MAX_FAILURES) return false
        if (System.currentTimeMillis() - state.lastFailure > LOCKOUT_MS) {
            failures.remove(clientAddress)
            return false
        }
        return true
    }

    fun lockoutRemainingSeconds(clientAddress: String): Int {
        val state = failures[clientAddress] ?: return 0
        val elapsed = System.currentTimeMillis() - state.lastFailure
        val remaining = LOCKOUT_MS - elapsed
        return if (remaining <= 0) 0 else ((remaining + 999) / 1000).toInt()
    }

    fun recordFailure(clientAddress: String) {
        failures.compute(clientAddress) { _, current ->
            FailureState((current?.count ?: 0) + 1, System.currentTimeMillis())
        }
    }

    fun recordSuccess(clientAddress: String) {
        failures.remove(clientAddress)
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value > SESSION_TTL_MS }
        failures.entries.removeAll { now - it.value.lastFailure > LOCKOUT_MS * 4 }
    }

    private data class FailureState(val count: Int, val lastFailure: Long)

    companion object {
        const val COOKIE_NAME = "lan_session"

        private const val SESSION_ID_BYTES = 20
        private const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_FAILURES = 10
        private const val LOCKOUT_MS = 30_000L
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
