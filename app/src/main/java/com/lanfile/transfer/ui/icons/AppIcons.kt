package com.lanfile.transfer.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 全部使用矢量图标（ImageVector），不依赖位图资源与互联网图标库。
 */
private fun buildIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black)
        )
    }.build()

object AppIcons {

    /** 电源：启动 */
    val Power: ImageVector by lazy {
        buildIcon(
            "Power",
            "M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 " +
                "1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 " +
                "9-9c0-2.74-1.23-5.18-3.17-6.83z"
        )
    }

    /** 停止 */
    val Stop: ImageVector by lazy {
        buildIcon("Stop", "M6 6h12v12H6z")
    }

    /** 复制 */
    val Copy: ImageVector by lazy {
        buildIcon(
            "Copy",
            "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 " +
                "2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
        )
    }

    /** Wi-Fi / 热点 */
    val Wifi: ImageVector by lazy {
        buildIcon(
            "Wifi",
            "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 " +
                "0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"
        )
    }

    /** 手机设备 */
    val Phone: ImageVector by lazy {
        buildIcon(
            "Phone",
            "M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-2 " +
                "20h-4v-1h4v1zm3.25-3H6.75V4h10.5v14z"
        )
    }

    /** 文件下载（接收） */
    val FileDownload: ImageVector by lazy {
        buildIcon("FileDownload", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    }

    /** 文件夹 */
    val Folder: ImageVector by lazy {
        buildIcon(
            "Folder",
            "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
        )
    }

    /** 完成 */
    val CheckCircle: ImageVector by lazy {
        buildIcon(
            "CheckCircle",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 " +
                "14.17l7.59-7.59L19 8l-9 9z"
        )
    }

    /** 错误 */
    val ErrorOutline: ImageVector by lazy {
        buildIcon(
            "ErrorOutline",
            "M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 " +
                "12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"
        )
    }

    /** 取消 */
    val Cancel: ImageVector by lazy {
        buildIcon(
            "Cancel",
            "M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm5 13.59L15.59 17 12 " +
                "13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41 13.41 12 17 15.59z"
        )
    }

    /** 上传中 */
    val Uploading: ImageVector by lazy {
        buildIcon(
            "Uploading",
            "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z"
        )
    }

    /** 等待 */
    val Hourglass: ImageVector by lazy {
        buildIcon(
            "Hourglass",
            "M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99-.01-.01H18V2H6z"
        )
    }

    /** 设置 */
    val Settings: ImageVector by lazy {
        buildIcon(
            "Settings",
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
        )
    }

    /** 刷新 */
    val Refresh: ImageVector by lazy {
        buildIcon(
            "Refresh",
            "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 " +
                "7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 " +
                "1.78L13 11h7V4l-2.35 2.35z"
        )
    }

    /** 信息 */
    val Info: ImageVector by lazy {
        buildIcon(
            "Info",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"
        )
    }

    /** 已连接设备 */
    val Devices: ImageVector by lazy {
        buildIcon(
            "Devices",
            "M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 " +
                "0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z"
        )
    }

    /** Token / 锁 */
    val Lock: ImageVector by lazy {
        buildIcon(
            "Lock",
            "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 " +
                "2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 " +
                "1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"
        )
    }

    /** 箭头 */
    val ChevronRight: ImageVector by lazy {
        buildIcon("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    }

    /** 删除/清空 */
    val Delete: ImageVector by lazy {
        buildIcon(
            "Delete",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
        )
    }

    /** 服务器/局域网 */
    val Router: ImageVector by lazy {
        buildIcon(
            "Router",
            "M20.2 5.9l.8-.8C19.6 3.7 17.8 3 16 3s-3.6.7-5 2.1l.8.8C13 4.8 14.5 4.2 16 4.2s3 .6 4.2 1.7zm-.9.8c-.9-.9-2.1-1.4-3.3-1.4s-2.4.5-3.3 1.4l.8.8c.7-.7 1.6-1 2.5-1 .9 0 1.8.3 2.5 1l.8-.8zM19 13h-2V9h-2v4H5c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2zm-12 5H5v-2h2v2zm3.5 0h-2v-2h2v2zm3.5 0h-2v-2h2v2z"
        )
    }

    /** 照片 */
    val Photo: ImageVector by lazy {
        buildIcon(
            "Photo",
            "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 " +
                "3.01L14.5 12l4.5 6H5l3.5-4.5z"
        )
    }

    /** 发送（手机 → 电脑） */
    val Send: ImageVector by lazy {
        buildIcon("Send", "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z")
    }

    /** 关闭 / 移除 */
    val Close: ImageVector by lazy {
        buildIcon(
            "Close",
            "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 " +
                "17.59 13.41 12z"
        )
    }
}
