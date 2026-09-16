package com.lanfile.transfer.util

import java.security.SecureRandom

/**
 * 每次启动服务器生成一次性的访问 Token。
 * 不使用易混淆字符（0/O、1/I）。
 */
object TokenGenerator {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun generate(length: Int = 6): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }
}
