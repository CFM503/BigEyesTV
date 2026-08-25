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

    private var activeUrl: String? = null
    private var lastKnownPositionMs = 0L
    private var recoveryAttempts = 0
    private var recoveryRunnable: Runnable? = null

    init {
        mainHandler.post {
            initPlayerOnMainThread()
        }
    }

    private fun initPlayerOnMainThread() {
        if (exoPlayer != null) return
        val player = ExoPlayer.Builder(context).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                    val position = exoPlayer?.currentPosition ?: 0L
                    if (position > 0L) {
                        lastKnownPositionMs = position
                    }
                    resetRecovery()
                }

                if (playbackState == Player.STATE_ENDED) {
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

    fun attachPlayerView(playerView: PlayerView) {
        runOnMain {
            initPlayerOnMainThread()
            playerView.player = exoPlayer
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
        Log.i(TAG, "Play request received: url=$url, startPositionMs=$startPositionMs")
        currentUrl = url
        activeUrl = url
        lastKnownPositionMs = startPositionMs.coerceAtLeast(0L)
        resetRecovery()
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
        nextUrl = null
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
