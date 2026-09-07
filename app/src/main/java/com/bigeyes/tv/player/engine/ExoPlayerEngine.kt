package com.bigeyes.tv.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bigeyes.tv.config.TvPlayerConfig
import com.bigeyes.tv.player.model.PlaybackState

/**
 * Concrete implementation of PlayerEngine backed by AndroidX Media3 ExoPlayer.
 * Robustly manages player lifecycle, intelligent buffering recovery, and UI attachment.
 */
class ExoPlayerEngine(private val context: Context) : PlayerEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var listener: PlayerEngineListener? = null

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var activeUrl: String? = null

    @Volatile
    private var currentState: PlaybackState = PlaybackState.IDLE

    @Volatile
    private var isBuffering: Boolean = false

    private var lastKnownPositionMs = 0L
    private var recoveryAttempts = 0
    private var recoveryRunnable: Runnable? = null
    private var resumePlaybackOnFirstFrame = false

    // Buffering watchdog
    private var bufferingStartTime = 0L
    private var bufferingTimeoutRunnable: Runnable? = null
    private var bufferingShowIndicatorRunnable: Runnable? = null

    // Auto-retry
    private var autoRetryCount = 0
    private var autoRetryRunnable: Runnable? = null

    init {
        runOnMain {
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
                    currentState = PlaybackState.COMPLETED
                    listener?.onEngineStateChanged(PlaybackState.COMPLETED)
                    listener?.onPlaybackCompleted()
                    Log.i(TAG, "ExoPlayer reached STATE_ENDED, forwarded onPlaybackCompleted")
                    return
                }

                if (playbackState == Player.STATE_IDLE && activeUrl != null) {
                    scheduleRecovery()
                    return
                }

                val newState = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> if (player.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
                    else -> PlaybackState.IDLE
                }

                handleBufferingStateChange(newState)
                currentState = newState
                listener?.onEngineStateChanged(newState)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (exoPlayer?.playbackState == Player.STATE_READY) {
                    val newState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                    currentState = newState
                    listener?.onEngineStateChanged(newState)
                }
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
        Log.i(TAG, "ExoPlayerEngine initialized successfully.")
    }

    private fun handleBufferingStateChange(newState: PlaybackState) {
        when (newState) {
            PlaybackState.BUFFERING -> {
                if (bufferingStartTime == 0L) {
                    bufferingStartTime = System.currentTimeMillis()
                    startBufferingTimeout()
                }

                if (!isBuffering) {
                    bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
                    bufferingShowIndicatorRunnable = Runnable {
                        if (currentState == PlaybackState.BUFFERING) {
                            isBuffering = true
                            listener?.onBufferingStateChanged(true, "正在缓冲...")
                        }
                    }
                    mainHandler.postDelayed(
                        bufferingShowIndicatorRunnable!!,
                        TvPlayerConfig.Buffering.SHOW_INDICATOR_DELAY_MS
                    )
                }
            }
            PlaybackState.PLAYING, PlaybackState.PAUSED -> {
                cancelBufferingTimeout()
                cancelAutoRetry()
                if (isBuffering) {
                    isBuffering = false
                    bufferingStartTime = 0L
                    bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
                    listener?.onBufferingStateChanged(false, "")
                }
            }
            else -> {
                cancelBufferingTimeout()
                if (isBuffering) {
                    isBuffering = false
                    bufferingStartTime = 0L
                    bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
                }
            }
        }
    }

    private fun startBufferingTimeout() {
        cancelBufferingTimeout()
        bufferingTimeoutRunnable = Runnable {
            val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
            Log.w(TAG, "Buffering timeout detected: ${bufferingDuration}ms")

            if (autoRetryCount < TvPlayerConfig.Buffering.MAX_AUTO_RETRIES) {
                autoRetryCount++
                val message = "网络不稳定，正在尝试恢复... ($autoRetryCount/${TvPlayerConfig.Buffering.MAX_AUTO_RETRIES})"
                isBuffering = true
                listener?.onBufferingStateChanged(true, message)

                autoRetryRunnable = Runnable {
                    if (activeUrl != null && currentState == PlaybackState.BUFFERING) {
                        Log.i(TAG, "Auto-retry #$autoRetryCount attempting recovery")
                        attemptRecovery()
                    }
                }
                mainHandler.postDelayed(autoRetryRunnable!!, TvPlayerConfig.Buffering.AUTO_RETRY_INTERVAL_MS)
            } else {
                isBuffering = true
                val message = "网络连接中断"
                listener?.onBufferingStateChanged(true, message)
                notifyError(message)
            }
        }
        mainHandler.postDelayed(bufferingTimeoutRunnable!!, TvPlayerConfig.Buffering.TIMEOUT_MS)
    }

    private fun attemptRecovery() {
        val url = activeUrl ?: return
        val player = exoPlayer ?: return
        try {
            Log.i(TAG, "Attempting recovery from position: $lastKnownPositionMs ms")
            prepareCurrentMediaItem(player, lastKnownPositionMs)
        } catch (e: Exception) {
            Log.e(TAG, "Recovery attempt failed: ${e.message}")
            scheduleRecovery()
        }
    }

    override fun manualRetry() {
        Log.i(TAG, "Manual retry requested")
        autoRetryCount = 0
        cancelBufferingTimeout()
        cancelAutoRetry()

        if (activeUrl != null) {
            isBuffering = true
            listener?.onBufferingStateChanged(true, "正在重新连接...")
            attemptRecovery()
        }
    }

    override fun play(url: String, startPositionMs: Long) {
        Log.i(TAG, "Engine play url=$url, startPositionMs=$startPositionMs")
        currentUrl = url
        activeUrl = url
        lastKnownPositionMs = startPositionMs.coerceAtLeast(0L)
        resetRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        autoRetryCount = 0
        isBuffering = false
        bufferingStartTime = 0L

        runOnMain {
            initPlayerOnMainThread()
            val player = exoPlayer ?: return@runOnMain
            try {
                prepareCurrentMediaItem(player, startPositionMs)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback for $url", e)
                notifyError("Play error: ${e.message}")
            }
        }
    }

    override fun pause() {
        runOnMain {
            exoPlayer?.playWhenReady = false
        }
    }

    override fun resume() {
        runOnMain {
            initPlayerOnMainThread()
            val player = exoPlayer
            if (player == null) {
                activeUrl?.let { play(it, 0L) }
                return@runOnMain
            }
            if (player.mediaItemCount == 0 && activeUrl != null) {
                prepareCurrentMediaItem(player, lastKnownPositionMs)
            } else {
                player.playWhenReady = true
            }
        }
    }

    override fun stop() {
        currentUrl = null
        activeUrl = null
        cancelRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        autoRetryCount = 0
        isBuffering = false
        bufferingStartTime = 0L

        runOnMain {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            currentState = PlaybackState.STOPPED
            listener?.onEngineStateChanged(PlaybackState.STOPPED)
        }
    }

    override fun seekTo(positionMs: Long) {
        runOnMain {
            val safePos = positionMs.coerceAtLeast(0L)
            lastKnownPositionMs = safePos
            exoPlayer?.seekTo(safePos)
        }
    }

    override fun release() {
        runOnMain {
            activeUrl = null
            currentUrl = null
            cancelRecovery()
            cancelBufferingTimeout()
            cancelAutoRetry()
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        runOnMain {
            exoPlayer?.setPlaybackSpeed(speed)
        }
    }

    override fun getPlaybackSpeed(): Float {
        return exoPlayer?.playbackParameters?.speed ?: 1.0f
    }

    override fun getCurrentPositionMs(): Long {
        val pos = exoPlayer?.currentPosition ?: 0L
        return if (pos > 0) pos else 0L
    }

    override fun getDurationMs(): Long {
        val dur = exoPlayer?.duration ?: 0L
        return if (dur > 0) dur else 0L
    }

    override fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    override fun isEnded(): Boolean {
        return currentState == PlaybackState.COMPLETED || exoPlayer?.playbackState == Player.STATE_ENDED
    }

    override fun isReady(): Boolean {
        return exoPlayer?.playbackState == Player.STATE_READY
    }

    override fun attachPlayerView(playerView: PlayerView) {
        runOnMain {
            initPlayerOnMainThread()
            playerView.player = exoPlayer
            val player = exoPlayer ?: return@runOnMain
            if (player.currentTimeline.isEmpty) {
                activeUrl?.let {
                    prepareCurrentMediaItem(player, lastKnownPositionMs)
                }
            } else if (!player.isPlaying) {
                resumePlaybackOnFirstFrame = true
                player.seekTo(player.currentPosition.coerceAtLeast(0L))
            }
        }
    }

    override fun detachPlayerView(playerView: PlayerView) {
        runOnMain {
            playerView.player = null
        }
    }

    override fun setListener(listener: PlayerEngineListener?) {
        this.listener = listener
    }

    private fun prepareCurrentMediaItem(player: ExoPlayer, startPositionMs: Long) {
        val url = activeUrl ?: return
        try {
            currentState = PlaybackState.LOADING
            listener?.onEngineStateChanged(PlaybackState.LOADING)
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)), startPositionMs)
            player.prepare()
            player.playWhenReady = true
            listener?.onPlaybackStarted(url)
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
        currentState = PlaybackState.BUFFERING
        listener?.onEngineStateChanged(PlaybackState.BUFFERING)

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

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    private fun cancelAutoRetry() {
        autoRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        autoRetryRunnable = null
    }

    private fun notifyError(message: String) {
        currentState = PlaybackState.ERROR
        listener?.onError(message)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "ExoPlayerEngine"
    }
}
