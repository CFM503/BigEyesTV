package com.bigeyes.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bigeyes.tv.R
import com.bigeyes.tv.airplay.AirPlayDiscoveryService
import com.bigeyes.tv.dlna.SsdpServer
import com.bigeyes.tv.player.PlayerState
import com.bigeyes.tv.player.TvPlayerListener
import com.bigeyes.tv.player.TvPlayerManager
import com.bigeyes.tv.server.TvHttpServer
import com.bigeyes.tv.ui.MainActivity
import com.bigeyes.tv.utils.NetworkUtils

class TvReceiverService : Service(), TvPlayerListener {

    private val binder = LocalBinder()
    private var httpServer: TvHttpServer? = null
    private var airPlayDiscovery: AirPlayDiscoveryService? = null
    private var ssdpServer: SsdpServer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var notificationManager: NotificationManager

    inner class LocalBinder : Binder() {
        fun getService(): TvReceiverService = this@TvReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TvReceiverService onCreate")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        startForeground(NOTIFICATION_ID, buildNotification("等待投屏中", "AirPlay & DLNA 在线"))
        acquireLocks()
        startServers()

        TvPlayerManager.getInstance(this).addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        updateNotificationStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "TvReceiverService onDestroy")
        TvPlayerManager.getInstance(this).removeListener(this)
        stopServers()
        releaseLocks()
        super.onDestroy()
    }

    override fun onStateChanged(state: PlayerState) {
        updateNotificationStatus()
    }

    override fun onPlaybackStarted(url: String) {
        val title = "BigEyes TV 正在播放"
        val text = "正在投屏: $url"
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onPlaybackStopped() {
        updateNotificationStatus()
    }

    override fun onError(error: String) {
        val title = "BigEyes TV 播放出错"
        val text = "错误信息: $error"
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun updateNotificationStatus() {
        val player = TvPlayerManager.getInstance(this)
        val ip = NetworkUtils.getLocalIpAddress()
        val (title, text) = when {
            player.isPlaying() -> {
                "BigEyes TV 正在播放" to "正在投屏播放 | 来源: ${player.currentUrl ?: "流媒体"}"
            }
            player.currentState == PlayerState.READY -> {
                "BigEyes TV 播放就绪" to "已加载视频 | http://$ip:$SERVER_PORT"
            }
            player.currentState == PlayerState.BUFFERING -> {
                "BigEyes TV 缓冲中..." to "正在缓冲流媒体数据"
            }
            else -> {
                "BigEyes TV 投屏服务运行中" to "等待投屏中 | http://$ip:$SERVER_PORT"
            }
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun startServers() {
        val playerManager = TvPlayerManager.getInstance(this)

        try {
            httpServer = TvHttpServer(this, playerManager, SERVER_PORT).apply {
                start(5000, false)
            }
            Log.i(TAG, "TvHttpServer started on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TvHttpServer on port $SERVER_PORT", e)
        }

        try {
            airPlayDiscovery = AirPlayDiscoveryService(this, SERVER_PORT).apply {
                start()
            }
            Log.i(TAG, "AirPlayDiscoveryService started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AirPlayDiscoveryService", e)
        }

        try {
            ssdpServer = SsdpServer(this, SERVER_PORT).apply {
                start()
            }
            Log.i(TAG, "SsdpServer started.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SsdpServer", e)
        }
    }

    private fun stopServers() {
        try {
            airPlayDiscovery?.stop()
            airPlayDiscovery = null
        } catch (e: Exception) {
            // ignore
        }

        try {
            ssdpServer?.stop()
            ssdpServer = null
        } catch (e: Exception) {
            // ignore
        }

        try {
            httpServer?.stop()
            httpServer = null
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BigEyesTV::WakeLock"
            )?.apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(wifiMode, "BigEyesTV::WifiLock")?.apply {
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock acquire failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            // ignore
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock released")
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BigEyes TV 投屏接收服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示投屏与播放状态"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TvReceiverService"
        const val SERVER_PORT = 7000
        const val ACTION_START = "com.bigeyes.tv.action.START"
        const val ACTION_STOP = "com.bigeyes.tv.action.STOP"
        private const val CHANNEL_ID = "bigeyes_tv_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TvReceiverService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TvReceiverService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}
