package com.lanfile.transfer.model

/**
 * 手机端分享给电脑的一个文件（照片或普通文件）。
 *
 * 只保存 Content URI 与元数据，不复制文件内容，因此不占用额外磁盘空间。
 */
data class SharedFile(
    val id: String,
    val uri: String,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val addedAt: Long,
    val downloaded: Boolean = false
) {
    /** 图片才有缩略图，其它类型在界面上显示文件图标与扩展名。 */
    val isImage: Boolean
        get() = mimeType.startsWith("image/")
}
