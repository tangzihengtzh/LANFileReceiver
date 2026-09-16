package com.lanfile.transfer.repository

import android.os.SystemClock
import com.lanfile.transfer.model.LanAddress
import com.lanfile.transfer.model.ServerState
import com.lanfile.transfer.model.ServerStatus
import com.lanfile.transfer.model.TransferItem
import com.lanfile.transfer.model.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 服务器状态与传输历史的唯一数据源。
 * HTTP Server / UploadHandler 写入，Compose UI 只读订阅。
 */
class TransferRepository {

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val _connectedDevices = MutableStateFlow(0)
    val connectedDevices: StateFlow<Int> = _connectedDevices.asStateFlow()

    private val history = CopyOnWriteArrayList<TransferItem>()

    private val lastProgressEmit = HashMap<String, Long>()

    // ---------------- 服务器状态 ----------------

    fun setServerStarting(port: Int) {
        _serverState.update {
            ServerState(status = ServerStatus.STARTING, port = port)
        }
    }

    fun setServerRunning(port: Int, token: String, addresses: List<LanAddress>) {
        _serverState.update {
            ServerState(
                status = ServerStatus.RUNNING,
                port = port,
                token = token,
                addresses = addresses
            )
        }
    }

    /** 热点/IP 可能在服务器启动后才变化，这里允许刷新显示地址。 */
    fun setAddresses(addresses: List<LanAddress>) {
        _serverState.update { state ->
            if (state.status != ServerStatus.RUNNING) {
                state
            } else if (state.addresses.map { it.ip } == addresses.map { it.ip }) {
                state
            } else {
                state.copy(addresses = addresses)
            }
        }
    }

    fun setServerError(message: String) {
        _serverState.update {
            it.copy(status = ServerStatus.ERROR, errorMessage = message, addresses = emptyList())
        }
    }

    fun setServerStopped() {
        _connectedDevices.value = 0
        _serverState.update { ServerState(status = ServerStatus.STOPPED, port = it.port) }
    }

    fun setConnectedDevices(count: Int) {
        _connectedDevices.value = count.coerceAtLeast(0)
    }

    // ---------------- 传输历史 ----------------

    fun beginTransfer(
        id: String,
        fileName: String,
        fileSize: Long,
        clientAddress: String?
    ): TransferItem {
        val item = TransferItem(
            id = id,
            fileName = fileName,
            fileSize = fileSize,
            receivedBytes = 0L,
            status = TransferStatus.UPLOADING,
            startTime = System.currentTimeMillis(),
            clientAddress = clientAddress
        )
        history.add(0, item)
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.size - 1)
        }
        publish()
        return item
    }

    fun updateProgress(id: String, receivedBytes: Long) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastProgressEmit[id] ?: 0L
        if (now - previous < PROGRESS_INTERVAL_MS) return
        lastProgressEmit[id] = now
        updateItem(id) { it.copy(receivedBytes = receivedBytes) }
    }

    fun completeTransfer(id: String, receivedBytes: Long, savedPath: String?, savedFileName: String?) {
        lastProgressEmit.remove(id)
        updateItem(id) {
            it.copy(
                fileName = savedFileName ?: it.fileName,
                receivedBytes = receivedBytes,
                fileSize = receivedBytes,
                status = TransferStatus.COMPLETED,
                endTime = System.currentTimeMillis(),
                savedPath = savedPath,
                errorMessage = null
            )
        }
    }

    fun failTransfer(id: String, message: String) {
        lastProgressEmit.remove(id)
        updateItem(id) {
            it.copy(
                status = TransferStatus.FAILED,
                endTime = System.currentTimeMillis(),
                errorMessage = message
            )
        }
    }

    fun cancelTransfer(id: String, message: String) {
        lastProgressEmit.remove(id)
        updateItem(id) {
            it.copy(
                status = TransferStatus.CANCELLED,
                endTime = System.currentTimeMillis(),
                errorMessage = message
            )
        }
    }

    fun clearHistory() {
        history.clear()
        lastProgressEmit.clear()
        publish()
    }

    fun snapshot(): List<TransferItem> = history.toList()

    private fun updateItem(id: String, transform: (TransferItem) -> TransferItem) {
        val index = history.indexOfFirst { it.id == id }
        if (index < 0) return
        history[index] = transform(history[index])
        publish()
    }

    private fun publish() {
        _transfers.value = history.toList()
    }

    private companion object {
        const val MAX_HISTORY = 200
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
