package com.bigeyes.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bigeyes.tv.config.TvPlayerConfig
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unified ExoPlayer controller for both AirPlay and DLNA playback sessions.
 * Manages player lifecycle, thread dispatching, and state queries.
 * Includes intelligent buffering detection and network recovery mechanisms.
 */
class TvPlayerManager private constructor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private val listeners = CopyOnWriteArrayList<TvPlayerListener>()

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var nextUrl: String? = null
        private set

    @Volatile
    var currentState: PlayerState = PlayerState.IDLE
        private set

    @Volatile
    var isBuffering: Boolean = false
        private set

    @Volatile
    var bufferingMessage: String = ""
        private set

    private var activeUrl: String? = null
    private var lastKnownPositionMs = 0L
    private var recoveryAttempts = 0
    private var recoveryRunnable: Runnable? = null
    private var resumePlaybackOnFirstFrame = false

    // Buffering timeout detection
    private var bufferingStartTime = 0L
    private var bufferingTimeoutRunnable: Runnable? = null
    private var bufferingShowIndicatorRunnable: Runnable? = null

    // Auto-retry for network recovery
    private var autoRetryCount = 0
    private var autoRetryRunnable: Runnable? = null

    init {
        mainHandler.post {
            initPlayerOnMainThread()
        }
    }

    private fun initPlayerOnMainThread() {
        if (exoPlayer != null) return
        val player = ExoPlayer.Builder(context).build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (resumePlaybackOnFirstFrame) {
                    resumePlaybackOnFirstFrame = false
                    player.playWhenReady = true
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    val position = exoPlayer?.currentPosition ?: 0L
                    if (position > 0L) {
                        lastKnownPositionMs = position
                    }
                    resetRecovery()
                }

                if (playbackState == Player.STATE_ENDED) {
                    cancelBufferingTimeout()
                    cancelAutoRetry()
                    val next = nextUrl
                    if (!next.isNullOrBlank()) {
                        Log.i(TAG, "Current episode ended. Auto-advancing to preloaded next URL: $next")
                        nextUrl = null
                        play(next, 0L)
                        return
                    }
                }

                if (playbackState == Player.STATE_IDLE && activeUrl != null) {
                    scheduleRecovery()
                    return
                }

                val newState = when (playbackState) {
                    Player.STATE_IDLE -> PlayerState.IDLE
                    Player.STATE_BUFFERING -> PlayerState.BUFFERING
                    Player.STATE_READY -> PlayerState.READY
                    Player.STATE_ENDED -> PlayerState.ENDED
                    else -> PlayerState.IDLE
                }

                // Handle buffering state transitions
                handleBufferingStateChange(newState, playbackState)

                currentState = newState
                listeners.forEach { it.onStateChanged(newState) }
                if (newState == PlayerState.READY || newState == PlayerState.BUFFERING) {
                    listeners.forEach { it.onPlaybackStarted(currentUrl.orEmpty()) }
                }
                Log.d(TAG, "ExoPlayer state changed: $newState (playing=${player.isPlaying})")
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "Playback error: ${error.errorCodeName}"
                Log.e(TAG, "ExoPlayer error: $msg", error)
                cancelBufferingTimeout()
                cancelAutoRetry()

                if (activeUrl == null) {
                    notifyError(msg)
                    return
                }
                exoPlayer?.currentPosition?.takeIf { it > 0L }?.let {
                    lastKnownPositionMs = it
                }
                scheduleRecovery()
            }
        })
        exoPlayer = player
        Log.i(TAG, "ExoPlayer initialized on main thread.")
    }

    /**
     * Handle buffering state transitions with timeout detection and user feedback.
     */
    private fun handleBufferingStateChange(newState: PlayerState, playbackState: Int) {
        when (newState) {
            PlayerState.BUFFERING -> {
                // Start buffering timeout detection
                if (bufferingStartTime == 0L) {
                    bufferingStartTime = System.currentTimeMillis()
                    startBufferingTimeout()
                }

                // Show buffering indicator after delay (avoid flickering)
                if (!isBuffering) {
                    bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
                    bufferingShowIndicatorRunnable = Runnable {
                        if (currentState == PlayerState.BUFFERING) {
                            isBuffering = true
                            bufferingMessage = "正在缓冲..."
                            listeners.forEach { it.onBufferingStateChanged(true, bufferingMessage) }
                            Log.i(TAG, "Buffering indicator shown")
                        }
                    }
                    mainHandler.postDelayed(
                        bufferingShowIndicatorRunnable!!,
                        TvPlayerConfig.Buffering.SHOW_INDICATOR_DELAY_MS
                    )
                }
            }
            PlayerState.READY -> {
                // Buffering completed, reset all buffering state
                cancelBufferingTimeout()
                cancelAutoRetry()
                if (isBuffering) {
                    isBuffering = false
                    bufferingMessage = ""
                    bufferingStartTime = 0L
                    bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
                    listeners.forEach { it.onBufferingStateChanged(false, "") }
                    Log.i(TAG, "Buffering completed, playback resumed")
                }
            }
            else -> {
                // Other states: cancel buffering timeout
                cancelBufferingTimeout()
                if (isBuffering) {
                    isBuffering = false
                    bufferingMessage = ""
                    bufferingStartTime = 0L
                    bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
                }
            }
        }
    }

    /**
     * Start buffering timeout detection.
     * If buffering exceeds timeout, attempt auto-recovery or show dialog.
     */
    private fun startBufferingTimeout() {
        cancelBufferingTimeout()
        bufferingTimeoutRunnable = Runnable {
            val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
            Log.w(TAG, "Buffering timeout detected: ${bufferingDuration}ms")

            if (autoRetryCount < TvPlayerConfig.Buffering.MAX_AUTO_RETRIES) {
                // Try auto-recovery first
                autoRetryCount++
                val message = "网络不稳定，正在尝试恢复... ($autoRetryCount/${TvPlayerConfig.Buffering.MAX_AUTO_RETRIES})"
                isBuffering = true
                bufferingMessage = message
                listeners.forEach { it.onBufferingStateChanged(true, message) }
                listeners.forEach { it.onNetworkRetry(autoRetryCount, TvPlayerConfig.Buffering.MAX_AUTO_RETRIES) }

                // Schedule auto-retry
                autoRetryRunnable = Runnable {
                    if (activeUrl != null && currentState == PlayerState.BUFFERING) {
                        Log.i(TAG, "Auto-retry #$autoRetryCount attempting to recover playback")
                        attemptRecovery()
                    }
                }
                mainHandler.postDelayed(autoRetryRunnable!!, TvPlayerConfig.Buffering.AUTO_RETRY_INTERVAL_MS)
            } else {
                // Max retries reached, notify UI to show dialog
                isBuffering = true
                bufferingMessage = "网络连接中断"
                listeners.forEach { it.onBufferingStateChanged(true, bufferingMessage) }
                listeners.forEach { it.onNetworkInterrupted(lastKnownPositionMs) }
                Log.e(TAG, "Network interrupted, max auto-retries reached")
            }
        }
        mainHandler.postDelayed(bufferingTimeoutRunnable!!, TvPlayerConfig.Buffering.TIMEOUT_MS)
    }

    /**
     * Attempt to recover playback after network interruption.
     */
    private fun attemptRecovery() {
        val url = activeUrl ?: return
        val player = exoPlayer ?: return

        try {
            Log.i(TAG, "Attempting recovery from position: $lastKnownPositionMs ms")
            // Re-prepare the media item from last known position
            prepareCurrentMediaItem(player, lastKnownPositionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Recovery attempt failed: ${e.message}")
            scheduleRecovery()
        }
    }

    /**
     * Manual retry triggered by user dialog.
     */
    fun manualRetry() {
        Log.i(TAG, "Manual retry requested by user")
        autoRetryCount = 0
        cancelBufferingTimeout()
        cancelAutoRetry()

        if (activeUrl != null) {
            isBuffering = true
            bufferingMessage = "正在重新连接..."
            listeners.forEach { it.onBufferingStateChanged(true, bufferingMessage) }
            attemptRecovery()
        }
    }

    /**
     * Cancel playback and return to standby.
     */
    fun cancelAndReturnToStandby() {
        Log.i(TAG, "User cancelled playback, returning to standby")
        stop()
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    private fun cancelAutoRetry() {
        autoRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        autoRetryRunnable = null
    }

    fun attachPlayerView(playerView: PlayerView) {
        runOnMain {
            initPlayerOnMainThread()
            playerView.player = exoPlayer
            val player = exoPlayer ?: return@runOnMain
            if (player.currentTimeline.isEmpty) {
                activeUrl?.let { url ->
                    lastKnownPositionMs = if (lastKnownPositionMs > 0L) {
                        lastKnownPositionMs
                    } else {
                        player.currentPosition.coerceAtLeast(0L)
                    }
                    prepareCurrentMediaItem(player, lastKnownPositionMs)
                }
            } else if (!player.isPlaying) {
                resumePlaybackOnFirstFrame = true
                player.seekTo(player.currentPosition.coerceAtLeast(0L))
            }
        }
    }

    fun detachPlayerView(playerView: PlayerView) {
        runOnMain {
            playerView.player = null
        }
    }

    fun addListener(listener: TvPlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TvPlayerListener) {
        listeners.remove(listener)
    }

    /**
     * Start playing video stream URL at specified start position.
     * Called by both AirPlay POST /play and DLNA SetAVTransportURI / Play.
     */
    fun play(url: String, startPositionMs: Long = 0L) {
        val previousUrl = currentUrl
        if (!previousUrl.isNullOrBlank() && previousUrl != url) {
            Log.w(TAG, "New play request interrupting previous playback: previousUrl=$previousUrl, newUrl=$url")
        }
        Log.i(TAG, "Play request received: url=$url, startPositionMs=$startPositionMs")
        currentUrl = url
        activeUrl = url
        lastKnownPositionMs = startPositionMs.coerceAtLeast(0L)
        resetRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        autoRetryCount = 0
        isBuffering = false
        bufferingMessage = ""
        bufferingStartTime = 0L

        runOnMain {
            initPlayerOnMainThread()
            val player = exoPlayer ?: return@runOnMain
            try {
                prepareCurrentMediaItem(player, startPositionMs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback for $url", e)
                listeners.forEach { it.onError("Play error: ${e.message}") }
            }
        }
    }

    fun pause() {
        Log.i(TAG, "Pause request received")
        runOnMain {
            exoPlayer?.playWhenReady = false
        }
    }

    fun resume() {
        Log.i(TAG, "Resume request received")
        runOnMain {
            initPlayerOnMainThread()
            val player = exoPlayer
            if (player == null) {
                activeUrl?.let { play(it, 0L) }
                return@runOnMain
            }

            if (player.mediaItemCount == 0 && activeUrl != null && currentUrl == activeUrl) {
                prepareCurrentMediaItem(player, lastKnownPositionMs)
            } else {
                player.playWhenReady = true
            }
        }
    }

    fun togglePlayPause() {
        runOnMain {
            val player = exoPlayer ?: return@runOnMain
            val endedAtPosition = player.playbackState == Player.STATE_ENDED
            if (endedAtPosition) {
                player.seekToDefaultPosition()
            }
            if (player.mediaItemCount == 0 && activeUrl != null && currentUrl == activeUrl) {
                prepareCurrentMediaItem(player, lastKnownPositionMs)
                return@runOnMain
            }
            val shouldPlay = !player.isPlaying || endedAtPosition
            player.playWhenReady = shouldPlay
            Log.i(TAG, "Toggle play/pause: isPlaying now -> $shouldPlay")
        }
    }

    fun seekTo(positionMs: Long) {
        Log.i(TAG, "SeekTo request received: $positionMs ms")
        runOnMain {
            exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        Log.i(TAG, "SetPlaybackSpeed: $speed")
        runOnMain {
            exoPlayer?.setPlaybackSpeed(speed)
        }
    }

    fun getPlaybackSpeed(): Float {
        return exoPlayer?.playbackParameters?.speed ?: 1.0f
    }

    fun setNextUrl(url: String?) {
        Log.i(TAG, "SetNextUrl received: $url")
        nextUrl = url
    }

    fun playNext(): Boolean {
        val next = nextUrl
        if (!next.isNullOrBlank()) {
            Log.i(TAG, "PlayNext triggered: $next")
            nextUrl = null
            play(next, 0L)
            return true
        }
        return false
    }

    fun stop() {
        Log.i(TAG, "Stop request received")
        currentUrl = null
        activeUrl = null
        cancelRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        nextUrl = null
        autoRetryCount = 0
        isBuffering = false
        bufferingMessage = ""
        bufferingStartTime = 0L
        runOnMain {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            currentState = PlayerState.IDLE
            listeners.forEach { it.onPlaybackStopped() }
        }
    }

    fun release() {
        runOnMain {
            activeUrl = null
            cancelRecovery()
            cancelBufferingTimeout()
            cancelAutoRetry()
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    fun isEnded(): Boolean {
        return currentState == PlayerState.ENDED || exoPlayer?.playbackState == Player.STATE_ENDED
    }

    fun isReady(): Boolean {
        return exoPlayer?.playbackState == Player.STATE_READY
    }

    fun getDurationMs(): Long {
        val dur = exoPlayer?.duration ?: 0L
        return if (dur > 0) dur else 0L
    }

    fun getCurrentPositionMs(): Long {
        val pos = exoPlayer?.currentPosition ?: 0L
        return if (pos > 0) pos else 0L
    }

    /**
     * Get buffering duration in milliseconds.
     */
    fun getBufferingDurationMs(): Long {
        if (bufferingStartTime == 0L) return 0L
        return System.currentTimeMillis() - bufferingStartTime
    }

    private fun prepareCurrentMediaItem(player: ExoPlayer, startPositionMs: Long) {
        val url = activeUrl ?: return
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)), startPositionMs)
            player.prepare()
            player.playWhenReady = true
            listeners.forEach { it.onPlaybackStarted(url) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare playback for $url", e)
            notifyError("Play error: ${e.message}")
        }
    }

    private fun scheduleRecovery() {
        if (recoveryRunnable != null || activeUrl.isNullOrBlank()) return
        if (recoveryAttempts >= TvPlayerConfig.Recovery.MAX_ATTEMPTS) {
            val url = activeUrl
            activeUrl = null
            cancelRecovery()
            cancelBufferingTimeout()
            notifyError("Playback failed after recovery attempts")
            Log.e(TAG, "Recovery exhausted for $url")
            return
        }

        val delayMs = minOf(
            TvPlayerConfig.Recovery.INITIAL_DELAY_MS shl recoveryAttempts,
            TvPlayerConfig.Recovery.MAX_DELAY_MS
        )
        recoveryAttempts++
        Log.w(TAG, "Scheduling playback recovery #$recoveryAttempts in ${delayMs}ms")
        currentState = PlayerState.BUFFERING
        listeners.forEach { it.onStateChanged(PlayerState.BUFFERING) }

        val runnable = Runnable {
            recoveryRunnable = null
            val player = exoPlayer
            val url = activeUrl
            if (player != null && !url.isNullOrBlank()) {
                prepareCurrentMediaItem(player, lastKnownPositionMs)
            } else if (url != null) {
                mainHandler.post { play(url, 0L) }
            }
        }
        recoveryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun resetRecovery() {
        recoveryAttempts = 0
        cancelRecovery()
    }

    private fun cancelRecovery() {
        recoveryRunnable?.let(mainHandler::removeCallbacks)
        recoveryRunnable = null
    }

    private fun notifyError(message: String) {
        currentState = PlayerState.ERROR
        listeners.forEach { it.onError(message) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "TvPlayerManager"

        @Volatile
        private var INSTANCE: TvPlayerManager? = null

        fun getInstance(context: Context): TvPlayerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TvPlayerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
