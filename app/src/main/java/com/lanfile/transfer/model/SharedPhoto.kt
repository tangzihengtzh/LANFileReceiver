package com.lanfile.transfer.model

/**
 * 手机端分享给电脑的一张照片。
 *
 * 只保存 Content URI 与元数据，不复制文件内容，因此不占用额外磁盘空间。
 */
data class SharedPhoto(
    val id: String,
    val uri: String,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val addedAt: Long,
    val downloaded: Boolean = false
)
