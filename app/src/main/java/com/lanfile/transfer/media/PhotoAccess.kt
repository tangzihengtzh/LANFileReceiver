package com.lanfile.transfer.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import com.lanfile.transfer.model.SharedPhoto
import com.lanfile.transfer.storage.FileNameUtils
import com.lanfile.transfer.util.AppLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * 照片访问层：读取元数据、打开输入流、生成缩略图。
 *
 * 只处理 Content URI（系统照片选择器返回），不申请任何存储权限。
 */
object PhotoAccess {

    private const val TAG = AppLog.STORAGE

    data class Metadata(
        val displayName: String,
        val size: Long,
        val mimeType: String
    )

    /**
     * 把选择器返回的 URI 转换为可分享的照片条目，非图片类型的 URI 会被过滤掉。
     */
    fun buildSharedPhotos(context: Context, uris: List<Uri>): List<SharedPhoto> {
        val result = ArrayList<SharedPhoto>(uris.size)
        for (uri in uris) {
            val metadata = readMetadata(context, uri) ?: continue
            if (!metadata.mimeType.startsWith("image/")) continue
            result.add(
                SharedPhoto(
                    id = newId(),
                    uri = uri.toString(),
                    displayName = FileNameUtils.sanitize(metadata.displayName),
                    size = metadata.size,
                    mimeType = metadata.mimeType,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
        return result
    }

    fun readMetadata(context: Context, uri: Uri): Metadata? {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "读取照片信息失败：${t.message}", t)
        }

        val mimeType = try {
            resolver.getType(uri)
        } catch (t: Throwable) {
            null
        } ?: FileNameUtils.guessMimeType(name ?: uri.lastPathSegment ?: "photo.jpg", "image/jpeg")

        if (!mimeType.startsWith("image/")) return null

        val displayName = name
            ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "photo_${System.currentTimeMillis()}.jpg")

        return Metadata(displayName, size, mimeType)
    }

    /** 打开照片原始数据流，失败时抛出异常由调用方转成 HTTP 错误。 */
    fun openStream(context: Context, uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开照片数据流")
    }

    /** 通过文件描述符获取真实字节数，比 MediaStore 元数据更可靠。 */
    fun lengthOf(context: Context, uri: Uri, fallback: Long): Long {
        return try {
            val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            if (size > 0) size else fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    /**
     * 生成缩略图位图。API 29+ 使用系统缩略图，低版本按比例采样解码，避免 OOM。
     */
    fun loadThumbnailBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(uri, Size(maxSize, maxSize), null)
            } catch (t: Throwable) {
                AppLog.w(TAG, "系统缩略图生成失败，改用采样解码：${t.message}")
            }
        }
        return decodeSampled(context, uri, maxSize)
    }

    /** 编码为 JPEG 字节数组，用于 HTTP 缩略图接口。 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int = 82): ByteArray? {
        return try {
            ByteArrayOutputStream(64 * 1024).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                output.toByteArray()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "缩略图编码失败：${t.message}", t)
            null
        }
    }

    private fun decodeSampled(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= maxSize &&
                bounds.outHeight / (sampleSize * 2) >= maxSize
            ) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "采样解码失败：${t.message}", t)
            null
        }
    }

    private fun newId(): String =
        UUID.randomUUID().toString().replace("-", "").take(16)
}
