package com.bigeyes.tv.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.bigeyes.tv.config.TvPlayerConfig
import com.bigeyes.tv.player.model.PlaybackState

/**
 * Concrete implementation of PlayerEngine backed by AndroidX Media3 ExoPlayer.
 * Robustly manages player lifecycle, intelligent buffering recovery, and UI attachment.
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(private val context: Context) : PlayerEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var listener: PlayerEngineListener? = null

    /**
     * Shared HTTP factory holding the anti-hotlink headers of the current episode.
     * Headers are re-applied before every prepare() so playlist + segment requests carry them.
     */
    private val httpRequestFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36")
        .setAllowCrossProtocolRedirects(true)
    private val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpRequestFactory)

    @Volatile
    private var currentUrl: String? = null

    @Volatile
    private var activeUrl: String? = null

    @Volatile
    private var currentState: PlaybackState = PlaybackState.IDLE

    @Volatile
    private var isBuffering: Boolean = false

    /** Anti-hotlink headers applied to every HTTP request of the current media. */
    @Volatile
    private var requestHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var cachedSpeed: Float = 1.0f

    @Volatile
    private var cachedPositionMs: Long = 0L

    @Volatile
    private var cachedDurationMs: Long = 0L

    @Volatile
    private var cachedIsPlaying: Boolean = false

    @Volatile
    private var cachedIsReady: Boolean = false

    @Volatile
    private var cachedIsEnded: Boolean = false

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
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (resumePlaybackOnFirstFrame) {
                    resumePlaybackOnFirstFrame = false
                    player.playWhenReady = true
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncSnapshotsOnMain(player)
                if (playbackState == Player.STATE_READY) {
                    val position = player.currentPosition
                    if (position > 0L) {
                        lastKnownPositionMs = position
                    }
                    resetRecovery()
                } else if (playbackState == Player.STATE_BUFFERING) {
                    val position = player.currentPosition
                    if (position > 0L) {
                        lastKnownPositionMs = position
                    }
                }

                if (playbackState == Player.STATE_ENDED) {
                    cachedIsEnded = true
                    cachedIsPlaying = false
                    cachedIsReady = false
                    cancelRecovery()
                    cancelBufferingTimeout()
                    cancelAutoRetry()
                    cancelBufferingShowIndicator()
                    isBuffering = false
                    bufferingStartTime = 0L
                    currentState = PlaybackState.COMPLETED
                    listener?.onEngineStateChanged(PlaybackState.COMPLETED)
                    listener?.onPlaybackCompleted()
                    Log.i(TAG, "ExoPlayer reached STATE_ENDED, forwarded onPlaybackCompleted")
                    return
                }

                if (playbackState == Player.STATE_IDLE) {
                    if (currentState == PlaybackState.LOADING || currentState == PlaybackState.BUFFERING) {
                        // Suppress intermediate IDLE state while loading/preparing new media or waiting URL
                        return
                    }
                    if (currentState != PlaybackState.STOPPED && activeUrl != null && player.playerError != null) {
                        scheduleRecovery()
                        return
                    }
                }

                val newState = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> if (player.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
                    else -> PlaybackState.IDLE
                }

                handleBufferingStateChange(newState)
                val previousState = currentState
                currentState = newState
                listener?.onEngineStateChanged(newState)
                if (newState == PlaybackState.PLAYING && previousState != PlaybackState.PLAYING) {
                    activeUrl?.let { listener?.onPlaybackStarted(it) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncSnapshotsOnMain(exoPlayer)
                if (exoPlayer?.playbackState == Player.STATE_READY) {
                    val newState = if (isPlaying) PlaybackState.PLAYING else PlaybackState.PAUSED
                    val previousState = currentState
                    currentState = newState
                    listener?.onEngineStateChanged(newState)
                    if (newState == PlaybackState.PLAYING && previousState != PlaybackState.PLAYING) {
                        activeUrl?.let { listener?.onPlaybackStarted(it) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                syncSnapshotsOnMain(exoPlayer)
                val msg = error.message ?: "Playback error: ${error.errorCodeName}"
                Log.e(TAG, "ExoPlayer error: $msg (code: ${error.errorCode})", error)
                cancelBufferingTimeout()
                cancelAutoRetry()
                cancelBufferingShowIndicator()
                isBuffering = false

                val httpCause = findHttpInvalidResponseCodeException(error)
                val isHttp4xx = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    (httpCause != null && httpCause.responseCode in 400..499)

                if (isHttp4xx) {
                    val httpCode = httpCause?.responseCode
                    val userMsg = "视频地址已失效或受防盗链保护 (HTTP ${httpCode ?: 403})，请在手机端重新嗅探投屏"
                    Log.w(TAG, "Fast-failing HTTP 4xx error without recovery: $userMsg")
                    notifyError(userMsg)
                    return
                }

                if (activeUrl == null) {
                    notifyError(msg)
                    return
                }
                exoPlayer?.currentPosition?.takeIf { it > 0L }?.let {
                    lastKnownPositionMs = it
                    cachedPositionMs = it
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
                listener?.onNetworkRetry(
                    autoRetryCount,
                    TvPlayerConfig.Buffering.MAX_AUTO_RETRIES
                )
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
                listener?.onNetworkInterrupted(lastKnownPositionMs)
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
        runOnMain {
            Log.i(TAG, "Manual retry requested")
            autoRetryCount = 0
            cancelBufferingTimeout()
            cancelAutoRetry()
            cancelBufferingShowIndicator()

            if (activeUrl != null) {
                isBuffering = true
                listener?.onBufferingStateChanged(true, "正在重新连接...")
                attemptRecovery()
            }
        }
    }

    override fun play(url: String, startPositionMs: Long) {
        Log.i(TAG, "Engine play url=$url, startPositionMs=$startPositionMs")
        val safeStartPos = startPositionMs.coerceAtLeast(0L)
        currentUrl = url
        activeUrl = url
        lastKnownPositionMs = safeStartPos
        cachedPositionMs = safeStartPos
        cachedDurationMs = 0L
        cachedIsPlaying = false
        cachedIsReady = false
        cachedIsEnded = false
        resetRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        cancelBufferingShowIndicator()
        autoRetryCount = 0
        isBuffering = false
        bufferingStartTime = 0L
        resumePlaybackOnFirstFrame = false

        runOnMain {
            initPlayerOnMainThread()
            val player = exoPlayer ?: return@runOnMain
            try {
                prepareCurrentMediaItem(player, safeStartPos)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback for $url", e)
                notifyError("Play error: ${e.message}")
            }
        }
    }

    override fun pause() {
        runOnMain {
            resumePlaybackOnFirstFrame = false
            exoPlayer?.playWhenReady = false
            syncSnapshotsOnMain(exoPlayer)
        }
    }

    override fun setRequestHeaders(headers: Map<String, String>) {
        requestHeaders = headers
        runOnMain {
            httpRequestFactory.setDefaultRequestProperties(headers)
            if (headers.isNotEmpty()) {
                Log.d(TAG, "Applied request headers: ${headers.keys.joinToString()}")
            }
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
            syncSnapshotsOnMain(player)
        }
    }

    override fun stop() {
        currentUrl = null
        activeUrl = null
        resumePlaybackOnFirstFrame = false
        cancelRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        cancelBufferingShowIndicator()
        autoRetryCount = 0
        isBuffering = false
        bufferingStartTime = 0L
        lastKnownPositionMs = 0L
        cachedPositionMs = 0L
        cachedDurationMs = 0L
        cachedSpeed = 1.0f
        cachedIsPlaying = false
        cachedIsReady = false
        cachedIsEnded = false

        runOnMain {
            exoPlayer?.setPlaybackSpeed(1.0f)
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            currentState = PlaybackState.STOPPED
            listener?.onEngineStateChanged(PlaybackState.STOPPED)
        }
    }

    override fun resetForPendingUrl() {
        currentUrl = null
        activeUrl = null
        resumePlaybackOnFirstFrame = false
        cancelRecovery()
        cancelBufferingTimeout()
        cancelAutoRetry()
        cancelBufferingShowIndicator()
        autoRetryCount = 0
        isBuffering = true
        bufferingStartTime = 0L
        lastKnownPositionMs = 0L
        cachedPositionMs = 0L
        cachedDurationMs = 0L
        cachedIsPlaying = false
        cachedIsReady = false
        cachedIsEnded = false

        runOnMain {
            currentState = PlaybackState.BUFFERING
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            listener?.onEngineStateChanged(PlaybackState.BUFFERING)
        }
    }

    override fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        lastKnownPositionMs = safePos
        cachedPositionMs = safePos
        runOnMain {
            exoPlayer?.seekTo(safePos)
            syncSnapshotsOnMain(exoPlayer)
        }
    }

    override fun release() {
        runOnMain {
            activeUrl = null
            currentUrl = null
            resumePlaybackOnFirstFrame = false
            cancelRecovery()
            cancelBufferingTimeout()
            cancelAutoRetry()
            cancelBufferingShowIndicator()
            cachedIsPlaying = false
            cachedIsReady = false
            cachedIsEnded = false
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        cachedSpeed = safeSpeed
        runOnMain {
            exoPlayer?.setPlaybackSpeed(safeSpeed)
            syncSnapshotsOnMain(exoPlayer)
        }
    }

    override fun getPlaybackSpeed(): Float {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.playbackParameters?.speed?.let { cachedSpeed = it }
        }
        return cachedSpeed
    }

    override fun getCurrentPositionMs(): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val pos = exoPlayer?.currentPosition ?: cachedPositionMs
            cachedPositionMs = if (pos > 0L) pos else 0L
        }
        return cachedPositionMs
    }

    override fun getDurationMs(): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val dur = exoPlayer?.duration ?: cachedDurationMs
            if (dur > 0L) {
                cachedDurationMs = dur
            }
        }
        return cachedDurationMs
    }

    override fun isPlaying(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedIsPlaying = exoPlayer?.isPlaying ?: false
        }
        return cachedIsPlaying
    }

    override fun isEnded(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedIsEnded = currentState == PlaybackState.COMPLETED ||
                exoPlayer?.playbackState == Player.STATE_ENDED
        }
        return cachedIsEnded
    }

    override fun isReady(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cachedIsReady = exoPlayer?.playbackState == Player.STATE_READY
        }
        return cachedIsReady
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
            } else if (player.playWhenReady && player.playbackState != Player.STATE_ENDED && !player.isPlaying) {
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
            // Re-apply anti-hotlink headers before every (re)prepare so recovery keeps them
            httpRequestFactory.setDefaultRequestProperties(requestHeaders)
            // Replace media item directly without stop() to avoid resetting state to IDLE
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)), startPositionMs)
            player.prepare()
            player.playWhenReady = true
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
            listener?.onNetworkInterrupted(lastKnownPositionMs)
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

    private fun cancelBufferingShowIndicator() {
        bufferingShowIndicatorRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferingShowIndicatorRunnable = null
    }

    private fun syncSnapshotsOnMain(player: ExoPlayer?) {
        if (player == null) {
            cachedIsPlaying = false
            cachedIsReady = false
            return
        }
        val pos = player.currentPosition
        if (pos >= 0L) cachedPositionMs = pos
        val dur = player.duration
        if (dur > 0L) cachedDurationMs = dur
        cachedSpeed = player.playbackParameters.speed
        cachedIsPlaying = player.isPlaying
        cachedIsReady = player.playbackState == Player.STATE_READY
        cachedIsEnded = currentState == PlaybackState.COMPLETED || player.playbackState == Player.STATE_ENDED
    }

    private fun findHttpInvalidResponseCodeException(
        root: Throwable?
    ): androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException? {
        var cursor = root
        var depth = 0
        while (cursor != null && depth < 12) {
            if (cursor is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return cursor
            }
            cursor = cursor.cause
            depth++
        }
        return null
    }

    private fun notifyError(message: String) {
        cachedIsPlaying = false
        cachedIsReady = false
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
