package com.lanfile.transfer

import android.app.Application
import com.lanfile.transfer.repository.TransferRepository
import com.lanfile.transfer.storage.ServerConfig

/**
 * 应用级单例容器：Repository 与配置在 Service 与 UI 之间共享。
 */
class LanApplication : Application() {

    val repository: TransferRepository by lazy { TransferRepository() }

    val config: ServerConfig by lazy { ServerConfig(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: LanApplication
            private set
    }
}
