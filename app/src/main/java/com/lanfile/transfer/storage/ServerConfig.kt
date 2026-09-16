package com.lanfile.transfer.storage

import android.content.Context

/**
 * 服务器配置持久化（端口等）。
 */
class ServerConfig(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences("lan_file_receiver", Context.MODE_PRIVATE)

    var port: Int
        get() = preferences.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            preferences.edit().putInt(KEY_PORT, value).apply()
        }

    companion object {
        const val DEFAULT_PORT = 8080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        private const val KEY_PORT = "port"

        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT
    }
}
