package com.lanfile.transfer.storage

import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * 文件名处理：清洗非法字符、拆分扩展名、重名自动编号、MIME 推断。
 */
object FileNameUtils {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    const val MAX_NAME_LENGTH = 120

    /**
     * 去掉客户端可能带上的路径信息与非法字符。
     */
    fun sanitize(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = ILLEGAL.replace(name, "_")
        name = name.trim().trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") name = "unnamed"
        if (name.length > MAX_NAME_LENGTH) {
            val (base, extension) = split(name)
            val keep = (MAX_NAME_LENGTH - extension.length).coerceAtLeast(1)
            name = base.take(keep) + extension
        }
        return name
    }

    /**
     * 拆分为「主名」与「.扩展名」，无扩展名时第二项为空串。
     */
    fun split(name: String): Pair<String, String> {
        val index = name.lastIndexOf('.')
        return if (index > 0 && index < name.length - 1) {
            name.substring(0, index) to name.substring(index)
        } else {
            name to ""
        }
    }

    /**
     * 若目标名已存在，则生成 "name (1).ext"、"name (2).ext" ...
     */
    fun uniqueName(desired: String, exists: (String) -> Boolean): String {
        if (!exists(desired)) return desired
        val (base, extension) = split(desired)
        var index = 1
        while (index < 10_000) {
            val candidate = "$base ($index)$extension"
            if (!exists(candidate)) return candidate
            index++
        }
        return "$base (${System.currentTimeMillis()})$extension"
    }

    fun extensionOf(name: String): String = split(name).second.removePrefix(".").lowercase(Locale.ROOT)

    fun guessMimeType(name: String, fallback: String? = null): String {
        val extension = extensionOf(name)
        if (extension.isNotEmpty()) {
            val fromMap = try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            } catch (_: Throwable) {
                null
            }
            if (!fromMap.isNullOrBlank()) return fromMap
        }
        return if (fallback.isNullOrBlank()) "application/octet-stream" else fallback
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }
}
