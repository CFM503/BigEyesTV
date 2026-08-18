package com.bigeyes.tv.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.bigeyes.tv.databinding.ActivityMainBinding
import com.bigeyes.tv.player.PlayerState
import com.bigeyes.tv.player.TvPlayerListener
import com.bigeyes.tv.player.TvPlayerManager
import com.bigeyes.tv.service.TvReceiverService
import com.bigeyes.tv.utils.DeviceIdManager
import com.bigeyes.tv.utils.NetworkUtils

class MainActivity : AppCompatActivity(), TvPlayerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var deviceIdManager: DeviceIdManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceIdManager = DeviceIdManager.getInstance(this)
        playerManager = TvPlayerManager.getInstance(this)

        updateDeviceInfo()

        playerManager.addListener(this)
        playerManager.attachPlayerView(binding.playerView)

        // Start background receiver service (AirPlay & DLNA)
        TvReceiverService.start(this)
    }

    private fun updateDeviceInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        binding.tvDeviceName.text = "设备名称：${deviceIdManager.deviceName}"
        binding.tvIpAddress.text = "服务地址：http://$ip:${TvReceiverService.SERVER_PORT}"
        binding.tvDeviceId.text = "DeviceID (AirPlay MAC)：${deviceIdManager.deviceId}"
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        playerManager.attachPlayerView(binding.playerView)
    }

    override fun onDestroy() {
        playerManager.removeListener(this)
        playerManager.detachPlayerView(binding.playerView)
        super.onDestroy()
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
