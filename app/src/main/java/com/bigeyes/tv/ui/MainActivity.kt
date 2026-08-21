package com.bigeyes.tv.ui

import android.app.ProgressDialog
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
import androidx.media3.ui.AspectRatioFrameLayout
import com.bigeyes.tv.config.TvPlayerConfig
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TvPlayerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerManager: TvPlayerManager
    private lateinit var deviceIdManager: DeviceIdManager
    private lateinit var updateManager: UpdateManager

    private var updateDialog: AlertDialog? = null
    private var downloadProgressDialog: ProgressDialog? = null
    private var exitConfirmDialog: AlertDialog? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideOverlayRunnable: Runnable? = null
    private var progressUpdateRunnable: Runnable? = null

    // Holding speed (Long-press Left 0.5x / Right 3.0x)
    private var isHoldingSpeed = false
    private var pendingSpeedHoldRunnable: Runnable? = null

    // Scrubbing (Seekbar sliding like a mouse with real-time time preview)
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
        playerManager = TvPlayerManager.getInstance(this)
        updateManager = UpdateManager(this)

        updateDeviceInfo()
        setupOverlayControls()

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

        binding.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playerManager.getDurationMs()
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
                val duration = playerManager.getDurationMs()
                if (duration > 0 && seekBar != null) {
                    val targetMs = (seekBar.progress.toFloat() / 1000f * duration).toLong()
                    playerManager.seekTo(targetMs)
                }
                resetOverlayHideTimer()
            }
        })

        binding.btnOverlayPlayPause.setOnClickListener {
            playerManager.togglePlayPause()
            updateOverlayPlayPauseButton()
            resetOverlayHideTimer()
        }

        binding.btnOverlayRewind.setOnClickListener {
            val current = playerManager.getCurrentPositionMs()
            val target = (current - TvPlayerConfig.QuickSeek.REWIND_STEP_MS).coerceAtLeast(0L)
            playerManager.seekTo(target)
            updateOverlayProgress(target)
            resetOverlayHideTimer()
        }

        binding.btnOverlayForward.setOnClickListener {
            val current = playerManager.getCurrentPositionMs()
            val duration = playerManager.getDurationMs()
            val step = TvPlayerConfig.QuickSeek.FORWARD_STEP_MS
            val target = if (duration > 0) (current + step).coerceAtMost(duration) else (current + step)
            playerManager.seekTo(target)
            updateOverlayProgress(target)
            resetOverlayHideTimer()
        }

        binding.btnOverlayNextEpisode.setOnClickListener {
            val played = playerManager.playNext()
            if (played) {
                Toast.makeText(this, "正在为您播放下一集...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "暂无下一集预加载地址，可在手机端点击下一集", Toast.LENGTH_SHORT).show()
            }
            resetOverlayHideTimer()
        }

        binding.btnOverlaySpeed.setOnClickListener {
            val speedOptions = TvPlayerConfig.PlaybackOptions.SPEED_OPTIONS
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            val newSpeed = speedOptions[currentSpeedIndex]
            playerManager.setPlaybackSpeed(newSpeed)
            binding.btnOverlaySpeed.text = "倍速 ${newSpeed}x"
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
            hideOverlay()
            playerManager.stop()
        }
    }

    /**
     * Start or update sliding on SeekBar like a mouse, displaying real-time time preview
     */
    private fun startOrUpdateScrub(isForward: Boolean, repeatCount: Int) {
        val durationMs = playerManager.getDurationMs()
        if (durationMs <= 0) return

        // Cancel any pending debounce commit
        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        commitScrubRunnable = null

        val now = System.currentTimeMillis()
        if (!isScrubbing) {
            isScrubbing = true
            scrubOriginMs = playerManager.getCurrentPositionMs()
            scrubTargetMs = scrubOriginMs
            scrubHoldStartTime = now
        }

        // Stepped acceleration algorithm based on hold time
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

        // Update SeekBar position visually (like dragging with a mouse)
        val progress = ((scrubTargetMs.toFloat() / durationMs.toFloat()) * 1000).toInt()
        binding.overlaySeekBar.progress = progress.coerceIn(0, 1000)

        // Update current time label
        binding.tvOverlayCurrentTime.text = formatSecondsToTime(scrubTargetMs / 1000)

        // Update floating preview bubble
        binding.tvScrubPreviewTime.text = formatSecondsToTime(scrubTargetMs / 1000)
        val deltaSec = (scrubTargetMs - scrubOriginMs) / 1000
        val sign = if (deltaSec >= 0) "+" else "-"
        binding.tvScrubDeltaTime.text = "($sign${formatSecondsToTime(Math.abs(deltaSec))})"
        binding.layoutScrubPreview.visibility = View.VISIBLE

        resetOverlayHideTimer()
    }

    /**
     * Commit the scrubbed target position to ExoPlayer
     */
    private fun commitScrub() {
        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        commitScrubRunnable = null

        if (!isScrubbing) return
        isScrubbing = false

        Log.i(TAG, "Committing scrub position to: $scrubTargetMs ms")
        playerManager.seekTo(scrubTargetMs)
        updateOverlayProgress(scrubTargetMs)

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

    /**
     * Cancel scrubbing without seeking
     */
    private fun cancelScrub() {
        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        commitScrubRunnable = null

        if (!isScrubbing) return
        isScrubbing = false

        binding.layoutScrubPreview.visibility = View.INVISIBLE
        updateOverlayProgress()
        Log.i(TAG, "Scrubbing cancelled, restored to origin")
    }

    private fun scheduleCommitScrub() {
        if (!isScrubbing) return
        commitScrubRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { commitScrub() }
        commitScrubRunnable = r
        mainHandler.postDelayed(r, TvPlayerConfig.Scrubbing.COMMIT_DEBOUNCE_DELAY_MS)
    }

    private fun showOverlay(focusOnSeekBar: Boolean = false) {
        if (binding.playerView.visibility != View.VISIBLE) return
        binding.playbackOverlay.visibility = View.VISIBLE
        updateOverlayPlayPauseButton()
        updateOverlayHeader()
        updateOverlayProgress()

        if (focusOnSeekBar) {
            binding.overlaySeekBar.requestFocus()
        } else {
            // Default focus on Play/Pause button
            binding.btnOverlayPlayPause.requestFocus()
        }

        startProgressUpdates()
        resetOverlayHideTimer()
    }

    private fun hideOverlay() {
        cancelScrub()
        binding.playbackOverlay.visibility = View.GONE
        stopProgressUpdates()
        hideOverlayRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun isOverlayVisible(): Boolean {
        return binding.playbackOverlay.visibility == View.VISIBLE
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

    private fun startProgressUpdates() {
        stopProgressUpdates()
        val r = object : Runnable {
            override fun run() {
                if (isOverlayVisible()) {
                    if (!isScrubbing) {
                        updateOverlayProgress()
                    }
                    updateOverlayClock()
                    mainHandler.postDelayed(this, TvPlayerConfig.Overlay.PROGRESS_UPDATE_INTERVAL_MS)
                }
            }
        }
        progressUpdateRunnable = r
        mainHandler.post(r)
    }

    private fun stopProgressUpdates() {
        progressUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        progressUpdateRunnable = null
    }

    private fun updateOverlayPlayPauseButton() {
        val isPlaying = playerManager.isPlaying()
        binding.btnOverlayPlayPause.text = if (isPlaying) "暂停" else "播放"
    }

    private fun updateOverlayHeader() {
        val url = playerManager.currentUrl
        binding.tvOverlayTitle.text = if (!url.isNullOrBlank()) "正在投屏播放" else "大屏播放器"
        updateOverlayClock()
    }

    private fun updateOverlayClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvOverlayClock.text = sdf.format(Date())
    }

    private fun updateOverlayProgress(forcedCurrentMs: Long? = null) {
        val currentMs = forcedCurrentMs ?: playerManager.getCurrentPositionMs()
        val durationMs = playerManager.getDurationMs()

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

    private fun activateHoldingSpeed(speed: Float, hudText: String) {
        if (binding.playerView.visibility != View.VISIBLE) return
        isHoldingSpeed = true
        playerManager.setPlaybackSpeed(speed)
        binding.tvSpeedHudText.text = hudText
        binding.speedHudLayout.visibility = View.VISIBLE
        Log.i(TAG, "Holding speed activated: $speed ($hudText)")
    }

    private fun deactivateHoldingSpeed() {
        if (!isHoldingSpeed) return
        isHoldingSpeed = false
        val normalSpeed = TvPlayerConfig.PlaybackOptions.SPEED_OPTIONS[currentSpeedIndex]
        playerManager.setPlaybackSpeed(normalSpeed)
        binding.speedHudLayout.visibility = View.GONE
        Log.i(TAG, "Holding speed deactivated -> Restored to ${normalSpeed}x")
    }

    private fun showExitPlaybackConfirmDialog() {
        if (exitConfirmDialog?.isShowing == true) return

        val dialog = AlertDialog.Builder(this)
            .setTitle("退出投屏播放")
            .setMessage("确定要结束当前视频播放并返回主页吗？")
            .setCancelable(true)
            .setPositiveButton("确认退出") { _, _ ->
                playerManager.stop()
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

        // Focus "确认退出" for TV remote control
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateDeviceInfo()
        playerManager.attachPlayerView(binding.playerView)
        updateManager.checkAndResumePendingInstall(this)
    }

    override fun onDestroy() {
        exitConfirmDialog?.dismiss()
        exitConfirmDialog = null
        cancelScrub()
        deactivateHoldingSpeed()
        pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSpeedHoldRunnable = null
        hideOverlay()
        updateDialog?.dismiss()
        downloadProgressDialog?.dismiss()
        playerManager.removeListener(this)
        playerManager.detachPlayerView(binding.playerView)
        super.onDestroy()
    }

    /**
     * D-pad and TV Remote key event handling
     * - When overlay is hidden:
     *   - Holding Right: 3.0x speed, release to restore 1.0x normal
     *   - Holding Left: 0.5x speed, release to restore 1.0x normal
     *   - Short tap OK / D-pad: Show control overlay (Scheme A)
     *   - Back key: Show confirmation dialog to exit (Situation B)
     * - When overlay is visible:
     *   - If overlaySeekBar is focused: Left/Right to adjust progress, Down to focus button bar, OK to play/pause
     *   - If bottom buttons are focused: Up to focus overlaySeekBar, Left/Right to navigate buttons, OK to click
     *   - Back key: Hide overlay only (keep playing)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If exit confirm dialog is currently showing, let it handle keys
        if (exitConfirmDialog?.isShowing == true) {
            return super.onKeyDown(keyCode, event)
        }

        // If player is currently active/visible
        if (binding.playerView.visibility == View.VISIBLE) {
            if (!isOverlayVisible()) {
                // When overlay is HIDDEN:
                // Handle long-press holding on Right (3.0x speed) and Left (0.5x speed)
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event?.repeatCount == 0) {
                        if (pendingSpeedHoldRunnable == null && !isHoldingSpeed) {
                            val r = Runnable {
                                activateHoldingSpeed(
                                    TvPlayerConfig.HoldingSpeed.FAST_FORWARD_SPEED,
                                    TvPlayerConfig.HoldingSpeed.HUD_FAST_FORWARD_TEXT
                                )
                            }
                            pendingSpeedHoldRunnable = r
                            mainHandler.postDelayed(r, TvPlayerConfig.HoldingSpeed.TRIGGER_DELAY_MS)
                        }
                    } else if (event != null && event.repeatCount >= 1) {
                        if (!isHoldingSpeed) {
                            pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingSpeedHoldRunnable = null
                            activateHoldingSpeed(
                                TvPlayerConfig.HoldingSpeed.FAST_FORWARD_SPEED,
                                TvPlayerConfig.HoldingSpeed.HUD_FAST_FORWARD_TEXT
                            )
                        }
                        return true
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (event?.repeatCount == 0) {
                        if (pendingSpeedHoldRunnable == null && !isHoldingSpeed) {
                            val r = Runnable {
                                activateHoldingSpeed(
                                    TvPlayerConfig.HoldingSpeed.SLOW_MOTION_SPEED,
                                    TvPlayerConfig.HoldingSpeed.HUD_SLOW_MOTION_TEXT
                                )
                            }
                            pendingSpeedHoldRunnable = r
                            mainHandler.postDelayed(r, TvPlayerConfig.HoldingSpeed.TRIGGER_DELAY_MS)
                        }
                    } else if (event != null && event.repeatCount >= 1) {
                        if (!isHoldingSpeed) {
                            pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingSpeedHoldRunnable = null
                            activateHoldingSpeed(
                                TvPlayerConfig.HoldingSpeed.SLOW_MOTION_SPEED,
                                TvPlayerConfig.HoldingSpeed.HUD_SLOW_MOTION_TEXT
                            )
                        }
                        return true
                    }
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        Log.i(TAG, "Remote BACK pressed while overlay hidden -> Showing exit confirm dialog")
                        showExitPlaybackConfirmDialog()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                        Log.i(TAG, "Remote key $keyCode pressed -> Showing control overlay")
                        showOverlay()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        Log.i(TAG, "Remote UP pressed -> Showing control overlay with focus on SeekBar")
                        showOverlay(focusOnSeekBar = true)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // Captured for holding speed; short tap will show overlay in onKeyUp if not held
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        playerManager.togglePlayPause()
                        showOverlay()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        playerManager.resume()
                        showOverlay()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        playerManager.pause()
                        showOverlay()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        playerManager.stop()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val current = playerManager.getCurrentPositionMs()
                        val target = (current - TvPlayerConfig.QuickSeek.REWIND_STEP_MS).coerceAtLeast(0L)
                        playerManager.seekTo(target)
                        showOverlay()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val current = playerManager.getCurrentPositionMs()
                        val duration = playerManager.getDurationMs()
                        val step = TvPlayerConfig.QuickSeek.FORWARD_STEP_MS
                        val target = if (duration > 0) (current + step).coerceAtMost(duration) else (current + step)
                        playerManager.seekTo(target)
                        showOverlay()
                        return true
                    }
                }
            } else {
                // When overlay is VISIBLE:
                resetOverlayHideTimer()

                when (keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        Log.i(TAG, "Remote BACK pressed while overlay visible -> Hiding overlay only")
                        hideOverlay()
                        return true
                    }
                }

                // Check if progress bar (SeekBar) is currently focused
                if (binding.overlaySeekBar.hasFocus()) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            startOrUpdateScrub(isForward = false, repeatCount = event?.repeatCount ?: 0)
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            startOrUpdateScrub(isForward = true, repeatCount = event?.repeatCount ?: 0)
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (isScrubbing) {
                                commitScrub()
                            }
                            binding.btnOverlayPlayPause.requestFocus()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (isScrubbing) {
                                commitScrub()
                            } else {
                                playerManager.togglePlayPause()
                                updateOverlayPlayPauseButton()
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            if (isScrubbing) {
                                cancelScrub()
                                return true
                            }
                            hideOverlay()
                            return true
                        }
                    }
                } else {
                    // One of the bottom buttons is focused
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            binding.overlaySeekBar.requestFocus()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            val focused = currentFocus
                            if (focused != null && focused is Button) {
                                Log.i(TAG, "Remote OK clicked on focused button: ${focused.text}")
                                focused.performClick()
                                return true
                            }
                        }
                    }
                }

                // Let DPAD_LEFT, DPAD_RIGHT, DPAD_UP, DPAD_DOWN move focus normally across buttons
                return super.onKeyDown(keyCode, event)
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (binding.playerView.visibility == View.VISIBLE) {
            if (isOverlayVisible() && binding.overlaySeekBar.hasFocus()) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    scheduleCommitScrub()
                    return true
                }
            }

            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingSpeedHoldRunnable = null

                if (isHoldingSpeed) {
                    deactivateHoldingSpeed()
                    return true
                } else if (!isOverlayVisible()) {
                    // Short tap on left/right when overlay is hidden -> show overlay
                    showOverlay()
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStateChanged(state: PlayerState) {
        runOnUiThread {
            when (state) {
                PlayerState.READY, PlayerState.BUFFERING -> {
                    showPlayer()
                }
                PlayerState.IDLE, PlayerState.ENDED -> {
                    deactivateHoldingSpeed()
                    hideOverlay()
                    showStandby()
                }
                PlayerState.ERROR -> {
                    deactivateHoldingSpeed()
                    hideOverlay()
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
            deactivateHoldingSpeed()
            hideOverlay()
            showStandby()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Log.e(TAG, "Playback error: $error")
            deactivateHoldingSpeed()
            hideOverlay()
            showStandby()
        }
    }

    private fun showPlayer() {
        binding.playerView.visibility = View.VISIBLE
        binding.standbyLayout.visibility = View.GONE
    }

    private fun showStandby() {
        exitConfirmDialog?.dismiss()
        exitConfirmDialog = null
        deactivateHoldingSpeed()
        pendingSpeedHoldRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingSpeedHoldRunnable = null
        binding.playerView.visibility = View.GONE
        binding.standbyLayout.visibility = View.VISIBLE
        updateDeviceInfo()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
