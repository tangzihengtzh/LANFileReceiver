package com.lanfile.transfer.repository

import com.lanfile.transfer.model.SharedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手机 → 电脑 的待发送文件列表。
 *
 * 与 [TransferRepository] 一样只保留在内存中，进程结束后清空。
 */
class ShareRepository {

    private val _files = MutableStateFlow<List<SharedFile>>(emptyList())
    val files: StateFlow<List<SharedFile>> = _files.asStateFlow()

    private val store = CopyOnWriteArrayList<SharedFile>()
    private val lock = Any()

    /** 返回实际新增数量（同一份文件重复选择会被忽略）。 */
    fun addAll(items: List<SharedFile>): Int {
        var added = 0
        synchronized(lock) {
            for (item in items) {
                if (store.any { it.uri == item.uri }) continue
                store.add(item)
                added++
            }
            if (added > 0) publish()
        }
        return added
    }

    fun remove(id: String) {
        synchronized(lock) {
            if (store.removeAll { it.id == id }) publish()
        }
    }

    fun clear() {
        synchronized(lock) {
            if (store.isNotEmpty()) {
                store.clear()
                publish()
            }
        }
    }

    /** 电脑成功下载后回写状态，手机界面显示「已发送」。 */
    fun markDownloaded(id: String) {
        if (store.none { it.id == id && !it.downloaded }) return
        synchronized(lock) {
            val index = store.indexOfFirst { it.id == id }
            if (index < 0 || store[index].downloaded) return
            store[index] = store[index].copy(downloaded = true)
            publish()
        }
    }

    fun find(id: String): SharedFile? = store.firstOrNull { it.id == id }

    private fun publish() {
        _files.value = store.toList()
    }
}
