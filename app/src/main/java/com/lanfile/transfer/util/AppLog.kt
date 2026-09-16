package com.lanfile.transfer.util

import android.util.Log

/**
 * 统一日志入口。禁止在此打印用户文件内容。
 */
object AppLog {
    const val SERVER = "LAN_SERVER"
    const val UPLOAD = "LAN_UPLOAD"
    const val STORAGE = "LAN_STORAGE"
    const val NETWORK = "LAN_NETWORK"

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
    }
}
