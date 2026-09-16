package com.lanfile.transfer.model

/**
 * 单次文件传输的状态。
 */
enum class TransferStatus {
    WAITING,
    UPLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 传输历史记录项。仅在本次 Server 运行期间保留于内存中。
 */
data class TransferItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val receivedBytes: Long,
    val status: TransferStatus,
    val startTime: Long,
    val endTime: Long? = null,
    val savedPath: String? = null,
    val errorMessage: String? = null,
    val clientAddress: String? = null
) {
    val progress: Float
        get() = when {
            status == TransferStatus.COMPLETED -> 1f
            fileSize > 0L -> (receivedBytes.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

/**
 * 服务器运行状态。
 */
enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * 一个可用于访问本机 HTTP 服务的局域网 IPv4 地址。
 */
data class LanAddress(
    val ip: String,
    val interfaceName: String,
    val label: String,
    val score: Int
)

/**
 * 服务器整体状态快照，供 UI 直接订阅。
 */
data class ServerState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val port: Int = 8080,
    val token: String = "",
    val addresses: List<LanAddress> = emptyList(),
    val errorMessage: String? = null
) {
    val recommendedAddress: LanAddress?
        get() = addresses.firstOrNull()

    fun urlFor(address: LanAddress): String = "http://${address.ip}:$port/?token=$token"

    val primaryUrl: String?
        get() = recommendedAddress?.let { urlFor(it) }
}
