package com.bigeyes.tv.ui

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bigeyes.tv.databinding.ActivityMainBinding
import com.bigeyes.tv.player.PlayerState
import com.bigeyes.tv.player.TvPlayerListener
import com.bigeyes.tv.player.TvPlayerManager
import com.bigeyes.tv.service.TvReceiverService
import com.bigeyes.tv.update.ReleaseInfo
import com.bigeyes.tv.update.UpdateManager
import com.bigeyes.tv.utils.DeviceIdManager
import com.bigeyes.tv.utils.NetworkUtils
import java.io.File

class MainActivity : AppCompatActivity(), TvPlayerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var deviceIdManager: DeviceIdManager
    private lateinit var updateManager: UpdateManager

    private var updateDialog: AlertDialog? = null
    private var downloadProgressDialog: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceIdManager = DeviceIdManager.getInstance(this)
        playerManager = TvPlayerManager.getInstance(this)
        updateManager = UpdateManager(this)

        updateDeviceInfo()

        playerManager.addListener(this)
        playerManager.attachPlayerView(binding.playerView)

        // Start background receiver service (AirPlay & DLNA)
        TvReceiverService.start(this)

        // Check for updates asynchronously
        checkAppUpdate()
    }

    private fun updateDeviceInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        val version = updateManager.getAppVersionName()
        binding.tvDeviceName.text = "设备名称：${deviceIdManager.deviceName}"
        binding.tvIpAddress.text = "服务地址：http://$ip:${TvReceiverService.SERVER_PORT}"
        binding.tvDeviceId.text = "DeviceID (AirPlay MAC)：${deviceIdManager.deviceId}"
        binding.tvVersion.text = "当前版本：v$version"
    }

    private fun checkAppUpdate() {
        updateManager.checkForUpdates(object : UpdateManager.UpdateCheckListener {
            override fun onUpdateAvailable(release: ReleaseInfo) {
                if (isFinishing || isDestroyed) return
                showUpdateDialog(release)
            }

            override fun onNoUpdateAvailable() {
                Log.d(TAG, "Already up to date.")
            }

            override fun onError(error: String) {
                Log.w(TAG, "Update check skipped/failed: $error")
            }
        })
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val sizeText = if (release.apkSize > 0) {
            String.format(" (%.1f MB)", release.apkSize / (1024.0 * 1024.0))
        } else ""

        val notes = if (release.releaseNotes.isNotBlank()) {
            "\n\n更新说明：\n${release.releaseNotes}"
        } else ""

        val dialog = AlertDialog.Builder(this)
            .setTitle("发现新版本 ${release.tagName}$sizeText")
            .setMessage("检测到 BigEyes-TV 有可用新版本，是否立即下载更新？$notes")
            .setCancelable(true)
            .setPositiveButton("立即更新") { _, _ ->
                startDownloadApk(release)
            }
            .setNegativeButton("稍后提醒") { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .create()

        updateDialog = dialog
        dialog.show()

        // Focus positive button for TV remote control
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun startDownloadApk(release: ReleaseInfo) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("正在下载更新")
            setMessage("正在从 GitHub 下载 ${release.apkFileName}...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }
        downloadProgressDialog = progressDialog

        updateManager.downloadApk(release, object : UpdateManager.DownloadListener {
            override fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long) {
                progressDialog.progress = percent
            }

            override fun onDownloadComplete(file: File) {
                progressDialog.dismiss()
                Toast.makeText(this@MainActivity, "下载完成，正在调起安装器...", Toast.LENGTH_SHORT).show()
                updateManager.installApk(this@MainActivity, file)
            }

            override fun onDownloadError(error: String) {
                progressDialog.dismiss()
                Toast.makeText(this@MainActivity, "下载失败: $error", Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        playerManager.attachPlayerView(binding.playerView)
        updateManager.checkAndResumePendingInstall(this)
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        downloadProgressDialog?.dismiss()
        playerManager.removeListener(this)
        playerManager.detachPlayerView(binding.playerView)
        super.onDestroy()
    }

    /**
     * D-pad and TV Remote key event handling
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If player is currently active/visible
        if (binding.playerView.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    Log.i(TAG, "Remote BACK pressed during playback -> Stopping playback")
                    playerManager.stop()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    playerManager.togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    playerManager.resume()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    playerManager.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    playerManager.stop()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val current = playerManager.getCurrentPositionMs()
                    val target = (current - 10000L).coerceAtLeast(0L)
                    playerManager.seekTo(target)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val current = playerManager.getCurrentPositionMs()
                    val duration = playerManager.getDurationMs()
                    val target = if (duration > 0) (current + 10000L).coerceAtMost(duration) else (current + 10000L)
                    playerManager.seekTo(target)
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onStateChanged(state: PlayerState) {
        runOnUiThread {
            when (state) {
                PlayerState.READY, PlayerState.BUFFERING -> {
                    showPlayer()
                }
                PlayerState.IDLE, PlayerState.ENDED -> {
                    showStandby()
                }
                PlayerState.ERROR -> {
                    showStandby()
                }
            }
        }
    }

    override fun onPlaybackStarted(url: String) {
        runOnUiThread {
            Log.i(TAG, "Playback started: $url")
            showPlayer()
        }
    }

    override fun onPlaybackStopped() {
        runOnUiThread {
            Log.i(TAG, "Playback stopped")
            showStandby()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Log.e(TAG, "Playback error: $error")
            showStandby()
        }
    }

    private fun showPlayer() {
        binding.playerView.visibility = View.VISIBLE
        binding.standbyLayout.visibility = View.GONE
    }

    private fun showStandby() {
        binding.playerView.visibility = View.GONE
        binding.standbyLayout.visibility = View.VISIBLE
        updateDeviceInfo()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
