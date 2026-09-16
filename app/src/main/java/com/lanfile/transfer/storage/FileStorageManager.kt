package com.lanfile.transfer.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lanfile.transfer.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 文件落盘管理。
 *
 * - Android 10（API 29）及以上：使用 MediaStore.Downloads，写入 Download/LANTransfer/，
 *   以 IS_PENDING 充当临时文件，避免未完成文件对其它应用可见，无需存储权限。
 * - Android 9（API 28）及以下：直接写入公共下载目录，先写 .tmp 再重命名。
 */
class FileStorageManager(private val context: Context) {

    companion object {
        const val RELATIVE_DIR = "Download/LANTransfer"
        private const val TAG = AppLog.STORAGE
    }

    val displayPath: String get() = RELATIVE_DIR

    /** 保存分区剩余空间，用于上传前的提前检测。 */
    fun availableBytes(): Long {
        return try {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBytes
        } catch (t: Throwable) {
            AppLog.w(AppLog.STORAGE, "读取剩余空间失败：${t.message}", t)
            Long.MAX_VALUE
        }
    }

    /** 保存目录是否可写（旧版本系统需要外部存储权限）。 */
    fun canWrite(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            try {
                val state = Environment.getExternalStorageState()
                state == Environment.MEDIA_MOUNTED &&
                    context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun createDestination(displayName: String, mimeType: String?): Destination {
        val safeName = FileNameUtils.sanitize(displayName)
        val resolvedMime = if (mimeType.isNullOrBlank() || mimeType.contains('/').not()) {
            FileNameUtils.guessMimeType(safeName)
        } else {
            mimeType
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreDestination(safeName, resolvedMime)
        } else {
            LegacyDestination(safeName)
        }
    }

    interface Destination {
        val fileName: String
        val savedPath: String

        fun open(): OutputStream

        /** 提交为正式文件，返回最终保存路径。 */
        fun commit(): String

        /** 失败或取消时清理未完成的数据。 */
        fun abort()
    }

    private inner class MediaStoreDestination(
        private val requestedName: String,
        private val mimeType: String
    ) : Destination {

        private val resolver = context.contentResolver
        private var uri: Uri? = null
        private var stream: OutputStream? = null
        private var committed = false
        private var finalName: String = requestedName

        override val fileName: String get() = finalName
        override val savedPath: String get() = "$RELATIVE_DIR/$finalName"

        override fun open(): OutputStream {
            finalName = FileNameUtils.uniqueName(requestedName) { exists(it) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val inserted = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在 $RELATIVE_DIR 创建文件")
            uri = inserted
            val output = resolver.openOutputStream(inserted, "w")
                ?: throw IOException("无法打开文件输出流")
            stream = output
            AppLog.i(TAG, "创建接收文件：$finalName")
            return output
        }

        override fun commit(): String {
            val current = uri ?: throw IllegalStateException("文件尚未打开")
            try {
                stream?.flush()
            } catch (_: Throwable) {
            }
            try {
                stream?.close()
            } catch (_: Throwable) {
            }
            stream = null
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(current, values, null, null)
            committed = true
            return savedPath
        }

        override fun abort() {
            try {
                stream?.close()
            } catch (_: Throwable) {
            }
            stream = null
            val current = uri
            uri = null
            if (current != null && !committed) {
                try {
                    resolver.delete(current, null, null)
                    AppLog.w(TAG, "已清理未完成文件：$finalName")
                } catch (t: Throwable) {
                    AppLog.w(TAG, "清理未完成文件失败：${t.message}", t)
                }
            }
        }

        private fun exists(name: String): Boolean {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf(name, "$RELATIVE_DIR%")
            return try {
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    null
                )?.use { it.count > 0 } ?: false
            } catch (t: Throwable) {
                AppLog.w(TAG, "查询同名文件失败：${t.message}", t)
                false
            }
        }
    }

    private inner class LegacyDestination(
        private val requestedName: String
    ) : Destination {

        private val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LANTransfer"
        )
        private var tempFile: File? = null
        private var finalFile: File? = null
        private var committed = false

        override val fileName: String get() = finalFile?.name ?: requestedName
        override val savedPath: String
            get() = (finalFile ?: File(directory, requestedName)).absolutePath

        override fun open(): OutputStream {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("无法创建目录：${directory.absolutePath}")
            }
            val name = FileNameUtils.uniqueName(requestedName) { File(directory, it).exists() }
            val target = File(directory, name)
            val temp = File(directory, "$name.tmp")
            if (temp.exists()) temp.delete()
            finalFile = target
            tempFile = temp
            AppLog.i(TAG, "创建接收文件：$name")
            return FileOutputStream(temp)
        }

        override fun commit(): String {
            val temp = tempFile ?: throw IllegalStateException("文件尚未打开")
            val target = finalFile ?: throw IllegalStateException("文件尚未打开")
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("重命名临时文件失败")
            }
            committed = true
            return target.absolutePath
        }

        override fun abort() {
            if (!committed) {
                val temp = tempFile
                if (temp != null && temp.exists()) {
                    temp.delete()
                    AppLog.w(TAG, "已清理未完成文件：${temp.name}")
                }
            }
            tempFile = null
        }
    }
}
