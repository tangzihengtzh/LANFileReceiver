package com.lanfile.transfer.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.lanfile.transfer.LanApplication
import com.lanfile.transfer.MainActivity
import com.lanfile.transfer.R
import com.lanfile.transfer.network.NetworkUtils
import com.lanfile.transfer.repository.TransferRepository
import com.lanfile.transfer.storage.FileStorageManager
import com.lanfile.transfer.storage.ServerConfig
import com.lanfile.transfer.util.AppLog
import com.lanfile.transfer.util.TokenGenerator
import java.net.BindException

/**
 * 承载 HTTP Server 的前台服务。
 * 只要用户没有主动停止，切到后台/锁屏后传输仍会继续。
 */
class ServerService : Service() {

    private val repository: TransferRepository
        get() = (application as LanApplication).repository

    private val config: ServerConfig
        get() = (application as LanApplication).config

    private var server: HttpServer? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, config.port)
                startServer(port)
            }

            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }

            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // ---------------- 服务器生命周期 ----------------

    private fun startServer(port: Int) {
        if (running) return
        AppLog.i(AppLog.SERVER, "请求启动服务器，端口 $port")
        repository.setServerStarting(port)

        val starting = buildNotification(getString(R.string.notification_starting))
        if (!startForegroundCompat(starting)) {
            repository.setServerError("无法启动前台服务，请保持应用在前台后重试")
            stopSelf()
            return
        }

        val addresses = NetworkUtils.detectLanAddresses(this)
        if (addresses.isEmpty()) {
            AppLog.w(AppLog.SERVER, "未检测到可用局域网地址")
        } else {
            AppLog.i(AppLog.SERVER, "检测到局域网地址：" + addresses.joinToString { it.ip })
        }

        val token = TokenGenerator.generate()
        val router = ApiRouter(
            context = this,
            storage = FileStorageManager(this),
            repository = repository,
            photoRepository = (application as LanApplication).photoRepository,
            tokenProvider = { token },
            deviceNameProvider = { deviceName() }
        )
        val httpServer = HttpServer(port) { request, response -> router.route(request, response) }
        httpServer.onConnectionCountChanged = { count -> repository.setConnectedDevices(count) }

        try {
            httpServer.start()
        } catch (t: Throwable) {
            val message = describeStartFailure(port, t)
            AppLog.e(AppLog.SERVER, message, t)
            repository.setServerError(message)
            notify(buildNotification(message))
            stopSelf()
            return
        }

        server = httpServer
        running = true
        repository.setServerRunning(port, token, addresses)

        val first = addresses.firstOrNull()
        val text = if (first != null) {
            getString(R.string.notification_address, "${first.ip}:$port")
        } else {
            getString(R.string.notification_no_address)
        }
        notify(buildNotification(text))
    }

    private fun stopServer() {
        if (server == null && !running) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        server?.stop()
        server = null
        running = false
        repository.setServerStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        AppLog.i(AppLog.SERVER, "服务器已停止（服务退出）")
    }

    private fun describeStartFailure(port: Int, t: Throwable): String {
        val text = t.message ?: ""
        return when {
            t is BindException -> "服务器启动失败：端口 $port 被占用"
            text.contains("in use", ignoreCase = true) -> "服务器启动失败：端口 $port 被占用"
            text.contains("EADDRINUSE", ignoreCase = true) -> "服务器启动失败：端口 $port 被占用"
            t is SecurityException -> "服务器启动失败：缺少网络权限"
            else -> "服务器启动失败：${text.ifBlank { t.javaClass.simpleName }}"
        }
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return when {
            model.isBlank() -> "Android 手机"
            model.lowercase().startsWith(manufacturer.lowercase()) -> model
            manufacturer.isBlank() -> model
            else -> "$manufacturer $model"
        }
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun notify(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            AppLog.w(AppLog.SERVER, "更新通知失败：${t.message}", t)
        }
    }

    private fun startForegroundCompat(notification: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (t: Throwable) {
            AppLog.e(AppLog.SERVER, "startForeground 失败：${t.message}", t)
            false
        }
    }

    companion object {
        private const val TAG = AppLog.SERVER
        const val ACTION_START = "com.lanfile.transfer.action.START"
        const val ACTION_STOP = "com.lanfile.transfer.action.STOP"
        const val EXTRA_PORT = "extra_port"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "lan_file_receiver_channel"

        fun start(context: Context, port: Int) {
            val intent = Intent(context, ServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                AppLog.e(TAG, "启动服务失败：${t.message}", t)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                AppLog.e(TAG, "停止服务失败：${t.message}", t)
            }
        }
    }
}
