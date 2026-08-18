package com.bigeyes.tv.player

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
    var currentState: PlayerState = PlayerState.IDLE
        private set

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
                val newState = when (playbackState) {
                    Player.STATE_IDLE -> PlayerState.IDLE
                    Player.STATE_BUFFERING -> PlayerState.BUFFERING
                    Player.STATE_READY -> PlayerState.READY
                    Player.STATE_ENDED -> PlayerState.ENDED
                    else -> PlayerState.IDLE
                }
                currentState = newState
                listeners.forEach { it.onStateChanged(newState) }
                Log.d(TAG, "ExoPlayer state changed: $newState (playing=${player.isPlaying})")
            }

            override fun onPlayerError(error: PlaybackException) {
                currentState = PlayerState.ERROR
                val msg = error.message ?: "Playback error: ${error.errorCodeName}"
                Log.e(TAG, "ExoPlayer error: $msg", error)
                listeners.forEach { it.onError(msg) }
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
        runOnMain {
            initPlayerOnMainThread()
            val player = exoPlayer ?: return@runOnMain
            try {
                val mediaItem = MediaItem.fromUri(Uri.parse(url))
                player.setMediaItem(mediaItem, startPositionMs)
                player.prepare()
                player.playWhenReady = true
                listeners.forEach { it.onPlaybackStarted(url) }
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
            exoPlayer?.playWhenReady = true
        }
    }

    fun togglePlayPause() {
        runOnMain {
            val player = exoPlayer ?: return@runOnMain
            val shouldPlay = !player.isPlaying
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

    fun stop() {
        Log.i(TAG, "Stop request received")
        currentUrl = null
        runOnMain {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            currentState = PlayerState.IDLE
            listeners.forEach { it.onPlaybackStopped() }
        }
    }

    fun release() {
        runOnMain {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
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
