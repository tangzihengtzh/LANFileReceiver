package com.lanfile.transfer.repository

import com.lanfile.transfer.model.SharedPhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 手机 → 电脑 的待发送照片列表。
 *
 * 与 [TransferRepository] 一样只保留在内存中，进程结束后清空。
 */
class PhotoShareRepository {

    private val _photos = MutableStateFlow<List<SharedPhoto>>(emptyList())
    val photos: StateFlow<List<SharedPhoto>> = _photos.asStateFlow()

    private val store = CopyOnWriteArrayList<SharedPhoto>()
    private val lock = Any()

    /** 返回实际新增数量（同一张照片重复选择会被忽略）。 */
    fun addAll(items: List<SharedPhoto>): Int {
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
        val index = store.indexOfFirst { it.id == id }
        if (index < 0 || store[index].downloaded) return
        synchronized(lock) {
            val current = store.indexOfFirst { it.id == id }
            if (current < 0) return
            store[current] = store[current].copy(downloaded = true)
            publish()
        }
    }

    fun find(id: String): SharedPhoto? = store.firstOrNull { it.id == id }

    private fun publish() {
        _photos.value = store.toList()
    }
}
