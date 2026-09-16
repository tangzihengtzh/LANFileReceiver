package com.lanfile.transfer.util

import java.security.SecureRandom

/**
 * 每次启动服务器生成一次性的访问 Token。
 *
 * 当前为 4 位数字（保留前导零），仅用于局域网内的基础访问保护，
 * 不作为强身份认证使用。
 */
object TokenGenerator {

    private const val LENGTH = 4
    private val random = SecureRandom()

    fun generate(): String {
        val builder = StringBuilder(LENGTH)
        repeat(LENGTH) { builder.append(random.nextInt(10)) }
        return builder.toString()
    }
}
