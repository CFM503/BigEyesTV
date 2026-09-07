package com.bigeyes.tv.ui

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bigeyes.tv.config.TvPlayerConfig
import com.bigeyes.tv.databinding.ActivityMainBinding
import com.bigeyes.tv.player.command.PlaybackCommand
import com.bigeyes.tv.player.contract.PlaybackIntentContract
import com.bigeyes.tv.player.controller.PlaybackController
import com.bigeyes.tv.player.model.PlaybackSession
import com.bigeyes.tv.player.model.PlaybackState
import com.bigeyes.tv.player.remote.TvRemoteController
import com.bigeyes.tv.service.TvReceiverService
import com.bigeyes.tv.ui.dialog.EpisodeListDialog
import com.bigeyes.tv.update.ReleaseInfo
import com.bigeyes.tv.update.UpdateManager
import com.bigeyes.tv.utils.DeviceIdManager
import com.bigeyes.tv.utils.NetworkUtils
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TvRemoteController.RemoteCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: PlaybackController
    private lateinit var remoteController: TvRemoteController
    private lateinit var deviceIdManager: DeviceIdManager
    private lateinit var updateManager: UpdateManager

    private var updateDialog: AlertDialog? = null
    private var downloadProgressDialog: ProgressDialog? = null
    private var exitConfirmDialog: AlertDialog? = null
    private var episodeListDialog: EpisodeListDialog? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideOverlayRunnable: Runnable? = null

    // Holding speed (Long-press Left 0.5x / Right 3.0x)
    private var isHoldingSpeed = false

    // Scrubbing (Seekbar sliding like a mouse with real-time preview)
    private var isScrubbing = false
    private var scrubOriginMs = 0L
    private var scrubTargetMs = 0L
    private var scrubHoldStartTime = 0L
    private var commitScrubRunnable: Runnable? = null

    private var currentSpeedIndex = 0
    private var currentAspectRatioIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceIdManager = DeviceIdManager.getInstance(this)
        controller = PlaybackController.getInstance(this)
        remoteController = TvRemoteController(controller, this)
        updateManager = UpdateManager(this)

        updateDeviceInfo()
        setupOverlayControls()
        setupEndAndErrorControls()
        observePlaybackSession()

        // Start background receiver service (AirPlay & DLNA)
        TvReceiverService.start(this)

        // Handle initial intent
        handleIncomingIntent(intent)

        // Check for updates asynchronously
        checkAppUpdate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val command = PlaybackIntentContract.parseCommand(incomingIntent)
        if (command != null) {
            Log.i(TAG, "Executing intent command: $command")
            controller.dispatch(command)
        } else if (incomingIntent.action?.startsWith("com.bigeyes.tv.action") == true) {
            Log.w(TAG, "Unrecognized or invalid BigEyesTV action: ${incomingIntent.action}")
            Toast.makeText(this, "无效的播放请求参数", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDeviceInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        val version = updateManager.getAppVersionName()
        binding.tvDeviceName.text = "设备名称：${deviceIdManager.deviceName}"
        binding.tvIpAddress.text = "服务地址：http://$ip:${TvReceiverService.SERVER_PORT}"
        binding.tvDeviceId.text = "DeviceID (AirPlay MAC)：${deviceIdManager.deviceId}"
        binding.tvVersion.text = "当前版本：v$version"
    }

    private fun observePlaybackSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.session.collect { session ->
                    renderSessionState(session)
                }
            }
        }
    }

    private fun renderSessionState(session: PlaybackSession) {
        // 1. Update Title and Clock
        val ep = session.currentEpisode
        binding.tvOverlayTitle.text = ep?.toDisplayTitle() ?: if (!session.isIdle) "正在播放" else "大屏播放器"
        updateOverlayClock()

        // 2. Play/Pause Button
        binding.btnOverlayPlayPause.text = if (session.isPlaying) "暂停" else "播放"

        // 3. Next / Previous / Episode List visibility & status
        val queue = controller.episodeQueue
        binding.btnOverlayPrevEpisode.visibility = if (queue.hasPrevious()) View.VISIBLE else View.GONE
        binding.btnOverlayNextEpisode.visibility = if (queue.hasNext()) View.VISIBLE else View.GONE
        binding.btnOverlayEpisodeList.visibility = if (queue.size > 1) View.VISIBLE else View.GONE
        binding.btnOverlayAutoPlay.text = if (session.autoPlayNext) "连播: 开" else "连播: 关"
        binding.btnOverlayAutoPlay.setTextColor(if (session.autoPlayNext) Color.parseColor("#FFD700") else Color.WHITE)

        // 4. Speed
        val speedText = String.format(Locale.US, "%.2fx", session.speed)
        binding.btnOverlaySpeed.text = "倍速 $speedText"

        // 5. Progress updates (when not actively scrubbing)
        if (!isScrubbing) {
            updateProgressUi(session.position, session.duration)
        }

        // 6. 10-Second Auto Next Countdown Card
        if (session.hasCountdown) {
            val nextEp = queue.peekNext()
            val epNumber = nextEp?.episodeNumber ?: (session.currentIndex + 2)
            binding.tvCountdownMessage.text = "即将播放下一集：第 ${epNumber} 集 (${session.countdownRemainingSeconds}s)"
            binding.layoutCountdownCard.visibility = View.VISIBLE
        } else {
            binding.layoutCountdownCard.visibility = View.GONE
        }

        // 7. State Transitions
        when (session.playbackState) {
            PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING, PlaybackState.LOADING -> {
                showPlayer()
                binding.layoutPlaybackEnd.visibility = View.GONE
                binding.layoutPlaybackError.visibility = View.GONE

                if (session.playbackState == PlaybackState.BUFFERING || session.playbackState == PlaybackState.LOADING) {
                    binding.bufferingOverlay.visibility = View.VISIBLE
                    binding.tvBufferingMessage.text = if (session.playbackState == PlaybackState.LOADING) "正在加载视频..." else "正在缓冲..."
                } else {
                    binding.bufferingOverlay.visibility = View.GONE
                }
            }
            PlaybackState.COMPLETED -> {
                hideOverlay()
                binding.bufferingOverlay.visibility = View.GONE
                binding.layoutCountdownCard.visibility = View.GONE

                binding.layoutPlaybackEnd.visibility = View.VISIBLE
                binding.layoutPlaybackError.visibility = View.GONE

                if (session.isLastEpisode) {
                    binding.tvEndTitle.text = "全剧播放完毕"
                    binding.tvEndSubtitle.text = "所有剧集已播放结束"
                    binding.btnEndNextEpisode.visibility = View.GONE
                    binding.btnEndReplay.requestFocus()
                } else {
                    binding.tvEndTitle.text = "播放结束"
                    binding.tvEndSubtitle.text = "当前集已播放完毕"
                    binding.btnEndNextEpisode.visibility = View.VISIBLE
                    binding.btnEndNextEpisode.requestFocus()
                }
            }
            PlaybackState.ERROR -> {
                hideOverlay()
                binding.bufferingOverlay.visibility = View.GONE
                binding.layoutCountdownCard.visibility = View.GONE

                binding.layoutPlaybackError.visibility = View.VISIBLE
                binding.layoutPlaybackEnd.visibility = View.GONE

                val epNum = session.currentEpisode?.episodeNumber ?: (session.currentIndex + 1)
                binding.tvErrorTitle.text = "第 ${epNum} 集播放失败"
                binding.tvErrorMessage.text = session.errorMessage ?: "视频地址加载失败，请重试"

                binding.btnErrorPrevious.visibility = if (queue.hasPrevious()) View.VISIBLE else View.GONE
                binding.btnErrorNext.visibility = if (queue.hasNext()) View.VISIBLE else View.GONE
                binding.btnErrorRetry.requestFocus()
            }
            PlaybackState.IDLE, PlaybackState.STOPPED -> {
                hideOverlay()
                binding.bufferingOverlay.visibility = View.GONE
                binding.layoutCountdownCard.visibility = View.GONE
                binding.layoutPlaybackEnd.visibility = View.GONE
                binding.layoutPlaybackError.visibility = View.GONE
                showStandby()
            }
        }
    }

    private fun updateProgressUi(currentMs: Long, durationMs: Long) {
        val curSec = currentMs / 1000
        val durSec = durationMs / 1000

        binding.tvOverlayCurrentTime.text = formatSecondsToTime(curSec)
        binding.tvOverlayTotalTime.text = formatSecondsToTime(durSec)

        if (durSec > 0) {
            val progress = ((curSec.toFloat() / durSec.toFloat()) * 1000).toInt()
            binding.overlaySeekBar.progress = progress.coerceIn(0, 1000)
        } else {
            binding.overlaySeekBar.progress = 0
        }
    }

    private fun setupOverlayControls() {
        // Progress Bar (SeekBar) Focus & Interaction
        binding.overlaySeekBar.setOnFocusChangeListener { _, hasFocus ->
            binding.layoutProgressContainer.isActivated = hasFocus
            if (hasFocus) {
                binding.tvOverlayCurrentTime.setTextColor(Color.parseColor("#FFD700"))
                binding.tvOverlayCurrentTime.textSize = 18f
                resetOverlayHideTimer()
            } else {
                if (isScrubbing) {
                    commitScrub()
                }
                binding.tvOverlayCurrentTime.setTextColor(Color.WHITE)
                binding.tvOverlayCurrentTime.textSize = 16f
            }
        }

        binding.overlaySeekBar.setOnKeyListener { _, keyCode, event ->
            if (event?.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                startOrUpdateScrub(
                    isForward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT,
                    repeatCount = event.repeatCount
                )
                true
            } else if (event?.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                commitScrub()
                true
            } else {
                false
            }
        }

        binding.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = controller.playerEngine.getDurationMs()
                    if (duration > 0) {
                        val targetMs = (progress.toFloat() / 1000f * duration).toLong()
                        binding.tvOverlayCurrentTime.text = formatSecondsToTime(targetMs / 1000)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                resetOverlayHideTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = controller.playerEngine.getDurationMs()
                if (duration > 0 && seekBar != null) {
                    val targetMs = (seekBar.progress.toFloat() / 1000f * duration).toLong()
                    controller.dispatch(PlaybackCommand.Seek(targetMs))
                }
                resetOverlayHideTimer()
            }
        })

        binding.btnOverlayPlayPause.setOnClickListener {
            controller.dispatch(PlaybackCommand.TogglePlayPause)
            resetOverlayHideTimer()
        }

        binding.btnOverlayPrevEpisode.setOnClickListener {
            controller.dispatch(PlaybackCommand.Previous)
            resetOverlayHideTimer()
        }

        binding.btnOverlayNextEpisode.setOnClickListener {
            controller.dispatch(PlaybackCommand.Next)
            resetOverlayHideTimer()
        }

        binding.btnOverlayEpisodeList.setOnClickListener {
            showEpisodeListDialog()
        }

        binding.btnOverlayAutoPlay.setOnClickListener {
            val newAuto = !controller.session.value.autoPlayNext
            controller.dispatch(PlaybackCommand.SetAutoPlayNext(newAuto))
            Toast.makeText(this, if (newAuto) "已开启自动播放下一集" else "已关闭自动播放下一集", Toast.LENGTH_SHORT).show()
            resetOverlayHideTimer()
        }

        binding.btnOverlaySpeed.setOnClickListener {
            val speedOptions = TvPlayerConfig.PlaybackOptions.SPEED_OPTIONS
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            val newSpeed = speedOptions[currentSpeedIndex]
            controller.dispatch(PlaybackCommand.SetSpeed(newSpeed))
            Toast.makeText(this, "已切换为 ${newSpeed}x 倍速", Toast.LENGTH_SHORT).show()
            resetOverlayHideTimer()
        }

        binding.btnOverlayAspectRatio.setOnClickListener {
            val aspectRatios = TvPlayerConfig.PlaybackOptions.ASPECT_RATIOS
            val aspectRatioNames = TvPlayerConfig.PlaybackOptions.ASPECT_RATIO_NAMES
            currentAspectRatioIndex = (currentAspectRatioIndex + 1) % aspectRatios.size
            val newMode = aspectRatios[currentAspectRatioIndex]
            val modeName = aspectRatioNames[currentAspectRatioIndex]
            binding.playerView.resizeMode = newMode
            binding.btnOverlayAspectRatio.text = modeName
            Toast.makeText(this, "画面$modeName", Toast.LENGTH_SHORT).show()
            resetOverlayHideTimer()
        }

        binding.btnOverlayExit.setOnClickListener {
            showExitConfirmDialog()
        }

        // Countdown Card Buttons
        binding.btnCountdownPlayNow.setOnClickListener {
            controller.dispatch(PlaybackCommand.Next)
        }

        binding.btnCountdownCancel.setOnClickListener {
            cancelCountdown()
        }
    }

    private fun setupEndAndErrorControls() {
        // End overlay buttons
        binding.btnEndReplay.setOnClickListener {
            controller.dispatch(PlaybackCommand.PlayEpisode(controller.episodeQueue.currentIndex, 0L))
        }

        binding.btnEndNextEpisode.setOnClickListener {
            controller.dispatch(PlaybackCommand.Next)
        }

        binding.btnEndEpisodeList.setOnClickListener {
            showEpisodeListDialog()
        }

        binding.btnEndExit.setOnClickListener {
            controller.dispatch(PlaybackCommand.Stop)
        }

        // Error overlay buttons
        binding.btnErrorRetry.setOnClickListener {
            controller.dispatch(PlaybackCommand.Retry)
        }

        binding.btnErrorPrevious.setOnClickListener {
            controller.dispatch(PlaybackCommand.Previous)
        }

        binding.btnErrorNext.setOnClickListener {
            controller.dispatch(PlaybackCommand.Next)
        }

        binding.btnErrorExit.setOnClickListener {
            controller.dispatch(PlaybackCommand.Stop)
        }
    }

    private fun showEpisodeListDialog() {
        if (episodeListDialog?.isShowing == true) return
        val dialog = EpisodeListDialog(this, controller)
        episodeListDialog = dialog
        dialog.show()
    }

    // ==================== RemoteCallback Implementation ====================

    override fun isOverlayVisible(): Boolean = binding.playbackOverlay.visibility == View.VISIBLE

    override fun showOverlay(focusOnSeekBar: Boolean) {
        if (binding.playerView.visibility != View.VISIBLE) return
        binding.playbackOverlay.visibility = View.VISIBLE
        updateOverlayClock()

        if (focusOnSeekBar) {
            binding.overlaySeekBar.requestFocus()
        } else {
            binding.btnOverlayPlayPause.requestFocus()
        }
        resetOverlayHideTimer()
    }

    override fun hideOverlay() {
        cancelScrub()
        binding.playbackOverlay.visibility = View.GONE
        hideOverlayRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    override fun isDialogShowing(): Boolean {
        return exitConfirmDialog?.isShowing == true ||
                episodeListDialog?.isShowing == true ||
                updateDialog?.isShowing == true
    }

    override fun showExitConfirmDialog() {
        if (exitConfirmDialog?.isShowing == true) return

        val dialog = AlertDialog.Builder(this)
            .setTitle("退出投屏播放")
            .setMessage("确定要结束当前视频播放并返回主页吗？")
            .setCancelable(true)
            .setPositiveButton("确认退出") { _, _ ->
                controller.dispatch(PlaybackCommand.Stop)
            }
            .setNegativeButton("继续播放") { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            .setOnDismissListener {
                exitConfirmDialog = null
            }
            .create()

        exitConfirmDialog = dialog
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    override fun isHoldingSpeed(): Boolean = isHoldingSpeed

    override fun activateHoldingSpeed(speed: Float, hudText: String) {
        if (binding.playerView.visibility != View.VISIBLE) return
        isHoldingSpeed = true
        controller.dispatch(PlaybackCommand.SetSpeed(speed))
        binding.tvSpeedHudText.text = hudText
        binding.speedHudLayout.visibility = View.VISIBLE
    }

    override fun deactivateHoldingSpeed() {
        if (!isHoldingSpeed) return
        isHoldingSpeed = false
        val normalSpeed = TvPlayerConfig.PlaybackOptions.SPEED_OPTIONS[currentSpeedIndex]
        controller.dispatch(PlaybackCommand.SetSpeed(normalSpeed))
        binding.speedHudLayout.visibility = View.GONE
    }

    override fun isScrubbing(): Boolean = isScrubbing

    override fun startOrUpdateScrub(isForward: Boolean, repeatCount: Int) {
        val durationMs = controller.playerEngine.getDurationMs()
        if (durationMs <= 0) return

        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        commitScrubRunnable = null

        val now = System.currentTimeMillis()
        if (!isScrubbing) {
            isScrubbing = true
            scrubOriginMs = controller.playerEngine.getCurrentPositionMs()
            scrubTargetMs = scrubOriginMs
            scrubHoldStartTime = now
        }

        val holdDuration = now - scrubHoldStartTime
        val stepMs: Long = when {
            repeatCount == 0 || holdDuration < TvPlayerConfig.Scrubbing.STAGE_1_MAX_HOLD_MS -> TvPlayerConfig.Scrubbing.STAGE_1_STEP_MS
            holdDuration < TvPlayerConfig.Scrubbing.STAGE_2_MAX_HOLD_MS -> TvPlayerConfig.Scrubbing.STAGE_2_STEP_MS
            holdDuration < TvPlayerConfig.Scrubbing.STAGE_3_MAX_HOLD_MS -> TvPlayerConfig.Scrubbing.STAGE_3_STEP_MS
            else -> TvPlayerConfig.Scrubbing.STAGE_4_STEP_MS
        }

        scrubTargetMs = if (isForward) {
            (scrubTargetMs + stepMs).coerceAtMost(durationMs)
        } else {
            (scrubTargetMs - stepMs).coerceAtLeast(0L)
        }

        val progress = ((scrubTargetMs.toFloat() / durationMs.toFloat()) * 1000).toInt()
        binding.overlaySeekBar.progress = progress.coerceIn(0, 1000)

        binding.tvOverlayCurrentTime.text = formatSecondsToTime(scrubTargetMs / 1000)
        binding.tvScrubPreviewTime.text = formatSecondsToTime(scrubTargetMs / 1000)
        val deltaSec = (scrubTargetMs - scrubOriginMs) / 1000
        val sign = if (deltaSec >= 0) "+" else "-"
        binding.tvScrubDeltaTime.text = "($sign${formatSecondsToTime(Math.abs(deltaSec))})"
        binding.layoutScrubPreview.visibility = View.VISIBLE

        resetOverlayHideTimer()
    }

    override fun commitScrub() {
        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        commitScrubRunnable = null

        if (!isScrubbing) return
        isScrubbing = false

        controller.dispatch(PlaybackCommand.Seek(scrubTargetMs))
        updateProgressUi(scrubTargetMs, controller.playerEngine.getDurationMs())

        binding.layoutScrubPreview.animate()
            .alpha(0f)
            .setDuration(TvPlayerConfig.Scrubbing.TOOLTIP_FADE_DURATION_MS)
            .withEndAction {
                binding.layoutScrubPreview.visibility = View.INVISIBLE
                binding.layoutScrubPreview.alpha = 1f
            }
            .start()

        resetOverlayHideTimer()
    }

    override fun cancelScrub() {
        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        commitScrubRunnable = null

        if (!isScrubbing) return
        isScrubbing = false

        binding.layoutScrubPreview.visibility = View.INVISIBLE
        updateProgressUi(controller.playerEngine.getCurrentPositionMs(), controller.playerEngine.getDurationMs())
    }

    override fun isSeekBarFocused(): Boolean = binding.overlaySeekBar.hasFocus()

    override fun focusSeekBar() {
        binding.overlaySeekBar.requestFocus()
    }

    override fun focusButtonBar() {
        binding.btnOverlayPlayPause.requestFocus()
    }

    override fun performFocusedClick(): Boolean {
        val focused = currentFocus
        if (focused is Button) {
            focused.performClick()
            return true
        }
        return false
    }

    override fun cancelCountdown(): Boolean {
        if (controller.session.value.hasCountdown) {
            controller.dispatch(PlaybackCommand.CancelCountdown)
            binding.layoutCountdownCard.visibility = View.GONE
            Toast.makeText(this, "已取消自动下一集", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    // ==================== Remote Key Events Dispatching ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (remoteController.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (remoteController.onKeyUp(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun resetOverlayHideTimer() {
        hideOverlayRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (isOverlayVisible()) {
                hideOverlay()
            }
        }
        hideOverlayRunnable = r
        mainHandler.postDelayed(r, TvPlayerConfig.Overlay.AUTO_HIDE_DELAY_MS)
    }

    private fun updateOverlayClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvOverlayClock.text = sdf.format(Date())
    }

    private fun formatSecondsToTime(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
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

    // ==================== Update Checking ====================

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

    // ==================== Lifecycle ====================

    override fun onStart() {
        super.onStart()
        controller.attachPlayerView(binding.playerView)
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        updateManager.checkAndResumePendingInstall(this)
    }

    override fun onStop() {
        controller.detachPlayerView(binding.playerView)
        super.onStop()
    }

    override fun onDestroy() {
        exitConfirmDialog?.dismiss()
        exitConfirmDialog = null
        episodeListDialog?.dismiss()
        episodeListDialog = null
        updateDialog?.dismiss()
        updateDialog = null
        downloadProgressDialog?.dismiss()
        downloadProgressDialog = null

        remoteController.cleanup()
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
